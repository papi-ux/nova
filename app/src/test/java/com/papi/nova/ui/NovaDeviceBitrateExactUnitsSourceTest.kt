package com.papi.nova.ui

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/** Bounded wiring guard for the Activity closure; shared-page behavior is tested with real input. */
class NovaDeviceBitrateExactUnitsSourceTest {
    @Test fun playSetupDeviceBitrateUsesLocalizedMbpsExactEntryAndSavesOnlyThroughDeviceEdits() {
        val source = File("src/main/java/com/papi/nova/ui/NovaGameDetailActivity.kt").readText()
        val bitrate = source.substringAfter("if (row == NovaPlaySetupRow.DEVICE_BITRATE) {").substringBefore("return PlaySetupPage.Options")
        assertTrue("the exact field converts its displayed Mbps back to kbps", bitrate.contains("exactDivisor=1000"))
        assertTrue("the visible unit is localized", bitrate.contains("R.string.nova_settings_bitrate_exact_mbps"))
        assertTrue("only Save schedules a device edit", bitrate.contains("onSave={ deviceEdits.change(definition,NovaSettingValue.IntValue(it)) }"))
    }
}
