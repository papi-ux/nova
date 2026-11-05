package com.papi.nova.binding.video.sourcetime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * The production path between dequeue and release: the shared mapper with its default policy, the
 * presenter the renderer delegates to (target delay included) and its scheduler, against a display
 * shaped like Choreographer's frame timelines. Nothing here adds an offset of its own: a target is
 * whatever the presenter makes of the mapper's point, and the only display facts are the ones the
 * renderer gets (when the callback ran, which refreshes are still reachable, the period).
 *
 * The display's earliest reachable refresh is 25 ms after its vsync (W26 F2's condition), so a
 * frame decoded 3 ms after it was enqueued is already far behind that refresh by its mapped target.
 */
class SourceTimePresenterTest {
    /** A fixed-refresh display seen through frame timelines: a callback every period, [latencyNs]
     * after the vsync, offering [timelines] refreshes from [leadNs] after that vsync. */
    private class Display(val periodNs: Long, val leadNs: Long, val latencyNs: Long = 300_000L, val timelines: Int = 3) {
        fun nowAt(k: Long) = k * periodNs + latencyNs
        fun slotsAt(k: Long) = LongArray(timelines) { k * periodNs + leadNs + it * periodNs }

        /** Each timeline must be queued a millisecond before the vsync that starts its frame. */
        fun deadlinesAt(k: Long) = LongArray(timelines) { (k + 1 + it) * periodNs - 1_000_000L }
    }

    private class Frame(val sourceUs: Long, val arrivalUs: Long)

    private class Shown(
        /** Frame index to the refresh it was released for. */
        val slots: Map<Int, Long>,
        val counters: SourceTimeScheduler.Counters,
        val presenter: SourceTimePresenter,
    )

    private val hz120 = Display(8_333_333L, 25_000_000L)
    private val hz60 = Display(16_666_667L, 33_333_333L)

    private fun record(i: Int, f: Frame, point: SourceTimeMapper.Point?, surface: Int = 1) = SourceTimeSideMap.Record(
        frameNumber = i + 1,
        rtpTimestamp = (900_000 + f.sourceUs * 90 / 1000) and 0xFFFF_FFFFL,
        receiveTimeMs = f.arrivalUs / 1000,
        enqueueTimeMs = f.arrivalUs / 1000,
        submitNs = f.arrivalUs * 1000,
        hostLatencyTenths = 0,
        connectionGeneration = 1,
        decoderGeneration = 1,
        point = point,
        surfaceGeneration = surface,
    )

    /**
     * Runs the stream in time order: each frame is observed when it is enqueued (the renderer's
     * submit thread), admitted when it is decoded [decodeUs] later (its renderer thread), and every
     * vsync asks for a release (its vsync thread). [sourceTimed] false is the arrival-only control:
     * the same presenter, given no timing record, aims every frame at its decode time.
     */
    private fun present(
        frames: List<Frame>,
        display: Display,
        capacity: Int = 2,
        sourceTimed: Boolean = true,
        decodeUs: Long = 3_000,
    ): Shown {
        val mapper = SourceTimeMapper(SourceTimePolicy.DEFAULT, capacity, 11_111)
        val presenter = SourceTimePresenter(capacity, mapper)
        val points = arrayOfNulls<SourceTimeMapper.Point>(frames.size)
        val shown = HashMap<Int, Long>()
        var observed = 0
        var admitted = 0
        var k = 0L
        val endNs = (frames.last().arrivalUs + 200_000) * 1000
        while (display.nowAt(k) < endNs) {
            val now = display.nowAt(k)
            while (true) {
                val enqueueNs = if (observed < frames.size) frames[observed].arrivalUs * 1000 else Long.MAX_VALUE
                val decodeNs = if (admitted < observed) (frames[admitted].arrivalUs + decodeUs) * 1000 else Long.MAX_VALUE
                if (minOf(enqueueNs, decodeNs) > now) break
                if (enqueueNs <= decodeNs) {
                    val f = frames[observed]
                    points[observed] = mapper.observe(observed + 1, record(observed, f, null).rtpTimestamp, f.arrivalUs)
                    observed++
                } else {
                    val f = frames[admitted]
                    val point = if (sourceTimed) points[admitted] else null
                    presenter.admit(admitted, admitted.toLong(), decodeNs, record(admitted, f, point), 1, 1)
                    admitted++
                }
            }
            val decision = presenter.onVsync(now, display.slotsAt(k), display.deadlinesAt(k), display.periodNs, 1, 1)
            decision.release?.let { shown[it.index] = decision.releaseAtNs }
            k++
        }
        return Shown(shown, presenter.counters(), presenter)
    }

    private fun stream(count: Int, sourceIntervalUs: (Int) -> Long, arrivalJitterUs: (Int) -> Long): List<Frame> {
        var source = 0L
        return (0 until count).map { i ->
            if (i > 0) source += sourceIntervalUs(i)
            Frame(source, 1_000_000 + source + 15_000 + arrivalJitterUs(i))
        }
    }

    private fun intervals(shown: Shown, from: Int): List<Long> {
        val indices = shown.slots.keys.filter { it >= from }.sorted()
        return indices.zipWithNext { a, b -> (shown.slots[b]!! - shown.slots[a]!!) }
    }

    /**
     * How far the shown refreshes stray from the source times quantized to the display, after
     * warm-up: every shown refresh minus its frame's source time lies in one band. A band narrower
     * than a refresh is a fixed-phase quantization of the source cadence (design G.2: "presented
     * intervals match the quantized source intervals"); a frame shown one refresh off its source
     * place widens it to a refresh or more. Only meaningful when no two source frames fall inside
     * one refresh: those cannot both have their nearest refresh, whatever paces them.
     */
    private fun sourceBandNs(shown: Shown, frames: List<Frame>, from: Int): Long {
        val residues = shown.slots.filterKeys { it >= from }.map { (i, slot) -> slot - frames[i].sourceUs * 1000 }
        return residues.max() - residues.min()
    }

    private fun jitter(count: Int, maxUs: Long): List<Long> {
        var seed = 7L
        return (0 until count).map {
            seed = (seed * 1103515245 + 12345) and 0x7FFF_FFFF
            // Every eighth frame on time, so both deliveries see the same lowest offset.
            if (it % 8 == 0) 0L else seed % maxUs
        }
    }

    @Test
    fun withTheDisplaysLeadAheadOfEveryMappedTargetFramesStillLandOnTheirSourcePlaces() {
        // 60 FPS rendered unevenly (12 ms, 12 ms, 26 ms, ...) but delivered evenly every 16.7 ms:
        // up to 9.3 ms between source and arrival, more than a 120 Hz refresh and inside the
        // reserve's 12 ms. Every source interval is longer than a refresh, so each frame has a
        // refresh of its own.
        val even = stream(360, { 16_667 }, { 0 })
        val frames = stream(360, { if (it % 3 == 0) 26_000 else 12_000 }, { 0 }).mapIndexed { i, f -> Frame(f.sourceUs, even[i].arrivalUs) }
        val shown = present(frames, hz120)
        val band = sourceBandNs(shown, frames, 40)
        assertTrue("shown on the source grid (band ${band / 1000} us)", band < hz120.periodNs)
        val arrival = present(frames, hz120, sourceTimed = false)
        assertTrue("arrival order is not the source grid", sourceBandNs(arrival, frames, 40) >= hz120.periodNs)
        // The delay that makes that possible is measured and reported, inside its bounds.
        val delayUs = shown.presenter.targetDelayUs!!
        assertTrue("delay $delayUs us", delayUs in 25_000L..SourceTimeTargetDelay.MAXIMUM_US)
    }

    @Test
    fun differentSourceCadencesArrivingAlikeAreShownDifferentlyAndTheArrivalControlCannot() {
        // Two hosts deliver identically every 11.111 ms; one rendered evenly, one unevenly.
        val evenFrames = stream(360, { 11_111 }, { 0 })
        val uneven = stream(360, { if (it % 2 == 1) 5_000 else 17_222 }, { 0 })
            .mapIndexed { i, f -> Frame(f.sourceUs, evenFrames[i].arrivalUs) }
        assertNotEquals(intervals(present(evenFrames, hz120), 40), intervals(present(uneven, hz120), 40))
        assertEquals(
            intervals(present(evenFrames, hz120, sourceTimed = false), 40),
            intervals(present(uneven, hz120, sourceTimed = false), 40),
        )
    }

    @Test
    fun theSameSourceCadenceIsShownOnItsGridWhateverItsDelivery() {
        // 90 FPS on 120 Hz, delivered calmly or with up to 4 ms of jitter.
        val j = jitter(360, 4_000)
        val calm = stream(360, { 11_111 }, { 0 })
        val rough = stream(360, { 11_111 }, { j[it] })
        assertTrue(sourceBandNs(present(calm, hz120), calm, 40) < hz120.periodNs)
        assertTrue(sourceBandNs(present(rough, hz120), rough, 40) < hz120.periodNs)
        assertTrue("jitter leaks into arrival pacing",
            sourceBandNs(present(rough, hz120, sourceTimed = false), rough, 40) >= hz120.periodNs)
    }

    @Test
    fun theDelayCostsNoFramesWithRoomForOneOrTwo() {
        // 60 FPS on 60 Hz with jitter: every frame after warm-up is shown, none pushed out or late,
        // with the latency profile's single slot as much as with the balanced profile's two.
        val j = jitter(600, 3_000)
        val frames = stream(600, { 16_667 }, { j[it] })
        for (capacity in listOf(1, 2)) {
            val shown = present(frames, hz60, capacity = capacity)
            val missing = (40 until 600).filter { it !in shown.slots }
            assertEquals("capacity $capacity: frames not shown $missing", emptyList<Int>(), missing)
            assertEquals("capacity $capacity: one refresh per frame", shown.slots.size, shown.slots.values.toSet().size)
            assertTrue(sourceBandNs(shown, frames, 40) < hz60.periodNs)
        }
    }

    @Test
    fun everyArrivalPhaseKeepsEveryFrameWithOneSlotOrTwo() {
        // Where in its refresh a frame is decoded decides how long it waits for the next vsync. With
        // one slot there is no room to wait a whole refresh behind a queued frame, so the delay adds
        // only half of one (addendum 2); with two it adds a whole one. Either way, at 60 FPS on
        // 60 Hz, every frame after warm-up is shown at every phase.
        for (capacity in listOf(1, 2)) {
            for (phaseUs in 0L until 16_667L step 2_083L) {
                val frames = stream(400, { 16_667 }, { phaseUs })
                val shown = present(frames, hz60, capacity = capacity)
                val missing = (40 until 400).filter { it !in shown.slots }
                assertEquals("capacity $capacity, phase $phaseUs us: not shown $missing", 0, missing.size)
                assertEquals(0L, shown.counters.overflow - present(frames.take(40), hz60, capacity = capacity).counters.overflow)
            }
        }
    }

    @Test
    fun aBurstAfterAStallIsNotCaughtUpInOneRefresh() {
        // Ten frames held for 60 ms arrive within a millisecond; at most one frame per refresh, the
        // stale ones superseded rather than flashed.
        val frames = stream(120, { 11_111 }, { 0 }).mapIndexed { i, f ->
            if (i in 50..59) Frame(f.sourceUs, 1_000_000 + 15_000 + 59 * 11_111L + (i - 50) * 100L) else f
        }
        val shown = present(frames, hz120)
        assertEquals("one frame per refresh", shown.slots.size, shown.slots.values.toSet().size)
        assertTrue("some of the burst was superseded", (50..58).any { it !in shown.slots })
    }

    @Test
    fun aFrameWithoutUsableSourceTimeFallsBackToArrivalWithItsReason() {
        val mapper = SourceTimeMapper(SourceTimePolicy.DEFAULT, 2, 16_666)
        val presenter = SourceTimePresenter(2, mapper)
        val missing = presenter.admit(1, 1, 1_000_000, null, 1, 1)
        assertEquals(SourceTimeCounters.Admission.TIMING_METADATA_MISSING, missing.kind)
        assertFalse(missing.outOfBounds)
        val status = presenter.pacing(SourceTimeRequest.SOURCE_TIME, 1_000_000)
        assertEquals(SourceTimeEffective.PENDING, status.effective)
        assertEquals(SourceTimeReason.TIMING_METADATA_MISSING, status.fallback)
    }

    // --- W26 F3: a mapping reset or a new surface invalidates timing made under the old one. ---

    /** A warm presenter: a calm 60 FPS stream admitted and presented until source-paced. */
    private class Warm(val mapper: SourceTimeMapper, val presenter: SourceTimePresenter, var t: Long, var frame: Int)

    private fun warm(display: Display = hz60): Warm {
        val mapper = SourceTimeMapper(SourceTimePolicy.DEFAULT, 2, 16_667)
        val presenter = SourceTimePresenter(2, mapper)
        val w = Warm(mapper, presenter, 1_000_000_000L, 0)
        repeat(30) { stepFrame(w, display) }
        assertEquals(SourceTimeEffective.SOURCE_TIME_FIXED, presenter.pacing(SourceTimeRequest.AUTO, w.t).effective)
        return w
    }

    /** One frame enqueued, decoded 3 ms later and one vsync, all at 60 Hz. */
    private fun stepFrame(w: Warm, display: Display, surface: Int = 1): SourceTimePresenter.Vsync {
        val i = w.frame++
        val f = Frame(i * 16_667L, w.t / 1000)
        val point = w.mapper.observe(i + 1, record(i, f, null).rtpTimestamp, f.arrivalUs)
        w.presenter.admit(i, i.toLong(), w.t + 3_000_000, record(i, f, point, surface), surface, 1)
        val k = (w.t + 4_000_000) / display.periodNs + 1
        w.t += 16_667_000
        return w.presenter.onVsync(display.nowAt(k), display.slotsAt(k), display.deadlinesAt(k), display.periodNs, surface, 1)
    }

    @Test
    fun aMappingResetBetweenEnqueueAndDecodeNeverLetsTheOldTargetRestoreSourcePacing() {
        // W26 F3's case: points A and B are made, the display changes or the app comes to the
        // foreground (the renderer resets the mapping), and only then does A come out of the codec.
        val w = warm()
        val a = Frame(w.frame * 16_667L, w.t / 1000)
        val pointA = w.mapper.observe(w.frame + 1, record(w.frame, a, null).rtpTimestamp, a.arrivalUs)
        assertTrue(pointA.active)
        w.mapper.reset()
        w.presenter.onMappingReset(SourceTimePresenter.reasonForReset(SourceTimeCounters.RESET_FOREGROUND))
        assertEquals("the reset alone stops the claim", SourceTimeEffective.PENDING,
            w.presenter.pacing(SourceTimeRequest.AUTO, w.t).effective)
        assertFalse("the mapper says the point is not current", w.mapper.ready(pointA, a.arrivalUs + 3_000))
        val admission = w.presenter.admit(w.frame, w.frame.toLong(), w.t + 3_000_000, record(w.frame, a, pointA), 1, 1)
        assertEquals(SourceTimeCounters.Admission.STALE_EPOCH, admission.kind)
        val status = w.presenter.pacing(SourceTimeRequest.AUTO, w.t)
        assertEquals(SourceTimeEffective.PENDING, status.effective)
        assertEquals(SourceTimeReason.STALE_EPOCH, status.fallback)
        assertEquals(3000L, w.mapper.reserveUs)
    }

    @Test
    fun framesQueuedUnderAnOldEpochAreDroppedNotReleasedAndHoldNothingBack() {
        val w = warm()
        // Two frames queued with targets well ahead (a 150 ms jump the early bound still admits).
        for (n in 0 until 2) {
            val i = w.frame++
            val f = Frame(i * 16_667L + 150_000, w.t / 1000)
            val point = w.mapper.observe(i + 1, record(i, f, null).rtpTimestamp, f.arrivalUs)
            assertTrue(w.presenter.admit(i, i.toLong(), w.t + 3_000_000, record(i, f, point), 1, 1).dropped.isEmpty())
        }
        val queued = (w.frame - 2 until w.frame).toList()
        // The mapper re-warms on its own (or the renderer resets it): a new epoch.
        w.mapper.reset()
        // A frame with no source time arrives next. Under the old clamp it would wait behind the old
        // targets; now it is due at its decode time.
        val decodedNs = w.t + 3_000_000
        val fallback = w.presenter.admit(99, 99, decodedNs, null, 1, 1)
        assertEquals("the old frames are handed back to drop", queued, fallback.dropped.map { it.index })
        val k = decodedNs / hz60.periodNs + 1
        val vsync = w.presenter.onVsync(hz60.nowAt(k), hz60.slotsAt(k), hz60.deadlinesAt(k), hz60.periodNs, 1, 1)
        assertEquals(99, vsync.release!!.index)
        assertTrue("pending feedback belongs to the old epoch", vsync.fenceMoved)
        assertEquals(2L, w.presenter.counters().staleDropped)
        assertFalse("and only once", w.presenter.onVsync(hz60.nowAt(k + 1), hz60.slotsAt(k + 1), hz60.deadlinesAt(k + 1), hz60.periodNs, 1, 1).fenceMoved)
    }

    @Test
    fun aNewSurfaceDropsWhatWasQueuedForTheOldOneAndRelearnsTheDelay() {
        val w = warm()
        // A frame decoded and queued for its refresh on surface 1.
        val i = w.frame++
        val f = Frame(i * 16_667L, w.t / 1000)
        val point = w.mapper.observe(i + 1, record(i, f, null).rtpTimestamp, f.arrivalUs)
        w.presenter.admit(i, i.toLong(), w.t + 3_000_000, record(i, f, point), 1, 1)
        w.t += 16_667_000
        assertTrue(w.presenter.targetDelayUs != null)
        // The display's timing changes: surface generation 2, without a mapper reset yet.
        val k = (w.t + 4_000_000) / hz60.periodNs + 1
        val vsync = w.presenter.onVsync(hz60.nowAt(k), hz60.slotsAt(k), hz60.deadlinesAt(k), hz60.periodNs, 2, 1)
        assertEquals(listOf(i), vsync.drop.map { it.index })
        assertTrue(vsync.fenceMoved)
        assertEquals(null, w.presenter.targetDelayUs)
        assertEquals(SourceTimeReason.SURFACE_RECREATED, w.presenter.pacing(SourceTimeRequest.AUTO, w.t).fallback)
        // A frame mapped for the old surface but decoded after the change is aimed by arrival.
        val j = w.frame++
        val g = Frame(j * 16_667L, w.t / 1000)
        val pointG = w.mapper.observe(j + 1, record(j, g, null).rtpTimestamp, g.arrivalUs)
        val admission = w.presenter.admit(j, j.toLong(), w.t + 3_000_000, record(j, g, pointG, surface = 1), 2, 1)
        assertEquals(SourceTimeCounters.Admission.STALE_EPOCH, admission.kind)
        assertEquals(SourceTimeEffective.PENDING, w.presenter.pacing(SourceTimeRequest.AUTO, w.t).effective)
        // Frames mapped for the new surface source-pace again.
        repeat(5) { stepFrame(w, hz60, surface = 2) }
        assertEquals(SourceTimeEffective.SOURCE_TIME_FIXED, w.presenter.pacing(SourceTimeRequest.AUTO, w.t).effective)
    }

    // --- W34 D2: an epoch the submission thread starts at any moment, against admission and release. ---

    /**
     * The submission thread observes the host's next frame after its clock jumped five seconds: the
     * mapper starts a new epoch on its own, with only its lock, and tells nobody.
     */
    private fun newEpochOnTheSubmissionThread(w: Warm) {
        val before = w.mapper.epoch
        val i = w.frame++
        val f = Frame(i * 16_667L + 5_000_000L, w.t / 1000)
        val point = w.mapper.observe(i + 1, record(i, f, null).rtpTimestamp, f.arrivalUs)
        assertEquals(SourceTimeMapper.REASON_SOURCE_DISCONTINUITY, point.reason)
        assertEquals(before + 1, w.mapper.epoch)
    }

    /** Vsyncs from refresh [k] until one releases frame [index]; null if none does in [count]. */
    private fun releaseOf(w: Warm, index: Int, k: Long, count: Int = 6): SourceTimeScheduler.Entry? {
        for (n in 0 until count) {
            val at = k + n
            val vsync = w.presenter.onVsync(hz60.nowAt(at), hz60.slotsAt(at), hz60.deadlinesAt(at), hz60.periodNs, 1, 1)
            if (vsync.release?.index == index) return vsync.release
        }
        return null
    }

    /** Frames and vsyncs until a vsync hands out a release, as the renderer would then submit. */
    private fun nextRelease(w: Warm): SourceTimeScheduler.Entry {
        repeat(10) { stepFrame(w, hz60).release?.let { return it } }
        throw AssertionError("no release in ten refreshes")
    }

    @Test
    fun anEpochStartedBetweenReadyAndTheCommitNeitherClaimsSourcePacingNorQueuesTheOldTarget() {
        // W34 D2, interleaving 1: admission reads epoch E and ready(A) says A is current; the
        // submission thread then starts E+1; only then would admission commit A's claim and target.
        val w = warm()
        val i = w.frame++
        val a = Frame(i * 16_667L, w.t / 1000)
        val pointA = w.mapper.observe(i + 1, record(i, a, null).rtpTimestamp, a.arrivalUs)
        assertTrue(pointA.active)
        w.presenter.beforeCommit = { newEpochOnTheSubmissionThread(w) }
        val admission = w.presenter.admit(i, i.toLong(), w.t + 3_000_000, record(i, a, pointA), 1, 1)
        w.presenter.beforeCommit = null
        assertEquals(pointA.epoch + 1, w.mapper.epoch)
        assertEquals("aimed under an epoch that ended before its commit", SourceTimeCounters.Admission.STALE_EPOCH, admission.kind)
        val status = w.presenter.pacing(SourceTimeRequest.AUTO, w.t)
        assertEquals(SourceTimeEffective.PENDING, status.effective)
        assertEquals(SourceTimeReason.STALE_EPOCH, status.fallback)
        // A is queued by arrival under the new epoch and shown, not held at E's target nor dropped
        // as stale at the next vsync.
        val shown = releaseOf(w, i, (w.t + 4_000_000) / hz60.periodNs + 1)
        assertTrue("A is shown", shown != null)
        assertFalse("by arrival", shown!!.sourceTimed)
        assertEquals(w.mapper.epoch, shown.fence.epoch)
    }

    @Test
    fun theClaimEndsTheMomentTheMapperStartsANewEpochOnItsOwn() {
        // W34 D2: an automatic epoch change is visible at the next status read, with no admission or
        // vsync in between to move the presenter's fence.
        val w = warm()
        newEpochOnTheSubmissionThread(w)
        val status = w.presenter.pacing(SourceTimeRequest.AUTO, w.t)
        assertEquals(SourceTimeEffective.PENDING, status.effective)
        assertEquals(SourceTimeReason.STALE_EPOCH, status.fallback)
    }

    @Test
    fun aReleaseChosenBeforeANewEpochNeverReachesTheCodec() {
        // W34 D2, interleaving 2: vsync chooses frame R under epoch E and returns; the submission
        // thread starts E+1; the renderer then hands R to the codec.
        val w = warm()
        var codec = 0
        assertTrue("a current release goes to the codec", w.presenter.submit(nextRelease(w), 1, 1) { codec++ })
        val r = nextRelease(w)
        val before = w.presenter.counters()
        newEpochOnTheSubmissionThread(w)
        assertFalse(w.presenter.submit(r, 1, 1) { codec++ })
        assertEquals("the codec never saw the old decision", 1, codec)
        val after = w.presenter.counters()
        assertEquals("not released", before.released - 1, after.released)
        assertEquals("dropped as stale", before.staleDropped + 1, after.staleDropped)
        // Nor does a decision made for an earlier surface or decoder.
        val s = nextReleaseAfterEpoch(w)
        assertFalse(w.presenter.submit(s, 2, 1) { codec++ })
        assertFalse(w.presenter.submit(s, 1, 2) { codec++ })
        assertEquals(1, codec)
    }

    /** After a new epoch: one arrival frame and its release under that epoch. */
    private fun nextReleaseAfterEpoch(w: Warm): SourceTimeScheduler.Entry {
        val decodedNs = w.t + 3_000_000
        w.presenter.admit(500, 500, decodedNs, null, 1, 1)
        return releaseOf(w, 500, decodedNs / hz60.periodNs + 1) ?: throw AssertionError("no release")
    }

    @Test(timeout = 20_000)
    fun aNewEpochWaitsForAReleaseAlreadyBeingHandedToTheCodec() {
        // W34 D2: checking the epoch and then calling the codec without a hold would let the
        // submission thread start a new epoch between the two. While the codec call runs, the new
        // epoch waits; the frame is one submitted before the reset.
        val w = warm()
        val r = nextRelease(w)
        val epoch = w.mapper.epoch
        val started = CountDownLatch(1)
        val done = CountDownLatch(1)
        var epochInCodec = -1L
        var waited = false
        val submitted = w.presenter.submit(r, 1, 1) {
            thread(name = "submission") {
                started.countDown()
                w.mapper.reset()
                done.countDown()
            }
            assertTrue(started.await(5, TimeUnit.SECONDS))
            waited = !done.await(300, TimeUnit.MILLISECONDS)
            epochInCodec = w.mapper.epoch
        }
        assertTrue(submitted)
        assertTrue("the new epoch waited for the codec call", waited)
        assertEquals(epoch, epochInCodec)
        assertTrue(done.await(5, TimeUnit.SECONDS))
        assertEquals(epoch + 1, w.mapper.epoch)
    }

    // --- W26 F4: source-paced has to stay fresh. ---

    /** One frame whose RTP timestamp repeats the last one, enqueued and decoded at 60 Hz. */
    private fun repeatFrame(w: Warm, display: Display = hz60) {
        val i = w.frame++
        val f = Frame((w.frame - 2) * 16_667L, w.t / 1000)
        val rtp = (900_000 + (lastSourceUs(w)) * 90 / 1000) and 0xFFFF_FFFFL
        val point = w.mapper.observe(i + 1, rtp, f.arrivalUs)
        assertEquals(SourceTimeMapper.REASON_DUPLICATE_TIMESTAMP, point.reason)
        w.presenter.admit(i, i.toLong(), w.t + 3_000_000, record(i, f, point), 1, 1)
        val k = (w.t + 4_000_000) / display.periodNs + 1
        w.t += 16_667_000
        w.presenter.onVsync(display.nowAt(k), display.slotsAt(k), display.deadlinesAt(k), display.periodNs, 1, 1)
    }

    private var repeatedSourceUs = -1L
    private fun lastSourceUs(w: Warm): Long {
        if (repeatedSourceUs < 0) repeatedSourceUs = (w.frame - 2) * 16_667L
        return repeatedSourceUs
    }

    private fun truthOf(w: Warm) = PresentationTruth.from(w.presenter.pacing(SourceTimeRequest.SOURCE_TIME, w.t),
        null, com.papi.nova.api.PolarisSessionVrr.Reading.Absent, true, 41, 60f)

    @Test
    fun anIsolatedRepeatKeepsTheStateButASecondOfRepeatsEndsTheClaimEverywhere() {
        val w = warm()
        assertEquals(60, w.presenter.staleFrames)
        // A few repeated timestamps: one frame's condition each, source-paced stays.
        repeat(5) { repeatFrame(w) }
        assertTrue(truthOf(w).sourcePaced)
        // The host keeps repeating for a second of frames: no target, so no claim.
        repeat(w.presenter.staleFrames - 5) { repeatFrame(w) }
        val truth = truthOf(w)
        assertFalse("not source-paced in the reducer", truth.sourcePaced)
        assertEquals(SourceTimeReason.SOURCE_TIMESTAMP_MISSING, truth.client.fallback)
        assertEquals("st1;requested=source_time;effective=pending;vrr_requested=0;vrr_effective=0;fallback=source_timestamp_missing",
            truth.client.toFramePacingState())
        assertFalse("no HUD chip", com.papi.nova.ui.NovaHudQualityChips.from(null, 60f, truth).any { it.label == "Source-paced" })
        // The host's next frame (one interval on; a gap over a second would be a discontinuity and a
        // new warm-up) is source-timed again and restores it.
        val i = w.frame++
        val f = Frame(repeatedSourceUs + 16_667L, w.t / 1000)
        repeatedSourceUs = -1
        val point = w.mapper.observe(i + 1, record(i, f, null).rtpTimestamp, f.arrivalUs)
        assertTrue(point.active)
        assertEquals(SourceTimeCounters.Admission.SOURCE_TIMED,
            w.presenter.admit(i, i.toLong(), w.t + 3_000_000, record(i, f, point), 1, 1).kind)
        assertTrue(truthOf(w).sourcePaced)
    }

    @Test
    fun aSecondWithoutAMappedTargetEndsTheClaimEvenWithFewFramesOrNone() {
        // Few frames: at 30 FPS negotiated but frames 600 ms apart, two repeats already span a second.
        val mapper = SourceTimeMapper(SourceTimePolicy.DEFAULT, 2, 33_333)
        val presenter = SourceTimePresenter(2, mapper, framesPerSecond = 30)
        val w = Warm(mapper, presenter, 1_000_000_000L, 0)
        repeat(30) { stepFrame(w, hz60) }
        assertEquals(SourceTimeEffective.SOURCE_TIME_FIXED, presenter.pacing(SourceTimeRequest.AUTO, w.t).effective)
        // Three repeats, half a second apart: far fewer than 30 frames, but a second without a target.
        repeatFrame(w)
        w.t += 500_000_000
        repeatFrame(w)
        assertEquals(SourceTimeEffective.SOURCE_TIME_FIXED, presenter.pacing(SourceTimeRequest.AUTO, w.t).effective)
        w.t += 500_000_000
        repeatFrame(w)
        assertEquals(SourceTimeReason.SOURCE_TIMESTAMP_MISSING, presenter.pacing(SourceTimeRequest.AUTO, w.t).fallback)
        // No frames at all: a status read a second after the last target says the same.
        repeatedSourceUs = -1
        val idle = warm()
        assertEquals(SourceTimeEffective.SOURCE_TIME_FIXED, idle.presenter.pacing(SourceTimeRequest.AUTO, idle.t).effective)
        val later = idle.presenter.pacing(SourceTimeRequest.AUTO, idle.t + 1_000_000_000L)
        assertEquals(SourceTimeEffective.PENDING, later.effective)
        assertEquals(SourceTimeReason.SOURCE_TIMESTAMP_MISSING, later.fallback)
    }
}
