package io.github.jqssun.airplay

import android.content.Context
import android.content.res.Configuration
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.ConfigurationCompat
import androidx.core.os.LocaleListCompat

/** AppCompat persists the choice and applies it on Android 7 and newer. */
object AppLanguage {
    const val SYSTEM = ""
    const val CHINESE = "zh-CN"
    const val ENGLISH = "en"

    fun current(): String = when (AppCompatDelegate.getApplicationLocales()[0]?.language) {
        "zh" -> CHINESE
        "en" -> ENGLISH
        else -> SYSTEM
    }

    fun select(tag: String) {
        require(tag == SYSTEM || tag == CHINESE || tag == ENGLISH)
        AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(tag))
    }

    // A Service/Application context does not receive AppCompat's activity locale
    // on Android 12 and below. Resolve notification/toast strings explicitly.
    fun localizedContext(context: Context): Context {
        val locales = AppCompatDelegate.getApplicationLocales()
        if (locales.isEmpty) return context
        val config = Configuration(context.resources.configuration)
        ConfigurationCompat.setLocales(config, locales)
        return context.createConfigurationContext(config)
    }
}
