package com.solosafe.app

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.compose.runtime.*
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.solosafe.app.ui.main.SimpleMainScreen
import com.solosafe.app.ui.qr.QrScanScreen
import com.solosafe.app.ui.settings.SettingsScreen
import com.solosafe.app.ui.welcome.WelcomeScreen
import com.solosafe.app.ui.permissions.PermissionScreen
import com.solosafe.app.ui.registration.FreeRegistrationScreen
import com.solosafe.app.ui.theme.SoloSafeTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        // Install the system splash screen BEFORE super.onCreate() so it shows
        // immediately at launch and is dismissed automatically when first
        // composable frame is drawn.
        installSplashScreen()
        super.onCreate(savedInstanceState)
        try { enableEdgeToEdge() } catch (_: Exception) {}

        val prefs = getSharedPreferences(SoloSafeApp.PREFS_NAME, Context.MODE_PRIVATE)
        val operatorId = prefs.getString(SoloSafeApp.KEY_OPERATOR_ID, null)
        val freeUser = prefs.getBoolean("free_user", false)
        val permsDone = prefs.getBoolean("permissions_done", false)
        val startDest = when {
            operatorId != null && permsDone -> "main"
            freeUser && permsDone -> "main"
            operatorId != null || freeUser -> "permissions"
            else -> "registration"
        }
        Log.d("SoloSafe", "onCreate: operator_id=${operatorId ?: "NULL"}, startDest=$startDest")

        setContent {
            SoloSafeTheme {
                val navController = rememberNavController()

                NavHost(navController = navController, startDestination = startDest) {

                    composable("registration") {
                        FreeRegistrationScreen(
                            onGoToQr = { navController.navigate("qr") },
                        )
                    }

                    composable("welcome") {
                        WelcomeScreen(
                            onConfigureDevice = { navController.navigate("qr") },
                        )
                    }

                    composable("qr") {
                        QrScanScreen(
                            onScanned = { /* Activity restarts itself */ },
                            onBack = { navController.popBackStack() },
                        )
                    }

                    composable("permissions") {
                        PermissionScreen(onAllGranted = {
                            prefs.edit().putBoolean("permissions_done", true).commit()
                            navController.navigate("main") {
                                popUpTo(navController.graph.startDestinationId) { inclusive = true }
                            }
                        })
                    }

                    composable("main") {
                        Log.d("SoloSafe", "Rendering SimpleMainScreen")
                        SimpleMainScreen(
                            onOpenSettings = { navController.navigate("settings") },
                        )
                    }

                    composable("settings") {
                        SettingsScreen(onBack = { navController.popBackStack() })
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
    }
}
