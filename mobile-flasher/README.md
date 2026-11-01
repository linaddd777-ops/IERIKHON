# IERIKHON Mobile Flasher

Android USB-OTG one-tap flasher for the existing IERIKHON ESP32 firmware v1.5.4.

- Does not modify the main IERIKHON Android app.
- Embeds the exact merged ESP32 image at flash offset `0x0`.
- Uses USB OTG with CP210x/CH340/FTDI/CDC-class serial adapters supported by usb-serial-for-android.
- Tries automatic DTR/RTS bootloader entry first and falls back to a manual BOOT + EN/RESET flow when required by clone boards.
- The build asserts the embedded firmware SHA-256 before packaging the APK.
