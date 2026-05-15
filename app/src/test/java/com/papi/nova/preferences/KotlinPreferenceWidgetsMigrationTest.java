package com.papi.nova.preferences;

import android.content.Context;
import android.os.Bundle;
import android.util.AttributeSet;

import androidx.test.core.app.ApplicationProvider;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.io.File;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

@Config(sdk = {33})
@RunWith(RobolectricTestRunner.class)
public class KotlinPreferenceWidgetsMigrationTest {
    @Test
    public void selectedPreferenceWidgetsAreKotlinSources() {
        String[] names = {
                "SeekBarPreference",
                "SmallIconCheckboxPreference",
                "ConfirmDeleteKeyboardPreference",
                "ConfirmDeleteOscPreference",
                "LanguagePreference",
                "WebLauncherPreference",
                "NovaListPreferenceDialogFragment"
        };

        for (String name : names) {
            File javaFile = new File("src/main/java/com/papi/nova/preferences/" + name + ".java");
            File kotlinFile = new File("src/main/java/com/papi/nova/preferences/" + name + ".kt");
            assertFalse(name + " should no longer be a Java source", javaFile.exists());
            assertTrue(name + " should be migrated to Kotlin", kotlinFile.exists());
        }
    }

    @Test
    public void preferenceWidgetsKeepXmlInflationConstructors() throws NoSuchMethodException {
        SeekBarPreference.class.getConstructor(Context.class, AttributeSet.class);

        SmallIconCheckboxPreference.class.getConstructor(Context.class);
        SmallIconCheckboxPreference.class.getConstructor(Context.class, AttributeSet.class);
        SmallIconCheckboxPreference.class.getConstructor(Context.class, AttributeSet.class, int.class);
        SmallIconCheckboxPreference.class.getConstructor(Context.class, AttributeSet.class, int.class, int.class);

        ConfirmDeleteKeyboardPreference.class.getConstructor(Context.class);
        ConfirmDeleteKeyboardPreference.class.getConstructor(Context.class, AttributeSet.class);
        ConfirmDeleteKeyboardPreference.class.getConstructor(Context.class, AttributeSet.class, int.class);
        ConfirmDeleteKeyboardPreference.class.getConstructor(Context.class, AttributeSet.class, int.class, int.class);

        ConfirmDeleteOscPreference.class.getConstructor(Context.class);
        ConfirmDeleteOscPreference.class.getConstructor(Context.class, AttributeSet.class);
        ConfirmDeleteOscPreference.class.getConstructor(Context.class, AttributeSet.class, int.class);
        ConfirmDeleteOscPreference.class.getConstructor(Context.class, AttributeSet.class, int.class, int.class);

        LanguagePreference.class.getConstructor(Context.class);
        LanguagePreference.class.getConstructor(Context.class, AttributeSet.class);
        LanguagePreference.class.getConstructor(Context.class, AttributeSet.class, int.class);
        LanguagePreference.class.getConstructor(Context.class, AttributeSet.class, int.class, int.class);

        WebLauncherPreference.class.getConstructor(Context.class, AttributeSet.class);
        WebLauncherPreference.class.getConstructor(Context.class, AttributeSet.class, int.class);
        WebLauncherPreference.class.getConstructor(Context.class, AttributeSet.class, int.class, int.class);
    }

    @Test
    public void dialogFragmentFactoriesKeepPreferenceKeyArgument() {
        Bundle oscArgs = ConfirmDeleteOscPreference.DialogFragmentCompat
                .newInstance("option_reset_osc_preference")
                .getArguments();
        Bundle keyboardArgs = ConfirmDeleteKeyboardPreference.DialogFragmentCompat
                .newInstance("option_reset_keyboard_preference")
                .getArguments();
        Bundle listArgs = NovaListPreferenceDialogFragment
                .newInstance("list_languages")
                .getArguments();

        assertEquals("option_reset_osc_preference", oscArgs.getString("key"));
        assertEquals("option_reset_keyboard_preference", keyboardArgs.getString("key"));
        assertEquals("list_languages", listArgs.getString("key"));
    }

    @Test
    public void webLauncherPreferenceStillRequiresUrlAttribute() {
        Context context = ApplicationProvider.getApplicationContext();

        assertThrows(IllegalStateException.class, () -> new WebLauncherPreference(context, null));
    }
}
