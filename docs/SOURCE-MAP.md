# Где лежит каждый файл ИЕРИХОНА

Здесь перечислены **главные файлы с собственным кодом проекта**, а не сторонние библиотеки из EXE.

## 📱 Android v1.5.7

| Ссылка | Что в файле |
|---|---|
| [Готовый объединённый Java v1.5.7](../source-archive/v1.5.7/generated/android/MainActivityV152.java) | **Самое удобное для чтения и отправки ИИ:** весь класс после патчей |
| [Оригинальная Java-основа](../app/src/main/java/com/sdrx/ierikhon/MainActivityV152.java) | Историческая отправная точка; отдельно ещё не 1.5.7 |
| [Первая стадия патча](../tools/v154_patch.py) | Настройка интерфейса и логики для v1.5.4 |
| [Вторая стадия патча](../tools/soft_ui_patch.py) | Изменения UI/BLE для v1.5.6; вызывает предыдущую стадию |
| [Третья стадия патча](../tools/v157_patch.py) | Изменения v1.5.7; вызывает две предыдущие |
| [Более поздний патч 1.5.8](../tools/v158_patch.py) | **Не использовать для 1.5.7** |
| [AndroidManifest](../app/src/main/AndroidManifest.xml) | Bluetooth-разрешения и главный экран |
| [Gradle](../app/build.gradle) | Версия и параметры Android-сборки; текущая база на 1.5.8 |

## 🖥️ Windows Flasher 1.0.4

| Ссылка | Назначение |
|---|---|
| [ierikhon_flasher.py](../pc-flasher/ierikhon_flasher.py) | Основной GUI, COM-порты, esptool, логи |
| [ierikhon_flasher_v104.py](../pc-flasher/ierikhon_flasher_v104.py) | Входной файл 1.0.4: выбирает прошивку ESP32 1.5.4 |
| [Windows workflow](../.github/workflows/build-windows-flasher.yml) | Как EXE собирался на Python 3.12 и PyInstaller |

## 🔌 ESP32

| Ссылка | Назначение |
|---|---|
| [IERIKHON_v1.5.4.ino](../firmware/IERIKHON_v1.5.4/IERIKHON_v1.5.4.ino) | Исходник команд BLE, GPIO25, таймер реле |
| [Сохранённая копия INO](../source-archive/v1.5.7/esp32/IERIKHON_v1.5.4.ino) | Точный Git blob оригинального файла |

## 📦 Для повторяемости и проверки

- [Архив 12 неизменённых исходных файлов](../source-archive/v1.5.7/README.md).
- [Исходник автоматически объединённого Java](../source-archive/v1.5.7/generated/README.md).
- [Скрипт экспорта](../scripts/export_v157_snapshot.py) — работает во временной папке и не изменяет оригиналы.
- [Исходники, APK/EXE и хеши: что доказано](PROVENANCE.md).
- [Как настроить под себя](CUSTOMIZATION.md).
- [Как объяснить всё другому ИИ](AI-HANDOFF.md).

**Границы полноты:** все обнаруженные собственные ключевые исходники Android/Windows/ESP32 сохранены или доступны по ссылкам, но точное побайтовое соответствие повторной сборки историческим APK/EXE не доказано. Извлечённые библиотеки PyInstaller и Android runtime не являются исходниками IERIKHON. Для запуска на реальном мотоцикле действующий код недостаточно защищён — [предупреждение](SECURITY-NOTICE-v1.5.4.md).