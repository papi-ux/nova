package com.papi.nova;

import android.os.Build;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

@Config(sdk = {33})
@RunWith(RobolectricTestRunner.class)
public class GameHdrDecisionTest {

    @Test
    public void explicitHdrOptInRequestsTenBitStreamOnSdrDisplay() {
        assertTrue(Game.shouldRequestHdrStream(true, false, Build.VERSION_CODES.TIRAMISU, false));
        assertTrue(Game.shouldShowSdr10BitOptInToast(true, false, Build.VERSION_CODES.TIRAMISU, false));
    }

    @Test
    public void hdr10DisplayDoesNotNeedSdrOptInToast() {
        assertTrue(Game.shouldRequestHdrStream(true, false, Build.VERSION_CODES.TIRAMISU, true));
        assertFalse(Game.shouldShowSdr10BitOptInToast(true, false, Build.VERSION_CODES.TIRAMISU, true));
    }

    @Test
    public void preNougatDevicesDoNotRequestHdrStream() {
        assertFalse(Game.shouldRequestHdrStream(true, false, Build.VERSION_CODES.M, false));
        assertTrue(Game.shouldShowHdrRequiresAndroidNToast(true, false, Build.VERSION_CODES.M));
    }
}
