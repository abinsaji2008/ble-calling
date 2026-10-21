package com.abinsaji.blecalling

import java.util.UUID

object BleConfig {
    // Configure the ESP32-S3 firmware to use these exact UUIDs.
    val SERVICE_UUID: UUID = UUID.fromString("7a230001-5475-4c53-9c8e-7a2f4f2a0001")
    val COMMAND_UUID: UUID = UUID.fromString("7a230002-5475-4c53-9c8e-7a2f4f2a0001")
}
