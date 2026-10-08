package com.papi.nova.diagnostics

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
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

@Config(sdk = [33])
@RunWith(RobolectricTestRunner::class)
class NovaMomentShareTest {
    @Before fun attachProviderForThisApplication() {
        // Robolectric gives each test a new cache root. Attach the manifest provider before
        // requesting a URI so FileProvider discards a path strategy from an earlier test.
        val application = RuntimeEnvironment.getApplication()
        Robolectric.setupContentProvider(FileProvider::class.java, "${application.packageName}.fileprovider")
    }

    /** An activity that has no app able to receive a share. */
    class NoTargetActivity : Activity() {
        override fun startActivity(intent: Intent?) {
            throw ActivityNotFoundException("no target")
        }
    }

    private fun activity() = Robolectric.buildActivity(Activity::class.java).setup().get()

    @Test fun theUserPicksTheRecipientOfTheExactFrozenBytes() {
        val activity = activity()
        val bytes = "# Nova moment report\nfrozen\n".toByteArray()
        val file = NovaMomentShare.write(NovaMomentShare.directory(activity), "2026-10-08T11:30:00Z", bytes)!!
        val result = NovaMomentShare.offer(activity, file)
        assertEquals(NovaMomentShare.Result.Offered(file), result)

        val chooser = shadowOf(activity).nextStartedActivity
        assertEquals(Intent.ACTION_CHOOSER, chooser.action)
        val send = chooser.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)!!
        assertEquals(Intent.ACTION_SEND, send.action)
        assertEquals(NovaMomentShare.MIME_TYPE, send.type)
        assertTrue((send.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION) != 0)
        val uri = send.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)!!
        assertEquals("content", uri.scheme)
        assertEquals("${activity.packageName}.fileprovider", uri.authority)
        assertEquals(uri, send.clipData!!.getItemAt(0).uri)
        // The attachment is exactly what was frozen, and nothing else was started: no send happened.
        assertArrayEquals(bytes, activity.contentResolver.openInputStream(uri)!!.use { it.readBytes() })
        assertNull(shadowOf(activity).nextStartedActivity)
    }

    @Test fun everyMomentIsItsOwnFileAndNeverOverwritesAnother() {
        val activity = activity()
        val directory = NovaMomentShare.directory(activity)
        val first = NovaMomentShare.write(directory, "2026-10-08T11:30:00Z", "first".toByteArray())!!
        val second = NovaMomentShare.write(directory, "2026-10-08T11:30:00Z", "second".toByteArray())!!
        assertNotEquals(first, second)
        assertEquals("first", first.readText())
        assertEquals("second", second.readText())
        assertTrue(first.name.startsWith("nova-moment-20261008T113000Z-"))
    }

    @Test fun retentionIsBoundedToTheNewestReports() {
        val activity = activity()
        val directory = NovaMomentShare.directory(activity)
        val written = (1..NovaMomentShare.MAX_RETAINED + 4).map { index ->
            NovaMomentShare.write(directory, "2026-10-08T11:30:00Z", "report $index".toByteArray())!!
                .also { it.setLastModified(1_000_000L * index) }
        }
        NovaMomentShare.prune(directory, keep = written.last())
        val kept = directory.listFiles()!!.map { it.name }.toSet()
        assertEquals(NovaMomentShare.MAX_RETAINED, kept.size)
        assertEquals(written.takeLast(NovaMomentShare.MAX_RETAINED).map { it.name }.toSet(), kept)
        // Files that are not moment reports are left alone.
        File(directory, "other.txt").writeText("keep")
        NovaMomentShare.prune(directory, keep = written.last())
        assertTrue(File(directory, "other.txt").exists())
    }

    @Test fun storageAndShareFailuresAreReportedNotSwallowed() {
        val activity = activity()
        val blocked = File(activity.cacheDir, "not-a-directory").apply { writeText("x") }
        assertNull(NovaMomentShare.write(blocked, "2026-10-08T11:30:00Z", "x".toByteArray()))

        val noTarget = Robolectric.buildActivity(NoTargetActivity::class.java).setup().get()
        val file = NovaMomentShare.write(NovaMomentShare.directory(noTarget), "2026-10-08T11:30:00Z", "x".toByteArray())
        assertNotNull(file)
        assertEquals(NovaMomentShare.Result.Failed(NovaMomentShare.Reason.NO_SHARE_TARGET), NovaMomentShare.offer(noTarget, file!!))

        // A file outside the provider's paths cannot be granted.
        val outside = File(activity.filesDir, "elsewhere.md").apply { writeText("x") }
        assertEquals(NovaMomentShare.Result.Failed(NovaMomentShare.Reason.STORAGE), NovaMomentShare.offer(activity, outside))
    }
}
