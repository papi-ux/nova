package com.papi.nova.utils;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.content.Context;
import android.view.View;

import org.junit.Test;

import java.io.File;
import java.lang.reflect.Modifier;

public class KotlinExternalDisplayUiMigrationTest {
    @Test
    public void externalDisplayUiHelpersAreKotlinSources() {
        String[] names = {
                "utils/ExternalDisplayControlActivity",
                "utils/UiHelper"
        };

        for (String name : names) {
            File javaFile = new File("src/main/java/com/papi/nova/" + name + ".java");
            File kotlinFile = new File("src/main/java/com/papi/nova/" + name + ".kt");
            assertFalse(name + " should no longer be a Java source", javaFile.exists());
            assertTrue(name + " should be migrated to Kotlin", kotlinFile.exists());
        }
    }

    @Test
    public void externalDisplayUiHelpersKeepJavaCompatibleApis() throws Exception {
        assertEquals("launchIntent", ExternalDisplayControlActivity.EXTRA_LAUNCH_INTENT);
        assertEquals(1, ExternalDisplayControlActivity.SECONDARY_SCREEN_NOTIFICATION_ID);
        assertTrue(Modifier.isStatic(ExternalDisplayControlActivity.class.getField("EXTRA_LAUNCH_INTENT").getModifiers()));
        assertTrue(Modifier.isStatic(ExternalDisplayControlActivity.class.getField("instance").getModifiers()));
        ExternalDisplayControlActivity.class.getConstructor();
        ExternalDisplayControlActivity.class.getMethod("closeExternalDisplayControl");
        ExternalDisplayControlActivity.class.getMethod("toggleKeyboard");
        ExternalDisplayControlActivity.class.getMethod("toggleFullKeyboard");
        ExternalDisplayControlActivity.class.getMethod("toggleGameMenu");
        ExternalDisplayControlActivity.class.getMethod("toggleZoomMode", boolean.class);
        ExternalDisplayControlActivity.class.getMethod("showGameMenu");

        UiHelper.class.getMethod("isTvDevice", Context.class);
        UiHelper.class.getMethod("applyTvFocusStyle", View.class);
        UiHelper.class.getMethod("applyTvFocusStyle", Context.class, View.class);
        UiHelper.class.getMethod("notifyStreamConnecting", Context.class);
        UiHelper.class.getMethod("notifyStreamConnected", Context.class);
        UiHelper.class.getMethod("notifyStreamEnteringPiP", Context.class);
        UiHelper.class.getMethod("notifyStreamExitingPiP", Context.class);
        UiHelper.class.getMethod("notifyStreamEnded", Context.class);
        UiHelper.class.getMethod("setLocale", Activity.class);
        UiHelper.class.getMethod("applyStatusBarPadding", View.class);
        UiHelper.class.getMethod("notifyNewRootView", Activity.class);
        UiHelper.class.getMethod("showDecoderCrashDialog", Activity.class);
        UiHelper.class.getMethod(
                "displayConfirmationDialog",
                Activity.class,
                String.class,
                String.class,
                String.class,
                String.class,
                Runnable.class,
                Runnable.class);
        UiHelper.class.getMethod("displayVdisplayConfirmationDialog", Activity.class, com.papi.nova.nvstream.http.ComputerDetails.class, Runnable.class, Runnable.class);
        UiHelper.class.getMethod("displayQuitConfirmationDialog", Activity.class, Runnable.class, Runnable.class);
        UiHelper.class.getMethod("displayDeletePcConfirmationDialog", Activity.class, com.papi.nova.nvstream.http.ComputerDetails.class, Runnable.class, Runnable.class);
        UiHelper.class.getMethod("dpToPx", Context.class, float.class);
    }
}
