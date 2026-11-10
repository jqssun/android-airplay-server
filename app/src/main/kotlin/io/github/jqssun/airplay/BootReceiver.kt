package io.github.jqssun.airplay

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat
import io.github.jqssun.airplay.service.AirPlayService

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in BOOT_ACTIONS) return

        val prefs = context.getSharedPreferences(Prefs.NAME, Context.MODE_PRIVATE)
        if (!prefs[Prefs.BOOT_AUTO_START]) return

        val serviceIntent = Intent(context, AirPlayService::class.java)
            .setAction(AirPlayService.ACTION_START_SERVER)
        try {
            ContextCompat.startForegroundService(context, serviceIntent)
        } catch (e: Exception) {
            // android 15+ also re-sends BOOT_COMPLETED when an app leaves the
            // force-stopped state, and that delivery may not be allowed to start a
            // foreground service (ForegroundServiceStartNotAllowedException)
            Log.w(TAG, "boot auto-start refused: ${e.message}")
        }
    }

    private companion object {
        const val TAG = "AirPlayBoot"

        // some devices (HTC and various Android TVs among them) broadcast a
        // quickboot action instead of BOOT_COMPLETED when waking from "fast boot"
        val BOOT_ACTIONS = setOf(
            Intent.ACTION_BOOT_COMPLETED,
            "android.intent.action.QUICKBOOT_POWERON",
            "com.htc.intent.action.QUICKBOOT_POWERON",
        )
    }
}
