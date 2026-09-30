package com.papi.nova.ui

import androidx.activity.ComponentActivity
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.papi.nova.api.PolarisCapabilities
import com.papi.nova.binding.video.PyroWaveAvailability
import com.papi.nova.preferences.PreferenceConfiguration.FormatOption
import com.papi.nova.ui.panel.frames
import com.papi.nova.ui.panel.setPanelContent
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[33],qualifiers="w900dp-h520dp")
class NovaHostCodecComposeTest {
    @get:Rule val rule=createAndroidComposeRule<ComponentActivity>()
    @Test fun hostRefusalIsReadAtTheActualCodecOptionAndItsActivationIsProtected() {
        var writes=0
        val reason="This host needs GPU-native capture"
        val row=novaPlaySetupHostCodecRow(rule.activity,"forcepyrowave",FormatOption.FORCE_PYROWAVE,
            { PyroWaveAvailability.Status.AVAILABLE },{ PolarisCapabilities.PyrowaveUnavailable("capture_cpu",reason) },
            {true},onSelect={writes++})
        val pyro=row.options.first { it.label.contains("PyroWave") }
        rule.setPanelContent {
            NovaPlaySetupBands(listOf(NovaPlaySetupBand(null,row.options)),{ it.onSelect?.invoke() },
                rowModifier={ key,_ -> Modifier.testTag("host-codec-$key") })
        }
        rule.frames(6)
        rule.onNodeWithTag("host-codec-${novaPlaySetupOptionKey(0,pyro)}").assertIsNotEnabled().performClick()
        assertEquals(0,writes)
        assertEquals(reason,pyro.consequence)
        assertTrue("the disabled option's explanation is visible",rule.onAllNodes(
            androidx.compose.ui.test.hasText(reason,substring=true),useUnmergedTree=true).fetchSemanticsNodes().isNotEmpty())
        assertTrue(row.options.first { it.label==rule.activity.getString(com.papi.nova.R.string.videoformat_auto) }.enabled)
    }
}
