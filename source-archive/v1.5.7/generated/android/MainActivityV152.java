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
    private static final String APP_VERSION = "1.5.7";
    private static final String FW_VERSION = "1.5.4";
    private static final String NAME = "IERIKHON-01";
    
    private static final UUID SERVICE = UUID.fromString("6f4f0001-8f3a-4d7a-9c12-4a494552494b");
    private static final UUID COMMAND = UUID.fromString("6f4f0002-8f3a-4d7a-9c12-4a494552494b");
    private static final UUID CCCD = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");

    private static final int REQ_BLE = 71;
    private static final long HOLD_MS = 1200L;
    private static final int MAX_RECONNECTS = 4;

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
        int busyRetries;
        Op(int type, String command) {
            this.type = type;
            this.command = command;
        }
    }

    private final ArrayDeque<Op> ops = new ArrayDeque<>();
    private boolean opBusy;
    private Op currentOp;
    private int opSerial;

    private final Runnable pulseSyncDebounce = () -> {
        if (verified && !starting && !startSent) syncPulseToEsp();
    };

    private final Runnable scanTimeout = () -> {
        if (!scanning) return;
        logEvent("TIMEOUT", "scan 8s; seen=" + seen);
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

        getWindow().setStatusBarColor(Color.rgb(7, 10, 14));
        getWindow().setNavigationBarColor(Color.rgb(7, 10, 14));
        setContentView(makeUi());
        renderPulse();
        disconnectedUi();

        logEvent("APP", "===== NEW SESSION =====");
        logEvent("APP", "app=" + APP_VERSION + " pairedFw=" + FW_VERSION + " starterPulse=" + pulseMs);
        logEvent("PHONE", Build.MANUFACTURER + " " + Build.MODEL + "; Android=" + Build.VERSION.RELEASE + "; SDK=" + Build.VERSION.SDK_INT);
        logEvent("PERM", permissionsSnapshot());
        setStatus("Готов к подключению");
    }

    private View makeUi() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(Color.rgb(7, 10, 14));

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(18), dp(16), dp(18), dp(24));
        root.setBackgroundColor(Color.rgb(7, 10, 14));
        scroll.addView(root);

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);

        LinearLayout brand = new LinearLayout(this);
        brand.setOrientation(LinearLayout.VERTICAL);
        brand.addView(txt("IERIKHON", 29, Color.WHITE, true));
        brand.addView(txt("REMOTE START", 10, Color.rgb(126, 139, 149), true), top(1));

        LinearLayout.LayoutParams brandLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        header.addView(brand, brandLp);

        conn = txt("● OFFLINE", 11, Color.rgb(225, 55, 82), true);
        conn.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        header.addView(conn, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(header);

        status = txt("Готов к подключению", 16, Color.rgb(90, 225, 160), true);
        status.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams statusLp = top(20);
        root.addView(status, statusLp);

        hint = txt("Подключи ESP32", 11, Color.rgb(118, 132, 143), false);
        hint.setGravity(Gravity.CENTER);
        root.addView(hint, top(4));

        start = new Button(this);
        start.setText("ЗАВЕСТИ");
        start.setTextSize(24);
        start.setTextColor(Color.WHITE);
        start.setTypeface(Typeface.DEFAULT_BOLD);
        start.setAllCaps(false);
        start.setEnabled(false);
        start.setBackground(round(Color.rgb(9, 13, 18), Color.rgb(215, 43, 74), 28, 2));
        start.setOnTouchListener((v, e) -> {
            int action = e.getActionMasked();
            if (action == MotionEvent.ACTION_DOWN) {
                logEvent("UI", "START DOWN; verified=" + verified + " pulseConfirmed=" + pulseConfirmed + " pulse=" + pulseMs);
                if (!verified) {
                    setStatus("Сначала дождись «ГОТОВО»");
                    return true;
                }
                if (!pulseConfirmed) {
                    setStatus("Жду подтверждение времени стартера");
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
                        hint.setText("Удерживай 1.2 секунды");
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

        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(156));
        sp.topMargin = dp(18);
        root.addView(start, sp);

        LinearLayout info = card();
        LinearLayout deviceRow = new LinearLayout(this);
        deviceRow.setOrientation(LinearLayout.HORIZONTAL);
        deviceRow.setGravity(Gravity.CENTER_VERTICAL);
        TextView espLabel = txt("ESP32", 10, Color.rgb(112, 126, 138), true);
        deviceRow.addView(espLabel, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        dev = txt(NAME, 12, Color.WHITE, true);
        dev.setGravity(Gravity.END);
        deviceRow.addView(dev, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        info.addView(deviceRow);

        LinearLayout pulseRow = new LinearLayout(this);
        pulseRow.setOrientation(LinearLayout.HORIZONTAL);
        pulseRow.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams pulseRowLp = top(12);

        TextView starterLabel = txt("СТАРТЕР", 10, Color.rgb(112, 126, 138), true);
        pulseRow.addView(starterLabel, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        LinearLayout compact = new LinearLayout(this);
        compact.setOrientation(LinearLayout.HORIZONTAL);
        compact.setGravity(Gravity.CENTER_VERTICAL);

        pulseMinus = btn("−");
        pulseMinus.setTextSize(20);
        pulseMinus.setMinWidth(dp(42));
        pulseMinus.setMinimumWidth(dp(42));
        pulseMinus.setMinHeight(dp(38));
        pulseMinus.setMinimumHeight(dp(38));
        pulseMinus.setPadding(0, 0, 0, 0);

        pulseValue = txt("", 16, Color.WHITE, true);
        pulseValue.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams valueLp = new LinearLayout.LayoutParams(dp(74), dp(38));

        pulsePlus = btn("+");
        pulsePlus.setTextSize(20);
        pulsePlus.setMinWidth(dp(42));
        pulsePlus.setMinimumWidth(dp(42));
        pulsePlus.setMinHeight(dp(38));
        pulsePlus.setMinimumHeight(dp(38));
        pulsePlus.setPadding(0, 0, 0, 0);

        compact.addView(pulseMinus, new LinearLayout.LayoutParams(dp(42), dp(38)));
        compact.addView(pulseValue, valueLp);
        compact.addView(pulsePlus, new LinearLayout.LayoutParams(dp(42), dp(38)));
        pulseRow.addView(compact, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        pulseMinus.setOnClickListener(v -> changePulse(-PULSE_STEP_MS));
        pulsePlus.setOnClickListener(v -> changePulse(PULSE_STEP_MS));

        info.addView(pulseRow, pulseRowLp);

        pulseState = txt("0.5–3.0 с • шаг 0.1 с", 9, Color.rgb(110, 121, 131), false);
        pulseState.setGravity(Gravity.END);
        info.addView(pulseState, top(5));
        root.addView(info, top(14));

        connect = btn("ПОДКЛЮЧИТЬ");
        connect.setTextSize(12);
        connect.setMinHeight(dp(48));
        connect.setBackground(round(Color.rgb(13, 14, 19), Color.rgb(145, 35, 57), 22, 1));
        connect.setOnClickListener(v -> {
            logEvent("UI", "CONNECT click; scanning=" + scanning + " connected=" + connected + " serviceReady=" + serviceReady);
            if (scanning || connected || serviceReady) disconnect();
            else beginConnect();
        });
        root.addView(connect, top(12));

        proto = txt("ESP32: —", 10, Color.rgb(91, 103, 113), false);
        proto.setGravity(Gravity.CENTER);
        root.addView(proto, top(12));

        LinearLayout logRow = new LinearLayout(this);
        logRow.setOrientation(LinearLayout.HORIZONTAL);
        logRow.setGravity(Gravity.CENTER);

        Button share = btn("ОТПРАВИТЬ ЛОГ");
        share.setTextSize(10);
        share.setOnClickListener(v -> shareLog());

        Button copy = btn("СКОПИРОВАТЬ");
        copy.setTextSize(10);
        copy.setOnClickListener(v -> copyLog());

        LinearLayout.LayoutParams logBtnLp = new LinearLayout.LayoutParams(0, dp(42), 1f);
        logRow.addView(share, logBtnLp);
        Space logGap = new Space(this);
        logRow.addView(logGap, new LinearLayout.LayoutParams(dp(8), 1));
        logRow.addView(copy, new LinearLayout.LayoutParams(0, dp(42), 1f));
        root.addView(logRow, top(10));

        TextView foot = txt("APP v" + APP_VERSION + " • BLE • GPIO25", 9, Color.rgb(62, 72, 81), false);
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
            ui.removeCallbacks(pulseSyncDebounce);
            ui.postDelayed(pulseSyncDebounce, 380);
            setStatus("Стартер: " + formatPulse(pulseMs));
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
        if (!verified || ch == null || starting || startSent) return;

        ui.removeCallbacks(pulseSyncDebounce);
        dropQueuedPulseOps();

        pulseConfirmed = false;
        renderPulse();

        final int wanted = pulseMs;
        String cmd = "SET_PULSE:" + wanted;
        logEvent("PULSE", "sync latest -> " + cmd);
        queueWrite(cmd);

        ui.postDelayed(() -> {
            if (verified && !pulseConfirmed && pulseMs == wanted && !starting && !startSent) {
                queueWrite("GET_PULSE");
                setStatus("Проверяю " + formatPulse(wanted) + "…");
            }
        }, 850);
    }

    private void dropQueuedPulseOps() {
        if (ops.isEmpty()) return;
        ArrayDeque<Op> keep = new ArrayDeque<>();
        while (!ops.isEmpty()) {
            Op op = ops.poll();
            boolean pulseOp = op.type == OP_READ
                    || "GET_PULSE".equals(op.command)
                    || (op.command != null && op.command.startsWith("SET_PULSE:"));
            if (!pulseOp) keep.add(op);
        }
        ops.addAll(keep);
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
        conn.setText("● SEARCH");
        conn.setTextColor(Color.rgb(255, 185, 92));
        connect.setText("ОТМЕНА");
        dev.setText("Ищу имя и UUID сервиса…");
        setStatus("Ищу IERIKHON-01…");
        logEvent("SCAN", "start LOW_LATENCY; target=" + NAME + "; service=" + SERVICE);

        try {
            scanner.startScan(null,
                    new ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(),
                    scanCb);
            ui.removeCallbacks(scanTimeout);
            ui.postDelayed(scanTimeout, 8000);
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

        conn.setText("● CONNECT");
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
                    conn.setText("● ONLINE");
                    conn.setTextColor(Color.rgb(61, 225, 191));
                    connect.setText("ОТКЛЮЧИТЬ");
                    dev.setText(NAME + " • GATT");
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
            serviceReady = false;
            canRead = false;
            int p = cc.getProperties();

            logEvent("GATT", "command properties=0x" + Integer.toHexString(p) + "; canRead=" + canRead);

            boolean hasWrite = (p & BluetoothGattCharacteristic.PROPERTY_WRITE) != 0;
            boolean hasNotify = (p & BluetoothGattCharacteristic.PROPERTY_NOTIFY) != 0;
            if (!hasWrite || !hasNotify) {
                runOnUiThread(() -> reconnect("BLE-канал несовместим"));
                return;
            }

            boolean waitingForDescriptor = false;
            try {
                boolean notifyLocal = gg.setCharacteristicNotification(cc, true);
                logEvent("GATT", "setCharacteristicNotification=" + notifyLocal);

                BluetoothGattDescriptor desc = cc.getDescriptor(CCCD);
                if (desc != null && notifyLocal) {
                    if (Build.VERSION.SDK_INT >= 33) {
                        int result = gg.writeDescriptor(desc, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
                        logEvent("GATT", "writeDescriptor api33 result=" + result);
                        waitingForDescriptor = result == BluetoothStatusCodes.SUCCESS;
                    } else {
                        desc.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
                        boolean result = gg.writeDescriptor(desc);
                        logEvent("GATT", "writeDescriptor legacy=" + result);
                        waitingForDescriptor = result;
                    }
                } else {
                    logEvent("GATT", "CCCD/notify unavailable; READ fallback");
                }
            } catch (Exception ex) {
                logEvent("GATT", "notify setup exception=" + ex.getClass().getSimpleName());
            }

            final boolean descriptorPending = waitingForDescriptor;
            runOnUiThread(() -> {
                ui.removeCallbacks(serviceTimeout);
                if (!descriptorPending) {
                    reconnect("не удалось включить BLE-уведомления");
                } else {
                    setStatus("Подключаю канал управления…");
                }
            });
        }

        @Override
        public void onDescriptorWrite(BluetoothGatt gg, BluetoothGattDescriptor descriptor, int statusCode) {
            logEvent("GATT", "descriptorWrite status=" + statusCode);
            runOnUiThread(() -> {
                if (statusCode == BluetoothGatt.GATT_SUCCESS) markGattReady("notify ready");
                else reconnect("ошибка включения BLE-уведомлений: " + statusCode);
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
                String completedCommand = currentOp == null ? "" : currentOp.command;
                logEvent("WRITE", "status=" + statusCode + " cmd=" + (completedCommand.isEmpty() ? "?" : completedCommand));
                finishOp();
                if (canRead && !completedCommand.startsWith("SET_PULSE:")) {
                    ui.postDelayed(MainActivityV152.this::queueRead, 220);
                }
            });
        }
    };

    private void rx(String text) {
        runOnUiThread(() -> handleRx(text));
    }

    private void markGattReady(String detail) {
        if (!connected || gatt == null || ch == null || serviceReady) return;

        serviceReady = true;
        verified = true;
        pulseConfirmed = false;
        ui.removeCallbacks(serviceTimeout);

        conn.setText("● ONLINE");
        conn.setTextColor(Color.rgb(90, 225, 160));
        connect.setText("ОТКЛЮЧИТЬ");
        dev.setText(NAME);
        proto.setText("ESP32: ONLINE");
        hint.setText("Удерживай ЗАВЕСТИ 1.2 секунды");
        setStatus("ГОТОВО");
        logEvent("GATT", "ready; " + detail);

        start.setEnabled(false);
        ui.postDelayed(this::syncPulseToEsp, 120);
    }

    private void handleRx(String raw) {
        String text = raw == null ? "" : raw.trim();
        if (text.isEmpty()) return;

        logEvent("RX", text);

        if (text.startsWith("PULSE_OK:") || text.startsWith("PULSE:")) {
            Integer ms = parseTrailingInt(text);
            if (ms != null) {
                if (ms != pulseMs) {
                    logEvent("PULSE", "stale reply " + ms + "ms; latest=" + pulseMs + "ms");
                    pulseConfirmed = false;
                    renderPulse();
                    ui.removeCallbacks(pulseSyncDebounce);
                    ui.postDelayed(pulseSyncDebounce, 220);
                    return;
                }
                pulseConfirmed = true;
                renderPulse();
                start.setEnabled(verified);
                start.setTextColor(Color.WHITE);
                pulseState.setText("ГОТОВО • " + formatPulse(pulseMs));
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
                if (result == BluetoothStatusCodes.ERROR_GATT_WRITE_REQUEST_BUSY) {
                    Op retry = currentOp;
                    int attempt = retry == null ? 99 : ++retry.busyRetries;
                    logEvent("WRITE", "GATT busy retry=" + attempt + " cmd=" + cmd);
                    opBusy = false;
                    currentOp = null;
                    if (retry != null && attempt <= 6) {
                        ops.addFirst(retry);
                        ui.postDelayed(this::pumpOps, 100L + attempt * 70L);
                    } else {
                        setStatus("BLE занят. Повтори команду.");
                    }
                    return;
                }
                if (result != BluetoothStatusCodes.SUCCESS) {
                    logEvent("WRITE", "rejected result=" + result + " cmd=" + cmd);
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
        ui.postDelayed(() -> connectGatt(target), 900);
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
        ui.removeCallbacks(pulseSyncDebounce);
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
            conn.setText("● OFFLINE");
            conn.setTextColor(Color.rgb(255, 98, 98));
        }
        if (dev != null) dev.setText(NAME);
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
        v.setBackground(round(Color.rgb(10, 13, 17), Color.rgb(77, 25, 38), 22, 1));
        return v;
    }

    private Button btn(String text) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setTextColor(Color.WHITE);
        b.setTextSize(13);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        b.setBackground(round(Color.rgb(16, 13, 17), Color.rgb(125, 31, 51), 18, 1));
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
