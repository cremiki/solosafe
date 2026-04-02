package com.solosafe.app

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.*
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.solosafe.app.ui.main.SimpleMainScreen
import com.solosafe.app.ui.qr.QrScanScreen
import com.solosafe.app.ui.settings.SettingsScreen
import com.solosafe.app.ui.welcome.WelcomeScreen
import com.solosafe.app.ui.theme.SoloSafeTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try { enableEdgeToEdge() } catch (_: Exception) {}

        val prefs = getSharedPreferences(SoloSafeApp.PREFS_NAME, Context.MODE_PRIVATE)
        val operatorId = prefs.getString(SoloSafeApp.KEY_OPERATOR_ID, null)
        val startDest = if (operatorId != null) "main" else "welcome"
        Log.d("SoloSafe", "onCreate: operator_id=${operatorId ?: "NULL"}, startDest=$startDest")

        setContent {
            SoloSafeTheme {
                val navController = rememberNavController()

                NavHost(navController = navController, startDestination = startDest) {

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
