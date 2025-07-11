package com.limelight.shadows;

import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;

@Implements(value = com.limelight.nvstream.jni.MoonBridge.class, isInAndroidSdk = false)
public class ShadowMoonBridge {

    // Static initializer override to prevent System.loadLibrary
    @Implementation
    protected static void __staticInitializer__() {
        // no-op
    }

    // Provide minimal nested AudioConfiguration
    public static class AudioConfiguration {
        public final int channelCount;
        public final int channelMask;
        public AudioConfiguration(int c, int m) {
            this.channelCount = c; this.channelMask = m;
        }
        public int toInt() { return 0; }
    }

    // Define constants minimally needed by code under test
    public static final AudioConfiguration AUDIO_CONFIGURATION_STEREO = new AudioConfiguration(2, 0x3);
    public static final AudioConfiguration AUDIO_CONFIGURATION_51_SURROUND = new AudioConfiguration(6, 0x3F);
    public static final AudioConfiguration AUDIO_CONFIGURATION_71_SURROUND = new AudioConfiguration(8, 0x63F);

    public static final int DR_OK = 0;

    public static int CAPABILITY_SLICES_PER_FRAME(byte s) { return 0; }

    public static int getPendingAudioDuration() { return 0; }

    // stubbed methods used by code but not relevant to unit tests
    public static void cleanupBridge() {}
}