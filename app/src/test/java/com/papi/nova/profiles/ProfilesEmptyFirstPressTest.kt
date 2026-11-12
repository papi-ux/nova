package com.papi.nova.profiles

import android.content.Context
import android.os.Looper
import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.papi.nova.EditProfileActivity
import com.papi.nova.ProfilesActivity
import com.papi.nova.R
import com.papi.nova.shadows.ShadowGameManager
import com.papi.nova.shadows.ShadowMoonBridge
import java.util.UUID
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * The empty Presets page opens with + holding focus, so the first A makes a preset (#480).
 *
 * On the RP6 the page said "Press + or tap it", yet the first A only put focus on + and a second
 * opened the editor. The page had opened in touch mode, and a window opening in touch mode takes
 * focus from a view that cannot hold it there: the first A was then spent bringing focus back.
 * These open the page in touch mode and read what has focus, then press A through the window's
 * key gate as a pad does. The platform's own step that spends a key on leaving touch mode does
 * not run under this runner; with + already holding focus it leaves the key alone.
 */
@Config(sdk = [33], shadows = [ShadowMoonBridge::class, ShadowGameManager::class])
@RunWith(RobolectricTestRunner::class)
class ProfilesEmptyFirstPressTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Before
    fun touchMode() {
        ProfilesManager.instance = null
        ProfilesManager.getInstance().load(context)
        InstrumentationRegistry.getInstrumentation().setInTouchMode(true)
    }

    @After
    fun keyMode() {
        InstrumentationRegistry.getInstrumentation().setInTouchMode(false)
        ProfilesManager.instance = null
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun ProfilesActivity.plus(): View = findViewById(R.id.addProfileFab)

    private fun press(activity: ProfilesActivity, keyCode: Int) {
        val now = SystemClock.uptimeMillis()
        for (action in listOf(KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP)) {
            activity.dispatchKeyEvent(KeyEvent(now, now, action, keyCode, 0, 0, 0, 0, 0, InputDevice.SOURCE_GAMEPAD))
        }
        idle()
    }

    @Test
    fun inTouchModePlusHoldsFocusWhenThePageOpens() {
        ActivityScenario.launch(ProfilesActivity::class.java).use { scenario ->
            idle()
            scenario.onActivity { activity ->
                assertTrue("the page opened in touch mode", activity.window.decorView.isInTouchMode)
                assertTrue("+ holds focus as the empty page opens", activity.plus().isFocused)
            }
        }
    }

    @Test
    fun theFirstAOpensTheEditor() {
        ActivityScenario.launch(ProfilesActivity::class.java).use { scenario ->
            idle()
            scenario.onActivity { activity ->
                press(activity, KeyEvent.KEYCODE_BUTTON_A)
                val next = shadowOf(activity).nextStartedActivity
                assertNotNull("one A opened the editor", next)
                assertEquals(EditProfileActivity::class.java.name, next.component!!.className)
            }
        }
    }

    @Test
    fun aTapOnPlusStillOpensTheEditorAtOnce() {
        ActivityScenario.launch(ProfilesActivity::class.java).use { scenario ->
            idle()
            scenario.onActivity { activity ->
                val plus = activity.plus()
                val now = SystemClock.uptimeMillis()
                val x = plus.width / 2f
                val y = plus.height / 2f
                plus.dispatchTouchEvent(MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, x, y, 0))
                plus.dispatchTouchEvent(MotionEvent.obtain(now, now + 50, MotionEvent.ACTION_UP, x, y, 0))
                idle()
                val next = shadowOf(activity).nextStartedActivity
                assertNotNull("one tap opened the editor", next)
                assertEquals(EditProfileActivity::class.java.name, next.component!!.className)
            }
        }
    }

    @Test
    fun withAPresetListedPlusIsAPlainButtonAgain() {
        val now = System.currentTimeMillis()
        ProfilesManager.getInstance().add(SettingsProfile(UUID.randomUUID(), "W8 test", now, now, null))
        ActivityScenario.launch(ProfilesActivity::class.java).use { scenario ->
            idle()
            scenario.onActivity { activity ->
                val plus = activity.plus()
                assertFalse("+ does not hold focus in touch mode beside a list", plus.isFocusableInTouchMode)
                assertFalse(plus.isFocused)
                assertNull("nothing opened", shadowOf(activity).nextStartedActivity)
            }
        }
    }
}
