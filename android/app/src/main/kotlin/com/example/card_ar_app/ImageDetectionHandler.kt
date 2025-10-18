package com.example.card_ar_app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.util.Log
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.label.ImageLabeling
import com.google.mlkit.vision.label.defaults.ImageLabelerOptions
import io.flutter.plugin.common.MethodChannel
import kotlinx.coroutines.*
import java.io.ByteArrayOutputStream
import kotlin.coroutines.CoroutineContext

/**
 * Handler để detect ảnh thẻ bài sử dụng template matching và ML Kit
 */
class ImageDetectionHandler(
    private val context: Context,
    private val methodChannel: MethodChannel
) : CoroutineScope {
    
    companion object {
        private const val TAG = "ImageDetectionHandler"
        private const val MATCH_THRESHOLD = 0.65 // Confidence threshold cho match
    }
    
    private val job = Job()
    override val coroutineContext: CoroutineContext
        get() = Dispatchers.Default + job
    
    // Reference images để so sánh
    private val referenceImages = mutableMapOf<String, Bitmap>()
    
    // ML Kit image labeler
    private val imageLabeler by lazy {
        val options = ImageLabelerOptions.Builder()
            .setConfidenceThreshold(0.7f)
            .build()
        ImageLabeling.getClient(options)
    }
    
    // Detection state
    private var isDetecting = false
    private var lastDetectionTime = 0L
    private val detectionThrottleMs = 500L
    
    /**
     * Add reference image để so sánh
     */
    fun addReferenceImage(imageId: String, imageBytes: ByteArray) {
        try {
            val bitmap = BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)
            if (bitmap != null) {
                // Resize để tăng tốc độ so sánh
                val resized = Bitmap.createScaledBitmap(bitmap, 300, 300, true)
                referenceImages[imageId] = resized
                Log.d(TAG, "Added reference image: $imageId (${resized.width}x${resized.height})")
            } else {
                Log.e(TAG, "Failed to decode reference image: $imageId")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error adding reference image $imageId: ${e.message}", e)
        }
    }
    
    /**
     * Detect image từ camera frame
     */
    fun detectImage(
        width: Int,
        height: Int,
        format: String,
        planes: List<Map<String, Any>>,
        result: MethodChannel.Result
    ) {
        // Throttle detection
        val now = System.currentTimeMillis()
        if (isDetecting || now - lastDetectionTime < detectionThrottleMs) {
            result.success(null)
            return
        }
        
        isDetecting = true
        lastDetectionTime = now
        
        launch {
            try {
                // Convert camera data to Bitmap
                val bitmap = convertCameraImageToBitmap(width, height, planes)
                
                if (bitmap != null) {
                    // So sánh với reference images
                    val detectedId = compareWithReferenceImages(bitmap)
                    
                    withContext(Dispatchers.Main) {
                        if (detectedId != null) {
                            // Notify Flutter về detection
                            methodChannel.invokeMethod("onImageDetected", mapOf(
                                "imageId" to detectedId
                            ))
                            result.success(detectedId)
                        } else {
                            result.success(null)
                        }
                    }
                } else {
                    withContext(Dispatchers.Main) {
                        result.success(null)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error detecting image: ${e.message}", e)
                withContext(Dispatchers.Main) {
                    result.error("DETECTION_ERROR", e.message, null)
                }
            } finally {
                isDetecting = false
            }
        }
    }
    
    /**
     * Convert camera image data to Bitmap
     */
    private fun convertCameraImageToBitmap(
        width: Int,
        height: Int,
        planes: List<Map<String, Any>>
    ): Bitmap? {
        return try {
            // Lấy Y plane (plane đầu tiên)
            val yPlane = planes[0]["bytes"] as ByteArray
            val uPlane = planes[1]["bytes"] as ByteArray
            val vPlane = planes[2]["bytes"] as ByteArray
            
            // Convert YUV to NV21 format
            val nv21 = ByteArray(yPlane.size + uPlane.size + vPlane.size)
            System.arraycopy(yPlane, 0, nv21, 0, yPlane.size)
            
            // Convert NV21 to JPEG then to Bitmap
            val yuvImage = YuvImage(nv21, ImageFormat.NV21, width, height, null)
            val out = ByteArrayOutputStream()
            yuvImage.compressToJpeg(Rect(0, 0, width, height), 50, out)
            val imageBytes = out.toByteArray()
            
            BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)
        } catch (e: Exception) {
            Log.e(TAG, "Error converting camera image: ${e.message}", e)
            null
        }
    }
    
    /**
     * So sánh bitmap với reference images sử dụng simple template matching
     */
    private suspend fun compareWithReferenceImages(bitmap: Bitmap): String? = withContext(Dispatchers.Default) {
        if (referenceImages.isEmpty()) {
            Log.w(TAG, "No reference images loaded")
            return@withContext null
        }
        
        // Resize input image để so sánh
        val resizedInput = Bitmap.createScaledBitmap(bitmap, 300, 300, true)
        
        var bestMatch: String? = null
        var bestScore = 0.0
        
        // So sánh với mỗi reference image
        for ((imageId, refBitmap) in referenceImages) {
            val score = calculateSimilarity(resizedInput, refBitmap)
            Log.d(TAG, "Similarity with $imageId: $score")
            
            if (score > bestScore && score > MATCH_THRESHOLD) {
                bestScore = score
                bestMatch = imageId
            }
        }
        
        if (bestMatch != null) {
            Log.d(TAG, "Detected image: $bestMatch (confidence: $bestScore)")
        }
        
        bestMatch
    }
    
    /**
     * Tính toán similarity giữa 2 bitmaps
     * Sử dụng histogram comparison (đơn giản nhưng hiệu quả)
     */
    private fun calculateSimilarity(bitmap1: Bitmap, bitmap2: Bitmap): Double {
        if (bitmap1.width != bitmap2.width || bitmap1.height != bitmap2.height) {
            return 0.0
        }
        
        try {
            val width = bitmap1.width
            val height = bitmap1.height
            
            // Calculate histogram
            val hist1 = IntArray(256) { 0 }
            val hist2 = IntArray(256) { 0 }
            
            var matchingPixels = 0
            val totalPixels = width * height
            val threshold = 30 // Color difference threshold
            
            for (x in 0 until width step 2) { // Sample every 2 pixels để tăng tốc
                for (y in 0 until height step 2) {
                    val pixel1 = bitmap1.getPixel(x, y)
                    val pixel2 = bitmap2.getPixel(x, y)
                    
                    // Compare color channels
                    val r1 = (pixel1 shr 16) and 0xFF
                    val g1 = (pixel1 shr 8) and 0xFF
                    val b1 = pixel1 and 0xFF
                    
                    val r2 = (pixel2 shr 16) and 0xFF
                    val g2 = (pixel2 shr 8) and 0xFF
                    val b2 = pixel2 and 0xFF
                    
                    val diff = Math.abs(r1 - r2) + Math.abs(g1 - g2) + Math.abs(b1 - b2)
                    
                    if (diff < threshold) {
                        matchingPixels++
                    }
                    
                    // Build histogram
                    val gray1 = (r1 + g1 + b1) / 3
                    val gray2 = (r2 + g2 + b2) / 3
                    hist1[gray1]++
                    hist2[gray2]++
                }
            }
            
            // Calculate similarity từ histogram correlation
            val histSimilarity = calculateHistogramCorrelation(hist1, hist2)
            
            // Calculate pixel matching ratio
            val pixelSimilarity = matchingPixels.toDouble() / (totalPixels / 4) // Chia 4 vì step 2
            
            // Combine 2 metrics
            return (histSimilarity * 0.4 + pixelSimilarity * 0.6)
            
        } catch (e: Exception) {
            Log.e(TAG, "Error calculating similarity: ${e.message}", e)
            return 0.0
        }
    }
    
    /**
     * Calculate histogram correlation
     */
    private fun calculateHistogramCorrelation(hist1: IntArray, hist2: IntArray): Double {
        var sum1 = 0.0
        var sum2 = 0.0
        var sum12 = 0.0
        var sum1Sq = 0.0
        var sum2Sq = 0.0
        
        for (i in hist1.indices) {
            val h1 = hist1[i].toDouble()
            val h2 = hist2[i].toDouble()
            
            sum1 += h1
            sum2 += h2
            sum12 += h1 * h2
            sum1Sq += h1 * h1
            sum2Sq += h2 * h2
        }
        
        val n = hist1.size.toDouble()
        val numerator = n * sum12 - sum1 * sum2
        val denominator = Math.sqrt((n * sum1Sq - sum1 * sum1) * (n * sum2Sq - sum2 * sum2))
        
        return if (denominator > 0) {
            Math.abs(numerator / denominator)
        } else {
            0.0
        }
    }
    
    /**
     * Start detection
     */
    fun startDetection() {
        Log.d(TAG, "Detection started")
    }
    
    /**
     * Stop detection
     */
    fun stopDetection() {
        Log.d(TAG, "Detection stopped")
        isDetecting = false
    }
    
    /**
     * Cleanup
     */
    fun cleanup() {
        job.cancel()
        referenceImages.clear()
        imageLabeler.close()
        Log.d(TAG, "Cleaned up")
    }
}

