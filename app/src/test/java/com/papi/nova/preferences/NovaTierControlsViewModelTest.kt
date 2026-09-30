package com.papi.nova.preferences

import android.content.Context
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** Exercises the definitions and writes used by Compose Settings, rather than the engine API alone. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class NovaTierControlsViewModelTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val definitions get() = NovaSettingDefinitions.load(context)
    private class Store(initial: Map<String, NovaSettingValue>, private val supported: Boolean = true) : NovaSettingsStore {
        var values = initial
        override suspend fun snapshot(definitions: NovaSettingsDefinitionSet) =
            definitions.settings.mapNotNull { d -> d.defaultValue?.let { d.key to it } }.toMap() + values
        override suspend fun storedStreamKeys() = values.keys
        override suspend fun deviceTierInputs() = NovaTierInputs(NovaSize(1920,1080), listOf(60,120), NovaDistance.HAND,
            NovaDeviceCapabilities(if (supported) listOf(NovaCodecCapability(NovaCodecChoice.HEVC,"fixture", listOf(
                NovaDecodePoint(NovaSize(1920,1080),120), NovaDecodePoint(NovaSize(3840,2160),60)))) else emptyList()))
        override suspend fun set(definition: NovaSettingDefinition, value: NovaSettingValue) { values += definition.key to value }
        override suspend fun updateAtomically(updates: List<Pair<NovaSettingDefinition,NovaSettingValue>>, removeKeys: Set<String>) {
            values = values - removeKeys + updates.associate { it.first.key to it.second }
        }
        override suspend fun reset(definition: NovaSettingDefinition) { values -= definition.key }
        override suspend fun overrideKeys(definitions: NovaSettingsDefinitionSet) = emptySet<String>()
        override suspend fun resettableKeys(definitions: NovaSettingsDefinitionSet) = emptySet<String>()
    }
    private fun await(done: () -> Boolean) {
        val end = System.nanoTime() + 5_000_000_000L
        while (!done() && System.nanoTime() < end) { shadowOf(Looper.getMainLooper()).idle(); Thread.sleep(5) }
        assertTrue("settings operation completed", done())
    }
    private fun model(store: Store) = NovaSettingsViewModel(definitions, store).also { vm -> await { vm.streamTiers.value != null } }
    private fun quality(vm: NovaSettingsViewModel) = vm.uiState.value.quickSettings.first { it.key == "nova_stream_preset" }
    private fun write(vm: NovaSettingsViewModel, definition: NovaSettingDefinition, value: NovaSettingValue) {
        var done = false
        vm.setValue(definition, value) { done = true }
        await { done }
    }
    private fun fresh() = mapOf(NovaSettingsMigration.TIER to NovaSettingValue.StringValue("recommended"),
        NovaSettingsMigration.CUSTOM_EXISTS to NovaSettingValue.BooleanValue(false),
        NovaSettingsMigration.AUTO to NovaSettingValue.BooleanValue(true))
    private fun custom() = fresh() + mapOf(NovaSettingsMigration.TIER to NovaSettingValue.StringValue("custom"),
        NovaSettingsMigration.CUSTOM_EXISTS to NovaSettingValue.BooleanValue(true),
        NovaSettingsMigration.CUSTOM_AUTO to NovaSettingValue.BooleanValue(false),
        "list_resolution" to NovaSettingValue.StringValue("2560x1440"), "list_fps" to NovaSettingValue.StringValue("60"),
        "video_format" to NovaSettingValue.StringValue("forceh265"), "seekbar_bitrate_kbps" to NovaSettingValue.IntValue(47000))

    @Test fun existingQualityRowShowsGeneratedChoicesAndEffectiveSelection() {
        val vm = model(Store(fresh()))
        assertEquals("Quality", quality(vm).title)
        assertTrue(quality(vm).options.map { it.value }.containsAll(listOf("saver","recommended","max","custom")))
        assertFalse(quality(vm).options.any { it.value in listOf("balanced","performance","quality") })
        assertEquals(NovaSettingValue.StringValue("recommended"), vm.uiState.value.values["nova_stream_preset"])
    }

    @Test fun rp6MaxChoiceShowsAboveNativeAlongsideItsDecoderCeiling() {
        val vm = model(Store(fresh()))
        val option = quality(vm).options.single { it.value == "max" }
        assertTrue("Max must explain downscaling in the actual Quality choices: ${option.caption}",
            option.caption.orEmpty().contains("Sharper than this screen"))
        assertTrue(option.caption.orEmpty().contains("4K"))
        assertTrue(option.caption.orEmpty().contains("60 fps"))
        val selectedCaption = NovaTierControls.caption(vm.streamTiers.value!!, NovaTier.MAX)
        assertTrue("The selected row must retain the explanation", selectedCaption.contains("Sharper than this screen"))
        assertTrue("The decoder limit must remain visible in the option", option.caption.orEmpty().contains("decodes"))
    }

    @Test fun choosingRecommendedThroughQualityNeverRewritesCustomPins() {
        val store = Store(custom())
        val before = store.values.filterKeys { it in NovaSettingsMigration.STREAM_KEYS || it == NovaSettingsMigration.CUSTOM_AUTO }
        val vm = model(store)
        write(vm, quality(vm), NovaSettingValue.StringValue("recommended"))
        assertEquals(NovaTier.RECOMMENDED, vm.pictureTier)
        assertEquals(before, store.values.filterKeys { it in NovaSettingsMigration.STREAM_KEYS || it == NovaSettingsMigration.CUSTOM_AUTO })
        assertFalse(store.values.containsKey("nova_stream_preset"))
        write(vm, quality(vm), NovaSettingValue.StringValue("custom"))
        assertEquals("47 Mbps", vm.bitrateText)
    }

    @Test fun generatedPlanProjectsItsSizeRateAndBitrateWithoutSavingThem() {
        val store = Store(fresh())
        val before = store.values
        val vm = model(store)
        assertEquals(NovaSettingValue.StringValue("120"), vm.uiState.value.values["list_fps"])
        assertEquals(NovaSettingValue.IntValue(30000), vm.uiState.value.values["seekbar_bitrate_kbps"])
        assertEquals(before, store.values)
    }

    @Test fun autoControlIsInTheExistingQualityCategoryAndCreatesAnIndependentCustomPin() {
        val store = Store(fresh())
        val vm = model(store)
        vm.selectCategory("category_stream_quality")
        val auto = vm.uiState.value.visibleSettings.firstOrNull { it.key == NovaSettingsMigration.AUTO }
        assertNotNull("Auto bitrate is reachable from Settings", auto)
        write(vm, auto!!, NovaSettingValue.BooleanValue(false))
        assertEquals(NovaTier.CUSTOM, vm.pictureTier)
        assertEquals(NovaSettingValue.IntValue(30000), store.values["seekbar_bitrate_kbps"])
        assertEquals(NovaSettingValue.BooleanValue(false), store.values[NovaSettingsMigration.CUSTOM_AUTO])
    }

    @Test fun qualityResetUsesRecommendedInsteadOfRemovingTheLegacyPreset() {
        val vm = model(Store(custom()))
        vm.resetValue(quality(vm))
        await { vm.pictureTier == NovaTier.RECOMMENDED }
        assertEquals("Auto · 30 Mbps", vm.bitrateText)
    }

    @Test fun unavailableGeneratedChoiceCannotChangeTheSavedSelection() {
        val store = Store(custom(), supported = false)
        val vm = model(store)
        assertFalse(vm.streamTiers.value!!.max.available)
        val before = store.values
        write(vm, quality(vm), NovaSettingValue.StringValue("max"))
        assertEquals(before, store.values)
        assertEquals(NovaTier.CUSTOM, vm.pictureTier)
    }
}
