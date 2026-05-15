package com.papi.nova;

import androidx.appcompat.app.AppCompatActivity;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.io.File;
import java.lang.reflect.Method;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

@Config(sdk = {33})
@RunWith(RobolectricTestRunner.class)
public class KotlinHelpActivityMigrationTest {
    @Test
    public void helpActivityIsKotlinSource() {
        File javaFile = new File("src/main/java/com/papi/nova/HelpActivity.java");
        File kotlinFile = new File("src/main/java/com/papi/nova/HelpActivity.kt");

        assertFalse("HelpActivity should no longer be a Java source", javaFile.exists());
        assertTrue("HelpActivity should be migrated to Kotlin", kotlinFile.exists());
    }

    @Test
    public void helpActivityKeepsActivityContract() throws NoSuchMethodException {
        assertTrue(AppCompatActivity.class.isAssignableFrom(HelpActivity.class));
        HelpActivity.class.getConstructor();
        assertEquals(void.class, HelpActivity.class.getMethod("onBackPressed").getReturnType());
    }

    @Test
    public void helpActivityKeepsHttpsOnlyUrlPolicy() throws Exception {
        Method method = HelpActivity.class.getDeclaredMethod("isSafeUrl", String.class);
        method.setAccessible(true);
        HelpActivity activity = new HelpActivity();

        assertTrue((boolean) method.invoke(activity, "https://github.com/papi-ux/nova"));
        assertTrue((boolean) method.invoke(activity, "HTTPS://github.com/papi-ux/nova"));
        assertFalse((boolean) method.invoke(activity, "http://github.com/papi-ux/nova"));
        assertFalse((boolean) method.invoke(activity, "file:///sdcard/help.html"));
        assertFalse((boolean) method.invoke(activity, "github.com/papi-ux/nova"));
    }
}
