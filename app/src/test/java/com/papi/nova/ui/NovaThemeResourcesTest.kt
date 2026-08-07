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
        assertEquals("Portable Chrome", names[values.indexOf("portable_chrome")])
        assertEquals("Miami Nebula", names[values.indexOf("miami")])
    }

    @Test
    fun novaThemeSurfaceContractDocumentsThemeAndSmokeRequirements() {
        val contract = File("../docs/nova-theme-surface-contract.md").readText().lowercase()

        listOf(
            "latest available debug nova apk",
            "latest available debug polaris build",
            "portable chrome playstation symbol accents",
            "smoked graphite/dim moonlight grey/silver",
            "flamingo pink as the visible hero accent",
            "purple/violet accents must not",
            "transparent/glass",
            "novahud",
            "drawers, sheets, dialogs",
            "game-detail",
            "material you",
            "no redundant press a badges",
            "current"
        ).forEach { required ->
            assertTrue("Nova theme surface contract should explicitly mention ", contract.contains(required))
        }
    }


    @Test
    fun portableChromeIsASelectableThemeValueAndLabel() {
        val context = ApplicationProvider.getApplicationContext<Context>()

        NovaThemeManager.setTheme(context, NovaThemeManager.THEME_PORTABLE_CHROME)

        assertEquals(NovaThemeManager.THEME_PORTABLE_CHROME, NovaThemeManager.getTheme(context))
        assertEquals("Portable Chrome", NovaThemeManager.getThemeLabel(context))
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
        assertTrue(colors.contains("nova_portable_accent") && colors.contains("#FF2F64B3"))
        assertTrue(colors.contains("<color name=\"nova_accent\">@color/nova_polaris_accent</color>"))

        // The contract bans purple in Portable Chrome, and bans Material's default purple
        // leaking through unthemed surfaces. Neither is the brand accent. This used to be
        // enforced by asserting the hex appeared nowhere in the file at all, which is a
        // proxy broad enough to outlaw Polaris' own colour — so say what is meant instead.
        assertTrue(
            "the Polaris accent is the brand's Medium Purple, style guide 2026 p8",
            colors.contains("<color name=\"nova_polaris_accent\">#FF7C73FF</color>")
        )
        assertTrue(
            "Portable Chrome stays cross-blue; the brand purple must not reach its tokens",
            colors.lines()
                .filter { it.contains("nova_portable") }
                .none { it.lowercase().contains("7c73ff") }
        )
    }


    @Test
    fun portableChromeHasDedicatedThemeStylesAndAccentTokens() {
        val colors = File("src/main/res/values/colors_nova.xml").readText()
        val styles = File("src/main/res/values/styles.xml").readText()

        assertTrue(colors.contains("nova_portable_accent") && colors.contains("#FF2F64B3"))
        assertTrue(colors.contains("<color name=\"nova_polaris_accent\">"))
        assertTrue(styles.contains("AppTheme.PortableChrome"))
        assertTrue(styles.contains("SettingsTheme.PortableChrome"))
        assertTrue(styles.contains("@color/nova_portable_accent"))
    }

    @Test
    fun miamiKeepsFlamingoPinkHeroAccentWithCyanAquaSupport() {
        val colors = File("src/main/res/values/colors_nova.xml").readText()
        val strings = File("src/main/res/values/strings.xml").readText()

        assertTrue(colors.contains("nova_miami_accent") && colors.contains("#FFFF5CAB"))
        assertTrue(colors.contains("nova_miami_accent_surface") && colors.contains("#1AFF5CAB"))
        assertTrue(colors.contains("nova_miami_accent_glow") && colors.contains("#73FF5CAB"))
        assertTrue(colors.contains("nova_miami_water_accent") && colors.contains("#FF47F3FF"))
        assertTrue(colors.contains("nova_miami_water_accent_surface") && colors.contains("#1A47F3FF"))
        assertFalse("Miami must not replace flamingo pink with cyan as the primary accent", colors.lines().any { it.contains("nova_miami_accent") && it.contains("#FF47F3FF") })
        assertTrue(strings.contains("flamingo pink"))
        assertTrue(strings.contains("cyan/aqua"))
    }

    @Test
    fun portableChromeUsesSubtlePlayStationSymbolAccentTokens() {
        val colors = File("src/main/res/values/colors_nova.xml").readText()
        val strings = File("src/main/res/values/strings.xml").readText()

        assertTrue(colors.contains("nova_portable_accent") && colors.contains("#FF2F64B3"))
        assertTrue(colors.contains("nova_portable_cross_accent") && colors.contains("#FF2F64B3"))
        assertTrue(colors.contains("nova_portable_square_accent") && colors.contains("#FF9D679D"))
        assertTrue(colors.contains("nova_portable_circle_accent") && colors.contains("#FFB8575F"))
        assertTrue(colors.contains("nova_portable_triangle_accent") && colors.contains("#FF4F9A67"))
        val oldPortableAccent = "nova_portable_accent" + 34.toChar() + ">#FF7FA38D"
        assertFalse("Portable Chrome primary accent must not stay generic muted green", colors.contains(oldPortableAccent))
        assertTrue(strings.contains("PlayStation-symbol accents"))
    }

    @Test
    fun pcViewThemePickerExposesPortableChrome() {
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
        assertTrue("theme picker sheet should disable parent clipping so rounded selected/focused strokes are not cut off", picker.contains("clipChildren = false") && picker.contains("clipToPadding = false"))
        assertTrue("theme picker grid should reserve top padding for thick focus strokes", picker.contains("setPadding(gridGap, gridGap, gridGap, gridGap)"))
        assertTrue("theme picker cards should reserve compat padding around rounded strokes", picker.contains("useCompatPadding = true"))
        assertFalse("theme picker rows must not use the solid server-card foreground that obscures focused text", picker.contains("nova_card_focus_frame"))
        assertFalse("non-selected theme rows should not repeat an obvious Press A badge", picker.contains("pcview_theme_picker_apply_badge"))
        assertTrue("theme picker should request focus for the current/first row", source.contains("requestFocus()"))
        assertTrue("Portable Chrome should be eye-scan visible as the picker title", strings.contains("Portable Chrome"))
        assertTrue("Portable Chrome subtitle should describe the chrome profile without PSP naming", strings.contains("Smoked graphite handheld chrome profile"))
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
        val gameDetail = File("src/main/java/com/papi/nova/ui/NovaGameDetailActivity.kt").readText() +
            File("src/main/java/com/papi/nova/ui/NovaGameDetailContent.kt").readText() +
            File("src/main/java/com/papi/nova/ui/NovaGameDetailOverview.kt").readText() +
            File("src/main/java/com/papi/nova/ui/NovaGameDetailDestinations.kt").readText()
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
    fun bottomSheetChromeClearsMaterialHostSoChildPanelDoesNotDrawABottomBump() {
        val sheetChrome = File("src/main/java/com/papi/nova/ui/NovaSheetChrome.kt").readText()
        val applyBody = sheetChrome.substringAfter("fun applyBottomSheetChrome(")
            .substringBefore("fun applyAlertDialogChrome")

        assertTrue("bottom-sheet chrome must clear the Material host/window to transparent so it cannot peek out as a bottom bump", applyBody.contains("ColorDrawable(Color.TRANSPARENT)"))
        assertTrue("bottom-sheet chrome should draw the themed glass surface on the content panel, not the Material host", applyBody.contains("contentView?.background = createSheetBackground(context)"))
        assertFalse("Material design_bottom_sheet host must not draw the same rounded sheet background behind the content panel", applyBody.contains("sheet.background = createSheetBackground(context)"))
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
        val gameDetail = File("src/main/java/com/papi/nova/ui/NovaGameDetailActivity.kt").readText() +
            File("src/main/java/com/papi/nova/ui/NovaGameDetailContent.kt").readText() +
            File("src/main/java/com/papi/nova/ui/NovaGameDetailOverview.kt").readText() +
            File("src/main/java/com/papi/nova/ui/NovaGameDetailDestinations.kt").readText()

        assertTrue("native sheet chrome must expose a named glass alpha contract", sheetChrome.contains("SHEET_GLASS_ALPHA"))
        assertTrue("native sheet backgrounds should preserve theme color while applying the absolute outer opacity", sheetChrome.contains("ColorUtils.setAlphaComponent") && sheetChrome.contains("NovaMenuPreferences.outerSurfaceAlpha"))
        assertTrue("high contrast can remain more opaque for readability", sheetChrome.contains("HIGH_CONTRAST_SHEET_GLASS_ALPHA"))
        assertTrue("shared scrim should be light enough for NovaHUD/game context to remain visible", sheetChrome.contains("const val SCRIM_ALPHA = 0.22f"))
        assertTrue("default action row state should remain transparent glass, not an opaque mini slab", sheetChrome.contains("fillAccentBlend = 0f") && sheetChrome.contains("Color.TRANSPARENT"))
        assertTrue("Compose library surfaces should reuse shared glass alpha language", composeTheme.contains("NovaSheetChrome.SHEET_GLASS_ALPHA"))
        assertTrue("game detail Compose drawer should reuse the shared native sheet radius token", gameDetail.contains("NovaSheetChrome.SHEET_CORNER_RADIUS_DP.dp"))
    }


    @Test
    fun menuOpacityWiresLiteralOuterSurfacesWithoutCouplingNovaHud() {
        val sheetChrome = File("src/main/java/com/papi/nova/ui/NovaSheetChrome.kt").readText()
        val composeTheme = File("src/main/java/com/papi/nova/ui/compose/NovaComposeTheme.kt").readText()
        val quickMenuContent = File("src/main/java/com/papi/nova/ui/NovaQuickMenuContent.kt").readText()
        val streamHud = File("src/main/java/com/papi/nova/ui/NovaStreamHud.kt").readText()
        val streamHudContent = File("src/main/java/com/papi/nova/ui/NovaStreamHudContent.kt").readText()
        val legacyAlertChrome = sheetChrome
            .substringAfter("fun applyMenuOpacityToLegacyAlert(dialog: AlertDialog")
            .substringBefore("fun applyAlertDialogChrome(dialog: AlertDialog")

        assertTrue("native sheet glass should read the shared menu opacity preference", sheetChrome.contains("NovaMenuPreferences.readOpacityPercent"))
        assertTrue("native outer surfaces should use absolute opacity with the dark-text readability floor", sheetChrome.contains("NovaMenuPreferences.outerSurfaceAlpha"))
        assertFalse("literal 100% opacity must not bypass shared alert chrome", legacyAlertChrome.contains("NovaMenuPreferences.MAX_OPACITY_PERCENT"))
        assertTrue("native sheet scrims should expose a preference-scaled alpha", sheetChrome.contains("getSheetScrimAlpha"))
        assertTrue("Compose menus should publish one menu opacity composition local", composeTheme.contains("LocalNovaMenuOpacityScale"))
        assertTrue("Compose menu surfaces should receive the current opacity scale", composeTheme.contains("librarySurfaces(theme, menuOpacityScale)"))
        assertTrue(
            "Compose outer panels should resolve absolute opacity rather than multiply theme glass",
            composeTheme.contains("NovaMenuPreferences.outerSurfaceAlpha(") &&
                composeTheme.contains("opacityScale = opacityScale") &&
                !composeTheme.contains("surfaces.panel.alpha * opacityScale")
        )
        assertTrue("Command Center outer panel should consume the shared absolute panel", quickMenuContent.contains(".background(surfaces.panel)"))
        assertFalse("Command Center outer panel must not retain the historical glass multiplier", quickMenuContent.contains("NovaInGameOverlayAlpha.GlassPanel * LocalNovaMenuOpacityScale.current"))
        assertTrue("Compose roots should observe saved menu opacity changes without requiring Activity recreation", composeTheme.contains("registerOnSharedPreferenceChangeListener") && composeTheme.contains("NovaMenuPreferences.KEY_OPACITY"))
        assertTrue(
            "NovaHUD must opt out of menu opacity so its own slider remains authoritative",
            streamHud.contains("NovaComposeTheme(menuOpacityPercent = NovaMenuPreferences.MAX_OPACITY_PERCENT)")
        )
        assertTrue("NovaHUD outer panel should use its own literal opacity", streamHudContent.contains(".background(surfaces.panel.copy(alpha = hudOpacityScale))"))
    }

    @Test
    fun sessionQuitConfirmationUsesNovaGlassBottomSheetInsteadOfRawAlertDialog() {
        val sheetChrome = File("src/main/java/com/papi/nova/ui/NovaSheetChrome.kt").readText()
        val spinnerDialog = File("src/main/java/com/papi/nova/utils/SpinnerDialog.kt").readText()
        val game = File("src/main/java/com/papi/nova/Game.kt").readText()
        val quitBody = game.substringAfter("fun quit() {").substringBefore("override fun showGameMenu")
        val spinnerLayout = File("src/main/res/layout/nova_spinner_dialog.xml").readText()

        assertTrue("shared chrome should still expose AlertDialog styling for remaining legacy session popups", sheetChrome.contains("applyAlertDialogChrome"))
        assertTrue("establishing-session spinner should apply Nova glass dialog chrome", spinnerDialog.contains("NovaSheetChrome.applyAlertDialogChrome(createdDialog"))
        assertFalse("spinner progress must not hardcode the Polaris accent", spinnerLayout.contains("@color/nova_accent"))
        assertFalse("spinner progress tint should be applied at runtime instead of risky XML attr tinting", spinnerLayout.contains("indeterminateTint"))
        assertTrue("spinner should tint progress from the active Nova theme at runtime", spinnerDialog.contains("NovaThemeManager.getAccentColor") && spinnerDialog.contains("indeterminateDrawable"))
        assertTrue("spinner layout should consume theme text color attrs", spinnerLayout.contains("?android:attr/textColorPrimary"))
        assertTrue("quit confirmation should be rebuilt as a Nova bottom sheet so it shares drawer/HUD glass chrome", quitBody.contains("BottomSheetDialog"))
        assertTrue("quit confirmation should build its own themed glass sheet container", quitBody.contains("NovaSheetChrome.createSheetContainer"))
        assertTrue("quit confirmation should apply shared bottom-sheet chrome", quitBody.contains("NovaSheetChrome.applyBottomSheetChrome(sheet"))
        assertTrue("quit confirmation should style custom action rows through shared sheet chrome", quitBody.contains("NovaSheetChrome.styleSheetAction"))
        assertFalse("quit confirmation should not use a raw AlertDialog shell", quitBody.contains("AlertDialog.Builder"))
        assertFalse("quit confirmation should not use platform dialog buttons", quitBody.contains("setPositiveButton") || quitBody.contains("setNegativeButton"))
        assertTrue("quit confirmation should use Nova-themed session action copy", game.contains("R.string.game_dialog_action_end_session") && game.contains("R.string.game_dialog_action_stay_in_game"))
        assertFalse("quit confirmation should drop the old generic streaming button labels", game.contains("game_dialog_action_end_stream") || game.contains("game_dialog_action_keep_streaming"))
        assertTrue("Command Center NovaHUD toggles should persist the next-stream preference", game.contains("setNovaHudPreference(true)") && game.contains("setNovaHudPreference(false)"))
    }

    @Test
    fun sessionProgressAndQuitCopyMatchesNovaSessionSemantics() {
        val strings = File("src/main/res/values/strings.xml").readText()

        assertTrue("connection spinner title should say stream, not old connection/session language", strings.contains("""<string name="conn_establishing_title">Starting stream</string>"""))
        assertTrue("connection spinner message should mention video/audio/input readiness", strings.contains("Preparing video, audio, and controller input"))
        assertTrue("quit title should be Nova-session language, not raw stream-control wording", strings.contains("""<string name="game_dialog_title_quit_confirm">End this Nova session?</string>"""))
        assertTrue("quit message should distinguish ending the host app from disconnect/resume", strings.contains("This closes the host app and the resumable stream"))
        assertTrue("quit destructive action should say End session", strings.contains("""<string name="game_dialog_action_end_session">End Session</string>"""))
        assertTrue("quit safe action should say Stay in game", strings.contains("""<string name="game_dialog_action_stay_in_game">Stay in Game</string>"""))
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
    fun commandCenterExposesLiveMenuOpacityWithoutDependingOnNovaHud() {
        val quickMenu = File("src/main/java/com/papi/nova/ui/NovaQuickMenu.kt").readText()
        val content = File("src/main/java/com/papi/nova/ui/NovaQuickMenuContent.kt").readText()
        val strings = File("src/main/res/values/strings.xml").readText()

        assertTrue("Command Center state should read the saved menu opacity", quickMenu.contains("menuOpacityPercent = NovaMenuPreferences.readOpacityPercent(prefs)"))
        assertTrue("Command Center should expose a menu-opacity callback", quickMenu.contains("onMenuOpacityChange = { percent ->"))
        assertTrue("the open Command Center should recompose from its live opacity state", quickMenu.contains("NovaComposeTheme(menuOpacityPercent = uiState.menuOpacity.percent)"))
        assertTrue("Command Center content should render the independent menu opacity control", content.contains("NovaQuickMenuMenuOpacityControl"))
        val menuOpacityControl = content
            .substringAfter("private fun NovaQuickMenuMenuOpacityControl(")
            .substringBefore("\n@Composable")
        assertTrue("the rendered control should expose every preset from state", menuOpacityControl.contains("state.menuOpacity.presets.forEach"))
        assertTrue("each rendered preset should dispatch the menu-opacity callback", menuOpacityControl.contains("callbacks.onMenuOpacityChange(percent)"))
        assertTrue("rendered presets should retain controller-sized focus targets", menuOpacityControl.contains("minHeight = 44.dp"))
        assertTrue("Command Center explicit glass constants should consume the menu opacity composition local", content.contains("LocalNovaMenuOpacityScale.current"))
        assertTrue("Command Center should label the new control as Menu Opacity", strings.contains("<string name=\"nova_quick_menu_menu_opacity\">Menu Opacity</string>"))
    }

    @Test
    fun settingsMenuOpacitySliderPreviewsLiveAndRestoresOnCancel() {
        val settings = File("src/main/java/com/papi/nova/preferences/NovaSettingsScreen.kt").readText()
        val sliderDialog = settings
            .substringAfter("private fun NovaSliderDialog(")
            .substringBefore("private fun NovaTextDialog(")

        assertTrue("Settings should preview Menu Opacity through owner-scoped process-local state while dragging", settings.contains("NovaMenuOpacityPreview.update(owner, percent)") && settings.contains("NovaMenuOpacityPreview.newOwner()") && sliderDialog.contains("onValueChange = { nextValue ->"))
        assertFalse("live preview must not write the durable SharedPreferences key", settings.contains("NovaMenuPreferences.writeOpacityPercent(prefs"))
        assertTrue("cancel and save should clear only the owning temporary preview", settings.contains("NovaMenuOpacityPreview::clear") && settings.contains("previewOwnerAtSave"))
        assertTrue("Save should persist through the repository before clearing preview", sliderDialog.contains("onSave(definition, NovaSettingValue.IntValue(value.roundToInt()))"))
        assertFalse("default Material slider dialogs should not be unconditionally restyled at 100%", sliderDialog.contains("containerColor = surfaces.panel") || sliderDialog.contains("tonalElevation = 0.dp"))
    }

    @Test
    fun menuOpacityCoversLifecycleLibraryOptionsAndResetPaths() {
        val lifecycle = File("src/main/java/com/papi/nova/ui/NovaStreamOverlayContent.kt").readText()
        val library = File("src/main/java/com/papi/nova/ui/NovaLibraryActivity.kt").readText()
        val gameDetail = File("src/main/java/com/papi/nova/ui/NovaGameDetailActivity.kt").readText() +
            File("src/main/java/com/papi/nova/ui/NovaGameDetailContent.kt").readText() +
            File("src/main/java/com/papi/nova/ui/NovaGameDetailOverview.kt").readText() +
            File("src/main/java/com/papi/nova/ui/NovaGameDetailDestinations.kt").readText()
        val settings = File("src/main/java/com/papi/nova/preferences/NovaSettingsScreen.kt").readText()
        val focusComponents = File("src/main/java/com/papi/nova/ui/compose/NovaFocusComponents.kt").readText()
        val settingsViewModel = File("src/main/java/com/papi/nova/preferences/NovaSettingsViewModel.kt").readText()
        val streamSettings = File("src/main/java/com/papi/nova/preferences/StreamSettings.kt").readText()
        val settingsRepository = File("src/main/java/com/papi/nova/preferences/NovaSettingsRepository.kt").readText()

        assertTrue("session start/reconnect scrims should retain a contrast floor while honoring menu opacity", lifecycle.contains("NovaMenuPreferences.readabilityScrimAlpha(") && lifecycle.contains("scrimAlpha,"))
        assertTrue("Compose library modal scrims should retain a contrast floor", library.contains("NovaMenuPreferences.readabilityScrimAlpha"))
        assertTrue(
            "both Library Options and System drawer scrims should use the readability floor",
            library.split("NovaMenuPreferences.readabilityScrimAlpha(").size - 1 >= 3
        )
        assertTrue("Library fixed glass overrides should consume the menu opacity local", library.contains("LocalNovaMenuOpacityScale.current"))
        assertTrue("game detail fixed glass overrides should consume the menu opacity local", gameDetail.contains("LocalNovaMenuOpacityScale.current"))
        assertTrue("options fixed glass overrides should consume the menu opacity local", settings.contains("LocalNovaMenuOpacityScale.current"))
        assertTrue("shared focus components should scale panel glass while retaining focus rings", focusComponents.contains("LocalNovaMenuOpacityScale.current"))
        assertTrue("Stream UI reset should restore menu opacity to its declared default", settingsViewModel.contains("NovaMenuPreferences.KEY_OPACITY to NovaSettingValue.IntValue(NovaMenuPreferences.DEFAULT_OPACITY_PERCENT)"))
        assertTrue("Stream UI reset should remove stale HUD coordinates in the same authoritative batch", settingsViewModel.contains("NOVA_STREAM_UI_RESET_REMOVALS") && settingsViewModel.contains("store.updateAtomically(updates, NOVA_STREAM_UI_RESET_REMOVALS)"))
        assertTrue("reset construction should fail closed instead of silently skipping filtered definitions", settingsViewModel.contains("definitions.require(key) to value"))
        assertTrue("Compose Settings should use canonical unfiltered definitions for resets", streamSettings.contains("resetDefinitions = canonicalDefinitions"))
        assertTrue("repository batch should commit SharedPreferences once as the runtime authority", settingsRepository.contains("check(editor.commit())") && settingsRepository.contains("SharedPreferences is authoritative"))
        assertTrue("DataStore should be repaired from the authoritative runtime mirror", settingsRepository.contains("reconcileDataStoreMirror") && settingsRepository.contains("canonicalDefinitions.settings"))
    }

    @Test
    fun adaptiveMenuBlurTargetsOnlyBackdropContentAndFailsSoftBelowAndroid12() {
        val blur = File("src/main/java/com/papi/nova/ui/NovaMenuBlur.kt").readText()
        val composeBlur = File("src/main/java/com/papi/nova/ui/compose/NovaMenuBackdropBlur.kt").readText()
        val composeTheme = File("src/main/java/com/papi/nova/ui/compose/NovaComposeTheme.kt").readText()
        val sheetChrome = File("src/main/java/com/papi/nova/ui/NovaSheetChrome.kt").readText()
        val quickMenuHost = File("src/main/java/com/papi/nova/ui/NovaQuickMenu.kt").readText()
        val quickMenu = File("src/main/java/com/papi/nova/ui/NovaQuickMenuContent.kt").readText()
        val library = File("src/main/java/com/papi/nova/ui/NovaLibraryActivity.kt").readText()
        val settings = File("src/main/java/com/papi/nova/preferences/NovaSettingsScreen.kt").readText()
        val progress = File("src/main/java/com/papi/nova/ui/SessionProgressOverlay.kt").readText()
        val reconnect = File("src/main/java/com/papi/nova/ui/ReconnectOverlay.kt").readText()

        assertTrue("adaptive blur should use API 31 RenderEffect rather than optional cross-window blur", blur.contains("Build.VERSION_CODES.S") && blur.contains("RenderEffect.createBlurEffect"))
        assertTrue("blur cleanup must be owner-scoped for overlapping overlays", blur.contains("class BlurLease") && blur.contains("ownerRadiiDp") && blur.contains("applyStrongestOwnedEffect"))
        assertTrue("all View and dialog mutations should be main-thread confined", blur.contains("Looper.myLooper() == Looper.getMainLooper()") && blur.contains("requireMainThread()"))
        assertTrue("stale dialog listeners must not remove a newer binding", blur.contains("dialogBindings[view] !== binding") && blur.contains("dialogBindings[view] === binding"))
        assertTrue("releasing an owner should recompute the strongest remaining radius", blur.contains("state.ownerRadiiDp.remove(owner)") && blur.contains("state.ownerRadiiDp.values.maxOrNull()"))
        assertTrue("Compose drawers should lease the Activity backdrop and release only their own effect", composeBlur.contains("NovaMenuBlur.acquireActivityBackground") && composeBlur.contains("lease?.release()"))
        assertTrue("Command Center must live in a separate Dialog window before leasing the Game backdrop", quickMenuHost.contains("val overlay = Dialog(game)") && quickMenuHost.contains("overlay.setContentView(composeView)"))
        assertTrue("Command Center should opt into adaptive backdrop blur", quickMenu.contains("NovaMenuBackdropBlur()"))
        assertTrue("Library drawers should blur only while a separate-window drawer is active", library.contains("if (activeOptionsSheet || activeSystemMenu)") && library.contains("NovaMenuBackdropBlur()"))
        assertFalse("same-window filter sheets must not blur their own controls through the Activity decor", library.contains("activeFilterSheet != null || activeOptionsSheet"))
        assertTrue("Settings editors should blur the underlying Settings surface", settings.contains("NovaMenuBackdropBlur()"))
        assertTrue("native dialog blur should start on window attach and clear on detach", blur.contains("onViewAttachedToWindow") && blur.contains("isAttachedToWindow") && blur.contains("onViewDetachedFromWindow"))
        assertTrue("native sheets and alerts should share the same adaptive blur contract", sheetChrome.contains("NovaMenuBlur.attachBehindDialog"))
        assertTrue("dark-text native surfaces should retain a WCAG readability floor below 100%", sheetChrome.contains("NovaMenuPreferences.outerSurfaceAlpha") && sheetChrome.contains("ColorUtils.calculateLuminance"))
        assertTrue("Compose contrast scrims should use the stronger dark-text floor", composeTheme.contains("usesDarkText = textPrimary.luminance() < 0.5f"))
        assertTrue("custom select dialogs should draw the theme-aware window contrast scrim only below compatibility opacity", settings.contains("NovaDialogContrastBackdrop()") && settings.contains("if (opacityScale < 1f)") && settings.contains("surfaces.backgroundScrim.toArgb()"))
        assertTrue("unfocused native action strokes should disappear with menu glass", sheetChrome.contains("strokeAccentBlend * menuOpacityScale"))
        assertTrue("focused and pressed native action strokes should remain as readability cues", sheetChrome.contains("if (preservesFocusCue)"))
        assertTrue("session startup should release leases on explicit dismissal and unexpected view detach", progress.contains("NovaMenuBlur.acquireChildren") && progress.contains("releaseBackgroundBlur") && progress.contains("releaseOnUnexpectedDetach"))
        assertTrue("reconnect should release leases on explicit dismissal and unexpected view detach", reconnect.contains("NovaMenuBlur.acquireChildren") && reconnect.contains("releaseBackgroundBlur") && reconnect.contains("releaseOnUnexpectedDetach"))
    }

    @Test
    fun requiredNativeAlertsUseSharedOpacityAndBlurChrome() {
        val gameDetail = File("src/main/java/com/papi/nova/ui/NovaGameDetailActivity.kt").readText() +
            File("src/main/java/com/papi/nova/ui/NovaGameDetailContent.kt").readText() +
            File("src/main/java/com/papi/nova/ui/NovaGameDetailOverview.kt").readText() +
            File("src/main/java/com/papi/nova/ui/NovaGameDetailDestinations.kt").readText()
        val legacySlider = File("src/main/java/com/papi/nova/preferences/SeekBarPreference.kt").readText()
        val sessionDialog = File("src/main/java/com/papi/nova/utils/Dialog.kt").readText()

        val preflight = gameDetail
            .substringAfter("private fun showPreflightReview(")
            .substringBefore("private fun showDesktopSteamLaunchDecision(")
        assertTrue(
            "the game detail window raises no legacy alert at all: the preflight review expands the status line in place instead",
            !preflight.contains("AlertDialog.Builder") && preflight.contains("reviewExpanded")
        )
        assertTrue("legacy sliders, including Menu & Drawer Opacity, should use shared literal-opacity alert chrome", legacySlider.contains("NovaSheetChrome.applyMenuOpacityToLegacyAlert(createdDialog)"))
        assertTrue("session termination/error alerts should use shared literal-opacity alert chrome", sessionDialog.contains("NovaSheetChrome.applyMenuOpacityToLegacyAlert(createdAlert)"))
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
    fun api31ThemeStylesPreserveEveryNovaPalette() {
        val stylesV31 = File("src/main/res/values-v31/styles.xml").readText()
        val requiredItems = listOf(
            "android:colorBackground",
            "android:windowBackground",
            "android:textColorPrimary",
            "android:textColorSecondary",
            "colorPrimary",
            "colorSurface",
            "colorOnSurface",
            "colorOnPrimary",
            "android:statusBarColor",
            "android:navigationBarColor",
        )
        val paletteMarkers = mapOf(
            "AppTheme.PortableChrome" to "@color/nova_portable_bg_window",
            "AppTheme.OLED" to "@color/nova_oled_bg_window",
            "AppTheme.Miami" to "@color/nova_miami_void",
            "AppTheme.HighContrast" to "@color/nova_hc_bg_window",
        )

        paletteMarkers.forEach { (styleName, paletteMarker) ->
            val block = stylesV31
                .substringAfter("<style name=\"$styleName\"")
                .substringBefore("</style>")
            assertTrue("v31 $styleName must retain its palette", block.contains(paletteMarker))
            requiredItems.forEach { item ->
                assertTrue("v31 $styleName missing $item", block.contains("name=\"$item\""))
            }
        }
    }

    @Test
    fun dashboardActivitiesRecreateAfterExternalThemeChanges() {
        listOf("PcView.kt", "AppView.kt").forEach { fileName ->
            val source = File("src/main/java/com/papi/nova/$fileName").readText()
            val onCreate = source.substringAfter("override fun onCreate(savedInstanceState: Bundle?)")
                .substringBefore("private fun", missingDelimiterValue = source.substringAfter("override fun onCreate(savedInstanceState: Bundle?)"))
            val onResume = source.substringAfter("override fun onResume()")
                .substringBefore("override fun onPause()")

            assertTrue("$fileName must snapshot the applied theme before view inflation", onCreate.indexOf("appliedTheme = NovaThemeManager.getTheme(this)") in 0 until onCreate.indexOf("super.onCreate(savedInstanceState)"))
            assertTrue("$fileName must detect and recreate for an external theme change", onResume.contains("if (recreateForThemeChangeIfNeeded()) return"))
            assertFalse("$fileName must not partially retheme an already-inflated hierarchy", onResume.contains("NovaThemeManager.applyTheme(this)"))
            assertFalse("$fileName must not manually recolor only part of an already-inflated hierarchy", onResume.contains("applyThemeToServerBrowser()"))
            assertTrue("$fileName theme-change helper must recreate the whole Activity", source.contains("private fun recreateForThemeChangeIfNeeded(): Boolean") && source.contains("recreate()"))
        }
    }

    @Test
    fun materialYouAppSurfacesDoNotUseStaticLegacyBackgrounds() {
        val pcView = File("src/main/java/com/papi/nova/PcView.kt").readText()
        val appView = File("src/main/java/com/papi/nova/AppView.kt").readText()
        val gameDetail = File("src/main/java/com/papi/nova/ui/NovaGameDetailActivity.kt").readText() +
            File("src/main/java/com/papi/nova/ui/NovaGameDetailContent.kt").readText() +
            File("src/main/java/com/papi/nova/ui/NovaGameDetailOverview.kt").readText() +
            File("src/main/java/com/papi/nova/ui/NovaGameDetailDestinations.kt").readText()
        val manager = File("src/main/java/com/papi/nova/ui/NovaThemeManager.kt").readText()
        val particles = File("src/main/java/com/papi/nova/ui/SpaceParticleView.kt").readText()

        assertFalse(pcView.contains("R.color.nova_bg_elevated"))
        assertFalse(appView.contains("R.color.nova_bg_elevated"))
        assertFalse(gameDetail.contains("R.color.nova_deep"))
        assertTrue(manager.contains("fun getActivityWindowSurfaceColor(context: Context): Int"))
        assertTrue(manager.contains("val surfaceColor = getActivityWindowSurfaceColor(activity)"))
        assertTrue(particles.contains("NovaThemeManager.getActivityWindowSurfaceColor(context)"))
    }

    @Test
    fun legacyPreferenceRowsBindSemanticThemeColors() {
        val settings = File("src/main/java/com/papi/nova/preferences/StreamSettings.kt").readText()
        val layout = File("src/main/res/layout/nova_preference_semantic.xml").readText()
        val title = File("src/main/res/color/nova_preference_title_semantic.xml").readText()
        val summary = File("src/main/res/color/nova_preference_summary_semantic.xml").readText()

        assertTrue(settings.contains("NovaThemeManager.getTheme(requireContext()) == NovaThemeManager.THEME_MATERIAL_YOU"))
        assertTrue(settings.contains("applySemanticPreferenceLayouts(preferenceScreen)"))
        assertTrue(settings.contains("preference.layoutResource = R.layout.nova_preference_semantic"))
        assertTrue(settings.contains("preference !is PreferenceCategory"))
        assertTrue(settings.contains("preference is PreferenceGroup"))
        assertFalse(settings.contains("PreferenceGroupAdapter"))
        assertTrue(layout.contains("@color/nova_preference_title_semantic"))
        assertTrue(layout.contains("@color/nova_preference_summary_semantic"))
        assertTrue(title.contains("state_enabled=\"false\""))
        assertTrue(title.contains("android:alpha=\"0.38\""))
        assertTrue(title.contains("?attr/colorOnSurface"))
        assertTrue(summary.contains("state_enabled=\"false\""))
        assertTrue(summary.contains("android:alpha=\"0.38\""))
        assertTrue(summary.contains("?attr/colorOnSurfaceVariant"))
        assertFalse(layout.contains("@layout/image_frame"))
        assertFalse(layout.contains("@style/PreferenceSummaryTextStyle"))
        assertFalse(layout.contains("androidx.preference.internal"))
        assertTrue(layout.contains("androidx.appcompat.widget.AppCompatImageView"))
        assertTrue(layout.contains("?android:attr/textAppearanceListItemSecondary"))
    }

    @Test
    fun sharedThemeOverlaysUseSemanticRolesInsteadOfPolarisPalette() {
        val styles = File("src/main/res/values/styles.xml").readText()
        listOf(
            "NovaPreferenceTheme.MaterialYou",
            "NovaAlertDialog.MaterialYou",
            "NovaPopupMenu",
            "NovaFilterChip",
            "NovaMaterialChip",
            "NovaMaterialChip.Action",
            "NovaBottomSheet",
        ).forEach { styleName ->
            val block = styles.substringAfter("<style name=\"$styleName\"")
                .substringBefore("</style>")
            val semanticBlock = block
                .replace("@color/nova_chip_bg_selector", "")
                .replace("@color/nova_focus_stroke_selector", "")
            assertFalse("$styleName must not hardcode the Polaris palette", semanticBlock.contains("@color/nova_"))
        }
        listOf("NovaPreferenceTheme.MaterialYou", "NovaAlertDialog.MaterialYou").forEach { styleName ->
            val block = styles.substringAfter("<style name=\"$styleName\"")
                .substringBefore("</style>")
            assertTrue(block.contains("?attr/colorOnSurface"))
            assertTrue(block.contains("?attr/colorOnSurfaceVariant"))
        }

        val appView = File("src/main/res/layout/activity_app_view.xml").readText()
        val profilesButton = appView.substringAfter("android:id=\"@+id/profilesButton\"")
            .substringBefore("/>")
        assertTrue(profilesButton.contains("app:backgroundTint=\"?attr/colorAccent\""))
        assertTrue(profilesButton.contains("app:iconTint=\"?attr/colorOnPrimary\""))

        val profiles = File("src/main/res/layout/activity_profiles.xml").readText()
        val addProfileFab = profiles.substringAfter("android:id=\"@+id/addProfileFab\"")
            .substringBefore("/>")
        assertTrue(addProfileFab.contains("app:backgroundTint=\"?attr/colorAccent\""))
        assertTrue(addProfileFab.contains("app:tint=\"?attr/colorOnPrimary\""))
        assertFalse(addProfileFab.contains("app:iconTint="))
    }

    @Test
    fun materialYouStylesExplicitlyUseDynamicDayNightParents() {
        val styles = File("src/main/res/values/styles.xml").readText()
        val stylesV31 = File("src/main/res/values-v31/styles.xml").readText()
        val stylesNightV31 = File("src/main/res/values-night-v31/styles.xml").readText()

        assertTrue(
            styles.contains(
                "<style name=\"AppTheme.MaterialYou\" parent=\"Theme.Material3.DynamicColors.DayNight.NoActionBar\">"
            )
        )
        val materialSettingsStyle = styles
            .substringAfter("<style name=\"SettingsTheme.MaterialYou\"")
            .substringBefore("</style>")
        assertTrue(
            styles.contains(
                "<style name=\"SettingsTheme.MaterialYou\" parent=\"Theme.Material3.DynamicColors.DayNight.NoActionBar\">"
            )
        )
        assertTrue(materialSettingsStyle.contains("name=\"preferenceTheme\">@style/NovaPreferenceTheme.MaterialYou"))
        assertTrue(materialSettingsStyle.contains("name=\"alertDialogTheme\">@style/NovaAlertDialog.MaterialYou"))
        assertTrue(materialSettingsStyle.contains("name=\"android:alertDialogTheme\">@style/NovaAlertDialog.MaterialYou"))
        assertTrue(materialSettingsStyle.contains("name=\"materialAlertDialogTitleTextStyle\">@style/NovaAlertDialogTitleText.MaterialYou"))

        val v31MaterialSettings = stylesV31
            .substringAfter("<style name=\"SettingsTheme.MaterialYou\"")
            .substringBefore("</style>")
        val nightV31MaterialSettings = stylesNightV31
            .substringAfter("<style name=\"SettingsTheme.MaterialYou\"")
            .substringBefore("</style>")
        assertTrue(v31MaterialSettings.contains("@android:color/system_neutral1_900"))
        assertTrue(v31MaterialSettings.contains("name=\"preferenceTheme\">@style/NovaPreferenceTheme.MaterialYou"))
        assertTrue(v31MaterialSettings.contains("name=\"materialAlertDialogTitleTextStyle\">@style/NovaAlertDialogTitleText.MaterialYou"))
        assertTrue(nightV31MaterialSettings.contains("@android:color/system_neutral1_50"))
        assertTrue(nightV31MaterialSettings.contains("name=\"preferenceTheme\">@style/NovaPreferenceTheme.MaterialYou"))
        assertTrue(nightV31MaterialSettings.contains("name=\"materialAlertDialogTitleTextStyle\">@style/NovaAlertDialogTitleText.MaterialYou"))
        val v31PreferenceOverlay = stylesV31
            .substringAfter("<style name=\"NovaPreferenceTheme.MaterialYou\"")
            .substringBefore("</style>")
        val nightPreferenceOverlay = stylesNightV31
            .substringAfter("<style name=\"NovaPreferenceTheme.MaterialYou\"")
            .substringBefore("</style>")
        assertTrue(v31PreferenceOverlay.contains("name=\"android:textColorPrimary\">@android:color/system_neutral1_900"))
        assertTrue(nightPreferenceOverlay.contains("name=\"android:textColorPrimary\">@android:color/system_neutral1_50"))
        val v31AlertOverlay = stylesV31
            .substringAfter("<style name=\"NovaAlertDialog.MaterialYou\"")
            .substringBefore("</style>")
        assertTrue(v31AlertOverlay.contains("name=\"materialAlertDialogTitleTextStyle\">@style/NovaAlertDialogTitleText.MaterialYou"))
        val baseAlertOverlay = styles
            .substringAfter("<style name=\"NovaAlertDialog.MaterialYou\"")
            .substringBefore("</style>")
        val baseAppCompatAlertTitle = styles
            .substringAfter("<style name=\"NovaAppCompatDialogTitle.MaterialYou\"")
            .substringBefore("</style>")
        val v31AppCompatAlertTitle = stylesV31
            .substringAfter("<style name=\"NovaAppCompatDialogTitle.MaterialYou\"")
            .substringBefore("</style>")
        val nightAppCompatAlertTitle = stylesNightV31
            .substringAfter("<style name=\"NovaAppCompatDialogTitle.MaterialYou\"")
            .substringBefore("</style>")
        assertTrue(baseAlertOverlay.contains("name=\"android:windowTitleStyle\">@style/NovaAppCompatDialogTitle.MaterialYou"))
        assertTrue(baseAppCompatAlertTitle.contains("?attr/colorOnSurface"))
        assertTrue(v31AppCompatAlertTitle.contains("@android:color/system_neutral1_900"))
        assertTrue(nightAppCompatAlertTitle.contains("@android:color/system_neutral1_50"))
        assertTrue(
            stylesV31.contains(
                "<style name=\"AppTheme.MaterialYou\" parent=\"Theme.Material3.DynamicColors.DayNight.NoActionBar\">"
            )
        )
        assertTrue(
            stylesNightV31.contains(
                "<style name=\"AppTheme.MaterialYou\" parent=\"Theme.Material3.DynamicColors.DayNight.NoActionBar\">"
            )
        )
    }

    @Test
    fun materialYouUsesDayNightSemanticRolesAcrossSystemBarsComposeAndVisibleXml() {
        val manager = File("src/main/java/com/papi/nova/ui/NovaThemeManager.kt").readText()
        val composeTheme = File("src/main/java/com/papi/nova/ui/compose/NovaComposeTheme.kt").readText()
        val v31Styles = File("src/main/res/values-v31/styles.xml").readText()
        val v31MaterialYou = v31Styles
            .substringAfter("<style name=\"AppTheme.MaterialYou\"")
            .substringBefore("</style>")

        listOf(
            "android:colorBackground",
            "android:windowBackground",
            "android:statusBarColor",
            "android:navigationBarColor",
            "android:textColorPrimary",
            "android:textColorSecondary",
            "colorSurface",
            "colorOnSurface",
        ).forEach { role ->
            assertTrue("API 31+ Material You style must retain $role instead of shadowing the base style", v31MaterialYou.contains("name=\"$role\""))
        }

        assertTrue("Material You role lookup must use the Material dynamic context wrapper", manager.contains("DynamicColors.wrapContextIfAvailable"))
        assertTrue(
            "system-bar icons must choose the higher-contrast black or white appearance",
            manager.contains("isAppearanceLightStatusBars") &&
                manager.contains("isAppearanceLightNavigationBars") &&
                manager.contains("ColorUtils.calculateContrast(Color.BLACK, surfaceColor)") &&
                manager.contains("ColorUtils.calculateContrast(Color.WHITE, surfaceColor)")
        )
        assertTrue("Compose must support a light Material color scheme", composeTheme.contains("lightColorScheme"))
        assertTrue("Compose Material You must follow system DayNight", composeTheme.contains("isSystemInDarkTheme"))

        val forbiddenStaticRoles = listOf(
            "@color/nova_text_primary",
            "@color/nova_text_secondary",
            "@color/nova_text_muted",
            "@color/nova_ice",
            "@color/nova_bg_card",
            "@color/nova_deep",
            "@color/nova_bg_window",
            "@color/nova_badge_bg",
            "@color/nova_storm",
            "@color/nova_silver",
            "@color/nova_accent",
        )
        val offenders = File("src/main/res")
            .walkTopDown()
            .filter { file ->
                val parentName = file.parentFile?.name.orEmpty()
                file.isFile && file.extension == "xml" && (parentName.startsWith("layout") || parentName == "drawable")
            }
            .flatMap { file ->
                file.readLines().mapIndexedNotNull { index, line ->
                    forbiddenStaticRoles.firstOrNull(line::contains)?.let { "${file.path}:${index + 1}:$it" }
                }
            }
            .toList()
        assertTrue("visible XML must consume semantic theme attributes, not static Nova surface/text roles: $offenders", offenders.isEmpty())
    }

    @Test
    fun everyVisibleColdStartActivityAppliesSelectedNovaTheme() {
        val debug = File("src/main/java/com/papi/nova/DebugInfoActivity.kt").readText()
        val shortcut = File("src/main/java/com/papi/nova/ShortcutTrampoline.kt").readText()
        val debugBeforeSuper = debug.substringAfter("override fun onCreate").substringBefore("super.onCreate")
        val shortcutBeforeSuper = shortcut.substringAfter("override fun onCreate").substringBefore("super.onCreate")

        assertTrue("DebugInfoActivity must apply the selected theme before super.onCreate", debugBeforeSuper.contains("NovaThemeManager.applyTheme(this)"))
        assertTrue("ShortcutTrampoline dialogs must inherit the selected theme on cold start", shortcutBeforeSuper.contains("NovaThemeManager.applyTheme(this)"))
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
