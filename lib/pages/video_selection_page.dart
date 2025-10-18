import 'package:flutter/material.dart';
import 'package:permission_handler/permission_handler.dart';
import 'ar_video_viewer_page.dart';

class ARVideoData {
  final String id;
  final String name;
  final String description;
  final String videoPath;
  final IconData icon;

  ARVideoData({
    required this.id,
    required this.name,
    required this.description,
    required this.videoPath,
    required this.icon,
  });
}

class VideoSelectionPage extends StatelessWidget {
  const VideoSelectionPage({Key? key}) : super(key: key);

  // Danh sách video AR có sẵn
  static final List<ARVideoData> arVideos = [
    ARVideoData(
      id: 'video_1',
      name: 'Video Demo 1',
      description: 'Video trình diễn AR thực tế ảo',
      videoPath: 'assets/videos/video_1.mp4',
      icon: Icons.play_circle_filled,
    ),
    ARVideoData(
      id: 'video_2',
      name: 'Video Demo 2',
      description: 'Khám phá không gian 3D tương tác',
      videoPath: 'assets/videos/video_2.mp4',
      icon: Icons.view_in_ar,
    ),
    ARVideoData(
      id: 'video_3',
      name: 'Video Demo 3',
      description: 'Hành trình thực tế ảo đặc biệt',
      videoPath: 'assets/videos/video_3.mp4',
      icon: Icons.threed_rotation,
    ),
  ];

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      body: Container(
        decoration: BoxDecoration(
          gradient: LinearGradient(
            begin: Alignment.topCenter,
            end: Alignment.bottomCenter,
            colors: [
              Colors.deepPurple.shade400,
              Colors.blue.shade600,
            ],
          ),
        ),
        child: SafeArea(
          child: Column(
            children: [
              // Header
              Padding(
                padding: const EdgeInsets.all(20),
                child: Row(
                  children: [
                    IconButton(
                      icon: const Icon(Icons.arrow_back,
                          color: Colors.white, size: 28),
                      onPressed: () => Navigator.pop(context),
                    ),
                    const SizedBox(width: 10),
                    const Text(
                      'Chọn Video AR',
                      style: TextStyle(
                        fontSize: 24,
                        fontWeight: FontWeight.bold,
                        color: Colors.white,
                      ),
                    ),
                  ],
                ),
              ),

              // Video List
              Expanded(
                child: ListView.builder(
                  padding:
                      const EdgeInsets.symmetric(horizontal: 20, vertical: 10),
                  itemCount: arVideos.length,
                  itemBuilder: (context, index) {
                    final video = arVideos[index];
                    return _buildVideoCard(context, video);
                  },
                ),
              ),
            ],
          ),
        ),
      ),
    );
  }

  Widget _buildVideoCard(BuildContext context, ARVideoData video) {
    return Container(
      margin: const EdgeInsets.only(bottom: 20),
      decoration: BoxDecoration(
        color: Colors.white,
        borderRadius: BorderRadius.circular(20),
        boxShadow: [
          BoxShadow(
            color: Colors.black.withOpacity(0.15),
            blurRadius: 15,
            offset: const Offset(0, 5),
          ),
        ],
      ),
      child: Material(
        color: Colors.transparent,
        child: InkWell(
          borderRadius: BorderRadius.circular(20),
          onTap: () async {
            // Kiểm tra quyền camera trước khi mở AR
            final cameraStatus = await Permission.camera.status;
            
            if (!context.mounted) return;
            
            if (cameraStatus.isDenied) {
              // Yêu cầu quyền
              final result = await Permission.camera.request();
              if (!result.isGranted) {
                _showPermissionDialog(context);
                return;
              }
            } else if (cameraStatus.isPermanentlyDenied) {
              _showPermissionDialog(context);
              return;
            }
            
            // Quyền đã được cấp, mở AR viewer
            if (!context.mounted) return;
            Navigator.push(
              context,
              MaterialPageRoute(
                builder: (context) => ARVideoViewerPage(videoData: video),
              ),
            );
          },
          child: Padding(
            padding: const EdgeInsets.all(20),
            child: Row(
              children: [
                // Icon
                Container(
                  width: 70,
                  height: 70,
                  decoration: BoxDecoration(
                    gradient: LinearGradient(
                      colors: [
                        Colors.blue.shade400,
                        Colors.purple.shade600,
                      ],
                    ),
                    borderRadius: BorderRadius.circular(15),
                  ),
                  child: Icon(
                    video.icon,
                    size: 35,
                    color: Colors.white,
                  ),
                ),
                const SizedBox(width: 20),

                // Info
                Expanded(
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Text(
                        video.name,
                        style: const TextStyle(
                          fontSize: 20,
                          fontWeight: FontWeight.bold,
                          color: Colors.black87,
                        ),
                      ),
                      const SizedBox(height: 5),
                      Text(
                        video.description,
                        style: TextStyle(
                          fontSize: 14,
                          color: Colors.grey.shade600,
                        ),
                      ),
                    ],
                  ),
                ),

                // Arrow
                Icon(
                  Icons.arrow_forward_ios,
                  color: Colors.grey.shade400,
                  size: 20,
                ),
              ],
            ),
          ),
        ),
      ),
    );
  }

  void _showPermissionDialog(BuildContext context) {
    showDialog(
      context: context,
      builder: (context) => AlertDialog(
        title: const Text('Cần quyền Camera'),
        content: const Text(
          'Ứng dụng cần quyền camera để hiển thị AR. Vui lòng cấp quyền trong Cài đặt.',
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(context),
            child: const Text('Hủy'),
          ),
          ElevatedButton(
            onPressed: () {
              Navigator.pop(context);
              openAppSettings();
            },
            child: const Text('Mở Cài đặt'),
          ),
        ],
      ),
    );
  }
}
