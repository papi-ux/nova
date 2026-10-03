package com.papi.nova.preferences

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NovaDisplayRoleComposerSourceTest {
    private fun source(name: String) = File("src/main/java/com/papi/nova/preferences/$name").readText()
    private fun utilsSource(name: String) = File("src/main/java/com/papi/nova/utils/$name").readText()

    @Test
    fun composeAndLegacySettingsUseTheSpecializedComposer() {
        val screen = source("NovaSettingsScreen.kt")
        val legacy = source("StreamSettings.kt")
        val composer = source("NovaDisplayRoleComposer.kt")

        // One page for both screens: pushed in the Compose pane, a right-edge page on the legacy one.
        assertTrue(screen.contains("PreferenceConfiguration.ANDROID_STREAM_DISPLAY_TARGET_PREF_STRING"))
        assertTrue(screen.contains("SettingsPage.DisplayRole("))
        assertTrue(screen.contains("is SettingsPage.DisplayRole -> NovaDisplayRolePage(page)"))
        assertTrue(legacy.contains("preference.key == PreferenceConfiguration.ANDROID_STREAM_DISPLAY_TARGET_PREF_STRING"))
        assertTrue(legacy.contains("SettingsPage.DisplayRole("))
        assertTrue(legacy.contains("NovaDisplayRolePage(shown)"))
        assertTrue(composer.contains("fun NovaPageScope.NovaDisplayRolePage("))
        assertTrue(composer.contains("AndroidDisplayRolePlan.build("))
    }

    // Was legacyComposerUsesOpaqueAppCompatDialogHost: the legacy composer sat in a Compose card
    // inside an AppCompat dialog. It is a page in NovaPanelWindow now, which draws the panel
    // surface itself, so there is no dialog host of its own to keep opaque.
    @Test
    fun legacyComposerOpensInThePanelWindow() {
        val legacy = source("StreamSettings.kt")

        assertTrue(legacy.contains("NovaSurfaces.of(activity).open("))
        assertFalse(legacy.contains("NovaDisplayRoleComposerDialogFragment"))
        assertFalse(legacy.contains("PreferenceDialogFragmentCompat"))
        assertFalse(legacy.contains("android.app.Dialog(context)"))
    }

    @Test
    fun liveDisplaySnapshotsAndExplicitApplyRemainLifecycleSafe() {
        val composer = source("NovaDisplayRoleComposer.kt")
        val applyBlock = composer.substringAfter("fun NovaDisplayRoleComposerActions(")

        assertTrue(composer.contains("DisplayManager.DisplayListener"))
        assertTrue(composer.contains("registerDisplayListener"))
        assertTrue(composer.contains("unregisterDisplayListener"))
        assertTrue(composer.contains("onDisplayAdded"))
        assertTrue(composer.contains("onDisplayChanged"))
        assertTrue(composer.contains("onDisplayRemoved"))
        assertTrue(composer.contains("roleState.canApply"))
        assertTrue(composer.contains("R.string.display_role_next_stream"))
        assertTrue(applyBlock.contains("onApply"))
        // Cancel is B and the page header: leaving applies nothing, and Apply hands the target on
        // only after the page has gone.
        assertTrue(composer.contains("if (!panel.pop()) panel.close()\n                        page.onApply(target)"))
        assertFalse(composer.contains("androidx.preference.internal"))
    }

    // Swap sat with the dialog's pinned actions so the body's clip could never hide it. The page
    // has no clip: Swap and Apply are the last items of the page's own list, which scrolls them
    // into view with a row of context like any other row.
    @Test
    fun swapAndApplyAreRowsOfThePageInsteadOfClippedInsideTheScrollableBody() {
        val composer = source("NovaDisplayRoleComposer.kt")
        val page = composer.substringAfter("fun NovaPageScope.NovaDisplayRolePage(")
            .substringBefore("private fun NovaDisplayRoleRouteSummary(")
        val actions = composer.substringAfter("fun NovaDisplayRoleComposerActions(")
            .substringBefore("private fun roleLabel(")

        assertTrue(page.contains("state = listState"))
        assertTrue(page.contains("item(key = \"actions\")"))
        assertFalse(composer.contains(".clipToBounds()"))
        assertTrue(actions.contains("onSwap: () -> Unit"))
        assertTrue(actions.contains("stringResource(R.string.display_role_swap)"))
    }

    @Test
    fun composerAndRuntimeShareOrderedRealMetricDisplayCandidates() {
        val composer = source("NovaDisplayRoleComposer.kt")
        val runtime = utilsSource("ServerHelper.kt")
        val adapter = File("src/main/java/com/papi/nova/utils/AndroidDisplayCandidateAdapter.kt")

        assertTrue(adapter.exists())
        assertTrue(composer.contains("AndroidDisplayCandidateAdapter.from(display)"))
        assertTrue(runtime.contains("AndroidDisplayCandidateAdapter.from(display)"))
        assertFalse(composer.contains(".sortedWith("))
        assertFalse(composer.contains("currentMode.physicalWidth"))
        assertFalse(composer.contains("currentMode.physicalHeight"))
    }

    // The dialog's actions wrapped in a FlowRow; the page's pair sits side by side while both
    // labels fit and stacks at full width otherwise, so neither is ever cut.
    @Test
    fun compactPageWrapsItsActions() {
        val composer = source("NovaDisplayRoleComposer.kt")
        val actions = composer.substringAfter("fun NovaDisplayRoleComposerActions(")
            .substringBefore("private fun roleLabel(")

        assertTrue(actions.contains("NovaPanelButtonPair("))
    }

    @Test
    fun roleChoicesExposeSelectionAndActivationSemantics() {
        val composer = source("NovaDisplayRoleComposer.kt")
        val choice = composer.substringAfter("private fun NovaDisplayRoleChoice(")
            .substringBefore("fun NovaDisplayRoleComposerActions(")

        assertTrue(choice.contains(".novaClickable(enabled = enabled, role = Role.RadioButton"))
        assertTrue(choice.contains("this.selected = selected"))
        assertTrue(choice.contains("stateDescription = selectionState"))
        // R9: the choice in effect carries the check, never a fill or a border.
        assertTrue(choice.contains("NovaCurrentMark()"))
        assertTrue(composer.contains("R.string.display_role_card_action_description"))
        assertTrue(composer.contains("R.string.display_role_follow_action_description"))
    }

    @Test
    fun composerCopyIsResourceBacked() {
        val strings = File("src/main/res/values/strings.xml").readText()
        val names = listOf(
            "title_display_role_composer",
            "display_role_follow",
            "display_role_stream",
            "display_role_companion",
            "display_role_current",
            "display_role_pending",
            "display_role_apply",
            "display_role_swap",
            "display_role_next_stream",
            "display_role_resolution_refresh",
            "display_role_recovery_single",
            "display_role_recovery_unavailable",
            "display_role_recovery_unknown",
            "display_role_card_action_description",
            "display_role_card_unavailable_description",
            "display_role_follow_action_description",
            "display_role_selection_state_selected",
            "display_role_selection_state_not_selected",
        )
        names.forEach { name ->
            assertTrue("missing resource-backed composer copy: $name", strings.contains("name=\"$name\""))
        }
    }

    @Test
    fun settingsSurfaceUsesDisplayRoleProductVocabulary() {
        val strings = File("src/main/res/values/strings.xml").readText()

        assertTrue(strings.contains(">Display roles</string>"))
        assertTrue(strings.contains(">Follow</string>"))
        assertTrue(strings.contains(">Stream on this device</string>"))
        assertTrue(strings.contains(">Stream on a connected display</string>"))
        assertTrue(strings.contains(">Stream on the largest screen</string>"))
        assertTrue(strings.contains("other connected display becomes Companion"))
        assertFalse(strings.contains("Android stream display"))
        assertFalse(strings.contains("Auto keeps the old first-external behavior"))
        assertFalse(strings.contains("Auto first external"))
        assertFalse(strings.contains("First external display"))
    }
}
