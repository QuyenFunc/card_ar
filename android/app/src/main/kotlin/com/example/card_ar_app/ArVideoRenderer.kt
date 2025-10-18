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
        
        // Fragment shader - External texture (for video)
        private const val FRAGMENT_SHADER = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            varying vec2 v_TexCoord;
            uniform samplerExternalOES u_Texture;
            
            void main() {
                gl_FragColor = texture2D(u_Texture, v_TexCoord);
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
            
            // Create external texture for video
            val textures = IntArray(1)
            GLES20.glGenTextures(1, textures, 0)
            textureId = textures[0]
            
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
            
            Log.d(TAG, "OpenGL initialized successfully")
            glResourcesInitialized = true
            
        } catch (e: Exception) {
            Log.e(TAG, "Error initializing OpenGL: ${e.message}", e)
        }
    }
    
    /**
     * Check OpenGL errors
     */
    private fun checkGlError(op: String) {
        val error = GLES20.glGetError()
        if (error != GLES20.GL_NO_ERROR) {
            Log.e(TAG, "OpenGL error after $op: 0x${Integer.toHexString(error)}")
        }
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
        try {
            Log.d(TAG, "Initializing video at anchor: $videoPath")
            
            if (!glResourcesInitialized || textureId == 0) {
                Log.e(TAG, "OpenGL resources not initialized before initializeVideo")
                return false
            }

            // Store anchor
            this.anchor = anchor
            this.videoWidth = width
            
            // Create SurfaceTexture from OpenGL texture
            surfaceTexture?.release()
            surface?.release()

            surfaceTexture = SurfaceTexture(textureId).apply {
                setOnFrameAvailableListener {
                    // Video frame available - will be handled in draw()
                }
            }

            surface = Surface(surfaceTexture)
            
            // Create and setup MediaPlayer
            mainThreadHandler.post {
                try {
                    mediaPlayer?.let {
                        try {
                            if (it.isPlaying) it.stop()
                        } catch (_: Exception) {
                        }
                        it.release()
                    }

                    mediaPlayer = MediaPlayer().apply {
                        val flutterAssetPath = "flutter_assets/$videoPath"
                        Log.d(TAG, "Loading from asset path: $flutterAssetPath")

                        val afd = context.assets.openFd(flutterAssetPath)
                        setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
                        afd.close()

                        // Set surface
                        setSurface(surface)

                        setOnPreparedListener { mp ->
                            // Get video dimensions
                            val vWidth = mp.videoWidth
                            val vHeight = mp.videoHeight
                            val aspectRatio = if (vHeight > 0) vWidth.toFloat() / vHeight.toFloat() else 16f / 9f

                            // Calculate proper video plane height based on width (in METERS, not pixels!)
                            this@ArVideoRenderer.videoHeight = this@ArVideoRenderer.videoWidth / aspectRatio

                            Log.d(TAG, "Video prepared - ${vWidth}x${vHeight} pixels, aspect ratio: $aspectRatio")
                            Log.d(TAG, "Video plane size: ${this@ArVideoRenderer.videoWidth}m x ${this@ArVideoRenderer.videoHeight}m")

                            // Start playback
                            mp.start()
                            this@ArVideoRenderer.isPlaying = true
                            Log.d(TAG, "Video playback started")
                            onVideoPrepared?.invoke()
                        }

                        setOnErrorListener { _, what, extra ->
                            val error = RuntimeException("MediaPlayer error: what=$what, extra=$extra")
                            Log.e(TAG, error.message ?: "MediaPlayer error")
                            onVideoError?.invoke(error)
                            true
                        }

                        setLooping(true)
                        prepareAsync()
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error setting up MediaPlayer: ${e.message}", e)
                    onVideoError?.invoke(e)
                }
            }
            
            isInitialized = true
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
        if (!isInitialized || anchor == null || !isPlaying) {
            return
        }
        
        // Check anchor tracking state
        if (anchor?.trackingState != TrackingState.TRACKING) {
            return
        }
        
        // Update texture from video
        try {
            surfaceTexture?.updateTexImage()
        } catch (e: Exception) {
            // Texture not ready yet
            return
        }
        
        // Use shader program
        GLES20.glUseProgram(shaderProgram)
        checkGlError("glUseProgram")
        
        // Enable blending for transparency
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        
        // Calculate model matrix from anchor pose
        val modelMatrix = FloatArray(16)
        anchor?.pose?.toMatrix(modelMatrix, 0)
        
        // Scale to video dimensions
        val scaleMatrix = FloatArray(16)
        Matrix.setIdentityM(scaleMatrix, 0)
        Matrix.scaleM(scaleMatrix, 0, videoWidth, 1f, videoHeight)
        
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
        
        // Cleanup
        GLES20.glDisableVertexAttribArray(positionAttribute)
        GLES20.glDisableVertexAttribArray(texCoordAttribute)
        GLES20.glDisable(GLES20.GL_BLEND)
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
                Log.d(TAG, "Video paused")
            } else {
                it.start()
                isPlaying = true
                Log.d(TAG, "Video resumed")
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
        Log.d(TAG, "Cleaning up resources")
        
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

