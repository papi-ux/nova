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

        assertTrue(screen.contains("PreferenceConfiguration.ANDROID_STREAM_DISPLAY_TARGET_PREF_STRING"))
        assertTrue(screen.contains("NovaDisplayRoleComposerDialog"))
        assertTrue(legacy.contains("preference.key == PreferenceConfiguration.ANDROID_STREAM_DISPLAY_TARGET_PREF_STRING"))
        assertTrue(legacy.contains("NovaDisplayRoleComposerDialogFragment.newInstance(preference.key)"))
        assertTrue(composer.contains("fun NovaDisplayRoleComposerDialog("))
        assertTrue(composer.contains("fun NovaDisplayRoleComposerLegacyPanel("))
        assertTrue(composer.contains("AndroidDisplayRolePlan.build("))
    }

    @Test
    fun legacyComposerUsesOpaqueAppCompatDialogHost() {
        val legacy = source("StreamSettings.kt")
        val composer = source("NovaDisplayRoleComposer.kt")
        val panel = composer.substringAfter("fun NovaDisplayRoleComposerLegacyPanel(")
            .substringBefore("private fun NovaDisplayRoleComposerBody(")

        assertTrue(panel.contains(".background(surfaces.panel.copy(alpha = 1f))"))
        assertFalse(panel.contains("LocalNovaMenuOpacityScale.current"))
        assertTrue(legacy.contains("class NovaDisplayRoleComposerDialogFragment : PreferenceDialogFragmentCompat()"))
        assertTrue(legacy.contains("override fun onCreateDialogView(context: Context): View"))
        assertTrue(legacy.contains("override fun onPrepareDialogBuilder(builder: androidx.appcompat.app.AlertDialog.Builder)"))
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
        assertTrue(applyBlock.contains("onDismiss"))
        assertFalse(composer.contains("androidx.preference.internal"))
    }

    @Test
    fun swapIsPinnedWithDialogActionsInsteadOfClippedInsideTheScrollableBody() {
        val composer = source("NovaDisplayRoleComposer.kt")
        val body = composer.substringAfter("private fun NovaDisplayRoleComposerBody(")
            .substringBefore("private fun NovaDisplayRoleRouteSummary(")
        val actions = composer.substringAfter("fun NovaDisplayRoleComposerActions(")
            .substringBefore("private fun roleLabel(")

        assertFalse(body.contains("NovaDisplayRoleSwapAction("))
        assertTrue(body.contains(".clipToBounds()"))
        assertTrue(actions.contains("onSwap: () -> Unit"))
        assertTrue(actions.contains("Text(stringResource(R.string.display_role_swap))"))
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

    @Test
    fun compactDialogReservesActionSpaceAndWrapsActions() {
        val screen = source("NovaSettingsScreen.kt")
        val composer = source("NovaDisplayRoleComposer.kt")
        val shell = screen.substringAfter("internal fun NovaSelectDialogShell(")
            .substringBefore("private fun NovaDialogContrastBackdrop(")
        val legacyPanel = composer.substringAfter("fun NovaDisplayRoleComposerLegacyPanel(")
            .substringBefore("private fun NovaDisplayRoleComposerBody(")
        val actions = composer.substringAfter("fun NovaDisplayRoleComposerActions(")
            .substringBefore("private fun roleLabel(")

        assertTrue(shell.contains(".weight(1f, fill = false)"))
        assertTrue(legacyPanel.contains(".weight(1f, fill = false)"))
        assertTrue(actions.contains("FlowRow("))
    }

    @Test
    fun roleChoicesExposeSelectionAndActivationSemantics() {
        val composer = source("NovaDisplayRoleComposer.kt")

        assertTrue(composer.contains(".selectable("))
        assertTrue(composer.contains("role = Role.RadioButton"))
        assertTrue(composer.contains("stateDescription ="))
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
            "display_role_cancel",
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
