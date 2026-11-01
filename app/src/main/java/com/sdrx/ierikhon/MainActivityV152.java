package com.sdrx.ierikhon;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.BluetoothStatusCodes;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.location.LocationManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelUuid;
import android.provider.Settings;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Space;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

public class MainActivityV152 extends Activity {
    private static final String APP_VERSION = "1.5.2";
    private static final String FW_VERSION = "1.5.2";
    private static final String NAME = "IERIKHON-01";
    private static final String EXPECTED_PONG = "PONG:IERIKHON:" + FW_VERSION;

    private static final UUID SERVICE = UUID.fromString("6f4f0001-8f3a-4d7a-9c12-4a494552494b");
    private static final UUID COMMAND = UUID.fromString("6f4f0002-8f3a-4d7a-9c12-4a494552494b");
    private static final UUID CCCD = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");

    private static final int REQ_BLE = 71;
    private static final long HOLD_MS = 1200L;
    private static final int MAX_RECONNECTS = 2;

    private static final int PULSE_MIN_MS = 500;
    private static final int PULSE_MAX_MS = 3000;
    private static final int PULSE_STEP_MS = 100;
    private static final int PULSE_DEFAULT_MS = 800;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final Object logLock = new Object();
    private final StringBuilder sessionLog = new StringBuilder();

    private BluetoothLeScanner scanner;
    private BluetoothGatt gatt;
    private BluetoothGattCharacteristic ch;
    private BluetoothDevice target;

    private boolean scanning;
    private boolean connected;
    private boolean serviceReady;
    private boolean verified;
    private boolean canRead;
    private boolean manualDisconnect;
    private boolean starting;
    private boolean holdPassed;
    private boolean armOk;
    private boolean startSent;
    private int seen;
    private int reconnects;

    private int pulseMs = PULSE_DEFAULT_MS;
    private boolean pulseConfirmed;

    private TextView conn;
    private TextView dev;
    private TextView status;
    private TextView proto;
    private TextView hint;
    private TextView pulseValue;
    private TextView pulseState;
    private TextView logPreview;
    private Button connect;
    private Button start;
    private Button pulseMinus;
    private Button pulsePlus;

    private File logFile;
    private SharedPreferences prefs;

    private static final int OP_WRITE = 1;
    private static final int OP_READ = 2;

    private static final class Op {
        final int type;
        final String command;
        Op(int type, String command) {
            this.type = type;
            this.command = command;
        }
    }

    private final ArrayDeque<Op> ops = new ArrayDeque<>();
    private boolean opBusy;
    private Op currentOp;
    private int opSerial;

    private final Runnable scanTimeout = () -> {
        if (!scanning) return;
        logEvent("TIMEOUT", "scan 20s; seen=" + seen);
        stopScan();
        disconnectedUi();
        setStatus("IERIKHON-01 не найден. BLE-устройств замечено: " + seen);
    };

    private final Runnable connectTimeout = () -> {
        if (verified || manualDisconnect) return;
        logEvent("TIMEOUT", "GATT connect");
        reconnect("таймаут подключения");
    };

    private final Runnable serviceTimeout = () -> {
        if (!connected || serviceReady || manualDisconnect) return;
        logEvent("TIMEOUT", "service discovery");
        reconnect("таймаут чтения BLE-сервисов");
    };

    private final Runnable firmwareTimeout = () -> {
        if (!serviceReady || verified) return;
        logEvent("FW", "handshake timeout expected=" + EXPECTED_PONG);
        start.setEnabled(false);
        conn.setText("●  НЕВЕРНАЯ ПРОШИВКА");
        conn.setTextColor(Color.rgb(255, 98, 98));
        setStatus("ESP32 не ответила " + EXPECTED_PONG + ". Прошей FW v" + FW_VERSION);
    };

    private final Runnable holdDone = () -> {
        if (!starting) return;
        holdPassed = true;
        logEvent("START", "hold 1200ms reached; armOk=" + armOk + "; pulse=" + pulseMs);
        hint.setText(armOk ? "Запускаю…" : "Удержание принято • жду ESP32");
        finishStartIfReady();
    };

    private final Runnable armTimeout = () -> {
        if (!starting || startSent) return;
        logEvent("TIMEOUT", "ARM confirmation 4.5s");
        queueWrite("DISARM");
        resetStart();
        start.setEnabled(verified && pulseConfirmed);
        hint.setText("ARM не подтверждён");
        setStatus("ESP32 не подтвердила ARM. START не отправлен.");
    };

    private final Runnable relayTimeout = () -> {
        if (!startSent) return;
        logEvent("TIMEOUT", "no RELAY_OFF after START");
        startSent = false;
        start.setEnabled(verified && pulseConfirmed);
        hint.setText("Нет RELAY_OFF • отправь лог");
        setStatus("Нет ответа RELAY_OFF от ESP32. Нажми «ОТПРАВИТЬ ЛОГ».");
    };

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        prefs = getSharedPreferences("ierikhon", MODE_PRIVATE);
        pulseMs = clampPulse(prefs.getInt("starter_pulse_ms", PULSE_DEFAULT_MS));
        logFile = new File(getFilesDir(), "ierikhon-debug.log");

        getWindow().setStatusBarColor(Color.rgb(6, 9, 12));
        getWindow().setNavigationBarColor(Color.rgb(6, 9, 12));
        setContentView(makeUi());
        renderPulse();
        disconnectedUi();

        logEvent("APP", "===== NEW SESSION =====");
        logEvent("APP", "app=" + APP_VERSION + " fwExpected=" + FW_VERSION + " starterPulse=" + pulseMs);
        logEvent("PHONE", Build.MANUFACTURER + " " + Build.MODEL + "; Android=" + Build.VERSION.RELEASE + "; SDK=" + Build.VERSION.SDK_INT);
        logEvent("PERM", permissionsSnapshot());
        setStatus("Готов к подключению");
    }

    private View makeUi() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(18), dp(20), dp(24));
        root.setBackgroundColor(Color.rgb(6, 9, 12));
        scroll.addView(root);

        root.addView(txt("SDRX • REMOTE START", 11, Color.rgb(107, 128, 140), true));
        root.addView(txt("ИЕРИХОН", 34, Color.WHITE, true), top(2));
        TextView ver = txt("TEST BUILD v" + APP_VERSION + " • LOG ENABLED", 12, Color.rgb(61, 225, 191), true);
        LinearLayout.LayoutParams vp = top(3);
        vp.bottomMargin = dp(16);
        root.addView(ver, vp);

        LinearLayout card = card();
        conn = txt("●  НЕ ПОДКЛЮЧЕНО", 15, Color.rgb(255, 98, 98), true);
        dev = txt("Устройство: " + NAME, 13, Color.rgb(184, 197, 207), false);
        connect = btn("ПОДКЛЮЧИТЬ");
        connect.setOnClickListener(v -> {
            logEvent("UI", "CONNECT click; scanning=" + scanning + " connected=" + connected + " serviceReady=" + serviceReady);
            if (scanning || connected || serviceReady) disconnect();
            else beginConnect();
        });
        card.addView(conn);
        card.addView(dev, top(8));
        card.addView(connect, top(14));
        root.addView(card);

        root.addView(space(14));

        LinearLayout pulseCard = card();
        pulseCard.addView(txt("ВРЕМЯ СТАРТЕРА", 11, Color.rgb(107, 128, 140), true));
        pulseValue = txt("", 26, Color.WHITE, true);
        pulseValue.setGravity(Gravity.CENTER);
        pulseCard.addView(pulseValue, top(8));

        LinearLayout controls = new LinearLayout(this);
        controls.setOrientation(LinearLayout.HORIZONTAL);
        controls.setGravity(Gravity.CENTER);

        pulseMinus = btn("−");
        pulsePlus = btn("+");
        pulseMinus.setTextSize(24);
        pulsePlus.setTextSize(24);

        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(0, dp(54), 1f);
        controls.addView(pulseMinus, cp);
        Space mid = new Space(this);
        controls.addView(mid, new LinearLayout.LayoutParams(dp(12), 1));
        controls.addView(pulsePlus, new LinearLayout.LayoutParams(0, dp(54), 1f));

        pulseMinus.setOnClickListener(v -> changePulse(-PULSE_STEP_MS));
        pulsePlus.setOnClickListener(v -> changePulse(PULSE_STEP_MS));

        pulseCard.addView(controls, top(10));
        pulseState = txt("Диапазон 0.5–3.0 с • шаг 0.1 с", 11, Color.rgb(123, 143, 155), false);
        pulseState.setGravity(Gravity.CENTER);
        pulseCard.addView(pulseState, top(8));
        root.addView(pulseCard);

        root.addView(space(16));

        TextView z = txt("ЗАПУСК", 11, Color.rgb(107, 128, 140), true);
        z.setGravity(Gravity.CENTER);
        root.addView(z);

        start = new Button(this);
        start.setText("ЗАПУСТИТЬ");
        start.setTextSize(23);
        start.setTextColor(Color.rgb(88, 99, 108));
        start.setTypeface(Typeface.DEFAULT_BOLD);
        start.setAllCaps(false);
        start.setEnabled(false);
        start.setBackground(round(Color.rgb(14, 20, 25), Color.rgb(42, 54, 63), 34, 2));
        start.setOnTouchListener((v, e) -> {
            int action = e.getActionMasked();
            if (action == MotionEvent.ACTION_DOWN) {
                logEvent("UI", "START DOWN; verified=" + verified + " pulseConfirmed=" + pulseConfirmed + " pulse=" + pulseMs);
                if (!verified) {
                    setStatus("Сначала дождись «ГОТОВО»");
                    return true;
                }
                if (!pulseConfirmed) {
                    setStatus("Жду подтверждение времени стартера от ESP32");
                    syncPulseToEsp();
                    return true;
                }
                beginStart();
                return true;
            }
            if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                logEvent("UI", "START " + (action == MotionEvent.ACTION_UP ? "UP" : "CANCEL") +
                        "; starting=" + starting + " holdPassed=" + holdPassed + " armOk=" + armOk + " startSent=" + startSent);
                if (starting && !startSent) {
                    if (!holdPassed) {
                        queueWrite("DISARM");
                        resetStart();
                        start.setEnabled(verified && pulseConfirmed);
                        hint.setText("Удерживай полные 1.2 секунды");
                        setStatus("Отпущено слишком рано");
                    } else {
                        hint.setText("Удержание принято • жду ESP32");
                        finishStartIfReady();
                    }
                }
                return true;
            }
            return true;
        });

        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(150));
        sp.topMargin = dp(8);
        root.addView(start, sp);

        hint = txt("Сначала подключись к IERIKHON-01", 12, Color.rgb(123, 143, 155), false);
        hint.setGravity(Gravity.CENTER);
        root.addView(hint, top(8));

        root.addView(space(16));

        LinearLayout diag = card();
        diag.addView(txt("ДИАГНОСТИКА", 11, Color.rgb(107, 128, 140), true));
        status = txt("—", 14, Color.WHITE, false);
        diag.addView(status, top(7));
        proto = txt("ESP32: —", 12, Color.rgb(123, 143, 155), false);
        diag.addView(proto, top(9));

        TextView help = txt("Если что-то не сработало — сразу нажми «ОТПРАВИТЬ ЛОГ» и пришли текст разработчику.", 12, Color.rgb(255, 190, 98), false);
        diag.addView(help, top(12));

        Button share = btn("ОТПРАВИТЬ ЛОГ");
        share.setOnClickListener(v -> shareLog());
        diag.addView(share, top(10));

        Button copy = btn("СКОПИРОВАТЬ ЛОГ");
        copy.setOnClickListener(v -> copyLog());
        diag.addView(copy, top(8));

        logPreview = txt("Лог появится здесь…", 10, Color.rgb(126, 148, 160), false);
        logPreview.setTypeface(Typeface.MONOSPACE);
        logPreview.setTextIsSelectable(true);
        diag.addView(logPreview, top(10));
        root.addView(diag);

        TextView foot = txt("APP v" + APP_VERSION + " • FW v" + FW_VERSION + " • GPIO25 • STARTER " + formatPulse(pulseMs), 10, Color.rgb(72, 87, 97), false);
        foot.setGravity(Gravity.CENTER);
        root.addView(foot, top(12));

        return scroll;
    }

    private void changePulse(int delta) {
        if (starting || startSent) {
            setStatus("Нельзя менять время, пока идёт запуск");
            return;
        }

        int next = clampPulse(pulseMs + delta);
        if (next == pulseMs) return;

        pulseMs = next;
        pulseConfirmed = false;
        prefs.edit().putInt("starter_pulse_ms", pulseMs).apply();
        renderPulse();

        logEvent("PULSE", "user selected " + pulseMs + "ms");

        if (verified) {
            start.setEnabled(false);
            syncPulseToEsp();
        }
    }

    private void renderPulse() {
        if (pulseValue != null) pulseValue.setText(formatPulse(pulseMs));
        if (pulseState != null) {
            pulseState.setText(pulseConfirmed
                    ? "ESP32 подтверждено: " + formatPulse(pulseMs)
                    : "Диапазон 0.5–3.0 с • шаг 0.1 с");
        }
        if (pulseMinus != null) pulseMinus.setEnabled(pulseMs > PULSE_MIN_MS && !starting && !startSent);
        if (pulsePlus != null) pulsePlus.setEnabled(pulseMs < PULSE_MAX_MS && !starting && !startSent);
    }

    private void syncPulseToEsp() {
        if (!verified || ch == null) return;
        pulseConfirmed = false;
        renderPulse();
        String cmd = "SET_PULSE:" + pulseMs;
        logEvent("PULSE", "sync -> " + cmd);
        queueWrite(cmd);
        ui.postDelayed(() -> {
            if (verified && !pulseConfirmed) {
                queueWrite("GET_PULSE");
                setStatus("Проверяю время стартера на ESP32…");
            }
        }, 700);
    }

    private static int clampPulse(int value) {
        if (value < PULSE_MIN_MS) return PULSE_MIN_MS;
        if (value > PULSE_MAX_MS) return PULSE_MAX_MS;
        return value;
    }

    private static String formatPulse(int ms) {
        return String.format(Locale.US, "%.1f с", ms / 1000.0);
    }

    private void beginConnect() {
        manualDisconnect = false;
        reconnects = 0;
        seen = 0;
        target = null;
        pulseConfirmed = false;
        renderPulse();

        logEvent("BLE", "beginConnect; " + permissionsSnapshot());

        if (!permissionsOk()) {
            logEvent("PERM", "requesting BLE permissions");
            requestPermissionsNow();
            return;
        }

        if (!locationOk()) {
            logEvent("PERM", "legacy location disabled");
            setStatus("Android 6–11: включи геолокацию для BLE");
            try {
                startActivity(new Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS));
            } catch (Exception e) {
                logEvent("ERR", "open location settings: " + e.getClass().getSimpleName());
            }
            return;
        }

        scan();
    }

    @SuppressLint("MissingPermission")
    private void scan() {
        BluetoothManager bm = (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
        BluetoothAdapter ba = bm == null ? null : bm.getAdapter();

        if (ba == null) {
            error("Bluetooth недоступен");
            return;
        }

        if (!ba.isEnabled()) {
            logEvent("BLE", "adapter disabled");
            try {
                startActivity(new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE));
            } catch (Exception e) {
                logEvent("ERR", "ACTION_REQUEST_ENABLE: " + e.getClass().getSimpleName());
            }
            setStatus("Включи Bluetooth и снова нажми ПОДКЛЮЧИТЬ");
            return;
        }

        closeGatt();
        scanner = ba.getBluetoothLeScanner();

        if (scanner == null) {
            error("BLE-сканер недоступен");
            return;
        }

        scanning = true;
        seen = 0;
        conn.setText("●  ПОИСК…");
        conn.setTextColor(Color.rgb(255, 185, 92));
        connect.setText("ОТМЕНА");
        dev.setText("Ищу имя и UUID сервиса…");
        setStatus("Сканирование BLE до 20 секунд");
        logEvent("SCAN", "start LOW_LATENCY; target=" + NAME + "; service=" + SERVICE);

        try {
            scanner.startScan(null,
                    new ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(),
                    scanCb);
            ui.removeCallbacks(scanTimeout);
            ui.postDelayed(scanTimeout, 20000);
        } catch (SecurityException ex) {
            error("Нет разрешения BLE SCAN: " + ex.getClass().getSimpleName());
        }
    }

    private final ScanCallback scanCb = new ScanCallback() {
        @Override
        @SuppressLint("MissingPermission")
        public void onScanResult(int type, ScanResult r) {
            seen++;
            BluetoothDevice d = r.getDevice();
            String name = null;

            try {
                name = d.getName();
            } catch (SecurityException ignored) { }

            if (name == null && r.getScanRecord() != null) {
                name = r.getScanRecord().getDeviceName();
            }

            boolean serviceMatch = false;
            if (r.getScanRecord() != null) {
                List<ParcelUuid> ids = r.getScanRecord().getServiceUuids();
                if (ids != null) {
                    for (ParcelUuid id : ids) {
                        if (SERVICE.equals(id.getUuid())) {
                            serviceMatch = true;
                            break;
                        }
                    }
                }
            }

            if (NAME.equals(name) || serviceMatch) {
                logEvent("SCAN", "match name=" + name + " rssi=" + r.getRssi() + " serviceMatch=" + serviceMatch);
                target = d;
                stopScan();
                connectGatt(d);
            }
        }

        @Override
        public void onScanFailed(int code) {
            scanning = false;
            ui.removeCallbacks(scanTimeout);
            logEvent("SCAN", "failed code=" + code);
            error("Ошибка BLE-сканирования: " + code);
        }
    };

    @SuppressLint("MissingPermission")
    private void connectGatt(BluetoothDevice d) {
        if (d == null) {
            error("ESP32 не выбрана");
            return;
        }

        conn.setText("●  ПОДКЛЮЧЕНИЕ…");
        conn.setTextColor(Color.rgb(255, 185, 92));
        dev.setText("IERIKHON-01 найден • подключаю GATT");
        setStatus("Подключаюсь к ESP32…");
        logEvent("GATT", "connectGatt");

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                gatt = d.connectGatt(this, false, gattCb, BluetoothDevice.TRANSPORT_LE);
            } else {
                gatt = d.connectGatt(this, false, gattCb);
            }
            ui.removeCallbacks(connectTimeout);
            ui.postDelayed(connectTimeout, 12000);
        } catch (SecurityException ex) {
            error("Нет разрешения BLUETOOTH_CONNECT");
        }
    }

    private final BluetoothGattCallback gattCb = new BluetoothGattCallback() {
        @Override
        @SuppressLint("MissingPermission")
        public void onConnectionStateChange(BluetoothGatt gg, int statusCode, int newState) {
            logEvent("GATT", "stateChange status=" + statusCode + " newState=" + newState);

            if (statusCode == BluetoothGatt.GATT_SUCCESS && newState == BluetoothProfile.STATE_CONNECTED) {
                connected = true;
                ui.removeCallbacks(connectTimeout);

                runOnUiThread(() -> {
                    conn.setText("●  СОЕДИНЕНО");
                    conn.setTextColor(Color.rgb(61, 225, 191));
                    connect.setText("ОТКЛЮЧИТЬ");
                    dev.setText("Устройство: " + NAME + " • GATT");
                    setStatus("BLE подключён. Читаю сервис…");
                });

                try {
                    gg.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH);
                } catch (Exception ignored) { }

                ui.postDelayed(() -> {
                    if (gatt != gg || !connected) return;
                    try {
                        boolean ok = gg.discoverServices();
                        logEvent("GATT", "discoverServices=" + ok);
                        if (!ok) reconnect("discoverServices=false");
                    } catch (SecurityException ex) {
                        reconnect("нет разрешения на discoverServices");
                    }
                }, 350);

                ui.removeCallbacks(serviceTimeout);
                ui.postDelayed(serviceTimeout, 9000);
                return;
            }

            connected = false;
            serviceReady = false;
            verified = false;
            pulseConfirmed = false;

            if (manualDisconnect) {
                runOnUiThread(MainActivityV152.this::disconnectedUi);
            } else {
                runOnUiThread(() -> reconnect("GATT status=" + statusCode + " state=" + newState));
            }
        }

        @Override
        @SuppressLint("MissingPermission")
        public void onServicesDiscovered(BluetoothGatt gg, int statusCode) {
            logEvent("GATT", "servicesDiscovered status=" + statusCode);

            if (statusCode != BluetoothGatt.GATT_SUCCESS) {
                runOnUiThread(() -> reconnect("ошибка чтения сервисов: " + statusCode));
                return;
            }

            BluetoothGattService svc = gg.getService(SERVICE);
            if (svc == null) {
                runOnUiThread(() -> reconnect("сервис IERIKHON не найден"));
                return;
            }

            BluetoothGattCharacteristic cc = svc.getCharacteristic(COMMAND);
            if (cc == null) {
                runOnUiThread(() -> reconnect("BLE-канал COMMAND не найден"));
                return;
            }

            ch = cc;
            serviceReady = true;
            canRead = (cc.getProperties() & BluetoothGattCharacteristic.PROPERTY_READ) != 0;
            int p = cc.getProperties();

            logEvent("GATT", "command properties=0x" + Integer.toHexString(p) + "; canRead=" + canRead);

            try {
                boolean notifyLocal = gg.setCharacteristicNotification(cc, true);
                logEvent("GATT", "setCharacteristicNotification=" + notifyLocal);

                BluetoothGattDescriptor desc = cc.getDescriptor(CCCD);
                if (desc != null) {
                    if (Build.VERSION.SDK_INT >= 33) {
                        int result = gg.writeDescriptor(desc, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
                        logEvent("GATT", "writeDescriptor api33 result=" + result);
                    } else {
                        desc.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
                        boolean result = gg.writeDescriptor(desc);
                        logEvent("GATT", "writeDescriptor legacy=" + result);
                    }
                } else {
                    logEvent("GATT", "CCCD missing; continuing with READ fallback");
                }
            } catch (Exception ex) {
                logEvent("GATT", "notify setup exception=" + ex.getClass().getSimpleName());
            }

            runOnUiThread(() -> {
                ui.removeCallbacks(serviceTimeout);
                conn.setText("●  ПРОВЕРКА ПРОШИВКИ…");
                setStatus("Проверяю версию ESP32…");
                queueWrite("PING");
                if (canRead) ui.postDelayed(MainActivityV152.this::queueRead, 350);
                ui.removeCallbacks(firmwareTimeout);
                ui.postDelayed(firmwareTimeout, 4500);
            });
        }

        @Override
        public void onCharacteristicChanged(BluetoothGatt gg, BluetoothGattCharacteristic characteristic) {
            byte[] value = characteristic.getValue();
            rx(new String(value == null ? new byte[0] : value, StandardCharsets.UTF_8));
        }

        @Override
        public void onCharacteristicChanged(BluetoothGatt gg, BluetoothGattCharacteristic characteristic, byte[] value) {
            rx(new String(value == null ? new byte[0] : value, StandardCharsets.UTF_8));
        }

        @Override
        public void onCharacteristicRead(BluetoothGatt gg, BluetoothGattCharacteristic characteristic, int statusCode) {
            String value = "";
            if (statusCode == BluetoothGatt.GATT_SUCCESS) {
                byte[] data = characteristic.getValue();
                value = new String(data == null ? new byte[0] : data, StandardCharsets.UTF_8);
            }
            final String v = value;
            runOnUiThread(() -> {
                logEvent("READ", "status=" + statusCode + " value=" + v);
                if (statusCode == BluetoothGatt.GATT_SUCCESS) handleRx(v);
                finishOp();
            });
        }

        @Override
        public void onCharacteristicRead(BluetoothGatt gg, BluetoothGattCharacteristic characteristic, byte[] value, int statusCode) {
            String v = statusCode == BluetoothGatt.GATT_SUCCESS
                    ? new String(value == null ? new byte[0] : value, StandardCharsets.UTF_8)
                    : "";
            runOnUiThread(() -> {
                logEvent("READ", "status=" + statusCode + " value=" + v);
                if (statusCode == BluetoothGatt.GATT_SUCCESS) handleRx(v);
                finishOp();
            });
        }

        @Override
        public void onCharacteristicWrite(BluetoothGatt gg, BluetoothGattCharacteristic characteristic, int statusCode) {
            runOnUiThread(() -> {
                logEvent("WRITE", "status=" + statusCode + " cmd=" + (currentOp == null ? "?" : currentOp.command));
                finishOp();
                if (canRead) ui.postDelayed(MainActivityV152.this::queueRead, 220);
            });
        }
    };

    private void rx(String text) {
        runOnUiThread(() -> handleRx(text));
    }

    private void handleRx(String raw) {
        String text = raw == null ? "" : raw.trim();
        if (text.isEmpty()) return;

        logEvent("RX", text);

        if (text.equals(EXPECTED_PONG)) {
            verified = true;
            ui.removeCallbacks(firmwareTimeout);
            proto.setText("ESP32: " + text);
            conn.setText("●  ГОТОВО");
            conn.setTextColor(Color.rgb(61, 225, 191));
            setStatus("Связь подтверждена. Синхронизирую время стартера…");
            hint.setText("Удерживай ЗАПУСТИТЬ 1.2 секунды");
            syncPulseToEsp();
            return;
        }

        if (text.startsWith("PULSE_OK:") || text.startsWith("PULSE:")) {
            Integer ms = parseTrailingInt(text);
            if (ms != null) {
                if (ms != pulseMs) {
                    logEvent("PULSE", "ESP returned " + ms + "ms, app wants " + pulseMs + "ms");
                    queueWrite("SET_PULSE:" + pulseMs);
                    return;
                }
                pulseConfirmed = true;
                renderPulse();
                start.setEnabled(verified);
                pulseState.setText("ESP32 подтверждено: " + formatPulse(pulseMs));
                setStatus("ГОТОВО • стартер " + formatPulse(pulseMs));
                logEvent("PULSE", "confirmed " + pulseMs + "ms");
            }
            return;
        }

        if (text.equals("ARMED")) {
            armOk = true;
            logEvent("START", "ARMED received; holdPassed=" + holdPassed);
            finishStartIfReady();
            return;
        }

        if (text.startsWith("START_COMMAND_OK")) {
            startSent = true;
            starting = false;
            ui.removeCallbacks(armTimeout);
            ui.removeCallbacks(holdDone);
            start.setEnabled(false);
            renderPulse();

            Integer actual = parseTrailingInt(text);
            if (actual != null) {
                setStatus("START принят ESP32 • стартер " + formatPulse(actual));
                hint.setText("Стартер крутит " + formatPulse(actual) + "…");
                logEvent("START", "relay active actualPulse=" + actual);
            } else {
                setStatus("START принят ESP32");
                hint.setText("Команда START принята");
            }

            ui.removeCallbacks(relayTimeout);
            ui.postDelayed(relayTimeout, pulseMs + 2500L);
            return;
        }

        if (text.startsWith("RELAY_OFF")) {
            ui.removeCallbacks(relayTimeout);
            startSent = false;
            starting = false;
            armOk = false;
            holdPassed = false;
            start.setEnabled(verified && pulseConfirmed);
            renderPulse();
            hint.setText("Готово • удерживай 1.2 секунды для следующего запуска");
            setStatus("Реле стартера отключено");
            return;
        }

        if (text.startsWith("DENIED:")) {
            ui.removeCallbacks(armTimeout);
            ui.removeCallbacks(relayTimeout);
            resetStart();
            start.setEnabled(verified && pulseConfirmed);
            renderPulse();
            hint.setText("Команда отклонена • отправь лог");
            setStatus(text);
            return;
        }

        if (text.equals("DISARMED") || text.equals("ARM_TIMEOUT")) {
            if (starting && !startSent) {
                resetStart();
                start.setEnabled(verified && pulseConfirmed);
                renderPulse();
            }
        }
    }

    private static Integer parseTrailingInt(String text) {
        int i = text.lastIndexOf(':');
        if (i < 0 || i == text.length() - 1) return null;
        try {
            return Integer.parseInt(text.substring(i + 1).trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private void beginStart() {
        if (!verified || !pulseConfirmed || starting || startSent) return;

        starting = true;
        holdPassed = false;
        armOk = false;
        start.setEnabled(false);
        renderPulse();
        hint.setText("Удерживай 1.2 секунды…");
        setStatus("ARM → жду подтверждение ESP32");
        logEvent("START", "begin pulse=" + pulseMs + "ms");

        queueWrite("ARM");

        ui.removeCallbacks(holdDone);
        ui.postDelayed(holdDone, HOLD_MS);
        ui.removeCallbacks(armTimeout);
        ui.postDelayed(armTimeout, 4500);
    }

    private void finishStartIfReady() {
        if (!starting || startSent || !holdPassed || !armOk) return;

        startSent = true;
        starting = false;
        ui.removeCallbacks(holdDone);
        ui.removeCallbacks(armTimeout);
        hint.setText("Отправляю START…");
        setStatus("START → ESP32 • " + formatPulse(pulseMs));
        logEvent("START", "sending START; pulse=" + pulseMs);
        queueWrite("START");
    }

    private void resetStart() {
        starting = false;
        holdPassed = false;
        armOk = false;
        startSent = false;
        ui.removeCallbacks(holdDone);
        ui.removeCallbacks(armTimeout);
        ui.removeCallbacks(relayTimeout);
        renderPulse();
    }

    private void queueWrite(String command) {
        ops.add(new Op(OP_WRITE, command));
        pumpOps();
    }

    private void queueRead() {
        if (!canRead) return;
        ops.add(new Op(OP_READ, ""));
        pumpOps();
    }

    @SuppressLint("MissingPermission")
    private void pumpOps() {
        if (opBusy || ops.isEmpty() || gatt == null || ch == null) return;

        currentOp = ops.poll();
        opBusy = true;
        int serial = ++opSerial;

        if (currentOp.type == OP_READ) {
            try {
                boolean ok = gatt.readCharacteristic(ch);
                logEvent("READ", "queued ok=" + ok);
                if (!ok) finishOp();
            } catch (SecurityException e) {
                logEvent("ERR", "readCharacteristic SecurityException");
                finishOp();
            }
            return;
        }

        String cmd = currentOp.command;
        byte[] data = cmd.getBytes(StandardCharsets.UTF_8);
        logEvent("TX", cmd);

        int props = ch.getProperties();
        boolean writeWithResponse = (props & BluetoothGattCharacteristic.PROPERTY_WRITE) != 0;

        try {
            if (Build.VERSION.SDK_INT >= 33) {
                int writeType = writeWithResponse
                        ? BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                        : BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE;
                int result = gatt.writeCharacteristic(ch, data, writeType);
                logEvent("WRITE", "api33 result=" + result + " type=" + writeType);
                if (result != BluetoothStatusCodes.SUCCESS) {
                    finishOp();
                    return;
                }
                if (!writeWithResponse) {
                    ui.postDelayed(() -> {
                        if (opBusy && opSerial == serial) {
                            finishOp();
                            if (canRead) ui.postDelayed(this::queueRead, 200);
                        }
                    }, 160);
                }
            } else {
                ch.setWriteType(writeWithResponse
                        ? BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                        : BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE);
                ch.setValue(data);
                boolean ok = gatt.writeCharacteristic(ch);
                logEvent("WRITE", "legacy ok=" + ok + " type=" + ch.getWriteType());
                if (!ok) {
                    finishOp();
                    return;
                }
                if (!writeWithResponse) {
                    ui.postDelayed(() -> {
                        if (opBusy && opSerial == serial) {
                            finishOp();
                            if (canRead) ui.postDelayed(this::queueRead, 200);
                        }
                    }, 160);
                }
            }
        } catch (SecurityException ex) {
            logEvent("ERR", "writeCharacteristic SecurityException cmd=" + cmd);
            finishOp();
        }
    }

    private void finishOp() {
        opBusy = false;
        currentOp = null;
        ui.postDelayed(this::pumpOps, 45);
    }

    private void clearOps() {
        ops.clear();
        opBusy = false;
        currentOp = null;
        opSerial++;
    }

    private void reconnect(String reason) {
        if (manualDisconnect) return;

        logEvent("RECONNECT", reason + "; attempt=" + reconnects + "/" + MAX_RECONNECTS);

        if (reconnects >= MAX_RECONNECTS || target == null) {
            closeGatt();
            disconnectedUi();
            setStatus("Не удалось подключиться: " + reason + ". Нажми ПОДКЛЮЧИТЬ и отправь лог.");
            return;
        }

        reconnects++;
        closeGatt();
        setStatus("Повтор BLE " + reconnects + "/" + MAX_RECONNECTS + ": " + reason);
        ui.postDelayed(() -> connectGatt(target), 700);
    }

    @SuppressLint("MissingPermission")
    private void stopScan() {
        ui.removeCallbacks(scanTimeout);
        if (!scanning) return;
        scanning = false;

        try {
            if (scanner != null) scanner.stopScan(scanCb);
        } catch (Exception e) {
            logEvent("ERR", "stopScan: " + e.getClass().getSimpleName());
        }
    }

    @SuppressLint("MissingPermission")
    private void disconnect() {
        manualDisconnect = true;
        logEvent("BLE", "manual disconnect");
        stopScan();

        try {
            if (gatt != null) gatt.disconnect();
        } catch (SecurityException ignored) { }

        closeGatt();
        disconnectedUi();
        setStatus("Отключено");
    }

    @SuppressLint("MissingPermission")
    private void closeGatt() {
        ui.removeCallbacks(connectTimeout);
        ui.removeCallbacks(serviceTimeout);
        ui.removeCallbacks(firmwareTimeout);
        resetStart();

        serviceReady = false;
        verified = false;
        connected = false;
        pulseConfirmed = false;
        ch = null;
        canRead = false;
        clearOps();

        if (gatt != null) {
            try {
                gatt.close();
            } catch (Exception ignored) { }
        }
        gatt = null;
        renderPulse();
    }

    private void disconnectedUi() {
        connected = false;
        serviceReady = false;
        verified = false;
        pulseConfirmed = false;

        if (conn != null) {
            conn.setText("●  НЕ ПОДКЛЮЧЕНО");
            conn.setTextColor(Color.rgb(255, 98, 98));
        }
        if (dev != null) dev.setText("Устройство: " + NAME);
        if (connect != null) {
            connect.setEnabled(true);
            connect.setText("ПОДКЛЮЧИТЬ");
        }
        if (start != null) {
            start.setEnabled(false);
            start.setTextColor(Color.rgb(88, 99, 108));
        }
        if (hint != null) hint.setText("Сначала подключись к IERIKHON-01");
        if (proto != null) proto.setText("ESP32: —");
        renderPulse();
    }

    private void error(String text) {
        logEvent("ERR", text);
        setStatus(text);
        Toast.makeText(this, text, Toast.LENGTH_LONG).show();
    }

    private void setStatus(String text) {
        if (status != null) status.setText(text);
        logEvent("STATUS", text);
    }

    private boolean permissionsOk() {
        if (Build.VERSION.SDK_INT >= 31) {
            return checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED
                    && checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED;
        }
        return checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    private void requestPermissionsNow() {
        if (Build.VERSION.SDK_INT >= 31) {
            requestPermissions(new String[]{
                    Manifest.permission.BLUETOOTH_SCAN,
                    Manifest.permission.BLUETOOTH_CONNECT
            }, REQ_BLE);
        } else {
            requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION}, REQ_BLE);
        }
    }

    private boolean locationOk() {
        if (Build.VERSION.SDK_INT >= 31) return true;
        LocationManager lm = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
        if (lm == null) return true;
        try {
            return lm.isProviderEnabled(LocationManager.GPS_PROVIDER)
                    || lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER);
        } catch (Exception e) {
            return true;
        }
    }

    private String permissionsSnapshot() {
        if (Build.VERSION.SDK_INT >= 31) {
            return "SCAN=" + (checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED)
                    + "; CONNECT=" + (checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED);
        }
        return "LOCATION=" + (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED)
                + "; service=" + locationOk();
    }

    @Override
    public void onRequestPermissionsResult(int req, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(req, permissions, grantResults);
        if (req != REQ_BLE) return;

        logEvent("PERM", "result " + permissionsSnapshot());

        if (permissionsOk()) {
            ui.postDelayed(this::beginConnect, 150);
        } else {
            setStatus("Разрешение Bluetooth отклонено");
        }
    }

    private void logEvent(String tag, String msg) {
        String line = new SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(new Date())
                + " [" + tag + "] " + msg + "\n";

        synchronized (logLock) {
            sessionLog.append(line);
            if (sessionLog.length() > 90000) {
                sessionLog.delete(0, sessionLog.length() - 70000);
            }

            try (FileOutputStream out = new FileOutputStream(logFile, true)) {
                out.write(line.getBytes(StandardCharsets.UTF_8));
            } catch (Exception ignored) { }
        }

        if (logPreview != null) {
            String preview;
            synchronized (logLock) {
                int from = Math.max(0, sessionLog.length() - 7000);
                preview = sessionLog.substring(from);
            }
            logPreview.setText(preview);
        }
    }

    private String readLogForShare() {
        synchronized (logLock) {
            StringBuilder all = new StringBuilder();
            all.append("IERIKHON APP ").append(APP_VERSION)
                    .append(" / EXPECTED FW ").append(FW_VERSION)
                    .append(" / STARTER ").append(pulseMs).append("ms\n\n");
            all.append(sessionLog);

            if (logFile != null && logFile.isFile()) {
                try (FileInputStream in = new FileInputStream(logFile)) {
                    byte[] data = new byte[(int)Math.min(logFile.length(), 180000)];
                    int read = in.read(data);
                    if (read > 0) {
                        String persisted = new String(data, 0, read, StandardCharsets.UTF_8);
                        if (!persisted.endsWith(sessionLog.toString())) {
                            all.append("\n--- persisted ---\n").append(persisted);
                        }
                    }
                } catch (Exception ignored) { }
            }
            return all.toString();
        }
    }

    private void shareLog() {
        String text = readLogForShare();
        Intent send = new Intent(Intent.ACTION_SEND);
        send.setType("text/plain");
        send.putExtra(Intent.EXTRA_SUBJECT, "IERIKHON debug " + APP_VERSION);
        send.putExtra(Intent.EXTRA_TEXT, text);

        try {
            startActivity(Intent.createChooser(send, "Отправить лог IERIKHON"));
        } catch (Exception e) {
            copyLog();
        }
    }

    private void copyLog() {
        ClipboardManager cm = (ClipboardManager)getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm != null) {
            cm.setPrimaryClip(ClipData.newPlainText("IERIKHON debug", readLogForShare()));
            Toast.makeText(this, "Лог скопирован", Toast.LENGTH_SHORT).show();
        }
    }

    private LinearLayout card() {
        LinearLayout v = new LinearLayout(this);
        v.setOrientation(LinearLayout.VERTICAL);
        v.setPadding(dp(16), dp(15), dp(16), dp(15));
        v.setBackground(round(Color.rgb(12, 18, 23), Color.rgb(34, 46, 54), 18, 1));
        return v;
    }

    private Button btn(String text) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setTextColor(Color.WHITE);
        b.setTextSize(13);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        b.setBackground(round(Color.rgb(18, 28, 34), Color.rgb(46, 63, 72), 12, 1));
        return b;
    }

    private TextView txt(String text, float size, int color, boolean bold) {
        TextView v = new TextView(this);
        v.setText(text);
        v.setTextSize(size);
        v.setTextColor(color);
        if (bold) v.setTypeface(Typeface.DEFAULT_BOLD);
        return v;
    }

    private GradientDrawable round(int fill, int stroke, int radiusDp, int strokeDp) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(fill);
        d.setCornerRadius(dp(radiusDp));
        d.setStroke(dp(strokeDp), stroke);
        return d;
    }

    private LinearLayout.LayoutParams top(int dp) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        p.topMargin = dp(dp);
        return p;
    }

    private Space space(int h) {
        Space s = new Space(this);
        s.setLayoutParams(new LinearLayout.LayoutParams(1, dp(h)));
        return s;
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    @Override
    protected void onDestroy() {
        manualDisconnect = true;
        stopScan();
        closeGatt();
        super.onDestroy();
    }
}
