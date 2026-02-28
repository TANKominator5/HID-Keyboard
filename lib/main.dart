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
    final result = await _service.startTyping(text);
    if (mounted) setState(() => _status = result);
  }

  Future<void> _stopTyping() async {
    final result = await _service.stopTyping();
    if (mounted) setState(() {
      _isTyping = false;
      _status = result;
    });
  }

  /// Shows a bottom sheet listing all already-paired Bluetooth devices.
  /// Tapping one calls connectToDevice() on the native side.
  Future<void> _showConnectSheet() async {
    // Fetch paired devices first
    final devices = await _service.getPairedDevices();

    if (!mounted) return;

    showModalBottomSheet(
      context: context,
      shape: const RoundedRectangleBorder(
        borderRadius: BorderRadius.vertical(top: Radius.circular(16)),
      ),
      builder: (ctx) {
        return Padding(
          padding: const EdgeInsets.all(16),
          child: Column(
            mainAxisSize: MainAxisSize.min,
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              const Text(
                'Select a paired device to connect',
                style: TextStyle(fontSize: 16, fontWeight: FontWeight.bold),
              ),
              const SizedBox(height: 8),
              if (devices.isEmpty)
                const Padding(
                  padding: EdgeInsets.symmetric(vertical: 24),
                  child: Text(
                    'No paired devices found.\n\n'
                    '1. Go to your phone\'s Bluetooth Settings\n'
                    '2. Pair with your PC\n'
                    '3. Come back and tap "Connect to PC"',
                    textAlign: TextAlign.center,
                    style: TextStyle(color: Colors.grey),
                  ),
                )
              else
                ...devices.map((d) {
                  final name = d['name'] ?? 'Unknown';
                  final address = d['address'] ?? '';
                  return ListTile(
                    leading: const Icon(Icons.computer),
                    title: Text(name),
                    subtitle: Text(address),
                    onTap: () async {
                      Navigator.pop(ctx);
                      final result = await _service.connectToDevice(address);
                      if (mounted) setState(() => _status = result);
                    },
                  );
                }),
              const SizedBox(height: 8),
              OutlinedButton.icon(
                onPressed: () async {
                  await _service.makeDiscoverable();
                },
                icon: const Icon(Icons.bluetooth_searching),
                label: const Text('Make phone discoverable (120 s)'),
              ),
              const SizedBox(height: 8),
            ],
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

            // ── CONNECT TO PC button (shown when not paired) ──────────────
            if (!isPaired)
              SizedBox(
                height: 48,
                child: ElevatedButton.icon(
                  onPressed: _showConnectSheet,
                  icon: const Icon(Icons.bluetooth_connected),
                  label: const Text('Connect to PC'),
                  style: ElevatedButton.styleFrom(
                    backgroundColor: Colors.blueGrey,
                    foregroundColor: Colors.white,
                    textStyle: const TextStyle(fontSize: 16),
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
    if (status == 'Connecting...') return Colors.blue;
    return Colors.grey;
  }
}
