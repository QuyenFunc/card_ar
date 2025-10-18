import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:video_player/video_player.dart';
import 'package:vector_math/vector_math_64.dart' as vector;

/// Widget hiển thị video overlay được "gắn" vào vị trí AR
class ARVideoOverlay extends StatefulWidget {
  final VideoPlayerController videoController;
  final String cardName;
  final VoidCallback onClose;
  final vector.Vector3? anchorPosition;
  final vector.Matrix4? cameraTransform;
  
  const ARVideoOverlay({
    Key? key,
    required this.videoController,
    required this.cardName,
    required this.onClose,
    this.anchorPosition,
    this.cameraTransform,
  }) : super(key: key);

  @override
  State<ARVideoOverlay> createState() => _ARVideoOverlayState();
}

class _ARVideoOverlayState extends State<ARVideoOverlay> with SingleTickerProviderStateMixin {
  late AnimationController _animationController;
  late Animation<double> _scaleAnimation;
  
  @override
  void initState() {
    super.initState();
    
    // Animation cho video appearance
    _animationController = AnimationController(
      duration: const Duration(milliseconds: 300),
      vsync: this,
    );
    
    _scaleAnimation = CurvedAnimation(
      parent: _animationController,
      curve: Curves.easeOutBack,
    );
    
    _animationController.forward();
    
    // Listen to video controller changes
    widget.videoController.addListener(_videoListener);
  }
  
  void _videoListener() {
    if (mounted) {
      setState(() {});
    }
  }
  
  @override
  void dispose() {
    widget.videoController.removeListener(_videoListener);
    _animationController.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    print('[ARVideoOverlay] Building - isInitialized: ${widget.videoController.value.isInitialized}, isPlaying: ${widget.videoController.value.isPlaying}');
    
    return ScaleTransition(
      scale: _scaleAnimation,
      child: Container(
        decoration: BoxDecoration(
          color: Colors.black,
          border: Border.all(color: Colors.green, width: 4),
          borderRadius: BorderRadius.circular(20),
          boxShadow: [
            BoxShadow(
              color: Colors.black.withOpacity(0.8),
              blurRadius: 20,
              spreadRadius: 5,
            ),
            BoxShadow(
              color: Colors.green.withOpacity(0.6),
              blurRadius: 30,
              spreadRadius: 0,
            ),
          ],
        ),
        child: ClipRRect(
          borderRadius: BorderRadius.circular(16),
          child: Stack(
            fit: StackFit.expand,
            children: [
                // Background (cho trường hợp video chưa load)
                Container(color: Colors.black),
                
                // Video player - FULL WIDTH AND HEIGHT  
                if (widget.videoController.value.isInitialized)
                  Positioned.fill(
                    child: FittedBox(
                      fit: BoxFit.cover,
                      alignment: Alignment.center,
                      child: SizedBox(
                        width: widget.videoController.value.size.width,
                        height: widget.videoController.value.size.height,
                        child: VideoPlayer(widget.videoController),
                      ),
                    ),
                  ),
                
                // Loading indicator
                if (!widget.videoController.value.isInitialized)
                  Center(
                    child: Column(
                      mainAxisSize: MainAxisSize.min,
                      children: [
                        CircularProgressIndicator(
                          valueColor: AlwaysStoppedAnimation<Color>(Colors.white),
                        ),
                        SizedBox(height: 8),
                        Text(
                          'Loading video...',
                          style: TextStyle(color: Colors.white, fontSize: 12),
                        ),
                      ],
                    ),
                  ),
                
                // Tap để pause/play
                Positioned.fill(
                  child: GestureDetector(
                    onTap: () {
                      setState(() {
                        if (widget.videoController.value.isPlaying) {
                          widget.videoController.pause();
                        } else {
                          widget.videoController.play();
                        }
                      });
                      HapticFeedback.lightImpact();
                    },
                    child: Container(color: Colors.transparent),
                  ),
                ),
                
                // Play button khi pause
                if (!widget.videoController.value.isPlaying)
                  Center(
                    child: Container(
                      width: 60,
                      height: 60,
                      decoration: BoxDecoration(
                        gradient: LinearGradient(
                          begin: Alignment.topLeft,
                          end: Alignment.bottomRight,
                          colors: [
                            Colors.green.shade400,
                            Colors.green.shade600,
                          ],
                        ),
                        shape: BoxShape.circle,
                        border: Border.all(color: Colors.white, width: 3),
                        boxShadow: [
                          BoxShadow(
                            color: Colors.green.withOpacity(0.5),
                            blurRadius: 10,
                            spreadRadius: 2,
                          ),
                        ],
                      ),
                      child: Icon(
                        Icons.play_arrow_rounded,
                        color: Colors.white,
                        size: 36,
                      ),
                    ),
                  ),
                
                // Tiêu đề video
                Positioned(
                  top: 8,
                  left: 8,
                  child: Container(
                    padding: EdgeInsets.symmetric(horizontal: 10, vertical: 5),
                    decoration: BoxDecoration(
                      color: Colors.black.withOpacity(0.7),
                      borderRadius: BorderRadius.circular(8),
                    ),
                    child: Row(
                      mainAxisSize: MainAxisSize.min,
                      children: [
                        Icon(Icons.play_circle_filled, color: Colors.green, size: 14),
                        SizedBox(width: 5),
                        Text(
                          widget.cardName,
                          style: TextStyle(
                            color: Colors.white,
                            fontSize: 11,
                            fontWeight: FontWeight.w600,
                          ),
                        ),
                      ],
                    ),
                  ),
                ),
                
                // Close button
                Positioned(
                  top: -10,
                  right: -10,
                  child: Material(
                    color: Colors.transparent,
                    child: InkWell(
                      onTap: () {
                        HapticFeedback.mediumImpact();
                        widget.onClose();
                      },
                      customBorder: CircleBorder(),
                      child: Container(
                        width: 40,
                        height: 40,
                        decoration: BoxDecoration(
                          gradient: LinearGradient(
                            begin: Alignment.topLeft,
                            end: Alignment.bottomRight,
                            colors: [
                              Color(0xFFFF416C),
                              Color(0xFFFF4B2B),
                            ],
                          ),
                          shape: BoxShape.circle,
                          border: Border.all(color: Colors.white, width: 2.5),
                          boxShadow: [
                            BoxShadow(
                              color: Colors.red.withOpacity(0.5),
                              blurRadius: 8,
                              spreadRadius: 1,
                              offset: Offset(0, 3),
                            ),
                          ],
                        ),
                        child: Icon(
                          Icons.close_rounded,
                          color: Colors.white,
                          size: 22,
                        ),
                      ),
                    ),
                  ),
                ),
            ],
          ),
        ),
      ),
    );
  }
}
