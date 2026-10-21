# BLECalling Arduino Library

A small ESP32 / ESP32-S3 Arduino library that sends call commands to the Patient Call Bridge Android app. BLE implementation stays inside the library; the main application calls:

~~~cpp
ble_calling(number);
~~~

## Minimal integration

~~~cpp
#include <BLECalling.h>

void setup() {
  Serial.begin(115200);
  ble_calling_begin();  // Once at boot, so the Android app can discover the ESP32.
}

void someFunction() {
  String number = "+919876543210";
  ble_calling(number);  // This is the call command used in the main application.
}

void loop() {
  // Your existing application logic.
}
~~~

The one-time ble_calling_begin() call is required so the ESP32 advertises its BLE service before the Android app connects. No BLE setup code is needed in the rest of the main sketch.

## Command protocol

The library sends a UTF-8 BLE notification containing a command like this:

~~~text
CALL:+919876543210
~~~

The default service and characteristic UUIDs match the Patient Call Bridge Android app. Default BLE device name: ESP32S3-PatientCall. It can be customized by calling ble_calling_begin("MyPatientAssistant").

## Requirements

- Arduino IDE with the Espressif ESP32 board package installed.
- An ESP32 model with BLE support, including ESP32-S3.
- The Patient Call Bridge Android app installed and connected over BLE.

This uses BLE APIs bundled with the Espressif Arduino core; no additional BLE library is required.

## Install

1. Download the repository as a ZIP, or copy the arduino-library/BLECalling folder.
2. For Arduino IDE, package the BLECalling folder itself as the top-level ZIP folder. It must contain library.properties, src/, and examples/.
3. Select Sketch -> Include Library -> Add .ZIP Library.
4. Or copy the BLECalling folder into your Arduino libraries folder and restart the IDE.
5. Open File -> Examples -> BLECalling -> SerialNumber.

## Phone/app setup

1. Open Patient Call Bridge and connect it to ESP32S3-PatientCall.
2. Add the destination number to the app's trusted-number list.
3. Grant Phone permission and enable Auto-call.
4. To receive commands while the app is minimized, keep its foreground-service notification active.

The library does not directly place calls. It sends a command; the Android app checks the trusted-number list and requests the cellular call.

## API

- bool ble_calling_begin(const char* deviceName = "ESP32S3-PatientCall") — start advertising; call once from setup().
- bool ble_calling(const String& number) — send a call command. Returns false if the number is invalid or Android is disconnected.
- bool ble_calling_isConnected() — check BLE connection state.
- String ble_calling_lastError() — get the last diagnostic message.

Numbers are normalized by removing spaces, hyphens, and parentheses, then checked for international E.164-style format. The library does not queue call requests for later delivery.

## Safety

This is a prototype communication helper, not a certified emergency-calling system. Test with a number you control before integrating it into a patient or emergency workflow.
