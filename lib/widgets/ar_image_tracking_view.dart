import 'package:flutter/foundation.dart';
import 'package:flutter/gestures.dart';
import 'package:flutter/material.dart';
import 'package:flutter/rendering.dart';
import 'package:flutter/services.dart';

typedef ARImageTrackingViewCreatedCallback = void Function(ARImageTrackingController controller);
typedef OnImageDetectedCallback = void Function(String imageId, Map<String, double> position);
typedef OnPositionUpdateCallback = void Function(String imageId, double screenX, double screenY);

/// Custom ARCore view với augmented images tracking
class ARImageTrackingView extends StatefulWidget {
  final ARImageTrackingViewCreatedCallback onViewCreated;
  final OnImageDetectedCallback? onImageDetected;
  final OnPositionUpdateCallback? onPositionUpdate;
  
  const ARImageTrackingView({
    Key? key,
    required this.onViewCreated,
    this.onImageDetected,
    this.onPositionUpdate,
  }) : super(key: key);

  @override
  State<ARImageTrackingView> createState() => _ARImageTrackingViewState();
}

class _ARImageTrackingViewState extends State<ARImageTrackingView> {
  ARImageTrackingController? _controller;

  @override
  Widget build(BuildContext context) {
    const viewType = 'ar_image_tracking_view';

    return PlatformViewLink(
      viewType: viewType,
      surfaceFactory: (context, controller) {
        return AndroidViewSurface(
          controller: controller as AndroidViewController,
          gestureRecognizers: const <Factory<OneSequenceGestureRecognizer>>{},
          hitTestBehavior: PlatformViewHitTestBehavior.transparent,
        );
      },
      onCreatePlatformView: (params) {
        final controller = PlatformViewsService.initSurfaceAndroidView(
          id: params.id,
          viewType: viewType,
          layoutDirection: TextDirection.ltr,
          creationParams: null,
          creationParamsCodec: const StandardMessageCodec(),
        );

        controller.addOnPlatformViewCreatedListener((id) {
          params.onPlatformViewCreated(id);
          _onPlatformViewCreated(id);
        });

        controller.create();
        return controller;
      },
    );
  }

  void _onPlatformViewCreated(int id) {
    _controller = ARImageTrackingController(
      id: id,
      onImageDetected: widget.onImageDetected,
      onPositionUpdate: widget.onPositionUpdate,
    );
    
    widget.onViewCreated(_controller!);
  }
  
  @override
  void dispose() {
    _controller?.dispose();
    super.dispose();
  }
}

/// Controller để điều khiển AR session
class ARImageTrackingController {
  final int id;
  final OnImageDetectedCallback? onImageDetected;
  final OnPositionUpdateCallback? onPositionUpdate;
  late MethodChannel _channel;
  
  ARImageTrackingController({
    required this.id,
    this.onImageDetected,
    this.onPositionUpdate,
  }) {
    _channel = MethodChannel('ar_image_tracking_$id');
    _channel.setMethodCallHandler(_handleMethodCall);
  }
  
  Future<void> _handleMethodCall(MethodCall call) async {
    switch (call.method) {
      case 'onImageDetected':
        final imageId = call.arguments['imageId'] as String?;
        final centerX = call.arguments['centerX'] as double?;
        final centerY = call.arguments['centerY'] as double?;
        final centerZ = call.arguments['centerZ'] as double?;
        final screenX = call.arguments['screenX'] as double?;
        final screenY = call.arguments['screenY'] as double?;
        
        if (imageId != null && centerX != null && centerY != null && centerZ != null) {
          onImageDetected?.call(imageId, {
            'x': centerX,
            'y': centerY,
            'z': centerZ,
            'screenX': screenX ?? 0.0,
            'screenY': screenY ?? 0.0,
          });
        }
        break;
        
      case 'onPositionUpdate':
        final imageId = call.arguments['imageId'] as String?;
        final screenX = call.arguments['screenX'] as double?;
        final screenY = call.arguments['screenY'] as double?;
        
        if (imageId != null && screenX != null && screenY != null) {
          onPositionUpdate?.call(imageId, screenX, screenY);
        }
        break;
        
      case 'onReadyToPlayVideo':
        // Native đã sẵn sàng để play video
        // Flutter sẽ trigger video playback sau khi nhận callback này
        break;
    }
  }
  
  /// Add reference image để tracking
  Future<bool> addReferenceImage(String imageId, Uint8List imageBytes, double physicalWidth) async {
    try {
      final result = await _channel.invokeMethod<bool>('addReferenceImage', {
        'imageId': imageId,
        'imageBytes': imageBytes,
        'physicalWidth': physicalWidth,
      });
      return result ?? false;
    } catch (e) {
      print('[ARController] Error adding reference image: $e');
      return false;
    }
  }
  
  /// Setup augmented images database
  Future<bool> setupAugmentedImages() async {
    try {
      final result = await _channel.invokeMethod<bool>('setupAugmentedImages');
      return result ?? false;
    } catch (e) {
      print('[ARController] Error setup augmented images: $e');
      return false;
    }
  }
  
  /// Resume AR session
  Future<void> resume() async {
    try {
      await _channel.invokeMethod('resume');
    } catch (e) {
      print('[ARController] Error resuming: $e');
    }
  }
  
  /// Pause AR session
  Future<void> pause() async {
    try {
      await _channel.invokeMethod('pause');
    } catch (e) {
      print('[ARController] Error pausing: $e');
    }
  }
  
  /// Play video at detected image (native rendering)
  Future<bool> playVideo(String imageId, String videoPath) async {
    try {
      final result = await _channel.invokeMethod<bool>('playVideo', {
        'imageId': imageId,
        'videoPath': videoPath,
      });
      return result ?? false;
    } catch (e) {
      print('[ARController] Error playing video: $e');
      return false;
    }
  }
  
  /// Pause/resume video
  Future<bool> pauseVideo(String imageId) async {
    try {
      final result = await _channel.invokeMethod<bool>('pauseVideo', {
        'imageId': imageId,
      });
      return result ?? false;
    } catch (e) {
      print('[ARController] Error pausing video: $e');
      return false;
    }
  }
  
  /// Stop video
  Future<bool> stopVideo(String imageId) async {
    try {
      final result = await _channel.invokeMethod<bool>('stopVideo', {
        'imageId': imageId,
      });
      return result ?? false;
    } catch (e) {
      print('[ARController] Error stopping video: $e');
      return false;
    }
  }
  
  /// Dispose
  void dispose() {
    pause();
  }
}

