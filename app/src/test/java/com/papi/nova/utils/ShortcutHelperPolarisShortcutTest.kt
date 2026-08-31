package com.papi.nova.utils

import android.app.Activity
import com.papi.nova.AppView
import com.papi.nova.Game
import com.papi.nova.ShortcutTrampoline
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@Config(sdk = [33])
@RunWith(RobolectricTestRunner::class)
class ShortcutHelperPolarisShortcutTest {
    @Test
    fun primitiveOverloadBuildsSameShortcutTrampolineContractAsLegacyOverload() {
        val activity = Robolectric.buildActivity(Activity::class.java).get()
        val shortcutManager = activity.getSystemService(ShortcutManagerClass)
        val shadowShortcutManager = shadowOf(shortcutManager)
        shadowShortcutManager.setIsRequestPinShortcutSupported(true)

        val hostUuid = "F976460C-ED1F-6F05-4802-CF641F329CB9"
        val hostName = "STUDIO"
        val appUuid = "875C7772-6DDD-8124-4293-F7FB09D44A97"
        val appId = 1519944208
        val appName = "Ys VIII: Lacrimosa of Dana"
        val hdrSupported = true

        assertEquals(
            GameShortcutPinState.AVAILABLE,
            ShortcutHelper(activity).getGameShortcutPinState(hostUuid, appId),
        )

        val result = ShortcutHelper(activity).createPinnedGameShortcut(
            hostUuid = hostUuid,
            hostName = hostName,
            appUuid = appUuid,
            appId = appId,
            appName = appName,
            hdrSupported = hdrSupported,
            iconBits = null,
        )

        assertTrue("requestPinShortcut should report success", result)

        // pinnedShortcuts mirrors the real ShortcutManager API, so it's read off the
        // real (Robolectric-intercepted) manager rather than the shadow instance.
        val pinned = shortcutManager.pinnedShortcuts
        assertEquals(1, pinned.size)

        val intent = requireNotNull(pinned[0].intent) { "Pinned shortcut is missing its intent" }
        assertEquals(ShortcutTrampoline::class.java.name, intent.component?.className)
        assertEquals(android.content.Intent.ACTION_DEFAULT, intent.action)
        assertEquals(hostName, intent.getStringExtra(AppView.NAME_EXTRA))
        assertEquals(hostUuid, intent.getStringExtra(AppView.UUID_EXTRA))
        assertEquals(appName, intent.getStringExtra(Game.EXTRA_APP_NAME))
        assertEquals(appUuid, intent.getStringExtra(Game.EXTRA_APP_UUID))
        assertEquals("" + appId, intent.getStringExtra(Game.EXTRA_APP_ID))
        assertEquals(hdrSupported, intent.getBooleanExtra(Game.EXTRA_APP_HDR, !hdrSupported))
        assertEquals(
            GameShortcutPinState.PINNED,
            ShortcutHelper(activity).getGameShortcutPinState(hostUuid, appId),
        )
        assertEquals(
            "pin state must be scoped to the exact host and app",
            GameShortcutPinState.AVAILABLE,
            ShortcutHelper(activity).getGameShortcutPinState(hostUuid, appId + 1),
        )
        assertEquals(
            "a shortcut pinned for another host must not claim this game is pinned",
            GameShortcutPinState.AVAILABLE,
            ShortcutHelper(activity).getGameShortcutPinState("OTHER-HOST", appId),
        )
    }

    @Test
    fun reportsUnsupportedWhenLauncherCannotRequestPinnedShortcuts() {
        val activity = Robolectric.buildActivity(Activity::class.java).get()
        val shortcutManager = activity.getSystemService(ShortcutManagerClass)
        shadowOf(shortcutManager).setIsRequestPinShortcutSupported(false)

        assertEquals(
            GameShortcutPinState.UNSUPPORTED,
            ShortcutHelper(activity).getGameShortcutPinState("HOST", 42),
        )
    }

    companion object {
        // Local alias keeps the getSystemService call below on one line.
        private val ShortcutManagerClass = android.content.pm.ShortcutManager::class.java
    }
}
