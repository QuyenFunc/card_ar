package com.example.card_ar_app

import android.content.Context
import android.graphics.SurfaceTexture
import android.media.MediaPlayer
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.Matrix
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Surface
import com.google.ar.core.Anchor
import com.google.ar.core.Frame
import com.google.ar.core.TrackingState
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * Renderer đơn giản hơn, sử dụng OpenGL ES trực tiếp
 * để render video texture lên AR plane anchor
 */
class ArVideoRenderer(private val context: Context) {
    
    companion object {
        private const val TAG = "ArVideoRenderer"
        
        // Vertex shader - Simple quad with texture
        private const val VERTEX_SHADER = """
            uniform mat4 u_ModelViewProjection;
            attribute vec4 a_Position;
            attribute vec2 a_TexCoord;
            varying vec2 v_TexCoord;
            
            void main() {
                v_TexCoord = a_TexCoord;
                gl_Position = u_ModelViewProjection * a_Position;
            }
        """
        
        // Fragment shader - External texture (for video) with debug color
        private const val FRAGMENT_SHADER = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            varying vec2 v_TexCoord;
            uniform samplerExternalOES u_Texture;
            uniform float u_VideoReady;
            
            void main() {
                if (u_VideoReady > 0.5) {
                    // Video is ready - render texture
                    gl_FragColor = texture2D(u_Texture, v_TexCoord);
                } else {
                    // Video not ready - show BRIGHT GREEN debug color (impossible to miss!)
                    gl_FragColor = vec4(0.0, 1.0, 0.0, 1.0);
                }
            }
        """
        
        // Quad vertices (plane for video)
        private val QUAD_COORDS = floatArrayOf(
            -0.5f,  0.5f, 0.0f,  // Top left
            -0.5f, -0.5f, 0.0f,  // Bottom left
             0.5f,  0.5f, 0.0f,  // Top right
             0.5f, -0.5f, 0.0f   // Bottom right
        )
        
        // Texture coordinates
        private val TEX_COORDS = floatArrayOf(
            0.0f, 0.0f,  // Top left
            0.0f, 1.0f,  // Bottom left
            1.0f, 0.0f,  // Top right
            1.0f, 1.0f   // Bottom right
        )
        
        private const val COORDS_PER_VERTEX = 3
        private const val TEX_COORDS_PER_VERTEX = 2
    }
    
    // OpenGL resources
    private var shaderProgram = 0
    private var textureId = 0
    private var positionAttribute = 0
    private var texCoordAttribute = 0
    private var modelViewProjectionUniform = 0
    private var textureUniform = 0
    private var videoReadyUniform = 0
    
    // Buffers
    private var vertexBuffer: FloatBuffer
    private var texCoordBuffer: FloatBuffer
    
    // Media components
    private var mediaPlayer: MediaPlayer? = null
    private var surfaceTexture: SurfaceTexture? = null
    private var surface: Surface? = null
    
    // AR anchor
    private var anchor: Anchor? = null
    
    // Video dimensions
    private var videoWidth = 1.0f
    private var videoHeight = 0.5625f  // 16:9 default
    
    // State
    private var isInitialized = false
    private var isPlaying: Boolean = false
    private var glResourcesInitialized = false
    private var frameCount = 0

    private val mainThreadHandler = Handler(Looper.getMainLooper())
    
    init {
        // Initialize buffers
        vertexBuffer = ByteBuffer.allocateDirect(QUAD_COORDS.size * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .apply {
                put(QUAD_COORDS)
                position(0)
            }
        
        texCoordBuffer = ByteBuffer.allocateDirect(TEX_COORDS.size * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .apply {
                put(TEX_COORDS)
                position(0)
            }
    }
    
    /**
     * Khởi tạo OpenGL shaders và texture
     */
    fun initializeGl() {
        if (glResourcesInitialized) {
            return
        }
        try {
            // Create shader program
            val vertexShader = loadShader(GLES20.GL_VERTEX_SHADER, VERTEX_SHADER)
            val fragmentShader = loadShader(GLES20.GL_FRAGMENT_SHADER, FRAGMENT_SHADER)
            
            shaderProgram = GLES20.glCreateProgram()
            GLES20.glAttachShader(shaderProgram, vertexShader)
            GLES20.glAttachShader(shaderProgram, fragmentShader)
            GLES20.glLinkProgram(shaderProgram)
            
            // Check for link errors
            val linkStatus = IntArray(1)
            GLES20.glGetProgramiv(shaderProgram, GLES20.GL_LINK_STATUS, linkStatus, 0)
            if (linkStatus[0] == 0) {
                val error = GLES20.glGetProgramInfoLog(shaderProgram)
                Log.e(TAG, "Error linking program: $error")
                GLES20.glDeleteProgram(shaderProgram)
                shaderProgram = 0
                return
            }
            
            // Get attribute locations
            positionAttribute = GLES20.glGetAttribLocation(shaderProgram, "a_Position")
            texCoordAttribute = GLES20.glGetAttribLocation(shaderProgram, "a_TexCoord")
            
            // Get uniform locations
            modelViewProjectionUniform = GLES20.glGetUniformLocation(shaderProgram, "u_ModelViewProjection")
            textureUniform = GLES20.glGetUniformLocation(shaderProgram, "u_Texture")
            videoReadyUniform = GLES20.glGetUniformLocation(shaderProgram, "u_VideoReady")
            
            // Create external texture for video
            val textures = IntArray(1)
            GLES20.glGenTextures(1, textures, 0)
            textureId = textures[0]
            
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
            
            glResourcesInitialized = true
            Log.i(TAG, "✅ OpenGL initialized - Program: $shaderProgram, Texture: $textureId")
            
        } catch (e: Exception) {
            Log.e(TAG, "Error initializing OpenGL: ${e.message}", e)
        }
    }
    
    /**
     * Check OpenGL errors (silent - no log spam)
     */
    private fun checkGlError(op: String) {
        // Silent check - errors will show up in critical paths only
        GLES20.glGetError()
    }
    
    /**
     * Load và compile shader
     */
    private fun loadShader(type: Int, shaderCode: String): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, shaderCode)
        GLES20.glCompileShader(shader)
        
        // Check for compile errors
        val compileStatus = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compileStatus, 0)
        if (compileStatus[0] == 0) {
            val error = GLES20.glGetShaderInfoLog(shader)
            Log.e(TAG, "Error compiling shader: $error")
            GLES20.glDeleteShader(shader)
            return 0
        }
        
        return shader
    }
    
    /**
     * Khởi tạo video tại anchor position
     */
    fun initializeVideo(
        videoPath: String,
        anchor: Anchor,
        width: Float = 1.0f,
        onVideoPrepared: (() -> Unit)? = null,
        onVideoError: ((Throwable) -> Unit)? = null
    ): Boolean {
        Log.i(TAG, "📹 initializeVideo START")
        Log.i(TAG, "  - videoPath: $videoPath")
        Log.i(TAG, "  - width: $width")
        Log.i(TAG, "  - glResourcesInitialized: $glResourcesInitialized")
        Log.i(TAG, "  - textureId: $textureId")
        
        try {
            if (!glResourcesInitialized) {
                Log.e(TAG, "❌ OpenGL not ready - glResourcesInitialized=$glResourcesInitialized")
                return false
            }

            // Store anchor
            this.anchor = anchor
            this.videoWidth = width
            
            // Set initialized immediately so we can see green plane
            isInitialized = true
            Log.i(TAG, "✅ isInitialized set to TRUE - green plane should appear!")
            
            // Create SurfaceTexture from OpenGL texture
            Log.i(TAG, "🎨 Creating SurfaceTexture with textureId: $textureId")
            surfaceTexture?.release()
            surface?.release()

            surfaceTexture = SurfaceTexture(textureId).apply {
                setOnFrameAvailableListener {
                    // Video frame available - will be handled in draw()
                }
            }
            Log.i(TAG, "✓ SurfaceTexture created")

            surface = Surface(surfaceTexture)
            Log.i(TAG, "✓ Surface created")
            
            // Create and setup MediaPlayer
            Log.i(TAG, "🎵 Scheduling MediaPlayer setup on main thread...")
            mainThreadHandler.post {
                try {
                    Log.i(TAG, "🎬 MediaPlayer setup starting...")
                    
                    mediaPlayer?.let {
                        try {
                            if (it.isPlaying) it.stop()
                        } catch (_: Exception) {
                        }
                        it.release()
                    }

                    mediaPlayer = MediaPlayer().apply {
                        val flutterAssetPath = "flutter_assets/$videoPath"
                        Log.i(TAG, "📂 Loading video from: $flutterAssetPath")

                        val afd = context.assets.openFd(flutterAssetPath)
                        setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
                        afd.close()
                        Log.i(TAG, "✓ DataSource set")

                        // Set surface
                        setSurface(surface)
                        Log.i(TAG, "✓ Surface attached to MediaPlayer")

                        setOnPreparedListener { mp ->
                            Log.i(TAG, "🎉 MediaPlayer PREPARED callback!")
                            
                            // Get video dimensions
                            val vWidth = mp.videoWidth
                            val vHeight = mp.videoHeight
                            val aspectRatio = if (vHeight > 0) vWidth.toFloat() / vHeight.toFloat() else 16f / 9f

                            // Calculate proper video plane height based on width (in METERS, not pixels!)
                            this@ArVideoRenderer.videoHeight = this@ArVideoRenderer.videoWidth / aspectRatio

                            // Start playback
                            mp.start()
                            this@ArVideoRenderer.isPlaying = true
                            Log.i(TAG, "▶️▶️▶️ Video PLAYING! Size: ${vWidth}x${vHeight}, aspect: $aspectRatio")
                            onVideoPrepared?.invoke()
                        }

                        setOnErrorListener { _, what, extra ->
                            val error = RuntimeException("MediaPlayer error: what=$what, extra=$extra")
                            Log.e(TAG, "❌ ${error.message}")
                            onVideoError?.invoke(error)
                            true
                        }

                        setLooping(true)
                        Log.i(TAG, "📞 Calling prepareAsync()...")
                        prepareAsync()
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "❌ Error setting up MediaPlayer: ${e.message}", e)
                    onVideoError?.invoke(e)
                }
            }
            
            // Already set isInitialized = true above to show green plane immediately
            return true
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize video: ${e.message}", e)
            return false
        }
    }
    
    /**
     * Draw video quad lên AR scene
     */
    fun draw(viewMatrix: FloatArray, projectionMatrix: FloatArray) {
        // Debug every frame for first 100 frames
        if (frameCount < 100) {
            Log.d(TAG, "🖼️ draw() called - frame #$frameCount, anchor: ${anchor != null}, tracking: ${anchor?.trackingState}")
        }
        
        // Always try to draw if we have an anchor (for debug)
        if (anchor == null) {
            if (frameCount % 60 == 0) {
                Log.w(TAG, "❌ No anchor!")
            }
            return
        }
        
        // Check anchor tracking state
        if (anchor?.trackingState != TrackingState.TRACKING) {
            // Log once
            if (frameCount % 60 == 0) {
                Log.w(TAG, "⚠️ Anchor not tracking: ${anchor?.trackingState}")
            }
            return
        }
        
        frameCount++
        
        // Update texture from video (only if video is playing)
        if (isPlaying) {
            try {
                surfaceTexture?.updateTexImage()
            } catch (e: Exception) {
                // Texture not ready yet - but still render debug color
            }
        }
        
        // Validate shader program before use
        if (shaderProgram == 0) {
            if (frameCount % 60 == 0) {
                Log.e(TAG, "❌ No shader program!")
            }
            return
        }
        
        // Debug log every 30 frames (about 0.5 second)
        if (frameCount % 30 == 0) {
            Log.d(TAG, "🎥 Frame #$frameCount - Playing: $isPlaying, Init: $isInitialized, GL: $glResourcesInitialized")
        }
        
        // First frame special log
        if (frameCount == 1) {
            Log.i(TAG, "🎬🎬🎬 FIRST FRAME RENDERING! This should show green plane!")
        }
        
        // Use shader program
        GLES20.glUseProgram(shaderProgram)
        
        // DISABLE depth test and depth write completely - render on top of everything
        GLES20.glDisable(GLES20.GL_DEPTH_TEST)
        GLES20.glDepthMask(false)
        
        // Enable blending with full alpha
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        
        // Disable face culling to show both sides
        GLES20.glDisable(GLES20.GL_CULL_FACE)
        
        // Calculate model matrix from anchor pose
        val modelMatrix = FloatArray(16)
        anchor?.pose?.toMatrix(modelMatrix, 0)
        
        // Scale to video dimensions (HUGE for testing - 1m x 1m)
        val scaleMatrix = FloatArray(16)
        Matrix.setIdentityM(scaleMatrix, 0)
        // Make it MASSIVE so we can't miss it!
        Matrix.scaleM(scaleMatrix, 0, 1.0f, 1.0f, 1.0f)
        
        // Rotate to face up (ARCore anchors are oriented differently)
        val rotationMatrix = FloatArray(16)
        Matrix.setIdentityM(rotationMatrix, 0)
        Matrix.rotateM(rotationMatrix, 0, 90f, 1f, 0f, 0f)
        
        // Combine transformations
        val tempMatrix = FloatArray(16)
        Matrix.multiplyMM(tempMatrix, 0, modelMatrix, 0, rotationMatrix, 0)
        Matrix.multiplyMM(modelMatrix, 0, tempMatrix, 0, scaleMatrix, 0)
        
        // Calculate MVP matrix
        val viewProjectionMatrix = FloatArray(16)
        val mvpMatrix = FloatArray(16)
        Matrix.multiplyMM(viewProjectionMatrix, 0, projectionMatrix, 0, viewMatrix, 0)
        Matrix.multiplyMM(mvpMatrix, 0, viewProjectionMatrix, 0, modelMatrix, 0)
        
        // Set MVP matrix uniform
        GLES20.glUniformMatrix4fv(modelViewProjectionUniform, 1, false, mvpMatrix, 0)
        
        // Set video ready uniform (1.0 if playing, 0.0 for debug green)
        GLES20.glUniform1f(videoReadyUniform, if (isPlaying && isInitialized) 1.0f else 0.0f)
        
        // Bind texture
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
        GLES20.glUniform1i(textureUniform, 0)
        
        // Set vertex attributes
        GLES20.glEnableVertexAttribArray(positionAttribute)
        GLES20.glVertexAttribPointer(
            positionAttribute,
            COORDS_PER_VERTEX,
            GLES20.GL_FLOAT,
            false,
            0,
            vertexBuffer
        )
        
        GLES20.glEnableVertexAttribArray(texCoordAttribute)
        GLES20.glVertexAttribPointer(
            texCoordAttribute,
            TEX_COORDS_PER_VERTEX,
            GLES20.GL_FLOAT,
            false,
            0,
            texCoordBuffer
        )
        
        // Draw quad
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        
        // Check for GL errors
        val error = GLES20.glGetError()
        if (error != GLES20.GL_NO_ERROR) {
            Log.e(TAG, "❌❌❌ OpenGL Error after draw: $error")
        }
        
        // Log first few draws
        if (frameCount <= 10) {
            Log.i(TAG, "✅ glDrawArrays called! Frame: $frameCount, VideoReady: ${if (isPlaying && isInitialized) 1.0f else 0.0f}")
            Log.i(TAG, "📐 MVP Matrix: ${mvpMatrix.take(4).joinToString()}")
            Log.i(TAG, "📍 Anchor Pose: ${anchor?.pose?.let { "tx=${it.tx()}, ty=${it.ty()}, tz=${it.tz()}" }}")
        }
        
        // Cleanup
        GLES20.glDisableVertexAttribArray(positionAttribute)
        GLES20.glDisableVertexAttribArray(texCoordAttribute)
        GLES20.glDisable(GLES20.GL_BLEND)
        GLES20.glEnable(GLES20.GL_DEPTH_TEST) // Re-enable depth test
        GLES20.glDepthMask(true) // Re-enable depth write
        GLES20.glEnable(GLES20.GL_CULL_FACE) // Re-enable culling
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, 0)
    }
    
    /**
     * Toggle play/pause
     */
    fun togglePlayPause() {
        mediaPlayer?.let {
            if (it.isPlaying) {
                it.pause()
                isPlaying = false
            } else {
                it.start()
                isPlaying = true
            }
        }
    }
    
    /**
     * Check if playing
     */
    fun isVideoPlaying(): Boolean = isPlaying
    
    /**
     * Cleanup resources
     */
    fun cleanup() {
        mainThreadHandler.post {
            mediaPlayer?.let {
                try {
                    if (it.isPlaying) it.stop()
                } catch (_: Exception) {
                }
                try {
                    it.release()
                } catch (e: Exception) {
                    Log.e(TAG, "Error releasing MediaPlayer: ${e.message}")
                }
            }
            mediaPlayer = null
        }
        
        surfaceTexture?.release()
        surfaceTexture = null

        surface?.release()
        surface = null
        
        anchor?.detach()
        anchor = null
        
        if (textureId != 0) {
            GLES20.glDeleteTextures(1, intArrayOf(textureId), 0)
            textureId = 0
        }
        
        if (shaderProgram != 0) {
            GLES20.glDeleteProgram(shaderProgram)
            shaderProgram = 0
        }
        
        glResourcesInitialized = false
        isInitialized = false
        isPlaying = false
        videoWidth = 1.0f
        videoHeight = 0.5625f
    }
}

