package com.papi.nova.preferences

import android.widget.TextView
import android.content.pm.ActivityInfo
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import com.papi.nova.BuildConfig
import com.papi.nova.PcView
import com.papi.nova.R
import com.papi.nova.ui.NovaLibraryActivity
import com.papi.nova.ui.NovaThemeManager
import com.papi.nova.ui.panel.NovaCommonPage
import com.papi.nova.ui.panel.NovaPanelState
import com.papi.nova.ui.panel.NovaScrim
import com.papi.nova.ui.panel.NovaSurfacesLayer
import com.papi.nova.ui.panel.setPanelContent
import org.junit.Assert.*
import org.junit.Rule
import org.junit.rules.ExternalResource
import org.junit.rules.RuleChain
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** Actual variant metadata reaches its existing app consumers, without a display-only rename. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "w412dp-h915dp-port")
class NovaBetaVersionLabelTest {
    private val compose = createAndroidComposeRule<ComponentActivity>()
    // ComponentActivity is normally declared by the Debug test manifest. The real Beta variant
    // intentionally does not ship that test-only declaration, so register the fixture in its PM.
    @get:Rule val rules: RuleChain = RuleChain.outerRule(object : ExternalResource() {
        override fun before() {
            val context = ApplicationProvider.getApplicationContext<android.content.Context>()
            shadowOf(context.packageManager).addOrUpdateActivity(ActivityInfo().apply {
                name = ComponentActivity::class.java.name
                packageName = context.packageName
                applicationInfo = context.applicationInfo
                exported = true
            })
        }
    }).around(compose)

    // The evidence-only Gradle init hook supplies fixed independent expectations for numbered
    // suffix/release runs. Normal Debug and unnumbered Beta runs need no override.
    private val expectedName: String get() = System.getProperty("nova.expected.versionName")
        ?: if (BuildConfig.BUILD_TYPE == "preRelease") "1.4.14-beta" else "1.4.14"
    private val expectedCode: Int get() = System.getProperty("nova.expected.versionCode")?.toInt()
        ?: if (BuildConfig.BUILD_TYPE == "preRelease") 5500 else 55
    private val expectedLabel: String get() = "Nova $expectedName ($expectedCode)"

    @Test fun compiledMetadataKeepsTheBetaIdentityAndCorrectVersionCode() {
        assertEquals(expectedName, BuildConfig.VERSION_NAME)
        assertEquals(expectedCode, BuildConfig.VERSION_CODE)
        if (BuildConfig.BUILD_TYPE == "preRelease") {
            assertEquals("com.papi.nova.pre", BuildConfig.APPLICATION_ID)
            assertEquals("Nova Beta", compose.activity.getString(R.string.app_label))
        } else {
            assertFalse("stable/debug must not acquire the Beta package", BuildConfig.APPLICATION_ID.endsWith(".pre"))
        }
    }

    @Test fun currentAndUpdateLabelsUseTheActualCompiledVersion() {
        assertEquals(expectedLabel, NovaAppVersion.current())
        assertEquals(expectedLabel, NovaUpdateChecker.currentVersionLabel())
    }

    @Test fun theActualSettingsVersionValueUsesTheCompiledLabel() {
        val definitions = NovaSettingDefinitions.load(compose.activity)
        val definition = definitions.require("nova_app_version")
        assertEquals(NovaSettingValue.StringValue(expectedLabel), definition.defaultValue)
        val state = NovaSettingsUiStateFactory.build(definitions, emptyMap(), "category_nova", "version")
        assertEquals(expectedLabel, state.valueLabel(compose.activity, definition))
    }

    @Test fun theRealHostsUpdatePillDisplaysTheCompiledVersion() {
        // Use the real view and updater method without starting pairing, probes or background work.
        val activity = Robolectric.buildActivity(PcView::class.java).get()
        NovaThemeManager.applyTheme(activity)
        activity.setContentView(R.layout.activity_pc_view)
        val method = PcView::class.java.declaredMethods.single {
            it.name == "updateDashboardUpdatePill" && it.parameterCount == 2
        }.apply { isAccessible = true }
        val current = method.parameterTypes[0].enumConstants.single { it.toString() == "CURRENT" }
        method.invoke(activity, current, null)
        assertEquals(activity.getString(R.string.pcview_update_pill_current_version, expectedName),
            activity.findViewById<TextView>(R.id.updateVersionLabel).text.toString())
    }

    @Test fun theRealLibraryAboutPageRendersTheCompiledVersion() {
        val activity = Robolectric.buildActivity(NovaLibraryActivity::class.java).get()
        val page = NovaLibraryActivity::class.java.getDeclaredMethod("aboutNovaPage")
            .apply { isAccessible = true }.invoke(activity) as NovaCommonPage.Notice
        val expected = activity.getString(R.string.nova_system_menu_about_version, expectedLabel)
        assertEquals(expected, page.message)
        val panel = NovaPanelState().apply { open(page) }
        // Draw the same surfaces layer and common Notice renderer as the Library's window.
        // Opening bare panel state does not create or draw a window by itself.
        compose.setPanelContent {
            NovaSurfacesLayer(
                panel = panel,
                states = emptyList(),
                scrim = NovaScrim.None,
                pageContent = {},
                onIdle = {},
            )
        }
        compose.onNodeWithText(expected).assertIsDisplayed()
    }
}
