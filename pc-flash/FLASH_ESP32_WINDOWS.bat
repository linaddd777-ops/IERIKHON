@echo off
chcp 65001 >nul
setlocal EnableExtensions
cd /d "%~dp0"

title IERIKHON ESP32 v1.5.4 - Windows Flasher

set "BIN=IERIKHON-ESP32-v1.5.4-FULL.bin"

if not exist "%BIN%" (
  echo [ERROR] Не найден файл %BIN%
  echo Держи BAT и BIN в одной папке.
  pause
  exit /b 1
)

echo.
echo ================================================
echo   IERIKHON ESP32 v1.5.4 - прошивка с Windows
echo ================================================
echo.
echo Найденные COM-порты:
powershell -NoProfile -Command "Get-CimInstance Win32_SerialPort ^| Select-Object DeviceID,Name ^| Format-Table -AutoSize" 2>nul
echo.
set /p PORT=Введи COM-порт ESP32, например COM5: 
if "%PORT%"=="" (
  echo [ERROR] COM-порт не указан.
  pause
  exit /b 1
)

set "PY="
where py >nul 2>&1
if not errorlevel 1 set "PY=py -3"
if not defined PY (
  where python >nul 2>&1
  if not errorlevel 1 set "PY=python"
)

if not defined PY (
  echo.
  echo [ERROR] На ПК не найден Python.
  echo Используй IERIKHON-FLASHER-v1.0.4.exe.
  pause
  exit /b 1
)

echo.
echo [1/3] Проверяю esptool...
%PY% -m esptool version >nul 2>&1
if errorlevel 1 (
  echo [2/3] Ставлю esptool 4.8.1...
  %PY% -m pip install --user "esptool==4.8.1"
  if errorlevel 1 (
    echo [ERROR] Не удалось установить esptool.
    pause
    exit /b 1
  )
) else (
  echo [2/3] esptool уже установлен.
)

echo.
echo [3/3] Прошиваю %PORT%...
%PY% -m esptool --chip esp32 --port "%PORT%" --baud 460800 --before default_reset --after hard_reset --connect-attempts 10 write_flash 0x0 "%BIN%"
if errorlevel 1 (
  echo.
  echo [ERROR] Прошивка не завершилась.
  echo Проверь COM-порт и USB data-кабель.
  pause
  exit /b 1
)

echo.
echo ================================================
echo   ГОТОВО. ESP32 прошита IERIKHON FW v1.5.4
echo ================================================
echo BLE-имя: IERIKHON-01.
echo Используй приложение IERIKHON v1.5.7.
echo.
pause
