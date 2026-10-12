package com.papi.nova.diagnostics

import android.content.Context
import android.content.SharedPreferences
import com.papi.nova.diagnostics.NovaWorkingBaselineFixtures.APP
import com.papi.nova.diagnostics.NovaWorkingBaselineFixtures.PC
import com.papi.nova.diagnostics.NovaWorkingBaselineFixtures.entry
import com.papi.nova.diagnostics.NovaWorkingBaselineFixtures.snapshot
import com.papi.nova.diagnostics.NovaWorkingBaselineStore.Loaded
import com.papi.nova.diagnostics.NovaWorkingBaselineStore.Saved
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Reading never writes; Save and Clear are the only maintenance, and both stay within bounds. */
@Config(sdk = [33])
@RunWith(RobolectricTestRunner::class)
class NovaWorkingBaselineStoreTest {
    /** The real app-private file, with every edit counted. */
    private class Counted(val real: SharedPreferences) : SharedPreferences by real {
        var edits = 0
        override fun edit(): SharedPreferences.Editor {
            edits++
            return real.edit()
        }
    }

    private val prefs = Counted(RuntimeEnvironment.getApplication()
        .getSharedPreferences(NovaWorkingBaselineStore.FILE, Context.MODE_PRIVATE).also { it.edit().clear().commit() })
    private var keysMade = 0
    private val store = NovaWorkingBaselineStore(prefs) {
        keysMade++
        ByteArray(NovaWorkingBaseline.KEY_BYTES) { (it + keysMade).toByte() }
    }

    private fun entry(app: String) = NovaWorkingBaseline.RequestedEntry.of(PC, app)!!

    @Test fun aSaveThenLoadsAndASecondSaveForTheSameEntryReplacesIt() {
        assertEquals(Loaded.None, store.load(entry))
        assertEquals(Saved.SAVED, store.save(entry, snapshot(renderedFps = 59.8)))
        assertEquals(59.8, (store.load(entry) as Loaded.Found).baseline.readings.renderedFps!!, 0.0)
        assertEquals(Saved.REPLACED, store.save(entry, snapshot(markedAtUtc = "2026-10-08T14:00:00Z", renderedFps = 58.0)))
        val found = store.load(entry) as Loaded.Found
        assertEquals("2026-10-08T14:00:00Z", found.baseline.markedAtUtc)
        assertEquals(2, prefs.all.size) // the local key and one entry
        assertEquals(1, keysMade)
    }

    @Test fun nothingStoredHoldsARawIdentityLogOrCounter() {
        store.save(entry, snapshot())
        val everything = prefs.all.entries.joinToString("\n") { (name, value) -> "$name=$value" }
        assertFalse(everything, everything.contains(PC) || everything.contains(APP))
        assertFalse(everything, everything.contains("frames_lost") || everything.contains("frames_expected"))
        assertFalse(everything, everything.contains("Nova log") || everything.contains("app_session"))
    }

    @Test fun readingWithNoKeyOrAnUnusableKeyNeverWritesOrCreatesOne() {
        assertEquals(Loaded.None, store.load(entry))
        prefs.real.edit().putString(NovaWorkingBaselineStore.LOCAL_KEY, "not-a-key").commit()
        val before = prefs.all
        assertEquals(Loaded.Unreadable, store.load(entry))
        assertEquals(0, prefs.edits)
        assertEquals(0, keysMade)
        assertEquals(before, prefs.all)
    }

    @Test fun aCorruptEntryReadsAsUnreadableAndIsLeftInPlaceByReads() {
        store.save(entry, snapshot())
        val name = prefs.all.keys.single { it.startsWith(NovaWorkingBaselineStore.ENTRY_PREFIX) }
        prefs.real.edit().putString(name, "{\"kind\":\"nova-working-baseline-v1\"}").commit()
        val edits = prefs.edits
        repeat(3) { assertEquals(Loaded.Unreadable, store.load(entry)) }
        assertEquals(edits, prefs.edits)
        assertTrue(name in prefs.all)
    }

    @Test fun aFileHoldingMoreThanThisStoreWritesIsUnreadableWithoutBeingChangedByReads() {
        store.save(entry, snapshot())
        prefs.real.edit().putString("something_else", "x").commit()
        val before = prefs.all
        assertEquals(Loaded.Unreadable, store.load(entry))
        assertEquals(before, prefs.all)
        // Save is the maintenance point: it drops what doesn't belong.
        assertEquals(Saved.REPLACED, store.save(entry, snapshot()))
        assertFalse("something_else" in prefs.all)
        assertTrue(store.load(entry) is Loaded.Found)
    }

    @Test fun savingTheThirteenthEntryEvictsTheOldestAndKeepsTwelve() {
        (1..13).forEach { index ->
            val marked = "2026-10-%02dT10:00:00Z".format(index)
            assertEquals(Saved.SAVED, store.save(entry("app-$index"), snapshot(markedAtUtc = marked)))
        }
        val entries = prefs.all.keys.filter { it.startsWith(NovaWorkingBaselineStore.ENTRY_PREFIX) }
        assertEquals(NovaWorkingBaseline.MAX_SCOPES, entries.size)
        assertEquals(Loaded.None, store.load(entry("app-1")))
        (2..13).forEach { assertTrue("app-$it", store.load(entry("app-$it")) is Loaded.Found) }
    }

    @Test fun anUnusableKeyIsReplacedOnlyBySaveWhichDropsTheEntriesItOrphaned() {
        store.save(entry("first"), snapshot())
        prefs.real.edit().putString(NovaWorkingBaselineStore.LOCAL_KEY, "zz").commit()
        assertEquals(Loaded.Unreadable, store.load(entry("first")))
        assertEquals(Saved.SAVED, store.save(entry("second"), snapshot()))
        assertEquals(2, keysMade)
        assertEquals(2, prefs.all.size)
        assertEquals(Loaded.None, store.load(entry("first")))
        assertTrue(store.load(entry("second")) is Loaded.Found)
    }

    @Test fun clearRemovesOnlyThisEntryAndNeverCreatesAKey() {
        assertTrue(store.clear(entry))
        assertTrue(prefs.all.isEmpty())
        assertEquals(0, keysMade)
        store.save(entry("keep"), snapshot())
        store.save(entry, snapshot())
        assertTrue(store.clear(entry))
        assertEquals(Loaded.None, store.load(entry))
        assertTrue(store.load(entry("keep")) is Loaded.Found)
    }

    @Test fun clearAlsoRemovesAnUnreadableEntryForThisEntry() {
        store.save(entry, snapshot())
        val name = prefs.all.keys.single { it.startsWith(NovaWorkingBaselineStore.ENTRY_PREFIX) }
        prefs.real.edit().putString(name, "garbage").commit()
        assertTrue(store.clear(entry))
        assertEquals(Loaded.None, store.load(entry))
    }

    @Test fun aMultibyteOversizedOtherEntryMakesTheFileUnreadableUntilAnExplicitSave() {
        store.save(entry, snapshot())
        // 1100 characters, under the character bound, but 2200 UTF-8 bytes.
        val other = NovaWorkingBaselineStore.ENTRY_PREFIX + "b".repeat(64)
        prefs.real.edit().putString(other, "é".repeat(1_100)).commit()
        val before = prefs.all
        val edits = prefs.edits
        assertEquals(Loaded.Unreadable, store.load(entry))
        assertTrue(store.inspect(entry).unrecoverableOthers)
        assertEquals(edits, prefs.edits)
        assertEquals(before, prefs.all)
        assertEquals(Saved.REPLACED, store.save(entry, snapshot()))
        assertFalse(other in prefs.all)
        assertTrue(store.load(entry) is Loaded.Found)
    }

    @Test fun inspectionSaysBeforehandWhatASaveOrClearWouldAlsoRemove() {
        assertEquals(NovaWorkingBaselineStore.Inspection(Loaded.None, unrecoverableOthers = false, saveEvictsOldest = false),
            store.inspect(entry))
        (1..12).forEach { store.save(entry("app-$it"), snapshot(markedAtUtc = "2026-10-%02dT10:00:00Z".format(it))) }
        val full = store.inspect(entry)
        assertTrue(full.saveEvictsOldest)
        assertFalse(full.unrecoverableOthers)
        assertFalse("replacing one of the twelve evicts nothing", store.inspect(entry("app-3")).saveEvictsOldest)
        prefs.real.edit().putString(NovaWorkingBaselineStore.LOCAL_KEY, "lost").commit()
        val keyless = store.inspect(entry("app-3"))
        assertEquals(Loaded.Unreadable, keyless.loaded)
        assertTrue("with no usable key, every stored baseline is unrecoverable", keyless.unrecoverableOthers)
    }

    @Test fun clearWithAUsableKeyAlsoRemovesUnreadableRecordsButKeepsReadableOnes() {
        store.save(entry("keep"), snapshot())
        store.save(entry, snapshot())
        val junk = NovaWorkingBaselineStore.ENTRY_PREFIX + "c".repeat(64)
        prefs.real.edit().putString(junk, "{}").commit()
        assertTrue(store.inspect(entry).unrecoverableOthers)
        assertTrue(store.clear(entry))
        assertFalse(junk in prefs.all)
        assertEquals(Loaded.None, store.load(entry))
        assertTrue(store.load(entry("keep")) is Loaded.Found)
        assertFalse(store.inspect(entry).unrecoverableOthers)
    }

    @Test fun clearOnAnOverCapacityFileKeepsEveryOtherReadableBaseline() {
        (1..12).forEach { store.save(entry("app-$it"), snapshot(markedAtUtc = "2026-10-%02dT10:00:00Z".format(it))) }
        // A thirteenth readable entry, written past the store as only a damaged or edited file could hold.
        val key = NovaWorkingBaseline.unhex(prefs.all[NovaWorkingBaselineStore.LOCAL_KEY] as String)
        val scope = NovaWorkingBaseline.scope(key, entry("app-13"))!!
        val extra = NovaWorkingBaseline.fromSnapshot(scope, snapshot(markedAtUtc = "2026-10-13T10:00:00Z"))!!
        prefs.real.edit().putString(NovaWorkingBaselineStore.ENTRY_PREFIX + scope, NovaWorkingBaseline.encode(extra)).commit()
        assertEquals("over the bound, reads are unavailable", Loaded.Unreadable, store.load(entry("app-5")))
        assertFalse(store.inspect(entry("app-5")).unrecoverableOthers)
        assertTrue(store.clear(entry("app-5")))
        assertEquals(Loaded.None, store.load(entry("app-5")))
        (1..13).filter { it != 5 }.forEach { assertTrue("app-$it", store.load(entry("app-$it")) is Loaded.Found) }
    }

    @Test fun clearWithNoUsableKeyRemovesEveryStoredBaselineAsItsNoteWarns() {
        store.save(entry("other"), snapshot())
        store.save(entry, snapshot())
        prefs.real.edit().putString(NovaWorkingBaselineStore.LOCAL_KEY, "lost").commit()
        assertTrue(store.inspect(entry).unrecoverableOthers)
        assertTrue(store.clear(entry))
        assertTrue(prefs.all.keys.none { it.startsWith(NovaWorkingBaselineStore.ENTRY_PREFIX) })
        assertEquals("Clear made no key", 1, keysMade)
    }

    @Test fun aCorruptedTimestampCannotSortAsTheNewestBaselineForever() {
        store.save(entry("bent"), snapshot(markedAtUtc = "2026-10-01T10:00:00Z"))
        val bent = prefs.all.keys.single { it.startsWith(NovaWorkingBaselineStore.ENTRY_PREFIX) }
        prefs.real.edit().putString(bent, (prefs.all[bent] as String).replace("2026-10-01T10:00:00Z", "2026-19-45T99:00:00Z")).commit()
        assertEquals(Loaded.Unreadable, store.load(entry("bent")))
        assertTrue(store.inspect(entry).unrecoverableOthers)
        store.save(entry, snapshot())
        assertFalse("an unreadable record is removed, not kept as newest", bent in prefs.all)
    }

    @Test fun aStaleSampleIsNotSavedAndNothingIsWritten() {
        assertEquals(Saved.READINGS_UNAVAILABLE, store.save(entry, snapshot(sampleAt = 1_000L)))
        assertEquals(Saved.READINGS_UNAVAILABLE, store.save(entry, snapshot(sample = false)))
        assertEquals(0, prefs.edits)
        assertTrue(prefs.all.isEmpty())
    }

    @Test fun clearWithANonHexKeyRemovesThatKeyTooSoTheNextReadIsNoneAndClearIsOver() {
        store.save(entry("other"), snapshot())
        store.save(entry, snapshot())
        prefs.real.edit().putString(NovaWorkingBaselineStore.LOCAL_KEY, "not-a-key").commit()
        assertEquals(Loaded.Unreadable, store.load(entry))
        assertTrue(store.clear(entry))
        assertFalse("the key that can't be read is removed with what it orphaned", NovaWorkingBaselineStore.LOCAL_KEY in prefs.all)
        assertTrue(prefs.all.isEmpty())
        assertEquals(NovaWorkingBaselineStore.Inspection(Loaded.None, unrecoverableOthers = false, saveEvictsOldest = false),
            store.inspect(entry))
        assertEquals(Loaded.None, store.load(entry("other")))
        assertEquals("Clear made no key", 1, keysMade)
        // Nothing is left for a second Clear to do, and it still makes no key.
        val edits = prefs.edits
        assertTrue(store.clear(entry))
        assertEquals(edits, prefs.edits)
        assertEquals(1, keysMade)
        assertTrue(prefs.all.isEmpty())
    }

    @Test fun clearWithAWrongLengthHexKeyRemovesThatKeyToo() {
        store.save(entry, snapshot())
        // Lowercase hex, but one byte short of a key: not something this store ever wrote.
        prefs.real.edit().putString(NovaWorkingBaselineStore.LOCAL_KEY, "ab".repeat(NovaWorkingBaseline.KEY_BYTES - 1)).commit()
        assertEquals(Loaded.Unreadable, store.load(entry))
        assertTrue(store.clear(entry))
        assertTrue(prefs.all.isEmpty())
        assertEquals(NovaWorkingBaselineStore.Inspection(Loaded.None, unrecoverableOthers = false, saveEvictsOldest = false),
            store.inspect(entry))
        assertEquals(1, keysMade)
    }

    @Test fun clearWithOnlyAnUnusableKeyLeftDoesNotDeadEndOnUnreadable() {
        prefs.real.edit().putString(NovaWorkingBaselineStore.LOCAL_KEY, "zz").commit()
        assertEquals(Loaded.Unreadable, store.load(entry))
        assertTrue(store.clear(entry))
        assertEquals(Loaded.None, store.load(entry))
        assertTrue(prefs.all.isEmpty())
        assertEquals(0, keysMade)
        // A later Save starts afresh with a new key, as it always did.
        assertEquals(Saved.SAVED, store.save(entry, snapshot()))
        assertEquals(1, keysMade)
        assertTrue(store.load(entry) is Loaded.Found)
    }

    @Test fun anOrdinaryScopedClearKeepsTheUsableKeyAndEveryOtherReadableBaseline() {
        store.save(entry("keep"), snapshot())
        store.save(entry, snapshot())
        val key = prefs.all[NovaWorkingBaselineStore.LOCAL_KEY]
        assertTrue(store.clear(entry))
        assertEquals("a usable key is not what Clear removes", key, prefs.all[NovaWorkingBaselineStore.LOCAL_KEY])
        assertEquals(Loaded.None, store.load(entry))
        assertTrue(store.load(entry("keep")) is Loaded.Found)
        assertEquals(2, prefs.all.size)
        assertEquals(1, keysMade)
    }

    @Test fun clearWithAnUnusableKeyThatStorageRefusesChangesNothingAndReportsFailure() {
        store.save(entry, snapshot())
        prefs.real.edit().putString(NovaWorkingBaselineStore.LOCAL_KEY, "not-a-key").commit()
        val before = prefs.all
        val refusing = object : SharedPreferences by prefs.real {
            override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor by prefs.real.edit() {
                override fun commit(): Boolean = false
            }
        }
        var refusedKeys = 0
        val refusingStore = NovaWorkingBaselineStore(refusing) { refusedKeys++; ByteArray(NovaWorkingBaseline.KEY_BYTES) }
        assertFalse(refusingStore.clear(entry))
        assertEquals(before, prefs.all)
        assertEquals(Loaded.Unreadable, refusingStore.load(entry))
        assertEquals(0, refusedKeys)
    }
}
