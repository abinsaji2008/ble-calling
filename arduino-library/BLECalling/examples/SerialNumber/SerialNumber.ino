#include <BLECalling.h>

// Enter an international phone number in Serial Monitor.
// The Android app must be connected and the number must be trusted there.

void setup() {
  Serial.begin(115200);
  delay(300);

  // One-time initialization: advertise so the Android app can connect.
  // All call requests in your main application use ble_calling(number).
  if (!ble_calling_begin()) {
    Serial.print("BLE startup failed: ");
    Serial.println(ble_calling_lastError());
    return;
  }

  Serial.println("Enter a number such as +919876543210, then press Enter.");
}

void loop() {
  if (!Serial.available()) {
    delay(10);
    return;
  }

  String number = Serial.readStringUntil('\n');
  number.trim();

  if (ble_calling(number)) {
    Serial.println("Call command sent to Android app.");
  } else {
    Serial.print("Failed: ");
    Serial.println(ble_calling_lastError());
  }
}
