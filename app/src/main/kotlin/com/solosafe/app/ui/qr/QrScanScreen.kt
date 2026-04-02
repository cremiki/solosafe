package com.solosafe.app.ui.qr

import android.app.Activity
import android.content.Intent
import android.Manifest
import android.content.pm.PackageManager
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.common.InputImage
import android.util.Log
import com.solosafe.app.SoloSafeApp
import com.solosafe.app.data.remote.SupabaseClient
import com.solosafe.app.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun QrScanScreen(
    onScanned: (String) -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()

    var scannedToken by remember { mutableStateOf<String?>(null) }
    var configState by remember { mutableStateOf<ConfigState>(ConfigState.Scanning) }
    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        )
    }

    // When token is scanned, fetch config from Supabase
    LaunchedEffect(scannedToken) {
        val token = scannedToken ?: return@LaunchedEffect
        Log.d("SoloSafe", "QR scansionato, token: $token")
        configState = ConfigState.Loading

        try {
            val supabase = SupabaseClient()
            Log.d("SoloSafe", "Chiamata getOperatorConfig...")
            val config = withContext(Dispatchers.IO) {
                supabase.getOperatorConfig(token)
            }

            if (config != null) {
                Log.d("SoloSafe", "Config ricevuta: operatore=${config.name}, id=${config.id}")
                // Save to SharedPreferences — commit() is synchronous
                val saved = context.getSharedPreferences(SoloSafeApp.PREFS_NAME, android.content.Context.MODE_PRIVATE)
                    .edit()
                    .putBoolean(SoloSafeApp.KEY_CONFIGURED, true)
                    .putString(SoloSafeApp.KEY_OPERATOR_ID, config.id)
                    .putString("company_id", config.company_id)
                    .putString("operator_name", config.name)
                    .putString("default_preset", config.default_preset)
                    .putString("default_session_type", config.default_session_type)
                    .putInt("default_duration_hours", config.default_duration_hours)
                    .commit()
                Log.d("SoloSafe", "SharedPreferences saved: $saved")

                // Verify it was actually written
                val verifyId = context.getSharedPreferences(SoloSafeApp.PREFS_NAME, android.content.Context.MODE_PRIVATE)
                    .getString(SoloSafeApp.KEY_OPERATOR_ID, null)
                Log.d("SoloSafe", "Verify operator_id after save: $verifyId")

                configState = ConfigState.Success(config.name)

                // Show success briefly, then restart Activity
                kotlinx.coroutines.delay(1500)
                Log.d("SoloSafe", "Restarting MainActivity...")
                (context as Activity).apply {
                    val restartIntent = Intent(this, com.solosafe.app.MainActivity::class.java)
                    restartIntent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                    startActivity(restartIntent)
                    finish()
                }
            } else {
                Log.w("SoloSafe", "Operatore non trovato per token: $token")
                configState = ConfigState.Error("Operatore non trovato. Verifica il QR code.")
            }
        } catch (e: Exception) {
            Log.e("SoloSafe", "Errore config: ${e.message}", e)
            configState = ConfigState.Error("Errore di connessione: ${e.message}")
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Background)
            .systemBarsPadding(),
    ) {
        // Header
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.Default.ArrowBack, contentDescription = "Indietro", tint = TextSecondary)
            }
            Text("Scansiona QR", color = TextPrimary, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        }

        when (configState) {
            is ConfigState.Loading -> {
                Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(color = SoloSafeRed, modifier = Modifier.size(48.dp))
                        Spacer(modifier = Modifier.height(16.dp))
                        Text("Configurazione in corso...", color = TextSecondary, fontSize = 14.sp)
                    }
                }
            }

            is ConfigState.Success -> {
                val name = (configState as ConfigState.Success).operatorName
                Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Default.CheckCircle, contentDescription = null, tint = Protected, modifier = Modifier.size(64.dp))
                        Spacer(modifier = Modifier.height(16.dp))
                        Text("Configurato!", color = Protected, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                        Spacer(modifier = Modifier.height(8.dp))
                        Text("Operatore: $name", color = TextPrimary, fontSize = 16.sp)
                    }
                }
            }

            is ConfigState.Error -> {
                val msg = (configState as ConfigState.Error).message
                Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(32.dp)) {
                        Icon(Icons.Default.Error, contentDescription = null, tint = Alarm, modifier = Modifier.size(48.dp))
                        Spacer(modifier = Modifier.height(16.dp))
                        Text("Errore", color = Alarm, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(msg, color = TextSecondary, fontSize = 14.sp, textAlign = TextAlign.Center)
                        Spacer(modifier = Modifier.height(24.dp))
                        Button(
                            onClick = { configState = ConfigState.Scanning; scannedToken = null },
                            colors = ButtonDefaults.buttonColors(containerColor = SoloSafeRed),
                            shape = RoundedCornerShape(12.dp),
                        ) {
                            Text("Riprova", fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }

            is ConfigState.Scanning -> {
                // Camera preview
                Box(modifier = Modifier.weight(1f).padding(24.dp)) {
                    AndroidView(
                        factory = { ctx ->
                            val previewView = PreviewView(ctx)
                            val cameraProviderFuture = ProcessCameraProvider.getInstance(ctx)
                            cameraProviderFuture.addListener({
                                try {
                                    val cameraProvider = cameraProviderFuture.get()
                                    val preview = Preview.Builder().build().also {
                                        it.setSurfaceProvider(previewView.surfaceProvider)
                                    }

                                    val analyzer = ImageAnalysis.Builder()
                                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                                        .build().also { analysis ->
                                            analysis.setAnalyzer(ContextCompat.getMainExecutor(ctx)) { imageProxy ->
                                                if (scannedToken != null) {
                                                    imageProxy.close()
                                                    return@setAnalyzer
                                                }
                                                @androidx.annotation.OptIn(androidx.camera.core.ExperimentalGetImage::class)
                                                val mediaImage = imageProxy.image
                                                if (mediaImage != null) {
                                                    val image = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)
                                                    BarcodeScanning.getClient().process(image)
                                                        .addOnSuccessListener { barcodes ->
                                                            for (barcode in barcodes) {
                                                                val raw = barcode.rawValue ?: continue
                                                                if (raw.startsWith("solosafe://config/")) {
                                                                    val token = raw.removePrefix("solosafe://config/")
                                                                    if (token.isNotBlank()) {
                                                                        scannedToken = token
                                                                    }
                                                                    return@addOnSuccessListener
                                                                }
                                                            }
                                                        }
                                                        .addOnCompleteListener { imageProxy.close() }
                                                } else {
                                                    imageProxy.close()
                                                }
                                            }
                                        }

                                    cameraProvider.unbindAll()
                                    cameraProvider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analyzer)
                                } catch (e: Exception) {
                                    // Camera init failed
                                }
                            }, ContextCompat.getMainExecutor(ctx))
                            previewView
                        },
                        modifier = Modifier.fillMaxSize(),
                    )

                    // Overlay frame
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Surface(
                            shape = RoundedCornerShape(16.dp),
                            color = SoloSafeRed.copy(alpha = 0.08f),
                            modifier = Modifier.size(250.dp),
                            border = ButtonDefaults.outlinedButtonBorder,
                        ) {}
                    }
                }

                // Instruction
                Box(
                    modifier = Modifier.fillMaxWidth().padding(24.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Default.QrCodeScanner, contentDescription = null, tint = SoloSafeRed, modifier = Modifier.size(32.dp))
                        Spacer(modifier = Modifier.height(8.dp))
                        Text("Inquadra il codice QR di configurazione", color = TextSecondary, fontSize = 14.sp)
                    }
                }
            }
        }
    }
}

private sealed class ConfigState {
    data object Scanning : ConfigState()
    data object Loading : ConfigState()
    data class Success(val operatorName: String) : ConfigState()
    data class Error(val message: String) : ConfigState()
}
