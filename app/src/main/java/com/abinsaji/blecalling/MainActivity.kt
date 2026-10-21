package com.abinsaji.blecalling

import android.Manifest
import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.util.UUID

class MainActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var numberInput: EditText
    private lateinit var trustedInput: EditText
    private lateinit var autoCallButton: Button
    private var bluetoothGatt: BluetoothGatt? = null
    private var commandCharacteristic: BluetoothGattCharacteristic? = null
    private var autoCall = false
    private val trustedNumbers = linkedSetOf<String>()
    private val prefs by lazy { getSharedPreferences("settings", Context.MODE_PRIVATE) }

    private val callPermissionRequest = 201
    private val bluetoothPermissionRequest = 202

    private val adapter: BluetoothAdapter? by lazy {
        (getSystemService(BLUETOOTH_SERVICE) as BluetoothManager).adapter
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        autoCall = prefs.getBoolean("autocall", false)
        trustedNumbers.addAll(prefs.getStringSet("trusted", emptySet()) ?: emptySet())

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(28, 24, 28, 24)
        }
        root.addView(TextView(this).apply {
            text = "Patient Call Bridge"
            textSize = 24f
        })
        status = TextView(this).apply {
            text = "Status: disconnected"
            textSize = 16f
        }
        root.addView(status)

        numberInput = EditText(this).apply {
            hint = "Trusted number, e.g. +919876543210"
            inputType = android.text.InputType.TYPE_CLASS_PHONE
        }
        root.addView(numberInput)

        root.addView(Button(this).apply {
            text = "Add trusted number"
            setOnClickListener { addTrustedNumber() }
        })

        trustedInput = EditText(this).apply {
            isFocusable = false
            isClickable = false
        }
        root.addView(trustedInput)

        autoCallButton = Button(this).apply {
            text = if (autoCall) "Auto-call: ON" else "Auto-call: OFF"
            setOnClickListener { toggleAutoCall() }
        }
        root.addView(autoCallButton)

        root.addView(Button(this).apply {
            text = "Connect to ESP32-S3"
            setOnClickListener { startScan() }
        })

        root.addView(Button(this).apply {
            text = "Test call (trusted number)"
            setOnClickListener {
                val number = normalizeNumber(numberInput.text.toString())
                if (number !in trustedNumbers) {
                    toast("Add this number to trusted numbers first")
                } else {
                    placeCall(number)
                }
            }
        })

        setContentView(ScrollView(this).apply { addView(root) })
        refreshTrustedLabel()
        requestCallPermissionIfNeeded()
    }

    private fun normalizeNumber(raw: String): String = raw.trim().replace(" ", "").replace("-", "")

    private fun isValidNumber(number: String): Boolean =
        number.matches(Regex("^\\+[1-9][0-9]{7,14}$"))

    private fun addTrustedNumber() {
        val number = normalizeNumber(numberInput.text.toString())
        if (!isValidNumber(number)) {
            toast("Enter a valid international number, e.g. +919876543210")
            return
        }
        trustedNumbers.add(number)
        prefs.edit().putStringSet("trusted", trustedNumbers.toSet()).apply()
        refreshTrustedLabel()
        toast("Trusted number added")
    }

    private fun refreshTrustedLabel() {
        trustedInput.setText(
            if (trustedNumbers.isEmpty()) "Trusted numbers: none"
            else "Trusted numbers: " + trustedNumbers.joinToString()
        )
    }

    private fun toggleAutoCall() {
        if (!autoCall && checkSelfPermission(Manifest.permission.CALL_PHONE) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.CALL_PHONE), callPermissionRequest)
            toast("Grant Phone permission, then enable auto-call")
            return
        }
        autoCall = !autoCall
        prefs.edit().putBoolean("autocall", autoCall).apply()
        autoCallButton.text = if (autoCall) "Auto-call: ON" else "Auto-call: OFF"
        toast(if (autoCall) "Auto-call enabled" else "Auto-call disabled")
    }

    private fun requestCallPermissionIfNeeded() {
        if (checkSelfPermission(Manifest.permission.CALL_PHONE) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.CALL_PHONE), callPermissionRequest)
        }
    }

    private fun hasBluetoothPermissions(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED &&
                checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
        } else {
            checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        }

    private fun startScan() {
        if (!hasBluetoothPermissions()) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                requestPermissions(
                    arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT),
                    bluetoothPermissionRequest
                )
            } else {
                requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), bluetoothPermissionRequest)
            }
            return
        }

        val scanner = adapter?.bluetoothLeScanner
        if (scanner == null) {
            status.text = "Status: Bluetooth unavailable or switched off"
            return
        }

        status.text = "Status: scanning for ESP32-S3…"
        try {
            scanner.startScan(scanCallback)
        } catch (_: SecurityException) {
            status.text = "Status: Bluetooth permission denied"
        }
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val advertisedServices = result.scanRecord?.serviceUuids ?: emptyList()
            if (advertisedServices.any { it.uuid == BleConfig.SERVICE_UUID }) {
                runOnUiThread {
                    try {
                        adapter?.bluetoothLeScanner?.stopScan(this)
                        status.text = "Status: connecting to " + (result.device.name ?: result.device.address)
                        bluetoothGatt?.close()
                        bluetoothGatt = result.device.connectGatt(this@MainActivity, false, gattCallback)
                    } catch (_: SecurityException) {
                        status.text = "Status: Bluetooth permission denied"
                    }
                }
            }
        }

        override fun onScanFailed(errorCode: Int) {
            runOnUiThread { status.text = "Status: scan failed ($errorCode)" }
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, statusCode: Int, newState: Int) {
            if (statusCode == BluetoothGatt.GATT_SUCCESS && newState == BluetoothProfile.STATE_CONNECTED) {
                bluetoothGatt = gatt
                try {
                    gatt.discoverServices()
                } catch (_: SecurityException) {
                    runOnUiThread { status.text = "Status: Bluetooth permission denied" }
                }
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                runOnUiThread {
                    status.text = "Status: disconnected"
                    commandCharacteristic = null
                }
                gatt.close()
                if (bluetoothGatt == gatt) bluetoothGatt = null
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, statusCode: Int) {
            if (statusCode != BluetoothGatt.GATT_SUCCESS) {
                runOnUiThread { status.text = "Status: service discovery failed ($statusCode)" }
                return
            }
            val service = gatt.getService(BleConfig.SERVICE_UUID)
            val characteristic = service?.getCharacteristic(BleConfig.COMMAND_UUID)
            commandCharacteristic = characteristic
            if (characteristic == null) {
                runOnUiThread { status.text = "Status: BLE service/command characteristic not found" }
                return
            }
            enableCommandNotifications(gatt, characteristic)
        }

        @Suppress("DEPRECATION")
        private fun enableCommandNotifications(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic
        ) {
            val properties = characteristic.properties
            val canNotify = properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0
            val canIndicate = properties and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0
            if (!canNotify && !canIndicate) {
                runOnUiThread { status.text = "Status: command characteristic does not support notify/indicate" }
                return
            }

            try {
                if (!gatt.setCharacteristicNotification(characteristic, true)) {
                    runOnUiThread { status.text = "Status: could not enable BLE notifications" }
                    return
                }
                val descriptor = characteristic.getDescriptor(CCCD_UUID)
                if (descriptor == null) {
                    runOnUiThread { status.text = "Status: notification descriptor missing on ESP32" }
                    return
                }
                val value = if (canNotify) {
                    BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                } else {
                    BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
                }

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    val result = gatt.writeDescriptor(descriptor, value)
                    if (result != BluetoothStatusCodes.SUCCESS) {
                        runOnUiThread { status.text = "Status: could not subscribe to BLE notifications ($result)" }
                    }
                } else {
                    descriptor.value = value
                    if (!gatt.writeDescriptor(descriptor)) {
                        runOnUiThread { status.text = "Status: could not subscribe to BLE notifications" }
                    }
                }
            } catch (_: SecurityException) {
                runOnUiThread { status.text = "Status: Bluetooth permission denied" }
            }
        }

        override fun onDescriptorWrite(
            gatt: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            statusCode: Int
        ) {
            if (descriptor.uuid == CCCD_UUID) {
                runOnUiThread {
                    status.text = if (statusCode == BluetoothGatt.GATT_SUCCESS) {
                        "Status: connected — waiting for CALL command"
                    } else {
                        "Status: BLE notification subscription failed ($statusCode)"
                    }
                }
            }
        }

        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic
        ) {
            handleBleBytes(characteristic.value ?: return)
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            handleBleBytes(value)
        }
    }

    private fun handleBleBytes(value: ByteArray) {
        val command = value.toString(Charsets.UTF_8).trim()
        runOnUiThread { handleCommand(command) }
    }

    private fun handleCommand(command: String) {
        status.text = "Last command: $command"
        if (!command.startsWith("CALL:", ignoreCase = true)) {
            status.append("\nIgnored: unknown command")
            return
        }
        val number = normalizeNumber(command.substringAfter(":"))
        if (!isValidNumber(number)) {
            status.append("\nIgnored: invalid number")
            return
        }
        if (number !in trustedNumbers) {
            status.append("\nIgnored: number is not in trusted list")
            return
        }
        if (!autoCall) {
            status.append("\nAuto-call is OFF; no call placed")
            return
        }
        placeCall(number)
    }

    private fun placeCall(number: String) {
        if (checkSelfPermission(Manifest.permission.CALL_PHONE) != PackageManager.PERMISSION_GRANTED) {
            status.append("\nPhone permission missing; grant it in Android settings")
            requestCallPermissionIfNeeded()
            return
        }
        try {
            startActivity(Intent(Intent.ACTION_CALL, Uri.parse("tel:$number")))
            status.append("\nCalling $number")
        } catch (e: Exception) {
            status.append("\nCall could not start: " + (e.message ?: e.javaClass.simpleName))
        }
    }

    override fun onDestroy() {
        try {
            bluetoothGatt?.close()
        } catch (_: SecurityException) {
        }
        bluetoothGatt = null
        super.onDestroy()
    }

    private fun toast(message: String) =
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()

    companion object {
        private val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }
}
