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
}
