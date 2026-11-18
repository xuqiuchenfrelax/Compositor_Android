package com.pojia.compositor.mobile

import android.app.Activity
import android.app.LocaleManager
import android.content.Context
import android.content.ContextWrapper
import android.content.res.Configuration
import android.os.Build
import android.os.LocaleList

internal enum class AppLanguage(val tag: String, val label: Int) {
    SYSTEM("", R.string.language_system), ENGLISH("en", R.string.language_english),
    SIMPLIFIED_CHINESE("zh-CN", R.string.language_simplified_chinese)
}

/** Android 13 uses the system per-app preference; Android 12 keeps an equivalent activity override. */
internal object AppLanguages {
    private const val PREFERENCES = "compositor_language"
    private const val LANGUAGE = "language_tag"

    fun current(context: Context): AppLanguage {
        val tag = if (Build.VERSION.SDK_INT >= 33) {
            context.getSystemService(LocaleManager::class.java).applicationLocales.toLanguageTags()
                .substringBefore(',')
        } else context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).getString(LANGUAGE, "").orEmpty()
        return AppLanguage.entries.firstOrNull { it.tag.equals(tag, ignoreCase = true) } ?: AppLanguage.SYSTEM
    }

    fun wrap(context: Context): Context {
        if (Build.VERSION.SDK_INT >= 33) return context
        val language = current(context)
        if (language == AppLanguage.SYSTEM) return context
        val locales = LocaleList.forLanguageTags(language.tag)
        val configuration = Configuration(context.resources.configuration).apply {
            setLocales(locales)
            setLayoutDirection(locales[0])
        }
        return context.createConfigurationContext(configuration)
    }

    fun set(context: Context, language: AppLanguage) {
        if (current(context) == language) return
        if (Build.VERSION.SDK_INT >= 33) {
            context.getSystemService(LocaleManager::class.java).applicationLocales =
                LocaleList.forLanguageTags(language.tag)
        } else {
            context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).edit()
                .putString(LANGUAGE, language.tag).apply()
            activity(context)?.recreate()
        }
    }

    private fun activity(context: Context): Activity? = when (context) {
        is Activity -> context
        is ContextWrapper -> if (context.baseContext !== context) activity(context.baseContext) else null
        else -> null
    }
}
