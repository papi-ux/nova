package com.papi.nova

import android.view.KeyEvent
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import com.papi.nova.nvstream.http.NvApp
import com.papi.nova.ui.AdapterFragmentCallbacks
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KotlinActivityShellMigrationTest {
    private fun readSource(path: String): String = File(path).readText()

    @Test
    fun activityShellsAreKotlinSources() {
        assertFalse(File("src/main/java/com/papi/nova/AppView.java").exists())
        assertFalse(File("src/main/java/com/papi/nova/PcView.java").exists())
        assertTrue(File("src/main/java/com/papi/nova/AppView.kt").exists())
        assertTrue(File("src/main/java/com/papi/nova/PcView.kt").exists())
    }

    @Test
    fun appViewKeepsIntentContractAndNestedAppObject() {
        assertTrue(AppCompatActivity::class.java.isAssignableFrom(AppView::class.java))
        assertTrue(AdapterFragmentCallbacks::class.java.isAssignableFrom(AppView::class.java))
        assertEquals("Name", AppView.NAME_EXTRA)
        assertEquals("UUID", AppView.UUID_EXTRA)
        assertEquals("NewPair", AppView.NEW_PAIR_EXTRA)
        assertEquals("ShowHiddenApps", AppView.SHOW_HIDDEN_APPS_EXTRA)
        assertEquals("HiddenApps", AppView.HIDDEN_APPS_PREF_FILENAME)

        AppView::class.java.getMethod("finish")
        AppView::class.java.getMethod("getAdapterFragmentLayoutId")
        AppView::class.java.getMethod("receiveAbsListView", View::class.java)

        val appObject = AppView.AppObject(NvApp("Game", "game-uuid", 7, false))
        assertEquals("Game", appObject.toString())

        val appField = AppView.AppObject::class.java.getField("app")
        val runningField = AppView.AppObject::class.java.getField("isRunning")
        val hiddenField = AppView.AppObject::class.java.getField("isHidden")
        val pinnedField = AppView.AppObject::class.java.getField("isPinned")
        assertEquals(NvApp::class.java, appField.type)
        assertEquals(Boolean::class.javaPrimitiveType!!, runningField.type)
        assertEquals(Boolean::class.javaPrimitiveType!!, hiddenField.type)
        assertEquals(Boolean::class.javaPrimitiveType!!, pinnedField.type)
    }

    @Test
    fun pcViewKeepsJavaCompatibleShellApis() {
        assertTrue(AppCompatActivity::class.java.isAssignableFrom(PcView::class.java))
        assertTrue(AdapterFragmentCallbacks::class.java.isAssignableFrom(PcView::class.java))

        PcView::class.java.getMethod("dispatchKeyEvent", KeyEvent::class.java)
        PcView::class.java.getMethod("getAdapterFragmentLayoutId")
        PcView::class.java.getMethod("receiveAbsListView", View::class.java)
        PcView::class.java.getMethod("onDestroy")
    }

    @Test
    fun appViewPolarisMetadataRefreshUsesRuntimeTasks() {
        val appViewSource = readSource("src/main/java/com/papi/nova/AppView.kt")

        assertTrue(
            appViewSource.contains("private val runtimeTasks = NovaRuntimeTasks(this, \"Nova app list\")")
        )
        assertTrue(appViewSource.contains("runtimeTasks.launchIo(\"PolarisGameMetadata\")"))
        assertFalse(
            Regex(
                """Thread\s*\([\s\S]*"PolarisGameMetadata"[\s\S]*?\)\s*\.start\s*\("""
            ).containsMatchIn(appViewSource)
        )
    }

    @Test
    fun appViewKeepsFallbackAppListErrorsInActivityWithRetry() {
        val appViewSource = readSource("src/main/java/com/papi/nova/AppView.kt")
        val appViewLayout = readSource("src/main/res/layout/activity_app_view.xml")
        val strings = readSource("src/main/res/values/strings.xml")

        assertTrue(appViewSource.contains("private fun showAppListError"))
        assertTrue(appViewSource.contains("private fun retryAppListLoad"))
        assertTrue(appViewSource.contains("R.id.appListErrorCard"))
        assertTrue(appViewSource.contains("R.id.appListRetryButton"))
        assertTrue(appViewSource.contains("poller?.pollNow()"))
        assertTrue(appViewSource.contains("catch (e: RuntimeException)"))
        assertTrue(appViewLayout.contains("@+id/appListErrorCard"))
        assertTrue(appViewLayout.contains("@+id/appListErrorDetail"))
        assertTrue(appViewLayout.contains("@+id/appListRetryButton"))
        assertTrue(strings.contains("name=\"applist_error_title\""))
        assertTrue(strings.contains("name=\"applist_error_message\""))
        assertTrue(strings.contains("name=\"applist_error_retry\""))
    }

    @Test
    fun pcViewPolarisBackgroundWorkUsesRuntimeTasks() {
        val pcViewSource = readSource("src/main/java/com/papi/nova/PcView.kt")

        assertTrue(
            pcViewSource.contains("private val runtimeTasks = NovaRuntimeTasks(this, \"Nova dashboard\")")
        )
        assertTrue(pcViewSource.contains("runtimeTasks.launchIo(\"NovaLibraryProbe\")"))
        assertTrue(pcViewSource.contains("runtimeTasks.launchIo(\"NovaPolarisStartup\")"))
        assertFalse(
            Regex(
                """Thread\s*\(\s*\{[\s\S]*?\}\s*,\s*"NovaLibraryProbe"\s*,?\s*\)\s*\.start\s*\("""
            ).containsMatchIn(pcViewSource)
        )
        assertFalse(
            Regex(
                """Thread\s*\(\s*\{[\s\S]*?\}\s*,\s*"NovaPolarisStartup"\s*,?\s*\)\s*\.start\s*\("""
            ).containsMatchIn(pcViewSource)
        )
    }
}
