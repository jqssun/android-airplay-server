package io.github.jqssun.airplay

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import io.github.jqssun.airplay.service.AirPlayService

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        Log.i(TAG, "received action=$action")
        if (action != Intent.ACTION_BOOT_COMPLETED &&
            action != "android.intent.action.QUICKBOOT_POWERON" &&
            action != "android.intent.action.LOCKED_BOOT_COMPLETED" &&
            action != Intent.ACTION_MY_PACKAGE_REPLACED) return

        val prefs = context.getSharedPreferences(Prefs.NAME, Context.MODE_PRIVATE)
        val enabled = prefs[Prefs.BOOT_AUTO_START]
        Log.i(TAG, "boot auto-start pref = $enabled")
        if (!enabled) return

        val appCtx = context.applicationContext

        // Fire OS 7+ sometimes blocks startForegroundService immediately after boot. A short
        // delay lets the system settle and significantly increases success rate.
        Handler(Looper.getMainLooper()).postDelayed({
            try {
                val serviceIntent = Intent(appCtx, AirPlayService::class.java)
                    .setAction(AirPlayService.ACTION_START_SERVER)
                ContextCompat.startForegroundService(appCtx, serviceIntent)
                Log.i(TAG, "startForegroundService dispatched")
            } catch (t: Throwable) {
                Log.e(TAG, "startForegroundService failed", t)
            }
        }, 2000L)
    }

    companion object {
        private const val TAG = "AirPlayBoot"
    }
}
