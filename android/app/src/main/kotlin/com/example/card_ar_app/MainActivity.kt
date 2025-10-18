package com.example.card_ar_app

import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine

class MainActivity : FlutterActivity() {
    
    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)
        
        // Register custom ARCore platform view
        flutterEngine.platformViewsController.registry.registerViewFactory(
            "ar_image_tracking_view",
            ArCoreImageTrackingViewFactory(this, flutterEngine.dartExecutor.binaryMessenger)
        )
    }
}
