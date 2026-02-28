package com.example.hid_keyboard

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHidDevice
import android.bluetooth.BluetoothHidDeviceAppSdpSettings
import android.bluetooth.BluetoothHidDeviceAppQosSettings
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.util.concurrent.Executors

// ─────────────────────────────────────────────────────────────────────────────
// BluetoothHidService
//
// Registers the Android device as a Bluetooth HID keyboard using the public
// BluetoothHidDevice API (no root needed, available since Android 9 / API 28).
//
// Architecture:
//   • register()    – calls registerApp() to advertise SDP record + HID descriptor
//   • startTyping() – spins up a background executor that sends HID key-down/key-up
//                     reports for every character with a 25 ms gap.
//   • stopTyping()  – signals the executor to quit early.
//   • statusCallback – lambda called from any thread whenever status changes;
//                      callers must post to main thread if needed.
// ─────────────────────────────────────────────────────────────────────────────

typealias StatusCallback = (String) -> Unit

@SuppressLint("MissingPermission")   // Permissions checked in MainActivity before any call.
class BluetoothHidService(
    private val context: Context,
    private val statusCallback: StatusCallback
) {

    companion object {
        private const val TAG = "BluetoothHidService"

        // ── HID Report Descriptor ────────────────────────────────────────────
        // Standard boot-protocol keyboard descriptor.
        // This exact byte sequence is required so that the PC/BIOS recognises
        // the device as a generic HID keyboard without any special driver.
        //
        // Breakdown:
        //   Usage Page (Generic Desktop)  – 0x05 0x01
        //   Usage (Keyboard)              – 0x09 0x06
        //   Collection (Application)      – 0xA1 0x01
        //     -- Input: modifier byte (8 bits = 8 modifier keys) --
        //     Usage Page (Key Codes)       – 0x05 0x07
        //     Usage Minimum (224)          – 0x19 0xE0  (Left Ctrl)
        //     Usage Maximum (231)          – 0x29 0xE7  (Right GUI)
        //     Logical Minimum (0)          – 0x15 0x00
        //     Logical Maximum (1)          – 0x25 0x01
        //     Report Size (1)              – 0x75 0x01
        //     Report Count (8)             – 0x95 0x08
        //     Input (Data, Variable, Abs)  – 0x81 0x02
        //     -- Input: reserved byte --
        //     Report Size (8)              – 0x75 0x08
        //     Report Count (1)             – 0x95 0x01
        //     Input (Constant)             – 0x81 0x01
        //     -- Output: LEDs (5 bits) + 3 padding bits --
        //     Usage Page (LEDs)            – 0x05 0x08
        //     Usage Minimum (Num Lock)     – 0x19 0x01
        //     Usage Maximum (Kana)         – 0x29 0x05
        //     Report Size (1)              – 0x75 0x01
        //     Report Count (5)             – 0x95 0x05
        //     Output (Data, Variable, Abs) – 0x91 0x02
        //     Report Size (3)              – 0x75 0x03
        //     Report Count (1)             – 0x95 0x01
        //     Output (Constant)            – 0x91 0x01
        //     -- Input: 6 key codes (6 simultaneous keys max) --
        //     Usage Page (Key Codes)       – 0x05 0x07
        //     Usage Minimum (0)            – 0x19 0x00
        //     Usage Maximum (101)          – 0x29 0x65
        //     Report Size (8)              – 0x75 0x08
        //     Report Count (6)             – 0x95 0x06
        //     Input (Data, Array, Abs)     – 0x81 0x00
        //   End Collection                 – 0xC0
        val HID_REPORT_DESCRIPTOR: ByteArray = byteArrayOf(
            0x05.toByte(), 0x01, // Usage Page (Generic Desktop)
            0x09.toByte(), 0x06, // Usage (Keyboard)
            0xA1.toByte(), 0x01, // Collection (Application)
            // Modifier byte
            0x05.toByte(), 0x07, //   Usage Page (Key Codes)
            0x19.toByte(), 0xE0.toByte(), //   Usage Minimum (224 = Left Ctrl)
            0x29.toByte(), 0xE7.toByte(), //   Usage Maximum (231 = Right GUI)
            0x15.toByte(), 0x00, //   Logical Minimum (0)
            0x25.toByte(), 0x01, //   Logical Maximum (1)
            0x75.toByte(), 0x01, //   Report Size (1 bit)
            0x95.toByte(), 0x08, //   Report Count (8)
            0x81.toByte(), 0x02, //   Input (Data, Variable, Absolute)
            // Reserved byte
            0x75.toByte(), 0x08, //   Report Size (8 bits)
            0x95.toByte(), 0x01, //   Report Count (1)
            0x81.toByte(), 0x01, //   Input (Constant)
            // LED output report
            0x05.toByte(), 0x08, //   Usage Page (LEDs)
            0x19.toByte(), 0x01, //   Usage Minimum (Num Lock)
            0x29.toByte(), 0x05, //   Usage Maximum (Kana)
            0x75.toByte(), 0x01, //   Report Size (1)
            0x95.toByte(), 0x05, //   Report Count (5)
            0x91.toByte(), 0x02, //   Output (Data, Variable, Absolute)
            0x75.toByte(), 0x03, //   Report Size (3) – padding
            0x95.toByte(), 0x01, //   Report Count (1)
            0x91.toByte(), 0x01, //   Output (Constant)
            // Key array (6 simultaneous key codes)
            0x05.toByte(), 0x07, //   Usage Page (Key Codes)
            0x19.toByte(), 0x00, //   Usage Minimum (0)
            0x29.toByte(), 0x65, //   Usage Maximum (101)
            0x75.toByte(), 0x08, //   Report Size (8 bits)
            0x95.toByte(), 0x06, //   Report Count (6)
            0x81.toByte(), 0x00, //   Input (Data, Array, Absolute)
            0xC0.toByte()        // End Collection
        )

        // HID report ID. We use 0 (no report ID prefix) which matches boot protocol.
        const val REPORT_ID: Byte = 0x00

        // ── Keymap: Char → Pair(scanCode, modifier) ──────────────────────────
        // modifier 0x00 = no modifier
        // modifier 0x02 = Left Shift
        // Scan codes follow the USB HID Usage Table (Keyboard/Keypad page, 0x07).
        val KEY_MAP: Map<Char, Pair<Byte, Byte>> = buildMap {
            // a-z (scan codes 0x04-0x1D, no modifier)
            for (i in 0..25) {
                put('a' + i, Pair((0x04 + i).toByte(), 0x00.toByte()))
            }
            // A-Z (same scan codes, Left Shift modifier = 0x02)
            for (i in 0..25) {
                put('A' + i, Pair((0x04 + i).toByte(), 0x02.toByte()))
            }
            // 1-9 (scan codes 0x1E-0x26, no modifier)
            for (i in 1..9) {
                put('0' + i, Pair((0x1D + i).toByte(), 0x00.toByte()))
            }
            // 0 (scan code 0x27)
            put('0', Pair(0x27.toByte(), 0x00.toByte()))

            // ── Special characters (US QWERTY) ────────────────────────────
            put(' ',  Pair(0x2C.toByte(), 0x00.toByte())) // Space
            put('\n', Pair(0x28.toByte(), 0x00.toByte())) // Enter
            put('\t', Pair(0x2B.toByte(), 0x00.toByte())) // Tab
            put('-',  Pair(0x2D.toByte(), 0x00.toByte())) // Minus / Hyphen
            put('=',  Pair(0x2E.toByte(), 0x00.toByte())) // Equals
            put('[',  Pair(0x2F.toByte(), 0x00.toByte())) // Left bracket
            put(']',  Pair(0x30.toByte(), 0x00.toByte())) // Right bracket
            put('\\', Pair(0x31.toByte(), 0x00.toByte())) // Backslash
            put(';',  Pair(0x33.toByte(), 0x00.toByte())) // Semicolon
            put('\'', Pair(0x34.toByte(), 0x00.toByte())) // Apostrophe
            put('`',  Pair(0x35.toByte(), 0x00.toByte())) // Grave accent
            put(',',  Pair(0x36.toByte(), 0x00.toByte())) // Comma
            put('.',  Pair(0x37.toByte(), 0x00.toByte())) // Period
            put('/',  Pair(0x38.toByte(), 0x00.toByte())) // Slash

            // Shifted punctuation (US QWERTY layout)
            put('!',  Pair(0x1E.toByte(), 0x02.toByte())) // Shift+1
            put('@',  Pair(0x1F.toByte(), 0x02.toByte())) // Shift+2
            put('#',  Pair(0x20.toByte(), 0x02.toByte())) // Shift+3
            put('$',  Pair(0x21.toByte(), 0x02.toByte())) // Shift+4
            put('%',  Pair(0x22.toByte(), 0x02.toByte())) // Shift+5
            put('^',  Pair(0x23.toByte(), 0x02.toByte())) // Shift+6
            put('&',  Pair(0x24.toByte(), 0x02.toByte())) // Shift+7
            put('*',  Pair(0x25.toByte(), 0x02.toByte())) // Shift+8
            put('(',  Pair(0x26.toByte(), 0x02.toByte())) // Shift+9
            put(')',  Pair(0x27.toByte(), 0x02.toByte())) // Shift+0
            put('_',  Pair(0x2D.toByte(), 0x02.toByte())) // Shift+-
            put('+',  Pair(0x2E.toByte(), 0x02.toByte())) // Shift+=
            put('{',  Pair(0x2F.toByte(), 0x02.toByte())) // Shift+[
            put('}',  Pair(0x30.toByte(), 0x02.toByte())) // Shift+]
            put('|',  Pair(0x31.toByte(), 0x02.toByte())) // Shift+backslash
            put(':',  Pair(0x33.toByte(), 0x02.toByte())) // Shift+;
            put('"',  Pair(0x34.toByte(), 0x02.toByte())) // Shift+'
            put('~',  Pair(0x35.toByte(), 0x02.toByte())) // Shift+`
            put('<',  Pair(0x36.toByte(), 0x02.toByte())) // Shift+,
            put('>',  Pair(0x37.toByte(), 0x02.toByte())) // Shift+.
            put('?',  Pair(0x38.toByte(), 0x02.toByte())) // Shift+/
        }
    }

    // ── State ─────────────────────────────────────────────────────────────────
    private val bluetoothAdapter: BluetoothAdapter? by lazy {
        val bm = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        bm?.adapter
    }

    private var hidDevice: BluetoothHidDevice? = null
    private var connectedDevice: BluetoothDevice? = null

    // Flag to signal the typing loop to stop.
    @Volatile private var typingCancelled = false

    // Single-thread executor used for the typing loop so we don't block the main thread.
    private val typingExecutor = Executors.newSingleThreadExecutor()

    // Handler used to post status callbacks back to the main thread.
    private val mainHandler = Handler(Looper.getMainLooper())

    // ── HID Callback ──────────────────────────────────────────────────────────
    // BluetoothHidDevice.Callback receives profile events (connected, disconnected, etc.)
    private val hidCallback = object : BluetoothHidDevice.Callback() {

        override fun onAppStatusChanged(pluggedDevice: BluetoothDevice?, registered: Boolean) {
            Log.d(TAG, "onAppStatusChanged: registered=$registered device=$pluggedDevice")
            if (registered) {
                postStatus("Not paired")
            } else {
                postStatus("Not registered")
            }
        }

        override fun onConnectionStateChanged(device: BluetoothDevice, state: Int) {
            Log.d(TAG, "onConnectionStateChanged: state=$state device=${device.address}")
            when (state) {
                BluetoothProfile.STATE_CONNECTED -> {
                    connectedDevice = device
                    postStatus("Paired & Ready")
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    if (connectedDevice?.address == device.address) {
                        connectedDevice = null
                    }
                    postStatus("Not paired")
                }
                BluetoothProfile.STATE_CONNECTING -> postStatus("Connecting...")
                BluetoothProfile.STATE_DISCONNECTING -> postStatus("Disconnecting...")
            }
        }

        override fun onGetReport(device: BluetoothDevice, type: Byte, id: Byte, bufferSize: Int) {
            // Respond with an empty report. Required by the protocol.
            hidDevice?.replyReport(device, type, id, ByteArray(8))
        }

        override fun onSetReport(device: BluetoothDevice, type: Byte, id: Byte, data: ByteArray) {
            hidDevice?.reportError(device, BluetoothHidDevice.ERROR_RSP_SUCCESS.toByte())
        }

        override fun onInterruptData(device: BluetoothDevice, reportId: Byte, data: ByteArray) {
            // Host-to-device interrupt data (e.g. LED state). We ignore it here.
        }
    }

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Opens the BluetoothHidDevice profile proxy and registers the app with
     * the SDP / HID descriptor. Must be called once before anything else.
     */
    fun register() {
        val adapter = bluetoothAdapter
        if (adapter == null || !adapter.isEnabled) {
            postStatus("Error: Bluetooth not enabled")
            return
        }

        // getProfileProxy() is asynchronous; onServiceConnected fires when ready.
        adapter.getProfileProxy(context, object : BluetoothProfile.ServiceListener {

            override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
                Log.d(TAG, "HID profile proxy connected")
                hidDevice = proxy as BluetoothHidDevice

                // ── SDP record ────────────────────────────────────────────
                // This is what appears in the Bluetooth device list on the PC.
                val sdp = BluetoothHidDeviceAppSdpSettings(
                    "HID Keyboard",          // name visible to host
                    "Flutter HID",           // description
                    "Android",               // provider
                    BluetoothHidDevice.SUBCLASS1_KEYBOARD,
                    HID_REPORT_DESCRIPTOR
                )

                // ── QoS settings (use defaults for a simple keyboard) ──────
                val qos = BluetoothHidDeviceAppQosSettings(
                    BluetoothHidDeviceAppQosSettings.SERVICE_BEST_EFFORT,
                    800,   // token rate  (bytes/s)
                    9,     // token bucket (bytes)
                    0,     // peak bandwidth
                    10000, // latency (µs) – 10 ms
                    Integer.MAX_VALUE // delay variation
                )

                // registerApp() tells Android to advertise us via SDP as a keyboard.
                hidDevice?.registerApp(sdp, null, qos, Executors.newCachedThreadPool(), hidCallback)
                    ?: postStatus("Error: Could not register HID app")
            }

            override fun onServiceDisconnected(profile: Int) {
                Log.d(TAG, "HID profile proxy disconnected")
                hidDevice = null
                postStatus("Not paired")
            }
        }, BluetoothProfile.HID_DEVICE)
    }

    /**
     * Returns a list of already-bonded (paired) Bluetooth devices as
     * List<Map<String,String>> with keys "name" and "address".
     * The Flutter UI presents these so the user can tap one to connect.
     */
    fun getPairedDevices(): List<Map<String, String>> {
        val adapter = bluetoothAdapter ?: return emptyList()
        return adapter.bondedDevices.orEmpty().map { device ->
            mapOf(
                "name"    to (device.name ?: "Unknown"),
                "address" to device.address
            )
        }
    }

    /**
     * Tells the HID device to connect to the already-bonded device with
     * the given Bluetooth MAC address.  The host (PC) must already be
     * paired with this Android phone via the normal Bluetooth settings.
     */
    fun connectToDevice(address: String) {
        val hid = hidDevice
        if (hid == null) {
            postStatus("Error: HID not ready – wait for registration")
            return
        }
        val adapter = bluetoothAdapter ?: run {
            postStatus("Error: Bluetooth not available")
            return
        }
        val device = try {
            adapter.getRemoteDevice(address)
        } catch (e: IllegalArgumentException) {
            postStatus("Error: Bad address $address")
            return
        }
        postStatus("Connecting...")
        val ok = hid.connect(device)
        if (!ok) {
            postStatus("Error: connect() failed – is the device bonded?")
        }
    }

    /**
     * Types [text] character by character over Bluetooth HID.
     * Each character produces one key-down report followed (25 ms later) by a key-up report.
     * Runs on a background thread so the UI stays responsive.
     */
    fun startTyping(text: String) {
        val device = connectedDevice
        if (device == null) {
            postStatus("Error: No device paired")
            return
        }
        val hid = hidDevice
        if (hid == null) {
            postStatus("Error: HID not ready")
            return
        }

        typingCancelled = false
        postStatus("Typing...")

        typingExecutor.submit {
            try {
                for (ch in text) {
                    // Check cancellation flag before every character.
                    if (typingCancelled) break

                    val (scanCode, modifier) = KEY_MAP[ch] ?: run {
                        // Unknown character – skip it with the same timing.
                        Thread.sleep(25)
                        return@run null
                    } ?: continue

                    // ── Key DOWN report ───────────────────────────────────
                    // Report layout (8 bytes, boot-protocol):
                    //   [0] modifier byte  (bitmask: bit1 = Left Shift, etc.)
                    //   [1] reserved       (always 0x00)
                    //   [2] key code 1     (first active key)
                    //   [3..7] key codes 2-6 (unused = 0x00)
                    val keyDown = byteArrayOf(modifier, 0x00, scanCode, 0x00, 0x00, 0x00, 0x00, 0x00)
                    hid.sendReport(device, REPORT_ID.toInt(), keyDown)

                    Thread.sleep(25) // 25 ms key-down hold

                    // ── Key UP report ─────────────────────────────────────
                    // All zeros = no keys pressed.
                    val keyUp = ByteArray(8)
                    hid.sendReport(device, REPORT_ID.toInt(), keyUp)

                    Thread.sleep(25) // 25 ms between characters
                }

                if (!typingCancelled) {
                    postStatus("Paired & Ready")
                }
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                postStatus("Stopped")
            } catch (e: Exception) {
                Log.e(TAG, "Typing error", e)
                postStatus("Error: ${e.message}")
            }
        }
    }

    /**
     * Signals the typing background thread to stop after the current character.
     */
    fun stopTyping() {
        typingCancelled = true
        postStatus("Stopped")
    }

    /**
     * Returns the current status as a plain string (useful for initial query).
     */
    fun currentStatus(): String {
        val adapter = bluetoothAdapter
        if (adapter == null || !adapter.isEnabled) return "Error: Bluetooth not enabled"
        if (hidDevice == null) return "Initialising..."
        return if (connectedDevice != null) "Paired & Ready" else "Not paired"
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /** Posts a status string to the main thread and then fires the callback. */
    private fun postStatus(status: String) {
        mainHandler.post { statusCallback(status) }
    }
}
