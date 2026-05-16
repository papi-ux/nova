package com.papi.nova.preferences

import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.closeSoftKeyboard
import androidx.test.espresso.action.ViewActions.typeText
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.papi.nova.R
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AddComputerManuallySmokeTest {
    @get:Rule
    val activityRule = ActivityScenarioRule(AddComputerManually::class.java)

    @Test
    fun addComputerScreenShowsCoreControls() {
        onView(withId(R.id.hostTextView)).check(matches(isDisplayed()))
        onView(withId(R.id.addPcButton)).check(matches(isDisplayed()))
    }

    @Test
    fun hostFieldAcceptsManualEntry() {
        onView(withId(R.id.hostTextView))
            .perform(typeText("192.168.1.42"), closeSoftKeyboard())
            .check(matches(withText("192.168.1.42")))
    }
}
