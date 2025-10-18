import 'package:flutter/material.dart';
import 'package:permission_handler/permission_handler.dart';
import 'ar_camera_page.dart';

class ARPermissionWrapper extends StatefulWidget {
  const ARPermissionWrapper({Key? key}) : super(key: key);

  @override
  State<ARPermissionWrapper> createState() => _ARPermissionWrapperState();
}

class _ARPermissionWrapperState extends State<ARPermissionWrapper> {
  bool _isCheckingPermission = true;
  bool _hasPermission = false;
  
  @override
  void initState() {
    super.initState();
    _checkAndRequestPermissions();
  }

  Future<void> _checkAndRequestPermissions() async {
    try {
      // Check camera permission
      final cameraStatus = await Permission.camera.status;
      
      if (cameraStatus.isDenied) {
        // Request permission
        final result = await Permission.camera.request();
        setState(() {
          _hasPermission = result.isGranted;
          _isCheckingPermission = false;
        });
      } else if (cameraStatus.isGranted) {
        setState(() {
          _hasPermission = true;
          _isCheckingPermission = false;
        });
      } else if (cameraStatus.isPermanentlyDenied) {
        setState(() {
          _hasPermission = false;
          _isCheckingPermission = false;
        });
      }
    } catch (e) {
      debugPrint('Permission error: $e');
      setState(() {
        _hasPermission = false;
        _isCheckingPermission = false;
      });
    }
  }

  @override
  Widget build(BuildContext context) {
    if (_isCheckingPermission) {
      return Scaffold(
        backgroundColor: Colors.black,
        body: Center(
          child: Column(
            mainAxisAlignment: MainAxisAlignment.center,
            children: [
              const CircularProgressIndicator(
                color: Colors.white,
              ),
              const SizedBox(height: 20),
              const Text(
                'Đang kiểm tra quyền camera...',
                style: TextStyle(color: Colors.white, fontSize: 16),
              ),
            ],
          ),
        ),
      );
    }

    if (!_hasPermission) {
      return Scaffold(
        backgroundColor: Colors.black,
        body: SafeArea(
          child: Center(
            child: Padding(
              padding: const EdgeInsets.all(24.0),
              child: Column(
                mainAxisAlignment: MainAxisAlignment.center,
                children: [
                  const Icon(
                    Icons.camera_alt_outlined,
                    size: 80,
                    color: Colors.white54,
                  ),
                  const SizedBox(height: 24),
                  const Text(
                    'Cần quyền truy cập Camera',
                    style: TextStyle(
                      color: Colors.white,
                      fontSize: 24,
                      fontWeight: FontWeight.bold,
                    ),
                  ),
                  const SizedBox(height: 16),
                  const Text(
                    'Ứng dụng cần quyền camera để quét thẻ bài và hiển thị AR',
                    textAlign: TextAlign.center,
                    style: TextStyle(
                      color: Colors.white70,
                      fontSize: 16,
                    ),
                  ),
                  const SizedBox(height: 32),
                  ElevatedButton.icon(
                    onPressed: () async {
                      final status = await Permission.camera.status;
                      if (status.isPermanentlyDenied) {
                        // Open app settings
                        await openAppSettings();
                      } else {
                        // Try requesting again
                        await _checkAndRequestPermissions();
                      }
                    },
                    icon: const Icon(Icons.settings),
                    label: const Text('Cấp quyền camera'),
                    style: ElevatedButton.styleFrom(
                      backgroundColor: Colors.blue,
                      foregroundColor: Colors.white,
                      padding: const EdgeInsets.symmetric(
                        horizontal: 24,
                        vertical: 12,
                      ),
                    ),
                  ),
                  const SizedBox(height: 16),
                  TextButton(
                    onPressed: () => Navigator.pop(context),
                    child: const Text(
                      'Quay lại',
                      style: TextStyle(color: Colors.white70),
                    ),
                  ),
                ],
              ),
            ),
          ),
        ),
      );
    }

    // Permission granted, show AR page for card scanning
    return const ARCameraPage();
  }
}
