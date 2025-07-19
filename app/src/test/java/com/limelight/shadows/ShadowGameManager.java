package com.limelight.shadows;

import android.app.GameManager;
import android.app.GameState;
import android.content.Context;

import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;

@Implements(value = GameManager.class, isInAndroidSdk = true, callThroughByDefault = false)
public class ShadowGameManager {

    @Implementation
    protected void __constructor__(Context context) {
        // no-op constructor to avoid ServiceManager lookup
    }

    @Implementation
    protected void setGameState(GameState state) {
        // stub method – nothing to do in tests
    }
}