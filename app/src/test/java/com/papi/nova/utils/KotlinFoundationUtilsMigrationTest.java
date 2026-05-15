package com.papi.nova.utils;

import org.junit.Test;

import java.io.File;
import java.util.Arrays;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class KotlinFoundationUtilsMigrationTest {
    @Test
    public void selectedFoundationUtilitiesAreKotlinSources() {
        String[] names = {
                "CacheHelper",
                "PerformanceDataTracker",
                "DeviceUtils",
                "FileUriUtils",
                "NetHelper",
                "TrafficStatsHelper",
                "Vector2d",
                "MouseModeOption",
                "KeyConfigHelper"
        };

        for (String name : names) {
            File javaFile = new File("src/main/java/com/papi/nova/utils/" + name + ".java");
            File kotlinFile = new File("src/main/java/com/papi/nova/utils/" + name + ".kt");
            assertFalse(name + " should no longer be a Java source", javaFile.exists());
            assertTrue(name + " should be migrated to Kotlin", kotlinFile.exists());
        }
    }

    @Test
    public void vector2dKeepsMutableJavaApi() {
        Vector2d vector = new Vector2d();

        vector.initialize(3f, 4f);
        assertEquals(3f, vector.getX(), 0.001f);
        assertEquals(4f, vector.getY(), 0.001f);
        assertEquals(5.0, vector.getMagnitude(), 0.001);

        Vector2d normalized = new Vector2d();
        vector.getNormalized(normalized);
        assertEquals(1.0, normalized.getMagnitude(), 0.001);

        vector.scalarMultiply(2.0);
        assertEquals(6f, vector.getX(), 0.001f);
        assertEquals(8f, vector.getY(), 0.001f);
        assertNotNull(Vector2d.ZERO);
    }

    @Test
    public void keyConfigHelperKeepsGsonFriendlyPublicFields() {
        KeyConfigHelper.ShortcutFile file = KeyConfigHelper.parseShortcutFile(
                "{\"data\":[{\"id\":\"paste\",\"name\":\"Paste\",\"sticky\":true,\"keys\":[\"CTRL\",\"V\"]}]}"
        );

        assertEquals(1, file.data.size());
        KeyConfigHelper.Shortcut shortcut = file.data.get(0);
        assertEquals("paste", shortcut.id);
        assertEquals("Paste", shortcut.name);
        assertTrue(shortcut.sticky);
        assertEquals(Arrays.asList("CTRL", "V"), shortcut.keys);
    }

    @Test
    public void mouseModeOptionKeepsPublicFieldsAndLabelString() {
        MouseModeOption option = new MouseModeOption(2, "Trackpad");

        assertEquals(2, option.index);
        assertEquals("Trackpad", option.label);
        assertEquals("Trackpad", option.toString());
    }
}
