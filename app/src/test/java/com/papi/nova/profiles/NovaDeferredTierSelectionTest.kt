package com.papi.nova.profiles

import androidx.test.core.app.ApplicationProvider
import com.papi.nova.preferences.NovaStreamSettings
import com.papi.nova.preferences.NovaTier
import com.papi.nova.preferences.NovaSettingsMigration
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[33])
class NovaDeferredTierSelectionTest {
    @Test fun deferredSelectionCannotOverwriteALaterExplicitProfileSave() {
        val context=ApplicationProvider.getApplicationContext<android.content.Context>()
        ProfilesManager.instance=null
        val manager=ProfilesManager.getInstance()
        assertTrue(manager.load(context))
        val profile=SettingsProfile(UUID.randomUUID(),"Fixture",0,0,mapOf("list_fps" to "60"))
        manager.add(profile);manager.setActive(profile.getUuid())
        NovaStreamSettings.select(context,NovaTier.MAX)
        assertEquals("max",profile.getOptions()!![NovaSettingsMigration.TIER])
        profile.setName("Later save")
        profile.selectStreamTier(NovaTier.SAVER)
        manager.update(profile)
        manager.awaitDeferredWritesForTest()
        ProfilesManager.instance=null
        val reloaded=ProfilesManager.getInstance()
        assertTrue(reloaded.load(context))
        assertEquals("Later save",reloaded.getActive()!!.getName())
        assertEquals("saver",reloaded.getActive()!!.getOptions()!![NovaSettingsMigration.TIER])
    }
}
