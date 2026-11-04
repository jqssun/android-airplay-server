package io.github.jqssun.airplay

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import org.json.JSONObject

class SettingsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val prefs = context.getSharedPreferences(Prefs.NAME, Context.MODE_PRIVATE)
        when (intent.action) {
            ACTION_SET -> _set(prefs, intent.getStringExtra(EXTRA_KEY), intent.getStringExtra(EXTRA_VALUE))
            ACTION_LIST -> _reply(true, _list(prefs))
        }
    }

    private fun _set(prefs: SharedPreferences, key: String?, value: String?) {
        val pref = Prefs.all[key]
        when {
            pref == null -> _reply(false, "unknown key: $key")
            value == null || !pref.writeText(prefs, value) -> _reply(false, "invalid value for $key: $value")
            else -> _reply(true, "$key=$value")
        }
    }

    private fun _list(prefs: SharedPreferences) = JSONObject().apply {
        Prefs.all.forEach { (key, pref) -> put(key, JSONObject().put("value", prefs[pref]).put("default", pref.def)) }
    }.toString()

    private fun <T : Any> Pref<T>.writeText(p: SharedPreferences, s: String) = parse(s)?.let { write(p, it) } == true

    private fun _reply(ok: Boolean, data: String) =
        setResult(if (ok) Activity.RESULT_OK else Activity.RESULT_CANCELED, data, null)

    private companion object {
        const val ACTION_SET = "io.github.jqssun.airplay.SET_SETTING"
        const val ACTION_LIST = "io.github.jqssun.airplay.LIST_SETTINGS"
        const val EXTRA_KEY = "key"
        const val EXTRA_VALUE = "value"
    }
}
