package com.papi.nova.binding.input

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

class NovaPlayersTest {
    @Test
    fun aHandheldsOwnPadHoldsPlayerOneBeforeAnyonePresses() {
        // The Odin's pad took controller 0 without holding it, so a paired pad took 0 too
        // and the host saw one player where there were two.
        assertEquals(1.toShort(), NovaPlayers.initialHeldControllers(multiController = true, builtInGamepad = true))
        assertEquals(0.toShort(), NovaPlayers.initialHeldControllers(multiController = true, builtInGamepad = false))
        // With presence detection off every pad is player 1 anyway.
        assertEquals(0.toShort(), NovaPlayers.initialHeldControllers(multiController = false, builtInGamepad = true))
    }

    @Test
    fun onePlayerPerNumberLowestFirst() {
        val players = NovaPlayers.players(
            listOf(
                1 to "8BitDo Ultimate",
                0 to "",
                0 to "Xbox Wireless Controller",
                2 to "DualSense Wireless Controller",
            )
        )
        assertEquals(
            listOf(
                NovaPlayerSlot(0, "Xbox Wireless Controller"),
                NovaPlayerSlot(1, "8BitDo Ultimate"),
                NovaPlayerSlot(2, "DualSense Wireless Controller"),
            ),
            players
        )
    }

    @Test
    fun theCaptionSaysWhoIsWhoAndWhoHasNotJoined() {
        fun caption(players: List<NovaPlayerSlot>, waiting: List<String>, multi: Boolean = true) = NovaPlayers.caption(
            players, waiting, multi,
            playerFormat = "P%1\$d %2\$s",
            unnamedPad = "Controller",
            waitingFormat = "Press a button to join: %1\$s",
            nobodyYet = "Press a button on a controller to join.",
            onePlayer = "Every controller is player 1.",
        )
        assertEquals(
            "P1 Xbox Wireless Controller · P2 Controller · Press a button to join: DualSense",
            caption(listOf(NovaPlayerSlot(0, "Xbox Wireless Controller"), NovaPlayerSlot(1, "")), listOf("DualSense"))
        )
        assertEquals("Press a button on a controller to join.", caption(emptyList(), emptyList()))
        assertEquals("Every controller is player 1.", caption(listOf(NovaPlayerSlot(0, "Pad")), emptyList(), multi = false))
    }

    @Test
    fun theHandlerHoldsPlayerOneAndCanHandItBack() {
        val handler = String(
            Files.readAllBytes(Path.of("src/main/java/com/papi/nova/binding/input/ControllerHandler.kt")),
            StandardCharsets.UTF_8
        )
        assertTrue(
            "the handheld's own pad holds controller 0 from the start, so a paired pad takes 1",
            handler.contains("NovaPlayers.initialHeldControllers(prefConfig.multiController, hasBuiltInGamepad())")
        )
        assertTrue(
            "after Reassign the handheld's pad joins in press order like any other, instead of taking 0 back and merging with whoever pressed first",
            handler.contains("prefConfig.multiController && context.hasJoystickAxes && !builtInHoldsPlayerOne")
        )
        val forget = handler.substring(handler.indexOf("private fun forgetPlayer("), handler.indexOf("private fun builtInSticksContext("))
        assertTrue(
            "a pad that gives up its player stops its rumble and sensors and leaves the number, or the next player's rumble and gyro requests still found it",
            forget.contains("context.disableSensors()") &&
                forget.contains("context.controllerNumber = UNASSIGNED_CONTROLLER_NUMBER") &&
                forget.contains("removeCallbacks(context.batteryStateUpdateRunnable)")
        )
        val menu = String(
            Files.readAllBytes(Path.of("src/main/java/com/papi/nova/ui/NovaQuickMenu.kt")),
            StandardCharsets.UTF_8
        )
        val players = menu.substring(menu.indexOf("NovaQuickMenuActionId.PLAYERS ->"))
        assertTrue(
            "Command Center takes every pad's buttons while open, so Reassign closes it before anyone can join",
            players.indexOf("dismiss()") in 0 until players.indexOf("game.reassignPlayers()")
        )
    }
}
