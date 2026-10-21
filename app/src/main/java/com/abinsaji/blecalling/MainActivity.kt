package com.abinsaji.blecalling

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.telecom.TelecomManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {
    private lateinit var numberInput: EditText
    private lateinit var trustedLabel: TextView
    private lateinit var autoCallButton: Button
    private lateinit var statusLabel: TextView

    private var autoCall = false
    private val trustedNumbers = linkedSetOf<String>()
    private val prefs by lazy { getSharedPreferences("settings", Context.MODE_PRIVATE) }

    private val requestBlePermissionsCode = 202
    private val requestCallPermissionCode = 203
    private val requestNotificationPermissionCode = 204

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
            textSize = 25f
        })
        root.addView(TextView(this).apply {
            text = "Connect to the ESP32-S3 once. The foreground BLE service keeps the connection active while you use other apps."
            textSize = 15f
            setPadding(0, 12, 0, 12)
        })

        statusLabel = TextView(this).apply {
            text = "Tap Connect to start background BLE monitoring."
            textSize = 15f
        }
        root.addView(statusLabel)

        numberInput = EditText(this).apply {
            hint = "Trusted number, e.g. +919876543210"
            inputType = android.text.InputType.TYPE_CLASS_PHONE
        }
        root.addView(numberInput)

        root.addView(Button(this).apply {
            text = "Add trusted number"
            setOnClickListener { addTrustedNumber() }
        })

        trustedLabel = TextView(this).apply {
            textSize = 14f
            setPadding(0, 8, 0, 12)
        }
        root.addView(trustedLabel)

        autoCallButton = Button(this).apply {
            text = if (autoCall) "Auto-call: ON" else "Auto-call: OFF"
            setOnClickListener { toggleAutoCall() }
        }
        root.addView(autoCallButton)

        root.addView(Button(this).apply {
            text = "Connect / keep BLE running"
            setOnClickListener { requestBlePermissionsThenStart() }
        })

        root.addView(Button(this).apply {
            text = "Stop BLE connection"
            setOnClickListener {
                stopService(Intent(this@MainActivity, BleCallService::class.java))
                statusLabel.text = "BLE background service stopped."
                toast("BLE connection stopped")
            }
        })

        root.addView(Button(this).apply {
            text = "Test call (trusted number)"
            setOnClickListener {
                val number = normalizeNumber(numberInput.text.toString())
                if (number !in trustedNumbers) {
                    toast("Add the number to trusted numbers first")
                } else {
                    placeCall(number)
                }
            }
        })

        root.addView(TextView(this).apply {
            text = "Keep the ongoing Patient Call Bridge notification visible. On Redmi, set Battery saver to No restrictions and enable Autostart if available. Force-stop will stop background monitoring."
            textSize = 13f
            setPadding(0, 16, 0, 0)
        })

        setContentView(ScrollView(this).apply { addView(root) })
        refreshTrustedLabel()

        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), requestNotificationPermissionCode)
        }
    }

    private fun normalizeNumber(raw: String): String =
        raw.trim().replace(" ", "").replace("-", "")

    private fun isValidNumber(number: String): Boolean =
        number.matches(Regex("^\\+[1-9][0-9]{7,14}$"))

    private fun addTrustedNumber() {
        val number = normalizeNumber(numberInput.text.toString())
        if (!isValidNumber(number)) {
            toast("Enter an international number such as +919876543210")
            return
        }
        trustedNumbers.add(number)
        prefs.edit().putStringSet("trusted", trustedNumbers.toSet()).apply()
        refreshTrustedLabel()
        toast("Trusted number added")
    }

    private fun refreshTrustedLabel() {
        trustedLabel.text = if (trustedNumbers.isEmpty()) {
            "Trusted numbers: none"
        } else {
            "Trusted numbers:\n" + trustedNumbers.joinToString("\n")
        }
    }

    private fun toggleAutoCall() {
        if (!autoCall &&
            checkSelfPermission(Manifest.permission.CALL_PHONE) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.CALL_PHONE), requestCallPermissionCode)
            toast("Grant Phone permission, then tap Auto-call again")
            return
        }
        autoCall = !autoCall
        prefs.edit().putBoolean("autocall", autoCall).apply()
        autoCallButton.text = if (autoCall) "Auto-call: ON" else "Auto-call: OFF"
        toast(if (autoCall) "Auto-call enabled" else "Auto-call disabled")
    }

    private fun requestBlePermissionsThenStart() {
        val required = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        val missing = required.filter {
            checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            requestPermissions(missing.toTypedArray(), requestBlePermissionsCode)
            return
        }
        startBackgroundBleService()
    }

    private fun startBackgroundBleService() {
        try {
            val serviceIntent = Intent(this, BleCallService::class.java).apply {
                action = BleCallService.ACTION_START
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent)
            } else {
                startService(serviceIntent)
            }
            statusLabel.text = "Background BLE service started. Watch the persistent notification for connection status."
            toast("BLE connection service started")
        } catch (e: Exception) {
            statusLabel.text = "Could not start BLE service: " + (e.message ?: e.javaClass.simpleName)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == requestBlePermissionsCode) {
            if (grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
                startBackgroundBleService()
            } else {
                toast("Bluetooth permission is needed to connect to the ESP32-S3")
            }
        } else if (requestCode == requestCallPermissionCode) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                toast("Phone permission granted. Tap Auto-call again to enable it.")
            } else {
                toast("Phone permission denied; calls will not be placed")
            }
        }
    }

    private fun placeCall(number: String) {
        if (checkSelfPermission(Manifest.permission.CALL_PHONE) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.CALL_PHONE), requestCallPermissionCode)
            return
        }
        try {
            val telecom = getSystemService(Context.TELECOM_SERVICE) as TelecomManager
            telecom.placeCall(Uri.fromParts("tel", number, null), Bundle())
            toast("Call requested: $number")
        } catch (e: Exception) {
            toast("Call could not start: " + (e.message ?: e.javaClass.simpleName))
        }
    }

    override fun onResume() {
        super.onResume()
        autoCall = prefs.getBoolean("autocall", false)
        if (::autoCallButton.isInitialized) {
            autoCallButton.text = if (autoCall) "Auto-call: ON" else "Auto-call: OFF"
        }
        if (::trustedLabel.isInitialized) refreshTrustedLabel()
    }

    private fun toast(message: String) =
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
}
