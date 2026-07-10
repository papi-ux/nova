package com.papi.nova.preferences

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NovaUpdateSettingsIntegrationTest {
    @Test
    fun softwareUpdateActionUsesInAppCheckerForComposeAndLegacySettings() {
        val source = File("src/main/java/com/papi/nova/preferences/StreamSettings.kt").readText()

        assertTrue(source.contains("private fun checkForNovaUpdate()"))
        assertTrue(source.contains("\"option_software_release\" -> checkForNovaUpdate()"))
        assertTrue(source.contains("findPreference<Preference>(\"option_software_release\")"))
        assertTrue(source.contains("checkForNovaUpdate()"))
        assertTrue(source.contains("NovaUpdateChecker.checkLatest"))
        assertFalse(
            "Software Update must not only punt to the GitHub releases page anymore",
            source.contains("\"option_software_release\" -> HelpLauncher.launchUrl(this, \"https://github.com/papi-ux/nova/releases\")")
        )
    }

    @Test
    fun softwareUpdateCopyAdvertisesInAppUpdateCheck() {
        val strings = File("src/main/res/values/strings.xml").readText()

        assertTrue(strings.contains("<string name=\"title_software_update\">Check for Updates</string>"))
        assertTrue(strings.contains("<string name=\"summary_software_update\">Check GitHub releases from inside Nova</string>"))
        assertTrue(strings.contains("<string name=\"nova_update_checking\">Checking for Nova updates…</string>"))
        assertTrue(strings.contains("<string name=\"nova_update_available_title\">Nova update available</string>"))
        assertTrue(strings.contains("<string name=\"nova_update_download_apk\">Download APK</string>"))
    }
}
