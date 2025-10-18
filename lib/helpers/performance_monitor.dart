import 'dart:async';
import 'package:flutter/foundation.dart';
import 'package:flutter/scheduler.dart';

class PerformanceMonitor {
  static final PerformanceMonitor _instance = PerformanceMonitor._internal();
  factory PerformanceMonitor() => _instance;
  PerformanceMonitor._internal();

  Timer? _fpsTimer;
  int _frameCount = 0;
  double _currentFps = 60.0;
  // DateTime _lastFrameTime = DateTime.now();
  
  // Performance thresholds
  static const double _minAcceptableFps = 30.0;
  static const int _maxFrameDropCount = 5;
  int _consecutiveFrameDrops = 0;
  
  // Callbacks
  Function(double fps)? onFpsUpdate;
  Function()? onPerformanceIssue;
  
  void startMonitoring() {
    if (!kDebugMode) return; // Only monitor in debug mode
    
    SchedulerBinding.instance.addPersistentFrameCallback(_onFrame);
    
    _fpsTimer = Timer.periodic(const Duration(seconds: 1), (_) {
      _currentFps = _frameCount.toDouble();
      _frameCount = 0;
      
      onFpsUpdate?.call(_currentFps);
      
      // Check for performance issues
      if (_currentFps < _minAcceptableFps) {
        _consecutiveFrameDrops++;
        if (_consecutiveFrameDrops >= _maxFrameDropCount) {
          onPerformanceIssue?.call();
          // Only log once per performance issue cycle
          if (_consecutiveFrameDrops == _maxFrameDropCount) {
            debugPrint('⚠️ Performance issue detected: FPS = $_currentFps');
          }
        }
      } else {
        _consecutiveFrameDrops = 0;
      }
    });
  }
  
  void stopMonitoring() {
    _fpsTimer?.cancel();
    _fpsTimer = null;
    _frameCount = 0;
    _consecutiveFrameDrops = 0;
  }
  
  void _onFrame(Duration timestamp) {
    _frameCount++;
  }
  
  // Helper methods for performance optimization
  static Future<T> measureAsync<T>(String label, Future<T> Function() operation) async {
    if (!kDebugMode) return operation();
    
    final stopwatch = Stopwatch()..start();
    try {
      final result = await operation();
      stopwatch.stop();
      debugPrint('⏱️ $label took ${stopwatch.elapsedMilliseconds}ms');
      return result;
    } catch (e) {
      stopwatch.stop();
      debugPrint('❌ $label failed after ${stopwatch.elapsedMilliseconds}ms: $e');
      rethrow;
    }
  }
  
  static T measureSync<T>(String label, T Function() operation) {
    if (!kDebugMode) return operation();
    
    final stopwatch = Stopwatch()..start();
    try {
      final result = operation();
      stopwatch.stop();
      debugPrint('⏱️ $label took ${stopwatch.elapsedMilliseconds}ms');
      return result;
    } catch (e) {
      stopwatch.stop();
      debugPrint('❌ $label failed after ${stopwatch.elapsedMilliseconds}ms: $e');
      rethrow;
    }
  }
  
  // Memory monitoring
  static void logMemoryUsage(String context) {
    if (!kDebugMode) return;
    
    // Note: This is a simplified version. In production, you might want to use
    // more sophisticated memory profiling tools
    debugPrint('📊 Memory check at $context');
  }
  
  double get currentFps => _currentFps;
  bool get isPerformanceHealthy => _currentFps >= _minAcceptableFps;
}
