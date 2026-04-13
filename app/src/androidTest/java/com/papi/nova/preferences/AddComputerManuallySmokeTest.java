package com.papi.nova.preferences;

import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.action.ViewActions.closeSoftKeyboard;
import static androidx.test.espresso.action.ViewActions.typeText;
import static androidx.test.espresso.assertion.ViewAssertions.matches;
import static androidx.test.espresso.matcher.ViewMatchers.isDisplayed;
import static androidx.test.espresso.matcher.ViewMatchers.withId;
import static androidx.test.espresso.matcher.ViewMatchers.withText;

import androidx.test.ext.junit.rules.ActivityScenarioRule;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.papi.nova.R;

import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class AddComputerManuallySmokeTest {
    @Rule
    public ActivityScenarioRule<AddComputerManually> activityRule =
            new ActivityScenarioRule<>(AddComputerManually.class);

    @Test
    public void addComputerScreenShowsCoreControls() {
        onView(withId(R.id.hostTextView)).check(matches(isDisplayed()));
        onView(withId(R.id.addPcButton)).check(matches(isDisplayed()));
    }

    @Test
    public void hostFieldAcceptsManualEntry() {
        onView(withId(R.id.hostTextView))
                .perform(typeText("192.168.1.42"), closeSoftKeyboard())
                .check(matches(withText("192.168.1.42")));
    }
}
