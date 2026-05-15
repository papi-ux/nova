package com.papi.nova.grid;

import android.content.Context;
import android.view.View;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.RelativeLayout;
import android.widget.TextView;

import androidx.test.core.app.ApplicationProvider;

import com.papi.nova.AppView;
import com.papi.nova.nvstream.http.ComputerDetails;
import com.papi.nova.preferences.PreferenceConfiguration;

import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.io.File;
import java.util.Collections;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

@Config(sdk = {33})
@RunWith(RobolectricTestRunner.class)
public class KotlinGridAdaptersMigrationTest {
    @BeforeClass
    public static void suppressInvalidIdLogs() {
        com.papi.nova.TestLogSuppressor.install();
    }

    @Test
    public void appGridAdapterIsKotlinSource() {
        File javaFile = new File("src/main/java/com/papi/nova/grid/AppGridAdapter.java");
        File kotlinFile = new File("src/main/java/com/papi/nova/grid/AppGridAdapter.kt");

        assertFalse("AppGridAdapter should no longer be a Java source", javaFile.exists());
        assertTrue("AppGridAdapter should be migrated to Kotlin", kotlinFile.exists());
    }

    @Test
    public void appGridAdapterKeepsJavaCompatibleApis() throws NoSuchMethodException {
        AppGridAdapter.class.getConstructor(Context.class, PreferenceConfiguration.class, ComputerDetails.class, String.class, boolean.class);
        AppGridAdapter.class.getMethod("filterByName", String.class);
        assertEquals(int.class, AppGridAdapter.class.getMethod("getTotalAppCount").getReturnType());
        AppGridAdapter.class.getMethod("updateHiddenApps", Set.class, boolean.class);
        AppGridAdapter.class.getMethod("updateLayoutWithPreferences", Context.class, PreferenceConfiguration.class);
        AppGridAdapter.class.getMethod("cancelQueuedOperations");
        AppGridAdapter.class.getMethod("updatePinnedApps", Set.class);
        assertEquals(boolean.class, AppGridAdapter.class.getMethod("isAppPinned", int.class).getReturnType());
        AppGridAdapter.class.getMethod("addApp", AppView.AppObject.class);
        AppGridAdapter.class.getMethod("removeApp", AppView.AppObject.class);
        AppGridAdapter.class.getMethod("clear");
        AppGridAdapter.class.getMethod("populateFeaturedArt", AppView.AppObject.class, ImageView.class);
        AppGridAdapter.class.getMethod(
                "populateView",
                View.class,
                ImageView.class,
                RelativeLayout.class,
                ProgressBar.class,
                TextView.class,
                ImageView.class,
                AppView.AppObject.class
        );
    }

    @Test
    public void appGridAdapterKeepsPinnedStateWithoutItems() {
        AppGridAdapter adapter = createAdapter();

        assertEquals(0, adapter.getTotalAppCount());
        assertFalse(adapter.isAppPinned(42));

        adapter.updatePinnedApps(Collections.singleton(42));

        assertTrue(adapter.isAppPinned(42));
        assertFalse(adapter.isAppPinned(99));
    }

    private static AppGridAdapter createAdapter() {
        Context context = ApplicationProvider.getApplicationContext();
        PreferenceConfiguration prefs = new PreferenceConfiguration();
        ComputerDetails computer = new ComputerDetails();
        computer.uuid = "adapter-test-computer";
        return new AppGridAdapter(context, prefs, computer, "adapter-test", false);
    }
}
