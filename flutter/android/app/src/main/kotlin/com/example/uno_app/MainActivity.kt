package com.example.uno_app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodChannel

class MainActivity : FlutterActivity() {
    private val voiceChannel = "uno/voice_device"
    private val bluetoothRequest = 4201
    private var pendingBluetooth: MethodChannel.Result? = null

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)
        MethodChannel(flutterEngine.dartExecutor.binaryMessenger, voiceChannel).setMethodCallHandler { call, result ->
            if (call.method != "prepareBluetooth") {
                result.notImplemented()
            } else if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
                checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) {
                result.success(true)
            } else if (pendingBluetooth != null) {
                result.error("BUSY", "蓝牙权限请求尚未结束", null)
            } else {
                pendingBluetooth = result
                requestPermissions(arrayOf(Manifest.permission.BLUETOOTH_CONNECT), bluetoothRequest)
            }
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == bluetoothRequest) {
            pendingBluetooth?.success(grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED)
            pendingBluetooth = null
        }
    }
}
