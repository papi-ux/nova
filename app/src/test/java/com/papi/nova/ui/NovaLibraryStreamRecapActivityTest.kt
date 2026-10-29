package com.papi.nova.ui

import android.content.Context
import android.content.Intent
import android.os.Looper
import androidx.compose.runtime.MutableState
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performSemanticsAction
import androidx.preference.PreferenceManager
import androidx.test.core.app.ApplicationProvider
import com.papi.nova.TestLogSuppressor
import com.papi.nova.api.PolarisApiClient
import com.papi.nova.diagnostics.NovaStreamRecap
import com.papi.nova.diagnostics.NovaStreamRecap.End
import com.papi.nova.diagnostics.NovaStreamRecapStore
import com.papi.nova.diagnostics.NovaStreamRecapTest.Companion.launch
import com.papi.nova.diagnostics.NovaStreamRecapTest.Companion.sample
import com.papi.nova.shared.polaris.model.PolarisGame
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.mock
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

/**
 * End returns to a Library that resumes, and reads the recap store, before Game's onStop publishes
 * the stream's recap. While resumed the Library hears that publication and shows the recap in its
 * host bar without leaving the screen, for its own host only, and Dismiss still removes only the
 * recap it shows. Once it has left it reads the store again on its next resume, as before.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "w833dp-h468dp-land")
@LooperMode(LooperMode.Mode.PAUSED)
class NovaLibraryStreamRecapActivityTest {
    @get:Rule
    val rule = createEmptyComposeRule()

    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val store = NovaStreamRecapStore.process
    private val game = PolarisGame(id = "active", name = "Control Ultimate Edition", source = "steam", launcherSource = "steam")
    private val generations = mutableListOf<Long>()

    /** The store is the process's own: leave it holding nothing these tests published. */
    @After fun clearTheProcessStore() {
        generations.forEach { store.dismiss(it) }
    }

    /** One Game instance as the source drives it: begun, launched for [host], connected and sampled. */
    private inner class GameRun(host: String? = HOST) {
        val generation = store.begin().also { generations += it }
        private val accumulator = NovaStreamRecap.Accumulator(generation)

        init {
            accumulator.launch(launch(host))
            accumulator.connected(1_000L)
            accumulator.accept(sample(2_000L, expected = 60), 2_000L)
            accumulator.accept(sample(3_000L, expected = 120), 3_000L)
        }
        /** Game.onStop's stopConnection: the recap is finished and published, once. */
        fun stop(): Boolean =
            accumulator.finish(End.END_REQUESTED, null, 4_000L, "2026-10-08T14:00:00Z")?.let(store::publish) ?: false
    }

    @Test fun aRecapPublishedAfterTheLibraryResumedIsShownInPlace() = publishedWhileResumedIsShown()

    @Test @Config(qualifiers = "w412dp-h915dp-port")
    fun aRecapPublishedAfterThePortraitLibraryResumedIsShownInPlace() = publishedWhileResumedIsShown()

    private fun publishedWhileResumedIsShown() = withLibrary { activity ->
        // End: the Library has resumed and read the store, and the stream's Game hasn't stopped yet.
        assertNull(recap(activity).value)
        cards().assertCountEquals(0)
        val ended = GameRun()
        assertTrue(ended.stop())
        rule.waitForIdle()
        assertEquals(ended.generation, recap(activity).value?.generation)
        cards().assertCountEquals(1)
    }

    @Test fun dismissStillRemovesOnlyTheRecapItShows() = withLibrary { activity ->
        assertTrue(GameRun().stop())
        rule.waitForIdle()
        val earlier = recap(activity).value!!
        val newer = GameRun()
        assertTrue(newer.stop())
        rule.waitForIdle()
        assertEquals("the newer stream's recap replaces it in place", newer.generation, recap(activity).value?.generation)

        // A stale card's Dismiss, for the recap shown before, leaves the newer one.
        dismiss(activity, earlier)
        rule.waitForIdle()
        assertEquals(newer.generation, recap(activity).value?.generation)
        cards().assertCountEquals(1)

        rule.onNodeWithTag(NOVA_STREAM_RECAP_DISMISS_TAG).performSemanticsAction(SemanticsActions.OnClick)
        rule.waitForIdle()
        assertNull(recap(activity).value)
        assertNull(store.forHost(HOST))
        cards().assertCountEquals(0)
    }

    @Test fun aRecapForAnotherHostOrForNoneIsNeverShown() = withLibrary { activity ->
        assertTrue(GameRun(host = "host-entry-2").stop())
        rule.waitForIdle()
        assertNull(recap(activity).value)
        assertTrue(GameRun(host = null).stop())
        rule.waitForIdle()
        assertNull(recap(activity).value)
        cards().assertCountEquals(0)
    }

    @Test fun aStreamWhoseGameTheLaunchHandoffRecreatedIsShown() = withLibrary { activity ->
        // The launch-policy gate recreates Game before its stream starts. The retiring instance began
        // first, but onCreate returned at the gate before streamRecap.launch, and its onStop returns
        // before stopConnection. The replacement began last, launched, connected and stops at End.
        val retiring = NovaStreamRecap.Accumulator(store.begin().also { generations += it })
        val replacement = GameRun()
        assertTrue(replacement.stop())
        assertNull(retiring.finish(End.UNKNOWN, null, 5_000L, "2026-10-08T14:00:00Z"))
        rule.waitForIdle()
        assertEquals(replacement.generation, recap(activity).value?.generation)
        cards().assertCountEquals(1)
    }

    @Test fun afterLeavingTheLibraryReadsTheStoreWhenItResumes() {
        val controller = library()
        try {
            val activity = controller.get()
            controller.pause()
            idle()
            val ended = GameRun()
            assertTrue(ended.stop())
            idle()
            assertNull("a Library that has left no longer listens", recap(activity).value)
            controller.resume()
            rule.waitForIdle()
            assertEquals(ended.generation, recap(activity).value?.generation)
        } finally {
            close(controller)
        }
    }

    private fun withLibrary(check: (NovaLibraryActivity) -> Unit) {
        val controller = library()
        try {
            check(controller.get())
        } finally {
            close(controller)
        }
    }

    private fun library(): ActivityController<NovaLibraryActivity> {
        PreferenceManager.getDefaultSharedPreferences(context).edit()
            .putString("nova_library_layout_mode", NovaLibraryLayoutMode.STAGE.name)
            .commit()
        // A host that answers nothing: the library is given its games below.
        val intent = Intent(context, NovaLibraryActivity::class.java)
            .putExtra(NovaLibraryActivity.EXTRA_HOST, "127.0.0.1")
            .putExtra(NovaLibraryActivity.EXTRA_HTTPS_PORT, 9)
            .putExtra(NovaLibraryActivity.EXTRA_HTTP_PORT, 9)
            .putExtra(NovaLibraryActivity.EXTRA_SERVER_NAME, "test-pc")
            .putExtra(NovaLibraryActivity.EXTRA_PC_UUID, HOST)
        val controller = Robolectric.buildActivity(NovaLibraryActivity::class.java, intent).create()
        val activity = controller.get()
        NovaLibraryActivity::class.java.getDeclaredField("apiClient").apply {
            isAccessible = true
            set(activity, mock(PolarisApiClient::class.java))
        }
        controller.start().resume().visible()
        idle()
        state<Boolean>(activity, "isInitialLoading").value = false
        state<String?>(activity, "loadErrorMessage").value = null
        state<List<PolarisGame>>(activity, "allGames").value = listOf(game)
        rule.waitForIdle()
        return controller
    }

    private fun close(controller: ActivityController<NovaLibraryActivity>) {
        controller.pause().stop().destroy()
        PreferenceManager.getDefaultSharedPreferences(context).edit().clear().commit()
    }

    private fun cards() = rule.onAllNodesWithTag(NOVA_STREAM_RECAP_CARD_TAG, useUnmergedTree = true)

    private fun recap(activity: NovaLibraryActivity): MutableState<NovaStreamRecap.Recap?> = state(activity, "lastStreamRecap")

    private fun dismiss(activity: NovaLibraryActivity, recap: NovaStreamRecap.Recap) {
        NovaLibraryActivity::class.java.getDeclaredMethod("dismissStreamRecap", NovaStreamRecap.Recap::class.java)
            .apply { isAccessible = true }.invoke(activity, recap)
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> state(activity: NovaLibraryActivity, name: String): MutableState<T> =
        NovaLibraryActivity::class.java.getDeclaredField(name + "\$delegate").run {
            isAccessible = true
            get(activity) as MutableState<T>
        }

    private fun idle() {
        repeat(3) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(50)
        }
        shadowOf(Looper.getMainLooper()).idle()
    }

    private companion object {
        const val HOST = "host-entry-1"

        @JvmStatic
        @BeforeClass
        fun suppressLogs() {
            TestLogSuppressor.install()
        }
    }
}
