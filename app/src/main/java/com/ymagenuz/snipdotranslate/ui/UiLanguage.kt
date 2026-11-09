package com.ymagenuz.snipdotranslate.ui

import android.app.LocaleManager
import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.os.LocaleList
import com.ymagenuz.snipdotranslate.data.UI_LANGUAGE_ENGLISH
import java.util.Locale

/**
 * Applies the app's own interface language. It's English by default, as on the desktop,
 * and never changes what is sent to the model. The choice needs a synchronous copy
 * because a window's language must be known before anything is drawn.
 */
object UiLanguage {
    private const val PREFERENCES = "ui_language"
    private const val KEY = "code"

    fun wrap(base: Context): Context {
        val code = base.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).getString(KEY, null)
            ?: UI_LANGUAGE_ENGLISH
        val configuration = Configuration(base.resources.configuration)
        configuration.setLocale(Locale.forLanguageTag(code))
        return base.createConfigurationContext(configuration)
    }

    fun save(context: Context, code: String) {
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).edit().putString(KEY, code).apply()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // Also tells Android, which localises the tile labels and the system's language page.
            context.getSystemService(LocaleManager::class.java).applicationLocales = LocaleList.forLanguageTags(code)
        }
    }
}
