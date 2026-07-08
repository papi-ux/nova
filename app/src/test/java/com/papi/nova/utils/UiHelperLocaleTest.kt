package com.papi.nova.utils

import com.papi.nova.preferences.PreferenceConfiguration
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Test

class UiHelperLocaleTest {
    @Test
    fun selectedLanguageOverridesSystemLocale() {
        val systemLocale = Locale.forLanguageTag("sv-SE")

        val resolved = UiHelper.resolveLocaleForTests("en", systemLocale)

        assertEquals(Locale.forLanguageTag("en"), resolved)
    }

    @Test
    fun defaultLanguageUsesSystemLocale() {
        val systemLocale = Locale.forLanguageTag("sv-SE")

        val resolved = UiHelper.resolveLocaleForTests(
            PreferenceConfiguration.DEFAULT_LANGUAGE,
            systemLocale,
        )

        assertEquals(systemLocale, resolved)
    }

    @Test
    fun dashedLanguageTagsRemainStable() {
        val systemLocale = Locale.forLanguageTag("sv-SE")

        val resolved = UiHelper.resolveLocaleForTests("pt-BR", systemLocale)

        assertEquals("pt-BR", resolved.toLanguageTag())
    }
}
