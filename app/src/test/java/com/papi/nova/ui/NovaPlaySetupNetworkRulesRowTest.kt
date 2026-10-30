package com.papi.nova.ui

import android.content.Context
import androidx.preference.PreferenceManager
import androidx.test.core.app.ApplicationProvider
import com.papi.nova.R
import com.papi.nova.network.NetworkLaunchProfileChoice
import com.papi.nova.profiles.ProfilesManager
import com.papi.nova.profiles.SettingsProfile
import java.io.File
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Network Rules is reachable from Play Setup before anything has been saved. The Settings Profile
 * row was its only way in, and it stayed hidden until a profile existed: papi could not find it
 * until one was saved (#415). The rules screen says to save a profile first.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], shadows = [com.papi.nova.shadows.ShadowMoonBridge::class, com.papi.nova.shadows.ShadowGameManager::class])
class NovaPlaySetupNetworkRulesRowTest {
    private lateinit var context: Context

    @Before fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        ProfilesManager.instance = null
        File(context.filesDir, "profiles").deleteRecursively()
        PreferenceManager.getDefaultSharedPreferences(context).edit().clear().commit()
    }

    private fun activity(host: String? = "fixture-host"): NovaGameDetailActivity {
        val activity = Robolectric.buildActivity(NovaGameDetailActivity::class.java).get()
        val type = activity.javaClass
        type.getDeclaredField("serverUuid").apply { isAccessible = true }.set(activity, host)
        if (host != null) {
            type.getDeclaredMethod("revalidateLaunchProfile").apply { isAccessible = true }.invoke(activity)
        }
        return activity
    }

    private fun row(activity: NovaGameDetailActivity): NovaPlaySetupRowState? =
        activity.javaClass.getDeclaredMethod("launchProfileRow").apply { isAccessible = true }.invoke(activity)
            as NovaPlaySetupRowState?

    @Test fun withNoSavedProfilesTheRowLeadsToNetworkRules() {
        val activity = activity()
        val row = row(activity)
        assertNotNull("the only way to Network Rules was hidden with no saved profiles", row)
        assertEquals(context.getString(R.string.nova_launch_profile_row), row!!.label)
        val rules = context.getString(R.string.nova_launch_profile_edit_rules)
        assertEquals("nothing else to choose yet", listOf(rules), row.options.map { it.label })
        assertEquals(context.getString(R.string.nova_launch_profile_caption_rules_off), row.caption)

        row.options.single().onSelect?.invoke()
        assertEquals(NovaNetworkRulesActivity::class.java.name, shadowOf(activity).nextStartedActivity.component?.className)
    }

    @Test fun withASavedProfileEveryChoiceIsStillOffered() {
        val manager = ProfilesManager.getInstance()
        manager.add(SettingsProfile(UUID.randomUUID(), "Travel", 1L, 1L, emptyMap()))
        val options = row(activity())!!.options.map { it.label }
        assertEquals(context.getString(R.string.nova_launch_profile_automatic), options.first())
        assertTrue(options.contains("Travel"))
        assertEquals(context.getString(R.string.nova_launch_profile_edit_rules), options.last())
    }

    @Test fun whereRulesNeverApplyThereIsNoRow() {
        assertNull("no host to hold rules for", row(activity(host = null)))
    }

    @Test fun aRowOffersNetworkRulesOnlyOnceTheChoiceIsKnown() {
        // The plan has not been worked out yet, so there is nothing to read in the row.
        val activity = Robolectric.buildActivity(NovaGameDetailActivity::class.java).get()
        activity.javaClass.getDeclaredField("serverUuid").apply { isAccessible = true }.set(activity, "fixture-host")
        assertNull(row(activity))
        assertNull(displayedChoice(activity))
    }

    private fun displayedChoice(activity: NovaGameDetailActivity): NetworkLaunchProfileChoice? {
        val field = activity.javaClass.getDeclaredField("displayedLaunchChoice\$delegate").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        return (field.get(activity) as androidx.compose.runtime.MutableState<NetworkLaunchProfileChoice?>).value
    }
}
