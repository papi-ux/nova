package com.papi.nova.preferences

import android.content.Context
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import androidx.lifecycle.ViewModelStore
import com.papi.nova.profiles.ProfilesManager
import com.papi.nova.profiles.SettingsProfile
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class NovaTierSaveReceiptTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var manager: ProfilesManager
    private lateinit var profile: SettingsProfile
    private lateinit var vm: NovaSettingsViewModel
    private var completed = false
    private val models = ViewModelStore()
    @Before fun prepare() {
        ProfilesManager.instance = null
        manager = ProfilesManager.getInstance()
        assertTrue(manager.load(context))
        profile = SettingsProfile(UUID.randomUUID(),"Pinned",0L,0L,mapOf(
            "list_resolution" to "1920x1080", "list_fps" to "60", "seekbar_bitrate_kbps" to 350000,
            NovaSettingsMigration.TIER to "custom", NovaSettingsMigration.CUSTOM_AUTO to false))
        manager.add(profile); manager.setActive(profile.getUuid())
        val inputs = NovaTierInputs(NovaSize(1920,1080),listOf(60,120),NovaDistance.HAND,
            NovaDeviceCapabilities(listOf(NovaCodecCapability(NovaCodecChoice.HEVC,"fixture",
                listOf(NovaDecodePoint(NovaSize(3840,2160),120))))))
        NovaTierRuntime.installForTest(inputs)
        val prefs = context.getSharedPreferences("tier-receipt",0)
        prefs.edit().clear().putString(NovaSettingsMigration.TIER,"custom")
            .putString("list_resolution","1920x1080").putString("list_fps","60")
            .putInt("seekbar_bitrate_kbps",350000).putBoolean(NovaSettingsMigration.CUSTOM_AUTO,false).commit()
        val repo = NovaSettingsRepository.createForTest(context,prefs,File(context.filesDir,"tier-${UUID.randomUUID()}.preferences_pb"))
        vm = NovaSettingsViewModel(NovaSettingDefinitions.load(context),repo)
        models.put("settings",vm)
        await { vm.streamTiers.value != null }
    }
    @After fun finish() { models.clear(); manager.awaitDeferredWritesForTest(); NovaTierRuntime.installForTest(null) }
    private fun await(done: () -> Boolean) {
        val end=System.nanoTime()+5_000_000_000L
        while(!done() && System.nanoTime()<end) { shadowOf(Looper.getMainLooper()).idle();Thread.sleep(5) }
        assertTrue("owned save completed",done())
    }
    private fun quality() = vm.uiState.value.quickSettings.first { it.key == "nova_stream_preset" }
    private fun select() { vm.setValue(quality(),NovaSettingValue.StringValue("recommended")) { completed=true } }

    @Test fun completionWaitsForTheParticipatingSavedSetupReceipt() {
        val entered=CountDownLatch(1);val release=CountDownLatch(1)
        manager.openProfileWriter={ file -> object:FileOutputStream(file) {
            override fun write(bytes:ByteArray) { entered.countDown();check(release.await(5,TimeUnit.SECONDS));super.write(bytes) }
        } }
        try {
            select()
            await { entered.count==0L }
            repeat(20) { shadowOf(Looper.getMainLooper()).idle(); Thread.sleep(5) }
            assertFalse("base commit is not the setup save receipt",completed)
            assertTrue(quality().summary.contains("Saving"))
        } finally { release.countDown() }
        await { completed }
        assertTrue(quality().summary.contains("Saved"))
        ProfilesManager.instance=null
        val cold=ProfilesManager.getInstance();assertTrue(cold.load(context))
        assertEquals("recommended",cold.getActive()!!.getOptions()!![NovaSettingsMigration.TIER])
        assertEquals(350000,(cold.getActive()!!.getOptions()!!["seekbar_bitrate_kbps"] as Number).toInt())
    }

    @Test fun fineControlWaitsForItsSetupReceiptAndWritesTheExactManualPin() {
        val entered=CountDownLatch(1);val release=CountDownLatch(1)
        manager.openProfileWriter={ file -> object:FileOutputStream(file) {
            override fun write(bytes:ByteArray) { entered.countDown();check(release.await(5,TimeUnit.SECONDS));super.write(bytes) }
        } }
        try {
            vm.setValue(requireNotNull(NovaSettingDefinitions.load(context).find("seekbar_bitrate_kbps")),
                NovaSettingValue.IntValue(201124)) { completed=true }
            await { completed || entered.count==0L }
            assertEquals("fine edits must reach the participating setup writer",0L,entered.count)
            repeat(20) { shadowOf(Looper.getMainLooper()).idle();Thread.sleep(5) }
            assertFalse("fine edits must await the participating setup as tier choices do",completed)
        } finally { release.countDown() }
        await { completed };manager.awaitDeferredWritesForTest()
        ProfilesManager.instance=null
        val cold=ProfilesManager.getInstance();assertTrue(cold.load(context))
        assertEquals(201124,(cold.getActive()!!.getOptions()!!["seekbar_bitrate_kbps"] as Number).toInt())
        assertFalse(cold.getActive()!!.getOptions()!![NovaSettingsMigration.CUSTOM_AUTO] as Boolean)
    }

    @Test fun failedFineControlReceiptOffersRetryOfTheOwnedExactEdit() {
        manager.openProfileWriter={ throw IOException("injected fine-edit write failure") }
        vm.setValue(requireNotNull(NovaSettingDefinitions.load(context).find("seekbar_bitrate_kbps")),
            NovaSettingValue.IntValue(201124)) { completed=true }
        await { completed };manager.awaitDeferredWritesForTest()
        assertEquals(NovaTierSaveResult.PROFILE_FAILED,vm.uiState.value.tierSaveResult)
        assertTrue(quality().options.any { it.value=="retry_tier" })
        manager.openProfileWriter={ FileOutputStream(it) };completed=false
        vm.setValue(quality(),NovaSettingValue.StringValue("retry_tier")) { completed=true }
        await { completed };manager.awaitDeferredWritesForTest()
        ProfilesManager.instance=null
        val cold=ProfilesManager.getInstance();assertTrue(cold.load(context))
        assertEquals(201124,(cold.getActive()!!.getOptions()!!["seekbar_bitrate_kbps"] as Number).toInt())
        assertFalse(cold.getActive()!!.getOptions()!![NovaSettingsMigration.CUSTOM_AUTO] as Boolean)
    }

    @Test fun setupFailureIsVisibleAndRetrySavesTheCurrentIntentWithoutLosingThePin() {
        manager.openProfileWriter={ throw IOException("injected setup write failure") }
        select();await { completed };manager.awaitDeferredWritesForTest()
        assertEquals(NovaTier.RECOMMENDED,vm.pictureTier)
        assertTrue("partial result is visible",quality().summary.contains("saved setup could not be saved"))
        assertFalse(quality().summary.contains("Saved"))
        manager.openProfileWriter={ FileOutputStream(it) }
        completed=false
        vm.setValue(quality(),NovaSettingValue.StringValue("retry_tier")) { completed=true }
        await { completed };manager.awaitDeferredWritesForTest()
        ProfilesManager.instance=null
        val cold=ProfilesManager.getInstance();assertTrue(cold.load(context))
        assertEquals("recommended",cold.getActive()!!.getOptions()!![NovaSettingsMigration.TIER])
        assertEquals(350000,(cold.getActive()!!.getOptions()!!["seekbar_bitrate_kbps"] as Number).toInt())
        assertFalse(cold.getActive()!!.getOptions()!![NovaSettingsMigration.CUSTOM_AUTO] as Boolean)
    }
}
