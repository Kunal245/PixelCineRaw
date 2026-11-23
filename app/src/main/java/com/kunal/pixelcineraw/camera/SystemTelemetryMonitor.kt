package com.kunal.pixelcineraw.camera

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.BatteryManager
import android.os.Environment
import android.os.PowerManager
import android.os.StatFs
import kotlinx.coroutines.*
import java.util.concurrent.Executors

/**
 * Monitors battery, available storage, USB-C audio devices, and Tensor G4 thermal state.
 */
class SystemTelemetryMonitor(
    private val context: Context,
    private val onUpdate: (battery: Int, storageGb: Float, thermal: ThermalStatus, micConnected: Boolean) -> Unit
) {
    private val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var isMonitoring = false
    private var monitorJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    private var currentThermalStatus = ThermalStatus.NOMINAL

    private val thermalListener = PowerManager.OnThermalStatusChangedListener { status ->
        currentThermalStatus = when (status) {
            PowerManager.THERMAL_STATUS_NONE -> ThermalStatus.NOMINAL
            PowerManager.THERMAL_STATUS_LIGHT -> ThermalStatus.LIGHT
            PowerManager.THERMAL_STATUS_MODERATE -> ThermalStatus.MODERATE
            PowerManager.THERMAL_STATUS_SEVERE -> ThermalStatus.SEVERE
            PowerManager.THERMAL_STATUS_CRITICAL,
            PowerManager.THERMAL_STATUS_EMERGENCY,
            PowerManager.THERMAL_STATUS_SHUTDOWN -> ThermalStatus.EMERGENCY
            else -> ThermalStatus.NOMINAL
        }
    }

    fun start() {
        if (isMonitoring) return
        isMonitoring = true

        try {
            powerManager.addThermalStatusListener(Executors.newSingleThreadExecutor(), thermalListener)
        } catch (e: Exception) {
            // Ignore on devices without thermal HAL
        }

        monitorJob = scope.launch {
            while (isActive && isMonitoring) {
                pollTelemetry()
                delay(2000L) // Poll every 2 seconds
            }
        }
    }

    fun stop() {
        isMonitoring = false
        monitorJob?.cancel()
        monitorJob = null
        try {
            powerManager.removeThermalStatusListener(thermalListener)
        } catch (e: Exception) {
            // Ignore
        }
    }

    private fun pollTelemetry() {
        val battery = getBatteryPercentage()
        val storageGb = getAvailableStorageGb()
        val micConnected = isExternalMicAttached()

        onUpdate(battery, storageGb, currentThermalStatus, micConnected)
    }

    private fun getBatteryPercentage(): Int {
        val intentFilter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        val batteryStatus: Intent? = context.registerReceiver(null, intentFilter)
        val level: Int = batteryStatus?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale: Int = batteryStatus?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        return if (level >= 0 && scale > 0) {
            (level * 100 / scale.toFloat()).toInt()
        } else {
            100
        }
    }

    private fun getAvailableStorageGb(): Float {
        return try {
            val path = Environment.getExternalStorageDirectory()
            val stat = StatFs(path.path)
            val bytesAvailable = stat.availableBlocksLong * stat.blockSizeLong
            (bytesAvailable / (1024f * 1024f * 1024f))
        } catch (e: Exception) {
            64.0f
        }
    }

    private fun isExternalMicAttached(): Boolean {
        return try {
            val devices = audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS)
            devices.any { device ->
                device.type == AudioDeviceInfo.TYPE_USB_DEVICE ||
                device.type == AudioDeviceInfo.TYPE_USB_HEADSET ||
                device.type == AudioDeviceInfo.TYPE_WIRED_HEADSET
            }
        } catch (e: Exception) {
            false
        }
    }
}
