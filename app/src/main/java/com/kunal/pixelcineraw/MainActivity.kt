package com.kunal.pixelcineraw

import android.Manifest
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.Surface
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kunal.pixelcineraw.camera.CinemaViewModel
import com.kunal.pixelcineraw.camera.ColorProfile
import com.kunal.pixelcineraw.camera.OrientationLockMode
import com.kunal.pixelcineraw.ui.CinemaScreen
import com.kunal.pixelcineraw.ui.theme.PixelCineRawTheme

class MainActivity : ComponentActivity() {

    private val cinemaViewModel: CinemaViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // True 100% edge-to-edge rendering so the live camera viewfinder fills behind transparent system bars
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isNavigationBarContrastEnforced = false
        }

        // Keep OLED screen awake during filming
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        startOrientationListener()

        setContent {
            PixelCineRawTheme {
                val state by cinemaViewModel.state.collectAsStateWithLifecycle()

                // True HDR Display Mode: Boost Pixel 9a OLED peak brightness when HLG or HDR10+ mode is active
                LaunchedEffect(state.colorProfile) {
                    val isHdr = state.colorProfile == ColorProfile.HLG_10BIT || state.colorProfile == ColorProfile.HDR10_PLUS
                    window.colorMode = if (isHdr) {
                        ActivityInfo.COLOR_MODE_HDR
                    } else {
                        ActivityInfo.COLOR_MODE_DEFAULT
                    }
                }

                // Orientation Lock & Hold: Enforces user-selected lock and auto-holds orientation during recording
                LaunchedEffect(state.isRecording, state.orientationLockMode) {
                    if (state.isRecording) {
                        val isPortraitCurrently = resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_PORTRAIT
                        if (isPortraitCurrently) {
                            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_USER_PORTRAIT
                        } else {
                            val rot = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                                try { display?.rotation ?: Surface.ROTATION_90 } catch (_: Exception) { Surface.ROTATION_90 }
                            } else {
                                @Suppress("DEPRECATION")
                                windowManager.defaultDisplay.rotation
                            }
                            requestedOrientation = if (rot == Surface.ROTATION_270) {
                                ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE
                            } else {
                                ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                            }
                        }
                    } else {
                        requestedOrientation = when (state.orientationLockMode) {
                            OrientationLockMode.AUTO -> ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR
                            OrientationLockMode.LOCK_LANDSCAPE -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                            OrientationLockMode.LOCK_PORTRAIT -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                        }
                    }
                }

                val permissions = remember {
                    arrayOf(
                        Manifest.permission.CAMERA,
                        Manifest.permission.RECORD_AUDIO
                    )
                }

                var hasPermissions by remember {
                    mutableStateOf(
                        permissions.all {
                            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
                        }
                    )
                }

                val permissionLauncher = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.RequestMultiplePermissions()
                ) { grants ->
                    hasPermissions = grants.values.all { it }
                }

                if (hasPermissions) {
                    CinemaScreen(
                        viewModel = cinemaViewModel,
                        onRecordToggle = {
                            cinemaViewModel.toggleRecording()
                        }
                    )
                } else {
                    PermissionRequestScreen(
                        onRequestPermissions = {
                            permissionLauncher.launch(permissions)
                        }
                    )
                }
            }
        }
    }

    private var orientationEventListener: android.view.OrientationEventListener? = null

    private fun startOrientationListener() {
        orientationEventListener = object : android.view.OrientationEventListener(this) {
            override fun onOrientationChanged(orientation: Int) {
                if (orientation == ORIENTATION_UNKNOWN) return
                val isPort = resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_PORTRAIT
                val rotation = if (isPort) {
                    android.view.Surface.ROTATION_0
                } else {
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                        try { display?.rotation ?: android.view.Surface.ROTATION_90 } catch (_: Exception) { android.view.Surface.ROTATION_90 }
                    } else {
                        @Suppress("DEPRECATION")
                        windowManager.defaultDisplay.rotation
                    }
                }
                cinemaViewModel.cameraController.setDisplayRotation(rotation)
            }
        }.apply {
            if (canDetectOrientation()) enable()
        }
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        val isHdr = cinemaViewModel.state.value.colorProfile == ColorProfile.HLG_10BIT ||
                cinemaViewModel.state.value.colorProfile == ColorProfile.HDR10_PLUS
        window.colorMode = if (isHdr) ActivityInfo.COLOR_MODE_HDR else ActivityInfo.COLOR_MODE_DEFAULT
        val isPort = newConfig.orientation == android.content.res.Configuration.ORIENTATION_PORTRAIT
        val rotation = if (isPort) {
            android.view.Surface.ROTATION_0
        } else {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                try { display?.rotation ?: android.view.Surface.ROTATION_90 } catch (_: Exception) { android.view.Surface.ROTATION_90 }
            } else {
                @Suppress("DEPRECATION")
                windowManager.defaultDisplay.rotation
            }
        }
        cinemaViewModel.cameraController.setDisplayRotation(rotation)
    }

    override fun onResume() {
        super.onResume()
        orientationEventListener?.enable()
    }

    override fun onPause() {
        super.onPause()
        orientationEventListener?.disable()
    }

    override fun onDestroy() {
        super.onDestroy()
        orientationEventListener?.disable()
        orientationEventListener = null
    }
}

@Composable
fun PermissionRequestScreen(
    onRequestPermissions: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0F0F0F)),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier.padding(32.dp)
        ) {
            Text(
                text = "PIXEL CINE RAW",
                color = Color(0xFFFFD700),
                fontSize = 24.sp,
                fontWeight = FontWeight.Black,
                fontFamily = FontFamily.Monospace
            )

            Text(
                text = "Professional Cinema Camera for Google Pixel 9a.\nRequires Camera and Microphone permissions to record 10-bit Log & RAW.",
                color = Color(0xCCFFFFFF),
                fontSize = 13.sp,
                fontFamily = FontFamily.Monospace
            )

            Button(
                onClick = onRequestPermissions,
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFFD700))
            ) {
                Text(
                    text = "GRANT CINEMA PERMISSIONS",
                    color = Color.Black,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace
                )
            }
        }
    }
}
