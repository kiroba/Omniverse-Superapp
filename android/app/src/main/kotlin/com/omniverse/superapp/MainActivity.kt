package com.omniverse.superapp

import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodChannel

class MainActivity : FlutterActivity() {
    private var radioDiscovery: OmniRadioDiscovery? = null
    private var pendingPermissionResult: MethodChannel.Result? = null

    companion object {
        private const val PERMISSIONS_CHANNEL = "omni/radio/permissions"
        private const val REQ_RADIO = 4711
    }

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)
        radioDiscovery = OmniRadioDiscovery(applicationContext, flutterEngine.dartExecutor.binaryMessenger)

        // Runtime permissions for BLE + Wi-Fi Aware. The Dart side explains why first, then calls
        // "request"; Android shows its own dialog and we answer with what was granted.
        MethodChannel(flutterEngine.dartExecutor.binaryMessenger, PERMISSIONS_CHANNEL).setMethodCallHandler { call, result ->
            when (call.method) {
                "check" -> result.success(permissionReport(requested = false))
                "request" -> requestRadioPermissions(result)
                "openNode" -> {
                    val i = packageManager.getLaunchIntentForPackage("com.omniverse.node")
                    if (i == null) result.success(false) else {
                        startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); result.success(true)
                    }
                }
                "openSettings" -> {
                    try {
                        startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            Uri.fromParts("package", packageName, null)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        result.success(true)
                    } catch (t: Throwable) {
                        result.success(false)
                    }
                }
                else -> result.notImplemented()
            }
        }
    }

    /** Only the "dangerous" permissions; ACCESS_WIFI_STATE and friends are granted at install. */
    private fun radioRuntimePermissions(): List<String> {
        val out = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            out += listOf(
                "android.permission.BLUETOOTH_SCAN",
                "android.permission.BLUETOOTH_ADVERTISE",
                "android.permission.BLUETOOTH_CONNECT"
            )
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            out += "android.permission.NEARBY_WIFI_DEVICES"
        } else {
            out += "android.permission.ACCESS_FINE_LOCATION"   // BLE before 12, Wi-Fi Aware before 13
        }
        return out
    }

    private fun granted(p: String) = checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED

    /** granted / denied / blocked ("don't ask again", only knowable right after a request). */
    private fun permissionReport(requested: Boolean): Map<String, Any> {
        val all = radioRuntimePermissions()
        val denied = all.filter { !granted(it) }
        val blocked = if (requested) denied.filter { !shouldShowRequestPermissionRationale(it) } else emptyList()
        return mapOf(
            "allGranted" to denied.isEmpty(),
            "granted" to all.filter { granted(it) },
            "denied" to denied,
            "blocked" to blocked
        )
    }

    private fun requestRadioPermissions(result: MethodChannel.Result) {
        val missing = radioRuntimePermissions().filter { !granted(it) }
        if (missing.isEmpty()) {
            result.success(permissionReport(requested = false))
            return
        }
        if (pendingPermissionResult != null) {
            result.error("busy", "a permission request is already open", null)
            return
        }
        pendingPermissionResult = result
        requestPermissions(missing.toTypedArray(), REQ_RADIO)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQ_RADIO) return
        val r = pendingPermissionResult ?: return
        pendingPermissionResult = null
        r.success(permissionReport(requested = true))
    }

    override fun onDestroy() {
        pendingPermissionResult?.success(permissionReport(requested = false))
        pendingPermissionResult = null
        radioDiscovery?.stop()
        radioDiscovery = null
        super.onDestroy()
    }
}
