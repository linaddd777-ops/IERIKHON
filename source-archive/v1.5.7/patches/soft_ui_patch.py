from pathlib import Path
import re
import runpy

# Keep the proven v1.5.4 red UI + compact starter control as the visual base.
runpy.run_path("tools/v154_patch.py", run_name="__main__")

p = Path("app/src/main/java/com/sdrx/ierikhon/MainActivityV152.java")
s = p.read_text(encoding="utf-8")


def replace_once(old, new, label):
    global s
    count = s.count(old)
    if count != 1:
        raise SystemExit(f"{label}: expected 1 match, got {count}")
    s = s.replace(old, new, 1)


replace_once(
    'private static final String APP_VERSION = "1.5.4";',
    'private static final String APP_VERSION = "1.5.6";',
    'app version'
)
replace_once(
    'private static final String FW_VERSION = "1.5.2";',
    'private static final String FW_VERSION = "1.5.3";',
    'fw compatibility label'
)
replace_once(
    'private static final String EXPECTED_PONG = "PONG:IERIKHON:" + FW_VERSION;\n',
    '',
    'remove expected pong'
)
replace_once(
    'private static final int MAX_RECONNECTS = 2;',
    'private static final int MAX_RECONNECTS = 4;',
    'reconnect attempts'
)
replace_once(
    'ui.postDelayed(() -> connectGatt(target), 700);',
    'ui.postDelayed(() -> connectGatt(target), 900);',
    'reconnect backoff'
)

# 20 seconds was misleading for the user. The actual device is normally found almost instantly.
s = s.replace('scan 20s; seen=', 'scan 8s; seen=')
s = s.replace('setStatus("Сканирование BLE до 20 секунд");', 'setStatus("Ищу IERIKHON-01…");')
s = s.replace('ui.postDelayed(scanTimeout, 20000);', 'ui.postDelayed(scanTimeout, 8000);')

# Remove the old firmware-version timeout completely.
pattern = re.compile(r'\n    private final Runnable firmwareTimeout = \(\) -> \{.*?\n    \};\n', re.S)
s, count = pattern.subn('\n', s, count=1)
if count != 1:
    raise SystemExit(f'firmwareTimeout removal: expected 1, got {count}')

# Give queued writes a bounded retry counter for Android 13+ ERROR_GATT_WRITE_REQUEST_BUSY (201).
replace_once(
    '''        final int type;\n        final String command;\n        Op(int type, String command) {\n            this.type = type;\n            this.command = command;\n        }''',
    '''        final int type;\n        final String command;\n        int busyRetries;\n        Op(int type, String command) {\n            this.type = type;\n            this.command = command;\n        }''',
    'op retry field'
)

# Do NOT mark the command channel ready until CCCD write has actually completed.
replace_once(
    '            ch = cc;\n            serviceReady = true;\n            canRead = (cc.getProperties() & BluetoothGattCharacteristic.PROPERTY_READ) != 0;',
    '            ch = cc;\n            serviceReady = false;\n            canRead = (cc.getProperties() & BluetoothGattCharacteristic.PROPERTY_READ) != 0;',
    'service readiness gate'
)

old_services_tail = '''            try {\n                boolean notifyLocal = gg.setCharacteristicNotification(cc, true);\n                logEvent("GATT", "setCharacteristicNotification=" + notifyLocal);\n\n                BluetoothGattDescriptor desc = cc.getDescriptor(CCCD);\n                if (desc != null) {\n                    if (Build.VERSION.SDK_INT >= 33) {\n                        int result = gg.writeDescriptor(desc, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);\n                        logEvent("GATT", "writeDescriptor api33 result=" + result);\n                    } else {\n                        desc.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);\n                        boolean result = gg.writeDescriptor(desc);\n                        logEvent("GATT", "writeDescriptor legacy=" + result);\n                    }\n                } else {\n                    logEvent("GATT", "CCCD missing; continuing with READ fallback");\n                }\n            } catch (Exception ex) {\n                logEvent("GATT", "notify setup exception=" + ex.getClass().getSimpleName());\n            }\n\n            runOnUiThread(() -> {\n                ui.removeCallbacks(serviceTimeout);\n                conn.setText("● CHECK");\n                setStatus("Проверяю версию ESP32…");\n                queueWrite("PING");\n                if (canRead) ui.postDelayed(MainActivityV152.this::queueRead, 350);\n                ui.removeCallbacks(firmwareTimeout);\n                ui.postDelayed(firmwareTimeout, 4500);\n            });'''

new_services_tail = '''            boolean waitingForDescriptor = false;\n            try {\n                boolean notifyLocal = gg.setCharacteristicNotification(cc, true);\n                logEvent("GATT", "setCharacteristicNotification=" + notifyLocal);\n\n                BluetoothGattDescriptor desc = cc.getDescriptor(CCCD);\n                if (desc != null && notifyLocal) {\n                    if (Build.VERSION.SDK_INT >= 33) {\n                        int result = gg.writeDescriptor(desc, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);\n                        logEvent("GATT", "writeDescriptor api33 result=" + result);\n                        waitingForDescriptor = result == BluetoothStatusCodes.SUCCESS;\n                    } else {\n                        desc.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);\n                        boolean result = gg.writeDescriptor(desc);\n                        logEvent("GATT", "writeDescriptor legacy=" + result);\n                        waitingForDescriptor = result;\n                    }\n                } else {\n                    logEvent("GATT", "CCCD/notify unavailable; READ fallback");\n                }\n            } catch (Exception ex) {\n                logEvent("GATT", "notify setup exception=" + ex.getClass().getSimpleName());\n            }\n\n            final boolean descriptorPending = waitingForDescriptor;\n            runOnUiThread(() -> {\n                ui.removeCallbacks(serviceTimeout);\n                if (!descriptorPending) markGattReady("без уведомлений");\n                else setStatus("Подключаю канал управления…");\n            });'''
replace_once(old_services_tail, new_services_tail, 'service setup sequencing')

# Descriptor completion is the correct point at which the GATT command channel is free again.
anchor = '''        @Override\n        public void onCharacteristicChanged(BluetoothGatt gg, BluetoothGattCharacteristic characteristic) {'''
insert = '''        @Override\n        public void onDescriptorWrite(BluetoothGatt gg, BluetoothGattDescriptor descriptor, int statusCode) {\n            logEvent("GATT", "descriptorWrite status=" + statusCode);\n            runOnUiThread(() -> markGattReady(statusCode == BluetoothGatt.GATT_SUCCESS ? "notify ready" : "notify fallback"));\n        }\n\n        @Override\n        public void onCharacteristicChanged(BluetoothGatt gg, BluetoothGattCharacteristic characteristic) {'''
replace_once(anchor, insert, 'descriptor callback')

# Remove exact firmware handshake handling. BOOT/PONG are informational only now.
old_pong = '''        if (text.equals(EXPECTED_PONG)) {\n            verified = true;\n            ui.removeCallbacks(firmwareTimeout);\n            proto.setText("ESP32: " + text);\n            conn.setText("● ONLINE");\n            conn.setTextColor(Color.rgb(61, 225, 191));\n            setStatus("Связь подтверждена. Синхронизирую время стартера…");\n            hint.setText("Удерживай ЗАПУСТИТЬ 1.2 секунды");\n            syncPulseToEsp();\n            return;\n        }\n\n'''
new_pong = '''        if (text.startsWith("BOOT:IERIKHON:") || text.startsWith("PONG:IERIKHON:")) {\n            proto.setText("ESP32: ONLINE");\n            logEvent("BLE", "device banner received");\n            return;\n        }\n\n'''
replace_once(old_pong, new_pong, 'remove firmware handshake branch')

# Ready means: service + command characteristic exist and descriptor setup has finished.
method_anchor = '''    private void rx(String text) {\n        runOnUiThread(() -> handleRx(text));\n    }\n\n'''
method_insert = '''    private void rx(String text) {\n        runOnUiThread(() -> handleRx(text));\n    }\n\n    private void markGattReady(String detail) {\n        if (!connected || gatt == null || ch == null || serviceReady) return;\n\n        serviceReady = true;\n        verified = true;\n        pulseConfirmed = false;\n        ui.removeCallbacks(serviceTimeout);\n\n        conn.setText("● ONLINE");\n        conn.setTextColor(Color.rgb(90, 225, 160));\n        connect.setText("ОТКЛЮЧИТЬ");\n        dev.setText(NAME);\n        proto.setText("ESP32: ONLINE");\n        hint.setText("Удерживай ЗАВЕСТИ 1.2 секунды");\n        setStatus("ГОТОВО");\n        logEvent("GATT", "ready; " + detail);\n\n        start.setEnabled(false);\n        ui.postDelayed(this::syncPulseToEsp, 120);\n    }\n\n'''
replace_once(method_anchor, method_insert, 'mark ready method')

# Busy writes must be retried, not silently discarded.
old_busy = '''                logEvent("WRITE", "api33 result=" + result + " type=" + writeType);\n                if (result != BluetoothStatusCodes.SUCCESS) {\n                    finishOp();\n                    return;\n                }'''
new_busy = '''                logEvent("WRITE", "api33 result=" + result + " type=" + writeType);\n                if (result == BluetoothStatusCodes.ERROR_GATT_WRITE_REQUEST_BUSY) {\n                    Op retry = currentOp;\n                    int attempt = retry == null ? 99 : ++retry.busyRetries;\n                    logEvent("WRITE", "GATT busy retry=" + attempt + " cmd=" + cmd);\n                    opBusy = false;\n                    currentOp = null;\n                    if (retry != null && attempt <= 6) {\n                        ops.addFirst(retry);\n                        ui.postDelayed(this::pumpOps, 100L + attempt * 70L);\n                    } else {\n                        setStatus("BLE занят. Повтори команду.");\n                    }\n                    return;\n                }\n                if (result != BluetoothStatusCodes.SUCCESS) {\n                    logEvent("WRITE", "rejected result=" + result + " cmd=" + cmd);\n                    finishOp();\n                    return;\n                }'''
replace_once(old_busy, new_busy, 'GATT busy retry')

# No firmware timeout callback remains.
s = s.replace('        ui.removeCallbacks(firmwareTimeout);\n        ui.removeCallbacks(pulseSyncDebounce);', '        ui.removeCallbacks(pulseSyncDebounce);')

# Logs/UI no longer claim an exact firmware validation step.
s = s.replace('                    .append(" / EXPECTED FW ").append(FW_VERSION)\n                    .append(" / STARTER ")', '                    .append(" / STARTER ")')
s = s.replace('TextView foot = txt("APP v" + APP_VERSION + " • FW v" + FW_VERSION + " • GPIO25", 9, Color.rgb(62, 72, 81), false);',
              'TextView foot = txt("APP v" + APP_VERSION + " • BLE • GPIO25", 9, Color.rgb(62, 72, 81), false);')

# Remove any surviving user-facing firmware-check wording.
s = s.replace('Проверяю версию ESP32…', 'Подключаю ESP32…')
s = s.replace('● CHECK', '● CONNECT')

# Sanity: the old handshake must be completely gone from the built source.
for forbidden in ['EXPECTED_PONG', 'firmwareTimeout', 'queueWrite("PING")', 'ПРОВЕРКА ПРОШИВКИ', 'Проверяю версию ESP32']:
    if forbidden in s:
        raise SystemExit(f'forbidden handshake token still present: {forbidden}')

p.write_text(s, encoding="utf-8")
print("IERIKHON_V156_FAST_BLE_GATT_FIX=PASS")
