You are an expert Flutter + Kotlin developer.

Create a working Flutter Android app that does ONLY this:

- User pastes any text (up to 10k characters) into a large multiline TextField.
- User presses a single big button "START TYPING".
- The phone registers itself as a Bluetooth HID keyboard (no root needed).
- When paired with a PC, it types every character one-by-one into whatever has focus on the PC (real HID keyboard, works in BIOS, login, etc.).

Strict rules for this first version:
- Flutter 3.24+, Dart 3.5+, Android only (minSdkVersion 28 / Android 9+)
- Keep UI extremely simple — no themes, no progress bar, no settings, no speed slider.
- Use MethodChannel named 'bluetooth_hid' only.
- Do NOT add any extra features yet.

UI layout (very basic):
- Large TextField (multiline, expands) with label "Paste text here"
- Below it: Text widget showing status ("Not paired", "Paired & Ready", "Typing...", "Error")
- One big button: "START TYPING"
- One smaller button: "STOP"
- Text showing character count

Core flow:
- App must request Bluetooth HID permission and register as HID device (BluetoothHidDevice API).
- On "START TYPING" → native converts the entire string to HID reports (US QWERTY scan codes + modifiers).
- Sends key press → short fixed delay (25ms) → key release for every character.
- Support at minimum: a-z A-Z 0-9 space enter . , ! ? and common symbols.
- "STOP" immediately stops typing.

Native Kotlin side:
- Use BluetoothHidDevice (public API, no root)
- Proper HID keyboard report descriptor (boot protocol)
- Complete simple keymap: char → (scanCode, modifier)
- sendReport() function
- Background thread for typing with delay
- Return status via MethodChannel
- Detailed comments on every HID part

Generate in this exact order:
1. Full project structure (list all files)
2. Complete lib/main.dart with minimal UI and MethodChannel calls
3. The Dart service class
4. All Kotlin files (MainActivity.kt + BluetoothHidService.kt) with full implementation

Focus ONLY on making the Bluetooth HID typing actually work. We will add more features later.

Begin now.