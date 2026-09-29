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
        assertEquals(10, rows.size)
        rows.forEach { row ->
            val c = row.split(','); fun n(i: Int) = c[i].toInt()
            var input=inputs(NovaSize(n(1),n(2)),n(3),NovaDistance.valueOf(c[4]),NovaLink.valueOf(c[5]),n(6))
            if(c[20]=="avc") input=input.copy(capabilities=input.capabilities.copy(codecs=input.capabilities.codecs.filter { it.codec==NovaCodecChoice.AVC }))
            val tiers = NovaStreamTiers.generate(input)
            assertEquals(c[0], listOf(n(7), n(8), n(9), n(10)),
                listOf(tiers.recommended.width, tiers.recommended.height, tiers.recommended.fps, tiers.recommended.bitrateKbps))
            assertEquals(c[0], listOf(n(11), n(12), n(13)), listOf(tiers.saver.width, tiers.saver.height, tiers.saver.bitrateKbps))
            assertEquals(c[0],listOf(n(14),n(15),n(16),n(17)),listOf(tiers.max.width,tiers.max.height,tiers.max.fps,tiers.max.bitrateKbps))
            assertEquals(c[0],c[18],when(tiers.fourK) {
                NovaFourK.IsRecommended -> "recommended";NovaFourK.IsMax -> "max";is NovaFourK.Unavailable -> "unavailable"
            })
            assertTrue(c[0]+" reason "+c[19],(tiers.recommended.reasons+tiers.recommended.limits).any { it.code==c[19] })
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

    @Test fun shieldWifiKeepsFourKMaxSeparateWhileRecommendedIsHeld() {
        // Papi's 21:10 decision: the Wi-Fi hold must never collapse the 4K Max choice.
        val wifi=NovaStreamTiers.forDevice(inputs(NovaStreamTiers.FOUR_K,60,NovaDistance.ROOM,NovaLink.WIFI))
        assertFalse(wifi.mergedMax)
        assertEquals(listOf(NovaTier.SAVER,NovaTier.RECOMMENDED,NovaTier.MAX),wifi.availableTiers)
        assertEquals(NovaStreamTiers.FOUR_K,wifi.max.size)
        assertEquals(60,wifi.max.fps)
        assertEquals(50000,wifi.recommended.bitrateKbps)
        assertTrue(wifi.recommended.limits.any { it.code=="wifi_hold" })
        assertEquals(80000,wifi.max.bitrateKbps)
    }
    @Test fun shieldEthernetUsesNormalDuplicateMerging() {
        val ethernet=NovaStreamTiers.forDevice(inputs(NovaStreamTiers.FOUR_K,60,NovaDistance.ROOM,NovaLink.ETHERNET))
        assertTrue(ethernet.mergedMax)
        assertEquals(listOf(NovaTier.SAVER,NovaTier.RECOMMENDED),ethernet.availableTiers)
        assertEquals(NovaStreamTiers.FOUR_K,ethernet.recommended.size)
        assertEquals(80000,ethernet.recommended.bitrateKbps)
        assertFalse(ethernet.recommended.limits.any { it.code=="wifi_hold" })
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
        val unmeasured=NovaStreamTiers.generate(input.copy(capabilities = NovaDeviceCapabilities(claimed))).recommended
        assertTrue(unmeasured.available)
        assertTrue(unmeasured.reasons.any { it.code=="decoder_claimed" })
        assertFalse(NovaDeviceCapabilities(claimed).covered(NovaCodecChoice.HEVC,NovaSize(1920,1080),120))
        assertEquals(NovaCodecChoice.HEVC, NovaStreamTiers.generate(input).recommended.codec)
        assertTrue(NovaStreamTiers.customDelta(NovaStreamPlan(3840, 2160, 60, NovaCodecChoice.AV1, 250000),
            NovaStreamTiers.generate(input).recommended).length <= 56)
    }

    @Test fun fourKMetadataMatchesFiftyHzAndIgnoresUnchosenAv1() {
        val fifty=inputs(NovaStreamTiers.FOUR_K,50,NovaDistance.ROOM,NovaLink.ETHERNET).copy(refreshRates=listOf(50))
        val result=NovaStreamTiers.forDevice(fifty)
        assertEquals(NovaStreamTiers.FOUR_K,result.recommended.size)
        assertEquals(50,result.recommended.fps);assertEquals(NovaFourK.IsRecommended,result.fourK)
        val av1=inputs().copy(capabilities=NovaDeviceCapabilities(listOf(
            NovaCodecCapability(NovaCodecChoice.AVC,"avc",listOf(NovaDecodePoint(NovaSize(1920,1080),120))),
            NovaCodecCapability(NovaCodecChoice.AV1,"av1",listOf(NovaDecodePoint(NovaStreamTiers.FOUR_K,120))))))
        val auto=NovaStreamTiers.forDevice(av1)
        assertEquals(NovaCodecChoice.AVC,auto.recommended.codec)
        assertEquals("4K at 60 fps needs AV1 · choose AV1",(auto.fourK as NovaFourK.Unavailable).because.message)
        assertTrue(auto.max.height<=1080)
    }
    @Test fun mirrorAndPyrowaveUseTheirOwnLimits() {
        val mirror=NovaStreamTiers.generate(inputs().copy(host=NovaHostTierLimits(mirroredDesktop=NovaSize(2560,1440))))
        assertEquals(NovaSize(2560,1440),mirror.max.size)
        assertEquals("host_desktop",(mirror.fourK as NovaFourK.Unavailable).because.code)
        val pyro=inputs().copy(codec=NovaCodecChoice.PYROWAVE,capabilities=NovaDeviceCapabilities(emptyList()),
            pyrowave=NovaPyrowaveSupport(available=true))
        assertTrue(NovaStreamTiers.generate(pyro).recommended.available)
        assertEquals(NovaCodecChoice.PYROWAVE,NovaStreamTiers.generate(pyro).max.codec)
        // The calibrated 31 dB handheld figure now fits 4K60 below 200 Mbps.
        val fits=NovaStreamTiers.generate(pyro.copy(host=NovaHostTierLimits(pyrowaveFourKCapKbps=200000)))
        assertEquals(NovaFourK.IsMax,fits.fourK)
        val capped=NovaStreamTiers.generate(pyro.copy(host=NovaHostTierLimits(pyrowaveFourKCapKbps=150000)))
        assertEquals("pyrowave_cap",(capped.fourK as NovaFourK.Unavailable).because.code)
        assertTrue(capped.max.height<2160)
        assertFalse(NovaStreamTiers.generate(pyro.copy(pyrowave=NovaPyrowaveSupport())).recommended.available)
    }
    @Test fun reasonsDescribeOnlyRulesThatChangedEachRung() {
        val hand=NovaStreamTiers.forDevice(inputs(NovaSize(3200,1440)))
        assertEquals("hand_cap",hand.recommended.reasons.first().code)
        assertFalse(hand.max.reasons.any { it.code=="native_panel" })
        val rp6=NovaStreamTiers.forDevice(inputs())
        assertEquals("Fills this 1080p screen at its full 120 Hz",rp6.recommended.reasons.first().message)
        assertFalse(rp6.max.reasons.any { it.code=="native_panel" })
        val failed=inputs().let { it.copy(capabilities=it.capabilities.copy(failed=listOf(
            NovaFailedDecodePoint(NovaCodecChoice.HEVC,NovaSize(1920,1080),120)))) }
        assertFalse(NovaStreamTiers.forDevice(failed).max.limits.any { it.code=="decoder_failed" })
        assertTrue(NovaStreamTiers.resolve(inputs(),NovaTier.MAX,pins=NovaStreamPins(fps=60)).reasons.any { it.code=="above_native" })
    }
    @Test fun customDeltaNamesTheRecommendedBaselineAndIgnoresAutoNegotiation() {
        val rec=NovaStreamTiers.forDevice(inputs()).recommended
        assertEquals("Custom · same as Recommended",NovaStreamTiers.customDelta(rec.copy(codec=NovaCodecChoice.AUTO),rec))
        assertEquals("Custom · 37 Mbps (Recommended 30)",NovaStreamTiers.customDelta(rec.copy(bitrateKbps=37000),rec))
        assertEquals("Custom · 1440p, 60 fps +1 (Recommended 1080p, 120 fps)",
            NovaStreamTiers.customDelta(rec.copy(width=2560,height=1440,fps=60,bitrateKbps=40000),rec))
    }
}
