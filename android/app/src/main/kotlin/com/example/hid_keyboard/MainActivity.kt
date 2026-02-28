package com.example.hid_keyboard

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodChannel

// ─────────────────────────────────────────────────────────────────────────────
// MainActivity
//
// Sets up the 'bluetooth_hid' MethodChannel and delegates every call to
// BluetoothHidService.  Also handles Bluetooth runtime permissions (required
// on Android 12+ / API 31+).
class MainActivity : FlutterActivity() {

    companion object {
        private const val CHANNEL = "bluetooth_hid"
        private const val TAG = "MainActivity"
        private const val REQUEST_BLUETOOTH_PERMISSIONS = 1001
        private const val REQUEST_DISCOVERABLE = 1002
    }

    private lateinit var hidService: BluetoothHidService
    private var methodChannel: MethodChannel? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    // Permissions needed on Android 12+ (API 31+). On API 28-30 only
    // BLUETOOTH + BLUETOOTH_ADMIN (declared in manifest) are needed.
    private val bluetoothPermissions12 = arrayOf(
        Manifest.permission.BLUETOOTH_CONNECT,
        Manifest.permission.BLUETOOTH_ADVERTISE,
        Manifest.permission.BLUETOOTH_SCAN
    )

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)

        // Create the service; pass a callback that forwards status changes to Dart.
        hidService = BluetoothHidService(applicationContext) { status ->
            // This lambda is already dispatched to main thread by BluetoothHidService.
            methodChannel?.invokeMethod("onStatusChanged", status)
        }

        // Set up the MethodChannel on the main thread messenger.
        methodChannel = MethodChannel(
            flutterEngine.dartExecutor.binaryMessenger,
            CHANNEL
        ).also { channel ->
            channel.setMethodCallHandler { call, result ->
                when (call.method) {

                    // ── initialize ────────────────────────────────────────
                    // Requests permissions if needed, then registers the HID app.
                    "initialize" -> {
                        if (hasBluetoothPermissions()) {
                            hidService.register()
                            // Also make phone discoverable so the PC can see it
                            makeDiscoverable()
                            result.success(hidService.currentStatus())
                        } else {
                            requestBluetoothPermissions()
                            result.success("Requesting permissions...")
                        }
                    }

                    // ── getPairedDevices ───────────────────────────────────
                    "getPairedDevices" -> {
                        val devices = hidService.getPairedDevices()
                        result.success(devices)
                    }

                    // ── connectToDevice ────────────────────────────────────
                    "connectToDevice" -> {
                        val address = call.argument<String>("address") ?: ""
                        hidService.connectToDevice(address)
                        result.success("Connecting...")
                    }

                    // ── makeDiscoverable ───────────────────────────────────
                    "makeDiscoverable" -> {
                        makeDiscoverable()
                        result.success("Discoverable for 120s")
                    }

                    // ── startTyping ───────────────────────────────────────
                    "startTyping" -> {
                        val text = call.argument<String>("text") ?: ""
                        val delayMs = call.argument<Int>("delayMs") ?: 25
                        hidService.startTyping(text, delayMs.toLong())
                        result.success("Typing...")
                    }

                    // ── stopTyping ────────────────────────────────────────
                    "stopTyping" -> {
                        hidService.stopTyping()
                        result.success("Stopped")
                    }

                    // ── getStatus ─────────────────────────────────────────
                    "getStatus" -> {
                        result.success(hidService.currentStatus())
                    }

                    else -> result.notImplemented()
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        hidService.unregister()
    }

    /** Sends the system intent that makes this device visible to other BT devices for 120 s. */
    private fun makeDiscoverable() {
        val discoverableIntent = Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE).apply {
            putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, 120)
        }
        startActivityForResult(discoverableIntent, REQUEST_DISCOVERABLE)
    }

    // ── Permission helpers ────────────────────────────────────────────────────

    private fun hasBluetoothPermissions(): Boolean {
        // API 31+ requires new runtime permissions.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return bluetoothPermissions12.all { perm ->
                ContextCompat.checkSelfPermission(this, perm) ==
                        PackageManager.PERMISSION_GRANTED
            }
        }
        // API 28-30: BLUETOOTH + BLUETOOTH_ADMIN are normal permissions declared
        // in the manifest; they are granted at install time.
        return true
    }

    private fun requestBluetoothPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ActivityCompat.requestPermissions(
                this,
                bluetoothPermissions12,
                REQUEST_BLUETOOTH_PERMISSIONS
            )
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_BLUETOOTH_PERMISSIONS) {
            val allGranted = grantResults.isNotEmpty() &&
                    grantResults.all { it == PackageManager.PERMISSION_GRANTED }

            if (allGranted) {
                Log.d(TAG, "Bluetooth permissions granted – registering HID app")
                hidService.register()
                makeDiscoverable()
                mainHandler.post {
                    methodChannel?.invokeMethod("onStatusChanged", "Not paired")
                }
            } else {
                Log.w(TAG, "Bluetooth permissions denied")
                mainHandler.post {
                    methodChannel?.invokeMethod(
                        "onStatusChanged",
                        "Error: Bluetooth permissions denied"
                    )
                }
            }
        }
    }
}
