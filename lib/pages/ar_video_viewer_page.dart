import 'dart:async';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:ar_flutter_plugin/ar_flutter_plugin.dart';
import 'package:ar_flutter_plugin/datatypes/config_planedetection.dart';
import 'package:ar_flutter_plugin/managers/ar_anchor_manager.dart';
import 'package:ar_flutter_plugin/managers/ar_location_manager.dart';
import 'package:ar_flutter_plugin/managers/ar_object_manager.dart';
import 'package:ar_flutter_plugin/managers/ar_session_manager.dart';
import 'package:ar_flutter_plugin/models/ar_hittest_result.dart';
import 'package:video_player/video_player.dart';
import 'video_selection_page.dart';

class ARVideoViewerPage extends StatefulWidget {
  final ARVideoData videoData;

  const ARVideoViewerPage({Key? key, required this.videoData}) : super(key: key);

  @override
  State<ARVideoViewerPage> createState() => _ARVideoViewerPageState();
}

class _ARVideoViewerPageState extends State<ARVideoViewerPage> {
  ARSessionManager? arSessionManager;
  ARObjectManager? arObjectManager;
  ARAnchorManager? arAnchorManager;
  
  VideoPlayerController? videoController;
  bool isVideoPlaced = false;
  double videoAspectRatio = 16 / 9;
  
  // Video position tracking
  Offset? videoScreenPosition;
  double videoScale = 1.0;

  @override
  void initState() {
    super.initState();
    _initializeVideo();
  }

  Future<void> _initializeVideo() async {
    try {
      print('[AR Video] Initializing video: ${widget.videoData.videoPath}');
      videoController = VideoPlayerController.asset(widget.videoData.videoPath);
      await videoController!.initialize();
      
      if (videoController!.value.size.width > 0 && 
          videoController!.value.size.height > 0) {
        setState(() {
          videoAspectRatio = videoController!.value.size.width / 
                            videoController!.value.size.height;
        });
        print('[AR Video] Video aspect ratio: $videoAspectRatio');
      }
      
      videoController!.setLooping(true);
      
      videoController!.addListener(() {
        if (mounted) {
          setState(() {});
        }
      });
      
      // Auto play
      await videoController!.play();
      print('[AR Video] Video initialized and playing');
      
    } catch (e) {
      print('[AR Video] Error initializing video: $e');
      _showSnackBar('Lỗi khi tải video: $e');
    }
  }

  @override
  void dispose() {
    arSessionManager?.dispose();
    videoController?.dispose();
    super.dispose();
  }

  void onARViewCreated(
    ARSessionManager arSessionManager,
    ARObjectManager arObjectManager,
    ARAnchorManager arAnchorManager,
    ARLocationManager arLocationManager,
  ) {
    print('[AR Video] AR View Created');
    
    this.arSessionManager = arSessionManager;
    this.arObjectManager = arObjectManager;
    this.arAnchorManager = arAnchorManager;

    this.arSessionManager!.onInitialize(
      showFeaturePoints: false,
      showPlanes: true,
      showWorldOrigin: false,
      handleTaps: true,
      handlePans: false,
      handleRotation: false,
    );
    
    this.arObjectManager!.onInitialize();
    
    this.arSessionManager!.onPlaneOrPointTap = onPlaneOrPointTapped;
    
    _showSnackBar('Quét xung quanh để phát hiện mặt phẳng');
    print('[AR Video] AR initialized, plane detection enabled');
  }

  Future<void> onPlaneOrPointTapped(
    List<ARHitTestResult> hitTestResults,
  ) async {
    print('[AR Video] Plane/Point tapped! Results: ${hitTestResults.length}');
    
    if (hitTestResults.isEmpty) {
      _showSnackBar('Không phát hiện mặt phẳng, thử lại');
      return;
    }
    
    if (isVideoPlaced) {
      print('[AR Video] Video already placed, toggling play/pause');
      _togglePlayPause();
      return;
    }

    var singleHitTestResult = hitTestResults.first;
    await _placeVideoAtTapLocation(singleHitTestResult);
  }

  Future<void> _placeVideoAtTapLocation(ARHitTestResult hitResult) async {
    try {
      HapticFeedback.mediumImpact();
      print('[AR Video] Placing video at tapped location');

      // Get the world transform matrix
      final matrix = hitResult.worldTransform;
      
      // Extract world position (4th column)
      final worldX = matrix.getColumn(3).x;
      final worldY = matrix.getColumn(3).y;
      final worldZ = matrix.getColumn(3).z;
      
      print('[AR Video] World position: ($worldX, $worldY, $worldZ)');
      
      // For simplicity, we'll place video at center of screen
      // In a real AR SDK with proper projection, you'd project world coords to screen coords
      final screenWidth = MediaQuery.of(context).size.width;
      final screenHeight = MediaQuery.of(context).size.height;
      
      setState(() {
        // Place video at tap location (approximate center)
        videoScreenPosition = Offset(screenWidth / 2, screenHeight / 2);
        videoScale = 1.0;
        isVideoPlaced = true;
      });

      _showSnackBar('Video đã được đặt! Nhấn để play/pause');
      print('[AR Video] Video placed at screen position');
      
    } catch (e) {
      print('[AR Video] Error placing video: $e');
      _showSnackBar('Lỗi khi đặt video: $e');
    }
  }

  void _togglePlayPause() {
    HapticFeedback.lightImpact();
    if (videoController == null) return;

    setState(() {
      if (videoController!.value.isPlaying) {
        videoController!.pause();
        _showSnackBar('Video đã tạm dừng');
      } else {
        videoController!.play();
        _showSnackBar('Video đang phát');
      }
    });
  }

  Future<void> _resetVideo() async {
    HapticFeedback.mediumImpact();
    
    setState(() {
      isVideoPlaced = false;
      videoScreenPosition = null;
    });
    
    _showSnackBar('Đã xóa video. Nhấn lại để đặt video mới');
    print('[AR Video] Video reset');
  }

  void _replayVideo() {
    HapticFeedback.lightImpact();
    if (videoController != null) {
      videoController!.seekTo(Duration.zero);
      videoController!.play();
      _showSnackBar('Phát lại video');
    }
  }

  void _showSnackBar(String message) {
    if (!mounted) return;
    ScaffoldMessenger.of(context).showSnackBar(
      SnackBar(
        content: Text(message),
        duration: const Duration(seconds: 2),
        behavior: SnackBarBehavior.floating,
        margin: const EdgeInsets.only(bottom: 100, left: 20, right: 20),
        shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(10)),
      ),
    );
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      body: Stack(
        children: [
          // AR View
          ARView(
            onARViewCreated: onARViewCreated,
            planeDetectionConfig: PlaneDetectionConfig.horizontalAndVertical,
          ),

          // Video overlay at AR position
          if (isVideoPlaced && videoScreenPosition != null && 
              videoController != null && videoController!.value.isInitialized)
            Positioned(
              left: videoScreenPosition!.dx - (MediaQuery.of(context).size.width * 0.35),
              top: videoScreenPosition!.dy - (MediaQuery.of(context).size.width * 0.35 / videoAspectRatio / 2),
              child: GestureDetector(
                onTap: _togglePlayPause,
                child: Container(
                  width: MediaQuery.of(context).size.width * 0.7,
                  height: MediaQuery.of(context).size.width * 0.7 / videoAspectRatio,
                  decoration: BoxDecoration(
                    borderRadius: BorderRadius.circular(20),
                    boxShadow: [
                      BoxShadow(
                        color: Colors.black.withValues(alpha: 0.6),
                        blurRadius: 30,
                        spreadRadius: 10,
                      ),
                      BoxShadow(
                        color: Colors.blue.withValues(alpha: 0.3),
                        blurRadius: 20,
                        spreadRadius: 5,
                      ),
                    ],
                    border: Border.all(
                      color: Colors.white.withValues(alpha: 0.3),
                      width: 2,
                    ),
                  ),
                  child: ClipRRect(
                    borderRadius: BorderRadius.circular(18),
                    child: Stack(
                      children: [
                        // Video player
                        VideoPlayer(videoController!),
                        
                        // Play/Pause overlay
                        if (!videoController!.value.isPlaying)
                          Container(
                            color: Colors.black.withValues(alpha: 0.4),
                            child: const Center(
                              child: Icon(
                                Icons.play_circle_filled,
                                color: Colors.white,
                                size: 80,
                              ),
                            ),
                          ),
                        
                        // AR label
                        Positioned(
                          top: 8,
                          right: 8,
                          child: Container(
                            padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 4),
                            decoration: BoxDecoration(
                              color: Colors.blue.withValues(alpha: 0.8),
                              borderRadius: BorderRadius.circular(12),
                            ),
                            child: const Row(
                              mainAxisSize: MainAxisSize.min,
                              children: [
                                Icon(Icons.view_in_ar, color: Colors.white, size: 16),
                                SizedBox(width: 4),
                                Text(
                                  'AR',
                                  style: TextStyle(
                                    color: Colors.white,
                                    fontSize: 12,
                                    fontWeight: FontWeight.bold,
                                  ),
                                ),
                              ],
                            ),
                          ),
                        ),
                      ],
                    ),
                  ),
                ),
              ),
            ),

          // Top bar
          Positioned(
            top: 0,
            left: 0,
            right: 0,
            child: Container(
              padding: EdgeInsets.only(
                top: MediaQuery.of(context).padding.top + 10,
                left: 16,
                right: 16,
                bottom: 16,
              ),
              decoration: BoxDecoration(
                gradient: LinearGradient(
                  begin: Alignment.topCenter,
                  end: Alignment.bottomCenter,
                  colors: [
                    Colors.black.withValues(alpha: 0.7),
                    Colors.black.withValues(alpha: 0.0),
                  ],
                ),
              ),
              child: Row(
                children: [
                  // Back button
                  Container(
                    decoration: BoxDecoration(
                      color: Colors.white.withValues(alpha: 0.2),
                      shape: BoxShape.circle,
                    ),
                    child: IconButton(
                      icon: const Icon(Icons.arrow_back, color: Colors.white),
                      onPressed: () {
                        HapticFeedback.lightImpact();
                        Navigator.pop(context);
                      },
                    ),
                  ),
                  const SizedBox(width: 12),
                  // Title
                  Expanded(
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        Text(
                          widget.videoData.name,
                          style: const TextStyle(
                            color: Colors.white,
                            fontSize: 18,
                            fontWeight: FontWeight.bold,
                            shadows: [
                              Shadow(
                                color: Colors.black54,
                                offset: Offset(0, 1),
                                blurRadius: 4,
                              ),
                            ],
                          ),
                        ),
                        const SizedBox(height: 4),
                        Text(
                          isVideoPlaced ? 'Video trong AR' : 'Chế độ AR',
                          style: TextStyle(
                            color: Colors.white.withValues(alpha: 0.9),
                            fontSize: 13,
                          ),
                        ),
                      ],
                    ),
                  ),
                ],
              ),
            ),
          ),

          // Instructions
          Positioned(
            top: MediaQuery.of(context).padding.top + 100,
            left: 0,
            right: 0,
            child: Column(
              children: [
                if (!isVideoPlaced)
                  Container(
                    margin: const EdgeInsets.symmetric(horizontal: 20),
                    padding: const EdgeInsets.all(16),
                    decoration: BoxDecoration(
                      color: Colors.blue.withValues(alpha: 0.9),
                      borderRadius: BorderRadius.circular(15),
                      boxShadow: [
                        BoxShadow(
                          color: Colors.blue.withValues(alpha: 0.3),
                          blurRadius: 10,
                          spreadRadius: 2,
                        ),
                      ],
                    ),
                    child: Column(
                      mainAxisSize: MainAxisSize.min,
                      children: [
                        Row(
                          mainAxisSize: MainAxisSize.min,
                          children: [
                            const Icon(Icons.touch_app, color: Colors.white, size: 28),
                            const SizedBox(width: 12),
                            const Flexible(
                              child: Text(
                                'Di chuyển điện thoại để quét',
                                style: TextStyle(
                                  color: Colors.white,
                                  fontSize: 16,
                                  fontWeight: FontWeight.w600,
                                ),
                              ),
                            ),
                          ],
                        ),
                        const SizedBox(height: 12),
                        Row(
                          mainAxisSize: MainAxisSize.min,
                          children: [
                            Container(
                              width: 12,
                              height: 12,
                              decoration: const BoxDecoration(
                                color: Colors.greenAccent,
                                shape: BoxShape.circle,
                              ),
                            ),
                            const SizedBox(width: 8),
                            const Flexible(
                              child: Text(
                                'Thấy lưới trắng = Mặt phẳng đã phát hiện',
                                style: TextStyle(
                                  color: Colors.white,
                                  fontSize: 13,
                                ),
                              ),
                            ),
                          ],
                        ),
                        const SizedBox(height: 8),
                        const Text(
                          '👉 Nhấn vào lưới trắng để đặt video!',
                          style: TextStyle(
                            color: Colors.white,
                            fontSize: 15,
                            fontWeight: FontWeight.bold,
                          ),
                        ),
                      ],
                    ),
                  )
                else
                  Container(
                    margin: const EdgeInsets.symmetric(horizontal: 20),
                    padding: const EdgeInsets.all(12),
                    decoration: BoxDecoration(
                      color: Colors.green.withValues(alpha: 0.9),
                      borderRadius: BorderRadius.circular(15),
                      boxShadow: [
                        BoxShadow(
                          color: Colors.green.withValues(alpha: 0.3),
                          blurRadius: 10,
                          spreadRadius: 2,
                        ),
                      ],
                    ),
                    child: Row(
                      mainAxisSize: MainAxisSize.min,
                      children: [
                        Icon(
                          videoController?.value.isPlaying ?? false
                              ? Icons.play_circle_filled
                              : Icons.pause_circle_filled,
                          color: Colors.white,
                          size: 28,
                        ),
                        const SizedBox(width: 12),
                        Flexible(
                          child: Text(
                            videoController?.value.isPlaying ?? false
                                ? 'Video đang phát tại vị trí AR đã chọn'
                                : 'Video đã tạm dừng - Nhấn để phát',
                            style: const TextStyle(
                              color: Colors.white,
                              fontSize: 14,
                              fontWeight: FontWeight.w600,
                            ),
                          ),
                        ),
                      ],
                    ),
                  ),
              ],
            ),
          ),

          // Bottom controls
          if (isVideoPlaced)
            Positioned(
              bottom: MediaQuery.of(context).padding.bottom + 30,
              left: 0,
              right: 0,
              child: Container(
                padding: const EdgeInsets.symmetric(horizontal: 20),
                child: Row(
                  mainAxisAlignment: MainAxisAlignment.spaceEvenly,
                  children: [
                    _buildControlButton(
                      icon: Icons.refresh,
                      label: 'Đặt lại',
                      color: Colors.orange,
                      onPressed: _resetVideo,
                    ),
                    _buildControlButton(
                      icon: videoController?.value.isPlaying ?? false
                          ? Icons.pause
                          : Icons.play_arrow,
                      label: videoController?.value.isPlaying ?? false
                          ? 'Tạm dừng'
                          : 'Phát',
                      color: Colors.blue,
                      onPressed: _togglePlayPause,
                    ),
                    _buildControlButton(
                      icon: Icons.replay,
                      label: 'Phát lại',
                      color: Colors.purple,
                      onPressed: _replayVideo,
                    ),
                  ],
                ),
              ),
            ),
        ],
      ),
    );
  }

  Widget _buildControlButton({
    required IconData icon,
    required String label,
    required Color color,
    required VoidCallback onPressed,
  }) {
    return Material(
      color: Colors.transparent,
      child: InkWell(
        onTap: onPressed,
        borderRadius: BorderRadius.circular(15),
        child: Container(
          padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 12),
          decoration: BoxDecoration(
            color: color.withValues(alpha: 0.9),
            borderRadius: BorderRadius.circular(15),
            boxShadow: [
              BoxShadow(
                color: color.withValues(alpha: 0.4),
                blurRadius: 8,
                spreadRadius: 2,
              ),
            ],
          ),
          child: Column(
            mainAxisSize: MainAxisSize.min,
            children: [
              Icon(icon, color: Colors.white, size: 28),
              const SizedBox(height: 4),
              Text(
                label,
                style: const TextStyle(
                  color: Colors.white,
                  fontSize: 12,
                  fontWeight: FontWeight.w600,
                ),
              ),
            ],
          ),
        ),
      ),
    );
  }
}
