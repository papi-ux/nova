package com.papi.nova

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HonestLabelsSourceGuardTest {
    @Test
    fun theLibraryHeroSaysResumeOnlyWhileTheHostIsStillRunningTheGame() {
        val appView = File("src/main/java/com/papi/nova/AppView.kt").readText()
        val strings = File("src/main/res/values/strings.xml").readText()
        val heroStart = appView.indexOf("val appIsRunning = lastRunningAppId == finalTargetApp.app.appId")
        val hero = appView.substring(heroStart, appView.indexOf("endSessionView?.visibility", heroStart))

        assertTrue(
            "Resume is only honest while the host is still running the game; otherwise the button launches it, so it says Play",
            hero.contains("appIsRunning -> R.string.pcview_card_action_resume") &&
                hero.contains("else -> R.string.applist_hero_action_play") &&
                strings.contains("<string name=\"applist_hero_action_play\">Play</string>")
        )
    }

    @Test
    fun commandCenterSubtitleNoLongerPromisesQuickKeysFirst() {
        val strings = File("src/main/res/values/strings.xml").readText()

        assertTrue(
            "Quick Keys is the last panel now, so the subtitle names what the page is for",
            strings.contains("<string name=\"nova_quick_menu_headless_subtitle\">Session controls for Private Stream</string>") &&
                strings.contains("<string name=\"nova_quick_menu_virtual_subtitle\">Session controls for virtual display streaming</string>")
        )
        assertFalse(
            "no Command Center subtitle leads with Quick keys",
            strings.contains("_subtitle\">Quick keys")
        )
    }
}
