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
        assertTrue(colors.contains("nova_portable_accent") && colors.contains("#FF5A93D6"))
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

        assertTrue(colors.contains("nova_portable_accent") && colors.contains("#FF5A93D6"))
        assertTrue(colors.contains("<color name=\"nova_polaris_accent\">"))
        assertTrue(styles.contains("AppTheme.PortableChrome"))
        assertTrue(styles.contains("SettingsTheme.PortableChrome"))
        assertTrue(styles.contains("@color/nova_portable_accent"))
    }

    @Test
    fun miamiKeepsFlamingoPinkHeroAccentWithCyanAquaSupport() {
        val colors = File("src/main/res/values/colors_nova.xml").readText()

        assertTrue(colors.contains("nova_miami_accent") && colors.contains("#FFFF5CAB"))
        assertTrue(colors.contains("nova_miami_accent_surface") && colors.contains("#1AFF5CAB"))
        assertTrue(colors.contains("nova_miami_accent_glow") && colors.contains("#73FF5CAB"))
        assertTrue(colors.contains("nova_miami_water_accent") && colors.contains("#FF47F3FF"))
        assertTrue(colors.contains("nova_miami_water_accent_surface") && colors.contains("#1A47F3FF"))
        assertFalse("Miami must not replace flamingo pink with cyan as the primary accent", colors.lines().any { it.contains("nova_miami_accent") && it.contains("#FF47F3FF") })
        // The theme's description is the host theme page's caption now (the picker's subtitles
        // went with its sheet), and it still leads with the pink and keeps the cyan as support.
        val caption = File("src/main/res/values/strings_ui_hosts.xml").readText()
            .substringAfter("name=\"hosts_theme_caption_miami\">").substringBefore("</string>")
        assertTrue(caption, caption.contains("flamingo pink") && caption.contains("cyan"))
    }

    @Test
    fun portableChromeUsesSubtlePlayStationSymbolAccentTokens() {
        val colors = File("src/main/res/values/colors_nova.xml").readText()

        assertTrue(colors.contains("nova_portable_accent") && colors.contains("#FF5A93D6"))
        assertTrue(colors.contains("nova_portable_cross_accent") && colors.contains("#FF5A93D6"))
        assertTrue(colors.contains("nova_portable_square_accent") && colors.contains("#FFB583B5"))
        assertTrue(colors.contains("nova_portable_circle_accent") && colors.contains("#FFD4838A"))
        assertTrue(colors.contains("nova_portable_triangle_accent") && colors.contains("#FF6FBF8A"))
        val oldPortableAccent = "nova_portable_accent" + 34.toChar() + ">#FF7FA38D"
        assertFalse("Portable Chrome primary accent must not stay generic muted green", colors.contains(oldPortableAccent))
        val caption = File("src/main/res/values/strings_ui_hosts.xml").readText()
            .substringAfter("name=\"hosts_theme_caption_portable_chrome\">").substringBefore("</string>")
        assertTrue("the host theme page names the PlayStation accents: $caption", caption.contains("PlayStation accents"))
    }

    @Test
    fun pcViewThemePickerReadsTheSharedThemeList() {
        val source = File("src/main/java/com/papi/nova/PcView.kt").readText()
        val builder = source.substringAfter("private fun buildThemePickerThemes(")
            .substringBefore("private fun applyThemeSelection")

        // This used to assert that the picker listed Polaris, then Portable Chrome, then
        // OLED, by comparing indexOf positions in the source of showThemePicker. Once the
        // list moved into R.array.nova_theme_values, THEME_POLARIS stopped appearing in that
        // range, indexOf returned -1, and `-1 < somethingElse` is true -- so it passed while
        // checking nothing at all.
        //
        // Which themes exist and in what order is the array's job, and
        // themeArraysExposeMiamiInPredictableOrder above pins that by value. What is left
        // here is the wiring.
        assertTrue(
            "the dashboard picker reads the shared theme array",
            builder.contains("R.array.nova_theme_values")
        )
        assertFalse(
            "and does not keep a second copy of the theme list in Kotlin, which would let a " +
                "newly added theme appear in settings and silently not on the dashboard",
            builder.contains("THEME_POLARIS") || builder.contains("THEME_OLED")
        )
    }


    @Test
    fun pcViewThemePickerIsAChoicePageWithSwatches() {
        val source = File("src/main/java/com/papi/nova/PcView.kt").readText()
        val picker = source.substringAfter("private fun showThemePicker(")
            .substringBefore("private fun buildThemePickerThemes")
        val page = source.substringAfter("internal fun novaThemePickerPage(")
            .substringBefore("internal fun novaThemePickerCaption(")
        val strings = File("src/main/res/values/strings.xml").readText()

        // The picker was a bottom sheet of hand-built cards, with its own focus label, D-pad key
        // listener, two-column grid and clip workarounds. As a Choice page in a right-edge panel
        // (spec R2) the foundation does all of that once: it opens on the current theme with the
        // one current mark, acts on release, and keeps the focus ring inside the row.
        assertFalse("D-pad users need a panel page, not Android's square single-choice list", picker.contains("setSingleChoiceItems"))
        assertTrue(
            "the picker opens a Choice page at the right edge, returning focus to the theme action",
            picker.contains("novaSurfaces.open(") && picker.contains("NovaEdge.End") && picker.contains("NovaFocusReturn.View(it)")
        )
        assertFalse("the picker is no longer a sheet of its own", source.contains("BottomSheetDialog") || source.contains("createThemePickerRow("))
        assertTrue(
            "each theme shows the swatch the Settings theme page shows, so both draw the colours the app draws",
            page.contains("NovaCommonPage.Choice(") && page.contains("leading = { option -> NovaThemeSwatch(option.value) }")
        )
        assertTrue("the current theme is the Choice page's current value", page.contains("current = current,"))
        assertTrue("Material You should remain part of the dashboard picker when the device supports it", source.contains("NovaThemeManager.THEME_MATERIAL_YOU"))
        assertTrue("Portable Chrome should be eye-scan visible as the picker title", strings.contains("Portable Chrome"))
        val portableCaption = File("src/main/res/values/strings_ui_hosts.xml").readText()
            .substringAfter("name=\"hosts_theme_caption_portable_chrome\">").substringBefore("</string>")
        assertTrue(
            "Portable Chrome's caption should describe the chrome profile without PSP naming",
            portableCaption.contains("handheld chrome") && !portableCaption.contains("PSP")
        )
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
    fun overlaysShareThePanelChromeInsteadOfOneOffSurfaces() {
        val sheetChrome = File("src/main/java/com/papi/nova/ui/NovaSheetChrome.kt").readText()
        val pcView = File("src/main/java/com/papi/nova/PcView.kt").readText()
        val appView = File("src/main/java/com/papi/nova/AppView.kt").readText()
        val gameDetail = File("src/main/java/com/papi/nova/ui/NovaGameDetailActivity.kt").readText() +
            File("src/main/java/com/papi/nova/ui/NovaGameDetailContent.kt").readText() +
            File("src/main/java/com/papi/nova/ui/NovaGameDetailOverview.kt").readText() +
            File("src/main/java/com/papi/nova/ui/NovaGameDetailDestinations.kt").readText()
        val library = File("src/main/java/com/papi/nova/ui/NovaLibraryActivity.kt").readText()
        val libraryPanels = File("src/main/java/com/papi/nova/ui/NovaLibraryPanels.kt").readText()
        val playSetupPages = File("src/main/java/com/papi/nova/ui/NovaPlaySetupPages.kt").readText()
        val panelFrame = File("src/main/java/com/papi/nova/ui/panel/NovaPanelFrame.kt").readText()

        // The last View sheet and alert are panel pages now, so the View chrome that drew them is
        // gone: NovaSheetChrome keeps only the glass alphas the Compose theme reads, and the panel
        // frame draws the one surface, radius and scrim every overlay shares.
        assertFalse(
            "no View sheet or alert chrome is left to drift from the panel frame's",
            sheetChrome.contains("fun applyBottomSheetChrome(") || sheetChrome.contains("fun applyAlertDialogChrome(") ||
                sheetChrome.contains("fun createSheetBackground(") || sheetChrome.contains("BottomSheetDialog")
        )
        assertTrue(
            "the drawer radius is a step of the one corner scale, not a sheet constant",
            File("src/main/java/com/papi/nova/ui/compose/NovaRadius.kt").readText().contains("val drawer: Dp = 26.dp")
        )
        // The theme picker, the host menu and the app menu are panels now: NovaPanelWindow draws
        // their chrome, so none of them builds a sheet or applies the sheet chrome itself.
        assertFalse("the Hosts screen's menus are panels, not sheets", pcView.contains("NovaSheetChrome.") || pcView.contains("BottomSheetDialog"))
        assertFalse("the App list's menu is a panel, not a sheet", appView.contains("NovaSheetChrome.") || appView.contains("BottomSheetDialog"))
        // Group 3's sheets became panels: they share the panel frame's chrome rather than the sheet helper.
        assertTrue(
            "game detail hosts no sheet: Play Setup is a panel frame in its own window",
            !gameDetail.contains("BottomSheetDialog") && playSetupPages.contains("NovaPanelFrame(")
        )
        assertTrue("Polaris Sync is a page of the library's System panel", libraryPanels.contains("internal fun NovaPageScope.NovaPolarisSyncPage("))
        assertTrue(
            "the library's panels take the one drawer radius and themed scrim from the panel frame",
            library.contains("surfaces.open(") &&
                panelFrame.contains("NovaRadius.drawer") &&
                panelFrame.contains("NovaMenuPreferences.readabilityScrimAlpha(")
        )
        assertFalse("the app context sheet layout went with the sheet", File("src/main/res/layout/nova_app_context_sheet.xml").exists())
    }

    @Test
    fun themePickerCaptionsFitAndPanelsKeepAThemedEdge() {
        val panelFrame = File("src/main/java/com/papi/nova/ui/panel/NovaPanelFrame.kt").readText()
        val captions = File("src/main/res/values/strings_ui_hosts.xml").readText()

        // The picker moved onto the panel foundation, whose rows keep their focus ring inside the
        // row and whose captions wrap to two lines (spec R13). Its captions are short enough for
        // two lines beside a swatch in a Standard panel.
        Regex("<string name=\"(hosts_theme_caption_[a-z_]+)\">([^<]+)</string>").findAll(captions).forEach { caption ->
            assertTrue("${caption.groupValues[1]} keeps to two lines: ${caption.groupValues[2]}", caption.groupValues[2].length <= 48)
        }
        assertTrue(
            "a panel draws a themed hairline on its inner edge for a clean edge against what is behind it",
            panelFrame.contains("val hairline = surfaces.panelBorder") && panelFrame.contains("NovaPanelMetrics.Hairline.toPx()")
        )
    }

    @Test
    fun legacyFocusableDrawablesUseThemeAttrsInsteadOfStaticPolarisAccent() {
        // nova_dialog_choice_bg.xml went with the legacy list dialog it drew: legacy lists are
        // Choice pages now, drawn by the panel foundation. nova_server_row_focus_ring.xml went
        // when the host row took the one focus ring.
        val drawableFiles = listOf(
            "src/main/res/drawable/nova_chip_default.xml",
            "src/main/res/drawable/nova_chip_selected.xml",
            "src/main/res/drawable/nova_featured_action_bg.xml",
            "src/main/res/drawable/nova_card_focus_ring.xml"
        )
        drawableFiles.forEach { path ->
            val xml = File(path).readText()
            assertFalse("$path must not hardcode old Polaris violet", xml.contains("7C73FF", ignoreCase = true))
            assertFalse("$path must not bind reusable focus chrome to global nova_accent", xml.contains("@color/nova_accent"))
        }
    }


    @Test
    fun sheetChromeUsesSharedTranslucentGlassForNovaHudFriendlyDrawers() {
        val sheetChrome = File("src/main/java/com/papi/nova/ui/NovaSheetChrome.kt").readText()
        val composeTheme = File("src/main/java/com/papi/nova/ui/compose/NovaComposeTheme.kt").readText()
        val gameDetail = File("src/main/java/com/papi/nova/ui/NovaGameDetailActivity.kt").readText() +
            File("src/main/java/com/papi/nova/ui/NovaGameDetailContent.kt").readText() +
            File("src/main/java/com/papi/nova/ui/NovaGameDetailOverview.kt").readText() +
            File("src/main/java/com/papi/nova/ui/NovaGameDetailDestinations.kt").readText()

        assertTrue("panel glass keeps a named alpha contract", sheetChrome.contains("SHEET_GLASS_ALPHA"))
        assertTrue("high contrast can remain more opaque for readability", sheetChrome.contains("HIGH_CONTRAST_SHEET_GLASS_ALPHA"))
        // The View sheets' 0.22 scrim and their action rows went with them (spec section 2): the
        // panel frame draws the scrim, and panel rows take the one focus look.
        assertTrue("Compose library surfaces should reuse shared glass alpha language", composeTheme.contains("NovaSheetChrome.SHEET_GLASS_ALPHA"))
        // There was an assertion here that the game detail window reuses the sheet radius
        // token. It contradicted that window's own rule -- it raises no sheet, and
        // NovaLaunchSourceGuardTest asserts as much -- and it passed only because a
        // composable nothing ever called still mentioned the token. The window's radii come
        // from NovaRadius, as the drawer's does now.
        assertTrue("game detail must not reach for sheet chrome, since it raises no sheet", !gameDetail.contains("NovaSheetChrome."))
    }


    @Test
    fun menuOpacityWiresLiteralOuterSurfacesWithoutCouplingNovaHud() {
        val composeTheme = File("src/main/java/com/papi/nova/ui/compose/NovaComposeTheme.kt").readText()
        val quickMenuContent = File("src/main/java/com/papi/nova/ui/NovaQuickMenuContent.kt").readText()
        val streamHud = File("src/main/java/com/papi/nova/ui/NovaStreamHud.kt").readText()
        val streamHudContent = File("src/main/java/com/papi/nova/ui/NovaStreamHudContent.kt").readText()

        // The View sheet and alert chrome went with the last View sheet; the panel frame's scrim
        // scales with the same menu opacity preference.
        assertTrue(
            "panel scrims should expose a preference-scaled alpha",
            File("src/main/java/com/papi/nova/ui/panel/NovaPanelFrame.kt").readText().contains("NovaMenuPreferences.readabilityScrimAlpha(")
        )
        assertTrue("Compose menus should publish one menu opacity composition local", composeTheme.contains("LocalNovaMenuOpacityScale"))
        assertTrue("Compose menu surfaces should receive the current opacity scale", composeTheme.contains("librarySurfaces(theme, menuOpacityScale)"))
        assertTrue(
            "Compose outer panels should resolve absolute opacity rather than multiply theme glass",
            composeTheme.contains("NovaMenuPreferences.outerSurfaceAlpha(") &&
                composeTheme.contains("opacityScale = opacityScale") &&
                !composeTheme.contains("surfaces.panel.alpha * opacityScale")
        )
        // The Command Center is a page in the panel frame now; the frame draws the one outer panel.
        val panelFrame = File("src/main/java/com/papi/nova/ui/panel/NovaPanelFrame.kt").readText()
        assertTrue(
            "Command Center outer panel should consume the shared absolute panel, drawn once by the panel frame",
            panelFrame.contains(".background(surfaces.panel)") && !quickMenuContent.contains(".background(surfaces.panel)")
        )
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
        val spinnerDialog = File("src/main/java/com/papi/nova/utils/SpinnerDialog.kt").readText()
        val game = File("src/main/java/com/papi/nova/Game.kt").readText()
        val quitBody = game.substringAfter("fun quit() {").substringBefore("override fun showGameMenu")
        // The spinner is a Busy state page now, drawn by NovaStateScreen rather than a themed alert.
        val busyPage = File("src/main/java/com/papi/nova/ui/panel/NovaStateScreen.kt").readText()
            .substringAfter("private fun BusyContent(").substringBefore("private fun CodeContent(")

        assertTrue(
            "establishing-session spinner should draw as Nova's own full-screen Busy page, not a platform dialog",
            spinnerDialog.contains("NovaStatePage.Busy(") && !spinnerDialog.contains("AlertDialog")
        )
        assertTrue("spinner should tint progress from the active Nova theme at runtime", busyPage.contains("CircularProgressIndicator(") && busyPage.contains("color = colors.accent"))
        assertFalse("the spinner's View layout went with the platform dialog that drew it", File("src/main/res/layout/nova_spinner_dialog.xml").exists())
        // Ending splits in place wherever there is a button; quit() is the fallback for paths with
        // none, a destructive Confirm page in the panel window, which shares the panel's glass.
        assertTrue("quit confirmation should be a destructive panel Confirm page, not a sheet of its own", quitBody.contains("NovaCommonPage.Confirm(") && quitBody.contains("destructive = true") && !quitBody.contains("BottomSheetDialog"))
        assertTrue("quit confirmation should present in the stream's or the companion display's panel window", quitBody.contains("surfaces.present("))
        assertFalse("quit confirmation should not use a raw AlertDialog shell", quitBody.contains("AlertDialog.Builder"))
        assertFalse("quit confirmation should not use platform dialog buttons", quitBody.contains("setPositiveButton") || quitBody.contains("setNegativeButton"))
        assertTrue("quit confirmation should use Nova-themed session action copy", game.contains("R.string.game_dialog_action_end_session") && game.contains("R.string.nova_panel_stay"))
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

        val composeTheme = File("src/main/java/com/papi/nova/ui/compose/NovaComposeTheme.kt").readText()
        assertTrue("Command Center state should read the saved menu opacity", quickMenu.contains("NovaMenuPreferences.readOpacityPercent(prefs)"))
        assertTrue("Command Center should expose a menu-opacity callback", quickMenu.contains("onMenuOpacityChange = { percent ->"))
        // The row shows a step at once; the preference is written once the steps stop, and the
        // panel window's theme follows the saved preference, so the whole panel changes with it.
        assertTrue(
            "the open Command Center should follow its live opacity: the row at once, the panel once the preference is written",
            quickMenu.contains("pendingMenuOpacity = percent") &&
                quickMenu.contains("NovaMenuPreferences.writeOpacityPercent(game, percent)") &&
                composeTheme.contains("registerOnSharedPreferenceChangeListener")
        )
        assertTrue("Command Center content should render the independent menu opacity control", content.contains("NovaQuickMenuMenuOpacityControl"))
        val menuOpacityControl = content
            .substringAfter("private fun NovaPageScope.NovaQuickMenuMenuOpacityControl(")
            .substringBefore("\n@Composable")
        assertTrue("the rendered control should offer every preset from state", menuOpacityControl.contains("menuOpacity.presets.map"))
        assertTrue("each preset step should dispatch the menu-opacity callback", menuOpacityControl.contains("onChange = callbacks.onMenuOpacityChange"))
        assertTrue("the control should be one controller-sized value row that steps in place", menuOpacityControl.contains("NovaValueRow("))
        // The Command Center keeps no glass constants of its own since its rows and cards became the
        // panels' one row tile; the tile follows the menu opacity through the surfaces it is read from.
        assertTrue(
            "Command Center boxes should follow the menu opacity: they are the row tile, which the library surfaces scale by it",
            content.contains("rest = novaRowRest") &&
                !content.contains("NovaInGameOverlayAlpha.") &&
                composeTheme.contains("tile = surfaces.tile.copy(alpha = surfaces.tile.alpha * opacityScale)") &&
                composeTheme.contains("tileBorder = surfaces.tileBorder.copy(alpha = surfaces.tileBorder.alpha * opacityScale)")
        )
        assertTrue("Command Center should label the new control as Menu Opacity", strings.contains("<string name=\"nova_quick_menu_menu_opacity\">Menu Opacity</string>"))
    }

    @Test
    fun settingsMenuOpacitySliderPreviewsLiveAndRestoresOnCancel() {
        val settings = File("src/main/java/com/papi/nova/preferences/NovaSettingsScreen.kt").readText()
        // The slider dialog is the exact-value Slider page now; its onPreview streams the value.
        val sliderPage = settings
            .substringAfter("private fun openSlider(")
            .substringBefore("private fun NovaSettingsCompactHeader(")

        assertTrue("Settings should preview Menu Opacity through owner-scoped process-local state while the page is on top", settings.contains("NovaMenuOpacityPreview.update(owner, percent)") && settings.contains("NovaMenuOpacityPreview.newOwner()") && sliderPage.contains("onPreview = if (opacity) menuOpacity::update else null"))
        assertFalse("live preview must not write the durable SharedPreferences key", settings.contains("NovaMenuPreferences.writeOpacityPercent(prefs"))
        assertTrue("leaving the page and saving should clear only the owning temporary preview", settings.contains("NovaMenuOpacityPreview::clear") && settings.contains("previewOwnerAtSave") && settings.contains("if (topKey != MENU_OPACITY_PAGE_KEY) preview.clear()"))
        assertTrue("Save should persist through the repository before clearing preview", sliderPage.contains("onValue(definition, NovaSettingValue.IntValue(value)) {") && sliderPage.contains("previewOwnerAtSave?.let(NovaMenuOpacityPreview::clear)"))
        assertFalse("no Material slider dialog is left to restyle", settings.contains("AlertDialog(") || settings.contains("containerColor = surfaces.panel"))
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

        // Session start now wears the state pages' colour: the window at 0.94, a contrast floor
        // of its own; reconnecting is a Busy state page drawn the same way.
        assertTrue("session start should keep a contrast floor under its words", lifecycle.contains("colors.window.copy(alpha = NovaPanelMetrics.StatePageAlpha)"))
        // Library Options and System are panels in one window now; the panel frame draws their scrim.
        val panelFrame = File("src/main/java/com/papi/nova/ui/panel/NovaPanelFrame.kt").readText()
        assertTrue(
            "Compose library modal scrims should retain a contrast floor",
            library.contains("surfaces.open(") && panelFrame.contains("NovaMenuPreferences.readabilityScrimAlpha(")
        )
        assertTrue(
            "both Library Options and System drawer scrims should use the readability floor: both open through the one panel window",
            library.contains("private fun openLibraryPanel(root: LibraryPage)") &&
                library.contains("openLibraryPanel(LibraryPage.Options(") &&
                library.contains("openLibraryPanel(LibraryPage.System(")
        )
        assertTrue("Library fixed glass overrides should consume the menu opacity local", library.contains("LocalNovaMenuOpacityScale.current"))
        // Game detail's fixed glass overrides were the dead sheet panel's, and went with it: what is
        // left draws the theme's surfaces, which carry the menu opacity already.
        assertFalse(
            "game detail should keep no fixed glass override that ignores the menu opacity",
            Regex("""surfaces\.(panel|tile|control|selectedControl)\.copy\(alpha = [0-9.]+f\)""").containsMatchIn(gameDetail)
        )
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
        val quickMenuHost = File("src/main/java/com/papi/nova/ui/NovaQuickMenu.kt").readText()
        val quickMenu = File("src/main/java/com/papi/nova/ui/NovaQuickMenuContent.kt").readText()
        val library = File("src/main/java/com/papi/nova/ui/NovaLibraryActivity.kt").readText()
        val settings = File("src/main/java/com/papi/nova/preferences/NovaSettingsScreen.kt").readText()
        val progress = File("src/main/java/com/papi/nova/ui/SessionProgressOverlay.kt").readText()
        val reconnect = File("src/main/java/com/papi/nova/ui/ReconnectOverlay.kt").readText()

        assertTrue("adaptive blur should use API 31 RenderEffect rather than optional cross-window blur", blur.contains("Build.VERSION_CODES.S") && blur.contains("RenderEffect.createBlurEffect"))
        assertTrue("blur cleanup must be owner-scoped for overlapping overlays", blur.contains("class BlurLease") && blur.contains("ownerRadiiDp") && blur.contains("applyStrongestOwnedEffect"))
        assertTrue("all View and dialog mutations should be main-thread confined", blur.contains("Looper.myLooper() == Looper.getMainLooper()") && blur.contains("requireMainThread()"))
        assertFalse("no dialog leases a blur through a binding of its own: NovaPanelWindow's frame leases the activity backdrop", blur.contains("fun attachBehindDialog("))
        assertTrue("releasing an owner should recompute the strongest remaining radius", blur.contains("state.ownerRadiiDp.remove(owner)") && blur.contains("state.ownerRadiiDp.values.maxOrNull()"))
        assertTrue("Compose drawers should lease the Activity backdrop and release only their own effect", composeBlur.contains("NovaMenuBlur.acquireActivityBackground") && composeBlur.contains("lease?.release()"))
        // The Command Center opens in the panel window. Over the stream the frame draws only the
        // Command Center scrim; the backdrop blur is for screens (spec section 2).
        val panelFrame = File("src/main/java/com/papi/nova/ui/panel/NovaPanelFrame.kt").readText()
        assertTrue("Command Center must live in the separate panel window", quickMenuHost.contains("surfaces.open(root, NovaEdge.Start)"))
        assertTrue(
            "the panel frame blurs the backdrop over screens only, and the Command Center adds none of its own",
            panelFrame.contains("if (scrim == NovaScrim.Screen) NovaMenuBackdropBlur()") && !quickMenu.contains("NovaMenuBackdropBlur()")
        )
        // Library Options and System are panels in their own window, whose frame leases the blur;
        // Play Setup's panel is in the detail window's own tree, so it must not blur its window.
        val playSetupPages = File("src/main/java/com/papi/nova/ui/NovaPlaySetupPages.kt").readText()
        assertTrue(
            "Library drawers should blur only while a separate-window drawer is active",
            library.contains("surfaces.open(") &&
                panelFrame.contains("if (scrim == NovaScrim.Screen) NovaMenuBackdropBlur()") &&
                !library.contains("NovaMenuBackdropBlur()")
        )
        assertFalse("same-window filter sheets must not blur their own controls through the Activity decor", library.contains("activeFilterSheet != null || activeOptionsSheet"))
        assertTrue(
            "an in-tree panel must not blur its own controls through the Activity decor",
            playSetupPages.contains("scrim = NovaScrim.Stream") && !playSetupPages.contains("NovaScrim.Screen")
        )
        assertTrue(
            "Settings editors are pages in the Settings pane itself, and the legacy screen's open in NovaPanelWindow, whose frame blurs what is under it",
            settings.contains("NovaPageStackHost(") && !settings.contains("Dialog(") &&
                File("src/main/java/com/papi/nova/ui/panel/NovaPanelFrame.kt").readText().contains("NovaMenuBackdropBlur()")
        )
        assertTrue("Compose contrast scrims should use the stronger dark-text floor", composeTheme.contains("usesDarkText = textPrimary.luminance() < 0.5f"))
        assertFalse("Settings has no select dialog left to draw its own window scrim; its lists are pages", settings.contains("NovaDialogContrastBackdrop()"))
        assertTrue("session startup should release leases on explicit dismissal and unexpected view detach", progress.contains("NovaMenuBlur.acquireChildren") && progress.contains("releaseBackgroundBlur") && progress.contains("releaseOnUnexpectedDetach"))
        assertTrue("reconnecting should be a Busy state page, which holds no blur lease to leak", reconnect.contains("NovaStatePage.Busy(") && !reconnect.contains("NovaMenuBlur"))
    }

    @Test
    fun requiredNativeAlertsUseSharedOpacityAndBlurChrome() {
        val gameDetail = File("src/main/java/com/papi/nova/ui/NovaGameDetailActivity.kt").readText() +
            File("src/main/java/com/papi/nova/ui/NovaGameDetailContent.kt").readText() +
            File("src/main/java/com/papi/nova/ui/NovaGameDetailOverview.kt").readText() +
            File("src/main/java/com/papi/nova/ui/NovaGameDetailDestinations.kt").readText()
        val legacySlider = File("src/main/java/com/papi/nova/preferences/SeekBarPreference.kt").readText()
        val sessionDialog = File("src/main/java/com/papi/nova/utils/Dialog.kt").readText()
        val panelFrame = File("src/main/java/com/papi/nova/ui/panel/NovaPanelFrame.kt").readText()

        val preflight = gameDetail
            .substringAfter("private fun showPreflightReview(")
            .substringBefore("private fun showDesktopSteamLaunchDecision(")
        assertTrue(
            "the game detail window raises no legacy alert at all: the preflight review expands the status line in place instead",
            !preflight.contains("AlertDialog.Builder") && preflight.contains("reviewExpanded")
        )
        assertTrue(
            "legacy sliders, including Menu & Drawer Opacity, open a Slider page on the screen's NovaSurfaces, whose panel uses the shared menu opacity and blur",
            legacySlider.contains("NovaSurfaces.of(activity).open(") && legacySlider.contains("NovaCommonPage.Slider(") &&
                !legacySlider.contains("AlertDialog")
        )
        assertTrue(
            "session termination/error messages should post to NovaSurfaces, whose panel uses the shared menu opacity and blur",
            sessionDialog.contains("NovaSurfaces.of(activity)") &&
                sessionDialog.contains("NovaStatePage.Problem(") &&
                sessionDialog.contains("NovaCommonPage.Notice(") &&
                panelFrame.contains(".background(surfaces.panel)") &&
                panelFrame.contains("NovaMenuBackdropBlur()")
        )
    }

    @Test
    fun legacyQuickMenuExtrasArePanelPagesInsteadOfRawAlertLists() {
        // The legacy Quick Menu is gone. Its extras are the Command Center's More Controls page,
        // its key list the Keys page, and its empty server commands a disabled row with its reason.
        assertFalse("the legacy Quick Menu sheet must not come back", File("src/main/java/com/papi/nova/GameMenu.kt").exists())
        val pages = File("src/main/java/com/papi/nova/ui/NovaCommandCenterPages.kt").readText()
        val menu = File("src/main/java/com/papi/nova/ui/NovaQuickMenu.kt").readText()
        assertTrue("the extras draw as panel rows under section labels", pages.contains("NovaSectionLabel(title)") && pages.contains("NovaRow("))
        for (source in listOf(pages, menu)) {
            assertFalse("no raw AlertDialog.Builder for a menu shell", source.contains("AlertDialog.Builder"))
            assertFalse("no Android simple_list_item_1 rows", source.contains("android.R.layout.simple_list_item_1"))
            assertFalse("no ArrayAdapter-backed legacy rows", source.contains("ArrayAdapter"))
        }
        assertTrue(
            "no server commands is a disabled row that says why, not a dialog with no buttons",
            menu.contains("disabledReason = game.getString(R.string.game_dialog_message_server_cmd_empty)")
        )
    }


    @Test
    fun noAvcDecoderErrorDismissesSessionProgressOverlayBeforeDialog() {
        val game = File("src/main/java/com/papi/nova/Game.kt").readText()
        // The condition, not the whole line: it gained a second clause when a renderer arrived that
        // reports no H.264 because it offers none, and this test is about what the block does rather
        // than what decides to enter it.
        val noAvcBlock = game.substringAfter("!decoderRenderer!!.isAvcSupported").substringBefore("return")

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
}
