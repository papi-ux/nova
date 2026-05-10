package com.papi.nova.utils;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.res.Configuration;
import android.view.View;

import androidx.test.core.app.ApplicationProvider;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@Config(sdk = {33})
@RunWith(RobolectricTestRunner.class)
public class UiHelperTvTest {
    @Test
    public void isTvDeviceHonorsTelevisionConfiguration() {
        Context base = ApplicationProvider.getApplicationContext();
        Configuration config = new Configuration(base.getResources().getConfiguration());
        config.uiMode = (config.uiMode & ~Configuration.UI_MODE_TYPE_MASK) |
                Configuration.UI_MODE_TYPE_TELEVISION;

        Context tvContext = base.createConfigurationContext(config);

        assertTrue(UiHelper.isTvDevice(tvContext));
    }

    @Test
    public void isTvDeviceReturnsFalseForNormalConfiguration() {
        Context base = ApplicationProvider.getApplicationContext();
        Configuration config = new Configuration(base.getResources().getConfiguration());
        config.uiMode = (config.uiMode & ~Configuration.UI_MODE_TYPE_MASK) |
                Configuration.UI_MODE_TYPE_NORMAL;

        Context normalContext = base.createConfigurationContext(config);

        assertFalse(UiHelper.isTvDevice(normalContext));
    }

    @Test
    public void applyTvFocusStyleMakesViewDpadFocusable() {
        Context context = ApplicationProvider.getApplicationContext();
        View view = new View(context);

        UiHelper.applyTvFocusStyle(view);

        assertTrue(view.isFocusable());
        assertFalse(view.isFocusableInTouchMode());
    }
}
