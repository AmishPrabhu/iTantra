package com.itantra.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.*
import androidx.core.content.ContextCompat
import com.itantra.ITantraApp
import com.itantra.service.WalkieTalkieForegroundService
import com.itantra.ui.alerts.EmergencyAlertScreen
import com.itantra.ui.mesh.MeshDiagnosticsScreen
import com.itantra.ui.onboarding.OnboardingScreen
import com.itantra.ui.theme.ITantraTheme
import com.itantra.ui.walkietalkie.WalkieTalkieScreen
import com.itantra.ui.walkietalkie.WalkieTalkieViewModel

enum class ScreenState {
    ONBOARDING,
    WALKIE_TALKIE,
    ALERTS,
    MESH_DIAGNOSTICS
}

class MainActivity : ComponentActivity() {

    private val walkieViewModel: WalkieTalkieViewModel by viewModels()

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        ITantraApp.instance.meshCoordinator.restartTransports()
        startMeshService()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        walkieViewModel.attachContext(this)
        requestRequiredPermissions()

        setContent {
            ITantraTheme {
                var currentScreen by remember { mutableStateOf(ScreenState.ONBOARDING) }

                when (currentScreen) {
                    ScreenState.ONBOARDING -> {
                        OnboardingScreen(
                            onContinueToWalkie = { chosenLang, chosenTransport ->
                                walkieViewModel.setPreferredLanguage(chosenLang)
                                walkieViewModel.setTransportMode(chosenTransport)
                                currentScreen = ScreenState.WALKIE_TALKIE
                            }
                        )
                    }

                    ScreenState.WALKIE_TALKIE -> {
                        WalkieTalkieScreen(
                            viewModel = walkieViewModel,
                            onNavigateToAlerts = { currentScreen = ScreenState.ALERTS },
                            onNavigateToMeshState = { currentScreen = ScreenState.MESH_DIAGNOSTICS }
                        )
                    }

                    ScreenState.ALERTS -> {
                        EmergencyAlertScreen(
                            onSendAlert = { alertTitle ->
                                walkieViewModel.sendQuickAlert(alertTitle)
                                currentScreen = ScreenState.WALKIE_TALKIE
                            },
                            onBack = { currentScreen = ScreenState.WALKIE_TALKIE }
                        )
                    }

                    ScreenState.MESH_DIAGNOSTICS -> {
                        MeshDiagnosticsScreen(
                            viewModel = walkieViewModel,
                            onBack = { currentScreen = ScreenState.WALKIE_TALKIE }
                        )
                    }
                }
            }
        }
    }

    private fun requestRequiredPermissions() {
        val permissions = mutableListOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.ACCESS_FINE_LOCATION
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions.add(Manifest.permission.BLUETOOTH_SCAN)
            permissions.add(Manifest.permission.BLUETOOTH_CONNECT)
            permissions.add(Manifest.permission.BLUETOOTH_ADVERTISE)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.NEARBY_WIFI_DEVICES)
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        val needed = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (needed.isNotEmpty()) {
            permissionLauncher.launch(needed.toTypedArray())
        } else {
            ITantraApp.instance.meshCoordinator.restartTransports()
            startMeshService()
        }
    }

    override fun onResume() {
        super.onResume()
        walkieViewModel.attachContext(this)
    }

    private fun startMeshService() {
        val serviceIntent = Intent(this, WalkieTalkieForegroundService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }
    }
}
