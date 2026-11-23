package com.kunal.pixelcineraw.imu

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import java.io.BufferedWriter
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.util.concurrent.atomic.AtomicBoolean

/**
 * High-precision IMU Logger for Gyroflow video stabilization.
 * Records hardware gyro (rad/s) and accelerometer (m/s^2) at up to ~200-400Hz
 * synchronized to camera frame timestamps, outputting a standard .gcsv sidecar.
 */
class GyroflowLogger(private val context: Context) : SensorEventListener {

    companion object {
        private const val TAG = "GyroflowLogger"
    }

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
    private val gyroSensor = sensorManager?.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
    private val accelSensor = sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

    private var sensorThread: HandlerThread? = null
    private var sensorHandler: Handler? = null

    private var writer: BufferedWriter? = null
    private val isLogging = AtomicBoolean(false)

    // Cached latest accelerometer readings to pair with gyroscope events
    @Volatile private var latestAx = 0f
    @Volatile private var latestAy = 0f
    @Volatile private var latestAz = 0f

    private var startTimestampNs = -1L

    fun start(outputStream: OutputStream) {
        if (isLogging.getAndSet(true)) return

        startTimestampNs = -1L
        try {
            sensorThread = HandlerThread("GyroflowLoggerThread").apply {
                start()
                sensorHandler = Handler(looper)
            }

            val bw = BufferedWriter(OutputStreamWriter(outputStream, Charsets.UTF_8), 32768)
            bw.write("GYROFLOW IMU LOG\n")
            bw.write("version,1.3\n")
            bw.write("camera_name,Google Pixel 9a\n")
            bw.write("tscale,0.000001\n") // microseconds
            bw.write("gscale,1.0\n")
            bw.write("ascale,1.0\n")
            bw.write("t,gx,gy,gz,ax,ay,az\n")
            writer = bw

            val handler = sensorHandler
            gyroSensor?.let {
                sensorManager?.registerListener(this, it, SensorManager.SENSOR_DELAY_FASTEST, handler)
            }
            accelSensor?.let {
                sensorManager?.registerListener(this, it, SensorManager.SENSOR_DELAY_FASTEST, handler)
            }

            Log.i(TAG, "Gyroflow IMU logging started")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start Gyroflow logger", e)
            stop()
        }
    }

    fun stop() {
        if (!isLogging.getAndSet(false)) return

        try {
            sensorManager?.unregisterListener(this)
            writer?.flush()
            writer?.close()
            writer = null

            sensorThread?.quitSafely()
            sensorThread?.join(500)
            sensorThread = null
            sensorHandler = null

            Log.i(TAG, "Gyroflow IMU logging stopped successfully")
        } catch (e: Exception) {
            Log.w(TAG, "Error stopping Gyroflow logger", e)
        }
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (!isLogging.get()) return

        val bw = writer ?: return
        try {
            val timestampNs = event.timestamp
            if (startTimestampNs < 0) {
                startTimestampNs = timestampNs
            }
            val relTimestampUs = (timestampNs - startTimestampNs) / 1000L

            when (event.sensor.type) {
                Sensor.TYPE_ACCELEROMETER -> {
                    latestAx = event.values[0]
                    latestAy = event.values[1]
                    latestAz = event.values[2]
                }
                Sensor.TYPE_GYROSCOPE -> {
                    val gx = event.values[0]
                    val gy = event.values[1]
                    val gz = event.values[2]
                    // Format: t,gx,gy,gz,ax,ay,az
                    bw.write("$relTimestampUs,$gx,$gy,$gz,$latestAx,$latestAy,$latestAz\n")
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error writing IMU sample", e)
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
}
