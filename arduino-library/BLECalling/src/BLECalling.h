#pragma once

#include <Arduino.h>

/*
  BLECalling - send a call command to the Patient Call Bridge Android app.

  Usage:
    #include <BLECalling.h>

    void setup() {
      Serial.begin(115200);
      ble_calling_begin();  // Call once so Android can discover the ESP32.
    }

    // Wherever your application decides to place a call:
    ble_calling("+919876543210");
*/

/// Starts the BLE server and advertises the service expected by the Android app.
/// Call once from setup(). Returns true if BLE advertising was started.
bool ble_calling_begin(const char* deviceName = "ESP32S3-PatientCall");

/// Sends CALL:<number> as a BLE notification to the connected Android app.
/// Number must be in international format, e.g. +919876543210.
/// Returns false if the number is invalid or the app is disconnected.
bool ble_calling(const String& number);

/// C-string overload for convenient use with char arrays.
inline bool ble_calling(const char* number) {
  return number != nullptr && ble_calling(String(number));
}

/// True when at least one BLE client (normally the Android app) is connected.
bool ble_calling_isConnected();

/// Returns a diagnostic message for the most recent operation.
String ble_calling_lastError();
