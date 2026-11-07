package com.example.devicesync

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.graphics.Color
import androidx.core.content.ContextCompat
import com.example.devicesync.ui.AppUi

class MainActivity : ComponentActivity() {

    private val notifPerm = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (Build.VERSION.SDK_INT >= 33) {
            notifPerm.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        ContextCompat.startForegroundService(
            this, Intent(this, SyncNodeService::class.java)
        )

        setContent {
            MaterialTheme(colorScheme = darkColorScheme(
                background = Color(0xFF0D0D12),
                surface = Color(0xFF16161E),
                primary = Color(0xFF7C9EFF)
            )) {
                AppUi()
            }
        }
    }
}
