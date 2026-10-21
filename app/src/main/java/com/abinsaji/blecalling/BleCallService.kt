package com.abinsaji.blecalling

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.ParcelUuid
import android.telecom.TelecomManager
import java.util.UUID

/** Keeps the ESP32-S3 BLE connection alive while the activity is minimized. */
class BleCallService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private val prefs by lazy { getSharedPreferences("settings", Context.MODE_PRIVATE) }

    @Volatile private var scanning = false
    @Volatile private var connecting = false
    @Volatile private var connected = false
    @Volatile private var isStopping = false

    private var bluetoothGatt: BluetoothGatt? = null
    private var lastCalledNumber: String? = null
    private var lastCallAtMs = 0L

    private val reconnectRunnable = Runnable {
        if (!isStopping) beginScan()
    }

    private val bluetoothAdapter: BluetoothAdapter? by lazy {
        (getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            isStopping = true
            handler.removeCallbacks(reconnectRunnable)
            stopBleConnection()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }

        isStopping = false
        startAsForeground("Starting BLE connection to ESP32-S3…")
        beginScan()
        return START_STICKY
    }

    private fun startAsForeground(message: String) {
        val notification = makeNotification(message)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Patient Call Bridge connection",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows whether the ESP32-S3 BLE calling connection is active"
                setShowBadge(false)
            }
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .createNotificationChannel(channel)
        }
    }

    private fun makeNotification(message: String): Notification {
        val openPendingIntent = PendingIntent.getActivity(
            this,
            OPEN_REQUEST_CODE,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stopIntent = Intent(this, BleCallService::class.java).apply { action = ACTION_STOP }
        val stopPendingIntent = PendingIntent.getService(
            this,
            STOP_REQUEST_CODE,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_call)
            .setContentTitle("Patient Call Bridge")
            .setContentText(message)
            .setStyle(Notification.BigTextStyle().bigText(message))
            .setContentIntent(openPendingIntent)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(Notification.Action.Builder(null, "Disconnect", stopPendingIntent).build())
            .build()
    }

    private fun updateNotification(message: String) {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, makeNotification(message))
    }

    private fun hasBluetoothPermissions(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED &&
                checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
        } else {
            // Legacy Bluetooth permissions are install-time permissions. Discovery uses
            // CompanionDeviceManager from the activity, so location permission is unnecessary.
            true
        }

    private fun beginScan() {
        if (isStopping || connected || connecting || scanning) return
        if (!hasBluetoothPermissions()) {
            updateNotification("Bluetooth permission missing. Open the app and grant it.")
            return
        }

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            val savedAddress = prefs.getString("ble_device_address", null)
            if (savedAddress.isNullOrBlank()) {
                updateNotification("Open Patient Call Bridge and tap Connect to choose the ESP32-S3. App location permission is not needed.")
                return
            }
            connectToSavedDevice(savedAddress)
            return
        }

        val scanner = try { bluetoothAdapter?.bluetoothLeScanner } catch (_: SecurityException) { null }
        if (scanner == null) {
            updateNotification("Bluetooth is off or unavailable. Turn it on, then reconnect.")
            handler.postDelayed(reconnectRunnable, RECONNECT_DELAY_MS)
            return
        }

        val filter = ScanFilter.Builder().setServiceUuid(ParcelUuid(BleConfig.SERVICE_UUID)).build()
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()

        scanning = true
        updateNotification("Searching for ESP32-S3…")
        try {
            scanner.startScan(listOf(filter), settings, scanCallback)
        } catch (_: SecurityException) {
            scanning = false
            updateNotification("BLE scan permission denied. Open the app to fix permissions.")
        } catch (_: Exception) {
            scanning = false
            updateNotification("BLE scan failed. Retrying…")
            handler.postDelayed(reconnectRunnable, RECONNECT_DELAY_MS)
        }
    }

    private fun connectToSavedDevice(address: String) {
        if (isStopping || connected || connecting) return
        val adapter = try { bluetoothAdapter } catch (_: SecurityException) { null }
        if (adapter == null || !adapter.isEnabled) {
            updateNotification("Bluetooth is off. Turn it on to reconnect to ESP32-S3.")
            handler.postDelayed(reconnectRunnable, RECONNECT_DELAY_MS)
            return
        }

        try {
            val device = adapter.getRemoteDevice(address)
            connecting = true
            updateNotification("Connecting to saved ESP32-S3…")
            bluetoothGatt?.close()
            bluetoothGatt = device.connectGatt(this, false, gattCallback)
        } catch (e: Exception) {
            connecting = false
            updateNotification("Could not connect to saved ESP32-S3. Retrying…")
            handler.postDelayed(reconnectRunnable, RECONNECT_DELAY_MS)
        }
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            if (connecting || connected || isStopping) return
            handler.post {
                if (connecting || connected || isStopping) return@post
                val scanner = try { bluetoothAdapter?.bluetoothLeScanner } catch (_: SecurityException) { null }
                try { scanner?.stopScan(this) } catch (_: Exception) {}
                scanning = false
                connecting = true
                val name = try { result.device.name ?: result.scanRecord?.deviceName ?: "ESP32-S3" }
                    catch (_: SecurityException) { "ESP32-S3" }
                updateNotification("Connecting to $name…")
                try {
                    bluetoothGatt?.close()
                    bluetoothGatt = result.device.connectGatt(this@BleCallService, false, gattCallback)
                } catch (_: Exception) {
                    connecting = false
                    updateNotification("Could not connect to ESP32-S3. Retrying…")
                    handler.postDelayed(reconnectRunnable, RECONNECT_DELAY_MS)
                }
            }
        }

        override fun onScanFailed(errorCode: Int) {
            scanning = false
            updateNotification("BLE scan failed ($errorCode). Retrying…")
            handler.postDelayed(reconnectRunnable, RECONNECT_DELAY_MS)
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS && newState == BluetoothProfile.STATE_CONNECTED) {
                connected = true
                connecting = false
                bluetoothGatt = gatt
                try {
                    gatt.discoverServices()
                } catch (_: SecurityException) {
                    updateNotification("Bluetooth permission missing. Open the app.")
                }
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                connected = false
                connecting = false
                try { gatt.close() } catch (_: Exception) {}
                if (bluetoothGatt == gatt) bluetoothGatt = null
                if (!isStopping) {
                    updateNotification("ESP32-S3 disconnected. Reconnecting…")
                    handler.removeCallbacks(reconnectRunnable)
                    handler.postDelayed(reconnectRunnable, RECONNECT_DELAY_MS)
                }
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                updateNotification("BLE service discovery failed. Reconnecting…")
                disconnectAndRetry(gatt)
                return
            }
            val characteristic = gatt.getService(BleConfig.SERVICE_UUID)
                ?.getCharacteristic(BleConfig.COMMAND_UUID)
            if (characteristic == null) {
                updateNotification("ESP32-S3 BLE UUID mismatch. Check firmware UUIDs.")
                disconnectAndRetry(gatt)
                return
            }
            enableCommandNotifications(gatt, characteristic)
        }

        @Suppress("DEPRECATION")
        private fun enableCommandNotifications(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic
        ) {
            val canNotify = characteristic.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0
            val canIndicate = characteristic.properties and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0
            if (!canNotify && !canIndicate) {
                updateNotification("BLE command characteristic must support notifications.")
                disconnectAndRetry(gatt)
                return
            }

            try {
                if (!gatt.setCharacteristicNotification(characteristic, true)) {
                    updateNotification("Could not enable BLE notifications. Retrying…")
                    disconnectAndRetry(gatt)
                    return
                }
                val descriptor = characteristic.getDescriptor(CCCD_UUID)
                if (descriptor == null) {
                    updateNotification("ESP32-S3 characteristic is missing its notification descriptor.")
                    disconnectAndRetry(gatt)
                    return
                }
                val value = if (canNotify) BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                    else BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    val result = gatt.writeDescriptor(descriptor, value)
                    if (result != BluetoothStatusCodes.SUCCESS) {
                        updateNotification("Could not subscribe to BLE commands ($result).")
                    }
                } else {
                    descriptor.value = value
                    if (!gatt.writeDescriptor(descriptor)) {
                        updateNotification("Could not subscribe to BLE commands.")
                    }
                }
            } catch (_: SecurityException) {
                updateNotification("Bluetooth permission denied. Open the app to fix permissions.")
            }
        }

        override fun onDescriptorWrite(
            gatt: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int
        ) {
            if (descriptor.uuid == CCCD_UUID) {
                updateNotification(
                    if (status == BluetoothGatt.GATT_SUCCESS) {
                        "Connected to ESP32-S3. Waiting for CALL command."
                    } else {
                        "BLE notification subscription failed ($status)."
                    }
                )
            }
        }

        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            processIncomingBytes(characteristic.value ?: return)
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            processIncomingBytes(value)
        }
    }

    private fun processIncomingBytes(bytes: ByteArray) {
        val command = bytes.toString(Charsets.UTF_8).trim()
        handler.post { handleCommand(command) }
    }

    private fun handleCommand(command: String) {
        if (!command.startsWith("CALL:", ignoreCase = true)) {
            updateNotification("Ignored unknown ESP32-S3 command.")
            return
        }
        val number = command.substringAfter(":").trim().replace(" ", "").replace("-", "")
        if (!number.matches(Regex("^\\+[1-9][0-9]{7,14}$"))) {
            updateNotification("Ignored invalid phone number.")
            return
        }
        val trustedNumbers = prefs.getStringSet("trusted", emptySet()) ?: emptySet()
        if (number !in trustedNumbers) {
            updateNotification("CALL rejected: number is not in trusted list.")
            return
        }
        if (!prefs.getBoolean("autocall", false)) {
            updateNotification("CALL received. Auto-call is OFF.")
            return
        }
        if (checkSelfPermission(Manifest.permission.CALL_PHONE) != PackageManager.PERMISSION_GRANTED) {
            updateNotification("Phone permission missing. Open the app and grant it.")
            return
        }

        val now = System.currentTimeMillis()
        if (lastCalledNumber == number && now - lastCallAtMs < DUPLICATE_CALL_GUARD_MS) {
            updateNotification("Duplicate CALL command ignored.")
            return
        }
        try {
            val telecom = getSystemService(Context.TELECOM_SERVICE) as TelecomManager
            telecom.placeCall(Uri.fromParts("tel", number, null), Bundle())
            lastCalledNumber = number
            lastCallAtMs = now
            updateNotification("Call requested to $number")
        } catch (e: Exception) {
            updateNotification("Call failed: " + (e.message ?: e.javaClass.simpleName))
        }
    }

    private fun disconnectAndRetry(gatt: BluetoothGatt) {
        connected = false
        connecting = false
        try { gatt.disconnect() } catch (_: Exception) {}
        try { gatt.close() } catch (_: Exception) {}
        if (bluetoothGatt == gatt) bluetoothGatt = null
        if (!isStopping) handler.postDelayed(reconnectRunnable, RECONNECT_DELAY_MS)
    }

    private fun stopBleConnection() {
        try { bluetoothAdapter?.bluetoothLeScanner?.stopScan(scanCallback) } catch (_: Exception) {}
        scanning = false
        connected = false
        connecting = false
        try { bluetoothGatt?.disconnect() } catch (_: Exception) {}
        try { bluetoothGatt?.close() } catch (_: Exception) {}
        bluetoothGatt = null
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // Keep monitoring after the activity is swiped from the recent-apps screen.
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        isStopping = true
        handler.removeCallbacks(reconnectRunnable)
        stopBleConnection()
        super.onDestroy()
    }

    companion object {
        const val ACTION_START = "com.abinsaji.blecalling.action.START"
        const val ACTION_STOP = "com.abinsaji.blecalling.action.STOP"

        private const val CHANNEL_ID = "patient_call_bridge_ble"
        private const val NOTIFICATION_ID = 7312
        private const val OPEN_REQUEST_CODE = 7313
        private const val STOP_REQUEST_CODE = 7314
        private const val RECONNECT_DELAY_MS = 3000L
        private const val DUPLICATE_CALL_GUARD_MS = 30000L
        private val CCCD_UUID: UUID =
            UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }
}
