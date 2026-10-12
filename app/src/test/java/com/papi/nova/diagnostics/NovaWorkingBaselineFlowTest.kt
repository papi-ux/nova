package com.papi.nova.diagnostics

import android.content.Context
import com.papi.nova.diagnostics.NovaWorkingBaselineFixtures.entry
import com.papi.nova.diagnostics.NovaWorkingBaselineFixtures.snapshot
import com.papi.nova.diagnostics.NovaWorkingBaselineFlow.Compared
import com.papi.nova.diagnostics.NovaWorkingBaselineFlow.Outcome
import com.papi.nova.ui.NovaCommandCenterRuntime
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * The flow freezes the snapshot at the confirmed press, rechecks the stream there, writes only
 * what it froze off the main thread, answers only the opening that asked, refuses a second change
 * while one is outstanding, compares without writing, and touches nothing but its own store.
 */
@Config(sdk = [33])
@RunWith(RobolectricTestRunner::class)
class NovaWorkingBaselineFlowTest {
    /** A runtime whose IO work waits until the test runs it. */
    private class HeldRuntime : NovaCommandCenterRuntime {
        val io = ArrayList<suspend () -> Unit>()
        override fun launchIo(name: String, block: suspend () -> Unit) {
            assertEquals(NovaWorkingBaselineFlow.TASK, name)
            io += block
        }
        override suspend fun onMain(block: () -> Unit) = block()
        override fun postDelayed(delayMs: Long, block: () -> Unit) = Unit
        fun runIo() = runBlocking { val pending = io.toList(); io.clear(); pending.forEach { it() } }
    }

    private val app = RuntimeEnvironment.getApplication()
    private val prefs = app.getSharedPreferences(NovaWorkingBaselineStore.FILE, Context.MODE_PRIVATE)
    private val store = NovaWorkingBaselineStore(prefs)
    private val runtime = HeldRuntime()

    /** The stream's live state, which keeps changing while a confirmation is open and a write is queued. */
    private var active = true
    private var hasEntry = true
    private var liveFps = 59.8
    private var marked = "2026-10-08T13:00:00Z"
    private var snapshots = 0
    private val outcomes = ArrayList<Outcome>()

    private val flow = NovaWorkingBaselineFlow(
        store = store,
        runtime = runtime,
        entry = { if (hasEntry) entry else null },
        isActive = { active },
        snapshot = {
            snapshots++
            if (active) snapshot(markedAtUtc = marked, renderedFps = liveFps) else null
        },
    )

    private fun saveNow(): Outcome {
        flow.save { outcomes += it }
        runtime.runIo()
        return outcomes.last()
    }

    private fun savedFps() = flow.state().saved!!.readings.renderedFps!!

    @Test fun openingTheRowAndItsConfirmationFreezesNothing() {
        val state = flow.state()
        assertTrue(state.canSave)
        assertFalse(state.canCompare)
        assertEquals(0, snapshots)
        assertTrue(prefs.all.isEmpty())
    }

    @Test fun theSnapshotIsFrozenAtThePressAndOnlyThatIsWrittenLater() {
        flow.state() // the confirmation opens over this reading
        liveFps = 31.0
        marked = "2026-10-08T13:05:00Z"
        flow.save { outcomes += it } // the press: frozen here
        assertEquals(1, snapshots)
        assertTrue("nothing is written on the main thread", prefs.all.isEmpty())
        assertTrue(outcomes.isEmpty())
        liveFps = 12.0 // the stream keeps changing while the write is queued
        marked = "2026-10-08T13:09:00Z"
        runtime.runIo()
        assertEquals(listOf(Outcome.SAVED), outcomes)
        assertEquals(1, snapshots)
        assertEquals(31.0, savedFps(), 0.0)
        assertEquals("2026-10-08T13:05:00Z", flow.state().saved!!.markedAtUtc)
    }

    @Test fun aStreamThatStopsWhileTheConfirmationIsOpenSavesNothing() {
        assertTrue(flow.state().canSave)
        active = false
        flow.save { outcomes += it }
        assertEquals(listOf(Outcome.NOT_ACTIVE), outcomes)
        assertEquals(0, snapshots)
        assertTrue(runtime.io.isEmpty())
        assertTrue(prefs.all.isEmpty())
    }

    @Test fun aStaleSampleAtThePressIsRefusedWithoutQueueingAWrite() {
        flow.save { outcomes += it }
        runtime.runIo()
        assertEquals(1, prefs.all.keys.count { it.startsWith(NovaWorkingBaselineStore.ENTRY_PREFIX) })
        val staleFlow = NovaWorkingBaselineFlow(store, runtime, { entry }, { true }, { snapshot(sampleAt = 1_000L) })
        staleFlow.save { outcomes += it }
        assertEquals(Outcome.READINGS_UNAVAILABLE, outcomes.last())
        assertTrue(runtime.io.isEmpty())
    }

    @Test fun aSecondSaveOrClearWhileOneIsOutstandingIsRefused() {
        flow.save { outcomes += it }
        assertTrue(flow.busy)
        assertFalse(flow.state().canSave)
        assertFalse(flow.state().canClear)
        flow.save { outcomes += it }
        flow.clear { outcomes += it }
        assertEquals(listOf(Outcome.BUSY, Outcome.BUSY), outcomes)
        assertEquals(1, runtime.io.size)
        assertEquals(1, snapshots)
        runtime.runIo()
        assertEquals(Outcome.SAVED, outcomes.last())
        assertFalse(flow.busy)
    }

    @Test fun anAnswerForAClosedMenuIsDroppedButTheWriteStillFinishes() {
        flow.save { outcomes += it }
        flow.retire() // the menu closed, or the stream stopped
        runtime.runIo()
        assertTrue("no answer reaches a closed menu", outcomes.isEmpty())
        assertFalse(flow.busy)
        assertEquals(59.8, savedFps(), 0.0)
        // The next opening gets its own answers.
        liveFps = 44.0
        assertEquals(Outcome.REPLACED, saveNow())
        assertEquals(44.0, savedFps(), 0.0)
    }

    @Test fun aWriteQueuedBeforeTheMenuClosedKeepsTheNextOpeningBusyUntilItFinishes() {
        flow.save { outcomes += it }
        flow.retire() // closed and reopened before the IO ran
        assertTrue(flow.busy)
        assertFalse(flow.state().canSave)
        assertFalse(flow.state().canClear)
        liveFps = 20.0
        flow.save { outcomes += it }
        flow.clear { outcomes += it }
        assertEquals("no second change can queue behind the first", listOf(Outcome.BUSY, Outcome.BUSY), outcomes)
        assertEquals(1, runtime.io.size)
        runtime.runIo()
        assertFalse(flow.busy)
        assertEquals(59.8, savedFps(), 0.0)
        // Once the first write has finished, the new opening can change it.
        assertEquals(Outcome.REPLACED, saveNow())
        assertEquals(20.0, savedFps(), 0.0)
    }

    @Test fun aRetiredWriteRefreshesOnlyTheNewOpeningAndNeverItsOldOutcome() {
        var oldRefresh = 0
        var newRefresh = 0
        flow.onWriteFinished = { oldRefresh++ }
        flow.save { outcomes += it }
        flow.retire()
        flow.onWriteFinished = { newRefresh++ }
        runtime.runIo()
        assertEquals(0, oldRefresh)
        assertEquals(1, newRefresh)
        assertTrue(outcomes.isEmpty())
        assertFalse(flow.busy)
    }

    @Test fun anotherClosedOpeningReceivesNoRefreshFromTheRetiredWrite() {
        var refresh = 0
        flow.save { outcomes += it }
        flow.retire()
        flow.onWriteFinished = { refresh++ }
        flow.retire()
        runtime.runIo()
        assertEquals(0, refresh)
        assertTrue(outcomes.isEmpty())
    }

    @Test fun storageThatThrowsIsAStorageFailureNotACrash() {
        val broken = object : android.content.SharedPreferences by prefs {
            override fun edit(): android.content.SharedPreferences.Editor = throw IllegalStateException("disk")
        }
        val brokenFlow = NovaWorkingBaselineFlow(NovaWorkingBaselineStore(broken), runtime, { entry }, { true }, { snapshot() })
        brokenFlow.save { outcomes += it }
        runtime.runIo()
        prefs.edit().putString("baseline_v1_" + "a".repeat(64), "x").commit()
        brokenFlow.clear { outcomes += it }
        runtime.runIo()
        val throwingKey = NovaWorkingBaselineFlow(
            NovaWorkingBaselineStore(app.getSharedPreferences("other", Context.MODE_PRIVATE)) { throw SecurityException("no rng") },
            runtime, { entry }, { true }, { snapshot() })
        throwingKey.save { outcomes += it }
        runtime.runIo()
        assertEquals(listOf(Outcome.STORAGE_FAILED, Outcome.STORAGE_FAILED, Outcome.STORAGE_FAILED), outcomes)
    }

    @Test fun withoutARequestedEntryNothingCanBeSavedComparedOrCleared() {
        hasEntry = false
        val state = flow.state()
        assertFalse(state.hasEntry || state.canSave || state.canCompare || state.canClear)
        flow.save { outcomes += it }
        flow.clear { outcomes += it }
        assertEquals(listOf(Outcome.NO_ENTRY, Outcome.NO_ENTRY), outcomes)
        assertEquals(Compared.NoEntry, flow.compare())
        assertTrue(runtime.io.isEmpty())
        assertTrue(prefs.all.isEmpty())
    }

    @Test fun compareIsReadOnlyAndSetsTheBaselineBesideAFreshSnapshot() {
        saveNow()
        val before = prefs.all
        liveFps = 40.0
        val shown = flow.compare() as Compared.Shown
        val rendered = shown.comparison.readings.single { it.item == NovaWorkingBaseline.ReadingItem.RENDERED_FPS }
        assertEquals(59.8, rendered.baseline!!, 0.0)
        assertEquals(40.0, rendered.now!!, 0.0)
        assertEquals(before, prefs.all)
        assertTrue(runtime.io.isEmpty())
    }

    @Test fun compareWithNoKeyOrACorruptEntryWritesNothingAndCreatesNoKey() {
        assertEquals(Compared.NoBaseline, flow.compare())
        assertTrue(prefs.all.isEmpty())
        saveNow()
        val name = prefs.all.keys.single { it.startsWith(NovaWorkingBaselineStore.ENTRY_PREFIX) }
        prefs.edit().putString(name, "{").commit()
        val corrupt = prefs.all
        assertEquals(Compared.Unreadable, flow.compare())
        flow.state()
        assertEquals(corrupt, prefs.all)
        prefs.edit().remove(NovaWorkingBaselineStore.LOCAL_KEY).commit()
        val keyless = prefs.all
        assertEquals(Compared.Unreadable, flow.compare())
        flow.state()
        assertEquals(keyless, prefs.all)
        assertFalse(NovaWorkingBaselineStore.LOCAL_KEY in prefs.all)
        assertTrue(runtime.io.isEmpty())
    }

    @Test fun compareNeedsARunningStreamAndClearDoesNot() {
        saveNow()
        active = false
        assertEquals(Compared.NotActive, flow.compare())
        assertFalse(flow.state().canCompare)
        assertTrue(flow.state().canClear)
        flow.clear { outcomes += it }
        runtime.runIo()
        assertEquals(Outcome.CLEARED, outcomes.last())
        assertEquals(NovaWorkingBaselineStore.Loaded.None, flow.state().loaded)
    }

    @Test fun noFlowStepWritesAFileOrALog() {
        fun files() = listOf(app.filesDir, app.cacheDir).flatMap { root -> root.walk().filter(File::isFile).map { it.path }.toList() }.toSet()
        val before = files()
        flow.state(); saveNow(); flow.compare(); flow.clear { outcomes += it }; runtime.runIo()
        // Preferences live outside both roots; nothing else may appear in them.
        assertEquals(before, files())
        assertFalse(File(app.cacheDir, "moment-reports").exists())
    }

    @Test fun aConfirmedClearWithAnUnusableKeyLeavesNothingToClearAndMakesNoKey() {
        saveNow()
        prefs.edit().putString(NovaWorkingBaselineStore.LOCAL_KEY, "not-a-key").commit()
        val before = flow.state()
        assertEquals(NovaWorkingBaselineStore.Loaded.Unreadable, before.loaded)
        assertTrue("the row still offers Clear for what can't be read", before.canClear)
        flow.clear { outcomes += it }
        runtime.runIo()
        assertEquals(Outcome.CLEARED, outcomes.last())
        val after = flow.state()
        assertEquals(NovaWorkingBaselineStore.Loaded.None, after.loaded)
        assertFalse("nothing is left to clear, so Clear is not offered again", after.canClear)
        assertFalse(after.unrecoverableOthers)
        assertTrue(after.canSave)
        assertFalse(NovaWorkingBaselineStore.LOCAL_KEY in prefs.all)
        assertTrue("Clear made no key and left no record", prefs.all.isEmpty())
        // Reading and Compare after it stay read-only: still no key, still nothing stored.
        assertEquals(Compared.NoBaseline, flow.compare())
        assertTrue(prefs.all.isEmpty())
        assertTrue(runtime.io.isEmpty())
        // Only a later confirmed Save starts over with a fresh key.
        assertEquals(Outcome.SAVED, saveNow())
        assertTrue(NovaWorkingBaselineStore.LOCAL_KEY in prefs.all)
        assertTrue(flow.state().canClear)
    }
}
