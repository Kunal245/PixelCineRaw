package com.kunal.pixelcineraw.ui

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlin.math.abs

/**
 * High-precision electronic cinema horizon leveler using Sensor.TYPE_ROTATION_VECTOR.
 *
 * Provides live roll and pitch angle feedback across landscape orientations.
 * When the camera is aligned within ±1.0 degree of true horizon, the leveler
 * dynamically snaps and glows vibrant cinema neon green (#00FF66).
 */
@Composable
fun HorizonLeveler(
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var rollDegrees by remember { mutableFloatStateOf(0f) }
    var pitchDegrees by remember { mutableFloatStateOf(0f) }

    DisposableEffect(context) {
        val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
        val rotationSensor = sensorManager?.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)

        val listener = object : SensorEventListener {
            val rotationMatrix = FloatArray(9)
            val orientation = FloatArray(3)

            override fun onSensorChanged(event: SensorEvent?) {
                if (event?.sensor?.type == Sensor.TYPE_ROTATION_VECTOR) {
                    SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)
                    SensorManager.getOrientation(rotationMatrix, orientation)

                    // In landscape orientation (device rotated 90 deg counter-clockwise):
                    // pitch corresponds to orientation[2], roll corresponds to -orientation[1]
                    val rollRad = -orientation[1]
                    val pitchRad = orientation[2]

                    val rollDeg = Math.toDegrees(rollRad.toDouble()).toFloat()
                    val pitchDeg = Math.toDegrees(pitchRad.toDouble()).toFloat()

                    // Low-pass filter for rock-solid silky-smooth readout
                    rollDegrees = rollDegrees * 0.8f + rollDeg * 0.2f
                    pitchDegrees = pitchDegrees * 0.8f + pitchDeg * 0.2f
                }
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
        }

        if (rotationSensor != null) {
            sensorManager.registerListener(listener, rotationSensor, SensorManager.SENSOR_DELAY_UI)
        }

        onDispose {
            sensorManager?.unregisterListener(listener)
        }
    }

    val isLevel = abs(rollDegrees) < 1.0f

    val levelColor by animateColorAsState(
        targetValue = if (isLevel) Color(0xFF00FF66) else Color(0xCCFFFFFF),
        animationSpec = tween(durationMillis = 150),
        label = "HorizonColor"
    )

    Canvas(
        modifier = modifier
            .fillMaxSize()
            .graphicsLayer {
                // Rotate the horizon line counter to the phone's physical roll tilt
                rotationZ = -rollDegrees
            }
    ) {
        val cx = size.width / 2f
        val cy = size.height / 2f

        val barLength = 120.dp.toPx()
        val gap = 24.dp.toPx()
        val strokeW = if (isLevel) 2.5.dp.toPx() else 1.5.dp.toPx()

        // Left Horizon Wing
        drawLine(
            color = levelColor,
            start = Offset(cx - barLength, cy),
            end = Offset(cx - gap, cy),
            strokeWidth = strokeW
        )

        // Left End Tick
        drawLine(
            color = levelColor,
            start = Offset(cx - barLength, cy - 6.dp.toPx()),
            end = Offset(cx - barLength, cy + 6.dp.toPx()),
            strokeWidth = strokeW
        )

        // Right Horizon Wing
        drawLine(
            color = levelColor,
            start = Offset(cx + gap, cy),
            end = Offset(cx + barLength, cy),
            strokeWidth = strokeW
        )

        // Right End Tick
        drawLine(
            color = levelColor,
            start = Offset(cx + barLength, cy - 6.dp.toPx()),
            end = Offset(cx + barLength, cy + 6.dp.toPx()),
            strokeWidth = strokeW
        )

        // Center Reference Crosshair Dots
        drawCircle(
            color = levelColor,
            radius = if (isLevel) 3.5.dp.toPx() else 2.5.dp.toPx(),
            center = Offset(cx, cy)
        )
    }
}
