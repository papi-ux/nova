package com.papi.nova.utils;

import android.app.Activity;
import android.content.Context;
import android.content.DialogInterface;

import org.junit.Test;

import java.io.File;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class KotlinUtilsCoreMigrationTest {
    @Test
    public void coreDialogAndHelpUtilsAreKotlinSources() {
        String[] names = {
                "HelpLauncher",
                "Dialog",
                "SpinnerDialog"
        };

        for (String name : names) {
            File javaFile = new File("src/main/java/com/papi/nova/utils/" + name + ".java");
            File kotlinFile = new File("src/main/java/com/papi/nova/utils/" + name + ".kt");
            assertFalse(name + " should no longer be a Java source", javaFile.exists());
            assertTrue(name + " should be migrated to Kotlin", kotlinFile.exists());
        }
    }

    @Test
    public void migratedUtilsKeepJavaCompatibleApis() throws NoSuchMethodException {
        HelpLauncher.class.getMethod("launchUrl", Context.class, String.class);
        HelpLauncher.class.getMethod("launchSetupGuide", Context.class);
        HelpLauncher.class.getMethod("launchTroubleshooting", Context.class);
        HelpLauncher.class.getMethod("launchGameStreamEolFaq", Context.class);

        assertTrue(Runnable.class.isAssignableFrom(Dialog.class));
        Dialog.class.getMethod("closeDialogs");
        Dialog.class.getMethod("displayDialog", Activity.class, String.class, String.class, boolean.class);
        Dialog.class.getMethod("displayDialog", Activity.class, String.class, String.class, Runnable.class);

        assertTrue(Runnable.class.isAssignableFrom(SpinnerDialog.class));
        assertTrue(DialogInterface.OnCancelListener.class.isAssignableFrom(SpinnerDialog.class));
        SpinnerDialog.class.getMethod("displayDialog", Activity.class, String.class, String.class, boolean.class);
        SpinnerDialog.class.getMethod("closeDialogs", Activity.class);
        SpinnerDialog.class.getMethod("dismiss");
        SpinnerDialog.class.getMethod("setMessage", String.class);
    }
}
