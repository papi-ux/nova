package com.papi.nova.ui

import com.papi.nova.R
import com.papi.nova.api.PolarisSpace
import com.papi.nova.api.PolarisSpaces
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NovaSpacesCopyTest {
    private fun snapshot(
        enabled: Boolean = true,
        available: Boolean = true,
        canSwitch: Boolean = true,
        unavailableReason: String? = null,
        switchBlockedReason: String? = null,
        spaces: List<PolarisSpace> = listOf(PolarisSpace("a", "Alex", "ready", true)),
        desktopAllowed: Boolean = false,
    ) = PolarisSpaces(
        enabled, available, canSwitch,
        selectedId = if (available) spaces.firstOrNull { it.selected }?.id ?: "desktop" else "",
        spaces = spaces, desktopAllowed = desktopAllowed,
        unavailableReason = unavailableReason, switchBlockedReason = switchBlockedReason,
    )

    @Test
    fun reasonsComeFromTheHostAndUnknownOnesGetOneHonestSentence() {
        assertNull(NovaSpacesCopy.unavailableReason(snapshot()))
        assertEquals(
            R.string.nova_space_unavailable_controller_missing,
            NovaSpacesCopy.unavailableReason(snapshot(available = false, canSwitch = false, unavailableReason = "controller_missing", spaces = emptyList())),
        )
        assertEquals(
            "a reason this client has never heard of must not become a crash or a blank",
            R.string.nova_space_unavailable_generic,
            NovaSpacesCopy.unavailableReason(snapshot(available = false, canSwitch = false, unavailableReason = "something_new", spaces = emptyList())),
        )
        assertNull(NovaSpacesCopy.switchBlockedReason(snapshot()))
        assertEquals(R.string.nova_space_switch_blocked_your_stream, NovaSpacesCopy.switchBlockedReason(snapshot(canSwitch = false, switchBlockedReason = "your_stream")))
        assertEquals(R.string.nova_space_switch_blocked_generic, NovaSpacesCopy.switchBlockedReason(snapshot(canSwitch = false)))
        assertEquals(
            "an unavailable host explains the switch with its own reason",
            R.string.nova_space_unavailable_stopping,
            NovaSpacesCopy.switchBlockedReason(snapshot(available = false, canSwitch = false, unavailableReason = "stopping", spaces = emptyList())),
        )
    }

    @Test
    fun aSpaceIsOpenableWhenTheHostSaysSoOrItAlreadyRunsForUs() {
        assertNull(NovaSpacesCopy.openBlockedReason(PolarisSpace("a", "Alex", "ready", true)))
        assertNull(NovaSpacesCopy.openBlockedReason(PolarisSpace("a", "Alex", "running", true, canOpen = false, blockedReason = "running")))
        assertEquals(R.string.nova_space_blocked_at_capacity, NovaSpacesCopy.openBlockedReason(PolarisSpace("a", "Alex", "ready", true, canOpen = false, blockedReason = "at_capacity")))
        assertEquals(R.string.nova_space_blocked_starting, NovaSpacesCopy.openBlockedReason(PolarisSpace("a", "Alex", "starting", true)))
        assertEquals(R.string.nova_space_in_use, NovaSpacesCopy.openBlockedReason(PolarisSpace("a", "Alex", "in_use", false)))
        assertEquals(R.string.nova_space_play_blocked_at_capacity, NovaSpacesCopy.playBlockedLabel("ready", "at_capacity"))
        assertEquals(R.string.nova_space_play_blocked_in_use, NovaSpacesCopy.playBlockedLabel("in_use", null))
    }

    @Test
    fun aSpaceRowSaysWhichLauncherItOpens() {
        assertEquals("Steam", NovaSpacesCopy.launcherName("steam"))
        assertEquals("Heroic", NovaSpacesCopy.launcherName("heroic"))
        assertEquals("Lutris", NovaSpacesCopy.launcherName("lutris"))
        assertNull("a word this build does not know is shown as nothing, not as a raw token", NovaSpacesCopy.launcherName("brand_new"))
        assertNull(NovaSpacesCopy.launcherName(null))

        assertEquals("Heroic \u00B7 Current Space", NovaSpacesCopy.chooserCaption("heroic", "Current Space"))
        assertEquals("Lutris", NovaSpacesCopy.chooserCaption("lutris", null))
        assertEquals("an older host still gets its note", "Current Space", NovaSpacesCopy.chooserCaption(null, "Current Space"))
        assertEquals("", NovaSpacesCopy.chooserCaption(null, null))
    }

    @Test
    fun oneWordPerWireState() {
        assertEquals(R.string.nova_space_state_ready, NovaSpacesCopy.stateLabel("ready"))
        assertEquals(R.string.nova_space_state_starting, NovaSpacesCopy.stateLabel("starting"))
        assertEquals(R.string.nova_space_state_running, NovaSpacesCopy.stateLabel("running"))
        assertEquals(R.string.nova_space_state_stopping, NovaSpacesCopy.stateLabel("stopping"))
        assertEquals(R.string.nova_space_state_in_use, NovaSpacesCopy.stateLabel("in_use"))
        assertEquals(R.string.nova_space_state_unavailable, NovaSpacesCopy.stateLabel("unavailable"))
        assertEquals(R.string.nova_space_state_unknown, NovaSpacesCopy.stateLabel(null))
        assertEquals(R.string.nova_space_state_unknown, NovaSpacesCopy.stateLabel("at_capacity"))
    }

    @Test
    fun anEmptyLibraryIsExplainedBySpacesOnlyWhenSpacesAreTheReason() {
        assertNull(NovaSpacesCopy.emptyLibraryCase(snapshot()))
        assertNull(NovaSpacesCopy.emptyLibraryCase(snapshot(enabled = false, available = false, canSwitch = false, spaces = emptyList())))
        assertEquals(
            NovaSpacesCopy.EmptyLibraryCase.NO_SPACE_ASSIGNED,
            NovaSpacesCopy.emptyLibraryCase(snapshot(available = false, canSwitch = false, unavailableReason = "no_space_assigned", spaces = emptyList())),
        )
        assertEquals(
            "a host from before the reason fields lists nothing and allows no Desktop",
            NovaSpacesCopy.EmptyLibraryCase.NO_SPACE_ASSIGNED,
            NovaSpacesCopy.emptyLibraryCase(snapshot(available = false, canSwitch = false, spaces = emptyList())),
        )
        assertNull("a Desktop-only device has an ordinary library", NovaSpacesCopy.emptyLibraryCase(snapshot(spaces = emptyList(), desktopAllowed = true)))
        assertEquals(
            NovaSpacesCopy.EmptyLibraryCase.HOST_UNAVAILABLE,
            NovaSpacesCopy.emptyLibraryCase(snapshot(available = false, canSwitch = false, unavailableReason = "admin_failed")),
        )
    }

    @Test
    fun theSpaceControlNamesDesktopAsTheComputerAndNeverAsASpace() {
        val desktop = NovaSpacesCopy.environmentLabel(
            snapshot(spaces = listOf(PolarisSpace("a", "papi - steam", "ready", false)), desktopAllowed = true),
        )
        assertEquals(R.string.nova_space_bar_desktop, desktop.caption)
        assertEquals(R.string.nova_space_desktop, desktop.nameRes)
        assertNull(desktop.spaceName)
        assertNull(desktop.status)
        assertTrue("one Space and Desktop are two places to play", desktop.offersChoice)

        val space = NovaSpacesCopy.environmentLabel(
            snapshot(spaces = listOf(PolarisSpace("a", "papi - steam", "starting", true)), desktopAllowed = true),
        )
        assertEquals(R.string.nova_space_bar_your_space, space.caption)
        assertNull(space.nameRes)
        assertEquals("papi - steam", space.spaceName)
        assertEquals(R.string.nova_space_state_starting, space.status)

        assertEquals(
            "a failed check shows no stale state word",
            R.string.nova_space_state_unknown,
            NovaSpacesCopy.environmentLabel(snapshot(), statusKnown = false).status,
        )
        assertFalse(
            "one Space without Desktop leaves the chooser nothing else to offer; the control still opens it to say why",
            NovaSpacesCopy.environmentLabel(snapshot()).offersChoice,
        )
    }

    @Test
    fun theChooserAlwaysListsDesktopAndSaysWhyItIsOff() {
        val off = NovaSpacesCopy.desktopChoice(snapshot())
        assertFalse("without Desktop Access the row cannot be chosen", off.enabled)
        assertEquals("and says where to turn it on", R.string.nova_space_desktop_access_off, off.caption)

        val allowed = NovaSpacesCopy.desktopChoice(snapshot(desktopAllowed = true))
        assertTrue(allowed.enabled)
        assertEquals(R.string.nova_space_desktop_caption, allowed.caption)

        val current = NovaSpacesCopy.desktopChoice(
            snapshot(spaces = listOf(PolarisSpace("a", "Alex", "ready", false)), desktopAllowed = true),
        )
        assertEquals(R.string.nova_space_current, current.caption)

        assertFalse(
            "a stream on this device still blocks the change",
            NovaSpacesCopy.desktopChoice(snapshot(canSwitch = false, desktopAllowed = true)).enabled,
        )

        assertEquals("one Space and no Desktop is the only place", "Alex", NovaSpacesCopy.onlyPlace(snapshot())?.name)
        assertNull("Desktop is another place", NovaSpacesCopy.onlyPlace(snapshot(desktopAllowed = true)))
        assertNull(
            "two Spaces are two places",
            NovaSpacesCopy.onlyPlace(
                snapshot(spaces = listOf(PolarisSpace("a", "Alex", "ready", true), PolarisSpace("b", "Sam", "ready", false))),
            ),
        )
    }

    @Test
    fun theSpaceControlSaysAChangeIsUnderwayAndAnUnavailableHostInItsOwnWords() {
        val changing = NovaSpacesCopy.environmentLabel(snapshot(desktopAllowed = true), changing = true)
        assertEquals(R.string.nova_space_changing, changing.caption)
        assertEquals("the current name stays until the host answers", "Alex", changing.spaceName)
        assertNull(changing.status)

        val unavailable = NovaSpacesCopy.environmentLabel(
            snapshot(available = false, canSwitch = false, unavailableReason = "admin_failed", desktopAllowed = true),
        )
        assertEquals(R.string.nova_space_bar_spaces, unavailable.caption)
        assertEquals(R.string.nova_space_bar_unavailable, unavailable.nameRes)
        assertNull("an unavailable host is never read as Desktop", unavailable.spaceName)

        val none = NovaSpacesCopy.environmentLabel(
            PolarisSpaces(true, true, true, "", listOf(PolarisSpace("a", "Alex", "ready", false))),
        )
        assertEquals(R.string.nova_space_bar_your_space, none.caption)
        assertEquals(R.string.nova_space_bar_none, none.nameRes)
    }
}
