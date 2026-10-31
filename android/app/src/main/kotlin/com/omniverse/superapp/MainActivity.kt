package com.omniverse.superapp

import android.os.Build
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine

class MainActivity : FlutterActivity() {
    private var radioDiscovery: OmniRadioDiscovery? = null

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)
        val radio = OmniRadioDiscovery(applicationContext, flutterEngine.dartExecutor.binaryMessenger)
        radioDiscovery = radio
        // Ask once for Bluetooth / Nearby Wi-Fi / location so BLE and Wi-Fi Aware discovery can run.
        try {
            val missing = radio.missingRuntimePermissions()
            if (missing.isNotEmpty() && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                requestPermissions(missing.toTypedArray(), RADIO_PERMISSIONS_REQUEST)
            }
        } catch (_: Throwable) {
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == RADIO_PERMISSIONS_REQUEST) {
            try { radioDiscovery?.restart() } catch (_: Throwable) {}
        }
    }

    override fun onDestroy() {
        radioDiscovery?.stop()
        radioDiscovery = null
        super.onDestroy()
    }

    companion object {
        private const val RADIO_PERMISSIONS_REQUEST = 4711
    }
}
