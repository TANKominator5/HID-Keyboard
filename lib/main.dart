import 'package:flutter/material.dart';
import 'hid_keyboard_service.dart';

void main() {
  runApp(const HidKeyboardApp());
}

class HidKeyboardApp extends StatelessWidget {
  const HidKeyboardApp({super.key});

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      title: 'HID Keyboard',
      home: const HidKeyboardPage(),
      debugShowCheckedModeBanner: false,
    );
  }
}

class HidKeyboardPage extends StatefulWidget {
  const HidKeyboardPage({super.key});

  @override
  State<HidKeyboardPage> createState() => _HidKeyboardPageState();
}

class _HidKeyboardPageState extends State<HidKeyboardPage> {
  final TextEditingController _textController = TextEditingController();
  final HidKeyboardService _service = HidKeyboardService();

  String _status = 'Initialising...';
  bool _isTyping = false;
  double _delayMs = 25; // ms between each keystroke (5 = fastest, 100 = slowest)

  @override
  void initState() {
    super.initState();
    _service.onStatusChanged = (String status) {
      if (mounted) {
        setState(() {
          _status = status;
          if (status != 'Typing...') _isTyping = false;
        });
      }
    };
    _initService();
  }

  Future<void> _initService() async {
    final status = await _service.initialize();
    if (mounted) setState(() => _status = status);
  }

  Future<void> _startTyping() async {
    final text = _textController.text;
    if (text.isEmpty) {
      setState(() => _status = 'Error: No text to type');
      return;
    }
    setState(() {
      _isTyping = true;
      _status = 'Typing...';
    });
    final result = await _service.startTyping(text, delayMs: _delayMs.round());
    if (mounted) setState(() => _status = result);
  }

  Future<void> _stopTyping() async {
    await _service.stopTyping();
    // Don't manually set _isTyping or _status here.
    // The native side will fire onStatusChanged → "Paired & Ready"
    // once the typing thread exits cleanly.
  }

  /// Shows a bottom sheet listing all already-paired Bluetooth devices.
  /// Tapping one calls connectToDevice() on the native side.
  Future<void> _showConnectSheet() async {
    final devices = await _service.getPairedDevices();
    if (!mounted) return;

    showModalBottomSheet(
      context: context,
      isScrollControlled: true,
      shape: const RoundedRectangleBorder(
        borderRadius: BorderRadius.vertical(top: Radius.circular(16)),
      ),
      builder: (ctx) {
        return DraggableScrollableSheet(
          expand: false,
          initialChildSize: 0.6,
          maxChildSize: 0.9,
          builder: (_, scrollController) => Padding(
            padding: const EdgeInsets.all(16),
            child: ListView(
              controller: scrollController,
              children: [
                const Text(
                  'Connect to PC',
                  style: TextStyle(fontSize: 18, fontWeight: FontWeight.bold),
                ),
                const SizedBox(height: 4),
                const Text(
                  'Step 1: On your PC open Bluetooth Settings → "Add a device" and pair with this phone.\n'
                  'Step 2: Tap your PC in the list below.',
                  style: TextStyle(fontSize: 13, color: Colors.grey),
                ),
                const Divider(height: 24),

                // ── Make discoverable ─────────────────────────────────────
                OutlinedButton.icon(
                  onPressed: () async => await _service.makeDiscoverable(),
                  icon: const Icon(Icons.bluetooth_searching),
                  label: const Text('Make phone discoverable (120 s)'),
                ),
                const SizedBox(height: 12),

                if (devices.isEmpty)
                  const Padding(
                    padding: EdgeInsets.symmetric(vertical: 16),
                    child: Text(
                      'No paired devices found.\n'
                      'Pair with your PC in Android Bluetooth Settings first.',
                      textAlign: TextAlign.center,
                      style: TextStyle(color: Colors.grey),
                    ),
                  )
                else ...[
                  const Text('Bonded devices:', style: TextStyle(fontWeight: FontWeight.w600)),
                  const SizedBox(height: 4),
                  ...devices.map((d) {
                    final name = d['name'] ?? 'Unknown';
                    final address = d['address'] ?? '';
                    return Card(
                      child: ListTile(
                        leading: const Icon(Icons.computer, color: Colors.blue),
                        title: Text(name, style: const TextStyle(fontWeight: FontWeight.w600)),
                        subtitle: Text(address),
                        trailing: const Icon(Icons.arrow_forward_ios, size: 14),
                        onTap: () async {
                          Navigator.pop(ctx);
                          setState(() => _status = 'Connecting...');
                          final result = await _service.connectToDevice(address);
                          if (mounted) setState(() => _status = result);
                        },
                      ),
                    );
                  }),
                ],

                const SizedBox(height: 16),
                const Text(
                  '⚠️  If your PC is not listed above, go to Android Bluetooth Settings, '
                  'pair with the PC first, then come back here.',
                  style: TextStyle(fontSize: 12, color: Colors.orange),
                ),
                const SizedBox(height: 16),
              ],
            ),
          ),
        );
      },
    );
  }

  @override
  void dispose() {
    _textController.dispose();
    _service.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final charCount = _textController.text.length;
    final isPaired = _status == 'Paired & Ready';
    final isDisconnected = _status == 'Disconnected';
    final isWaiting = _status.startsWith('Waiting') || _status.startsWith('Registering');
    final isConnecting = _status.startsWith('Connecting') || _status.startsWith('Device bonded');

    return Scaffold(
      appBar: AppBar(
        title: const Text('HID Keyboard'),
        actions: [
          IconButton(
            icon: const Icon(Icons.bluetooth),
            tooltip: 'Connect to PC',
            onPressed: _showConnectSheet,
          ),
        ],
      ),
      body: Padding(
        padding: const EdgeInsets.all(16.0),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            // ── Large text input ──────────────────────────────────────────
            Expanded(
              child: TextField(
                controller: _textController,
                maxLines: null,
                expands: true,
                maxLength: 10000,
                textAlignVertical: TextAlignVertical.top,
                decoration: const InputDecoration(
                  labelText: 'Paste text here',
                  alignLabelWithHint: true,
                  border: OutlineInputBorder(),
                ),
                onChanged: (_) => setState(() {}),
              ),
            ),

            const SizedBox(height: 12),

            // ── Character count ───────────────────────────────────────────
            Text(
              'Characters: $charCount / 10 000',
              textAlign: TextAlign.right,
              style: const TextStyle(fontSize: 13, color: Colors.grey),
            ),

            const SizedBox(height: 8),

            // ── Status ────────────────────────────────────────────────────
            Container(
              padding: const EdgeInsets.symmetric(vertical: 8, horizontal: 12),
              decoration: BoxDecoration(
                color: _statusColor(_status).withOpacity(0.15),
                borderRadius: BorderRadius.circular(8),
                border: Border.all(color: _statusColor(_status)),
              ),
              child: Text(
                _status,
                style: TextStyle(
                  fontSize: 16,
                  fontWeight: FontWeight.bold,
                  color: _statusColor(_status),
                ),
                textAlign: TextAlign.center,
              ),
            ),

            const SizedBox(height: 12),

            // ── Typing Speed Slider ───────────────────────────────────────
            Row(
              children: [
                const Icon(Icons.speed, size: 20, color: Colors.grey),
                const SizedBox(width: 8),
                const Text('Speed', style: TextStyle(fontSize: 13)),
                Expanded(
                  child: Slider(
                    value: _delayMs,
                    min: 5,
                    max: 200,
                    divisions: 39,
                    label: '${_delayMs.round()} ms',
                    onChanged: _isTyping
                        ? null
                        : (v) => setState(() => _delayMs = v),
                  ),
                ),
                SizedBox(
                  width: 55,
                  child: Text(
                    '${_delayMs.round()} ms',
                    style: const TextStyle(fontSize: 12, color: Colors.grey),
                  ),
                ),
              ],
            ),

            // ── CONNECT TO PC button ──────────────────────────────────────
            if (!isPaired)
              SizedBox(
                height: 48,
                child: ElevatedButton.icon(
                  onPressed: _showConnectSheet,
                  icon: Icon(isConnecting || isWaiting
                      ? Icons.bluetooth_searching
                      : isDisconnected
                          ? Icons.bluetooth_disabled
                          : Icons.bluetooth_connected),
                  label: Text(isConnecting
                      ? 'Connecting... (tap to retry)'
                      : isWaiting
                          ? 'Waiting for PC… (tap to connect manually)'
                          : isDisconnected
                              ? 'Reconnect to PC'
                              : 'Connect to PC'),
                  style: ElevatedButton.styleFrom(
                    backgroundColor: isConnecting
                        ? Colors.orange
                        : isWaiting
                            ? Colors.teal
                            : isDisconnected
                                ? Colors.red
                                : Colors.blueGrey,
                    foregroundColor: Colors.white,
                    textStyle: const TextStyle(fontSize: 15),
                  ),
                ),
              ),

            if (!isPaired) const SizedBox(height: 10),

            // ── START TYPING button ───────────────────────────────────────
            SizedBox(
              height: 60,
              child: ElevatedButton(
                onPressed: (_isTyping || !isPaired) ? null : _startTyping,
                style: ElevatedButton.styleFrom(
                  backgroundColor: Colors.blue,
                  foregroundColor: Colors.white,
                  textStyle: const TextStyle(
                    fontSize: 20,
                    fontWeight: FontWeight.bold,
                  ),
                ),
                child: const Text('START TYPING'),
              ),
            ),

            const SizedBox(height: 10),

            // ── STOP button ───────────────────────────────────────────────
            SizedBox(
              height: 44,
              child: OutlinedButton(
                onPressed: _isTyping ? _stopTyping : null,
                style: OutlinedButton.styleFrom(
                  foregroundColor: Colors.red,
                  side: const BorderSide(color: Colors.red),
                  textStyle: const TextStyle(fontSize: 16),
                ),
                child: const Text('STOP'),
              ),
            ),

            const SizedBox(height: 16),
          ],
        ),
      ),
    );
  }

  Color _statusColor(String status) {
    if (status.startsWith('Error')) return Colors.red;
    if (status == 'Paired & Ready') return Colors.green;
    if (status == 'Typing...') return Colors.orange;
    if (status == 'Disconnected') return Colors.red;
    if (status.startsWith('Connecting') || status.startsWith('Device bonded')) return Colors.blue;
    if (status.startsWith('Waiting') || status.startsWith('Registering')) return Colors.teal;
    return Colors.grey;
  }
}
