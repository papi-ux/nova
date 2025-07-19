package com.limelight.shadows;

import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;

@Implements(className = "com.android.internal.policy.BackdropFrameRenderer", isInAndroidSdk = false)
public class ShadowBackdropFrameRenderer {
    @Implementation
    protected void run() {
        // No-op to avoid calling into Choreographer which crashes under Robolectric
    }
}