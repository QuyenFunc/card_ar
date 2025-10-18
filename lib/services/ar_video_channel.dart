import 'dart:io';
import 'package:flutter/services.dart';
import 'package:flutter/foundation.dart';

/// Service để giao tiếp với Android native code
/// thông qua Platform Channel cho AR video functionality
class ArVideoChannel {
  static const MethodChannel _channel =
      MethodChannel('com.example.card_ar_app/ar_video');

  /// Initialize AR session (Android only)
  static Future<bool> initializeAR() async {
    if (!Platform.isAndroid) {
      debugPrint('[ArVideoChannel] AR video channel only works on Android');
      return false;
    }

    try {
      final result = await _channel.invokeMethod<bool>('initializeAR');
      debugPrint('[ArVideoChannel] AR initialized: $result');
      return result ?? false;
    } catch (e) {
      debugPrint('[ArVideoChannel] Error initializing AR: $e');
      return false;
    }
  }

  /// Place video tại vị trí anchor
  /// 
  /// Parameters:
  /// - [videoPath]: Đường dẫn đến video (assets/videos/video_1.mp4)
  /// - [anchorData]: Map chứa pose data {tx, ty, tz, qx, qy, qz, qw}
  /// - [width]: Chiều rộng của video plane (meters)
  static Future<bool> placeVideoAtAnchor({
    required String videoPath,
    required Map<String, double> anchorData,
    double width = 1.0,
  }) async {
    if (!Platform.isAndroid) {
      debugPrint('[ArVideoChannel] Video placement only works on Android');
      return false;
    }

    try {
      debugPrint('[ArVideoChannel] Placing video at anchor:');
      debugPrint('  - Video: $videoPath');
      debugPrint('  - Anchor: $anchorData');
      debugPrint('  - Width: ${width}m');

      final result = await _channel.invokeMethod<bool>(
        'placeVideoAtAnchor',
        {
          'videoPath': videoPath,
          'anchorData': anchorData,
          'width': width,
        },
      );

      debugPrint('[ArVideoChannel] Video placed: $result');
      return result ?? false;
    } catch (e) {
      debugPrint('[ArVideoChannel] Error placing video: $e');
      return false;
    }
  }

  /// Toggle play/pause video
  static Future<bool> togglePlayPause() async {
    if (!Platform.isAndroid) return false;

    try {
      final isPlaying = await _channel.invokeMethod<bool>('togglePlayPause');
      debugPrint('[ArVideoChannel] Video playing: $isPlaying');
      return isPlaying ?? false;
    } catch (e) {
      debugPrint('[ArVideoChannel] Error toggling play/pause: $e');
      return false;
    }
  }

  /// Remove video khỏi scene
  static Future<bool> removeVideo() async {
    if (!Platform.isAndroid) return false;

    try {
      final result = await _channel.invokeMethod<bool>('removeVideo');
      debugPrint('[ArVideoChannel] Video removed: $result');
      return result ?? false;
    } catch (e) {
      debugPrint('[ArVideoChannel] Error removing video: $e');
      return false;
    }
  }

  /// Pause AR session
  static Future<bool> pauseAR() async {
    if (!Platform.isAndroid) return false;

    try {
      final result = await _channel.invokeMethod<bool>('pauseAR');
      debugPrint('[ArVideoChannel] AR paused: $result');
      return result ?? false;
    } catch (e) {
      debugPrint('[ArVideoChannel] Error pausing AR: $e');
      return false;
    }
  }

  /// Resume AR session
  static Future<bool> resumeAR() async {
    if (!Platform.isAndroid) return false;

    try {
      final result = await _channel.invokeMethod<bool>('resumeAR');
      debugPrint('[ArVideoChannel] AR resumed: $result');
      return result ?? false;
    } catch (e) {
      debugPrint('[ArVideoChannel] Error resuming AR: $e');
      return false;
    }
  }

  /// Helper: Tạo anchor data từ ARCore HitTestResult
  static Map<String, double> createAnchorDataFromPose({
    required double tx,
    required double ty,
    required double tz,
    double qx = 0.0,
    double qy = 0.0,
    double qz = 0.0,
    double qw = 1.0,
  }) {
    return {
      'tx': tx,
      'ty': ty,
      'tz': tz,
      'qx': qx,
      'qy': qy,
      'qz': qz,
      'qw': qw,
    };
  }
}

