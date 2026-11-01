# Flasher v1.0.4: что внутри и как собирать

Это обычное **Python-приложение с интерфейсом Tkinter**, собранное в EXE при помощи PyInstaller. Сохранились **оба авторских файла**:

- `pc-flasher/ierikhon_flasher.py` — окно, выбор COM-порта, журнал операций, вызовы esptool.
- `pc-flasher/ierikhon_flasher_v104.py` — входная точка 1.0.4. Устанавливает `APP_VERSION = "1.0.4"`, `FW_VERSION = "1.5.4"`, `BIN_NAME = "IERIKHON-ESP32-v1.5.4-FULL.bin"`.

В присланном EXE найдены Python 3.12, контейнер PyInstaller и **полный образ ESP32 на 4 МБ**. Код Python из GitHub — авторский исходник, а не приблизительная реконструкция из PYC. Однако точное соответствие всем байтам старого EXE без повторяемой сборки не доказано.

## Сборка на Windows

Нужны **Python 3.12**, образ `IERIKHON-ESP32-v1.5.4-FULL.bin`, входные Python-файлы и зависимости. Историческая настройка сохранена в [GitHub Actions](../.github/workflows/build-windows-flasher.yml).

```powershell
py -3.12 -m pip install "esptool==4.8.1" "pyserial==3.5" "pyinstaller==6.16.0"
```

Поместите **проверенный BIN** в `pc-flasher/`, затем из корня репозитория:

```powershell
py -3.12 -m PyInstaller --noconfirm --clean --onefile --windowed --name "IERIKHON-FLASHER-v1.0.4" --paths "pc-flasher" --collect-all esptool --collect-all serial --add-data "pc-flasher\IERIKHON-ESP32-v1.5.4-FULL.bin;." pc-flasher\ierikhon_flasher_v104.py
```

Готовый EXE будет в `dist/`. BIN пока **не хранится в GitHub**; он есть в отдельном архиве извлечённых материалов у владельца, его SHA-256 см. [отчёт](PROVENANCE.md).

**Историческая неточность:** базовый Python-файл содержит сообщения «успешно прошита 1.5.2», хотя точка входа v1.0.4 выбирает образ 1.5.4. Не исправлено, чтобы сохранить оригинал. Прошивку на подключённом к мотору устройстве без независимых защит не испытывайте.