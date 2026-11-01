from pathlib import Path
import re

p = Path("app/src/main/java/com/sdrx/ierikhon/MainActivityV152.java")
s = p.read_text(encoding="utf-8")

def replace_once(old, new, label):
    global s
    count = s.count(old)
    if count != 1:
        raise SystemExit(f"{label}: expected 1 match, got {count}")
    s = s.replace(old, new, 1)

replace_once(
    'private static final String APP_VERSION = "1.5.2";',
    'private static final String APP_VERSION = "1.5.4";',
    "version"
)
replace_once(
    'getWindow().setStatusBarColor(Color.rgb(6, 9, 12));',
    'getWindow().setStatusBarColor(Color.rgb(7, 10, 14));',
    "status bar"
)
replace_once(
    'getWindow().setNavigationBarColor(Color.rgb(6, 9, 12));',
    'getWindow().setNavigationBarColor(Color.rgb(7, 10, 14));',
    "navigation bar"
)

field_anchor = '''    private int opSerial;
'''
field_insert = '''    private int opSerial;

    private final Runnable pulseSyncDebounce = () -> {
        if (verified && !starting && !startSent) syncPulseToEsp();
    };
'''
replace_once(field_anchor, field_insert, "pulse debounce field")

new_make_ui = r'''    private View makeUi() {
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

        TextView foot = txt("APP v" + APP_VERSION + " • FW v" + FW_VERSION + " • GPIO25", 9, Color.rgb(62, 72, 81), false);
        foot.setGravity(Gravity.CENTER);
        root.addView(foot, top(12));

        return scroll;
    }'''

pattern = re.compile(r'    private View makeUi\(\) \{.*?\n    \}\n\n(?=    private void changePulse)', re.S)
s, count = pattern.subn(new_make_ui + "\n\n", s, count=1)
if count != 1:
    raise SystemExit(f"makeUi replace: expected 1, got {count}")

new_change_pulse = r'''    private void changePulse(int delta) {
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
    }'''
pattern = re.compile(r'    private void changePulse\(int delta\) \{.*?\n    \}\n\n(?=    private void renderPulse)', re.S)
s, count = pattern.subn(new_change_pulse + "\n\n", s, count=1)
if count != 1:
    raise SystemExit(f"changePulse replace: expected 1, got {count}")

new_sync = r'''    private void syncPulseToEsp() {
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
    }'''
pattern = re.compile(r'    private void syncPulseToEsp\(\) \{.*?\n    \}\n\n(?=    private static int clampPulse)', re.S)
s, count = pattern.subn(new_sync + "\n\n", s, count=1)
if count != 1:
    raise SystemExit(f"syncPulseToEsp replace: expected 1, got {count}")

old_mismatch = '''                if (ms != pulseMs) {
                    logEvent("PULSE", "ESP returned " + ms + "ms, app wants " + pulseMs + "ms");
                    queueWrite("SET_PULSE:" + pulseMs);
                    return;
                }
'''
new_mismatch = '''                if (ms != pulseMs) {
                    logEvent("PULSE", "stale reply " + ms + "ms; latest=" + pulseMs + "ms");
                    pulseConfirmed = false;
                    renderPulse();
                    ui.removeCallbacks(pulseSyncDebounce);
                    ui.postDelayed(pulseSyncDebounce, 220);
                    return;
                }
'''
replace_once(old_mismatch, new_mismatch, "stale pulse reply handling")

old_write_cb = '''                logEvent("WRITE", "status=" + statusCode + " cmd=" + (currentOp == null ? "?" : currentOp.command));
                finishOp();
                if (canRead) ui.postDelayed(MainActivityV152.this::queueRead, 220);
'''
new_write_cb = '''                String completedCommand = currentOp == null ? "" : currentOp.command;
                logEvent("WRITE", "status=" + statusCode + " cmd=" + (completedCommand.isEmpty() ? "?" : completedCommand));
                finishOp();
                if (canRead && !completedCommand.startsWith("SET_PULSE:")) {
                    ui.postDelayed(MainActivityV152.this::queueRead, 220);
                }
'''
replace_once(old_write_cb, new_write_cb, "write callback")

s = s.replace('conn.setText("●  НЕ ПОДКЛЮЧЕНО");', 'conn.setText("● OFFLINE");')
s = s.replace('conn.setText("●  ПОИСК…");', 'conn.setText("● SEARCH");')
s = s.replace('conn.setText("●  ПОДКЛЮЧЕНИЕ…");', 'conn.setText("● CONNECT");')
s = s.replace('conn.setText("●  СОЕДИНЕНО");', 'conn.setText("● ONLINE");')
s = s.replace('conn.setText("●  ПРОВЕРКА ПРОШИВКИ…");', 'conn.setText("● CHECK");')
s = s.replace('conn.setText("●  ГОТОВО");', 'conn.setText("● ONLINE");')

s = s.replace('if (dev != null) dev.setText("Устройство: " + NAME);', 'if (dev != null) dev.setText(NAME);')
s = s.replace('dev.setText("Устройство: " + NAME + " • GATT");', 'dev.setText(NAME + " • GATT");')

s = s.replace(
    'v.setBackground(round(Color.rgb(12, 18, 23), Color.rgb(34, 46, 54), 18, 1));',
    'v.setBackground(round(Color.rgb(10, 13, 17), Color.rgb(77, 25, 38), 22, 1));'
)
s = s.replace(
    'b.setBackground(round(Color.rgb(18, 28, 34), Color.rgb(46, 63, 72), 12, 1));',
    'b.setBackground(round(Color.rgb(16, 13, 17), Color.rgb(125, 31, 51), 18, 1));'
)

replace_once(
    '''                start.setEnabled(verified);
                pulseState.setText("ESP32 подтверждено: " + formatPulse(pulseMs));''',
    '''                start.setEnabled(verified);
                start.setTextColor(Color.WHITE);
                pulseState.setText("ГОТОВО • " + formatPulse(pulseMs));''',
    "confirmed pulse UI"
)

replace_once(
    '''        ui.removeCallbacks(firmwareTimeout);
        resetStart();''',
    '''        ui.removeCallbacks(firmwareTimeout);
        ui.removeCallbacks(pulseSyncDebounce);
        resetStart();''',
    "closeGatt pulse debounce cleanup"
)

p.write_text(s, encoding="utf-8")
print("IERIKHON_V154_UI_AND_PULSE_FIX=PASS")
