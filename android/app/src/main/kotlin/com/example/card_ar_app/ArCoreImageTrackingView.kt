package com.example.card_ar_app

import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import android.view.View
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
    
    // Video rendering with custom OpenGL renderer (fixed version)
    private val videoRenderers = mutableMapOf<String, ArVideoRenderer>()
    
    init {
        methodChannel.setMethodCallHandler(this)
        
        // Initialize ARSceneView
        arSceneView = ArSceneView(context).apply {
            // Add update listener to render videos each frame
            scene.addOnUpdateListener { frameTime ->
                updateVideoTextures()
            }
        }
        
        setupARSession()
    }
    
    /**
     * Update và render tất cả video textures mỗi frame
     */
    private fun updateVideoTextures() {
        val frame = arSceneView?.arFrame
        if (frame == null || frame.camera.trackingState != TrackingState.TRACKING) {
            return
        }
        
        val viewMatrix = FloatArray(16)
        val projectionMatrix = FloatArray(16)
        frame.camera.getViewMatrix(viewMatrix, 0)
        frame.camera.getProjectionMatrix(projectionMatrix, 0, 0.1f, 100f)
        
        // Render all active videos
        for ((imageId, renderer) in videoRenderers) {
            try {
                renderer.draw(viewMatrix, projectionMatrix)
            } catch (e: Exception) {
                // Ignore rendering errors silently
            }
        }
    }
    
    private fun setupARSession() {
        try {
            session = Session(activity).apply {
                val config = Config(this).apply {
                    focusMode = Config.FocusMode.AUTO
                    updateMode = Config.UpdateMode.LATEST_CAMERA_IMAGE
                    planeFindingMode = Config.PlaneFindingMode.DISABLED
                    lightEstimationMode = Config.LightEstimationMode.AMBIENT_INTENSITY
                }
                configure(config)
            }
            
            arSceneView?.setupSession(session)
            
            // Add update listener to track augmented images AND render videos
            arSceneView?.scene?.addOnUpdateListener { frameTime ->
                val frame = arSceneView?.arFrame ?: return@addOnUpdateListener
                
                if (frame.camera.trackingState != TrackingState.TRACKING) {
                    return@addOnUpdateListener
                }
                
                // Check for detected augmented images
                val updatedImages = frame.getUpdatedTrackables(AugmentedImage::class.java)
                
                for (augmentedImage in updatedImages) {
                    when (augmentedImage.trackingState) {
                        TrackingState.TRACKING -> {
                            if (!detectedImages.contains(augmentedImage.name)) {
                                detectedImages.add(augmentedImage.name)
                                Log.d(TAG, "🆕 New image detected in tracking loop: ${augmentedImage.name}")
                                onImageDetected(augmentedImage)
                            } else {
                                // Update video position continuously (silently)
                                updateVideoPosition(augmentedImage, frame)
                            }
                        }
                        TrackingState.STOPPED -> {
                            Log.w(TAG, "⏹️ Image tracking stopped: ${augmentedImage.name}")
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
            }
            
            Log.i(TAG, "✓ AR Session ready")
        } catch (e: Exception) {
            Log.e(TAG, "Error setting up AR session: ${e.message}", e)
        }
    }
    
    private fun onImageDetected(augmentedImage: AugmentedImage) {
        Log.i(TAG, "========================================")
        Log.i(TAG, "🎯 Image detected: ${augmentedImage.name}")
        Log.i(TAG, "📏 Size: ${augmentedImage.extentX}m x ${augmentedImage.extentZ}m")
        Log.i(TAG, "📍 Position: (${augmentedImage.centerPose.tx()}, ${augmentedImage.centerPose.ty()}, ${augmentedImage.centerPose.tz()})")
        
        // Create anchor at image center
        val anchor = augmentedImage.createAnchor(augmentedImage.centerPose)
        val anchorNode = AnchorNode(anchor).apply {
            setParent(arSceneView?.scene)
        }
        
        augmentedImageAnchors[augmentedImage.name] = anchorNode
        Log.d(TAG, "✓ Anchor node created and attached to scene")
        
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
            Log.d(TAG, "✓ Notified Flutter about image detection")
        }
        
        // Create video plane at image position
        createVideoPlaneAtImage(augmentedImage, anchorNode)
    }
    
    private var lastPositionUpdateTime = 0L
    
    private fun updateVideoPosition(image: AugmentedImage, frame: Frame) {
        // Throttle position updates to reduce log spam
        val now = System.currentTimeMillis()
        if (now - lastPositionUpdateTime < 100) return // Update mỗi 100ms thôi
        
        lastPositionUpdateTime = now
        
        // Update video position để track thẻ bài
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
            // Không còn tạo visual plane nữa - video sẽ được render trực tiếp
            Log.i(TAG, "✓ Ready to render video for: ${image.name}")
            
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
            val anchorNode = augmentedImageAnchors[imageId]
            if (anchorNode == null) {
                Log.e(TAG, "❌ No anchor node found for image: $imageId")
                return
            }

            // Get anchor from node
            val anchor = (anchorNode as? AnchorNode)?.anchor
            if (anchor == null) {
                Log.e(TAG, "❌ No anchor found in anchor node")
                return
            }

            Log.i(TAG, "========================================")
            Log.i(TAG, "🎬 Starting video playback for: $imageId")
            Log.i(TAG, "📹 Video path: $videoPath")
            Log.i(TAG, "⚓ Anchor: $anchor")

            // Create custom OpenGL video renderer
            val videoRenderer = ArVideoRenderer(context)
            videoRenderers[imageId] = videoRenderer

            val sceneView = arSceneView
            if (sceneView == null) {
                Log.e(TAG, "❌ ARSceneView is null, cannot initialize renderer")
                videoRenderers.remove(imageId)
                return
            }

            sceneView.queueEvent {
                try {
                    // Initialize GL resources on the GL thread
                    videoRenderer.initializeGl()
                    Log.d(TAG, "✓ OpenGL initialized on GL thread")

                    activity.runOnUiThread {
                        try {
                            // Initialize video (MediaPlayer setup happens on main thread internally)
                            val success = videoRenderer.initializeVideo(
                                videoPath,
                                anchor,
                                0.15f,
                                onVideoPrepared = {
                                    Log.i(TAG, "========================================")
                                    Log.i(TAG, "🎉 SUCCESS! Video renderer initialized for $imageId")
                                    Log.i(TAG, "========================================")
                                },
                                onVideoError = { error ->
                                    Log.e(TAG, "❌ Video initialization error: ${error.message}", error)
                                    videoRenderers.remove(imageId)
                                }
                            )

                            if (!success) {
                                Log.e(TAG, "❌ Failed to initialize video renderer")
                                videoRenderers.remove(imageId)
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "❌ Error initializing video on UI thread: ${e.message}", e)
                            videoRenderers.remove(imageId)
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "❌ Error initializing video on GL thread: ${e.message}", e)
                    videoRenderers.remove(imageId)
                }
            }

        } catch (e: Exception) {
            Log.e(TAG, "❌ Fatal error in playVideoForImage: ${e.message}", e)
        }
    }

    private fun stopVideoForImage(imageId: String) {
        // Cleanup video renderer
        videoRenderers[imageId]?.let { renderer ->
            arSceneView?.queueEvent {
                try {
                    renderer.cleanup()
                } catch (e: Exception) {
                    Log.e(TAG, "Error cleaning up video renderer: ${e.message}")
                }
            }
        }
        videoRenderers.remove(imageId)

        Log.i(TAG, "✓ Stopped video for $imageId")
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


