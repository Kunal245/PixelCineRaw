package com.kunal.pixelcineraw

import android.Manifest
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
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
import com.kunal.pixelcineraw.ui.CinemaScreen
import com.kunal.pixelcineraw.ui.theme.PixelCineRawTheme

class MainActivity : ComponentActivity() {

    private val cinemaViewModel: CinemaViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Keep OLED screen awake during filming
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // Hide system status and navigation bars for immersive cinema viewfinder
        hideSystemUI()

        setContent {
            PixelCineRawTheme {
                val state by cinemaViewModel.state.collectAsStateWithLifecycle()

                // True HDR Display Mode: Boost Pixel 9a OLED peak brightness when HLG mode is active
                LaunchedEffect(state.colorProfile) {
                    val isHlg = state.colorProfile == ColorProfile.HLG_10BIT
                    window.colorMode = if (isHlg) {
                        ActivityInfo.COLOR_MODE_HDR
                    } else {
                        ActivityInfo.COLOR_MODE_DEFAULT
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

    override fun onResume() {
        super.onResume()
        hideSystemUI()
    }

    private fun hideSystemUI() {
        window.insetsController?.let { controller ->
            controller.hide(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
            controller.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
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
