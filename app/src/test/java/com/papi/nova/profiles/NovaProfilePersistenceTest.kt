package com.papi.nova.profiles

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.papi.nova.R
import com.papi.nova.preferences.NovaSettingsMigration
import com.papi.nova.utils.Dialog
import com.papi.nova.utils.UiHelper
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Assert.*
import org.junit.Before
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlertDialog

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class NovaProfilePersistenceTest {
    private lateinit var context: Context
    private lateinit var manager: ProfilesManager
    private lateinit var directory: File
    private lateinit var profile: SettingsProfile

    @Before fun prepare() {
        context = ApplicationProvider.getApplicationContext()
        directory = File(context.filesDir, "profiles")
        directory.deleteRecursively()
        ProfilesManager.instance = null
        manager = ProfilesManager.getInstance()
        assertTrue(manager.load(context))
        profile = SettingsProfile(UUID.randomUUID(), "Pinned Custom", 1L, 1L, mapOf(
            "list_resolution" to "1920x1080", "list_fps" to "60", "seekbar_bitrate_kbps" to 350000,
            NovaSettingsMigration.TIER to "custom", NovaSettingsMigration.AUTO to false,
            NovaSettingsMigration.CUSTOM_AUTO to false, NovaSettingsMigration.CUSTOM_EXISTS to true))
        manager.add(profile)
        manager.add(SettingsProfile(UUID.randomUUID(), "Second", 1L, 1L, null))
        manager.setActive(profile.getUuid())
    }

    @After fun clean() { Dialog.closeDialogs(); manager.awaitDeferredWritesForTest(); directory.deleteRecursively() }

    private fun failAfterOpen(close: Boolean) {
        manager.openProfileWriter = { file -> object : FileOutputStream(file) {
            override fun write(bytes: ByteArray) = write(bytes, 0, bytes.size)
            override fun write(bytes: ByteArray, off: Int, len: Int) {
                super.write(bytes, off, if (close) len else minOf(7, len))
                if (!close) throw IOException("injected partial write")
            }
            override fun close() { super.close(); if (close) throw IOException("injected close failure") }
        } }
    }

    @Test fun deferredPartialAndCloseFailuresKeepThePreviousColdFileAndReturnFailure() {
        val file = File(directory, "profiles.json")
        val old = file.readBytes()
        for (close in listOf(false, true)) {
            failAfterOpen(close)
            var result: ProfilesManager.SaveResult? = null
            manager.updateDeferred(profile) { result = it }
            manager.awaitDeferredWritesForTest()
            assertEquals(ProfilesManager.SaveResult.FAILED, result)
            assertArrayEquals(old, file.readBytes())
            ProfilesManager.instance = null
            val cold = ProfilesManager.getInstance()
            assertTrue(cold.load(context))
            assertEquals(2, cold.getProfiles().size)
            assertEquals(profile.getUuid(), cold.getActive()!!.getUuid())
            assertEquals("custom", cold.getActive()!!.getOptions()!![NovaSettingsMigration.TIER])
        }
    }

    private fun renamed(name: String) = SettingsProfile(profile.getUuid(), name, 1L, 2L, profile.getOptions())

    private fun blockFirstWrite(entered: CountDownLatch, release: CountDownLatch) {
        val first = AtomicBoolean(true)
        manager.openProfileWriter = { file -> object : FileOutputStream(file) {
            override fun write(bytes: ByteArray) {
                if (first.compareAndSet(true, false)) {
                    entered.countDown()
                    check(release.await(5, TimeUnit.SECONDS)) { "test writer was not released" }
                }
                super.write(bytes)
            }
        } }
    }

    @Test fun anOlderDeferredSnapshotReportsSupersededWhileTheNewestOneSaves() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        blockFirstWrite(entered, release)
        val results = mutableListOf<ProfilesManager.SaveResult>()
        manager.updateDeferred(renamed("Older")) { results.add(it) }
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            manager.updateDeferred(renamed("Newest")) { results.add(it) }
        } finally { release.countDown() }
        manager.awaitDeferredWritesForTest()
        assertEquals(listOf(ProfilesManager.SaveResult.SUPERSEDED, ProfilesManager.SaveResult.SAVED), results)
        ProfilesManager.instance = null
        val cold = ProfilesManager.getInstance()
        assertTrue(cold.load(context))
        assertEquals("Newest", cold.getActive()!!.getName())
    }

    @Test fun aSupersededEditorCommitDoesNotPublishItsDraftOrClaimItSaved() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        blockFirstWrite(entered, release)
        var committed: Boolean? = null
        val writer = Thread { committed = manager.commit(context, renamed("Unsaved editor draft")) }
        writer.start()
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            val second = manager.getProfiles().first { it.getUuid() != profile.getUuid() }
            manager.updateDeferred(SettingsProfile(second.getUuid(), "Newest second", 1L, 2L, null))
            assertEquals("Pinned Custom", manager.getActive()!!.getName())
        } finally { release.countDown() }
        writer.join(5000)
        assertFalse(writer.isAlive)
        manager.awaitDeferredWritesForTest()
        assertEquals(false, committed)
        assertEquals("Pinned Custom", manager.getActive()!!.getName())
        ProfilesManager.instance = null
        val cold = ProfilesManager.getInstance()
        assertTrue(cold.load(context))
        assertEquals("Pinned Custom", cold.getActive()!!.getName())
        assertEquals("Newest second", cold.getProfiles().first { it.getUuid() != profile.getUuid() }.getName())
    }

    @Test fun aFailedEditorCommitKeepsItsPreviousMemoryAndColdFileThenRetrySaves() {
        val file = File(directory, "profiles.json")
        val old = file.readBytes()
        failAfterOpen(close = false)
        assertFalse(manager.commit(context, renamed("Edited")))
        assertEquals("Pinned Custom", manager.getActive()!!.getName())
        assertArrayEquals(old, file.readBytes())
        manager.openProfileWriter = { FileOutputStream(it) }
        assertTrue(manager.commit(context, renamed("Edited")))
        ProfilesManager.instance = null
        val cold = ProfilesManager.getInstance()
        assertTrue(cold.load(context))
        assertEquals(2, cold.getProfiles().size)
        assertEquals("Edited", cold.getActive()!!.getName())
    }

    @Test fun decoderRecoveryReportsFailureAndRetryAcknowledgesOnlyTheSavedReset() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        val activity = controller.get()
        val tombstone = context.getSharedPreferences("DecoderTombstone", Context.MODE_PRIVATE)
        tombstone.edit().putInt("CrashCount", 3).putInt("LastNotifiedCrashCount", 0).commit()
        try {
            failAfterOpen(close = false)
            UiHelper.showDecoderCrashDialog(activity)
            manager.awaitDeferredWritesForTest()
            shadowOf(Looper.getMainLooper()).idle()
            val failure = ShadowAlertDialog.getLatestAlertDialog()
            assertEquals(activity.getString(R.string.title_decoding_reset_failed), shadowOf(failure).title.toString())
            assertEquals(0, tombstone.getInt("LastNotifiedCrashCount", 0))
            manager.openProfileWriter = { FileOutputStream(it) }
            failure.getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
            // The click may dispatch the retry to the Activity's main thread before
            // that retry submits its file write. Drain it before waiting for the writer.
            shadowOf(Looper.getMainLooper()).idle()
            manager.awaitDeferredWritesForTest()
            shadowOf(Looper.getMainLooper()).idle()
            val saved = ShadowAlertDialog.getLatestAlertDialog()
            assertEquals(activity.getString(R.string.title_decoding_reset), shadowOf(saved).title.toString())
            saved.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(3, tombstone.getInt("LastNotifiedCrashCount", 0))
            ProfilesManager.instance = null
            val cold = ProfilesManager.getInstance()
            assertTrue(cold.load(context))
            val options = cold.getActive()!!.getOptions()!!
            assertEquals("recommended", options[NovaSettingsMigration.TIER])
            assertEquals(350000, (options["seekbar_bitrate_kbps"] as Number).toInt())
            assertEquals(false, options[NovaSettingsMigration.CUSTOM_AUTO])
        } finally { controller.pause().stop().destroy() }
    }
}
