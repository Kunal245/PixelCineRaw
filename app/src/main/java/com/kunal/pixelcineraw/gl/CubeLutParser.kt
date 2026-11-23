package com.kunal.pixelcineraw.gl

import android.util.Log
import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader

/**
 * High-performance parser for industry-standard Adobe / DaVinci Resolve .cube 3D LUT files.
 */
data class CubeLut(
    val title: String,
    val size: Int,
    val data: FloatArray // RGB RGB RGB ... length = size * size * size * 3
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as CubeLut
        return size == other.size && data.contentEquals(other.data)
    }

    override fun hashCode(): Int {
        var result = title.hashCode()
        result = 31 * result + size
        result = 31 * result + data.contentHashCode()
        return result
    }
}

object CubeLutParser {
    private const val TAG = "CubeLutParser"

    fun parse(inputStream: InputStream): CubeLut? {
        try {
            val reader = BufferedReader(InputStreamReader(inputStream))
            var title = "Custom LUT"
            var size = 0
            val floatList = mutableListOf<Float>()

            reader.useLines { lines ->
                for (rawLine in lines) {
                    val line = rawLine.trim()
                    if (line.isEmpty() || line.startsWith("#")) continue

                    val upper = line.uppercase()
                    if (upper.startsWith("TITLE")) {
                        title = line.substringAfter("TITLE").trim().removeSurrounding("\"")
                    } else if (upper.startsWith("LUT_3D_SIZE")) {
                        size = line.substringAfter("LUT_3D_SIZE").trim().toIntOrNull() ?: 0
                    } else if (upper.startsWith("DOMAIN_MIN") || upper.startsWith("DOMAIN_MAX") || upper.startsWith("LUT_1D_SIZE")) {
                        // Skip 1D or domain headers
                        continue
                    } else {
                        // Data row
                        val parts = line.split("\\s+".toRegex())
                        if (parts.size >= 3) {
                            val r = parts[0].toFloatOrNull()
                            val g = parts[1].toFloatOrNull()
                            val b = parts[2].toFloatOrNull()
                            if (r != null && g != null && b != null) {
                                floatList.add(r)
                                floatList.add(g)
                                floatList.add(b)
                            }
                        }
                    }
                }
            }

            if (size <= 0 || floatList.size < size * size * size * 3) {
                Log.w(TAG, "Invalid .cube file: size=$size, points=${floatList.size / 3}")
                return null
            }

            return CubeLut(title = title, size = size, data = floatList.toFloatArray())
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse .cube LUT file", e)
            return null
        }
    }
}
