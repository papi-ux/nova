package com.limelight.profiles;

import android.content.Context;

import androidx.test.core.app.ApplicationProvider;

import org.junit.After;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.robolectric.annotation.Config;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.io.File;
import java.util.UUID;

import static org.junit.Assert.*;

import com.limelight.TestLogSuppressor;

@Config(sdk = {33}, shadows = {com.limelight.shadows.ShadowMoonBridge.class, com.limelight.shadows.ShadowGameManager.class})
@RunWith(RobolectricTestRunner.class)
public class ProfilesManagerTest {
    private Context context;
    private ProfilesManager manager;
    private File profilesDir;

    @BeforeClass
    public static void suppressInvalidIdLogs() {
        TestLogSuppressor.install();
    }

    @Before
    public void setUp() {
        ProfilesManager.instance = null; // Reset the singleton before each test
        context = ApplicationProvider.getApplicationContext();
        profilesDir = new File(context.getFilesDir(), "profiles");
        deleteRecursively(profilesDir);
        manager = ProfilesManager.getInstance();
        manager.load(context);
    }

    @After
    public void tearDown() {
        deleteRecursively(profilesDir);
    }

    @Test
    public void addAndRetrieveProfile() {
        SettingsProfile p = new SettingsProfile(UUID.randomUUID(), "Test", System.currentTimeMillis(), System.currentTimeMillis(), null);
        manager.add(p);
        assertEquals(1, manager.getProfiles().size());
        assertEquals(p.getUuid(), manager.getProfiles().get(0).getUuid());
    }

    @Test
    public void setActivePersists() {
        SettingsProfile p = new SettingsProfile(UUID.randomUUID(), "Active", System.currentTimeMillis(), System.currentTimeMillis(), null);
        manager.add(p);
        manager.setActive(p.getUuid());
        // Reload from disk to verify persistence
        ProfilesManager fresh = ProfilesManager.getInstance();
        fresh.load(context);
        assertNotNull(fresh.getActive());
        assertEquals(p.getUuid(), fresh.getActive().getUuid());
    }

    @Test
    public void updateAndSaveProfile() {
        SettingsProfile p = new SettingsProfile(UUID.randomUUID(), "Original", System.currentTimeMillis(), System.currentTimeMillis(), null);
        manager.add(p);
        p.setName("Updated");
        manager.update(p);

        ProfilesManager fresh = ProfilesManager.getInstance();
        fresh.load(context);
        assertEquals("Updated", fresh.getProfiles().get(0).getName());
    }

    @Test
    public void deleteProfile() {
        SettingsProfile p = new SettingsProfile(UUID.randomUUID(), "ToDelete", System.currentTimeMillis(), System.currentTimeMillis(), null);
        manager.add(p);
        assertEquals(1, manager.getProfiles().size());

        manager.delete(p.getUuid());
        assertEquals(0, manager.getProfiles().size());
    }

    @Test
    public void deleteActiveProfile_resetsActive() {
        SettingsProfile p = new SettingsProfile(UUID.randomUUID(), "ActiveToDelete", System.currentTimeMillis(), System.currentTimeMillis(), null);
        manager.add(p);
        manager.setActive(p.getUuid());
        assertNotNull(manager.getActive());

        manager.delete(p.getUuid());
        assertNull(manager.getActive());

        // Verify persistence
        ProfilesManager fresh = ProfilesManager.getInstance();
        fresh.load(context);
        assertNull(fresh.getActive());
    }

    private void deleteRecursively(File f) {
        if (f == null || !f.exists()) return;
        if (f.isDirectory()) {
            for (File c : f.listFiles()) {
                deleteRecursively(c);
            }
        }
        f.delete();
    }
}