package com.kunal.pixelcineraw.camera

import android.hardware.camera2.params.RggbChannelVector
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min

/**
 * High-precision Blackbody / Planckian locus approximation for White Balance
 * Kelvin (2300K - 8000K) and Tint (-50 to +50) to Camera2 [RggbChannelVector].
 */
object ColorMath {

    enum class WbPreset(val label: String, val kelvin: Int, val tint: Int) {
        TUNGSTEN("Tungsten", 3200, 0),
        FLUORESCENT("Fluorescent", 4000, 10),
        DAYLIGHT("Daylight", 5600, 0),
        CLOUDY("Cloudy", 6500, 5),
        SHADE("Shade", 7500, 8)
    }

    /**
     * Converts color temperature in Kelvin and green-magenta tint to Camera2 RggbChannelVector.
     * Tint ranges from -50 (magenta) to +50 (green).
     */
    fun kelvinToRggbChannelVector(kelvin: Int, tint: Int = 0): RggbChannelVector {
        val clampedK = kelvin.coerceIn(2000, 10000).toDouble() / 100.0

        // Tanner Helland algorithm / Planckian locus approximation
        val red: Double = if (clampedK <= 66) {
            255.0
        } else {
            val r = clampedK - 60
            329.698727446 * Math.pow(r, -0.1332047592)
        }.coerceIn(0.0, 255.0)

        val green: Double = if (clampedK <= 66) {
            val g = clampedK
            99.4708025861 * ln(g) - 161.1195681661
        } else {
            val g = clampedK - 60
            288.1221695283 * Math.pow(g, -0.0755148492)
        }.coerceIn(0.0, 255.0)

        val blue: Double = if (clampedK >= 66) {
            255.0
        } else if (clampedK <= 19) {
            0.0
        } else {
            val b = clampedK - 10
            138.5177312231 * ln(b) - 305.0447927307
        }.coerceIn(0.0, 255.0)

        // Normalize so green is 1.0, then invert to get camera compensation gains
        val normR = max(red / 255.0, 0.01)
        val normG = max(green / 255.0, 0.01)
        val normB = max(blue / 255.0, 0.01)

        // Tint multiplier: positive shifts green up, negative shifts green down (magenta)
        val tintFactor = 1.0 + (tint.coerceIn(-50, 50) / 100.0)

        // Camera gains: to counteract warm light (low Kelvin / high red), red gain is reduced and blue gain is boosted
        val rGain = (normG / normR * 1.8f).toFloat().coerceIn(0.5f, 5.0f)
        val gGain = (1.0f * tintFactor).toFloat().coerceIn(0.5f, 3.0f)
        val bGain = (normG / normB * 1.8f).toFloat().coerceIn(0.5f, 5.0f)

        return RggbChannelVector(rGain, gGain, gGain, bGain)
    }

    /**
     * Converts exposure time in nanoseconds to human readable fraction or decimal string.
     */
    fun formatShutterSpeed(nanos: Long): String {
        if (nanos <= 0) return "1/60s"
        val seconds = nanos.toDouble() / 1_000_000_000.0
        return if (seconds >= 1.0) {
            String.format("%.1fs", seconds)
        } else {
            val denom = (1.0 / seconds).toInt()
            "1/${denom}s"
        }
    }

    /**
     * Converts shutter angle (e.g. 180 deg) at given FPS to exposure time in nanoseconds.
     * ExposureTime = (Angle / 360) * (1 / FPS)
     */
    fun shutterAngleToNanos(angleDegrees: Float, fps: Int): Long {
        val safeFps = max(1, fps)
        val frameDurationNs = 1_000_000_000L / safeFps
        val exposureNs = (frameDurationNs * (angleDegrees / 360f)).toLong()
        return max(100_000L, exposureNs) // Minimum 1/10000s clamp
    }

    /**
     * Converts exposure time in nanoseconds to shutter angle at given FPS.
     * Angle = ExposureTime * 360 * FPS
     */
    fun nanosToShutterAngle(nanos: Long, fps: Int): Float {
        val safeFps = max(1, fps)
        val frameDurationNs = 1_000_000_000L / safeFps
        return ((nanos.toFloat() / frameDurationNs.toFloat()) * 360f).coerceIn(1f, 360f)
    }
}
