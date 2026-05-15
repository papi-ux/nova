package com.papi.nova;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.view.KeyEvent;
import android.view.View;

import androidx.appcompat.app.AppCompatActivity;

import com.papi.nova.nvstream.http.NvApp;
import com.papi.nova.ui.AdapterFragmentCallbacks;

import org.junit.Test;

import java.io.File;
import java.lang.reflect.Field;

public class KotlinActivityShellMigrationTest {
    @Test
    public void activityShellsAreKotlinSources() {
        assertFalse(new File("src/main/java/com/papi/nova/AppView.java").exists());
        assertFalse(new File("src/main/java/com/papi/nova/PcView.java").exists());
        assertTrue(new File("src/main/java/com/papi/nova/AppView.kt").exists());
        assertTrue(new File("src/main/java/com/papi/nova/PcView.kt").exists());
    }

    @Test
    public void appViewKeepsIntentContractAndNestedAppObject() throws Exception {
        assertTrue(AppCompatActivity.class.isAssignableFrom(AppView.class));
        assertTrue(AdapterFragmentCallbacks.class.isAssignableFrom(AppView.class));
        assertEquals("Name", AppView.NAME_EXTRA);
        assertEquals("UUID", AppView.UUID_EXTRA);
        assertEquals("NewPair", AppView.NEW_PAIR_EXTRA);
        assertEquals("ShowHiddenApps", AppView.SHOW_HIDDEN_APPS_EXTRA);
        assertEquals("HiddenApps", AppView.HIDDEN_APPS_PREF_FILENAME);

        AppView.class.getMethod("finish");
        AppView.class.getMethod("getAdapterFragmentLayoutId");
        AppView.class.getMethod("receiveAbsListView", View.class);

        AppView.AppObject appObject = new AppView.AppObject(new NvApp("Game", "game-uuid", 7, false));
        assertEquals("Game", appObject.toString());

        Field appField = AppView.AppObject.class.getField("app");
        Field runningField = AppView.AppObject.class.getField("isRunning");
        Field hiddenField = AppView.AppObject.class.getField("isHidden");
        Field pinnedField = AppView.AppObject.class.getField("isPinned");
        assertEquals(NvApp.class, appField.getType());
        assertEquals(boolean.class, runningField.getType());
        assertEquals(boolean.class, hiddenField.getType());
        assertEquals(boolean.class, pinnedField.getType());
    }

    @Test
    public void pcViewKeepsJavaCompatibleShellApis() throws Exception {
        assertTrue(AppCompatActivity.class.isAssignableFrom(PcView.class));
        assertTrue(AdapterFragmentCallbacks.class.isAssignableFrom(PcView.class));

        PcView.class.getMethod("dispatchKeyEvent", KeyEvent.class);
        PcView.class.getMethod("getAdapterFragmentLayoutId");
        PcView.class.getMethod("receiveAbsListView", View.class);
        PcView.class.getMethod("onDestroy");
    }
}
