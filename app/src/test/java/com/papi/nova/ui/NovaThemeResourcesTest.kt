package com.papi.nova.ui

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.papi.nova.R
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class NovaThemeResourcesTest {
    @Test
    fun themeArraysExposeMiamiInPredictableOrder() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val names = context.resources.getStringArray(R.array.nova_theme_names).toList()
        val values = context.resources.getStringArray(R.array.nova_theme_values).toList()

        assertEquals(names.size, values.size)
        assertEquals(
            listOf("polaris", "portable_chrome", "oled", "miami", "high_contrast", "material_you"),
            values
        )
        assertEquals("PSP Chrome / Portable Chrome", names[values.indexOf("portable_chrome")])
        assertEquals("Miami Nebula", names[values.indexOf("miami")])
    }

    @Test
    fun pspPortableChromeIsASelectableThemeValueAndLabel() {
        val context = ApplicationProvider.getApplicationContext<Context>()

        NovaThemeManager.setTheme(context, NovaThemeManager.THEME_PORTABLE_CHROME)

        assertEquals(NovaThemeManager.THEME_PORTABLE_CHROME, NovaThemeManager.getTheme(context))
        assertEquals("PSP Chrome / Portable Chrome", NovaThemeManager.getThemeLabel(context))
    }

    @Test
    fun portableChromeAliasesToDefaultThemeAndBaseAccentAvoidsPurpleTaskMetadata() {
        val colors = File("src/main/res/values/colors_nova.xml").readText()
        val manager = File("src/main/java/com/papi/nova/ui/NovaThemeManager.kt").readText()
        val styles = File("src/main/res/values/styles.xml").readText()
        val stylesV14 = File("src/main/res/values-v14/styles.xml").readText()

        assertTrue(manager.contains("THEME_PORTABLE_CHROME"))
        assertTrue(manager.contains("portable_chrome"))
        assertTrue(manager.contains("psp"))
        assertTrue(colors.contains("<color name=\"nova_portable_accent\">#FF7FA38D</color>"))
        assertTrue(colors.contains("<color name=\"nova_accent\">@color/nova_polaris_accent</color>"))
        assertTrue(!colors.lowercase().contains("7c73ff"))
    }


    @Test
    fun portableChromeHasDedicatedThemeStylesAndAccentTokens() {
        val colors = File("src/main/res/values/colors_nova.xml").readText()
        val styles = File("src/main/res/values/styles.xml").readText()

        assertTrue(colors.contains("<color name=\"nova_portable_accent\">#FF7FA38D</color>"))
        assertTrue(colors.contains("<color name=\"nova_polaris_accent\">"))
        assertTrue(styles.contains("AppTheme.PortableChrome"))
        assertTrue(styles.contains("SettingsTheme.PortableChrome"))
        assertTrue(styles.contains("@color/nova_portable_accent"))
    }

    @Test
    fun pcViewThemePickerExposesPspPortableChrome() {
        val source = File("src/main/java/com/papi/nova/PcView.kt").readText()
        val picker = source.substringAfter("private fun showThemePicker(")
            .substringBefore("private fun applyThemeSelection")

        assertTrue(picker.contains("NovaThemeManager.THEME_PORTABLE_CHROME"))
        assertTrue(
            "Portable Chrome should sit between Polaris and OLED in the dashboard picker",
            picker.indexOf("NovaThemeManager.THEME_POLARIS") < picker.indexOf("NovaThemeManager.THEME_PORTABLE_CHROME") &&
                picker.indexOf("NovaThemeManager.THEME_PORTABLE_CHROME") < picker.indexOf("NovaThemeManager.THEME_OLED")
        )
    }


    @Test
    fun pcViewThemePickerUsesCustomDpadFocusedRows() {
        val source = File("src/main/java/com/papi/nova/PcView.kt").readText()
        val picker = source.substringAfter("private fun showThemePicker(")
            .substringBefore("private fun applyThemeSelection")
        val strings = File("src/main/res/values/strings.xml").readText()

        assertFalse("D-pad users need a custom focusable sheet, not Android's square single-choice list", picker.contains("setSingleChoiceItems"))
        assertTrue("theme picker should be rendered as a custom bottom sheet", picker.contains("BottomSheetDialog"))
        assertTrue("theme picker rows must be explicit focusable views", source.contains("createThemePickerRow("))
        assertTrue("theme picker should expose a focused-row helper label", source.contains("themePickerFocusLabel"))
        assertTrue("theme picker rows must update visible state on D-pad focus", source.contains("setOnFocusChangeListener"))
        assertTrue("theme picker should use a compact two-column grid so Material You is not pushed below the Retroid landscape fold", picker.contains("themes.chunked(2)"))
        assertTrue("Material You should remain part of the dashboard picker when the device supports it", picker.contains("NovaThemeManager.THEME_MATERIAL_YOU"))
        assertFalse("theme picker rows must not use the solid server-card foreground that obscures focused text", picker.contains("nova_card_focus_frame"))
        assertFalse("non-selected theme rows should not repeat an obvious Press A badge", picker.contains("pcview_theme_picker_apply_badge"))
        assertTrue("theme picker should request focus for the current/first row", source.contains("requestFocus()"))
        assertTrue("Chrome needs to be eye-scan visible in the picker title", strings.contains("PSP Chrome / Portable Chrome"))
        assertTrue("the exact old alias should remain visible as a subtitle", strings.contains("PSP / Portable Chrome profile"))
        assertTrue("the picker should tell handheld users that D-pad focus is live", strings.contains("D-pad"))
        assertFalse("the picker copy should not repeat Press A after removing per-row action badges", strings.contains("Press A"))
    }

    @Test
    fun serverSelectionAccentsUseThemeAttributesInsteadOfGlobalGreenResource() {
        val portrait = File("src/main/res/layout/activity_pc_view.xml").readText()
        val landscape = File("src/main/res/layout-land/activity_pc_view.xml").readText()
        val styles = File("src/main/res/values/styles.xml").readText()

        assertTrue(portrait.contains("app:strokeColor=\"?attr/colorAccent\""))
        assertTrue(portrait.contains("android:textColor=\"?attr/colorAccent\""))
        assertTrue(landscape.contains("app:strokeColor=\"?attr/colorAccent\""))
        assertTrue(landscape.contains("android:textColor=\"?attr/colorAccent\""))
        assertTrue(styles.contains("<item name=\"chipBackgroundColor\">@color/nova_chip_bg_selector</item>"))
        assertTrue(styles.contains("<item name=\"chipStrokeColor\">@color/nova_focus_stroke_selector</item>"))
        assertTrue(File("src/main/res/color/nova_focus_stroke_selector.xml").readText().contains("?attr/colorAccent"))
        assertTrue(File("src/main/res/color/nova_chip_bg_selector.xml").readText().contains("?attr/colorControlHighlight"))
    }


    @Test
    fun bottomSheetsShareNovaSheetChromeInsteadOfOneOffSurfaces() {
        val sheetChrome = File("src/main/java/com/papi/nova/ui/NovaSheetChrome.kt").readText()
        val pcView = File("src/main/java/com/papi/nova/PcView.kt").readText()
        val appView = File("src/main/java/com/papi/nova/AppView.kt").readText()
        val gameDetail = File("src/main/java/com/papi/nova/ui/NovaGameDetailSheet.kt").readText()
        val polarisSync = File("src/main/java/com/papi/nova/ui/NovaPolarisSyncSheet.kt").readText()
        val library = File("src/main/java/com/papi/nova/ui/NovaLibraryActivity.kt").readText()
        val contextSheet = File("src/main/res/layout/nova_app_context_sheet.xml").readText()

        assertTrue("native sheets should use a single chrome helper", sheetChrome.contains("object NovaSheetChrome"))
        assertTrue("sheet chrome should use theme-specific dialog surface colors", sheetChrome.contains("NovaThemeManager.getDialogBackgroundColor"))
        assertTrue("sheet chrome should use theme-specific accents for the handle/stroke", sheetChrome.contains("NovaThemeManager.getAccentColor"))
        assertTrue("sheet chrome should expose shared top corner radius", sheetChrome.contains("SHEET_CORNER_RADIUS_DP"))
        assertTrue("sheet chrome should expose shared landscape width policy", sheetChrome.contains("LANDSCAPE_WIDTH_FRACTION"))
        assertTrue("theme picker should use shared sheet chrome", pcView.contains("NovaSheetChrome.applyBottomSheetChrome(dialog"))
        assertTrue("pc context menu should use shared sheet chrome", pcView.contains("NovaSheetChrome.applyBottomSheetChrome(sheet"))
        assertTrue("app context menu should use shared sheet chrome", appView.contains("NovaSheetChrome.applyBottomSheetChrome(sheet"))
        assertTrue("game detail sheet should use shared sheet chrome", gameDetail.contains("NovaSheetChrome.applyBottomSheetChrome(bottomSheetDialog"))
        assertTrue("Polaris sync sheet should use shared sheet chrome", polarisSync.contains("NovaSheetChrome.applyBottomSheetChrome(bottomSheetDialog"))
        assertTrue("Compose library sheets should use the same shared radius token", library.contains("NovaSheetChrome.SHEET_CORNER_RADIUS_DP"))
        assertTrue("Compose library sheets should use a common themed scrim alpha", library.contains("NovaSheetChrome.SCRIM_ALPHA"))
        assertFalse("context sheet XML must not hardcode the Polaris sheet drawable", contextSheet.contains("@drawable/nova_sheet_bg"))
        assertFalse("context sheet title must not hardcode Polaris ice text", contextSheet.contains("@color/nova_ice"))
    }

    @Test
    fun sheetChromeContractPreventsClippedOrStaticThemePickerSurfaces() {
        val pcView = File("src/main/java/com/papi/nova/PcView.kt").readText()
        val picker = pcView.substringAfter("private fun showThemePicker(")
            .substringBefore("private fun buildThemePickerThemes")
        val sheetChrome = File("src/main/java/com/papi/nova/ui/NovaSheetChrome.kt").readText()

        assertTrue("theme picker content should be wrapped in a themed rounded sheet container", picker.contains("NovaSheetChrome.createSheetContainer"))
        assertFalse("theme picker must not draw a square setBackgroundColor surface inside a rounded sheet", picker.contains("setBackgroundColor(dialogSurface)"))
        assertTrue("theme picker rows need inset margins so focus strokes cannot look clipped", picker.contains("THEME_PICKER_GRID_GAP_DP"))
        assertTrue("sheet chrome must draw a stroke around sheet surfaces for clean themed edges", sheetChrome.contains("setStroke"))
        assertTrue("sheet chrome should include theme-specific light/dark stroke blending", sheetChrome.contains("getSheetStrokeColor"))
    }


    @Test
    fun legacyFocusableDrawablesUseThemeAttrsInsteadOfStaticPolarisAccent() {
        val drawableFiles = listOf(
            "src/main/res/drawable/nova_dialog_choice_bg.xml",
            "src/main/res/drawable/nova_chip_default.xml",
            "src/main/res/drawable/nova_chip_selected.xml",
            "src/main/res/drawable/nova_featured_action_bg.xml",
            "src/main/res/drawable/nova_card_focus_ring.xml",
            "src/main/res/drawable/nova_server_row_focus_ring.xml"
        )
        drawableFiles.forEach { path ->
            val xml = File(path).readText()
            assertFalse("$path must not hardcode old Polaris violet", xml.contains("7C73FF", ignoreCase = true))
            assertFalse("$path must not bind reusable focus chrome to global nova_accent", xml.contains("@color/nova_accent"))
        }
        assertTrue(File("src/main/res/drawable/nova_dialog_choice_bg.xml").readText().contains("?attr/colorAccent"))
        assertTrue(File("src/main/res/drawable/nova_dialog_choice_bg.xml").readText().contains("?attr/colorControlHighlight"))
        assertTrue(File("src/main/res/drawable/nova_server_row_focus_ring.xml").readText().contains("?attr/colorSurface"))
    }


    @Test
    fun sheetChromeUsesSharedTranslucentGlassForNovaHudFriendlyDrawers() {
        val sheetChrome = File("src/main/java/com/papi/nova/ui/NovaSheetChrome.kt").readText()
        val composeTheme = File("src/main/java/com/papi/nova/ui/compose/NovaComposeTheme.kt").readText()
        val gameDetail = File("src/main/java/com/papi/nova/ui/NovaGameDetailSheet.kt").readText()

        assertTrue("native sheet chrome must expose a named glass alpha contract", sheetChrome.contains("SHEET_GLASS_ALPHA"))
        assertTrue("native sheet backgrounds should alpha the active theme surface instead of using opaque slabs", sheetChrome.contains("ColorUtils.setAlphaComponent") && sheetChrome.contains("getSheetGlassAlpha"))
        assertTrue("high contrast can remain more opaque for readability", sheetChrome.contains("HIGH_CONTRAST_SHEET_GLASS_ALPHA"))
        assertTrue("shared scrim should be light enough for NovaHUD/game context to remain visible", sheetChrome.contains("const val SCRIM_ALPHA = 0.22f"))
        assertTrue("default action row state should remain transparent glass, not an opaque mini slab", sheetChrome.contains("fillAccentBlend = 0f") && sheetChrome.contains("Color.TRANSPARENT"))
        assertTrue("Compose library surfaces should reuse shared glass alpha language", composeTheme.contains("NovaSheetChrome.SHEET_GLASS_ALPHA"))
        assertTrue("game detail Compose drawer should reuse the shared native sheet radius token", gameDetail.contains("NovaSheetChrome.SHEET_CORNER_RADIUS_DP.dp"))
    }


    @Test
    fun sessionQuitConfirmationUsesNovaGlassBottomSheetInsteadOfRawAlertDialog() {
        val sheetChrome = File("src/main/java/com/papi/nova/ui/NovaSheetChrome.kt").readText()
        val game = File("src/main/java/com/papi/nova/Game.kt").readText()
        val quitBody = game.substringAfter("fun quit() {").substringBefore("override fun showGameMenu")

        assertTrue("shared chrome should still expose AlertDialog styling for remaining legacy session popups", sheetChrome.contains("applyAlertDialogChrome"))
        assertTrue("quit confirmation should be rebuilt as a Nova bottom sheet so it shares drawer/HUD glass chrome", quitBody.contains("BottomSheetDialog"))
        assertTrue("quit confirmation should build its own themed glass sheet container", quitBody.contains("NovaSheetChrome.createSheetContainer"))
        assertTrue("quit confirmation should apply shared bottom-sheet chrome", quitBody.contains("NovaSheetChrome.applyBottomSheetChrome(sheet"))
        assertTrue("quit confirmation should style custom action rows through shared sheet chrome", quitBody.contains("NovaSheetChrome.styleSheetAction"))
        assertFalse("quit confirmation should not use a raw AlertDialog shell", quitBody.contains("AlertDialog.Builder"))
        assertFalse("quit confirmation should not use platform dialog buttons", quitBody.contains("setPositiveButton") || quitBody.contains("setNegativeButton"))
        assertTrue("quit confirmation should use Nova-themed session action copy", game.contains("R.string.game_dialog_action_end_session") && game.contains("R.string.game_dialog_action_stay_in_game"))
        assertFalse("quit confirmation should drop the old generic streaming button labels", game.contains("game_dialog_action_end_stream") || game.contains("game_dialog_action_keep_streaming"))
    }

    @Test
    fun sessionProgressAndQuitCopyMatchesNovaSessionSemantics() {
        val strings = File("src/main/res/values/strings.xml").readText()

        assertTrue("connection spinner title should say stream, not old connection/session language", strings.contains("""<string name="conn_establishing_title">Starting stream</string>"""))
        assertTrue("connection spinner message should mention video/audio/input readiness", strings.contains("Preparing video, audio, and controller input"))
        assertTrue("quit title should be Nova-session language, not raw stream-control wording", strings.contains("""<string name="game_dialog_title_quit_confirm">End this Nova session?</string>"""))
        assertTrue("quit message should distinguish ending the host app from disconnect/resume", strings.contains("This closes the host app and the resumable stream"))
        assertTrue("quit destructive action should say End session", strings.contains("""<string name="game_dialog_action_end_session">End session</string>"""))
        assertTrue("quit safe action should say Stay in game", strings.contains("""<string name="game_dialog_action_stay_in_game">Stay in game</string>"""))
        assertFalse("old Keep streaming / End stream labels should not remain in the quit dialog copy", strings.contains("Keep streaming") || strings.contains("End stream and quit app?") || strings.contains("game_dialog_action_end_stream"))
    }

    @Test
    fun gameStartupUsesSessionProgressOverlayInsteadOfLegacySpinnerPopup() {
        val game = File("src/main/java/com/papi/nova/Game.kt").readText()
        val startup = game.substringAfter("setContentView(R.layout.activity_game)").substringBefore("appName =")

        assertTrue("Game startup should create the verbose Nova session progress overlay immediately", startup.contains("SessionProgressOverlay(this)"))
        assertTrue("Game startup should show the verbose Nova session progress overlay immediately", startup.contains("novaProgressOverlay?.show()"))
        assertFalse("Game startup must not show the legacy Starting stream spinner over the verbose progress overlay", startup.contains("SpinnerDialog.displayDialog"))
        assertFalse("Startup retry path must not assume a legacy spinner exists", game.contains("spinner!!.setMessage(getResources().getString(R.string.unlocking_or_starting))"))
        assertTrue("Startup retry path should report host readiness through the verbose progress overlay", game.contains("novaProgressOverlay?.updateState(\"unlocking_or_starting\""))
    }
    @Test
    fun legacyGameMenuUsesNovaGlassBottomSheetInsteadOfRawAlertList() {
        val source = File("src/main/java/com/papi/nova/GameMenu.kt").readText()
        val showMenuDialog = source.substringAfter("private fun showMenuDialog(").substringBefore("private fun showSpecialKeysMenu")

        assertTrue("GameMenu should render its in-stream menu as a Material bottom sheet", source.contains("BottomSheetDialog"))
        assertTrue("GameMenu should use shared Nova glass sheet containers", showMenuDialog.contains("NovaSheetChrome.createSheetContainer"))
        assertTrue("GameMenu should apply shared Nova bottom-sheet chrome", showMenuDialog.contains("NovaSheetChrome.applyBottomSheetChrome"))
        assertTrue("GameMenu title should use shared sheet title styling", showMenuDialog.contains("NovaSheetChrome.styleSheetTitle"))
        assertTrue("GameMenu rows should use shared focusable sheet action styling", showMenuDialog.contains("NovaSheetChrome.styleSheetAction"))
        assertFalse("GameMenu list must not use raw AlertDialog.Builder for the menu shell", showMenuDialog.contains("AlertDialog.Builder"))
        assertFalse("GameMenu list must not use Android simple_list_item_1 rows", showMenuDialog.contains("android.R.layout.simple_list_item_1"))
        assertFalse("GameMenu list must not use ArrayAdapter-backed legacy rows", showMenuDialog.contains("ArrayAdapter"))
        assertTrue("server-command empty dialog should still receive Nova alert chrome", source.contains("NovaSheetChrome.applyAlertDialogChrome(serverCommandDialog"))
    }


    @Test
    fun noAvcDecoderErrorDismissesSessionProgressOverlayBeforeDialog() {
        val game = File("src/main/java/com/papi/nova/Game.kt").readText()
        val noAvcBlock = game.substringAfter("if (!decoderRenderer!!.isAvcSupported)").substringBefore("return")

        assertTrue("No-AVC decoder error path should dismiss the verbose session progress overlay before showing the fatal dialog", noAvcBlock.contains("novaProgressOverlay?.dismiss()"))
        assertTrue("No-AVC decoder error path should still dismiss the legacy spinner for compatibility", noAvcBlock.contains("spinner!!.dismiss()"))
        assertTrue("No-AVC decoder error path should show the hardware H.264 support dialog after cleanup", noAvcBlock.contains("Dialog.displayDialog"))
    }

    @Test
    fun sheetActionRowsExposeDpadFocusedAndPressedFeedback() {
        val sheetChrome = File("src/main/java/com/papi/nova/ui/NovaSheetChrome.kt").readText()

        assertTrue("sheet action rows should use a stateful background so D-pad focus is visible", sheetChrome.contains("StateListDrawable"))
        assertTrue("sheet action rows should define a focused state", sheetChrome.contains("android.R.attr.state_focused"))
        assertTrue("sheet action rows should define a pressed state", sheetChrome.contains("android.R.attr.state_pressed"))
        assertTrue("focused/pressed rows should blend with the active theme accent", sheetChrome.contains("createActionStateBackground") && sheetChrome.contains("NovaThemeManager.getAccentColor"))
    }
}
