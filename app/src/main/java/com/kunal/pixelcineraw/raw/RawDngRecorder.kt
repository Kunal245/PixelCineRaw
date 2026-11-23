package com.kunal.pixelcineraw.raw

import android.content.ContentValues
import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.DngCreator
import android.media.Image
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * High-performance RAW Sensor (10/12-bit Bayer DNG) frame recorder using
 * a lock-free ring buffer on a dedicated high-priority worker thread to prevent
 * frame drops on Google Pixel 9a's Tensor G4.
 */
class RawDngRecorder(
    private val context: Context
) {
    companion object {
        private const val TAG = "RawDngRecorder"
        private const val RING_BUFFER_CAPACITY = 8
    }

    private data class RawFrame(
        val image: Image,
        val captureResult: CaptureResult,
        val frameIndex: Int
    )

    private val ringBuffer = ArrayBlockingQueue<RawFrame>(RING_BUFFER_CAPACITY)
    private val isRecording = AtomicBoolean(false)
    private val frameCounter = AtomicInteger(0)
    private val droppedCounter = AtomicInteger(0)

    private var workerThread: Thread? = null
    private var sessionFolderName: String = ""

    var onMetricsUpdate: ((seconds: Long, bytes: Long, droppedFrames: Int) -> Unit)? = null

    /**
     * Enqueues a captured RAW_SENSOR image into the ring buffer.
     * If buffer is full, drops the frame gracefully without blocking the Camera2 pipeline.
     */
    fun enqueueRawFrame(image: Image, result: CaptureResult) {
        if (!isRecording.get()) {
            image.close()
            return
        }

        val frame = RawFrame(
            image = image,
            captureResult = result,
            frameIndex = frameCounter.incrementAndGet()
        )

        val enqueued = ringBuffer.offer(frame)
        if (!enqueued) {
            droppedCounter.incrementAndGet()
            image.close()
            Log.w(TAG, "Ring buffer full! Dropped RAW frame #${frame.frameIndex}")
        }
    }

    /**
     * Starts the background DNG writing thread.
     */
    fun start(characteristics: CameraCharacteristics): String {
        if (isRecording.get()) return ""

        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        sessionFolderName = "RAW_CLIP_${timestamp}"
        frameCounter.set(0)
        droppedCounter.set(0)
        ringBuffer.clear()

        isRecording.set(true)
        val startTimeMs = System.currentTimeMillis()
        var totalBytesSaved = 0L

        workerThread = Thread({
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO)

            while (isRecording.get() || ringBuffer.isNotEmpty()) {
                val frame = try {
                    ringBuffer.poll(200, java.util.concurrent.TimeUnit.MILLISECONDS)
                } catch (e: InterruptedException) {
                    null
                } ?: continue

                try {
                    val bytes = writeDngFrame(frame, characteristics)
                    totalBytesSaved += bytes
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to write DNG frame #${frame.frameIndex}", e)
                } finally {
                    frame.image.close()
                }

                val elapsedSecs = (System.currentTimeMillis() - startTimeMs) / 1000
                onMetricsUpdate?.invoke(elapsedSecs, totalBytesSaved, droppedCounter.get())
            }
        }, "PixelCineRawDngWorker").apply {
            start()
        }

        return sessionFolderName
    }

    /**
     * Writes single ImageFormat.RAW_SENSOR frame to an Adobe DNG container via DngCreator.
     */
    private fun writeDngFrame(
        frame: RawFrame,
        characteristics: CameraCharacteristics
    ): Long {
        val fileName = String.format("FRAME_%05d.dng", frame.frameIndex)

        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
            put(MediaStore.Images.Media.MIME_TYPE, "image/x-adobe-dng")
            put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/PixelCineRaw/$sessionFolderName")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }

        val resolver = context.contentResolver
        val uri: Uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: throw IllegalStateException("Failed to create MediaStore DNG entry")

        var bytesWritten = 0L

        resolver.openOutputStream(uri)?.use { outputStream: OutputStream ->
            DngCreator(characteristics, frame.captureResult).use { dngCreator ->
                dngCreator.writeImage(outputStream, frame.image)
            }
        }

        // Mark as ready
        val updateValues = ContentValues().apply {
            put(MediaStore.Images.Media.IS_PENDING, 0)
        }
        resolver.update(uri, updateValues, null, null)

        bytesWritten = (frame.image.width * frame.image.height * 2L) // Estimated Bayer size
        return bytesWritten
    }

    /**
     * Stops the RAW DNG recording session and flushes pending frames.
     */
    fun stop() {
        if (!isRecording.getAndSet(false)) return

        try {
            workerThread?.join(3000)
        } catch (e: InterruptedException) {
            Log.w(TAG, "Raw worker thread interrupted", e)
        } finally {
            workerThread = null
        }

        // Drain any remaining frames to release native hardware buffers
        while (ringBuffer.isNotEmpty()) {
            val frame = ringBuffer.poll()
            frame?.image?.close()
        }
    }
}
