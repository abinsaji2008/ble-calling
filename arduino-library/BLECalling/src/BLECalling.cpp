#include "BLECalling.h"

#include <BLE2902.h>
#include <BLEDevice.h>
#include <BLEServer.h>
#include <BLEUtils.h>

namespace {
constexpr char SERVICE_UUID[] = "7a230001-5475-4c53-9c8e-7a2f4f2a0001";
constexpr char COMMAND_UUID[] = "7a230002-5475-4c53-9c8e-7a2f4f2a0001";

BLEServer* g_server = nullptr;
BLECharacteristic* g_commandCharacteristic = nullptr;
volatile bool g_connected = false;
bool g_initialized = false;
String g_lastError = "BLE has not been initialized.";

class BLECallingServerCallbacks final : public BLEServerCallbacks {
  void onConnect(BLEServer* server) override {
    g_connected = true;
    g_lastError = "";
    Serial.println("[BLECalling] Android app connected.");
  }

  void onDisconnect(BLEServer* server) override {
    g_connected = false;
    Serial.println("[BLECalling] Android app disconnected; advertising again.");
    server->startAdvertising();
  }
};

String normalizeNumber(const String& input) {
  String result;
  result.reserve(input.length());

  for (size_t i = 0; i < input.length(); ++i) {
    const char ch = input.charAt(i);
    if (ch == ' ' || ch == '-' || ch == '(' || ch == ')') {
      continue;
    }
    result += ch;
  }
  return result;
}

bool isValidInternationalNumber(const String& number) {
  // '+' plus 8 to 15 digits; first digit after '+' cannot be zero.
  if (number.length() < 9 || number.length() > 16 || number.charAt(0) != '+') {
    return false;
  }
  if (number.charAt(1) < '1' || number.charAt(1) > '9') {
    return false;
  }
  for (size_t i = 2; i < number.length(); ++i) {
    const char ch = number.charAt(i);
    if (ch < '0' || ch > '9') {
      return false;
    }
  }
  return true;
}
}  // namespace

bool ble_calling_begin(const char* deviceName) {
  if (g_initialized) {
    g_lastError = "";
    return true;
  }

  if (deviceName == nullptr || deviceName[0] == '\0') {
    deviceName = "ESP32S3-PatientCall";
  }

  BLEDevice::init(deviceName);

  g_server = BLEDevice::createServer();
  if (g_server == nullptr) {
    g_lastError = "Could not create BLE server.";
    return false;
  }
  g_server->setCallbacks(new BLECallingServerCallbacks());

  BLEService* service = g_server->createService(BLEUUID(SERVICE_UUID));
  if (service == nullptr) {
    g_lastError = "Could not create BLE service.";
    return false;
  }

  g_commandCharacteristic = service->createCharacteristic(
      BLEUUID(COMMAND_UUID),
      BLECharacteristic::PROPERTY_READ | BLECharacteristic::PROPERTY_NOTIFY);

  if (g_commandCharacteristic == nullptr) {
    g_lastError = "Could not create BLE command characteristic.";
    return false;
  }

  g_commandCharacteristic->setValue("READY");
  g_commandCharacteristic->addDescriptor(new BLE2902());
  service->start();

  BLEAdvertising* advertising = BLEDevice::getAdvertising();
  advertising->addServiceUUID(BLEUUID(SERVICE_UUID));
  advertising->setScanResponse(true);
  advertising->start();

  g_initialized = true;
  g_lastError = "";
  Serial.print("[BLECalling] Advertising as ");
  Serial.println(deviceName);
  Serial.println("[BLECalling] Open Patient Call Bridge and connect to this device.");
  return true;
}

bool ble_calling(const String& rawNumber) {
  if (!g_initialized) {
    g_lastError = "BLE is not started. Call ble_calling_begin() once from setup().";
    return false;
  }

  const String number = normalizeNumber(rawNumber);
  if (!isValidInternationalNumber(number)) {
    g_lastError = "Invalid number. Use international format, e.g. +919876543210.";
    Serial.print("[BLECalling] ");
    Serial.println(g_lastError);
    return false;
  }

  if (!g_connected || g_commandCharacteristic == nullptr) {
    g_lastError = "Android app is not connected over BLE.";
    Serial.print("[BLECalling] ");
    Serial.println(g_lastError);
    return false;
  }

  const String command = "CALL:" + number;
  g_commandCharacteristic->setValue(command.c_str());
  g_commandCharacteristic->notify();

  g_lastError = "";
  Serial.print("[BLECalling] Sent command: ");
  Serial.println(command);
  return true;
}

bool ble_calling_isConnected() {
  return g_connected;
}

String ble_calling_lastError() {
  return g_lastError;
}
