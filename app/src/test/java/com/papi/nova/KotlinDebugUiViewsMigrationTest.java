package com.papi.nova;

import android.content.Context;
import android.view.KeyEvent;
import android.view.View;

import androidx.appcompat.app.AppCompatActivity;
import androidx.fragment.app.Fragment;

import com.papi.nova.ui.AdapterFragment;
import com.papi.nova.ui.ExternalControllerView;

import org.junit.Test;

import java.io.File;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class KotlinDebugUiViewsMigrationTest {
    @Test
    public void debugAndUiViewsAreKotlinSources() {
        String[] names = {
                "DebugInfoActivity",
                "ui/AdapterFragment",
                "ui/ExternalControllerView"
        };

        for (String name : names) {
            File javaFile = new File("src/main/java/com/papi/nova/" + name + ".java");
            File kotlinFile = new File("src/main/java/com/papi/nova/" + name + ".kt");
            assertFalse(name + " should no longer be a Java source", javaFile.exists());
            assertTrue(name + " should be migrated to Kotlin", kotlinFile.exists());
        }
    }

    @Test
    public void migratedDebugAndUiViewsKeepJavaCompatibleApis() throws NoSuchMethodException {
        assertTrue(AppCompatActivity.class.isAssignableFrom(DebugInfoActivity.class));
        DebugInfoActivity.class.getConstructor();

        assertTrue(Fragment.class.isAssignableFrom(AdapterFragment.class));
        AdapterFragment.class.getConstructor();

        assertTrue(View.class.isAssignableFrom(ExternalControllerView.class));
        ExternalControllerView.class.getConstructor(Context.class);
        ExternalControllerView.class.getMethod("setInputCallbacks", ExternalControllerView.InputCallbacks.class);
        ExternalControllerView.class.getMethod("setCommitTextEnabled", boolean.class);

        assertTrue(ExternalControllerView.InputCallbacks.class.isInterface());
        ExternalControllerView.InputCallbacks.class.getMethod("handleKeyUp", KeyEvent.class);
        ExternalControllerView.InputCallbacks.class.getMethod("handleKeyDown", KeyEvent.class);
        ExternalControllerView.InputCallbacks.class.getMethod("handleCommitText", CharSequence.class);
        ExternalControllerView.InputCallbacks.class.getMethod("handleDeleteSurroundingText", int.class, int.class);
    }
}
