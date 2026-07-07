package io.github.jqssun.airplay

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import androidx.core.content.ContextCompat
import io.github.jqssun.airplay.service.AirPlayService

// Some OEM battery managers (MIUI, EMUI, ColorOS, ...) kill the whole app process
// outright -- no onDestroy, no onTaskRemoved -- despite an active foreground
// service. This is the last-resort safety net: a manifest-registered receiver
// (survives process death, unlike one registered at runtime) woken by
// AlarmManager -- which keeps running in system_server independently of our
// process -- that restarts the server if it's supposed to still be running and
// re-arms itself for the next check.
class WatchdogReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val prefs = context.getSharedPreferences(Prefs.NAME, Context.MODE_PRIVATE)
        if (prefs.getBoolean(Prefs.SERVER_SHOULD_RUN, false)) {
            val serviceIntent = Intent(context, AirPlayService::class.java)
                .setAction(AirPlayService.ACTION_START_SERVER)
            ContextCompat.startForegroundService(context, serviceIntent)
        }
        schedule(context.applicationContext)
    }

    companion object {
        private const val INTERVAL_MS = 5 * 60 * 1000L

        private fun pendingIntent(context: Context): PendingIntent =
            PendingIntent.getBroadcast(
                context,
                0,
                Intent(context, WatchdogReceiver::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )

        fun schedule(context: Context, delayMs: Long = INTERVAL_MS) {
            val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            am.setAndAllowWhileIdle(
                AlarmManager.ELAPSED_REALTIME_WAKEUP,
                SystemClock.elapsedRealtime() + delayMs,
                pendingIntent(context)
            )
        }

        fun cancel(context: Context) {
            val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            am.cancel(pendingIntent(context))
        }
    }
}
