package com.papi.nova.ui

import android.content.Context
import android.os.Looper
import androidx.lifecycle.ViewModelStore
import androidx.preference.PreferenceManager
import androidx.test.core.app.ApplicationProvider
import com.papi.nova.api.PolarisCapabilities
import com.papi.nova.preferences.*
import com.papi.nova.profiles.ProfilesManager
import java.io.File
import java.util.UUID
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** Actual XML definitions and Play Setup callbacks, through the device settings disk store. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class NovaManualBitrateUiTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val models = ViewModelStore()
    private val prefs get() = PreferenceManager.getDefaultSharedPreferences(context)
    private val input = NovaTierInputs(NovaSize(1920,1080), listOf(60,120), NovaDistance.HAND,
        NovaDeviceCapabilities(listOf(NovaCodecCapability(NovaCodecChoice.HEVC,"fixture",listOf(
            NovaDecodePoint(NovaSize(1920,1080),120))))))

    @Before fun clearDeviceStore() {
        ProfilesManager.instance = null
        prefs.edit().clear().commit()
        NovaTierRuntime.installForTest(input)
    }
    @After fun stop() { models.clear(); NovaTierRuntime.installForTest(null) }

    private fun model(): NovaSettingsViewModel = NovaSettingsViewModel(
        NovaSettingDefinitions.load(context), NovaSettingsRepository.createForTest(context,prefs,
            File(context.filesDir,"manual-bitrate-${UUID.randomUUID()}.preferences_pb")),
        initialCategoryKey="category_stream_quality",
    ).also { models.put("settings",it); await { it.streamTiers.value != null } }

    private fun await(condition: () -> Boolean) {
        val deadline = System.nanoTime()+5_000_000_000L
        while (!condition() && System.nanoTime()<deadline) {
            shadowOf(Looper.getMainLooper()).idle(); Thread.sleep(5)
        }
        assertTrue("the real device write must complete",condition())
    }

    @Test fun mainAndMeteredManualSlidersExposeFiveHundredWhileAutomaticAdviceStaysThreeHundred() {
        val definitions = NovaSettingDefinitions.load(context)
        assertEquals(500000, PreferenceConfiguration.MAX_BITRATE_KBPS)
        for (key in listOf(PreferenceConfiguration.BITRATE_PREF_STRING,"seekbar_metered_bitrate_kbps")) {
            val definition = definitions.require(key)
            assertEquals(key,NovaSettingType.Slider,definition.type)
            assertEquals(key,500000,definition.max)
        }
        assertEquals(300000,NovaBitrateAdvice.recommend(3840,2160,120,NovaCodecChoice.PYROWAVE).kbps)
        assertEquals(300000,NovaBitrateAdvice.AUTOMATIC_MAX_KBPS)
    }

    @Test fun customTextFormAcceptsFiveHundredAndRejectsValuesPastIt() {
        val key = PreferenceConfiguration.CUSTOM_BITRATE_PREF_STRING
        var saved: String? = null
        val form = novaTextFormPage(context,key,"Manual bitrate","",true,{ saved=it })
        assertNull(form.onSubmit(mapOf(key to "500")))
        assertEquals("500",saved)
        for (invalid in listOf("500.001","501","NaN","Infinity","0","-1")) {
            assertNotNull(invalid,form.onSubmit(mapOf(key to invalid)))
            assertEquals("a rejected value cannot replace the saved pin","500",saved)
        }
    }

    @Test fun everyGamePlaySetupCanStepToFiveHundredAndKeepTheManualCustomPinAcrossRecommended() {
        prefs.edit().putString(NovaSettingsMigration.TIER,"custom")
            .putString("list_resolution","1920x1080").putString("list_fps","60")
            .putString("video_format","forceh265").putInt("seekbar_bitrate_kbps",495000)
            .putBoolean(NovaSettingsMigration.CUSTOM_AUTO,false).commit()
        val model = model()
        val row = buildNovaDevicePlaySetupRows(model.uiState.value) { definition,value -> model.setValue(definition,value) }
            .single { it.row == NovaPlaySetupRow.DEVICE_BITRATE }
        val maximum = row.options.single { it.label == "500 Mbps" }
        assertTrue(maximum.enabled)
        maximum.onSelect!!.invoke()
        await { !model.uiState.value.tierSavePending && prefs.getInt("seekbar_bitrate_kbps",0)==500000 }
        assertEquals(NovaTier.CUSTOM,model.pictureTier)
        assertFalse(prefs.getBoolean(NovaSettingsMigration.CUSTOM_AUTO,true))
        model.useRecommended()
        await { model.pictureTier==NovaTier.RECOMMENDED && !model.uiState.value.tierSavePending }
        assertEquals("the paused Custom pin is device-only",500000,prefs.getInt("seekbar_bitrate_kbps",0))
        assertFalse(prefs.getBoolean(NovaSettingsMigration.CUSTOM_AUTO,true))
        model.selectPictureTier(NovaTier.CUSTOM)
        await { model.pictureTier==NovaTier.CUSTOM && !model.uiState.value.tierSavePending }
        assertEquals("500 Mbps",model.bitrateText)
    }

    @Test fun aDevicePinDoesNotRaiseTheAbsentHostCapabilityOrEraseItsSavedValue() {
        val custom = NovaStreamPlan(1920,1080,60,NovaCodecChoice.HEVC,500000,NovaBitrateBasis.CUSTOM)
        for ((capabilities,maximum) in listOf(null to 300000,
            PolarisCapabilities("Polaris","fixture",PolarisCapabilities.Features(manualBitrateMaxKbps=500000),
                PolarisCapabilities.CaptureInfo()) to 500000)) {
            val host = NovaHostTierLimits().withCapabilities(capabilities)
            assertEquals(maximum,NovaStreamTiers.resolve(input.copy(host=host),NovaTier.CUSTOM,custom).bitrateKbps)
            assertEquals("host projection cannot edit device storage",500000,custom.bitrateKbps)
        }
    }
}
