package com.papi.nova.diagnostics

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.core.content.FileProvider
import com.papi.nova.R
import java.io.File
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** Saved recap reports: their own immutable files and folder, bounded, shared only as saved. */
@Config(sdk = [33])
@RunWith(RobolectricTestRunner::class)
class NovaStreamRecapExportTest {
    @Before fun attachTheManifestProvider() {
        // The actual manifest ProviderInfo, with its metadata and grant flag, not a default one.
        val application = RuntimeEnvironment.getApplication()
        val providerInfo = requireNotNull(application.packageManager.resolveContentProvider(
            "${application.packageName}.fileprovider", PackageManager.GET_META_DATA))
        assertTrue(providerInfo.grantUriPermissions)
        Robolectric.buildContentProvider(FileProvider::class.java).create(providerInfo).get()
    }

    class NoTargetActivity : Activity() {
        override fun startActivity(intent: Intent?) {
            throw ActivityNotFoundException("no target")
        }
    }

    private fun activity() = Robolectric.buildActivity(Activity::class.java).setup().get()

    @Test fun theUserPicksTheRecipientOfTheExactSavedBytesWithTheRecapsOwnSubject() {
        val activity = activity()
        val bytes = NovaStreamRecapReport.render(NovaStreamRecapReportTest.recap(), "1.4.15", "release")!!
        val file = NovaStreamRecapExport.write(NovaStreamRecapExport.directory(activity), "2026-10-08T14:00:00Z", bytes)!!
        assertEquals(NovaStreamRecapExport.DIRECTORY, file.parentFile!!.name)
        assertEquals(NovaMomentShare.Result.Offered(file), NovaStreamRecapExport.offer(activity, file))

        val chooser = shadowOf(activity).nextStartedActivity
        assertEquals(Intent.ACTION_CHOOSER, chooser.action)
        assertEquals(activity.getString(R.string.nova_recap_report_share_title), chooser.getCharSequenceExtra(Intent.EXTRA_TITLE).toString())
        val send = chooser.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)!!
        assertEquals(Intent.ACTION_SEND, send.action)
        assertEquals(NovaMomentShare.MIME_TYPE, send.type)
        assertEquals(activity.getString(R.string.nova_recap_report_subject), send.getStringExtra(Intent.EXTRA_SUBJECT))
        assertTrue((send.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION) != 0)
        assertEquals(0, send.flags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        val uri = send.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)!!
        assertEquals("${activity.packageName}.fileprovider", uri.authority)
        assertEquals(uri, send.clipData!!.getItemAt(0).uri)
        assertArrayEquals(bytes, activity.contentResolver.openInputStream(uri)!!.use { it.readBytes() })
        assertNull("only the chooser opened; nothing was sent", shadowOf(activity).nextStartedActivity)
    }

    @Test fun everyReportIsItsOwnFileAndNeverAMomentName() {
        val directory = NovaStreamRecapExport.directory(activity())
        val first = NovaStreamRecapExport.write(directory, "2026-10-08T14:00:00Z", "first".toByteArray())!!
        val second = NovaStreamRecapExport.write(directory, "2026-10-08T14:00:00Z", "second".toByteArray())!!
        assertNotEquals(first, second)
        assertEquals("first", first.readText())
        assertEquals("second", second.readText())
        assertTrue(first.name.startsWith("nova-stream-recap-20261008T140000Z-"))
        assertFalse(first.name.contains("moment"))
    }

    @Test fun retentionKeepsTheNewestSixOfItsOwnAndLeavesMomentReportsAlone() {
        val activity = activity()
        val directory = NovaStreamRecapExport.directory(activity)
        val moment = NovaMomentShare.write(NovaMomentShare.directory(activity), "2026-10-08T11:30:00Z", "moment".toByteArray())!!
        val stray = File(directory.apply { mkdirs() }, "notes.md").apply { writeText("not ours") }
        val written = (1..8).map { index ->
            NovaStreamRecapExport.write(directory, "2026-10-08T14:00:00Z", "r$index".toByteArray())!!.also {
                it.setLastModified(1_000_000L + index * 1_000L)
            }
        }
        NovaStreamRecapExport.prune(directory, keep = written.last())
        val left = directory.listFiles()!!.filter { it.name.startsWith("nova-stream-recap-") }
        assertEquals(NovaStreamRecapExport.MAX_RETAINED, left.size)
        assertTrue(written.last() in left)
        assertFalse(written.first().exists())
        assertTrue(moment.exists())
        assertTrue(stray.exists())
    }

    @Test fun noAppToReceiveItIsSaidAndNothingElseHappens() {
        val activity = Robolectric.buildActivity(NoTargetActivity::class.java).setup().get()
        val file = NovaStreamRecapExport.write(NovaStreamRecapExport.directory(activity), "2026-10-08T14:00:00Z", "x".toByteArray())!!
        assertEquals(NovaMomentShare.Result.Failed(NovaMomentShare.Reason.NO_SHARE_TARGET), NovaStreamRecapExport.offer(activity, file))
    }

    @Test fun aFileOutsideTheSharedCacheIsAStorageFailure() {
        val activity = activity()
        val outside = File(activity.filesDir, "nova-stream-recap-outside.md").apply { writeText("x") }
        assertEquals(NovaMomentShare.Result.Failed(NovaMomentShare.Reason.STORAGE), NovaStreamRecapExport.offer(activity, outside))
        assertNull(shadowOf(activity).nextStartedActivity)
    }

    @Test fun aRefusedDirectoryIsAStorageFailureNotACrash() {
        val blocker = File(RuntimeEnvironment.getApplication().cacheDir, "stream-recaps-blocker").apply { writeText("file") }
        assertNull(NovaStreamRecapExport.write(File(blocker, "inside"), "2026-10-08T14:00:00Z", "x".toByteArray()))
    }
}
