/*
 * HuntMemory - Process Memory Editor & Scanner for Android
 * Copyright (C) 2026 Yervant7
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.yervant.huntmem.ui

import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import java.util.Locale
import androidx.core.content.edit
import com.yervant.huntmem.HuntMemApplication

object LocaleManager {

    private const val KEY_LANGUAGE_TAG = "app_language_tag"

    data class Language(val code: String, val nativeName: String)

    fun getSupportedLanguages(): List<Language> {
        return listOf(
            Language("en", "English"),
            Language("pt-BR", "Português (Brasil)"),
            Language("de", "Deutsch"),
            Language("es", "Español"),
            Language("fr", "Français"),
            Language("hi", "हिन्दी"),
            Language("ja", "日本語"),
            Language("ko", "한국어"),
            Language("pt", "Português"),
            Language("ru", "Русский"),
            Language("zh-CN", "中文 (简体)"),
        )
    }

    fun setLocale(languageTag: String) {
        val prefs = HuntMemApplication.sharedPreferences
        prefs.edit { putString(KEY_LANGUAGE_TAG, languageTag) }

        applyLocale(languageTag)
    }

    fun initialize() {
        val prefs = HuntMemApplication.sharedPreferences
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
