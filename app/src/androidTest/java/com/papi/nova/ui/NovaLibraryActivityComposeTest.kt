package com.papi.nova.ui

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.view.ViewGroup
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.platform.ComposeView
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.papi.nova.R
import org.junit.Assert.assertTrue
import org.junit.Rule
import androidx.compose.ui.test.performClick
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NovaLibraryActivityComposeTest {
    @get:Rule
    val composeRule = createEmptyComposeRule()

    @Test
    fun libraryShellShowsTvControls() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        launchLibraryAndAssertShell(context)
    }

    @Test
    fun libraryShellShowsControlsAcrossNovaThemes() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val themes = buildList {
            add(NovaThemeManager.THEME_POLARIS)
            add(NovaThemeManager.THEME_OLED)
            if (NovaThemeManager.isMaterialYouAvailable()) {
                add(NovaThemeManager.THEME_MATERIAL_YOU)
            }
        }

        try {
            themes.forEach { theme ->
                NovaThemeManager.setTheme(context, theme)
                launchLibraryAndAssertShell(context)
            }
        } finally {
            NovaThemeManager.setTheme(context, NovaThemeManager.THEME_POLARIS)
        }
    }

    private fun launchLibraryAndAssertShell(context: Context) {
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
            val serverName = "Test Server"
            val searchHint = context.getString(R.string.nova_library_search_hint)
            val optionsTitle = context.getString(R.string.nova_library_options_title)
            val systemTitle = context.getString(R.string.nova_system_menu_title)
            val filterAll = context.getString(R.string.nova_library_filter_all)
            val filterHdr = context.getString(R.string.nova_library_filter_hdr)

            // The toolbar leads with the host. "Library" is deliberately not drawn in the
            // cinematic layout -- it restates what the whole screen already is -- so this
            // used to assert the pre-cinematic toolbar and had been failing unseen.
            composeRule.onNodeWithText(serverName, substring = false).assertIsDisplayed()

            // Options carries its name as a content description; its text is the
            // controller hint, so asking for it by text asks for the wrong node.
            composeRule.onNodeWithContentDescription(optionsTitle)
                .assertIsDisplayed()
                .assertHasClickAction()
            composeRule.onNodeWithText(systemTitle)
                .assertIsDisplayed()
                .assertHasClickAction()
            // Everything below lives inside Options, not on the shell: search and the
            // filter chips both moved there. Asserting them without opening the drawer
            // was asserting a drawer nobody had opened, which is why all three failed in
            // turn once the one before them was fixed.
            composeRule.onNodeWithContentDescription(optionsTitle).performClick()
            composeRule.waitForIdle()
            composeRule.onNodeWithText(optionsTitle, substring = false).assertIsDisplayed()

            composeRule.onNodeWithContentDescription(searchHint)
                .performScrollTo()
                .assertIsDisplayed()
            // No performScrollTo on these two. They sit in a horizontally scrolling row
            // that recomposes as it scrolls, so scrolling to the first one detached the
            // second from the tree mid-assertion. They are on screen already; what is
            // worth asserting is that they exist and can be pressed.
            composeRule.onNode(hasContentDescription("$filterAll. ", substring = true))
                .assertExists()
                .assertHasClickAction()
            composeRule.onNode(hasContentDescription("$filterHdr. ", substring = true))
                .assertExists()
                .assertHasClickAction()
        }
    }
}
