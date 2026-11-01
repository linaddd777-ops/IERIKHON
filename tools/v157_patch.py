from pathlib import Path
import runpy

# Build on the proven red/compact UI and the Android-16 GATT sequencing fix.
runpy.run_path("tools/soft_ui_patch.py", run_name="__main__")

p = Path("app/src/main/java/com/sdrx/ierikhon/MainActivityV152.java")
s = p.read_text(encoding="utf-8")


def replace_once(old, new, label):
    global s
    count = s.count(old)
    if count != 1:
        raise SystemExit(f"{label}: expected 1 match, got {count}")
    s = s.replace(old, new, 1)


replace_once(
    'private static final String APP_VERSION = "1.5.6";',
    'private static final String APP_VERSION = "1.5.7";',
    'app version'
)
replace_once(
    'private static final String FW_VERSION = "1.5.3";',
    'private static final String FW_VERSION = "1.5.4";',
    'paired firmware label'
)

# The new firmware is deliberately WRITE + NOTIFY only. Reject any stale/old GATT shape.
anchor = '''            logEvent("GATT", "command properties=0x" + Integer.toHexString(p) + "; canRead=" + canRead);

            boolean waitingForDescriptor = false;'''
replacement = '''            logEvent("GATT", "command properties=0x" + Integer.toHexString(p) + "; canRead=" + canRead);

            boolean hasWrite = (p & BluetoothGattCharacteristic.PROPERTY_WRITE) != 0;
            boolean hasNotify = (p & BluetoothGattCharacteristic.PROPERTY_NOTIFY) != 0;
            if (!hasWrite || !hasNotify) {
                runOnUiThread(() -> reconnect("BLE-канал несовместим"));
                return;
            }

            boolean waitingForDescriptor = false;'''
replace_once(anchor, replacement, 'required WRITE+NOTIFY properties')

# No silent fallback: without CCCD there is no reliable confirmation channel.
old_ready = '''            final boolean descriptorPending = waitingForDescriptor;
            runOnUiThread(() -> {
                ui.removeCallbacks(serviceTimeout);
                if (!descriptorPending) markGattReady("без уведомлений");
                else setStatus("Подключаю канал управления…");
            });'''
new_ready = '''            final boolean descriptorPending = waitingForDescriptor;
            runOnUiThread(() -> {
                ui.removeCallbacks(serviceTimeout);
                if (!descriptorPending) {
                    reconnect("не удалось включить BLE-уведомления");
                } else {
                    setStatus("Подключаю канал управления…");
                }
            });'''
replace_once(old_ready, new_ready, 'descriptor required')

old_descriptor = '''        public void onDescriptorWrite(BluetoothGatt gg, BluetoothGattDescriptor descriptor, int statusCode) {
            logEvent("GATT", "descriptorWrite status=" + statusCode);
            runOnUiThread(() -> markGattReady(statusCode == BluetoothGatt.GATT_SUCCESS ? "notify ready" : "notify fallback"));
        }'''
new_descriptor = '''        public void onDescriptorWrite(BluetoothGatt gg, BluetoothGattDescriptor descriptor, int statusCode) {
            logEvent("GATT", "descriptorWrite status=" + statusCode);
            runOnUiThread(() -> {
                if (statusCode == BluetoothGatt.GATT_SUCCESS) markGattReady("notify ready");
                else reconnect("ошибка включения BLE-уведомлений: " + statusCode);
            });
        }'''
replace_once(old_descriptor, new_descriptor, 'descriptor status handling')

# New firmware has no READ property and no BOOT/PING characteristic contents.
replace_once(
    '            canRead = (cc.getProperties() & BluetoothGattCharacteristic.PROPERTY_READ) != 0;',
    '            canRead = false;',
    'disable characteristic reads'
)

# Delete the remaining compatibility branch that recognized old BOOT/PONG strings.
old_banner = '''        if (text.startsWith("BOOT:IERIKHON:") || text.startsWith("PONG:IERIKHON:")) {
            proto.setText("ESP32: ONLINE");
            logEvent("BLE", "device banner received");
            return;
        }

'''
replace_once(old_banner, '', 'remove old BOOT/PONG branch')

# User-facing build no longer advertises a firmware check; this is only a paired build label.
s = s.replace('logEvent("APP", "app=" + APP_VERSION + " fwExpected=" + FW_VERSION + " starterPulse=" + pulseMs);',
              'logEvent("APP", "app=" + APP_VERSION + " pairedFw=" + FW_VERSION + " starterPulse=" + pulseMs);')

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

for required in [
    'APP_VERSION = "1.5.7"',
    'FW_VERSION = "1.5.4"',
    'canRead = false;',
    'ERROR_GATT_WRITE_REQUEST_BUSY',
    'markGattReady("notify ready")',
    'BLE-канал несовместим',
    'не удалось включить BLE-уведомления'
]:
    if required not in s:
        raise SystemExit(f"required token missing: {required}")

p.write_text(s, encoding="utf-8")
print("IERIKHON_V157_APP_FW154_PAIR_PATCH=PASS")
