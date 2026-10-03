package com.papi.nova.preferences

import android.content.Context
import androidx.preference.PreferenceManager
import androidx.test.core.app.ApplicationProvider
import com.papi.nova.NovaApplication
import com.papi.nova.shadows.ShadowMoonBridge
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[33],application=NovaApplication::class,shadows=[ShadowMoonBridge::class])
class NovaTierApplicationStartupTest {
    @Test fun realApplicationSeedsRecommendedBeforeOtherDefaultsAndPreparesOnWorker()=runBlocking {
        val context=ApplicationProvider.getApplicationContext<Context>()
        assertTrue(context is NovaApplication)
        assertSame("This Application must start the runtime itself",context,NovaTierRuntime.initializedApplication)
        val prefs=PreferenceManager.getDefaultSharedPreferences(context)
        assertEquals("recommended",prefs.getString(NovaSettingsMigration.TIER,null))
        assertFalse(prefs.getBoolean(NovaSettingsMigration.CUSTOM_EXISTS,true))
        val snapshot=withTimeout(5000) { NovaTierRuntime.updates.filterNotNull().first() }
        assertSame(snapshot,NovaTierRuntime.snapshot())
    }
}
