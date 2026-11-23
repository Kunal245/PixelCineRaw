package com.kunal.pixelcineraw.camera

import android.app.Application
import android.view.Surface
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.StateFlow

/**
 * ViewModel managing the cinema camera lifecycle, manual controls, and monitoring telemetry.
 */
class CinemaViewModel(application: Application) : AndroidViewModel(application) {

    val cameraController = Camera2Controller(application.applicationContext, viewModelScope)

    val state: StateFlow<CinemaState> = cameraController.state

    private val telemetryMonitor = SystemTelemetryMonitor(application.applicationContext) { battery, storageGb, thermal, mic ->
        cameraController.updateTelemetry(battery, storageGb, thermal, mic)
    }

    init {
        telemetryMonitor.start()
    }

    fun attachPreviewSurface(surface: Surface) {
        cameraController.attachPreviewSurface(surface)
    }

    fun detachPreviewSurface() {
        cameraController.detachPreviewSurface()
    }

    override fun onCleared() {
        super.onCleared()
        telemetryMonitor.stop()
        cameraController.closeCamera()
        cameraController.stopBackgroundThread()
    }
}
