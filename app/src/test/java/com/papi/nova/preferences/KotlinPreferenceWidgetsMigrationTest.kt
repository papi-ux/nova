package com.papi.nova.preferences

import android.content.Context
import android.util.AttributeSet
import androidx.test.core.app.ApplicationProvider
import java.io.File
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
            "ConfirmDeleteOscPreference",
            "LanguagePreference",
            "WebLauncherPreference",
        )

        for (name in names) {
            val javaFile = File("src/main/java/com/papi/nova/preferences/$name.java")
            val kotlinFile = File("src/main/java/com/papi/nova/preferences/$name.kt")
            assertFalse("$name should no longer be a Java source", javaFile.exists())
            assertTrue("$name should be migrated to Kotlin", kotlinFile.exists())
        }
        // Deleted rather than migrated: the list dialog became a Choice page, and the keyboard
        // reset had no preference in any XML.
        for (name in arrayOf("ConfirmDeleteKeyboardPreference", "NovaListPreferenceDialogFragment")) {
            assertFalse("$name is gone", File("src/main/java/com/papi/nova/preferences/$name.java").exists())
            assertFalse("$name is gone", File("src/main/java/com/papi/nova/preferences/$name.kt").exists())
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

    // Was dialogFragmentFactoriesKeepPreferenceKeyArgument. The legacy dialog fragments are gone;
    // StreamSettings.SettingsFragment.onDisplayPreferenceDialog opens each as a page instead, and
    // NovaLegacySettingsPagesTest drives that route.
    @Test
    fun legacyDialogPreferencesOpenAsPagesThroughOneOverride() {
        val settings = File("src/main/java/com/papi/nova/preferences/StreamSettings.kt").readText()
        val route = settings.substringAfter("override fun onDisplayPreferenceDialog(preference: Preference) {")
            .substringBefore("private fun listChoicePage(")

        assertTrue(route.contains("preference is ConfirmDeleteOscPreference -> resetControlsPage(preference)"))
        assertTrue(route.contains("preference is ListPreference -> listChoicePage(preference)"))
        assertTrue(route.contains("preference is EditTextPreference -> textFormPage(preference)"))
        assertTrue(route.contains("NovaSurfaces.of(activity).open("))
        assertFalse(settings.contains("DialogFragment"))
    }

    // Was legacySeekBarDialogIsRecreatedSoOpacityTransitionsNeverReuseStaleChrome: the dialog was
    // rebuilt on every open so an opacity change never reused stale chrome. Its Slider page is
    // built fresh on every open for the same reason, and is drawn by the panel, not a dialog.
    @Test
    fun legacySeekBarOpensAFreshSliderPageEveryTime() {
        val source = File("src/main/java/com/papi/nova/preferences/SeekBarPreference.kt").readText()
        val show = source.substringAfter("fun showDialog() {").substringBefore("override fun onClick()")

        assertTrue(show.contains("root = sliderPage()"))
        assertTrue(source.contains("internal fun sliderPage(): NovaCommonPage.Slider {"))
        assertFalse(source.contains("AlertDialog"))
        assertFalse(source.contains("setPadding("))
    }

    @Test
    fun webLauncherPreferenceStillRequiresUrlAttribute() {
        val context = ApplicationProvider.getApplicationContext<Context>()

        assertThrows(IllegalStateException::class.java) {
            WebLauncherPreference(context, null)
        }
    }
}
