package com.yervant.huntmem.ui

import android.content.Context
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import java.util.Locale
import androidx.core.content.edit

object LocaleManager {

    private const val PREFS_NAME = "huntmem_locale_prefs"
    private const val KEY_LANGUAGE_TAG = "app_language_tag"

    data class Language(val code: String, val nativeName: String)

    fun getSupportedLanguages(): List<Language> {
        return listOf(
            Language("en", "English"),
            Language("pt-BR", "Português (Brasil)"),
        )
    }

    fun setLocale(context: Context, languageTag: String) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit { putString(KEY_LANGUAGE_TAG, languageTag) }

        applyLocale(languageTag)
    }

    fun initialize(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val languageTag = prefs.getString(KEY_LANGUAGE_TAG, "") ?: ""
        applyLocale(languageTag)
    }

    private fun applyLocale(languageTag: String) {
        val localeList = if (languageTag.isNotEmpty()) {
            val locale = Locale.forLanguageTag(languageTag)
            LocaleListCompat.create(locale)
        } else {
            LocaleListCompat.getEmptyLocaleList()
        }

        AppCompatDelegate.setApplicationLocales(localeList)
    }
}