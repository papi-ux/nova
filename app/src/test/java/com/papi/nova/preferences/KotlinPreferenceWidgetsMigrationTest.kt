package com.papi.nova.preferences

import android.content.Context
import android.os.Bundle
import android.util.AttributeSet
import androidx.test.core.app.ApplicationProvider
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@Config(sdk = [33])
@RunWith(RobolectricTestRunner::class)
class KotlinPreferenceWidgetsMigrationTest {
    @Test
    fun selectedPreferenceWidgetsAreKotlinSources() {
        val names = arrayOf(
            "SeekBarPreference",
            "SmallIconCheckboxPreference",
            "ConfirmDeleteKeyboardPreference",
            "ConfirmDeleteOscPreference",
            "LanguagePreference",
            "WebLauncherPreference",
            "NovaListPreferenceDialogFragment"
        )

        for (name in names) {
            val javaFile = File("src/main/java/com/papi/nova/preferences/$name.java")
            val kotlinFile = File("src/main/java/com/papi/nova/preferences/$name.kt")
            assertFalse("$name should no longer be a Java source", javaFile.exists())
            assertTrue("$name should be migrated to Kotlin", kotlinFile.exists())
        }
    }

    @Test
    fun preferenceWidgetsKeepXmlInflationConstructors() {
        val intType = Int::class.javaPrimitiveType!!

        SeekBarPreference::class.java.getConstructor(Context::class.java, AttributeSet::class.java)

        SmallIconCheckboxPreference::class.java.getConstructor(Context::class.java)
        SmallIconCheckboxPreference::class.java.getConstructor(Context::class.java, AttributeSet::class.java)
        SmallIconCheckboxPreference::class.java.getConstructor(Context::class.java, AttributeSet::class.java, intType)
        SmallIconCheckboxPreference::class.java.getConstructor(
            Context::class.java,
            AttributeSet::class.java,
            intType,
            intType
        )

        ConfirmDeleteKeyboardPreference::class.java.getConstructor(Context::class.java)
        ConfirmDeleteKeyboardPreference::class.java.getConstructor(Context::class.java, AttributeSet::class.java)
        ConfirmDeleteKeyboardPreference::class.java.getConstructor(Context::class.java, AttributeSet::class.java, intType)
        ConfirmDeleteKeyboardPreference::class.java.getConstructor(
            Context::class.java,
            AttributeSet::class.java,
            intType,
            intType
        )

        ConfirmDeleteOscPreference::class.java.getConstructor(Context::class.java)
        ConfirmDeleteOscPreference::class.java.getConstructor(Context::class.java, AttributeSet::class.java)
        ConfirmDeleteOscPreference::class.java.getConstructor(Context::class.java, AttributeSet::class.java, intType)
        ConfirmDeleteOscPreference::class.java.getConstructor(Context::class.java, AttributeSet::class.java, intType, intType)

        LanguagePreference::class.java.getConstructor(Context::class.java)
        LanguagePreference::class.java.getConstructor(Context::class.java, AttributeSet::class.java)
        LanguagePreference::class.java.getConstructor(Context::class.java, AttributeSet::class.java, intType)
        LanguagePreference::class.java.getConstructor(Context::class.java, AttributeSet::class.java, intType, intType)

        WebLauncherPreference::class.java.getConstructor(Context::class.java, AttributeSet::class.java)
        WebLauncherPreference::class.java.getConstructor(Context::class.java, AttributeSet::class.java, intType)
        WebLauncherPreference::class.java.getConstructor(Context::class.java, AttributeSet::class.java, intType, intType)
    }

    @Test
    fun dialogFragmentFactoriesKeepPreferenceKeyArgument() {
        val oscArgs: Bundle = ConfirmDeleteOscPreference.DialogFragmentCompat
            .newInstance("option_reset_osc_preference")
            .arguments!!
        val keyboardArgs: Bundle = ConfirmDeleteKeyboardPreference.DialogFragmentCompat
            .newInstance("option_reset_keyboard_preference")
            .arguments!!
        val listArgs: Bundle = NovaListPreferenceDialogFragment
            .newInstance("list_languages")
            .arguments!!

        assertEquals("option_reset_osc_preference", oscArgs.getString("key"))
        assertEquals("option_reset_keyboard_preference", keyboardArgs.getString("key"))
        assertEquals("list_languages", listArgs.getString("key"))
    }

    @Test
    fun legacySeekBarDialogIsRecreatedSoOpacityTransitionsNeverReuseStaleChrome() {
        val source = File("src/main/java/com/papi/nova/preferences/SeekBarPreference.kt").readText()

        assertTrue(source.contains("createdDialog.setOnDismissListener"))
        assertTrue(source.contains("dialog = null"))
        assertTrue(source.contains("seekBar = null"))
        assertTrue(source.contains("valueText = null"))
        assertTrue(source.contains("NovaSheetChrome.applyMenuOpacityToLegacyAlert(createdDialog)"))
    }

    @Test
    fun webLauncherPreferenceStillRequiresUrlAttribute() {
        val context = ApplicationProvider.getApplicationContext<Context>()

        assertThrows(IllegalStateException::class.java) {
            WebLauncherPreference(context, null)
        }
    }
}
