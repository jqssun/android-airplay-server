package io.github.jqssun.airplay

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import io.github.jqssun.airplay.service.AirPlayService

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val prefs = ctx.getSharedPreferences(Prefs.NAME, Context.MODE_PRIVATE)
        if (!prefs.getBoolean(Prefs.AUTO_START, Prefs.DEF_AUTO_START)) return
        val svcIntent = Intent(ctx, AirPlayService::class.java).setAction(AirPlayService.ACTION_AUTO_START)
        ContextCompat.startForegroundService(ctx, svcIntent)
    }
}
