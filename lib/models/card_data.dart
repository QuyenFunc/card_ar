class CardData {
  final String id;
  final String name;
  final String imagePath;
  final String videoPath;
  final double physicalWidth; // Chiều rộng thật của thẻ bài (mét)
  final double physicalHeight; // Chiều dài thật của thẻ bài (mét)

  CardData({
    required this.id,
    required this.name,
    required this.imagePath,
    required this.videoPath,
    this.physicalWidth = 0.05398, // 53.98mm = 0.05398m
    this.physicalHeight = 0.0856, // 85.6mm = 0.0856m
  });

  // Tỷ lệ khung hình của thẻ bài
  double get aspectRatio => physicalHeight / physicalWidth;

  static List<CardData> getAllCards() {
    return [
      CardData(
        id: 'card_1',
        name: 'Card 1',
        imagePath: 'assets/images/card_1.jpg',
        videoPath: 'assets/videos/video_1.mp4',
        physicalWidth: 0.05398, // 53.98mm
        physicalHeight: 0.0856, // 85.6mm
      ),
      CardData(
        id: 'card_2',
        name: 'Card 2',
        imagePath: 'assets/images/card_2.jpg',
        videoPath: 'assets/videos/video_2.mp4',
        physicalWidth: 0.05398,
        physicalHeight: 0.0856,
      ),
      CardData(
        id: 'card_3',
        name: 'Card 3',
        imagePath: 'assets/images/card_3.jpg',
        videoPath: 'assets/videos/video_3.mp4',
        physicalWidth: 0.05398,
        physicalHeight: 0.0856,
      ),
    ];
  }

  static CardData? getCardById(String id) {
    try {
      return getAllCards().firstWhere((card) => card.id == id);
    } catch (e) {
      return null;
    }
  }
}