package com.papi.nova.profiles;

import org.junit.Test;

import java.io.File;
import java.util.UUID;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class KotlinProfilesStackMigrationTest {
    @Test
    public void selectedProfilesStackClassesAreKotlinSources() {
        String[] paths = {
                "src/main/java/com/papi/nova/profiles/ProfilesManager",
                "src/main/java/com/papi/nova/profiles/ProfilesAdapter",
                "src/main/java/com/papi/nova/ProfilesActivity",
                "src/main/java/com/papi/nova/EditProfileActivity"
        };

        for (String path : paths) {
            File javaFile = new File(path + ".java");
            File kotlinFile = new File(path + ".kt");
            assertFalse(path + " should no longer be a Java source", javaFile.exists());
            assertTrue(path + " should be migrated to Kotlin", kotlinFile.exists());
        }
    }

    @Test
    public void profilesManagerKeepsJavaVisibleSingletonResetAndListenerApi() {
        ProfilesManager.instance = null;
        ProfilesManager manager = ProfilesManager.getInstance();
        final int[] calls = {0};

        ProfilesManager.ProfileChangeListener listener = () -> calls[0]++;
        manager.addListener(listener);

        SettingsProfile profile = new SettingsProfile(
                UUID.randomUUID(),
                "Living Room",
                10L,
                20L,
                null
        );
        manager.add(profile);
        manager.setActive(profile.getUuid());

        assertEquals(2, calls[0]);
        assertEquals(profile.getUuid(), manager.getActive().getUuid());
        assertEquals("Living Room", manager.getActiveName());

        manager.removeListener(listener);
        manager.setActive(null);

        assertEquals(2, calls[0]);
        assertNull(manager.getActive());
        assertEquals("", manager.getActiveName());
    }
}
