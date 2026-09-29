package com.papi.nova.ui

import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.papi.nova.manager.WorkerLaunchContract
import com.papi.nova.shared.polaris.model.PolarisGame
import com.papi.nova.ui.compose.NovaComposeTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class NovaSpaceComposeTest {
    @get:Rule val compose = createComposeRule()
    private val space = PolarisGame(id = WorkerLaunchContract.APP_UUID, name = "Living Room")

    private fun awaitFocus(tag: String) {
        compose.waitUntil(timeoutMillis = 2000) {
            runCatching { compose.onNodeWithTag(tag).assertIsFocused(); true }.getOrDefault(false)
        }
    }

    @Test fun primaryHasFirstFocusAndControllerCanOpenWithoutDetails() {
        var opens = 0
        compose.setContent {
            NovaComposeTheme {
                NovaSpaceContent(space, "Gaming PC", null, { opens++ }, {}, {})
            }
        }
        awaitFocus("nova-space-primary")
        compose.onNodeWithText("Your Space").assertIsDisplayed()
        compose.onNodeWithTag("nova-space-primary").assertIsFocused()
            .performKeyInput { pressKey(Key.DirectionCenter) }
        compose.runOnIdle { assertEquals(1, opens) }
        compose.onNodeWithTag("nova-game-detail-overview").assertDoesNotExist()
    }

    @Test fun settingsReturnRestoresControllerFocusAndDoNotLaunch() {
        val settings = mutableStateOf(false)
        var opens = 0
        compose.setContent {
            NovaComposeTheme {
                NovaSpaceContent(space, "Gaming PC", null, { opens++ }, { settings.value = true },
                    { settings.value = false }, showSettings = settings.value)
            }
        }
        awaitFocus("nova-space-primary")
        compose.onNodeWithTag("nova-space-primary").performKeyInput { pressKey(Key.DirectionRight) }
        awaitFocus("nova-space-settings")
        compose.onNodeWithTag("nova-space-settings").assertIsFocused()
            .performKeyInput { pressKey(Key.DirectionCenter) }
        awaitFocus("nova-space-settings-done")
        compose.onNodeWithTag("nova-space-settings-done").assertIsFocused().performClick()
        awaitFocus("nova-space-settings")
        compose.onNodeWithTag("nova-space-settings").assertIsFocused()
        compose.runOnIdle { assertEquals(0, opens) }
    }

    @Test fun longSpaceNameAndLargeTextKeepPhoneActionsReachable() {
        compose.setContent {
            NovaComposeTheme {
                CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, 1.5f)) {
                    NovaSpaceContent(space.copy(name = "Family Gaming Space With A Longer Name"), "Gaming PC", null, {}, {}, {},
                        modifier = Modifier.requiredSize(360.dp, 390.dp))
                }
            }
        }
        compose.onNodeWithTag("nova-space-primary").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("nova-space-settings").performScrollTo().assertIsDisplayed()
    }

    @Test fun anotherDeviceCannotBeResumedFromTheSpaceAction() {
        val session = NovaLibraryActiveSessionUiState(WorkerLaunchContract.APP_ID, WorkerLaunchContract.APP_UUID,
            space.name, "Another Device", false, 0, false, false, 1920, 1080, 60f)
        compose.setContent {
            NovaComposeTheme { NovaSpaceContent(space, "Gaming PC", session, {}, {}, {}) }
        }
        awaitFocus("nova-space-settings")
        compose.onNodeWithTag("nova-space-primary").assertIsNotEnabled()
        compose.onNodeWithText("Resume Space").assertDoesNotExist()
    }

    @Test fun settingsOffer120FpsWithoutHostTopologyControls() {
        var selected = 0
        val row = NovaPlaySetupRowState(NovaPlaySetupRow.FRAME_RATE, "Frame Rate", "Saved For Next Launch", "60 FPS",
            listOf(NovaPlaySetupOption("120 FPS", "", onSelect = { selected = 120 })))
        compose.setContent {
            NovaComposeTheme {
                NovaSpaceContent(space, "Gaming PC", null, {}, {}, {}, showSettings = true, settingsRows = listOf(row,
                    row.copy(row = NovaPlaySetupRow.WHERE_IT_RUNS, label = "Where It Runs")))
            }
        }
        compose.onNodeWithText("120 FPS").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(120, selected) }
        compose.onNodeWithText("Where It Runs").assertDoesNotExist()
        compose.onNodeWithText("Your Space").assertExists()
    }

    @Test fun hostConstraintKeepsSettingsReachableWithAController() {
        val ready = mutableStateOf(true)
        compose.setContent {
            NovaComposeTheme {
                NovaSpaceContent(space, "Gaming PC", null, {}, {}, {}, primaryEnabled = ready.value,
                    message = if (ready.value) null else "Polaris is offering 60 FPS for this device.")
            }
        }
        awaitFocus("nova-space-primary")
        compose.runOnIdle { ready.value = false }
        awaitFocus("nova-space-settings")
        compose.onNodeWithTag("nova-space-primary").assertIsNotEnabled()
        compose.onNodeWithTag("nova-space-message").assertIsDisplayed()
    }

    @Test fun chooserStartsOnCurrentSpaceAndControllerSelectsWithoutLaunching() {
        val snapshot = com.papi.nova.api.PolarisSpaces(true, true, true, "a", listOf(
            com.papi.nova.api.PolarisSpace("a", "Alex", "ready", true),
            com.papi.nova.api.PolarisSpace("b", "Sam", "in_use", false)))
        var choice = ""
        compose.setContent { NovaComposeTheme { NovaSpaceChooser(snapshot, busy = false, statusKnown = true, error = null, onChoose = { choice = it }, onBack = {}) } }
        awaitFocus("nova-space-choice-a")
        compose.onNodeWithTag("nova-space-choice-a").performKeyInput { pressKey(Key.DirectionDown) }
        awaitFocus("nova-space-choice-b")
        compose.onNodeWithTag("nova-space-choice-b").performKeyInput { pressKey(Key.DirectionCenter) }
        compose.runOnIdle { assertEquals("b", choice) }
        compose.onNodeWithText("Open Space").assertDoesNotExist()
        compose.onNodeWithText("In use").assertIsDisplayed()
    }

    @Test fun activeStreamLocksChoicesAndKeepsBackFocused() {
        val snapshot = com.papi.nova.api.PolarisSpaces(true, true, false, "a", listOf(
            com.papi.nova.api.PolarisSpace("a", "Alex", "stopping", true),
            com.papi.nova.api.PolarisSpace("b", "Sam", "ready", false)))
        compose.setContent { NovaComposeTheme { NovaSpaceChooser(snapshot, busy = false, statusKnown = true, error = null, onChoose = {}, onBack = {}) } }
        awaitFocus("nova-space-chooser-back")
        compose.onNodeWithTag("nova-space-choice-b").assertIsNotEnabled()
        compose.onNodeWithText("Spaces cannot be changed right now.").assertIsDisplayed()
    }

    @Test fun busySpaceKeepsChooserReachableAndCannotOpen() {
        compose.setContent { NovaComposeTheme {
            NovaSpaceContent(space, "Gaming PC", null, {}, {}, {}, spaceState = "in_use", onChoose = {})
        } }
        compose.onNodeWithTag("nova-space-primary").assertIsNotEnabled()
        compose.onNodeWithTag("nova-space-choose").assertIsEnabled()
    }

    @Test fun firstStatusCheckReturnsFocusToOpenWhenReady() {
        val ready = mutableStateOf(false)
        compose.setContent { NovaComposeTheme {
            NovaSpaceContent(space, "Gaming PC", null, {}, {}, {}, primaryEnabled = ready.value)
        } }
        awaitFocus("nova-space-settings")
        compose.runOnIdle { ready.value = true }
        awaitFocus("nova-space-primary")
    }
    @Test fun namedPlayersAndDesktopAreDistinctControllerChoices() {
        val snapshot = com.papi.nova.api.PolarisSpaces(true, true, true, "desktop", listOf(
            com.papi.nova.api.PolarisSpace("alex", "Alex’s Space", "ready", false, true),
            com.papi.nova.api.PolarisSpace("sam", "Sam’s Space", "ready", false, true)), desktopAllowed = true)
        var choice = ""
        compose.setContent { NovaComposeTheme { NovaSpaceChooser(snapshot, busy = false, statusKnown = true, error = null, onChoose = { choice = it }, onBack = {}) } }
        awaitFocus("nova-space-choice-desktop")
        compose.onNodeWithText("Change Space").assertIsDisplayed()
        compose.onNodeWithTag("nova-space-choice-desktop").performKeyInput { pressKey(Key.DirectionDown) }
        awaitFocus("nova-space-choice-alex")
        compose.onNodeWithTag("nova-space-choice-alex").performKeyInput { pressKey(Key.DirectionCenter) }
        compose.runOnIdle { assertEquals("alex", choice) }
        saveScreenshot("choose-player.png")
    }

    @Test fun libraryKeepsPlayerVisibleAndLongNamesKeepChooserReachable() {
        val snapshot = com.papi.nova.api.PolarisSpaces(true, true, true, "alex", listOf(
            com.papi.nova.api.PolarisSpace("alex", "Alex’s Family Gaming Space", "ready", true, true)), desktopAllowed = true)
        var choices = 0
        compose.setContent { NovaComposeTheme {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, 1.3f)) {
                NovaEnvironmentBar(snapshot, true, { choices++ }, modifier = Modifier.requiredSize(560.dp, 70.dp))
            }
        } }
        compose.onNodeWithText("Your Space").assertIsDisplayed()
        compose.onNodeWithTag("nova-environment-choose").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(1, choices) }
        saveScreenshot("your-space.png")
    }

    @Test fun desktopReadsAsTheComputerAndTheWholeRowOpensTheChooser() {
        val snapshot = com.papi.nova.api.PolarisSpaces(true, true, true, "desktop", listOf(
            com.papi.nova.api.PolarisSpace("alex", "Alex", "ready", false, true)), desktopAllowed = true)
        var choices = 0
        compose.setContent { NovaComposeTheme {
            NovaEnvironmentBar(snapshot, true, { choices++ }, modifier = Modifier.requiredSize(560.dp, 70.dp), compact = true)
        } }
        compose.onNodeWithText("This computer").assertIsDisplayed()
        compose.onNodeWithText("Your Space").assertDoesNotExist()
        compose.onNodeWithTag("nova-environment-choose").assertHasClickAction().performClick()
        compose.runOnIdle { assertEquals(1, choices) }
        saveScreenshot("space-control-desktop.png")
    }

    @Test fun oneEnvironmentStillOpensTheChooser() {
        val snapshot = com.papi.nova.api.PolarisSpaces(true, true, true, "alex", listOf(
            com.papi.nova.api.PolarisSpace("alex", "Alex", "ready", true, true)))
        var choices = 0
        compose.setContent { NovaComposeTheme {
            NovaEnvironmentBar(snapshot, true, { choices++ }, modifier = Modifier.requiredSize(560.dp, 70.dp), compact = true)
        } }
        compose.onNodeWithText("Alex").assertIsDisplayed()
        compose.onNodeWithTag("nova-environment-choose").assertHasClickAction().performClick()
        compose.runOnIdle { assertEquals(1, choices) }
    }

    @Test fun chooserWithoutDesktopAccessSaysWhereToTurnItOn() {
        val snapshot = com.papi.nova.api.PolarisSpaces(true, true, true, "alex", listOf(
            com.papi.nova.api.PolarisSpace("alex", "papi - steam", "ready", true, true)))
        var choice = ""
        compose.setContent { NovaComposeTheme {
            NovaSpaceChooser(snapshot, busy = false, statusKnown = true, error = null, onChoose = { choice = it }, onBack = {})
        } }
        compose.onNodeWithText("papi - steam is the only place this device can play right now.").assertIsDisplayed()
        compose.onNodeWithText("Desktop Access is off for this device. Turn it on from the Spaces page in Polaris.", useUnmergedTree = true)
            .assertIsDisplayed()
        compose.onNodeWithTag("nova-space-choice-desktop").performClick()
        compose.runOnIdle { assertEquals("a row without Desktop Access chooses nothing", "", choice) }
        saveScreenshot("choose-no-desktop-access.png")
    }

    private fun saveScreenshot(name: String) {
        val image = compose.onRoot().captureToImage()
        val bitmap = image.asAndroidBitmap()
        val context = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext
        java.io.File(context.getExternalFilesDir(null), name).outputStream().use {
            bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }
    }

}
