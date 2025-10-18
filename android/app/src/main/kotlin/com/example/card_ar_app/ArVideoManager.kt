package com.example.card_ar_app

import android.content.Context
import android.graphics.SurfaceTexture
import android.media.MediaPlayer
import android.net.Uri
import android.util.Log
import android.view.Surface
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
import com.google.ar.sceneform.rendering.ModelRenderable
import com.google.ar.sceneform.rendering.Renderable
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
            externalTexture = ExternalTexture().apply {
                Log.d(TAG, "ExternalTexture created")
            }
            
            // Create MediaPlayer
            mediaPlayer = MediaPlayer().apply {
                try {
                    // Load video from assets or file path
                    val videoUri = if (videoPath.startsWith("assets://")) {
                        // Load from assets
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
     * Sử dụng ModelRenderable với custom material
     */
    private suspend fun createVideoRenderable(
        width: Float,
        aspectRatio: Float,
        anchorNode: AnchorNode?,
        onReady: (() -> Unit)?
    ) = withContext(Dispatchers.Main) {
        try {
            val height = width / aspectRatio
            val texture = externalTexture ?: run {
                Log.e(TAG, "ExternalTexture is null")
                return@withContext
            }
            
            Log.d(TAG, "Creating video renderable: ${width}m x ${height}m")
            
            // Build material directly with ExternalTexture
            Material.builder()
                .setSource(context, android.net.Uri.parse("materials/video_material.mat"))
                .build()
                .thenAccept { material ->
                    // Set external texture to material
                    material.setExternalTexture("videoTexture", texture)
                    this@ArVideoManager.videoMaterial = material
                    Log.d(TAG, "✓ Material created with external texture")
                    
                    // Create simple plane renderable
                    val halfWidth = width / 2f
                    val halfHeight = height / 2f
                    
                    // Build a simple quad renderable
                    ModelRenderable.builder()
                        .setSource(context, android.net.Uri.parse("models/plane.sfb"))
                        .build()
                        .thenAccept { renderable ->
                            renderable.material = material
                            videoRenderable = renderable
                            Log.d(TAG, "✓ Video renderable created: ${width}m x ${height}m")
                            
                            // Attach to anchor node if provided
                            anchorNode?.let { node ->
                                attachToAnchorNode(node)
                            }
                            
                            // Notify ready
                            onReady?.invoke()
                        }
                        .exceptionally { error ->
                            Log.e(TAG, "Failed to create renderable: ${error?.message}", error)
                            null
                        }
                }
                .exceptionally { error ->
                    Log.e(TAG, "Failed to create material: ${error?.message}", error)
                    null
                }
            
        } catch (e: Exception) {
            Log.e(TAG, "Error creating video renderable: ${e.message}", e)
        }
    }
    
    /**
     * Render video lên AR scene
     * Gọi method này trong onDrawFrame của AR session
     */
    fun render(frame: Frame) {
        if (!isInitialized || videoRenderable == null || videoAnchor == null) {
            return
        }
        
        // Check if anchor is still tracking
        if (videoAnchor?.trackingState != TrackingState.TRACKING) {
            Log.w(TAG, "Anchor not tracking: ${videoAnchor?.trackingState}")
            return
        }
        
        // Update ExternalTexture
        externalTexture?.surfaceTexture?.updateTexImage()
        
        // Render the video plane at anchor position
        // Note: Actual rendering will be handled by Sceneform's rendering pipeline
        // This is just to update the texture
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
                    
                    // Rotate video to face up (adjust based on your needs)
                    localRotation = Quaternion.axisAngle(Vector3(1f, 0f, 0f), 90f)
                    
                    Log.d(TAG, "✓ Video node attached to anchor")
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

