# BLE Calling — Patient Call Bridge

Android companion app for an ESP32-S3 Smart Patient Assistant. The ESP32-S3 sends a BLE command such as `CALL:+919876543210`; the app validates it against a user-approved allowlist and can initiate a cellular call using the Android phone's SIM.

> Safety: this is a prototype, not a certified emergency-calling system. Test with your own number first. Android permissions and device policies apply. Do not rely on it as the only emergency route.

## Features
- BLE GATT client scans for a configured ESP32-S3 service and subscribes to its command characteristic.
- Configurable trusted phone-number allowlist (E.164 format, e.g. `+919876543210`).
- Optional auto-call toggle; requires the user to grant `CALL_PHONE` permission.
- Displays connection and last command status.
- GitHub Actions builds a debug APK artifact.

## Command protocol
Send a UTF-8 line via the BLE command characteristic:

`CALL:+919876543210\n`

Only numbers entered in the app's trusted contacts list are accepted. The app ignores malformed and unapproved numbers. Use the service and characteristic UUIDs defined in `app/src/main/java/com/abinsaji/blecalling/BleConfig.kt`; configure firmware to match.

## Build
Open this repository in Android Studio (current stable version), allow Gradle sync, then build `app` → `assembleDebug`.

Or push to GitHub and open **Actions → Build Android APK**. The workflow uploads `app-debug.apk` as an artifact when the build succeeds.

## First run
1. Install the generated APK on an Android phone with a SIM.
2. Open the app and grant nearby-device/Bluetooth permissions.
3. Add your own test number to the trusted list.
4. Connect to the ESP32-S3 BLE device.
5. Enable automatic calling only after testing commands and confirming the displayed number.
6. Send `CALL:<approved-number>` from the ESP32-S3.

The app requires the process to be running and BLE connected. Background execution, lock-screen behavior, and auto-call permissions differ by Android version and device vendor. The example does not guarantee background or unattended emergency calling.

## Hardware
ESP32-S3 supports Bluetooth Low Energy (BLE), not Bluetooth Classic SPP. Use BLE GATT for this app.
