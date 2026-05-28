package com.abook

import android.Manifest
import android.content.pm.PackageManager
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.abook.service.TtsPlaybackService
import com.abook.ui.navigation.ABookNavHost
import com.abook.ui.theme.ABookTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* no-op */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // POST_NOTIFICATIONS is required on Android 13+ (API 33) for our
        // foreground MediaStyle notification to show. Without it the system
        // treats our MediaSession as backgrounded and de-prioritises it for
        // headset / Bluetooth media button routing — any other media app
        // wins the next button press.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                this, Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        setContent {
            ABookTheme {
                ABookNavHost()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        reclaimHeadsetButtons()
    }

    /**
     * If another media app played while ABook was paused, Android usually keeps
     * headset/Bluetooth buttons routed to that app's last active MediaSession.
     * When the user returns to ABook, explicitly refresh our MediaSession so the
     * next headset Play/Pause goes back to the audiobook without having to start
     * playback from the screen first.
     */
    private fun reclaimHeadsetButtons() {
        try {
            startService(
                Intent(this, TtsPlaybackService::class.java).apply {
                    action = TtsPlaybackService.ACTION_RECLAIM_MEDIA_BUTTONS
                }
            )
        } catch (_: Exception) {
            // Best effort only. Normal playback controls still work from the UI.
        }
    }
}
