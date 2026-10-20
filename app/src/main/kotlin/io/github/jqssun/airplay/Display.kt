package io.github.jqssun.airplay

import android.content.Context
import android.hardware.display.DisplayManager
import android.os.Build
import android.provider.Settings
import android.view.Display

// physical panel resolution
fun Context.realDisplaySize(): Pair<Int, Int> {
    val mode = getSystemService(DisplayManager::class.java)
        ?.getDisplay(Display.DEFAULT_DISPLAY)?.mode
    return if (mode != null) mode.physicalWidth to mode.physicalHeight
    else resources.displayMetrics.let { it.widthPixels to it.heightPixels }
}

// user-facing device name from system settings, e.g. "Living Room TV"
fun Context.deviceName(): String {
    val global = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N_MR1)
        Settings.Global.getString(contentResolver, Settings.Global.DEVICE_NAME) else null
    // hidden key; may be unreadable on newer releases
    val bluetooth = runCatching { Settings.Secure.getString(contentResolver, "bluetooth_name") }.getOrNull()
    return listOf(global, bluetooth).firstOrNull { !it.isNullOrBlank() }?.trim() ?: Build.MODEL
}
