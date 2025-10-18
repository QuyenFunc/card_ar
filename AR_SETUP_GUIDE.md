# Hướng Dẫn Sử Dụng AR Card Scanner App

## ✅ Đã Hoàn Thành

### 1. **Chuyển Đổi Sang ar_flutter_plugin**
- ✅ Thay thế `arcore_flutter_plugin` và `arkit_plugin` bằng `ar_flutter_plugin v0.7.3`
- ✅ API thống nhất cho cả Android (ARCore) và iOS (ARKit)
- ✅ Hỗ trợ plane detection tốt hơn

### 2. **Cấu Hình Android**
- ✅ Cập nhật AndroidManifest.xml với quyền cần thiết
- ✅ ARCore required (thay vì optional)
- ✅ OpenGL ES 3.0
- ✅ Namespace fix cho plugin cũ

### 3. **Tính Năng Hiện Tại**

#### **AR Plane Detection**
- Quét xung quanh để phát hiện mặt phẳng (horizontal và vertical)
- Hiển thị feature points (chấm trắng) khi quét
- Tap vào mặt phẳng để đặt object 3D

#### **Video Player Integration**
- Video player tích hợp sẵn
- Play/Pause controls
- Auto loop
- Reset để đặt lại

#### **UI/UX**
- Status message real-time
- Visual feedback khi đặt object
- Control buttons dễ sử dụng
- Instructions cho người dùng

## 📱 Cách Sử Dụng App

### **Bước 1: Khởi động AR**
1. Mở app, chọn "Bắt đầu quét"
2. Cho phép quyền Camera
3. Di chuyển điện thoại xung quanh

### **Bước 2: Tìm Mặt Phẳng**
- Di chuyển điện thoại chậm rãi
- Quan sát các chấm trắng (feature points) xuất hiện
- Khi phát hiện mặt phẳng, sẽ thấy lưới trắng

### **Bước 3: Đặt Video**
- Tap vào mặt phẳng được phát hiện
- Video placeholder (hình vuông xanh) sẽ xuất hiện
- Di chuyển xung quanh để xem từ các góc độ

### **Bước 4: Điều Khiển**
- **Play/Pause**: Tap vào button phát/tạm dừng
- **Reset**: Xóa object và đặt lại

## 🔧 Cấu Hình Cho Image Tracking (TODO)

Hiện tại app sử dụng **Plane Detection**. Để thêm **Image Tracking** (nhận diện thẻ bài):

### **Android (ARCore)**
1. Tạo augmented image database:
   ```
   arcoreimg build-db --input_images_directory=assets/images --output_db_path=assets/ar_resources.imgdb
   ```

2. Load database trong code:
   ```dart
   arSessionManager!.onInitialize(
     showFeaturePoints: true,
     showPlanes: true,
     handleTaps: true,
     customPlaneTexturePath: "assets/triangle.png",
     referenceImage: ARReferenceImage(
       name: "card_1",
       physicalWidth: 0.08, // meter
       image: ByteData.view(cardImageBytes.buffer),
     ),
   );
   ```

### **iOS (ARKit)**
1. Tạo AR Resources trong Xcode:
   - Mở `ios/Runner.xcworkspace`
   - Right-click → New File → AR Resource Group
   - Thêm images vào AR Resources
   - Đặt tên group: "AR Resources"

2. Cấu hình trong code:
   ```dart
   // ar_flutter_plugin tự động detect AR Resources
   ```

## 🎯 Features Mong Muốn (Roadmap)

### ✅ Hoàn Thành
- [x] Plane detection
- [x] 3D object placement
- [x] Video player integration
- [x] Cross-platform (Android/iOS)

### 🚧 Đang Phát Triển
- [ ] Image tracking (nhận diện thẻ bài)
- [ ] Video texture mapping lên 3D object
- [ ] Multiple cards detection

### 💡 Tương Lai
- [ ] AR video overlay (video hiển thị thẳng trên thẻ)
- [ ] Animations và effects
- [ ] Social sharing
- [ ] Cloud anchors (collaborative AR)

## 🐛 Troubleshooting

### **Lỗi: Camera không bật**
- Kiểm tra quyền Camera trong Settings
- Restart app

### **Lỗi: Không phát hiện mặt phẳng**
- Di chuyển điện thoại chậm hơn
- Đảm bảo có đủ ánh sáng
- Tìm bề mặt có texture (không phải trơn nhẵn)

### **Lỗi: Video không phát**
- Kiểm tra video files tồn tại trong assets/
- Restart app

### **Lỗi: ARCore không khả dụng**
- Cập nhật Google Play Services for AR
- Thiết bị phải hỗ trợ ARCore

## 📚 Tài Liệu Tham Khảo

- [ar_flutter_plugin docs](https://pub.dev/packages/ar_flutter_plugin)
- [ARCore documentation](https://developers.google.com/ar)
- [ARKit documentation](https://developer.apple.com/documentation/arkit)

## 🔑 Key Points

1. **ar_flutter_plugin** là wrapper thống nhất cho ARCore + ARKit
2. **Plane detection** đang hoạt động tốt
3. **Image tracking** cần thêm configuration
4. App hiện tại dùng **webGLB model** từ internet (cần internet connection)
5. Để offline: cần tạo local 3D models hoặc dùng primitive shapes

---

**Version:** 2.0.0 (ar_flutter_plugin)  
**Last Updated:** October 2025

