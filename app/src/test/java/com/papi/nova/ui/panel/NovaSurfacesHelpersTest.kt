package com.papi.nova.ui.panel

import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.ComponentDialog
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import com.papi.nova.nvstream.http.ComputerDetails
import com.papi.nova.utils.Dialog
import com.papi.nova.utils.SpinnerDialog
import com.papi.nova.utils.UiHelper
import java.time.Duration
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog

/**
 * The legacy helpers (Dialog, SpinnerDialog and UiHelper's confirms) post to NovaSurfaces with
 * their signatures unchanged. These tests read the state layer: the state pages and the panel's
 * pages each helper puts up, and what their actions do.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class NovaSurfacesHelpersTest {
    private val controllers = mutableListOf<ActivityController<ComponentActivity>>()
    private var yes = 0
    private var no = 0
    private var reconnects = 0

    private fun newActivity(): ComponentActivity =
        Robolectric.buildActivity(ComponentActivity::class.java).setup().also { controllers += it }.get()

    @After
    fun destroyActivities() {
        controllers.forEach { it.pause().stop().destroy() }
        idle()
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(50))

    private fun ComponentActivity.states() = NovaSurfaces.of(this).states.value

    private fun ComponentActivity.topPage() = NovaSurfaces.of(this).panel.top

    @Test
    fun anEndingDialogPostsAProblemWithTheActionPrimaryAndCloseAsB() {
        val activity = newActivity()
        Dialog.displayDialog(
            activity, "Connection lost", "The host stopped answering.", true, "Reconnect", Runnable { reconnects++ },
        )

        val page = activity.states().single() as NovaStatePage.Problem
        assertEquals(NovaStateOwner.LegacyDialog, page.owner)
        assertEquals("Reconnect", page.primary.label)
        assertEquals(listOf("Close"), page.secondary.map { it.label })
        assertEquals("B is Close, the least destructive way out", "Close", page.back.label)

        page.primary.run()
        assertEquals(1, reconnects)
        assertFalse("the action skips the dismiss handling, so the screen is not finished under it", activity.isFinishing)
        assertTrue(activity.states().isEmpty())
    }

    @Test
    fun closingAnEndingDialogFinishesTheScreen() {
        val activity = newActivity()
        Dialog.displayDialog(activity, "PC not found", "Nova could not find that PC.", true)

        val page = activity.states().single() as NovaStatePage.Problem
        assertEquals("with no action, Close is the primary", "Close", page.primary.label)
        assertTrue(page.secondary.isEmpty())

        page.back.run()
        assertTrue(activity.isFinishing)
        assertTrue(activity.states().isEmpty())
    }

    @Test
    fun aDialogThatDoesNotEndTheScreenPresentsANotice() {
        val activity = newActivity()
        var dismissed = 0
        Dialog.displayDialog(activity, "Details", "name: Nova PC", Runnable { dismissed++ })

        val notice = activity.topPage() as NovaCommonPage.Notice
        assertEquals("Details", notice.title)
        assertEquals("name: Nova PC", notice.message)
        assertEquals("Close", notice.closeLabel)
        assertNull(notice.primary)
        assertTrue("a notice is not a state page", activity.states().isEmpty())

        notice.onClose()
        assertEquals(1, dismissed)
    }

    @Test
    fun aNoticeLeftByTheScrimOrStartStillRunsItsDismissHandling() {
        val activity = newActivity()
        var acknowledged = 0
        // The decoder crash notice acknowledges the crash here; skipping it brings the notice back.
        Dialog.displayDialog(activity, "Decoder error", "The decoder stopped.", Runnable { acknowledged++ })

        NovaSurfaces.of(activity).panel.close()

        assertEquals(1, acknowledged)
    }

    @Test
    fun closeDialogsTakesANoticeDownWithoutItsDismissHandlingAsBefore() {
        val activity = newActivity()
        var dismissed = 0
        Dialog.displayDialog(activity, "Details", "name: Nova PC", Runnable { dismissed++ })

        Dialog.closeDialogs()
        idle()

        assertFalse(NovaSurfaces.of(activity).panel.isOpen)
        assertEquals(0, dismissed)
    }

    @Test
    fun aQuitConfirmLeftAnyOtherWayRunsOnNo() {
        val activity = newActivity()
        // ShortcutTrampoline finishes itself in onNo; skipping it left a translucent screen alive.
        UiHelper.displayQuitConfirmationDialog(activity, Runnable { yes++ }, Runnable { no++ })

        NovaSurfaces.of(activity).panel.close()

        assertEquals(1, no)
        assertEquals(0, yes)
    }

    @Test
    fun aSuspendingConfirmClosedByTheScrimRunsStayAndAnswersFalse() {
        val activity = newActivity()
        val surfaces = NovaSurfaces.of(activity)
        var stays = 0
        val answer = CompletableDeferred<Boolean>()
        val scope = CoroutineScope(Dispatchers.Main)
        scope.launch {
            answer.complete(
                surfaces.confirm(
                    NovaCommonPage.Confirm(
                        key = "end",
                        title = "End this Nova session?",
                        message = AnnotatedString("The game closes on the host."),
                        stayLabel = "Stay",
                        actionLabel = "End Session",
                        destructive = true,
                        onConfirm = {},
                        onStay = { stays++ },
                    ),
                ),
            )
        }
        idle()

        surfaces.panel.close()
        idle()

        assertTrue(answer.isCompleted)
        assertFalse(answer.getCompleted())
        assertEquals(1, stays)
        scope.cancel()
    }

    @Test
    fun aNoticeKeepsItsActionAsThePrimary() {
        val activity = newActivity()
        Dialog.displayDialog(
            activity, "Nova closed unexpectedly", "It crashed at 09:14.", false, "Send report", Runnable { yes++ },
        )

        val notice = activity.topPage() as NovaCommonPage.Notice
        assertEquals("Send report", notice.primary?.label)
        notice.primary?.run?.invoke()
        assertEquals(1, yes)
    }

    @Test
    fun helpAppearsOnlyWhenACallerAsksForIt() {
        val activity = newActivity()
        Dialog.displayDialog(activity, "Lost", "Gone.", true, "Reconnect", Runnable { })
        Dialog.displayDialog(activity, "Lost again", "Gone.", true, "Reconnect", Runnable { }, true)
        val (plain, helped) = activity.states().map { it as NovaStatePage.Problem }
        assertNull("Help used to be on every message; now only where asked", plain.help)
        assertEquals("Help", helped.help?.label)

        Dialog.displayDialog(activity, "Add PC failed", "No answer.", false, null, null)
        assertNull((activity.topPage() as NovaCommonPage.Notice).help)
        Dialog.displayDialog(activity, "Add PC failed", "No answer.", false, null, null, true)
        assertEquals("Help", (activity.topPage() as NovaCommonPage.Notice).help?.label)
    }

    @Test
    fun closeDialogsClearsTheMessagesOfEveryActivityButNotTheirSpinners() {
        val first = newActivity()
        val second = newActivity()
        Dialog.displayDialog(first, "Connection lost", "Gone.", true)
        Dialog.displayDialog(second, "Pairing", "Type 4721 on the host.", false)
        SpinnerDialog.displayDialog(second, "Pairing", "Waiting for the host", false)

        Dialog.closeDialogs()
        idle()

        assertTrue(first.states().isEmpty())
        assertFalse("the presented notice is gone and the panel with it", NovaSurfaces.of(second).panel.isOpen)
        assertEquals(listOf(NovaStateOwner.LegacySpinner), second.states().map { it.owner })
    }

    @Test
    fun aSpinnerPostsUpdatesAndDismisses() {
        val activity = newActivity()
        val spinner = SpinnerDialog.displayDialog(activity, "Adding PC", "Looking for the host", false)

        val page = activity.states().single() as NovaStatePage.Busy
        assertEquals(NovaStateOwner.LegacySpinner, page.owner)
        assertEquals("Adding PC", page.title)
        assertNull("without finish the page cannot be left", page.cancel)

        spinner.setMessage("Found it")
        assertEquals("Found it", page.message.value)

        spinner.dismiss()
        assertTrue(activity.states().isEmpty())
    }

    @Test
    fun aFinishingSpinnerCancelsByFinishingTheScreen() {
        val activity = newActivity()
        SpinnerDialog.displayDialog(activity, "Refreshing apps", "", true)

        val cancel = (activity.states().single() as NovaStatePage.Busy).cancel
        assertEquals("Cancel", cancel?.label)
        cancel?.run?.invoke()
        assertTrue(activity.isFinishing)
        assertTrue(activity.states().isEmpty())
    }

    @Test
    fun spinnerCloseDialogsClearsOnlyThatActivity() {
        val first = newActivity()
        val second = newActivity()
        SpinnerDialog.displayDialog(first, "Connecting", "", false)
        SpinnerDialog.displayDialog(second, "Connecting", "", false)

        SpinnerDialog.closeDialogs(first)

        assertTrue(first.states().isEmpty())
        assertEquals(1, second.states().size)
    }

    @Test
    fun theQuitConfirmationIsDestructiveStartsSafeAndBRunsOnNo() {
        val activity = newActivity()
        UiHelper.displayQuitConfirmationDialog(activity, Runnable { yes++ }, Runnable { no++ })

        val confirm = activity.topPage() as NovaCommonPage.Confirm
        assertTrue(confirm.destructive)
        assertEquals("End this Nova session?", confirm.title)
        assertEquals("Stay", confirm.stayLabel)
        assertEquals("End Session", confirm.actionLabel)
        assertEquals("The game closes on the host, and anything it has not saved is lost.", confirm.message.text)

        idle()
        val window = ShadowDialog.getLatestDialog() as ComponentDialog
        assertTrue(window.isShowing)
        window.onBackPressedDispatcher.onBackPressed()
        idle()

        assertEquals(1, no)
        assertEquals(0, yes)
        assertFalse(NovaSurfaces.of(activity).panel.isOpen)
    }

    @Test
    fun theVirtualDisplayPromptIsNotDestructiveAndKeepsItsLink() {
        val activity = newActivity()
        val computer = ComputerDetails().apply { vDisplaySupported = false }
        UiHelper.displayVdisplayConfirmationDialog(activity, computer, Runnable { yes++ }, Runnable { no++ })

        val confirm = activity.topPage() as NovaCommonPage.Confirm
        assertFalse(confirm.destructive)
        assertEquals("Cancel", confirm.stayLabel)
        assertEquals("Proceed", confirm.actionLabel)
        val link = confirm.message.getLinkAnnotations(0, confirm.message.length).singleOrNull()?.item
        assertNotNull("the Polaris link still works for touch", link)
        assertEquals("https://github.com/papi-ux/polaris", (link as LinkAnnotation.Url).url)

        confirm.onConfirm()
        confirm.onStay()
        assertEquals(1, yes)
        assertEquals(1, no)
    }

    @Test
    fun deletingAPcIsDestructiveWithKeepAsStay() {
        val activity = newActivity()
        val computer = ComputerDetails().apply { name = "Living Room" }
        UiHelper.displayDeletePcConfirmationDialog(activity, computer, Runnable { yes++ }, Runnable { no++ })

        val confirm = activity.topPage() as NovaCommonPage.Confirm
        assertTrue(confirm.destructive)
        assertEquals("Living Room", confirm.title)
        assertEquals("Keep", confirm.stayLabel)
        assertEquals("Delete PC", confirm.actionLabel)
    }
}
