from pathlib import Path

root = Path('/tmp/esp-flash-android')
html_path = root / 'app/src/main/assets/flash.html'
build_path = root / 'app/build.gradle'
root_build_path = root / 'build.gradle'

s = html_path.read_text(encoding='utf-8')

# IERIKHON visual language: black + restrained red, one-tap embedded firmware.
s = s.replace('#2196f3', '#d72b4a')
s = s.replace('#1976d2', '#d72b4a')
s = s.replace('#121212', '#070a0e')
s = s.replace('#1e1e1e', '#0f1419')
s = s.replace('#262626', '#151b21')
s = s.replace('#383838', '#303942')
s = s.replace('<title>ESP Flash</title>', '<title>IERIKHON FLASHER</title>')

# The firmware is baked into the APK. Keep dangerous generic erase hidden.
s = s.replace('<button class="danger" id="eraseBtn"></button>', '<button class="danger" id="eraseBtn" style="display:none"></button>')

# Clear Russian one-tap instructions. Auto reset first; manual BOOT only if needed.
s = s.replace(
    "instructionsEmbedded: '<b>1.</b> Подключите плату к телефону по USB OTG<br>' +\n                          '<b>2.</b> Зажмите BOOT, нажмите RESET, отпустите BOOT<br>' +\n                          '<b>3.</b> Нажмите «Прошить»',",
    "instructionsEmbedded: '<b>1.</b> Подключи ESP32 к телефону через USB‑OTG<br>' +\n                          '<b>2.</b> Разреши доступ к USB<br>' +\n                          '<b>3.</b> Нажми «ПРОШИТЬ ESP32»<br><br>' +\n                          'Обычно BOOT/EN трогать не нужно. Если автозагрузка не сработает — приложение само покажет, что нажать.',"
)
s = s.replace("flash: 'Прошить',", "flash: 'ПРОШИТЬ ESP32',")
s = s.replace("done: 'Готово!',", "done: 'ГОТОВО • FW 1.5.4',")
s = s.replace("success: 'Прошивка завершена успешно',", "success: 'ГОТОВО: IERIKHON FW 1.5.4 записана в ESP32',")
s = s.replace("afterFlash: 'Отключите USB и перезагрузите плату.',", "afterFlash: 'ESP32 можно отключить от телефона. После перезагрузки BLE‑имя: IERIKHON-01.',")
s = s.replace("bootHint: 'Зажмите BOOT, нажмите RESET, отпустите BOOT и повторите.',", "bootHint: 'Если автозагрузка не сработает: зажми BOOT → коротко EN/RESET → отпусти BOOT → нажми ПРОШИТЬ ещё раз.',")
s = s.replace("usbOpen: 'USB-порт открыт',", "usbOpen: 'USB‑порт ESP32 открыт',")

# Normal ESP32 DevKit boards with CP210x/CH340 support automatic DTR/RTS reset.
# If a clone does not, remember it and make the next button press use no_reset,
# so the user can do BOOT+EN manually without our reset sequence undoing it.
old = '''async function openChip() {
  try {
    return await connectLoader('no_reset', 2);
  } catch (e) {
    log(t().resetFailed(e.message), 'err');
    log(t().retryWithReset, 'info');
    try { Android.disconnect(); } catch(_) {}
    await new Promise(r => setTimeout(r, 800));
    return await connectLoader('default_reset', 3);
  }
}'''
new = '''let manualBootNext = false;
async function openChip() {
  if (manualBootNext) {
    try {
      const result = await connectLoader('no_reset', 3);
      manualBootNext = false;
      return result;
    } catch (e) {
      manualBootNext = true;
      try { Android.disconnect(); } catch(_) {}
      throw new Error('ESP32 не в режиме прошивки. Зажми BOOT → коротко нажми EN/RESET → отпусти BOOT → нажми ПРОШИТЬ ещё раз. ' + e.message);
    }
  }

  try {
    return await connectLoader('default_reset', 4);
  } catch (e) {
    manualBootNext = true;
    try { Android.disconnect(); } catch(_) {}
    throw new Error('Автовход в загрузчик не сработал. Зажми BOOT → коротко нажми EN/RESET → отпусти BOOT → нажми ПРОШИТЬ ещё раз. ' + e.message);
  }
}'''
if old not in s:
    raise SystemExit('openChip block not found in upstream flash.html')
s = s.replace(old, new, 1)

# Do not scare the user with the BOOT hint before an automatic attempt.
s = s.replace("    log(t().bootHint, 'info');   // до попытки: чаще всего дело именно в этом\n    session = await openChip();",
              "    session = await openChip();")

# Make the baked build visibly IERIKHON even before firmware.json is read.
s = s.replace("<h1 id=\"title\">ESP Flash</h1>", "<h1 id=\"title\">IERIKHON FLASHER</h1>")

for required in [
    'manualBootNext',
    "connectLoader('default_reset', 4)",
    "connectLoader('no_reset', 3)",
    'ПРОШИТЬ ESP32',
    'IERIKHON FW 1.5.4',
    'IERIKHON-01',
    'style="display:none"'
]:
    if required not in s:
        raise SystemExit(f'missing patched token: {required}')

html_path.write_text(s, encoding='utf-8')

# Keep the upstream-pinned USB serial dependency. Newer 3.11.x pulls Kotlin 2.x metadata,
# which is incompatible with this pinned Kotlin 1.9.22 project.
# Keep the requested applicationId unchanged for the debug build instead of adding '.debug'.
b = build_path.read_text(encoding='utf-8')
b = b.replace("            applicationIdSuffix '.debug'\n", "")
if "applicationIdSuffix '.debug'" in b:
    raise SystemExit('debug applicationId suffix was not removed')
build_path.write_text(b, encoding='utf-8')

# Upstream pins Kotlin 1.9.22, but current AndroidX resolution pulls Kotlin 2.2 metadata.
# Compile with the matching Kotlin compiler instead of suppressing metadata checks.
rb = root_build_path.read_text(encoding='utf-8')
rb2 = rb.replace("id 'org.jetbrains.kotlin.android' version '1.9.22' apply false",
                 "id 'org.jetbrains.kotlin.android' version '2.2.10' apply false")
if rb2 == rb:
    raise SystemExit('Kotlin plugin pin not found in upstream root build.gradle')
root_build_path.write_text(rb2, encoding='utf-8')

print('IERIKHON_MOBILE_FLASHER_PATCH=PASS')
