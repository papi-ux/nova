package com.papi.nova.preferences

import android.app.Application
import android.content.Context
import android.content.Intent
import androidx.preference.Preference
import androidx.preference.PreferenceManager
import androidx.test.core.app.ApplicationProvider
import com.google.gson.Gson
import com.papi.nova.EditProfileActivity
import com.papi.nova.profiles.ProfilesManager
import com.papi.nova.profiles.SettingsProfile
import com.papi.nova.shadows.ShadowMoonBridge
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[33],application=Application::class,shadows=[ShadowMoonBridge::class])
class NovaStreamTierRoundSixTest {
    private val context:Context=ApplicationProvider.getApplicationContext()
    private val prefs get()=PreferenceManager.getDefaultSharedPreferences(context)
    private val file get()=File(context.filesDir,"profiles/profiles.json")
    @Before fun reset() {
        prefs.edit().clear().putString("list_resolution","1920x1080").putString("list_fps","120").commit()
        ProfilesManager.instance=null
        NovaTierRuntime.installForTest(null)
        NovaSettingsFeatureFlags.setComposeSettingsEnabled(context,false)
        file.delete()
    }
    @After fun clean() { ProfilesManager.instance=null; NovaTierRuntime.installForTest(null); file.delete() }
    private fun editDisk(options:Map<String,Any>, block:(EditProfileActivity,StreamSettings.SettingsFragment,SettingsProfile)->Unit) {
        val id=UUID.randomUUID()
        file.parentFile!!.mkdirs()
        file.writeText(Gson().toJson(mapOf("profiles" to listOf(SettingsProfile(id,"Disk setup",0,0,options)))))
        val manager=ProfilesManager.getInstance()
        assertTrue(manager.load(context))
        val profile=manager.getProfiles().single()
        val controller=Robolectric.buildActivity(EditProfileActivity::class.java,
            Intent(context,EditProfileActivity::class.java).putExtra("profileUuid",id.toString())).setup()
        try {
            val activity=controller.get()
            block(activity,activity.supportFragmentManager.fragments.filterIsInstance<StreamSettings.SettingsFragment>().single(),profile)
        } finally { controller.pause().stop().destroy() }
    }
    private fun save(activity:EditProfileActivity):Map<String,Any> {
        EditProfileActivity::class.java.getDeclaredMethod("saveProfile").apply { isAccessible=true }.invoke(activity)
        ProfilesManager.instance=null
        assertTrue(ProfilesManager.getInstance().load(context))
        return ProfilesManager.getInstance().getProfiles().single().getOptions()!!
    }
    @Test fun loadedNumericOverridesSurviveClassicSaveWithoutChangingTheTierOrPin() {
        for(tier in listOf("recommended","saver","custom")) {
            ProfilesManager.instance=null
            val numbers=mapOf("seekbar_bitrate_kbps" to 45000, "seekbar_deadzone" to 13,
                "seekbar_touchpad_sensitivity_opacity" to 117, "seekbar_resolution_scale_factor" to 85,
                "number_zoom_scale" to 1.25f)
            editDisk(numbers + mapOf(NovaSettingsMigration.TIER to tier, NovaSettingsMigration.AUTO to false,
                NovaSettingsMigration.CUSTOM_AUTO to false)) { activity,_,profile ->
                assertTrue("Must exercise Gson's Double representation",profile.getOptions()!!["seekbar_bitrate_kbps"] is Double)
                val saved=save(activity)
                numbers.forEach { (key,value) -> assertEquals(key,value.toDouble(),(saved[key] as? Number)?.toDouble()) }
                assertEquals(tier,saved[NovaSettingsMigration.TIER])
                assertEquals(false,saved[NovaSettingsMigration.AUTO])
                assertEquals(false,saved[NovaSettingsMigration.CUSTOM_AUTO])
                assertFalse(saved.containsKey("list_resolution"))
                assertFalse(saved.containsKey("list_fps"))
            }
        }
    }
    @Test fun migrationWriterRestoresNumbersAndStringSets() {
        NovaSettingsMigration.writeDifference(prefs,mapOf("integer" to 45000.0,"fraction" to 1.25,
            "long" to 4000000000.0,"set" to setOf("one","two")))
        assertEquals(45000,prefs.getInt("integer",0))
        assertEquals(1.25f,prefs.getFloat("fraction",0f))
        assertEquals(4000000000L,prefs.getLong("long",0L))
        assertEquals(setOf("one","two"),prefs.getStringSet("set",emptySet()))
    }
    @Test fun sparseStarsShowOnlyDifferentAndOverrideOnlyValues() {
        prefs.edit().putInt("seekbar_deadzone",13).putBoolean("checkbox_touchscreen_trackpad",false)
            .putBoolean("checkbox_enable_sops",true).remove("checkbox_multi_controller").commit()
        editDisk(mapOf("seekbar_deadzone" to 13,"checkbox_touchscreen_trackpad" to true,
            "checkbox_multi_controller" to false)) { _,fragment,_ ->
            fun starred(key:String)=fragment.findPreference<Preference>(key)!!.title.toString().startsWith("*")
            assertFalse("Inherited values are not overrides",starred("checkbox_enable_sops"))
            assertFalse("Double and Int are the same value",starred("seekbar_deadzone"))
            assertTrue(starred("checkbox_touchscreen_trackpad"))
            assertTrue("An override absent from the base still has a star",starred("checkbox_multi_controller"))
        }
    }
    @Test fun classicAutoCodecChangeUsesRoomDistanceAndRawInheritedSizeAndFps() {
        editDisk(mapOf("video_format" to "forceh265","seekbar_bitrate_kbps" to 35000,
            NovaSettingsMigration.CUSTOM_AUTO to true)) { activity,fragment,_ ->
            NovaTierRuntime.installForTest(NovaTierInputs(NovaSize(1920,1080),listOf(120),NovaDistance.ROOM,
                NovaDeviceCapabilities(emptyList())))
            val codec=fragment.findPreference<Preference>("video_format")!!
            assertTrue(codec.callChangeListener("forcepyrowave"))
            val memory=activity.getInMemoryPrefs()
            assertEquals(300000,memory.getInt("seekbar_bitrate_kbps",0))
            assertTrue(memory.getBoolean(NovaSettingsMigration.CUSTOM_AUTO,false))
            assertFalse(memory.contains("list_resolution")); assertFalse(memory.contains("list_fps"))
        }
    }
    @Test fun classicCodecChangeRetainsManualPinAndAutoFlag() {
        editDisk(mapOf("video_format" to "forceh265","seekbar_bitrate_kbps" to 45000,
            NovaSettingsMigration.CUSTOM_AUTO to false)) { activity,fragment,_ ->
            val codec=fragment.findPreference<Preference>("video_format")!!
            assertTrue(codec.callChangeListener("forcepyrowave"))
            assertEquals(45000,activity.getInMemoryPrefs().getInt("seekbar_bitrate_kbps",0))
            assertFalse(activity.getInMemoryPrefs().getBoolean(NovaSettingsMigration.CUSTOM_AUTO,true))
        }
    }
}
