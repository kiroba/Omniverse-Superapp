package com.omniverse.superapp

import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine

class MainActivity : FlutterActivity() {
    private var radioDiscovery: OmniRadioDiscovery? = null

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)
        radioDiscovery = OmniRadioDiscovery(applicationContext, flutterEngine.dartExecutor.binaryMessenger)
    }

    override fun onDestroy() {
        radioDiscovery?.stop()
        radioDiscovery = null
        super.onDestroy()
    }
}
