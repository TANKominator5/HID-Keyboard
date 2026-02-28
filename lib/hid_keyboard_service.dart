import 'package:flutter/services.dart';

/// Service class that bridges Flutter UI with the native Kotlin Bluetooth HID implementation.
/// All communication goes through the 'bluetooth_hid' MethodChannel.
class HidKeyboardService {
  static const MethodChannel _channel = MethodChannel('bluetooth_hid');

  // Callback invoked whenever the native side sends a status update.
  Function(String status)? onStatusChanged;

  HidKeyboardService() {
    // Listen to method calls coming FROM native (status updates pushed by Kotlin).
    _channel.setMethodCallHandler(_handleMethodCall);
  }

  /// Handles incoming calls from the native side.
  Future<void> _handleMethodCall(MethodCall call) async {
    if (call.method == 'onStatusChanged') {
      final status = call.arguments as String? ?? 'Unknown';
      onStatusChanged?.call(status);
    }
  }

  /// Initialises the Bluetooth HID profile and starts advertising as a keyboard.
  /// Returns the initial status string.
  Future<String> initialize() async {
    try {
      final result = await _channel.invokeMethod<String>('initialize');
      return result ?? 'Initialising...';
    } on PlatformException catch (e) {
      return 'Error: ${e.message}';
    } on MissingPluginException catch (_) {
      return 'Error: App needs full rebuild';
    }
  }

  /// Returns the list of already-bonded (paired) Bluetooth devices.
  /// Each entry has keys "name" and "address".
  Future<List<Map<String, String>>> getPairedDevices() async {
    try {
      final raw = await _channel.invokeMethod<List>('getPairedDevices');
      if (raw == null) return [];
      return raw
          .map((e) => Map<String, String>.from(e as Map))
          .toList();
    } on PlatformException catch (_) {
      return [];
    } on MissingPluginException catch (_) {
      return [];
    }
  }

  Future<String> connectToDevice(String address) async {
    try {
      final result = await _channel.invokeMethod<String>(
        'connectToDevice',
        {'address': address},
      );
      return result ?? 'Connecting...';
    } on PlatformException catch (e) {
      return 'Error: ${e.message}';
    } on MissingPluginException catch (_) {
      return 'Error: App needs full rebuild';
    }
  }

  Future<String> makeDiscoverable() async {
    try {
      final result = await _channel.invokeMethod<String>('makeDiscoverable');
      return result ?? 'Discoverable';
    } on PlatformException catch (e) {
      return 'Error: ${e.message}';
    } on MissingPluginException catch (_) {
      return 'Error: App needs full rebuild';
    }
  }

  /// Starts typing [text] character by character via HID reports.
  Future<String> startTyping(String text, {int delayMs = 25, bool letterJitter = false, bool wordPause = false}) async {
    try {
      final result = await _channel.invokeMethod<String>(
        'startTyping',
        {'text': text, 'delayMs': delayMs, 'letterJitter': letterJitter, 'wordPause': wordPause},
      );
      return result ?? 'Typing...';
    } on PlatformException catch (e) {
      return 'Error: ${e.message}';
    }
  }

  /// Immediately stops the current typing sequence.
  Future<String> stopTyping() async {
    try {
      final result = await _channel.invokeMethod<String>('stopTyping');
      return result ?? 'Stopped';
    } on PlatformException catch (e) {
      return 'Error: ${e.message}';
    }
  }

  /// Returns the current connection / pairing status from the native side.
  Future<String> getStatus() async {
    try {
      final result = await _channel.invokeMethod<String>('getStatus');
      return result ?? 'Unknown';
    } on PlatformException catch (e) {
      return 'Error: ${e.message}';
    }
  }

  void dispose() {
    _channel.setMethodCallHandler(null);
  }
}


