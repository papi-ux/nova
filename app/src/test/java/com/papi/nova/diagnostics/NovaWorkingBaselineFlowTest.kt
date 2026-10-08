package com.papi.nova.diagnostics

import android.content.Context
import com.papi.nova.diagnostics.NovaWorkingBaselineFixtures.entry
import com.papi.nova.diagnostics.NovaWorkingBaselineFixtures.snapshot
import com.papi.nova.diagnostics.NovaWorkingBaselineFlow.Compared
import com.papi.nova.diagnostics.NovaWorkingBaselineFlow.Outcome
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * The flow freezes the snapshot at the confirmed press, rechecks the stream there, compares
 * without writing, and touches nothing but its own store.
 */
@Config(sdk = [33])
@RunWith(RobolectricTestRunner::class)
class NovaWorkingBaselineFlowTest {
    private val app = RuntimeEnvironment.getApplication()
    private val prefs = app.getSharedPreferences(NovaWorkingBaselineStore.FILE, Context.MODE_PRIVATE)
    private val store = NovaWorkingBaselineStore(prefs)

    /** The stream's live state, which keeps changing while a confirmation is open. */
    private var active = true
    private var hasEntry = true
    private var liveFps = 59.8
    private var marked = "2026-10-08T13:00:00Z"
    private var snapshots = 0

    private val flow = NovaWorkingBaselineFlow(
        store = store,
        entry = { if (hasEntry) entry else null },
        isActive = { active },
        snapshot = {
            snapshots++
            if (active) snapshot(markedAtUtc = marked, renderedFps = liveFps) else null
        },
    )

    private fun savedFps() = flow.state().saved!!.readings.renderedFps!!

    @Test fun openingTheRowAndItsConfirmationFreezesNothing() {
        val state = flow.state()
        assertTrue(state.canSave)
        assertFalse(state.canCompare)
        assertEquals(0, snapshots)
        assertTrue(prefs.all.isEmpty())
    }

    @Test fun theSnapshotIsFrozenAtTheConfirmedPressNotWhenTheConfirmationOpened() {
        flow.state() // the confirmation opens over this reading
        liveFps = 31.0
        marked = "2026-10-08T13:05:00Z"
        assertEquals(Outcome.SAVED, flow.save())
        assertEquals(1, snapshots)
        assertEquals(31.0, savedFps(), 0.0)
        assertEquals("2026-10-08T13:05:00Z", flow.state().saved!!.markedAtUtc)
        liveFps = 12.0
        assertEquals(31.0, savedFps(), 0.0)
    }

    @Test fun aStreamThatStopsWhileTheConfirmationIsOpenSavesNothing() {
        assertTrue(flow.state().canSave)
        active = false
        assertEquals(Outcome.NOT_ACTIVE, flow.save())
        assertEquals(0, snapshots)
        assertTrue(prefs.all.isEmpty())
    }

    @Test fun replacingNeedsItsOwnConfirmedPressAndKeepsOneBaseline() {
        assertEquals(Outcome.SAVED, flow.save())
        liveFps = 45.0
        assertEquals(Outcome.REPLACED, flow.save())
        assertEquals(45.0, savedFps(), 0.0)
        assertEquals(1, prefs.all.keys.count { it.startsWith(NovaWorkingBaselineStore.ENTRY_PREFIX) })
    }

    @Test fun withoutARequestedEntryNothingCanBeSavedComparedOrCleared() {
        hasEntry = false
        val state = flow.state()
        assertFalse(state.hasEntry || state.canSave || state.canCompare || state.canClear)
        assertEquals(Outcome.NO_ENTRY, flow.save())
        assertEquals(Compared.NoEntry, flow.compare())
        assertEquals(Outcome.NO_ENTRY, flow.clear())
        assertTrue(prefs.all.isEmpty())
    }

    @Test fun compareIsReadOnlyAndSetsTheBaselineBesideAFreshSnapshot() {
        flow.save()
        val before = prefs.all
        liveFps = 40.0
        val shown = flow.compare() as Compared.Shown
        val rendered = shown.comparison.readings.single { it.item == NovaWorkingBaseline.ReadingItem.RENDERED_FPS }
        assertEquals(59.8, rendered.baseline!!, 0.0)
        assertEquals(40.0, rendered.now!!, 0.0)
        assertEquals(before, prefs.all)
    }

    @Test fun compareWithNoKeyOrACorruptEntryWritesNothingAndCreatesNoKey() {
        assertEquals(Compared.NoBaseline, flow.compare())
        assertTrue(prefs.all.isEmpty())
        flow.save()
        val name = prefs.all.keys.single { it.startsWith(NovaWorkingBaselineStore.ENTRY_PREFIX) }
        prefs.edit().putString(name, "{").commit()
        val corrupt = prefs.all
        assertEquals(Compared.Unreadable, flow.compare())
        assertEquals(corrupt, prefs.all)
        prefs.edit().remove(NovaWorkingBaselineStore.LOCAL_KEY).commit()
        val keyless = prefs.all
        assertEquals(Compared.Unreadable, flow.compare())
        assertEquals(keyless, prefs.all)
        assertFalse(NovaWorkingBaselineStore.LOCAL_KEY in prefs.all)
    }

    @Test fun compareNeedsARunningStream() {
        flow.save()
        active = false
        assertEquals(Compared.NotActive, flow.compare())
        assertFalse(flow.state().canCompare)
        assertTrue(flow.state().canClear)
    }

    @Test fun clearNeedsNoStreamAndOnlyRemovesThisBaseline() {
        flow.save()
        active = false
        assertEquals(Outcome.CLEARED, flow.clear())
        assertEquals(NovaWorkingBaselineStore.Loaded.None, flow.state().loaded)
    }

    @Test fun noFlowStepWritesAFileOrALog() {
        fun files() = listOf(app.filesDir, app.cacheDir).flatMap { root -> root.walk().filter(File::isFile).map { it.path }.toList() }.toSet()
        val before = files()
        flow.state(); flow.save(); flow.compare(); flow.clear()
        // Preferences live outside both roots; nothing else may appear in them.
        assertEquals(before, files())
        assertFalse(File(app.cacheDir, "moment-reports").exists())
    }
}
