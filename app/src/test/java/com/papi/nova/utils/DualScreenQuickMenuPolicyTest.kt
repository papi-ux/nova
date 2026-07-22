package com.papi.nova.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DualScreenQuickMenuPolicyTest {
    private val streamDisplayId = 0
    private val companionDisplayId = 4

    @Test
    fun followInteractionRoutesStreamOriginToStream() {
        assertEquals(
            DualScreenQuickMenuPolicy.Surface.STREAM,
            DualScreenQuickMenuPolicy.resolve(
                policy = DualScreenQuickMenuPolicy.FOLLOW_INTERACTION,
                originDisplayId = streamDisplayId,
                lastInteractionDisplayId = companionDisplayId,
                streamDisplayId = streamDisplayId,
                companionDisplayId = companionDisplayId,
            )
        )
    }

    @Test
    fun followInteractionRoutesCompanionOriginToCompanion() {
        assertEquals(
            DualScreenQuickMenuPolicy.Surface.COMPANION,
            DualScreenQuickMenuPolicy.resolve(
                policy = DualScreenQuickMenuPolicy.FOLLOW_INTERACTION,
                originDisplayId = companionDisplayId,
                lastInteractionDisplayId = streamDisplayId,
                streamDisplayId = streamDisplayId,
                companionDisplayId = companionDisplayId,
            )
        )
    }

    @Test
    fun followInteractionUsesLastInteractionWhenControllerHasNoDisplayOrigin() {
        assertEquals(
            DualScreenQuickMenuPolicy.Surface.COMPANION,
            DualScreenQuickMenuPolicy.resolve(
                policy = DualScreenQuickMenuPolicy.FOLLOW_INTERACTION,
                originDisplayId = null,
                lastInteractionDisplayId = companionDisplayId,
                streamDisplayId = streamDisplayId,
                companionDisplayId = companionDisplayId,
            )
        )
    }

    @Test
    fun followInteractionFallsBackToStreamWithoutKnownInteraction() {
        assertEquals(
            DualScreenQuickMenuPolicy.Surface.STREAM,
            DualScreenQuickMenuPolicy.resolve(
                policy = DualScreenQuickMenuPolicy.FOLLOW_INTERACTION,
                originDisplayId = null,
                lastInteractionDisplayId = null,
                streamDisplayId = streamDisplayId,
                companionDisplayId = companionDisplayId,
            )
        )
    }

    @Test
    fun streamPolicyAlwaysRoutesToStream() {
        assertEquals(
            DualScreenQuickMenuPolicy.Surface.STREAM,
            DualScreenQuickMenuPolicy.resolve(
                policy = DualScreenQuickMenuPolicy.STREAM,
                originDisplayId = companionDisplayId,
                lastInteractionDisplayId = companionDisplayId,
                streamDisplayId = streamDisplayId,
                companionDisplayId = companionDisplayId,
            )
        )
    }

    @Test
    fun companionPolicyRoutesToCompanionWhenAvailable() {
        assertEquals(
            DualScreenQuickMenuPolicy.Surface.COMPANION,
            DualScreenQuickMenuPolicy.resolve(
                policy = DualScreenQuickMenuPolicy.COMPANION,
                originDisplayId = streamDisplayId,
                lastInteractionDisplayId = streamDisplayId,
                streamDisplayId = streamDisplayId,
                companionDisplayId = companionDisplayId,
            )
        )
    }

    @Test
    fun companionPolicyFallsBackToStreamWhenCompanionIsUnavailable() {
        assertEquals(
            DualScreenQuickMenuPolicy.Surface.STREAM,
            DualScreenQuickMenuPolicy.resolve(
                policy = DualScreenQuickMenuPolicy.COMPANION,
                originDisplayId = companionDisplayId,
                lastInteractionDisplayId = companionDisplayId,
                streamDisplayId = streamDisplayId,
                companionDisplayId = null,
            )
        )
    }

    @Test
    fun unknownPolicyUsesFollowInteractionBehavior() {
        val result = DualScreenQuickMenuPolicy.resolve(
            policy = "future-value",
            originDisplayId = companionDisplayId,
            lastInteractionDisplayId = streamDisplayId,
            streamDisplayId = streamDisplayId,
            companionDisplayId = companionDisplayId,
        )
        assertEquals(DualScreenQuickMenuPolicy.Surface.COMPANION, result)
    }

    @Test
    fun backDismissesAnOpenQuickMenuBeforeOpeningAnotherOwner() {
        assertEquals(
            DualScreenQuickMenuPolicy.BackAction.DISMISS,
            DualScreenQuickMenuPolicy.backAction(backMenuEnabled = true, quickMenuOpen = true),
        )
    }

    @Test
    fun backOpensQuickMenuWhenEnabledAndClosed() {
        assertEquals(
            DualScreenQuickMenuPolicy.BackAction.SHOW,
            DualScreenQuickMenuPolicy.backAction(backMenuEnabled = true, quickMenuOpen = false),
        )
    }

    @Test
    fun backPassesThroughWhenQuickMenuShortcutIsDisabled() {
        assertEquals(
            DualScreenQuickMenuPolicy.BackAction.PASS_THROUGH,
            DualScreenQuickMenuPolicy.backAction(backMenuEnabled = false, quickMenuOpen = false),
        )
    }

    @Test
    fun escapedBackUsesCompanionInteractionAfterGameTakesWindowFocus() {
        assertEquals(
            companionDisplayId,
            DualScreenQuickMenuPolicy.escapedBackOrigin(
                companionDisplayId = companionDisplayId,
                lastInteractionDisplayId = companionDisplayId,
                companionHasWindowFocus = false,
            ),
        )
    }

    @Test
    fun escapedBackUsesCurrentCompanionFocusWhenLastInteractionIsStream() {
        assertEquals(
            companionDisplayId,
            DualScreenQuickMenuPolicy.escapedBackOrigin(
                companionDisplayId = companionDisplayId,
                lastInteractionDisplayId = streamDisplayId,
                companionHasWindowFocus = true,
            ),
        )
    }

    @Test
    fun escapedBackKeepsStreamInteractionWithoutCompanionFocus() {
        assertEquals(
            null,
            DualScreenQuickMenuPolicy.escapedBackOrigin(
                companionDisplayId = companionDisplayId,
                lastInteractionDisplayId = streamDisplayId,
                companionHasWindowFocus = false,
            ),
        )
    }

    @Test
    fun escapedBackFallsBackToCompanionFocusWithoutRecordedInteraction() {
        assertEquals(
            companionDisplayId,
            DualScreenQuickMenuPolicy.escapedBackOrigin(
                companionDisplayId = companionDisplayId,
                lastInteractionDisplayId = null,
                companionHasWindowFocus = true,
            ),
        )
    }

    @Test
    fun syntheticLegacyBackKeepsCompanionDisplayOrigin() {
        assertEquals(
            companionDisplayId,
            DualScreenQuickMenuPolicy.legacyCompanionBackOrigin(
                companionDisplayId = companionDisplayId,
                companionHasWindowFocus = true,
                inputDeviceId = -1,
                isMouseInput = false,
            ),
        )
    }

    @Test
    fun syntheticLegacyBackUsesRecordedCompanionInteractionWithoutFocus() {
        assertEquals(
            companionDisplayId,
            DualScreenQuickMenuPolicy.legacyCompanionBackOrigin(
                companionDisplayId = companionDisplayId,
                lastInteractionDisplayId = companionDisplayId,
                companionHasWindowFocus = false,
                inputDeviceId = -1,
                isMouseInput = false,
            ),
        )
    }

    @Test
    fun syntheticLegacyBackUsesCurrentCompanionFocusWhenInteractionIsStream() {
        assertEquals(
            companionDisplayId,
            DualScreenQuickMenuPolicy.legacyCompanionBackOrigin(
                companionDisplayId = companionDisplayId,
                lastInteractionDisplayId = streamDisplayId,
                companionHasWindowFocus = true,
                inputDeviceId = -1,
                isMouseInput = false,
            ),
        )
    }

    @Test
    fun syntheticLegacyBackKeepsStreamInteractionWithoutCompanionFocus() {
        assertEquals(
            null,
            DualScreenQuickMenuPolicy.legacyCompanionBackOrigin(
                companionDisplayId = companionDisplayId,
                lastInteractionDisplayId = streamDisplayId,
                companionHasWindowFocus = false,
                inputDeviceId = -1,
                isMouseInput = false,
            ),
        )
    }

    @Test
    fun physicalInputDeviceBackKeepsExistingControllerPath() {
        assertEquals(
            null,
            DualScreenQuickMenuPolicy.legacyCompanionBackOrigin(
                companionDisplayId = companionDisplayId,
                companionHasWindowFocus = true,
                inputDeviceId = 7,
                isMouseInput = false,
            ),
        )
    }

    @Test
    fun mouseGeneratedBackKeepsExistingRightClickPath() {
        assertEquals(
            null,
            DualScreenQuickMenuPolicy.legacyCompanionBackOrigin(
                companionDisplayId = companionDisplayId,
                companionHasWindowFocus = true,
                inputDeviceId = -1,
                isMouseInput = true,
            ),
        )
    }

    @Test
    fun ignoredSyntheticBackKeepsExistingInputPreference() {
        assertEquals(
            null,
            DualScreenQuickMenuPolicy.legacyCompanionBackOrigin(
                companionDisplayId = companionDisplayId,
                companionHasWindowFocus = true,
                inputDeviceId = -1,
                isMouseInput = false,
                ignoreSyntheticEvents = true,
            ),
        )
    }

    @Test
    fun backAsMetaKeepsBalancedHostKeyPath() {
        assertEquals(
            null,
            DualScreenQuickMenuPolicy.legacyCompanionBackOrigin(
                companionDisplayId = companionDisplayId,
                companionHasWindowFocus = true,
                inputDeviceId = -1,
                isMouseInput = false,
                sendMetaOnBack = true,
            ),
        )
    }

    @Test
    fun unfocusedCompanionKeepsExistingActivityPath() {
        assertEquals(
            null,
            DualScreenQuickMenuPolicy.legacyCompanionBackOrigin(
                companionDisplayId = companionDisplayId,
                companionHasWindowFocus = false,
                inputDeviceId = -1,
                isMouseInput = false,
            ),
        )
    }

    @Test
    fun legacyBackHasNoCompanionOriginAfterPresentationTeardown() {
        assertEquals(
            null,
            DualScreenQuickMenuPolicy.legacyCompanionBackOrigin(
                companionDisplayId = null,
                companionHasWindowFocus = true,
                inputDeviceId = -1,
                isMouseInput = false,
            ),
        )
    }

    @Test
    fun currentPresentationCanUpdateCompanionFocus() {
        assertTrue(
            DualScreenQuickMenuPolicy.acceptsCompanionFocus(
                currentCompanionDisplayId = companionDisplayId,
                focusDisplayId = companionDisplayId,
                isCurrentPresentation = true,
            ),
        )
    }

    @Test
    fun stalePresentationCannotOverrideReplacementFocus() {
        assertFalse(
            DualScreenQuickMenuPolicy.acceptsCompanionFocus(
                currentCompanionDisplayId = companionDisplayId,
                focusDisplayId = companionDisplayId,
                isCurrentPresentation = false,
            ),
        )
    }

    @Test
    fun companionOpenFailureFallsBackToStream() {
        var streamOpenCount = 0
        var companionOpenCount = 0

        val actualSurface = DualScreenQuickMenuPolicy.openWithFallback(
            requestedSurface = DualScreenQuickMenuPolicy.Surface.COMPANION,
            showStream = { streamOpenCount++ },
            showCompanion = {
                companionOpenCount++
                false
            },
        )

        assertEquals(DualScreenQuickMenuPolicy.Surface.STREAM, actualSurface)
        assertEquals(1, companionOpenCount)
        assertEquals(1, streamOpenCount)
    }

    @Test
    fun availableCompanionOpensWithoutTouchingStreamMenu() {
        var streamOpenCount = 0
        var companionOpenCount = 0

        val actualSurface = DualScreenQuickMenuPolicy.openWithFallback(
            requestedSurface = DualScreenQuickMenuPolicy.Surface.COMPANION,
            showStream = { streamOpenCount++ },
            showCompanion = {
                companionOpenCount++
                true
            },
        )

        assertEquals(DualScreenQuickMenuPolicy.Surface.COMPANION, actualSurface)
        assertEquals(1, companionOpenCount)
        assertEquals(0, streamOpenCount)
    }

    @Test
    fun streamRequestDoesNotProbeCompanionMenu() {
        var streamOpenCount = 0
        var companionOpenCount = 0

        val actualSurface = DualScreenQuickMenuPolicy.openWithFallback(
            requestedSurface = DualScreenQuickMenuPolicy.Surface.STREAM,
            showStream = { streamOpenCount++ },
            showCompanion = {
                companionOpenCount++
                true
            },
        )

        assertEquals(DualScreenQuickMenuPolicy.Surface.STREAM, actualSurface)
        assertEquals(0, companionOpenCount)
        assertEquals(1, streamOpenCount)
    }

    @Test
    fun unexpectedCompanionDismissMigratesAnOpenMenuToAnAvailableStream() {
        assertEquals(
            true,
            DualScreenQuickMenuPolicy.shouldMigrateCompanionMenu(
                menuWasOpen = true,
                dismissalRequestedByNova = false,
                streamAvailable = true,
            ),
        )
    }

    @Test
    fun intentionalCompanionDismissDoesNotReopenTheMenu() {
        assertEquals(
            false,
            DualScreenQuickMenuPolicy.shouldMigrateCompanionMenu(
                menuWasOpen = true,
                dismissalRequestedByNova = true,
                streamAvailable = true,
            ),
        )
    }

    @Test
    fun companionDismissDoesNotMigrateWithoutAnOpenMenuOrStreamOwner() {
        assertEquals(
            false,
            DualScreenQuickMenuPolicy.shouldMigrateCompanionMenu(
                menuWasOpen = false,
                dismissalRequestedByNova = false,
                streamAvailable = true,
            ),
        )
        assertEquals(
            false,
            DualScreenQuickMenuPolicy.shouldMigrateCompanionMenu(
                menuWasOpen = true,
                dismissalRequestedByNova = false,
                streamAvailable = false,
            ),
        )
    }
}
