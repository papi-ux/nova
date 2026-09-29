package com.papi.nova.preferences

import org.junit.Assert.*
import org.junit.Test

class NovaStreamTiersTest {
    private fun inputs(panel: NovaSize = NovaSize(1920, 1080), top: Int = 120,
        distance: NovaDistance = NovaDistance.HAND, link: NovaLink = NovaLink.WIFI, fourKfps: Int = 60): NovaTierInputs {
        val points = NovaStreamTiers.candidateSizes(panel).map {
            NovaDecodePoint(it, if (it == NovaStreamTiers.FOUR_K) fourKfps else top)
        }
        return NovaTierInputs(panel, listOf(60, top).distinct(), distance,
            NovaDeviceCapabilities(listOf(NovaCodecCapability(NovaCodecChoice.AVC, "fixture-avc", points),
                NovaCodecCapability(NovaCodecChoice.HEVC, "fixture-hevc", points))), link)
    }

    @Test fun deviceMatrixUsesFixtureCapabilitiesOnly() {
        val rows = javaClass.getResource("/capability/tier-devices.csv")!!.readText().lines()
            .filter { it.isNotBlank() && !it.startsWith("#") }.drop(1)
        assertEquals(9, rows.size)
        rows.forEach { row ->
            val c = row.split(','); fun n(i: Int) = c[i].toInt()
            val tiers = NovaStreamTiers.generate(inputs(NovaSize(n(1), n(2)), n(3),
                NovaDistance.valueOf(c[4]), NovaLink.valueOf(c[5]), n(6)))
            assertEquals(c[0], listOf(n(7), n(8), n(9), n(10)),
                listOf(tiers.recommended.width, tiers.recommended.height, tiers.recommended.fps, tiers.recommended.bitrateKbps))
            assertEquals(c[0], listOf(n(11), n(12), n(13)), listOf(tiers.saver.width, tiers.saver.height, tiers.saver.bitrateKbps))
        }
    }

    @Test fun handheldHasSmoothRecommendedAndSharperMax() {
        val tiers = NovaStreamTiers.generate(inputs())
        assertEquals("1080p · 120 fps · 30 Mbps · HEVC", tiers.recommended.numbers)
        assertEquals("4K · 60 fps · 80 Mbps · HEVC", tiers.max.numbers)
        assertTrue(tiers.max.reasons.any { it.message == "Sharper than this screen" })
        assertEquals(NovaFourK.IsMax, tiers.fourK)
        assertNull(tiers.custom)
    }

    @Test fun ethernetMergesDuplicateMaxButWifiHoldDoesNot() {
        val room = inputs(NovaStreamTiers.FOUR_K, 60, NovaDistance.ROOM, NovaLink.ETHERNET)
        assertTrue(NovaStreamTiers.generate(room).mergedMax)
        val wifi = NovaStreamTiers.generate(room.copy(link = NovaLink.WIFI))
        assertFalse(wifi.mergedMax)
        assertEquals(50000, wifi.recommended.bitrateKbps)
        assertEquals(80000, wifi.max.bitrateKbps)
    }

    @Test fun settingsIgnoreHostWhileGameUsesHostAndSpaceLimits() {
        val input = inputs().copy(host = NovaHostTierLimits(maxFps = 60, bitrateCapKbps = 28000))
        assertEquals(120, NovaStreamTiers.forDevice(input).recommended.fps)
        assertEquals(30000, NovaStreamTiers.forDevice(input).recommended.bitrateKbps)
        assertEquals(60, NovaStreamTiers.generate(input).recommended.fps)
        val space = NovaStreamTiers.generate(input.copy(host = NovaHostTierLimits(space = true)))
        assertEquals(NovaCodecChoice.AVC, space.recommended.codec)
        assertEquals(8000, space.recommended.bitrateKbps)
        assertEquals("space", (space.fourK as NovaFourK.Unavailable).because.code)
    }

    @Test fun pinsRecomputeBitrateAndPreserveAnExplicitBitratePin() {
        val input = inputs()
        val resolution = NovaStreamTiers.resolve(input, NovaTier.RECOMMENDED,
            pins = NovaStreamPins(size = NovaSize(1280, 720)))
        assertEquals(15000, resolution.bitrateKbps)
        assertEquals(120, resolution.fps)
        assertEquals(47000, NovaStreamTiers.resolve(input, NovaTier.RECOMMENDED,
            pins = NovaStreamPins(size = NovaSize(1280, 720), bitrateKbps = 47000)).bitrateKbps)
        assertEquals(NovaCodecChoice.AV1, NovaStreamTiers.resolve(input.copy(capabilities = input.capabilities.copy(
            codecs = input.capabilities.codecs + NovaCodecCapability(NovaCodecChoice.AV1, "fixture-av1", input.capabilities.codecs[0].points))),
            NovaTier.RECOMMENDED, pins = NovaStreamPins(codec = NovaCodecChoice.AV1)).codec)
    }

    @Test fun refusalReasonsFollowDecoderCodecDesktopSpaceOrder() {
        val thirty = NovaStreamTiers.generate(inputs(fourKfps = 30).copy(codec = NovaCodecChoice.AVC,
            host = NovaHostTierLimits(space = true)))
        assertTrue((thirty.fourK as NovaFourK.Unavailable).because.message.contains("30 fps"))
        val codec = NovaStreamTiers.generate(inputs().copy(codec = NovaCodecChoice.AVC,
            host = NovaHostTierLimits(space = true)))
        assertEquals("codec", (codec.fourK as NovaFourK.Unavailable).because.code)
        val mirror = NovaStreamTiers.generate(inputs().copy(host = NovaHostTierLimits(space = true,
            mirroredDesktop = NovaSize(1920, 1080))))
        assertEquals("host_desktop", (mirror.fourK as NovaFourK.Unavailable).because.code)
    }

    @Test fun failedPointStepsDownWithAnHonestReason() {
        val input = inputs().let { it.copy(capabilities = it.capabilities.copy(failed = listOf(
            NovaFailedDecodePoint(NovaCodecChoice.HEVC, NovaSize(1920, 1080), 120),
            NovaFailedDecodePoint(NovaCodecChoice.AVC, NovaSize(1920, 1080), 120)))) }
        val plan = NovaStreamTiers.generate(input).recommended
        assertTrue(plan.fps < 120 || plan.height < 1080)
        assertEquals("decoder_failed", plan.limits.first().code)
    }

    @Test fun claimedDecoderNeverBecomesCoveredAndAv1IsOptIn() {
        val input = inputs()
        val claimed = input.capabilities.codecs.map { it.copy(points = it.points.map { p -> p.copy(covered = false) }) }
        assertFalse(NovaStreamTiers.generate(input.copy(capabilities = NovaDeviceCapabilities(claimed))).recommended.available)
        assertEquals(NovaCodecChoice.HEVC, NovaStreamTiers.generate(input).recommended.codec)
        assertTrue(NovaStreamTiers.customDelta(NovaStreamPlan(3840, 2160, 60, NovaCodecChoice.AV1, 250000),
            NovaStreamTiers.generate(input).recommended).length <= 56)
    }
}
