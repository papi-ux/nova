package com.papi.nova.ui

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.view.ViewGroup
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.platform.ComposeView
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NovaLibraryActivityComposeTest {
    @get:Rule
    val composeRule = createEmptyComposeRule()

    @Test
    fun libraryShellShowsTvControls() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val intent = Intent(context, NovaLibraryActivity::class.java).apply {
            putExtra(NovaLibraryActivity.EXTRA_HOST, "127.0.0.1")
            putExtra(NovaLibraryActivity.EXTRA_SERVER_NAME, "Test Server")
            putExtra(NovaLibraryActivity.EXTRA_HTTPS_PORT, 47984)
            putExtra(NovaLibraryActivity.EXTRA_HTTP_PORT, 47989)
        }

        ActivityScenario.launch<NovaLibraryActivity>(intent).use {
            if (context.packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)) {
                it.onActivity { activity ->
                    val content = activity.findViewById<ViewGroup>(android.R.id.content)
                    assertTrue(content.getChildAt(0) is ComposeView)
                }
                return@use
            }

            composeRule.waitForIdle()
            composeRule.onNodeWithText("Library").assertIsDisplayed()
            composeRule.onNodeWithContentDescription("Search this library").assertIsDisplayed()
            composeRule.onNodeWithContentDescription("Refresh")
                .assertIsDisplayed()
                .assertHasClickAction()
            composeRule.onNodeWithContentDescription("Manage")
                .assertIsDisplayed()
                .assertHasClickAction()
            composeRule.onNodeWithContentDescription("Switch")
                .assertIsDisplayed()
                .assertHasClickAction()
            composeRule.onNodeWithText("All").assertIsDisplayed()
            composeRule.onNodeWithText("HDR").assertIsDisplayed()
        }
    }
}
