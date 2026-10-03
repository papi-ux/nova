package com.papi.nova.ui

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Source guards for the Hosts and App list screens.
 *
 * Moved verbatim out of NovaComposeSourceGuardTest, so each migration group owns the guards
 * on its own files.
 */
class NovaHostsSourceGuardTest {
    @Test
    fun legacyAppLibraryHeroExposesEndSessionForOwnedStreams() {
        val layout = readSource("src/main/res/layout/activity_app_view.xml")
        val source = readSource("src/main/java/com/papi/nova/AppView.kt")

        assertTrue(
            "legacy app library hero should include a dedicated End Session affordance",
            layout.contains("@+id/recently_played_end_session") &&
                source.contains("label = getString(R.string.applist_menu_quit)")
        )
        assertTrue(
            "end-session affordance should only show for this client's active stream",
            source.contains("endSessionView?.visibility = if (appIsRunning && !appOwnedByAnotherClient)")
        )
        assertTrue(
            "end-session affordance splits in place and ends through the same refresh path as the app panel",
            source.contains("endSessionView?.setNovaSplitConfirm(") &&
                source.contains("endRunningSessionFromLibrary(finalTargetApp.app)") &&
                source.contains("ServerHelper.doQuit")
        )
        assertTrue(
            "a split is the confirm, so ending never opens a second confirm of its own (spec R3)",
            !source.contains("UiHelper.displayQuitConfirmationDialog")
        )
        assertTrue(
            "library End Session should resume grid polling after either quit success or failure",
            source.contains("private fun quitRunningSessionAndRefresh(") &&
                source.contains("val resumeGridUpdates = Runnable") &&
                source.contains("ServerHelper.doQuit(this, activeComputer, app, binder, resumeGridUpdates, resumeGridUpdates)") &&
                readSource("src/main/java/com/papi/nova/utils/ServerHelper.kt")
                    .contains("onFail: Runnable?,")
        )
        assertTrue(
            "ServerHelper quit failure callbacks should run when the host reports quit failure",
            readSource("src/main/java/com/papi/nova/utils/ServerHelper.kt")
                .contains("if (httpConn.quitApp(sessionToken)) {\n                null\n            } else {\n                QuitRefusal(")
        )
    }

    private fun readSource(path: String): String =
        String(Files.readAllBytes(Path.of(path)), StandardCharsets.UTF_8)
}
