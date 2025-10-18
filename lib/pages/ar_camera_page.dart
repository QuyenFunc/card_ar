import 'dart:io';
import 'dart:typed_data';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

import '../models/card_data.dart';
import '../widgets/ar_image_tracking_view.dart';

class ARCameraPage extends StatefulWidget {
  const ARCameraPage({super.key});

  @override
  State<ARCameraPage> createState() => _ARCameraPageState();
}

class _ARCameraPageState extends State<ARCameraPage> {
  // AR Controller
  ARImageTrackingController? _arController;
  
  // State variables
  String? _detectedCardId;
  bool _isVideoPlaying = false;
  bool _isProcessing = false;
  bool _isARReady = false;
  String _statusMessage = 'Đang khởi tạo AR...';
  
  // Set để lưu các card đã được xử lý
  final Set<String> _processedCards = {};
  
  @override
  void dispose() {
    _arController?.dispose();
    _processedCards.clear();
    super.dispose();
  }
  
  @override
  void initState() {
    super.initState();
  }
  
  // Reset toàn bộ
  void _resetAllCards() {
    print('[AR] Reset all cards');
    _processedCards.clear();
    _resetAR();
  }

  // ============ AR VIEW CALLBACKS ============
  Future<void> onARViewCreated(ARImageTrackingController controller) async {
    _arController = controller;
    
    setState(() {
      _statusMessage = 'Đang tải reference images...';
    });
    
    // Load tất cả reference images
    final cards = CardData.getAllCards();
    
    for (final card in cards) {
      try {
        final ByteData imageData = await rootBundle.load(card.imagePath);
        final Uint8List bytes = imageData.buffer.asUint8List();
        
        await _arController!.addReferenceImage(
          card.id,
          bytes,
          card.physicalWidth,
        );
        
        print('[AR] Added reference image: ${card.name}');
      } catch (e) {
        print('[AR] Error loading image ${card.imagePath}: $e');
      }
    }
    
    // Setup augmented images database
    await _arController!.setupAugmentedImages();
    
    // Resume AR session
    await _arController!.resume();
    
    setState(() {
      _isARReady = true;
      _statusMessage = 'Sẵn sàng! Hướng camera vào thẻ bài để quét';
    });
    
    print('[AR] AR image tracking ready');
  }

  void onImageDetected(String imageId, Map<String, double> position) {
    print('[AR] ===== IMAGE DETECTED =====');
    print('[AR] Image ID: $imageId');
    print('[AR] 3D Position: ${position['x']}, ${position['y']}, ${position['z']}');
    
    // Tránh xử lý lặp lại
    if (_processedCards.contains(imageId) || _isProcessing) {
      print('[AR] Already processed: $imageId');
      return;
    }
    
    setState(() {
      _detectedCardId = imageId;
      final card = CardData.getCardById(imageId);
      _statusMessage = 'Phát hiện ${card?.name ?? "thẻ bài"}! Đang phát video...';
    });
    
    HapticFeedback.mediumImpact();
    
    // Thêm vào danh sách đã xử lý
    _processedCards.add(imageId);
    
    // Tự động phát video tại vị trí thẻ bài (native rendering)
    _playNativeVideoAtImage(imageId);
  }
  
  void onPositionUpdate(String imageId, double screenX, double screenY) {
    // Native code tự động update video position
    // Flutter không cần làm gì cả
  }

  Future<void> _playNativeVideoAtImage(String imageId) async {
    if (_isVideoPlaying) return;
    
    print('[AR] Playing native video for: $imageId');
    
    setState(() {
      _isProcessing = true;
      _statusMessage = 'Đang phát video...';
    });

    try {
      final card = CardData.getCardById(imageId);
      if (card == null) {
        throw Exception('Không tìm thấy thẻ bài');
      }

      // Gọi native code để play video
      print('[AR] Calling native playVideo: ${card.videoPath}');
      final success = await _arController?.playVideo(imageId, card.videoPath);
      
      if (success == true) {
        print('[AR] ✓ Native video started successfully!');
        
        if (mounted) {
          setState(() {
            _isVideoPlaying = true;
            _isProcessing = false;
            _detectedCardId = card.id;
            _statusMessage = 'Video đang phát! Di chuyển camera để xem 3D';
          });
        }
        
        HapticFeedback.mediumImpact();
      } else {
        throw Exception('Native video playback failed');
      }

    } catch (e) {
      setState(() {
        _isProcessing = false;
        _statusMessage = 'Lỗi phát video: ${e.toString()}';
      });
      print('[AR] Error: $e');
    }
  }

  void _resetAR() {
    print('[AR] Resetting AR');
    
    // Stop native video
    if (_detectedCardId != null) {
      _arController?.stopVideo(_detectedCardId!);
    }
    
    setState(() {
      _isVideoPlaying = false;
      _detectedCardId = null;
      _isProcessing = false;
      _statusMessage = 'Hướng camera vào thẻ bài để quét';
    });
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      body: Stack(
        children: [
          // Custom ARCore view với image tracking
          if (Platform.isAndroid)
            ARImageTrackingView(
              onViewCreated: onARViewCreated,
              onImageDetected: onImageDetected,
              onPositionUpdate: onPositionUpdate,
            )
          else
            // iOS fallback - cần implement ARKit
            Container(
              color: Colors.black,
              child: Center(
                child: Text(
                  'AR chỉ hỗ trợ trên Android hiện tại',
                  style: TextStyle(color: Colors.white),
                ),
              ),
            ),

          // Video được render trực tiếp bởi native ARCore code
          // Không cần Flutter overlay widget nữa!

          // Top UI
          Positioned(
            top: 0,
            left: 0,
            right: 0,
            child: Container(
              decoration: BoxDecoration(
                gradient: LinearGradient(
                  begin: Alignment.topCenter,
                  end: Alignment.bottomCenter,
                  colors: [Colors.black.withOpacity(0.7), Colors.transparent],
                ),
              ),
              child: SafeArea(
                child: Padding(
                  padding: const EdgeInsets.all(16.0),
                  child: Row(
                    children: [
                      // Close button
                      Container(
                        decoration: BoxDecoration(
                          color: Colors.white.withOpacity(0.3),
                          borderRadius: BorderRadius.circular(12),
                        ),
                        child: IconButton(
                          icon: const Icon(Icons.arrow_back, color: Colors.white),
                          onPressed: () => Navigator.pop(context),
                        ),
                      ),
                      
                      // Stop video button
                      if (_isVideoPlaying)
                        Container(
                          margin: EdgeInsets.only(left: 8),
                          decoration: BoxDecoration(
                            color: Colors.red.withOpacity(0.8),
                            borderRadius: BorderRadius.circular(12),
                          ),
                          child: IconButton(
                            icon: const Icon(Icons.stop, color: Colors.white),
                            onPressed: () {
                              HapticFeedback.mediumImpact();
                              _resetAR();
                              ScaffoldMessenger.of(context).showSnackBar(
                                SnackBar(
                                  content: Row(
                                    children: [
                                      Icon(Icons.check_circle, color: Colors.white),
                                      SizedBox(width: 8),
                                      Text('Đã đóng! Quét thẻ khác'),
                                    ],
                                  ),
                                  backgroundColor: Colors.green,
                                  duration: Duration(seconds: 2),
                                  behavior: SnackBarBehavior.floating,
                                ),
                              );
                            },
                            tooltip: 'Dừng video',
                          ),
                        ),
                      
                      // Reset button
                      if (_processedCards.isNotEmpty && !_isVideoPlaying)
                        Container(
                          margin: EdgeInsets.only(left: 8),
                          decoration: BoxDecoration(
                            color: Colors.orange.withOpacity(0.8),
                            borderRadius: BorderRadius.circular(12),
                          ),
                          child: IconButton(
                            icon: const Icon(Icons.refresh, color: Colors.white),
                            onPressed: () {
                              HapticFeedback.mediumImpact();
                              _resetAllCards();
                              ScaffoldMessenger.of(context).showSnackBar(
                                SnackBar(
                                  content: Row(
                                    children: [
                                      Icon(Icons.refresh, color: Colors.white),
                                      SizedBox(width: 8),
                                      Text('Đã reset! Quét lại tất cả thẻ'),
                                    ],
                                  ),
                                  backgroundColor: Colors.orange,
                                  duration: Duration(seconds: 2),
                                  behavior: SnackBarBehavior.floating,
                                ),
                              );
                            },
                            tooltip: 'Reset',
                          ),
                        ),
                      const SizedBox(width: 12),
                      // Status
                      Expanded(
                        child: Container(
                          padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 12),
                          decoration: BoxDecoration(
                            color: Colors.white.withOpacity(0.3),
                            borderRadius: BorderRadius.circular(12),
                          ),
                          child: Text(
                            _statusMessage,
                            style: const TextStyle(
                              color: Colors.white,
                              fontSize: 14,
                              fontWeight: FontWeight.w500,
                            ),
                            textAlign: TextAlign.center,
                            maxLines: 2,
                            overflow: TextOverflow.ellipsis,
                          ),
                        ),
                      ),
                    ],
                  ),
                ),
              ),
            ),
          ),

          // Bottom UI
          Positioned(
            bottom: 0,
            left: 0,
            right: 0,
            child: Container(
              decoration: BoxDecoration(
                gradient: LinearGradient(
                  begin: Alignment.bottomCenter,
                  end: Alignment.topCenter,
                  colors: [Colors.black.withOpacity(0.7), Colors.transparent],
                ),
              ),
              child: SafeArea(
                child: Padding(
                  padding: const EdgeInsets.all(20.0),
                  child: Column(
                    mainAxisSize: MainAxisSize.min,
                    children: [
                      // Detected card badge
                      if (_detectedCardId != null && !_isVideoPlaying)
                        Container(
                          margin: const EdgeInsets.only(bottom: 16),
                          padding: const EdgeInsets.symmetric(horizontal: 20, vertical: 12),
                          decoration: BoxDecoration(
                            color: Colors.green.withOpacity(0.8),
                            borderRadius: BorderRadius.circular(12),
                          ),
                          child: Row(
                            mainAxisSize: MainAxisSize.min,
                            children: [
                              const Icon(Icons.check_circle, color: Colors.white, size: 20),
                              const SizedBox(width: 8),
                              Text(
                                'Đã phát hiện: ${CardData.getCardById(_detectedCardId!)?.name ?? ""}',
                                style: const TextStyle(
                                  color: Colors.white,
                                  fontSize: 14,
                                  fontWeight: FontWeight.w600,
                                ),
                              ),
                            ],
                          ),
                        ),

                      // Instructions
                      if (!_isVideoPlaying && _detectedCardId == null && _isARReady)
                        Container(
                          padding: const EdgeInsets.symmetric(horizontal: 20, vertical: 16),
                          decoration: BoxDecoration(
                            color: Colors.blue.withOpacity(0.8),
                            borderRadius: BorderRadius.circular(12),
                          ),
                          child: Column(
                            mainAxisSize: MainAxisSize.min,
                            children: [
                              Icon(Icons.camera_alt, color: Colors.white, size: 32),
                              const SizedBox(height: 8),
                              Text(
                                'Hướng camera vào thẻ bài',
                                style: const TextStyle(
                                  color: Colors.white,
                                  fontSize: 16,
                                  fontWeight: FontWeight.bold,
                                ),
                              ),
                              const SizedBox(height: 4),
                              Text(
                                'Giữ camera ổn định để AR nhận diện',
                                style: const TextStyle(
                                  color: Colors.white70,
                                  fontSize: 12,
                                ),
                                textAlign: TextAlign.center,
                              ),
                            ],
                          ),
                        ),
                    ],
                  ),
                ),
              ),
            ),
          ),

          // Loading
          if (_isProcessing)
            Container(
              color: Colors.black54,
              child: const Center(
                child: Column(
                  mainAxisSize: MainAxisSize.min,
                  children: [
                    CircularProgressIndicator(
                      valueColor: AlwaysStoppedAnimation<Color>(Colors.white),
                    ),
                    SizedBox(height: 16),
                    Text(
                      'Đang tải video...',
                      style: TextStyle(color: Colors.white, fontSize: 16),
                    ),
                  ],
                ),
              ),
            ),
        ],
      ),
    );
  }
}
