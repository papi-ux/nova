package com.papi.nova.diagnostics

/** Snapshots as Game freezes them, through the real [NovaMomentReport.capture]. */
internal object NovaWorkingBaselineFixtures {
    const val PC = "8f2d1c3e-pc-entry-den-rig"
    const val APP = "b7a91f02-app-entry-portal"

    fun snapshot(
        markedAtUtc: String = "2026-10-08T13:00:00Z",
        renderedFps: Double = 59.8,
        receivedFps: Double = 60.0,
        decodeTimeMs: Double = 3.2,
        rttMs: Int = 4,
        packetLossPct: Double = 0.5,
        framesExpected: Long = 120_000,
        framesLost: Long = 12,
        codec: String = "c2.qti.hevc.decoder",
        sampleAt: Long = 9_800L,
        now: Long = 10_000L,
        width: Int = 1920,
        height: Int = 1080,
        requestedBitrateKbps: Int = 20_000,
        requestedCodec: String = "FORCE_HEVC",
        watching: Boolean = false,
        version: String = "1.4.15",
        device: String = "Google Pixel 9",
        sample: Boolean = true,
    ): NovaMomentReport.Snapshot = NovaMomentReport.capture(
        markedAtUtc = markedAtUtc, nowElapsedMs = now, novaVersion = version, buildType = "release",
        androidRelease = "15", androidSdk = 35, device = device, watching = watching, space = false,
        requestedWidth = width, requestedHeight = height, requestedFps = 60f, requestedBitrateKbps = requestedBitrateKbps,
        requestedCodec = requestedCodec,
        sample = if (!sample) null else NovaMomentReport.Sample(
            codec, width, height, renderedFps, receivedFps, decodeTimeMs, rttMs, 1, packetLossPct, 2.5,
            framesExpected, framesLost, sampleAt),
    )

    val entry: NovaWorkingBaseline.RequestedEntry get() = NovaWorkingBaseline.RequestedEntry.of(PC, APP)!!

    val key = ByteArray(NovaWorkingBaseline.KEY_BYTES) { (it * 7 + 3).toByte() }
}
