package com.limelight.profiles;

import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.os.Looper;
import android.view.View;
import android.widget.ImageButton;
import android.widget.RadioButton;

import androidx.recyclerview.widget.RecyclerView;
import androidx.test.core.app.ApplicationProvider;

import com.limelight.ProfilesActivity;
import com.limelight.R;
import com.limelight.TestLogSuppressor;

import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowAlertDialog;

import java.util.UUID;

import static org.junit.Assert.*;

@Config(sdk = {33}, shadows = {com.limelight.shadows.ShadowMoonBridge.class, com.limelight.shadows.ShadowGameManager.class})
@RunWith(RobolectricTestRunner.class)
public class ProfilesActivityUiTest {
    private Context context;
    private ProfilesManager pm;

    @BeforeClass
    public static void suppressLogs() {
        TestLogSuppressor.install();
    }

    @Before
    public void setUp() {
        context = ApplicationProvider.getApplicationContext();
//        ApplicationProvider.getApplicationContext().setTheme(R.style.SettingsTheme);
        ProfilesManager.instance = null;
        pm = ProfilesManager.getInstance();
        pm.load(context);
    }

    @Test
    public void fabLaunchesEditProfileActivity() {
        ActivityController<ProfilesActivity> controller = Robolectric.buildActivity(ProfilesActivity.class).setup();
        ProfilesActivity activity = controller.get();

        ImageButton fab = activity.findViewById(R.id.addProfileFab);
        assertNotNull(fab);
        fab.performClick();

        Intent next = Shadows.shadowOf(activity).getNextStartedActivity();
        assertNotNull("FAB should launch EditProfileActivity", next);
        assertEquals("com.limelight.EditProfileActivity", next.getComponent().getClassName());
    }

    @Test
    public void radioClick_changesActiveProfile() {
        // Prepare two profiles
        SettingsProfile p1 = new SettingsProfile(UUID.randomUUID(), "One", System.currentTimeMillis(), System.currentTimeMillis(), null);
        SettingsProfile p2 = new SettingsProfile(UUID.randomUUID(), "Two", System.currentTimeMillis(), System.currentTimeMillis(), null);
        pm.add(p1);
        pm.add(p2);
        pm.setActive(p1.getUuid());

        ActivityController<ProfilesActivity> controller = Robolectric.buildActivity(ProfilesActivity.class).setup();
        ProfilesActivity activity = controller.get();

        RecyclerView rv = activity.findViewById(R.id.profilesRecyclerView);
        rv.layout(0, 0, 1000, 1000);
        assertEquals(2, rv.getAdapter().getItemCount());

        // Click radio of second profile
        RecyclerView.ViewHolder vh = rv.findViewHolderForAdapterPosition(1);
        assertNotNull(vh);
        RadioButton rb = vh.itemView.findViewById(R.id.profileActive);
        assertNotNull(rb);
        rb.performClick();

        assertEquals(p2.getUuid(), pm.getActive().getUuid());
    }

    @Test
    public void deleteProfile_removesRowAndUpdatesEmptyState() {
        // Single profile
        SettingsProfile p = new SettingsProfile(UUID.randomUUID(), "ToDelete", System.currentTimeMillis(), System.currentTimeMillis(), null);
        pm.add(p);

        ActivityController<ProfilesActivity> controller = Robolectric.buildActivity(ProfilesActivity.class).setup();
        ProfilesActivity activity = controller.get();

        RecyclerView rv = activity.findViewById(R.id.profilesRecyclerView);
        rv.layout(0, 0, 1000, 1000);
        RecyclerView.ViewHolder vh = rv.findViewHolderForAdapterPosition(0);
        ImageButton deleteBtn = vh.itemView.findViewById(R.id.deleteProfile);
        deleteBtn.performClick();

        // Confirm the dialog
        AlertDialog dialog = ShadowAlertDialog.getLatestAlertDialog();
        assertNotNull(dialog);
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();

        // Process queued UI tasks
        Shadows.shadowOf(Looper.getMainLooper()).idle();

        // Adapter should now have zero items and empty state should be visible
        assertEquals(0, rv.getAdapter().getItemCount());
        assertTrue(pm.getProfiles().isEmpty());

        // After dataset change, RecyclerView may not refresh immediately; force layout
        rv.layout(0, 0, 1000, 1000);
        assertEquals(View.GONE, rv.getVisibility());
    }
}