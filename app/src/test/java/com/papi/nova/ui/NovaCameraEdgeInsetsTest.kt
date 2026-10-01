package com.papi.nova.ui

import android.content.Context
import android.graphics.Insets
import android.graphics.Rect
import android.view.DisplayCutout
import android.view.View
import android.view.WindowInsets
import androidx.preference.PreferenceManager
import androidx.test.core.app.ApplicationProvider
import com.papi.nova.TestLogSuppressor
import com.papi.nova.preferences.NovaSettingsFeatureFlags
import com.papi.nova.preferences.StreamSettings
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The production Settings Activity must not turn one camera into a screen-wide gutter. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class NovaCameraEdgeInsetsTest {
    private fun settingsWithCamera(safe: Insets, camera: Rect, check: (View) -> Unit) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        prefs.edit().putBoolean(NovaSystemBars.KEY_HIDE_SYSTEM_BARS, true).commit()
        NovaSettingsFeatureFlags.setComposeSettingsEnabled(context, true)
        val controller = Robolectric.buildActivity(StreamSettings::class.java).setup()
        try {
            val root = controller.get().findViewById<View>(android.R.id.content)
            val cutout = DisplayCutout(safe, camera.takeIf { safe.left > 0 },
                camera.takeIf { safe.top > 0 }, camera.takeIf { safe.right > 0 },
                camera.takeIf { safe.bottom > 0 })
            root.dispatchApplyWindowInsets(WindowInsets.Builder()
                .setDisplayCutout(cutout)
                .setInsets(WindowInsets.Type.displayCutout(), safe)
                .setVisible(WindowInsets.Type.displayCutout(), true)
                .setInsets(WindowInsets.Type.systemBars(), Insets.NONE)
                .setVisible(WindowInsets.Type.systemBars(), false)
                .build())
            check(root)
        } finally {
            controller.pause().stop().destroy()
            prefs.edit().clear().commit()
        }
    }

    @Test fun landscapeCameraDoesNotPadTheWholeSettingsBackground() =
        settingsWithCamera(Insets.of(80, 0, 0, 0), Rect(0, 180, 80, 280)) {
            assertEquals("one side camera protects nearby controls, not the entire Settings surface", 0, it.paddingLeft)
        }

    @Test fun portraitCameraDoesNotPadTheWholeSettingsBackground() =
        settingsWithCamera(Insets.of(0, 80, 0, 0), Rect(186, 0, 226, 80)) {
            assertEquals("a central camera must not reserve a full-width band above the background", 0, it.paddingTop)
        }

    @Test fun libraryAndMenuSurfacesDoNotConsumeWholeSideCameraInsets() {
        val library = File("src/main/java/com/papi/nova/ui/NovaLibraryActivity.kt").readText()
        val frame = File("src/main/java/com/papi/nova/ui/panel/NovaPanelFrame.kt").readText()
        assertFalse("the Library content must not reserve the entire camera side", library.contains(".windowInsetsPadding(WindowInsets.safeDrawing)"))
        assertFalse("the menu surface must not reserve the entire camera side", frame.contains(".windowInsetsPadding(WindowInsets.safeDrawing.only("))
    }

    companion object {
        @JvmStatic @BeforeClass fun suppressLogs() { TestLogSuppressor.install() }
    }
}
