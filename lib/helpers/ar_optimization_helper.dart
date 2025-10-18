import 'package:flutter/material.dart';
import 'dart:io';

class AROptimizationHelper {
  // Singleton pattern
  static final AROptimizationHelper _instance = AROptimizationHelper._internal();
  factory AROptimizationHelper() => _instance;
  AROptimizationHelper._internal();
  
  // Device performance tiers
  // static const Map<String, int> _devicePerformanceTiers = {
  //   'high': 3,    // Flagship devices
  //   'medium': 2,  // Mid-range devices
  //   'low': 1,     // Budget devices
  // };
  
  int _currentTier = 2; // Default to medium
  
  // Get optimized AR configuration based on device
  Map<String, dynamic> getOptimizedARConfig() {
    return {
      'enablePlaneRenderer': _currentTier >= 2,
      'enableFeaturePoints': _currentTier >= 3,
      'enableUpdateListener': true, // Enable for proper AR tracking
      'maxPlanes': _currentTier >= 3 ? 10 : 5,
      'planeDetectionInterval': _currentTier >= 2 ? 500 : 1000, // milliseconds
      'enableShadows': _currentTier >= 3,
      'enableLightEstimation': _currentTier >= 2,
    };
  }
  
  // Get optimized video settings
  Map<String, dynamic> getOptimizedVideoSettings() {
    return {
      'maxResolution': _currentTier >= 3 ? 1080 : 720,
      'enableHardwareAcceleration': true,
      'preloadBuffer': _currentTier >= 2 ? 5000 : 3000, // milliseconds
      'maxBitrate': _currentTier >= 3 ? 8000000 : 4000000, // bits per second
    };
  }
  
  // Get optimized 3D model settings
  Map<String, dynamic> getOptimized3DModelSettings() {
    return {
      'maxTextureSize': _currentTier >= 3 ? 2048 : 1024,
      'enableNormalMaps': _currentTier >= 3,
      'enableSpecularMaps': _currentTier >= 2,
      'maxPolygons': _currentTier >= 3 ? 50000 : 20000,
      'enableAnimations': _currentTier >= 2,
      'lodDistance': _currentTier >= 2 ? 10.0 : 5.0, // meters
    };
  }
  
  // Detect device performance tier
  Future<void> detectDevicePerformance() async {
    try {
      // Get device info
      final totalMemory = await _getTotalMemory();
      final cpuCores = Platform.numberOfProcessors;
      
      // Simple heuristic for performance tier
      if (cpuCores >= 8 && totalMemory >= 6) {
        _currentTier = 3; // High-end
      } else if (cpuCores >= 4 && totalMemory >= 4) {
        _currentTier = 2; // Mid-range
      } else {
        _currentTier = 1; // Low-end
      }
      
      debugPrint('Device performance tier: $_currentTier (Cores: $cpuCores, RAM: ${totalMemory}GB)');
    } catch (e) {
      debugPrint('Error detecting device performance: $e');
      _currentTier = 2; // Default to medium
    }
  }
  
  // Get total device memory in GB
  Future<int> _getTotalMemory() async {
    // This is a simplified implementation
    // In production, you'd use platform channels to get actual device memory
    if (Platform.isAndroid) {
      // Android typically has 4-12GB RAM
      return 4; // Conservative estimate
    } else if (Platform.isIOS) {
      // iOS devices typically have 3-6GB RAM
      return 3; // Conservative estimate
    }
    return 4;
  }
  
  // Performance tips for users
  List<String> getPerformanceTips() {
    final tips = <String>[
      'Đảm bảo ánh sáng đầy đủ cho AR hoạt động tốt',
      'Di chuyển điện thoại chậm và ổn định',
    ];
    
    if (_currentTier == 1) {
      tips.addAll([
        'Đóng các ứng dụng khác để tăng hiệu suất',
        'Tránh đặt quá nhiều object AR cùng lúc',
        'Khởi động lại điện thoại nếu gặp lag',
      ]);
    }
    
    return tips;
  }
  
  // Check if feature is supported on current device
  bool isFeatureSupported(String feature) {
    final featureRequirements = {
      'imageTracking': 2,
      'objectPlacement': 1,
      'videoPlayback': 2,
      'advancedLighting': 3,
      'multipleAnchors': 2,
      'faceTracking': 3,
    };
    
    final requiredTier = featureRequirements[feature] ?? 2;
    return _currentTier >= requiredTier;
  }
  
  int get currentTier => _currentTier;
  String get tierName {
    switch (_currentTier) {
      case 3:
        return 'Cao cấp';
      case 2:
        return 'Trung bình';
      case 1:
        return 'Cơ bản';
      default:
        return 'Không xác định';
    }
  }
}
