package com.papi.nova.shadows

import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements

@Implements(className = "com.android.internal.policy.BackdropFrameRenderer", isInAndroidSdk = false)
class ShadowBackdropFrameRenderer {
    @Implementation
    protected fun run() = Unit
}
