package com.example.card_ar_app

import android.content.Context
import android.graphics.SurfaceTexture
import android.media.MediaPlayer
import android.net.Uri
import android.util.Log
import android.view.Surface
import java.io.File
import com.google.ar.core.Anchor
import com.google.ar.core.Frame
import com.google.ar.core.Plane
import com.google.ar.core.Session
import com.google.ar.core.TrackingState
import com.google.ar.sceneform.AnchorNode
import com.google.ar.sceneform.Node
import com.google.ar.sceneform.math.Quaternion
import com.google.ar.sceneform.math.Vector3
import com.google.ar.sceneform.rendering.ExternalTexture
import com.google.ar.sceneform.rendering.Material
import com.google.ar.sceneform.rendering.MaterialFactory
import com.google.ar.sceneform.rendering.ModelRenderable
import com.google.ar.sceneform.rendering.Renderable
import com.google.ar.sceneform.rendering.Texture
import com.google.ar.sceneform.rendering.ShapeFactory
import com.google.ar.sceneform.rendering.Color
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Quản lý việc phát video AR gắn vào anchor point
 * Sử dụng ExternalTexture + MediaPlayer + Sceneform Renderable
 */
class ArVideoManager(private val context: Context) {
    
    companion object {
        private const val TAG = "ArVideoManager"
        private const val DEFAULT_VIDEO_WIDTH = 1.0f  // 1 meter width
        private const val DEFAULT_ASPECT_RATIO = 16f / 9f  // 16:9 video
    }
    
    // Media components
    private var mediaPlayer: MediaPlayer? = null
    private var externalTexture: ExternalTexture? = null
    private var videoSurface: Surface? = null
    
    // AR components
    private var videoAnchor: Anchor? = null
    private var videoRenderable: Renderable? = null
    private var videoMaterial: Material? = null
    private var videoNode: Node? = null
    
    // State
    private var isInitialized = false
    private var isPlaying: Boolean = false
    
    /**
     * Khởi tạo video tại vị trí anchor
     * @param videoPath Đường dẫn đến video trong assets hoặc file system
     * @param anchor Anchor point từ ARCore (plane tap position)
     * @param width Chiều rộng của video plane (meters)
     * @param anchorNode The anchor node from ArSceneView to attach video to
     * @param onReady Callback when video is ready to play
     */
    suspend fun initializeVideoAtAnchor(
        videoPath: String,
        anchor: Anchor,
        width: Float = DEFAULT_VIDEO_WIDTH,
        anchorNode: AnchorNode? = null,
        onReady: (() -> Unit)? = null
    ): Boolean = withContext(Dispatchers.Main) {
        try {
            Log.d(TAG, "Initializing video at anchor: $videoPath")
            
            // Cleanup old resources
            cleanup()
            
            // Store anchor
            videoAnchor = anchor
            
            // Create ExternalTexture
            // Sceneform will automatically update this texture on the render thread
            // We don't need to call updateTexImage() manually!
            externalTexture = ExternalTexture().apply {
                Log.d(TAG, "ExternalTexture created - Sceneform will auto-update")
            }
            
            // Create MediaPlayer
            mediaPlayer = MediaPlayer().apply {
                try {
                    // Load video from Flutter assets or file path
                    if (videoPath.startsWith("assets/")) {
                        // Load from Flutter assets - extract to temp file first
                        // Flutter compiles all assets into "flutter_assets" folder
                        Log.d(TAG, "Loading Flutter asset: $videoPath")
                        
                        try {
                            val assetManager = context.assets
                            
                            // Debug: List available assets to see what's actually there
                            try {
                                val assets = assetManager.list("flutter_assets") ?: emptyArray()
                                Log.d(TAG, "Available flutter_assets: ${assets.take(10).joinToString(", ")}")
                                
                                val videoAssets = assetManager.list("flutter_assets/videos") ?: emptyArray()
                                Log.d(TAG, "Available video assets: ${videoAssets.joinToString(", ")}")
                            } catch (listE: Exception) {
                                Log.w(TAG, "Could not list assets: ${listE.message}")
                            }
                            
                            // Flutter assets are in "flutter_assets" folder
                            val flutterAssetPath = "flutter_assets/$videoPath"
                            Log.d(TAG, "Trying Flutter asset path: $flutterAssetPath")
                            
                            val inputStream = assetManager.open(flutterAssetPath)
                            
                            // Create temporary file
                            val tempFile = File(context.cacheDir, "temp_video_${System.currentTimeMillis()}.mp4")
                            tempFile.outputStream().use { output ->
                                inputStream.copyTo(output)
                            }
                            inputStream.close()
                            
                            setDataSource(tempFile.absolutePath)
                            Log.d(TAG, "✓ Video extracted to temp file: ${tempFile.absolutePath}")
                            
                            // Schedule cleanup of temp file after use
                            tempFile.deleteOnExit()
                        } catch (e: Exception) {
                            Log.e(TAG, "❌ Failed to extract Flutter asset '$videoPath': ${e.message}", e)
                            
                            // Try without "assets/" prefix in flutter_assets folder
                            try {
                                val pathWithoutAssets = videoPath.substring(7) // Remove "assets/"
                                val flutterAssetPath = "flutter_assets/$pathWithoutAssets"
                                Log.d(TAG, "Trying alternative Flutter path: $flutterAssetPath")
                                
                                val assetManager = context.assets
                                val inputStream = assetManager.open(flutterAssetPath)
                                
                                val tempFile = File(context.cacheDir, "temp_video_${System.currentTimeMillis()}.mp4")
                                tempFile.outputStream().use { output ->
                                    inputStream.copyTo(output)
                                }
                                inputStream.close()
                                
                                setDataSource(tempFile.absolutePath)
                                Log.d(TAG, "✓ Video extracted using alternative path: ${tempFile.absolutePath}")
                                tempFile.deleteOnExit()
                            } catch (altE: Exception) {
                                Log.e(TAG, "❌ Alternative path also failed: ${altE.message}", altE)
                                throw e
                            }
                        }
                    } else if (videoPath.startsWith("assets://")) {
                        // Load from Android assets
                        val assetPath = videoPath.substring(9) // Remove "assets://"
                        val afd = context.assets.openFd(assetPath)
                        setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
                        afd.close()
                        null
                    } else {
                        // Load from file path or content URI
                        setDataSource(context, Uri.parse(videoPath))
                        null
                    }
                    
                    // Create surface from ExternalTexture
                    val surfaceTexture = externalTexture?.surfaceTexture
                    if (surfaceTexture != null) {
                        videoSurface = Surface(surfaceTexture)
                        setSurface(videoSurface)
                        Log.d(TAG, "MediaPlayer surface set")
                    } else {
                        Log.e(TAG, "Failed to get SurfaceTexture")
                        return@withContext false
                    }
                    
                    // Prepare player
                    prepareAsync()
                    setLooping(true)
                    
                    setOnPreparedListener { mp ->
                        Log.d(TAG, "MediaPlayer prepared - Duration: ${mp.duration}ms")
                        
                        // Get video dimensions
                        val videoWidth = mp.videoWidth
                        val videoHeight = mp.videoHeight
                        val aspectRatio = if (videoHeight > 0) {
                            videoWidth.toFloat() / videoHeight.toFloat()
                        } else {
                            DEFAULT_ASPECT_RATIO
                        }
                        
                        Log.d(TAG, "Video dimensions: ${videoWidth}x${videoHeight}, aspect ratio: $aspectRatio")
                        
                        // Create video renderable on main thread
                        CoroutineScope(Dispatchers.Main).launch {
                            createVideoRenderable(width, aspectRatio, anchorNode, onReady)
                        }
                        
                        // Start playing
                        mp.start()
                        this@ArVideoManager.isPlaying = true
                        Log.d(TAG, "Video started playing")
                    }
                    
                    setOnErrorListener { mp, what, extra ->
                        Log.e(TAG, "MediaPlayer error: what=$what, extra=$extra")
                        cleanup()
                        false
                    }
                    
                    setOnCompletionListener {
                        Log.d(TAG, "Video playback completed (should loop)")
                    }
                    
                } catch (e: Exception) {
                    Log.e(TAG, "Error setting up MediaPlayer: ${e.message}", e)
                    throw e
                }
            }
            
            isInitialized = true
            Log.d(TAG, "Video initialization successful")
            true
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize video: ${e.message}", e)
            cleanup()
            false
        }
    }
    
    /**
     * Tạo Renderable cho video plane với ExternalTexture
     * Sử dụng Material.builder() để tạo material tương thích với ExternalTexture
     */
    private suspend fun createVideoRenderable(
        width: Float,
        aspectRatio: Float,
        anchorNode: AnchorNode?,
        onReady: (() -> Unit)?
    ) = withContext(Dispatchers.Main) {
        val height = width / aspectRatio
        val extTexture = externalTexture ?: run {
            Log.e(TAG, "ExternalTexture is null")
            return@withContext
        }

        try {
            Log.d(TAG, "Creating video renderable: ${width}m x ${height}m")

            // Load the sceneform_opaque_textured_material.matc which supports ExternalTexture
            val resourceId = context.resources.getIdentifier(
                "sceneform_opaque_textured_material",
                "raw",
                context.packageName
            )
            
            if (resourceId == 0) {
                Log.e(TAG, "❌ Could not find sceneform_opaque_textured_material in resources")
                createMaterialWithExternalTexture(width, height, anchorNode, onReady)
                return@withContext
            }
            
            Log.d(TAG, "Loading material from resource ID: $resourceId")

            // Create material using the .matc file which properly supports ExternalTexture
            Material.builder()
                .setSource(context, resourceId)
                .build()
                .thenAccept { material: Material ->
                    try {
                        // Set the ExternalTexture as the "texture" parameter (standard in Sceneform materials)
                        // Try different parameter names - Sceneform materials may use different names
                        try {
                            material.setExternalTexture("texture", extTexture)
                            Log.d(TAG, "✓ ExternalTexture set with parameter name: 'texture'")
                        } catch (e: Exception) {
                            Log.w(TAG, "Failed with 'texture', trying 'videoTexture': ${e.message}")
                            try {
                                material.setExternalTexture("videoTexture", extTexture)
                                Log.d(TAG, "✓ ExternalTexture set with parameter name: 'videoTexture'")
                            } catch (e2: Exception) {
                                Log.w(TAG, "Failed with 'videoTexture', trying 'baseColorMap': ${e2.message}")
                                material.setExternalTexture("baseColorMap", extTexture)
                                Log.d(TAG, "✓ ExternalTexture set with parameter name: 'baseColorMap'")
                            }
                        }
                        
                        this@ArVideoManager.videoMaterial = material
                        Log.d(TAG, "✓ Material created with ExternalTexture using .matc file")

                        val renderable = ShapeFactory.makeCube(
                            Vector3(width, 0.001f, height),
                            Vector3.zero(),
                            material
                        )

                        videoRenderable = renderable
                        Log.d(TAG, "✓ Video plane created: ${width}m x ${height}m")

                        anchorNode?.let { node ->
                            attachToAnchorNode(node)
                        }

                        onReady?.invoke()

                    } catch (e: Exception) {
                        Log.e(TAG, "Error creating renderable: ${e.message}", e)
                        createFallbackRenderable(width, height, anchorNode, onReady)
                    }
                }
                .exceptionally { error: Throwable? ->
                    Log.e(TAG, "Failed to create material from .matc: ${error?.message ?: "Unknown error"}", error)
                    // Try alternative approach with color material + external texture
                    createMaterialWithExternalTexture(width, height, anchorNode, onReady)
                    null
                }

        } catch (e: Exception) {
            Log.e(TAG, "Error creating video renderable: ${e.message}", e)
            createFallbackRenderable(width, height, anchorNode, onReady)
        }
    }
    
    /**
     * Alternative approach: Create a base material and set ExternalTexture as parameter
     */
    private fun createMaterialWithExternalTexture(
        width: Float,
        height: Float,
        anchorNode: AnchorNode?,
        onReady: (() -> Unit)?
    ) {
        try {
            Log.i(TAG, "🔄 Trying alternative material creation with ExternalTexture")
            
            val extTexture = externalTexture ?: run {
                Log.e(TAG, "ExternalTexture is null in alternative approach")
                createFallbackRenderable(width, height, anchorNode, onReady)
                return
            }
            
            // Create a base opaque color material first
            val color = com.google.ar.sceneform.rendering.Color(1f, 1f, 1f, 1f) // White
            
            MaterialFactory.makeOpaqueWithColor(context, color)
                .thenAccept { material: Material ->
                    try {
                        // Try to set ExternalTexture as a parameter (using standard "texture" param name)
                        material.setExternalTexture("texture", extTexture)
                        
                        videoMaterial = material
                        Log.d(TAG, "✓ Material created with ExternalTexture (alternative approach)")
                        
                        // Create plane
                        videoRenderable = com.google.ar.sceneform.rendering.ShapeFactory.makeCube(
                            Vector3(width, 0.001f, height),
                            Vector3.zero(),
                            material
                        )
                        
                        Log.i(TAG, "✅ Video plane created with external texture")
                        
                        anchorNode?.let { node ->
                            attachToAnchorNode(node)
                        }
                        
                        onReady?.invoke()
                        
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to set ExternalTexture parameter: ${e.message}", e)
                        createFallbackRenderable(width, height, anchorNode, onReady)
                    }
                }
                .exceptionally { error: Throwable? ->
                    Log.e(TAG, "Alternative approach failed: ${error?.message ?: error.toString()}")
                    createFallbackRenderable(width, height, anchorNode, onReady)
                    null
                }
        } catch (e: Exception) {
            Log.e(TAG, "Error in alternative approach: ${e.message}", e)
            createFallbackRenderable(width, height, anchorNode, onReady)
        }
    }
    
    /**
     * Fallback: Create plane with default material (for testing)
     */
    private fun createFallbackRenderable(
        width: Float,
        height: Float,
        anchorNode: AnchorNode?,
        onReady: (() -> Unit)?
    ) {
        try {
            Log.i(TAG, "🔄 Using fallback - creating simple plane renderable")
            
            // Create a simple unlit material programmatically
            val color = com.google.ar.sceneform.rendering.Color(0f, 1f, 0f, 1f) // Green for debugging
            
            MaterialFactory.makeOpaqueWithColor(context, color)
                .thenAccept { material: Material ->
                    videoMaterial = material
                    
                    // Create plane
                    videoRenderable = com.google.ar.sceneform.rendering.ShapeFactory.makeCube(
                        Vector3(width, 0.001f, height),
                        Vector3.zero(),
                        material
                    )
                    
                    Log.i(TAG, "✅ Fallback plane created (should show green)")
                    
                    anchorNode?.let { node ->
                        attachToAnchorNode(node)
                    }
                    
                    onReady?.invoke()
                }
                .exceptionally { error: Throwable? ->
                    Log.e(TAG, "Fallback also failed: ${error?.message ?: error.toString()}")
                    null
                }
        } catch (e: Exception) {
            Log.e(TAG, "Error in fallback: ${e.message}", e)
        }
    }
    
    
    /**
     * Render video lên AR scene
     * Gọi method này trong onDrawFrame của AR session
     * NOTE: This must be called from the rendering thread (Sceneform's update listener)
     */
    fun render(frame: Frame) {
        if (!isInitialized || videoRenderable == null || videoAnchor == null) {
            return
        }
        
        // Check if anchor is still tracking
        if (videoAnchor?.trackingState != TrackingState.TRACKING) {
            // Don't log every frame to avoid spam
            return
        }
    }
    
    /**
     * Pause video playback
     */
    fun pause() {
        mediaPlayer?.let {
            if (it.isPlaying) {
                it.pause()
                isPlaying = false
                Log.d(TAG, "Video paused")
            }
        }
    }
    
    /**
     * Resume video playback
     */
    fun resume() {
        mediaPlayer?.let {
            if (!it.isPlaying) {
                it.start()
                isPlaying = true
                Log.d(TAG, "Video resumed")
            }
        }
    }
    
    /**
     * Toggle play/pause
     */
    fun togglePlayPause() {
        if (isPlaying) {
            pause()
        } else {
            resume()
        }
    }
    
    /**
     * Check if video is currently playing
     */
    fun isVideoPlaying(): Boolean = isPlaying
    
    /**
     * Get video anchor
     */
    fun getAnchor(): Anchor? = videoAnchor
    
    /**
     * Get video renderable
     */
    fun getRenderable(): Renderable? = videoRenderable
    
    /**
     * Get video node
     */
    fun getVideoNode(): Node? = videoNode
    
    /**
     * Attach video node to anchor node in scene
     * @param anchorNode The anchor node from ArSceneView
     */
    fun attachToAnchorNode(anchorNode: AnchorNode) {
        try {
            val renderable = videoRenderable ?: run {
                Log.w(TAG, "Video renderable not ready yet")
                return
            }
            
            // Create video node if not exists
            if (videoNode == null) {
                videoNode = Node().apply {
                    setParent(anchorNode)
                    this.renderable = renderable
                    
                    // No rotation - video plane will be parallel to the detected card image
                    // The anchor already has the correct orientation from ARCore image tracking
                    // If you need to flip or rotate, adjust here:
                    // localRotation = Quaternion.axisAngle(Vector3(1f, 0f, 0f), 180f) // flip
                    
                    Log.d(TAG, "✓ Video node attached to anchor (no rotation applied)")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error attaching video node: ${e.message}", e)
        }
    }
    
    /**
     * Cleanup tất cả resources
     */
    fun cleanup() {
        Log.d(TAG, "Cleaning up video resources")
        
        // Stop and release MediaPlayer
        mediaPlayer?.let {
            try {
                if (it.isPlaying) {
                    it.stop()
                }
                it.release()
            } catch (e: Exception) {
                Log.e(TAG, "Error releasing MediaPlayer: ${e.message}")
            }
        }
        mediaPlayer = null
        
        // Release Surface
        videoSurface?.release()
        videoSurface = null
        
        // Detach anchor
        videoAnchor?.detach()
        videoAnchor = null
        
        // Remove video node
        videoNode?.setParent(null)
        videoNode?.renderable = null
        videoNode = null
        
        // Clear references
        videoRenderable = null
        videoMaterial = null
        externalTexture = null
        
        isInitialized = false
        isPlaying = false
        
        Log.d(TAG, "Cleanup completed")
    }
    
    /**
     * Seek video to position
     */
    fun seekTo(positionMs: Int) {
        mediaPlayer?.seekTo(positionMs)
        Log.d(TAG, "Seeked to position: ${positionMs}ms")
    }
    
    /**
     * Get current video position
     */
    fun getCurrentPosition(): Int {
        return mediaPlayer?.currentPosition ?: 0
    }
    
    /**
     * Get video duration
     */
    fun getDuration(): Int {
        return mediaPlayer?.duration ?: 0
    }
}

