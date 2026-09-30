package com.papi.nova.preferences

import android.content.Context
import android.os.Looper
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** Inject failed store acknowledgements without changing the stored choice. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class NovaFineEditReceiptQueueTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val definitions get() = NovaSettingDefinitions.load(context)
    private val models = ViewModelStore()
    private class Store : NovaSettingsStore {
        var owner = "setup-a"
        var values = mapOf<String, NovaSettingValue>(
            NovaSettingsMigration.TIER to NovaSettingValue.StringValue("custom"),
            NovaSettingsMigration.CUSTOM_EXISTS to NovaSettingValue.BooleanValue(true),
            NovaSettingsMigration.CUSTOM_AUTO to NovaSettingValue.BooleanValue(false),
            "list_resolution" to NovaSettingValue.StringValue("1920x1080"),
            "list_fps" to NovaSettingValue.StringValue("60"),
            "seekbar_bitrate_kbps" to NovaSettingValue.IntValue(350000),
            "video_format" to NovaSettingValue.StringValue("forceh265"))
        val failures = linkedMapOf<String, NovaTierSaveResult>()
        val accepted = mutableListOf<Set<String>>()
        var supported = true
        var nextSaveBarrier: kotlinx.coroutines.CompletableDeferred<Unit>? = null
        var saveBlocked = false
        override fun tierOwner() = owner
        override suspend fun snapshot(definitions: NovaSettingsDefinitionSet) =
            definitions.settings.mapNotNull { d -> d.defaultValue?.let { d.key to it } }.toMap() + values
        override suspend fun storedStreamKeys() = values.keys
        override suspend fun deviceTierInputs() = NovaTierInputs(NovaSize(1920,1080),listOf(60,120),NovaDistance.HAND,
            NovaDeviceCapabilities(if (supported) listOf(NovaCodecCapability(NovaCodecChoice.HEVC,"fixture",
                listOf(NovaDecodePoint(NovaSize(3840,2160),120)))) else emptyList()))
        override suspend fun saveStreamEdits(updates: List<Pair<NovaSettingDefinition,NovaSettingValue>>,
            removeKeys: Set<String>, expectedOwner: Any?): NovaTierSaveResult {
            if (owner != expectedOwner) return NovaTierSaveResult.SUPERSEDED
            nextSaveBarrier?.let { nextSaveBarrier=null;saveBlocked=true;it.await() }
            val fields = updates.map { it.first.key }.toSet()
            failures.keys.firstOrNull { it in fields }?.let { return failures.getValue(it) }
            updateAtomically(updates,removeKeys)
            accepted += fields
            return NovaTierSaveResult.SAVED
        }
        override suspend fun set(definition: NovaSettingDefinition,value: NovaSettingValue) { values += definition.key to value }
        override suspend fun updateAtomically(updates: List<Pair<NovaSettingDefinition,NovaSettingValue>>,removeKeys: Set<String>) {
            values = values - removeKeys + updates.associate { it.first.key to it.second }
        }
        override suspend fun reset(definition: NovaSettingDefinition) { values -= definition.key }
        override suspend fun overrideKeys(definitions: NovaSettingsDefinitionSet) = emptySet<String>()
        override suspend fun resettableKeys(definitions: NovaSettingsDefinitionSet) = emptySet<String>()
    }
    @After fun finish() { models.clear() }
    private fun await(done: () -> Boolean) {
        val end=System.nanoTime()+5_000_000_000L
        while(!done() && System.nanoTime()<end) { shadowOf(Looper.getMainLooper()).idle();Thread.sleep(5) }
        assertTrue("owned operation completed",done())
    }
    private fun model(store: Store) = NovaSettingsViewModel(definitions,store).also {
        models.put("settings",it);await { it.streamTiers.value != null }
    }
    private fun quality(vm: NovaSettingsViewModel) = vm.uiState.value.quickSettings.first { it.key==NovaTierControls.QUALITY_KEY }
    private fun write(vm: NovaSettingsViewModel,key: String,value: String) {
        var done=false
        vm.setValue(definitions.require(key),NovaSettingValue.StringValue(value)) { done=true };await { done }
    }
    private fun retry(vm: NovaSettingsViewModel) {
        var done=false
        vm.setValue(quality(vm),NovaSettingValue.StringValue("retry_tier")) { done=true };await { done }
    }

    @Test fun earlierIndependentFailureBlocksPreflightAfterLaterSuccessAndRetryPreservesBoth() {
        val store=Store();store.failures["list_resolution"]=NovaTierSaveResult.FAILED
        val vm=model(store);var done=0
        vm.setValue(definitions.require("list_resolution"),NovaSettingValue.StringValue("3840x2160")) { done++ }
        vm.setValue(definitions.require("list_fps"),NovaSettingValue.StringValue("120")) { done++ }
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Unconfined)
        var settled=false;var preflightAllowed=false
        scope.launch {
            vm.awaitStreamWrites();settled=true
            preflightAllowed=vm.uiState.value.tierSaveResult !in setOf(NovaTierSaveResult.FAILED,
                NovaTierSaveResult.PROFILE_FAILED,NovaTierSaveResult.SUPERSEDED)
        }
        await { done==2 && settled };scope.cancel()
        assertEquals(NovaSettingValue.StringValue("1920x1080"),store.values["list_resolution"])
        assertEquals(NovaSettingValue.StringValue("120"),store.values["list_fps"])
        assertEquals(NovaTierSaveResult.FAILED,vm.uiState.value.tierSaveResult)
        assertFalse("the real GDA receipt guard must reject this partly applied plan",preflightAllowed)
        assertTrue(quality(vm).options.any { it.value=="retry_tier" })
        store.failures.clear();retry(vm)
        assertEquals(NovaTierSaveResult.SAVED,vm.uiState.value.tierSaveResult)
        assertEquals(NovaSettingValue.StringValue("3840x2160"),store.values["list_resolution"])
        assertEquals(NovaSettingValue.StringValue("120"),store.values["list_fps"])
        assertEquals(NovaSettingValue.IntValue(350000),store.values["seekbar_bitrate_kbps"])
        assertFalse(quality(vm).options.any { it.value=="retry_tier" })
    }

    @Test fun retrySubmitsAllIndependentlyFailedFields() {
        val store=Store();store.failures["list_resolution"]=NovaTierSaveResult.FAILED
        store.failures["list_fps"]=NovaTierSaveResult.PROFILE_FAILED
        val vm=model(store)
        write(vm,"list_resolution","3840x2160");write(vm,"list_fps","120")
        store.failures.clear();retry(vm)
        assertEquals(NovaSettingValue.StringValue("3840x2160"),store.values["list_resolution"])
        assertEquals(NovaSettingValue.StringValue("120"),store.values["list_fps"])
        assertEquals(NovaTierSaveResult.SAVED,vm.uiState.value.tierSaveResult)
    }

    @Test fun newerSameFieldSupersedesOnlyItsFailedChoice() {
        val store=Store();store.failures["list_resolution"]=NovaTierSaveResult.FAILED
        store.failures["list_fps"]=NovaTierSaveResult.FAILED
        val vm=model(store)
        write(vm,"list_resolution","3840x2160");write(vm,"list_fps","120")
        store.failures.remove("list_resolution")
        write(vm,"list_resolution","2560x1440")
        assertEquals(NovaTierSaveResult.FAILED,vm.uiState.value.tierSaveResult)
        store.failures.clear();retry(vm)
        assertEquals(NovaSettingValue.StringValue("2560x1440"),store.values["list_resolution"])
        assertEquals(NovaSettingValue.StringValue("120"),store.values["list_fps"])
        assertEquals(NovaTierSaveResult.SAVED,vm.uiState.value.tierSaveResult)
    }

    @Test fun replacementOwnerCannotReceiveFailedOldChoicesThroughRetry() {
        val store=Store();store.failures["list_resolution"]=NovaTierSaveResult.FAILED
        val vm=model(store);write(vm,"list_resolution","3840x2160")
        store.owner="setup-b";val replacement=store.values;store.failures.clear()
        retry(vm)
        assertEquals(replacement,store.values)
        assertTrue(store.accepted.isEmpty())
        assertEquals(NovaTierSaveResult.SUPERSEDED,vm.uiState.value.tierSaveResult)
        var refreshed=false;vm.refresh { refreshed=true };await { refreshed }
        write(vm,"list_fps","120")
        assertEquals(NovaTierSaveResult.SAVED,vm.uiState.value.tierSaveResult)
        assertFalse(quality(vm).options.any { it.value=="retry_tier" })
        retry(vm)
        assertEquals(NovaSettingValue.StringValue("1920x1080"),store.values["list_resolution"])
    }

    @Test fun explicitTierChoiceSupersedesFailedFineIntents() {
        val store=Store();store.failures["list_resolution"]=NovaTierSaveResult.FAILED
        val vm=model(store);write(vm,"list_resolution","3840x2160")
        write(vm,NovaTierControls.QUALITY_KEY,"recommended")
        assertEquals(NovaTierSaveResult.SAVED,vm.uiState.value.tierSaveResult)
        store.failures.clear();retry(vm)
        assertEquals(NovaSettingValue.StringValue("1920x1080"),store.values["list_resolution"])
        assertEquals(NovaTier.RECOMMENDED,vm.pictureTier)
    }

    @Test fun anotherRetryFailureRetainsEveryUnacknowledgedField() {
        val store=Store();store.failures["list_resolution"]=NovaTierSaveResult.FAILED
        store.failures["list_fps"]=NovaTierSaveResult.PROFILE_FAILED
        val vm=model(store)
        write(vm,"list_resolution","3840x2160");write(vm,"list_fps","120")
        store.failures.remove("list_fps");retry(vm)
        assertEquals(NovaTierSaveResult.FAILED,vm.uiState.value.tierSaveResult)
        assertEquals(NovaSettingValue.StringValue("120"),store.values["list_fps"])
        store.failures.clear();retry(vm)
        assertEquals(NovaSettingValue.StringValue("3840x2160"),store.values["list_resolution"])
        assertEquals(NovaTierSaveResult.SAVED,vm.uiState.value.tierSaveResult)
    }

    @Test fun rejectedUnavailableTierChoiceKeepsTheUnresolvedFineRetry() {
        val store=Store();store.supported=false;store.failures["list_resolution"]=NovaTierSaveResult.FAILED
        val vm=model(store);assertFalse(vm.streamTiers.value!!.max.available)
        write(vm,"list_resolution","3840x2160")
        write(vm,NovaTierControls.QUALITY_KEY,"max")
        assertEquals(NovaTier.CUSTOM,vm.pictureTier)
        assertEquals(NovaTierSaveResult.FAILED,vm.uiState.value.tierSaveResult)
        assertTrue(quality(vm).options.any { it.value=="retry_tier" })
        store.failures.clear();retry(vm)
        assertEquals(NovaSettingValue.StringValue("3840x2160"),store.values["list_resolution"])
        assertEquals(NovaTierSaveResult.SAVED,vm.uiState.value.tierSaveResult)
    }

    @Test fun tierBecomingUnavailableWhileQueuedDoesNotSupersedeFailedChoices() {
        val store=Store();store.failures["list_resolution"]=NovaTierSaveResult.FAILED
        val vm=model(store);assertTrue(vm.streamTiers.value!!.max.available)
        write(vm,"list_resolution","3840x2160")
        val release=kotlinx.coroutines.CompletableDeferred<Unit>();store.nextSaveBarrier=release
        var done=0
        vm.setValue(definitions.require("list_fps"),NovaSettingValue.StringValue("120")) { done++ }
        await { store.saveBlocked }
        vm.setValue(quality(vm),NovaSettingValue.StringValue("max")) { done++ }
        store.supported=false;release.complete(Unit)
        await { done==2 }
        assertFalse(vm.streamTiers.value!!.max.available)
        assertEquals(NovaTier.CUSTOM,vm.pictureTier)
        assertEquals(NovaTierSaveResult.FAILED,vm.uiState.value.tierSaveResult)
        assertTrue(quality(vm).options.any { it.value=="retry_tier" })
        store.failures.clear();retry(vm)
        assertEquals(NovaSettingValue.StringValue("3840x2160"),store.values["list_resolution"])
        assertEquals(NovaSettingValue.StringValue("120"),store.values["list_fps"])
    }
}
