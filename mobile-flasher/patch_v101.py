"""IERIKHON Android USB-OTG flasher v1.0.1 hardening.

Runs after mobile-flasher/patch_upstream.py on the pinned upstream source.
Fail-fast replacements deliberately make upstream drift visible during CI.
Only modifies the temporary build checkout, not archived original sources.
"""
from pathlib import Path

root = Path("/tmp/esp-flash-android")
html_file = root / "app/src/main/assets/flash.html"
serial_file = root / "app/src/main/java/io/github/drakosha/espflash/UsbSerialManager.kt"
bridge_file = root / "app/src/main/java/io/github/drakosha/espflash/JsBridge.kt"

EXPECTED_FW_MD5 = "039f5cea4544675339f7f82a91a0fbd8"
EXPECTED_FW_SIZE = 4194304


def replace_once(body: str, old: str, new: str, marker: str) -> str:
    found = body.count(old)
    if found != 1:
        raise SystemExit(f"IERIKHON hardening: {marker} expected 1 match, found {found}")
    return body.replace(old, new, 1)


html = html_file.read_text("utf-8")

# Important: the old JS shim always reported the native ESP32-S3 USB/JTAG
# VID:PID (303a:1001), even on CP210x/CH340 classic ESP32 boards.
# esptool-js uses real USB IDs to pick the right reset strategy.
html = replace_once(
    html,
    "  getInfo() { return { usbVendorId: 0x303A, usbProductId: 0x1001 }; }",
    "  getInfo() { return { usbVendorId: Android.usbVendorId(), usbProductId: Android.usbProductId() }; }",
    "hardware USB vendor/product IDs",
)

# Do not swallow a native USB write exception. Propagate it into the
# esptool-js retry logic instead of timing out waiting for a response.
html = replace_once(
    html,
    "      write: chunk => { Android.write(bytesToB64(chunk)); }",
    """      write: chunk => {
        const reply = Android.write(bytesToB64(chunk));
        if (reply !== 'ok') throw new Error(reply || 'USB write failed');
      }""",
    "USB write error propagation",
)

# Embedded full-chip image is for the original ESP32 (not S2, S3, C3, ESP8266).
# Check both detected chip class and flash capacity before any erase/write.
html = replace_once(
    html,
    """    session = await openChip();
    log(t().chip(session.chip), 'ok');

    const fileArray = loadParts();""",
    """    session = await openChip();
    log(t().chip(session.chip), 'ok');

    if (embedded) {
      const chipName = session.loader.chip && session.loader.chip.CHIP_NAME;
      if (chipName !== 'ESP32') {
        throw new Error('Неподходящий чип: эта прошивка предназначена только для классического ESP32 / ESP32-WROOM (не S2/S3/C3/ESP8266). Определён: ' + (chipName || 'неизвестно'));
      }
      const id = await session.loader.readFlashId();
      const sizeCode = (id >>> 16) & 0xff;
      if (sizeCode < 22 || sizeCode > 30) {
        throw new Error('Не подтверждена флеш-память минимум 4 МБ (JEDEC capacity 0x' + sizeCode.toString(16) + '). Запись отменена.');
      }
      log('ESP32 / flash: ' + Math.pow(2, sizeCode) / 1048576 + ' МБ', 'ok');
    }

    const fileArray = loadParts();""",
    "classic ESP32 + 4MB flash preflight",
)

# Raw flashBlock() does not itself compare the final chip memory with the
# firmware. Use esptool-js flashMd5sum() after writing all 4MB.
html = replace_once(
    html,
    """    setProgress(t().done, 100);
    log(t().success, 'ok');""",
    """    if (embedded) {
      setProgress('Проверка записанной прошивки (MD5)...', 97);
      const observed = await session.loader.flashMd5sum(0, 4194304);
      if (String(observed).toLowerCase() !== '039f5cea4544675339f7f82a91a0fbd8') {
        throw new Error('Проверка MD5 НЕ ПРОЙДЕНА. Запись в ESP32 отличается от исходного образа. Обнаружено: ' + observed);
      }
      log('Проверка флеш-памяти: MD5 совпал', 'ok');
    }

    setProgress(t().done, 100);
    log(t().success, 'ok');""",
    "post-flash MD5 verification",
)

# The complete 4 MB image will replace all on-chip partitions, including NVS.
html = replace_once(
    html,
    """    embedded ? T.instructionsEmbedded : T.instructions;""",
    """    embedded ? (T.instructionsEmbedded + '<br><br><b>ВНИМАНИЕ:</b> полная перезапись 4 МБ памяти ESP32, включая NVS и настройки. Только на отключённой от транспорта плате.') : T.instructions;""",
    "explicit whole-flash warning",
)

html_file.write_text(html, encoding="utf-8")

bridge = bridge_file.read_text("utf-8")
bridge = replace_once(
    bridge,
    """    @JavascriptInterface
    fun write(b64: String) {
        usbManager.write(Base64.decode(b64, Base64.NO_WRAP))
    }""",
    """    @JavascriptInterface
    fun usbVendorId(): Int = usbManager.findDevice()?.vendorId ?: 0

    @JavascriptInterface
    fun usbProductId(): Int = usbManager.findDevice()?.productId ?: 0

    @JavascriptInterface
    fun write(b64: String): String {
        return usbManager.write(Base64.decode(b64, Base64.NO_WRAP))
    }""",
    "native JavaScript bridge for hardware ID and write result",
)
bridge_file.write_text(bridge, encoding="utf-8")

serial = serial_file.read_text("utf-8")
serial = replace_once(
    serial,
    """    fun write(data: ByteArray) {
        try {
            val p = port ?: return
            if (data.size <= WRITE_CHUNK) {
                p.write(data, 2000)
                return
            }
            var off = 0
            while (off < data.size) {
                val n = minOf(WRITE_CHUNK, data.size - off)
                p.write(data.copyOfRange(off, off + n), 2000)
                off += n
                if (WRITE_PACING_MS > 0) Thread.sleep(WRITE_PACING_MS)
            }
        } catch (e: Exception) { Log.e(TAG, "write error", e) }
    }""",
    """    fun write(data: ByteArray): String {
        val p = port ?: return "error: USB port closed"
        return try {
            if (data.size <= WRITE_CHUNK) {
                p.write(data, 2000)
            } else {
                var off = 0
                while (off < data.size) {
                    val n = minOf(WRITE_CHUNK, data.size - off)
                    p.write(data.copyOfRange(off, off + n), 2000)
                    off += n
                    if (WRITE_PACING_MS > 0) Thread.sleep(WRITE_PACING_MS)
                }
            }
            "ok"
        } catch (e: Exception) {
            Log.e(TAG, "write error", e)
            "error: USB write failed: " + (e.message ?: "unknown")
        }
    }""",
    "USB error must propagate to Javascript",
)
serial = replace_once(
    serial,
    """            } catch (e: Exception) {
                if (running) Log.e(TAG, "read error", e)
                break
            }""",
    """            } catch (e: Exception) {
                if (running) {
                    Log.e(TAG, "read error", e)
                    running = false
                    webView.post {
                        webView.evaluateJavascript("window.__usbDisconnected && window.__usbDisconnected()", null)
                    }
                }
                break
            }""",
    "fatal USB read error must close the JS reader",
)
serial_file.write_text(serial, encoding="utf-8")

assert EXPECTED_FW_MD5 in html and str(EXPECTED_FW_SIZE) in html
for required in ("flashMd5sum", "CHIP_NAME", "readFlashId()", "usbVendorId()", "usbProductId()", "USB write failed"):
    if required not in (html + bridge + serial):
        raise SystemExit(f"Missing hardening token: {required}")

print("IERIKHON_MOBILE_FLASHER_HARDENING_V101=PASS")
