package com.example.card_ar_app

import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.SurfaceTexture
import android.media.MediaPlayer
import android.opengl.GLSurfaceView
import android.util.Log
import android.view.Surface
import android.view.View
import com.google.ar.core.Anchor
import com.google.ar.core.AugmentedImage
import com.google.ar.core.AugmentedImageDatabase
import com.google.ar.core.Config
import com.google.ar.core.Frame
import com.google.ar.core.Pose
import com.google.ar.core.Session
import com.google.ar.core.TrackingState
import com.google.ar.core.exceptions.CameraNotAvailableException
import com.google.ar.sceneform.AnchorNode
import com.google.ar.sceneform.ArSceneView
import com.google.ar.sceneform.Node
import com.google.ar.sceneform.math.Vector3
import com.google.ar.sceneform.rendering.ExternalTexture
import com.google.ar.sceneform.rendering.ModelRenderable
import com.google.ar.sceneform.rendering.Renderable
import io.flutter.plugin.common.BinaryMessenger
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import io.flutter.plugin.platform.PlatformView
import java.io.ByteArrayInputStream
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/**
 * Custom ARCore view với Augmented Images tracking
 * Hiển thị video texture tại vị trí thẻ bài được detect
 */
class ArCoreImageTrackingView(
    private val activity: Activity,
    private val context: Context,
    messenger: BinaryMessenger,
    id: Int
) : PlatformView, MethodChannel.MethodCallHandler {
    
    companion object {
        private const val TAG = "ArCoreImageTracking"
    }
    
    private val methodChannel = MethodChannel(messenger, "ar_image_tracking_$id")
    private var arSceneView: ArSceneView? = null
    private var session: Session? = null
    
    // Augmented images tracking
    private val augmentedImageAnchors = mutableMapOf<String, AnchorNode>()
    private val detectedImages = mutableSetOf<String>()
    
    // Reference images database
    private val referenceImageBytes = mutableMapOf<String, ByteArray>()
    
    // Video rendering with raw OpenGL (ArVideoRenderer)
    private val videoRenderers = mutableMapOf<String, ArVideoRenderer>()
    
    // Pending videos waiting for GL initialization
    private data class PendingVideo(val imageId: String, val videoPath: String, val anchor: Anchor)
    private val pendingVideos = mutableListOf<PendingVideo>()
    
    private var isGlInitialized = false
    
    init {
        methodChannel.setMethodCallHandler(this)
        
        // Initialize ARSceneView
        arSceneView = ArSceneView(context)
        
        setupARSession()
    }
    
    private fun setupARSession() {
        try {
            session = Session(activity).apply {
                val config = Config(this).apply {
                    focusMode = Config.FocusMode.AUTO
                    updateMode = Config.UpdateMode.LATEST_CAMERA_IMAGE
                    planeFindingMode = Config.PlaneFindingMode.DISABLED
                    lightEstimationMode = Config.LightEstimationMode.AMBIENT_INTENSITY
                    // Thêm depth mode để cải thiện tracking
                    depthMode = Config.DepthMode.AUTOMATIC
                    // Enable instant placement để tracking tốt hơn
                    instantPlacementMode = Config.InstantPlacementMode.LOCAL_Y_UP
                }
                configure(config)
            }
            
            arSceneView?.setupSession(session)
            
            // Add update listener to track augmented images AND render videos
            arSceneView?.scene?.addOnUpdateListener { frameTime ->
                try {
                    val frame = arSceneView?.arFrame ?: return@addOnUpdateListener
                    
                    // Không cần check tracking state ở đây vì có thể block image detection
                    // ARCore vẫn có thể detect images ngay cả khi tracking chưa hoàn hảo
                    
                    // Check for detected augmented images
                    val updatedImages = frame.getUpdatedTrackables(AugmentedImage::class.java)
                
                for (augmentedImage in updatedImages) {
                    when (augmentedImage.trackingState) {
                        TrackingState.TRACKING -> {
                            if (!detectedImages.contains(augmentedImage.name)) {
                                detectedImages.add(augmentedImage.name)
                                Log.i(TAG, "🆕 Image detected: ${augmentedImage.name}")
                                onImageDetected(augmentedImage)
                            } else {
                                // Update video position continuously (silently)
                                updateVideoPosition(augmentedImage, frame)
                            }
                        }
                        TrackingState.STOPPED -> {
                            Log.i(TAG, "⏹️ Tracking stopped: ${augmentedImage.name}")
                            detectedImages.remove(augmentedImage.name)
                            augmentedImageAnchors[augmentedImage.name]?.let {
                                arSceneView?.scene?.removeChild(it)
                            }
                            augmentedImageAnchors.remove(augmentedImage.name)
                            
                            // Stop and cleanup video
                            stopVideoForImage(augmentedImage.name)
                        }
                        else -> {
                            // PAUSED state - keep tracking
                        }
                    }
                }
                
                    // Render videos after processing frame
                    renderVideos()
                } catch (e: Exception) {
                    // Silent error to avoid spam
                    if (System.currentTimeMillis() % 5000 < 100) {
                        Log.e(TAG, "Frame update error: ${e.message}")
                    }
                }
            }
            
            Log.i(TAG, "✓ AR Session ready")
        } catch (e: Exception) {
            Log.e(TAG, "Error setting up AR session: ${e.message}", e)
        }
    }
    
    /**
     * Render tất cả videos với OpenGL trong AR frame update
     * This is called from the GL rendering thread
     */
    private fun renderVideos() {
        try {
            val frame = arSceneView?.arFrame ?: return
            
            // Initialize GL resources once on first render
            if (!isGlInitialized) {
                Log.i(TAG, "⚙️ GL thread ready - initializing resources...")
                isGlInitialized = true
            }
            
            // Process pending videos (that were waiting for GL to be ready)
            synchronized(pendingVideos) {
                if (pendingVideos.isNotEmpty()) {
                    Log.i(TAG, "📦 Processing ${pendingVideos.size} pending videos...")
                    val videosToProcess = pendingVideos.toList()
                    pendingVideos.clear()
                    
                    for (pending in videosToProcess) {
                        Log.i(TAG, "▶️ Initializing renderer for ${pending.imageId}")
                        initializeVideoRenderer(pending.imageId, pending.videoPath, pending.anchor)
                    }
                }
            }
            
            // Only render if we have videos
            if (videoRenderers.isEmpty()) {
                return
            }
            
            val viewMatrix = FloatArray(16)
            val projectionMatrix = FloatArray(16)
            frame.camera.getViewMatrix(viewMatrix, 0)
            frame.camera.getProjectionMatrix(projectionMatrix, 0, 0.1f, 100f)
            
            // Debug log để verify rendering
            if (System.currentTimeMillis() % 1000 < 50) {
                Log.d(TAG, "🎨 Rendering ${videoRenderers.size} video(s)")
            }
            
            // Render all active videos
            for ((imageId, renderer) in videoRenderers) {
                try {
                    renderer.draw(viewMatrix, projectionMatrix)
                } catch (e: Exception) {
                    Log.e(TAG, "❌ Render error for $imageId: ${e.message}")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ renderVideos error: ${e.message}", e)
        }
    }
    
    private fun onImageDetected(augmentedImage: AugmentedImage) {
        Log.i(TAG, "🎯 ${augmentedImage.name} - Size: ${augmentedImage.extentX}m x ${augmentedImage.extentZ}m")
        Log.i(TAG, "📍 Pose: tx=${augmentedImage.centerPose.tx()}, ty=${augmentedImage.centerPose.ty()}, tz=${augmentedImage.centerPose.tz()}")
        
        // Create anchor at image center
        val anchor = augmentedImage.createAnchor(augmentedImage.centerPose)
        Log.i(TAG, "⚓ Anchor created: ${anchor.trackingState}")
        
        val anchorNode = AnchorNode(anchor).apply {
            setParent(arSceneView?.scene)
        }
        
        augmentedImageAnchors[augmentedImage.name] = anchorNode
        
        // Get screen position của anchor
        val screenPos = getScreenPosition(augmentedImage.centerPose)
        
        // Notify Flutter với screen position
        activity.runOnUiThread {
            methodChannel.invokeMethod("onImageDetected", mapOf(
                "imageId" to augmentedImage.name,
                "extentX" to augmentedImage.extentX,
                "extentZ" to augmentedImage.extentZ,
                "screenX" to screenPos?.x,
                "screenY" to screenPos?.y,
                "centerX" to augmentedImage.centerPose.tx(),
                "centerY" to augmentedImage.centerPose.ty(),
                "centerZ" to augmentedImage.centerPose.tz()
            ))
        }
        
        // Create video plane at image position
        createVideoPlaneAtImage(augmentedImage, anchorNode)
    }
    
    private var lastPositionUpdateTime = 0L
    
    private fun updateVideoPosition(image: AugmentedImage, frame: Frame) {
        // Throttle position updates to reduce overhead
        val now = System.currentTimeMillis()
        if (now - lastPositionUpdateTime < 500) return // Update mỗi 500ms thôi
        
        lastPositionUpdateTime = now
        
        // Update video position để track thẻ bài (silent - no log)
        val screenPos = getScreenPosition(image.centerPose)
        
        if (screenPos != null) {
            activity.runOnUiThread {
                methodChannel.invokeMethod("onPositionUpdate", mapOf(
                    "imageId" to image.name,
                    "screenX" to screenPos.x,
                    "screenY" to screenPos.y
                ))
            }
        }
    }
    
    private fun getScreenPosition(pose: Pose): android.graphics.PointF? {
        val view = arSceneView ?: return null
        val camera = view.arFrame?.camera ?: return null
        
        try {
            // Get view and projection matrices
            val viewMatrix = FloatArray(16)
            val projMatrix = FloatArray(16)
            camera.getViewMatrix(viewMatrix, 0)
            camera.getProjectionMatrix(projMatrix, 0, 0.1f, 100f)
            
            // 3D position
            val worldPos = floatArrayOf(
                pose.tx(),
                pose.ty(),
                pose.tz(),
                1.0f
            )
            
            // Transform to clip space
            val clipPos = FloatArray(4)
            android.opengl.Matrix.multiplyMV(clipPos, 0, viewMatrix, 0, worldPos, 0)
            android.opengl.Matrix.multiplyMV(clipPos, 0, projMatrix, 0, clipPos, 0)
            
            // Perspective divide
            if (clipPos[3] != 0f) {
                val ndcX = clipPos[0] / clipPos[3]
                val ndcY = clipPos[1] / clipPos[3]
                
                // Convert to screen coordinates
                val screenX = (ndcX + 1) * 0.5f * view.width
                val screenY = (1 - ndcY) * 0.5f * view.height
                
                return android.graphics.PointF(screenX, screenY)
            }
        } catch (e: Exception) {
            // Silent error - tránh log spam
        }
        
        return null
    }
    
    
    private fun createVideoPlaneAtImage(image: AugmentedImage, anchorNode: AnchorNode) {
        try {
            // Notify Flutter that we're ready to play video
            activity.runOnUiThread {
                methodChannel.invokeMethod("onReadyToPlayVideo", mapOf(
                    "imageId" to image.name,
                    "extentX" to image.extentX,
                    "extentZ" to image.extentZ
                ))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error preparing video: ${e.message}")
        }
    }
    
    override fun onMethodCall(call: MethodCall, result: MethodChannel.Result) {
        when (call.method) {
            "addReferenceImage" -> {
                val imageId = call.argument<String>("imageId")
                val imageBytes = call.argument<ByteArray>("imageBytes")
                val physicalWidth = call.argument<Double>("physicalWidth")?.toFloat()
                
                if (imageId != null && imageBytes != null) {
                    referenceImageBytes[imageId] = imageBytes
                    Log.d(TAG, "Added reference image: $imageId")
                    result.success(true)
                } else {
                    result.error("INVALID_ARGS", "Missing required arguments", null)
                }
            }
            
            "setupAugmentedImages" -> {
                setupAugmentedImagesDatabase()
                result.success(true)
            }
            
            "playVideo" -> {
                val imageId = call.argument<String>("imageId")
                val videoPath = call.argument<String>("videoPath")
                
                if (imageId != null && videoPath != null) {
                    playVideoForImage(imageId, videoPath)
                    result.success(true)
                } else {
                    result.error("INVALID_ARGS", "Missing imageId or videoPath", null)
                }
            }
            
            "pauseVideo" -> {
                val imageId = call.argument<String>("imageId")
                if (imageId != null) {
                    videoRenderers[imageId]?.togglePlayPause()
                    result.success(true)
                } else {
                    result.error("INVALID_ARGS", "Missing imageId", null)
                }
            }
            
            "stopVideo" -> {
                val imageId = call.argument<String>("imageId")
                if (imageId != null) {
                    stopVideoForImage(imageId)
                    result.success(true)
                } else {
                    result.error("INVALID_ARGS", "Missing imageId", null)
                }
            }
            
            "resume" -> {
                onResume()
                result.success(null)
            }
            
            "pause" -> {
                onPause()
                result.success(null)
            }
            
            else -> {
                result.notImplemented()
            }
        }
    }
    
    private fun playVideoForImage(imageId: String, videoPath: String) {
        try {
            Log.i(TAG, "🎬 playVideoForImage called: $imageId, path: $videoPath")
            
            val anchorNode = augmentedImageAnchors[imageId]
            if (anchorNode == null) {
                Log.e(TAG, "❌ No anchor node found for image: $imageId")
                Log.e(TAG, "Available anchors: ${augmentedImageAnchors.keys}")
                return
            }

            // Get anchor from node
            val anchor = (anchorNode as? AnchorNode)?.anchor
            if (anchor == null) {
                Log.e(TAG, "❌ No anchor found in anchor node")
                return
            }
            
            Log.i(TAG, "⚓ Anchor state: ${anchor.trackingState}")

            Log.i(TAG, "🎬 Preparing video with ArVideoRenderer: $imageId")

            // Add to pending list - will be initialized on next render cycle when GL is ready
            synchronized(pendingVideos) {
                pendingVideos.add(PendingVideo(imageId, videoPath, anchor))
                Log.i(TAG, "📋 Video queued (${pendingVideos.size} pending)")
            }

        } catch (e: Exception) {
            Log.e(TAG, "❌ Fatal error in playVideoForImage: ${e.message}", e)
        }
    }
    
    /**
     * Initialize video renderer - called from GL thread in renderVideos()
     */
    private fun initializeVideoRenderer(imageId: String, videoPath: String, anchor: Anchor) {
        Log.i(TAG, "🔧 initializeVideoRenderer START for $imageId")
        
        try {
            // Check if already exists
            if (videoRenderers.containsKey(imageId)) {
                Log.w(TAG, "⚠️ Video renderer already exists for $imageId")
                return
            }
            
            // Create custom OpenGL video renderer
            val videoRenderer = ArVideoRenderer(context)
            Log.i(TAG, "✓ ArVideoRenderer instance created")
            
            // Initialize GL resources (we're on GL thread now)
            videoRenderer.initializeGl()
            Log.i(TAG, "✓ GL resources initialized for $imageId")
            
            // Add to map after GL init succeeds
            videoRenderers[imageId] = videoRenderer
            Log.i(TAG, "✓ Renderer added to map (${videoRenderers.size} total)")

            // Initialize video on main thread (MediaPlayer requires main thread)
            activity.runOnUiThread {
                try {
                    Log.i(TAG, "🎬 Starting video initialization on main thread...")
                    
                    // Get renderer from map
                    val renderer = videoRenderers[imageId]
                    if (renderer == null) {
                        Log.e(TAG, "❌ Renderer not found in map!")
                        return@runOnUiThread
                    }
                    
                    Log.i(TAG, "📹 Calling initializeVideo with path: $videoPath")
                    val success = renderer.initializeVideo(
                        videoPath,
                        anchor,
                        0.2f,  // 20cm width
                        onVideoPrepared = {
                            Log.i(TAG, "✅ ✅ ✅ Video ready and playing: $imageId")
                            // Notify Flutter
                            methodChannel.invokeMethod("onVideoStarted", mapOf(
                                "imageId" to imageId
                            ))
                        },
                        onVideoError = { error: Throwable ->
                            Log.e(TAG, "❌ Video error: ${error.message}", error)
                            videoRenderers.remove(imageId)
                        }
                    )

                    if (!success) {
                        Log.e(TAG, "❌ initializeVideo returned false")
                        videoRenderers.remove(imageId)
                    } else {
                        Log.i(TAG, "✓ initializeVideo returned true")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "❌ Error initializing video: ${e.message}", e)
                    videoRenderers.remove(imageId)
                }
            }

        } catch (e: Exception) {
            Log.e(TAG, "❌ Fatal error initializing renderer: ${e.message}", e)
            videoRenderers.remove(imageId)
        }
    }
    
    private fun stopVideoForImage(imageId: String) {
        videoRenderers[imageId]?.let { renderer ->
            try {
                renderer.cleanup()
            } catch (e: Exception) {
                Log.e(TAG, "Error cleaning up: ${e.message}")
            }
        }
        videoRenderers.remove(imageId)
    }
    
    private fun setupAugmentedImagesDatabase() {
        try {
            val currentSession = session ?: return
            
            val augmentedImageDatabase = AugmentedImageDatabase(currentSession)
            
            for ((imageId, imageBytes) in referenceImageBytes) {
                val bitmap = BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)
                if (bitmap != null) {
                    augmentedImageDatabase.addImage(imageId, bitmap)
                }
            }
            
            val config = Config(currentSession).apply {
                this.augmentedImageDatabase = augmentedImageDatabase
                focusMode = Config.FocusMode.AUTO
                updateMode = Config.UpdateMode.LATEST_CAMERA_IMAGE
                planeFindingMode = Config.PlaneFindingMode.DISABLED
            }
            
            currentSession.configure(config)
            
            Log.i(TAG, "✓ Configured ${augmentedImageDatabase.numImages} reference images")
        } catch (e: Exception) {
            Log.e(TAG, "Error setting up augmented images: ${e.message}", e)
        }
    }
    
    override fun getView(): View {
        return arSceneView ?: View(context)
    }
    
    override fun dispose() {
        onPause()
        
        // Cleanup all videos
        val imageIds = videoRenderers.keys.toList()
        for (imageId in imageIds) {
            stopVideoForImage(imageId)
        }
        
        arSceneView?.destroy()
        session?.close()
        session = null
    }
    
    private fun onResume() {
        try {
            arSceneView?.resume()
        } catch (e: CameraNotAvailableException) {
            Log.e(TAG, "Camera not available: ${e.message}", e)
        }
    }
    
    private fun onPause() {
        arSceneView?.pause()
    }
}


