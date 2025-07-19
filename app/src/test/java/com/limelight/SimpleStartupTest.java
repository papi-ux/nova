package com.limelight;

import android.content.Context;
import androidx.test.core.app.ApplicationProvider;
import com.limelight.profiles.ProfilesManager;

import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.io.File;

import static org.junit.Assert.*;

import com.limelight.TestLogSuppressor;

@Config(sdk = {33}, shadows = {com.limelight.shadows.ShadowMoonBridge.class, com.limelight.shadows.ShadowGameManager.class})
@RunWith(RobolectricTestRunner.class)
public class SimpleStartupTest {
    private Context context;

    @BeforeClass
    public static void suppressInvalidIdLogs() {
        TestLogSuppressor.install();
    }

    @Before
    public void setUp() {
        // Reset ProfilesManager instance using reflection since it's package-private
        try {
            java.lang.reflect.Field instanceField = ProfilesManager.class.getDeclaredField("instance");
            instanceField.setAccessible(true);
            instanceField.set(null, null);
        } catch (Exception e) {
            // Ignore reflection errors
        }
        context = ApplicationProvider.getApplicationContext();

        // Clean up any existing profiles
        File profilesDir = new File(context.getFilesDir(), "profiles");
        deleteRecursively(profilesDir);
    }

    @Test
    public void testApplicationCreation() {
        // Test basic application creation
        ArtemisApplication app = new ArtemisApplication();
        assertNotNull("Application should be created", app);
    }

    @Test
    public void testApplicationOnCreate() {
        // Test application onCreate which initializes ProfilesManager
        // After the fix, this should no longer crash
        try {
            ArtemisApplication app = new ArtemisApplication();
            app.onCreate();

            // Should now work without crashing
            ProfilesManager manager = ProfilesManager.getInstance();
            assertNotNull("ProfilesManager should be initialized", manager);
            assertNotNull("Profiles list should be initialized", manager.getProfiles());

            System.out.println("SUCCESS: Application startup no longer crashes!");
        } catch (Exception e) {
            fail("Application onCreate should not crash after fix: " + e.getMessage());
        }
    }

    @Test
    public void testProfilesManagerSingleton() {
        // Test ProfilesManager singleton behavior
        ProfilesManager manager1 = ProfilesManager.getInstance();
        ProfilesManager manager2 = ProfilesManager.getInstance();

        assertNotNull("First instance should not be null", manager1);
        assertNotNull("Second instance should not be null", manager2);
        assertSame("Should return same instance", manager1, manager2);
    }

    @Test
    public void testProfilesManagerLoad() {
        // Test ProfilesManager load with valid context
        try {
            ProfilesManager manager = ProfilesManager.getInstance();
            manager.load(context);

            assertNotNull("Profiles should be loaded", manager.getProfiles());
            assertEquals("Should start with empty profiles", 0, manager.getProfiles().size());
        } catch (Exception e) {
            fail("ProfilesManager load should not crash: " + e.getMessage());
        }
    }

    @Test
    public void testProfilesManagerSave() {
        // Test ProfilesManager save functionality
        try {
            ProfilesManager manager = ProfilesManager.getInstance();
            manager.load(context);
            manager.save(context);

            // Should not crash
            assertTrue("Save operation should complete", true);
        } catch (Exception e) {
            fail("ProfilesManager save should not crash: " + e.getMessage());
        }
    }

    @Test
    public void testContextFileAccess() {
        // Test basic file system access that the app needs
        try {
            File filesDir = context.getFilesDir();
            assertNotNull("Files directory should be accessible", filesDir);

            File testDir = new File(filesDir, "test");
            boolean created = testDir.mkdirs();
            assertTrue("Should be able to create directories", created || testDir.exists());

            boolean deleted = testDir.delete();
            assertTrue("Should be able to delete directories", deleted);
        } catch (Exception e) {
            fail("Basic file operations should work: " + e.getMessage());
        }
    }

    @Test
    public void testNullContextHandling() {
        // Test ProfilesManager with null context - after fix, it should handle gracefully
        ProfilesManager manager = ProfilesManager.getInstance();

        try {
            manager.load(null);
            // Should now handle null context gracefully without crashing
            assertTrue("Should handle null context gracefully", true);
        } catch (Exception e) {
            fail("Should handle null context gracefully: " + e.getMessage());
        }
    }

    private void deleteRecursively(File f) {
        if (f == null || !f.exists()) return;
        if (f.isDirectory()) {
            File[] children = f.listFiles();
            if (children != null) {
                for (File c : children) {
                    deleteRecursively(c);
                }
            }
        }
        f.delete();
    }
}