package com.papi.nova.shadows

import android.app.GameManager
import android.app.GameState
import android.content.Context
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements

@Implements(value = GameManager::class, isInAndroidSdk = true, callThroughByDefault = false)
class ShadowGameManager {
    @Implementation
    @Suppress("UNUSED_PARAMETER")
    protected fun __constructor__(context: Context) = Unit

    @Implementation
    @Suppress("UNUSED_PARAMETER")
    protected fun setGameState(state: GameState) = Unit
}
