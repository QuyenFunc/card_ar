import 'dart:async';
import 'dart:typed_data';
import 'package:flutter/services.dart';
import 'package:google_mlkit_image_labeling/google_mlkit_image_labeling.dart';
import 'package:camera/camera.dart';

/// Service để detect ảnh thẻ bài sử dụng ML Kit và template matching
class ImageDetectionService {
  static const platform = MethodChannel('com.example.card_ar_app/image_detection');
  
  // ML Kit image labeler
  ImageLabeler? _imageLabeler;
  
  // Reference images data
  final Map<String, Uint8List> _referenceImages = {};
  
  // Detection state
  bool _isProcessing = false;
  DateTime? _lastDetectionTime;
  static const _detectionThrottle = Duration(milliseconds: 500);
  
  // Callbacks
  Function(String imageId)? onImageDetected;
  
  ImageDetectionService() {
    _initializeLabeler();
    _setupPlatformChannel();
  }
  
  /// Khởi tạo ML Kit Image Labeler
  void _initializeLabeler() {
    final options = ImageLabelerOptions(
      confidenceThreshold: 0.7,
    );
    _imageLabeler = ImageLabeler(options: options);
  }
  
  /// Setup platform channel để nhận callback từ native
  void _setupPlatformChannel() {
    platform.setMethodCallHandler((call) async {
      switch (call.method) {
        case 'onImageDetected':
          final imageId = call.arguments['imageId'] as String?;
          if (imageId != null && onImageDetected != null) {
            onImageDetected!(imageId);
          }
          break;
        default:
          break;
      }
    });
  }
  
  /// Load reference images để so sánh
  Future<void> loadReferenceImages(Map<String, String> imagePaths) async {
    for (final entry in imagePaths.entries) {
      try {
        final ByteData data = await rootBundle.load(entry.value);
        _referenceImages[entry.key] = data.buffer.asUint8List();
        print('[ImageDetection] Loaded reference image: ${entry.key}');
      } catch (e) {
        print('[ImageDetection] Error loading ${entry.key}: $e');
      }
    }
    
    // Gửi reference images xuống native để so sánh
    await _sendReferenceImagesToNative();
  }
  
  /// Gửi reference images xuống native platform
  Future<void> _sendReferenceImagesToNative() async {
    try {
      for (final entry in _referenceImages.entries) {
        await platform.invokeMethod('addReferenceImage', {
          'imageId': entry.key,
          'imageBytes': entry.value,
        });
      }
      print('[ImageDetection] Sent ${_referenceImages.length} reference images to native');
    } catch (e) {
      print('[ImageDetection] Error sending reference images: $e');
    }
  }
  
  /// Process camera image để detect thẻ bài
  Future<String?> processImage(CameraImage cameraImage) async {
    // Throttle detection
    if (_isProcessing) return null;
    
    final now = DateTime.now();
    if (_lastDetectionTime != null && 
        now.difference(_lastDetectionTime!) < _detectionThrottle) {
      return null;
    }
    
    _isProcessing = true;
    _lastDetectionTime = now;
    
    try {
      // Convert CameraImage to format cho native processing
      final result = await platform.invokeMethod<String>('detectImage', {
        'width': cameraImage.width,
        'height': cameraImage.height,
        'format': cameraImage.format.group.toString(),
        'planes': cameraImage.planes.map((plane) => {
          'bytes': plane.bytes,
          'bytesPerRow': plane.bytesPerRow,
        }).toList(),
      });
      
      return result;
    } catch (e) {
      print('[ImageDetection] Error processing image: $e');
      return null;
    } finally {
      _isProcessing = false;
    }
  }
  
  /// Start continuous detection
  Future<void> startDetection() async {
    try {
      await platform.invokeMethod('startDetection');
      print('[ImageDetection] Started continuous detection');
    } catch (e) {
      print('[ImageDetection] Error starting detection: $e');
    }
  }
  
  /// Stop detection
  Future<void> stopDetection() async {
    try {
      await platform.invokeMethod('stopDetection');
      print('[ImageDetection] Stopped detection');
    } catch (e) {
      print('[ImageDetection] Error stopping detection: $e');
    }
  }
  
  /// Cleanup resources
  Future<void> dispose() async {
    await stopDetection();
    _imageLabeler?.close();
    _imageLabeler = null;
    _referenceImages.clear();
    onImageDetected = null;
  }
}

