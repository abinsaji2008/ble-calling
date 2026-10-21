package com.abinsaji.blecalling

import android.Manifest
import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.ParcelUuid
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.util.Locale

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

    private val permissionLauncherRequest = 201
    private val scanPermissionRequest = 202
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
        val title = TextView(this).apply { text = "Patient Call Bridge"; textSize = 24f }
        status = TextView(this).apply { text = "Status: disconnected"; textSize = 16f }
        numberInput = EditText(this).apply {
            hint = "Trusted number, e.g. +919876543210"
            inputType = android.text.InputType.TYPE_CLASS_PHONE
        }
        trustedInput = EditText(this).apply {
            hint = "Trusted numbers: " + if (trustedNumbers.isEmpty()) "none" else trustedNumbers.joinToString()
            isFocusable = false
            isClickable = false
        }
        val add = Button(this).apply {
            text = "Add trusted number"
            setOnClickListener { addTrustedNumber() }
        }
        autoCallButton = Button(this).apply {
            text = if (autoCall) "Auto-call: ON" else "Auto-call: OFF"
            setOnClickListener { toggleAutoCall() }
        }
        val connect = Button(this).apply {
            text = "Connect to ESP32-S3"
            setOnClickListener { startScan() }
        }
        val callTest = Button(this).apply {
            text = "Test call (trusted number)"
            setOnClickListener {
                val number = numberInput.text.toString().trim()
                if (number !in trustedNumbers) {
                    toast("Add this number to trusted numbers first")
                } else {
                    placeCall(number)
                }
            }
        }
        root.addView(title)
        root.addView(status)
        root.addView(numberInput)
        root.addView(add)
        root.addView(trustedInput)
        root.addView(autoCallButton)
        root.addView(connect)
        root.addView(callTest)
        val scroll = ScrollView(this).apply { addView(root) }
        setContentView(scroll)
        requestCallPermissionIfNeeded()
        refreshTrustedLabel()
    }

    private fun addTrustedNumber() {
        val number = numberInput.text.toString().trim().replace(" ", "")
        if (!number.matches(Regex("^\\+[1-9][0-9]{7,14}$"))) {
            toast("Enter a valid international number such as +919876543210")
            return
        }
        trustedNumbers.add(number)
        prefs.edit().putStringSet("trusted", trustedNumbers.toSet()).apply()
        refreshTrustedLabel()
        toast("Trusted number added")
    }

    private fun refreshTrustedLabel() {
        trustedInput.hint = if (trustedNumbers.isEmpty()) "Trusted numbers: none" else "Trusted: " + trustedNumbers.joinToString()
    }

    private fun toggleAutoCall() {
        autoCall = !autoCall
        if (autoCall && checkSelfPermission(Manifest.permission.CALL_PHONE) != PackageManager.PERMISSION_GRANTED) {
            autoCall = false
            requestPermissions(arrayOf(Manifest.permission.CALL_PHONE), permissionLauncherRequest)
            toast("Grant Phone permission, then enable auto-call")
        } else {
            prefs.edit().putBoolean("autocall", autoCall).apply()
            autoCallButton.text = if (autoCall) "Auto-call: ON" else "Auto-call: OFF"
            toast(if (autoCall) "Auto-call enabled" else "Auto-call disabled")
        }
    }

    private fun requestCallPermissionIfNeeded() {
        if (checkSelfPermission(Manifest.permission.CALL_PHONE) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.CALL_PHONE), permissionLauncherRequest)
        }
    }

    private fun hasBluetoothPermissions(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED &&
                checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
        } else {
            checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun startScan() {
        if (!hasBluetoothPermissions()) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                requestPermissions(arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT), scanPermissionRequest)
            } else {
                requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), scanPermissionRequest)
            }
            return
        }
        val scanner = adapter?.bluetoothLeScanner
        if (scanner == null) {
            status.text = "Status: Bluetooth unavailable or disabled"
            return
        }
        status.text = "Status: scanning for ESP32-S3…"
        try {
            scanner.startScan(scanCallback)
        } catch (e: SecurityException) {
            status.text = "Status: Bluetooth permission denied"
        }
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val serviceUuids = result.scanRecord?.serviceUuids ?: emptyList()
            if (serviceUuids.any { it.uuid == BleConfig.SERVICE_UUID }) {
                runOnUiThread {
                    try {
                        adapter?.bluetoothLeScanner?.stopScan(this)
                        status.text = "Status: connecting to " + (result.device.name ?: result.device.address)
                        bluetoothGatt?.close()
                        bluetoothGatt = result.device.connectGatt(this@MainActivity, false, gattCallback)
                    } catch (e: SecurityException) {
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
            if (newState == android.bluetooth.BluetoothProfile.STATE_CONNECTED) {
                bluetoothGatt = gatt
                gatt.discoverServices()
            } else if (newState == android.bluetooth.BluetoothProfile.STATE_DISCONNECTED) {
                runOnUiThread { status.text = "Status: disconnected" }
                gatt.close()
                if (bluetoothGatt == gatt) bluetoothGatt = null
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, statusCode: Int) {
            val service = gatt.getService(BleConfig.SERVICE_UUID)
            commandCharacteristic = service?.getCharacteristic(BleConfig.COMMAND_UUID)
            runOnUiThread {
                status.text = if (commandCharacteristic != null) "Status: connected — waiting for command" else "Status: service/characteristic not found"
            }
        }

        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            val command = characteristic.value?.toString(Charsets.UTF_8)?.trim() ?: return
            runOnUiThread { handleCommand(command) }
        }

        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
            runOnUiThread { handleCommand(value.toString(Charsets.UTF_8).trim()) }
        }
    }

    private fun handleCommand(command: String) {
        status.text = "Last command: " + command
        if (!command.startsWith("CALL:", ignoreCase = true)) {
            status.append("\nIgnored: unknown command")
            return
        }
        val number = command.substringAfter(":").trim().replace(" ", "")
        if (!number.matches(Regex("^\\+[1-9][0-9]{7,14}$"))) {
            status.append("\nIgnored: invalid number")
            return
        }
        if (number !in trustedNumbers) {
            status.append("\nIgnored: number not in trusted list")
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
            status.append("\nPhone permission missing; enable it in Android settings")
            requestCallPermissionIfNeeded()
            return
        }
        try {
            startActivity(Intent(Intent.ACTION_CALL, Uri.parse("tel:$number")))
            status.append("\nCalling " + number)
        } catch (e: Exception) {
            status.append("\nCall could not start: " + (e.message ?: e.javaClass.simpleName))
        }
    }

    override fun onDestroy() {
        try { bluetoothGatt?.close() } catch (_: SecurityException) {}
        bluetoothGatt = null
        super.onDestroy()
    }

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
}
