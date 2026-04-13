package com.papi.nova.profiles;

import android.content.Intent;
import android.view.View;
import android.content.SharedPreferences;
import android.os.Build;
import android.content.Context;
import android.app.GameManager;

import com.papi.nova.PcView;
import com.papi.nova.ProfilesActivity;
import com.papi.nova.R;
import com.papi.nova.AppView;

import androidx.test.core.app.ApplicationProvider;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.junit.BeforeClass;
import com.papi.nova.TestLogSuppressor;

import static org.junit.Assert.*;
import static org.mockito.Mockito.mock;

@Config(sdk = {33}, shadows = {com.papi.nova.shadows.ShadowMoonBridge.class, com.papi.nova.shadows.ShadowGameManager.class})
@RunWith(RobolectricTestRunner.class)
public class ProfilesNavigationTest {

    @BeforeClass
    public static void suppressInvalidIdLogs() {
        TestLogSuppressor.install();
    }

    private void prepareEnvironment() {
        // Ensure GL renderer prefs exist so PcView skips GLSurface initialization
        SharedPreferences glPrefs = ApplicationProvider.getApplicationContext()
                .getSharedPreferences("GlPreferences", 0);
        glPrefs.edit()
                .putString("Renderer", "TestRenderer")
                .putString("Fingerprint", Build.FINGERPRINT)
                .commit();

        // Provide fake GameManager service to avoid ServiceNotFoundException
        org.robolectric.shadows.ShadowApplication shadowApp = org.robolectric.Shadows.shadowOf((android.app.Application) ApplicationProvider.getApplicationContext());
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            shadowApp.setSystemService(Context.GAME_SERVICE, mock(GameManager.class));
        } else {
            // On older APIs, the service doesn't exist, but our code might still try to access it.
            // The cast will fail if it's just `new Object()`, but it shouldn't be called anyway.
            shadowApp.setSystemService(Context.GAME_SERVICE, null);
        }

        // Provide stub ComputerManagerService binder for bindService()
        Context ctx = ApplicationProvider.getApplicationContext();
        android.content.ComponentName cn = new android.content.ComponentName(ctx, com.papi.nova.computers.ComputerManagerService.class);

        // We only need a mock binder, not the real service
        com.papi.nova.computers.ComputerManagerService.ComputerManagerBinder binder = mock(com.papi.nova.computers.ComputerManagerService.ComputerManagerBinder.class);
        org.robolectric.Shadows.shadowOf((android.app.Application) ctx)
                .setComponentNameAndServiceForBindService(cn, binder);

        // Apply an AppCompat theme required by ProfilesActivity
        // ApplicationProvider.getApplicationContext().setTheme(R.style.SettingsTheme);
    }

    @Test
    public void clickingProfileButton_launchesProfilesActivity() {
        prepareEnvironment();
        ActivityController<PcView> controller = Robolectric.buildActivity(PcView.class).setup();
        PcView pcView = controller.get();

        View btn = pcView.findViewById(R.id.profilesButton);
        assertNotNull("profilesButton not found", btn);

        btn.performClick();

        Intent next = Shadows.shadowOf(pcView).getNextStartedActivity();
        assertNotNull("ProfilesActivity should be launched", next);
        assertEquals(ProfilesActivity.class.getName(), next.getComponent().getClassName());
    }

    @Test
    public void clickingProfileButton_launchesProfilesActivityFromAppView() {
        prepareEnvironment();
        Intent intent = new Intent(ApplicationProvider.getApplicationContext(), AppView.class);
        intent.putExtra("UUID", "test-uuid");
        intent.putExtra("Name", "Test PC");

        ActivityController<AppView> controller = Robolectric.buildActivity(AppView.class, intent).setup();
        AppView appView = controller.get();

        View btn = appView.findViewById(R.id.profilesButton);
        assertNotNull("profilesButton not found in AppView", btn);

        btn.performClick();

        Intent next = Shadows.shadowOf(appView).getNextStartedActivity();
        assertNotNull("ProfilesActivity should be launched from AppView", next);
        assertEquals(ProfilesActivity.class.getName(), next.getComponent().getClassName());
    }

    @Test
    public void profilesActivity_startsWithoutCrash() {
        prepareEnvironment();
        ProfilesActivity activity = Robolectric.buildActivity(ProfilesActivity.class).setup().get();
        assertNotNull(activity);
    }
}