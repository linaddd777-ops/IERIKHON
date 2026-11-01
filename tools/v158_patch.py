from pathlib import Path
import runpy

# Start from the clean APP 1.5.7 + FW 1.5.4 compatible client.
runpy.run_path("tools/v157_patch.py", run_name="__main__")

p = Path("app/src/main/java/com/sdrx/ierikhon/MainActivityV152.java")
s = p.read_text(encoding="utf-8")


def replace_once(old, new, label):
    global s
    count = s.count(old)
    if count != 1:
        raise SystemExit(f"{label}: expected 1 match, got {count}")
    s = s.replace(old, new, 1)


replace_once(
    'private static final String APP_VERSION = "1.5.7";',
    'private static final String APP_VERSION = "1.5.8";',
    'app version'
)

old_handle = '''    private void handleRx(String raw) {
        String text = raw == null ? "" : raw.trim();
        if (text.isEmpty()) return;

        logEvent("RX", text);
'''
new_handle = '''    private void handleRx(String raw) {
        String text = raw == null ? "" : raw.trim();
        if (text.isEmpty()) return;

        String original = text;
        text = normalizeCompatReply(text);
        if (!original.equals(text)) {
            logEvent("BLE", "compat expanded truncated reply: " + original + " -> " + text);
        }
        logEvent("RX", text);
'''
replace_once(old_handle, new_handle, 'normalize RX before parsing')

anchor = '''    private static Integer parseTrailingInt(String text) {
'''
compat_method = '''    private String normalizeCompatReply(String text) {
        if (text == null || text.isEmpty()) return text;

        // FW 1.5.4 may notify through the default 20-byte ATT payload on some phones.
        // Repair only when the received value is an exact prefix of the response that
        // is already guaranteed by the confirmed starter pulse in this session.
        String expectedStart = "START_COMMAND_OK:GPIO25:" + pulseMs;
        if (text.length() < expectedStart.length() && expectedStart.startsWith(text)) {
            return expectedStart;
        }

        String expectedRelayOff = "RELAY_OFF:GPIO25:" + pulseMs;
        if (text.length() < expectedRelayOff.length() && expectedRelayOff.startsWith(text)) {
            return expectedRelayOff;
        }

        return text;
    }

'''
if anchor not in s:
    raise SystemExit('normalize method anchor missing')
s = s.replace(anchor, compat_method + anchor, 1)

for required in [
    'APP_VERSION = "1.5.8"',
    'FW_VERSION = "1.5.4"',
    'normalizeCompatReply(text)',
    'START_COMMAND_OK:GPIO25:',
    'RELAY_OFF:GPIO25:',
    'compat expanded truncated reply',
    'expectedStart.startsWith(text)',
    'expectedRelayOff.startsWith(text)',
    'ERROR_GATT_WRITE_REQUEST_BUSY',
    'canRead = false;'
]:
    if required not in s:
        raise SystemExit(f"required token missing: {required}")

for forbidden in [
    'queueWrite("PING")',
    'EXPECTED_PONG',
    'firmwareTimeout',
    'Проверяю версию ESP32',
    'BOOT:IERIKHON:',
    'PONG:IERIKHON:'
]:
    if forbidden in s:
        raise SystemExit(f"forbidden old handshake token: {forbidden}")

p.write_text(s, encoding="utf-8")
print("IERIKHON_V158_TRUNCATED_REPLY_COMPAT=PASS")
