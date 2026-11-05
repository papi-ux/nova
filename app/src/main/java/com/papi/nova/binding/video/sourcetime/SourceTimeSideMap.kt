package com.papi.nova.binding.video.sourcetime

/**
 * The host's source time for each frame MediaCodec is decoding, kept beside the codec rather than
 * in it.
 *
 * MediaCodec's presentationTimeUs stays what it has always been in this renderer: the core's
 * enqueue milliseconds times a thousand, bumped a microsecond on a collision. T3, the decode and
 * end-to-end statistics and the benchmark capture all read it with that meaning, so source time
 * never goes into queueInputBuffer. It travels here instead, keyed by the exact timestamp that was
 * queued, and comes back out by the same key when the decoded frame does.
 *
 * Keys are unique within one decoder generation because the queued timestamp only increases, so a
 * lookup cannot pair a frame with another frame's time. A miss is counted, never guessed around:
 * a codec may rewrite the timestamp, an entry may have expired, or the map may have been full.
 * Entries older than a second expire, when a new one is put and when one is taken (a record no
 * later put has aged out is still never handed out). When every one of the 64 slots is still
 * young, the new frame is refused rather than an older one evicted, and that frame simply has no
 * source time.
 *
 * The renderer puts a record just before queueInputBuffer, so even a decoder that returns the
 * picture before that call does finds it, and [withdraw]s it if the codec refuses the buffer.
 *
 * put() runs on the decode-unit submission thread and take() on the renderer thread, so every
 * method is synchronized. Both are a few map operations on at most 64 entries.
 */
class SourceTimeSideMap(
    private val capacity: Int = DEFAULT_CAPACITY,
    private val maxAgeNs: Long = DEFAULT_MAX_AGE_NS,
) {
    /** What was known about one picture when it was queued. */
    data class Record(
        val frameNumber: Int,
        /** Raw RTP timestamp, 90 kHz ticks modulo 2^32, non-negative. */
        val rtpTimestamp: Long,
        val receiveTimeMs: Long,
        val enqueueTimeMs: Long,
        /** System.nanoTime() when the picture reached this renderer; diagnostic only. */
        val submitNs: Long,
        /** The host's capture-to-send time in tenths of a millisecond; zero when not reported. */
        val hostLatencyTenths: Int,
        val connectionGeneration: Int,
        val decoderGeneration: Int,
        /** Where the mapper placed this picture, when source time was asked for; else null. */
        val point: SourceTimeMapper.Point? = null,
        /** The render target and display timing this picture was mapped for. */
        val surfaceGeneration: Int = 0,
    ) {
        /** The mapper epoch [point] was placed in; null without a point. */
        val mapperEpoch: Long?
            get() = point?.epoch
    }

    enum class PutResult { STORED, EXHAUSTED, DUPLICATE }

    data class Counters(
        val live: Int,
        val stored: Long,
        val taken: Long,
        val misses: Long,
        val staleGeneration: Long,
        val expired: Long,
        val exhausted: Long,
        val duplicates: Long,
        val withdrawn: Long = 0,
    )

    private val entries = LinkedHashMap<Long, Record>(capacity * 2)
    private var stored = 0L
    private var taken = 0L
    private var misses = 0L
    private var staleGeneration = 0L
    private var expired = 0L
    private var exhausted = 0L
    private var duplicates = 0L
    private var withdrawn = 0L

    @Synchronized
    fun put(ptsUs: Long, record: Record, nowNs: Long): PutResult {
        expire(nowNs)
        if (entries.containsKey(ptsUs)) {
            duplicates++
            return PutResult.DUPLICATE
        }
        if (entries.size >= capacity) {
            exhausted++
            return PutResult.EXHAUSTED
        }
        entries[ptsUs] = record
        stored++
        return PutResult.STORED
    }

    /**
     * The record queued with exactly [ptsUs], removed. Null when there is none, when it belongs to
     * an earlier decoder generation (a flushed codec never returns those frames, so a match across a
     * flush would be a coincidence of timestamps rather than the same picture), or when it is older
     * than the maximum age at [nowNs].
     */
    @Synchronized
    fun take(ptsUs: Long, decoderGeneration: Int, nowNs: Long): Record? {
        val record = entries.remove(ptsUs)
        if (record == null) {
            misses++
            return null
        }
        if (record.decoderGeneration != decoderGeneration) {
            staleGeneration++
            return null
        }
        if (nowNs - record.submitNs > maxAgeNs) {
            expired++
            return null
        }
        taken++
        return record
    }

    /** The codec refused the buffer queued with [ptsUs]: its record goes, and no frame will miss it. */
    @Synchronized
    fun withdraw(ptsUs: Long) {
        if (entries.remove(ptsUs) != null) withdrawn++
    }

    @Synchronized
    fun clear() {
        entries.clear()
    }

    @Synchronized
    fun counters(): Counters =
        Counters(entries.size, stored, taken, misses, staleGeneration, expired, exhausted, duplicates, withdrawn)

    // Insertion order is submission order and submitNs only increases with it, so the first young
    // entry ends the scan.
    private fun expire(nowNs: Long) {
        val iterator = entries.values.iterator()
        while (iterator.hasNext()) {
            val record = iterator.next()
            if (nowNs - record.submitNs <= maxAgeNs) {
                break
            }
            iterator.remove()
            expired++
        }
    }

    companion object {
        const val DEFAULT_CAPACITY = 64
        const val DEFAULT_MAX_AGE_NS = 1_000_000_000L
    }
}
