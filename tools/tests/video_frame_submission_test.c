#include "video_payload_stats.h"
#ifdef NDEBUG
#undef NDEBUG
#endif
#include <assert.h>
#include <limits.h>
#include <pthread.h>
#include <stdatomic.h>
#include <stdio.h>
#include <string.h>

static int64_t clock_ms = 1000;
static bool tick = true;
static bool entropy = true;
static bool zero_nonce;
static unsigned nonce_id;
/* Providers execute under the real observer's mutex. */
int64_t nova_video_payload_clock_ms(void) { return tick ? ++clock_ms : clock_ms; }
bool nova_video_payload_random_nonce(uint8_t nonce[16]) {
    memset(nonce, 0, 16);
    nonce[0] = zero_nonce ? 0 : (uint8_t)++nonce_id;
    nonce[15] = zero_nonce ? 0 : 0xff;
    return entropy;
}

static int64_t begin(void) {
    nova_video_payload_begin();
    const int64_t epoch = nova_video_frame_submission_epoch();
    nova_video_payload_start();
    return epoch;
}

static atomic_bool writer_done;
static void* writer(void* unused) {
    (void)unused;
    for (uint32_t frame = 1; frame <= 50000; frame++)
        nova_video_frame_submission_record(frame, 123);
    atomic_store(&writer_done, true);
    return NULL;
}

int main(void) {
    nova_video_frame_submission_snapshot_t s, old;
    nova_video_payload_snapshot_t legacy;
    assert(nova_video_frame_submission_epoch() == 0);
    assert(!nova_video_frame_submission_snapshot(1, &s));
    nova_video_payload_begin();
    int64_t epoch = nova_video_frame_submission_epoch();
    assert(epoch > 0);
    nova_video_frame_submission_record(1, 999); /* Before start: no DU population. */
    assert(!nova_video_frame_submission_snapshot(epoch, &s));
    nova_video_payload_start();
    assert(!nova_video_frame_submission_snapshot(epoch, &s)); /* No cursor before first DU. */
    nova_video_frame_submission_record(10, 100); /* One whole DU, regardless of its NAL count/result. */
    assert(nova_video_frame_submission_snapshot(epoch, &s));
    assert(s.native_epoch == epoch && s.snapshot_sequence == 1 && s.last_frame_index == 10);
    assert(s.decode_units_submitted_total == 1 && s.payload_bytes_total == 100);
    assert(s.last_submission_timestamp_ms > 0 && s.last_submission_timestamp_ms < s.monotonic_timestamp_ms);
    assert(strlen(s.connection_nonce) == 32 && s.connection_nonce[31] == 'f');
    assert(nova_video_payload_snapshot(&legacy) && legacy.bytes == 100);
    old = s;
    nova_video_frame_submission_record(12, 200); /* A gap is an absent submission, not packet loss. */
    assert(!nova_video_frame_submission_snapshot(epoch + 1, &s)); /* Wrong renderer cannot consume it. */
    assert(nova_video_frame_submission_snapshot(epoch, &s));
    assert(s.last_frame_index == 12 && s.decode_units_submitted_total == 2 && s.payload_bytes_total == 300);
    assert(s.snapshot_sequence == 2);
    /* Addendum 01: cursor/unit/byte coherence, including an empty observation. */
    assert(s.decode_units_submitted_total - old.decode_units_submitted_total > 0);
    assert(s.decode_units_submitted_total - old.decode_units_submitted_total <= s.last_frame_index - old.last_frame_index);
    assert(s.payload_bytes_total - old.payload_bytes_total >= s.decode_units_submitted_total - old.decode_units_submitted_total);
    const nova_video_frame_submission_snapshot_t before_empty = s;
    assert(nova_video_frame_submission_snapshot(epoch, &s));
    assert(s.snapshot_sequence > before_empty.snapshot_sequence);
    assert(s.last_frame_index == before_empty.last_frame_index);
    assert(s.decode_units_submitted_total == before_empty.decode_units_submitted_total);
    assert(s.payload_bytes_total == before_empty.payload_bytes_total);
    assert(s.last_submission_timestamp_ms == before_empty.last_submission_timestamp_ms);
    nova_video_payload_stop();
    assert(nova_video_frame_submission_epoch() == 0);
    nova_video_frame_submission_record(13, 100);
    assert(!nova_video_frame_submission_snapshot(epoch, &s));
    nova_video_payload_start(); /* Cannot resurrect retired new evidence. */
    assert(!nova_video_frame_submission_snapshot(epoch, &s));

    epoch = begin();
    nova_video_frame_submission_record(100, 5000); /* Reset totals exceed previous epoch. */
    assert(nova_video_frame_submission_snapshot(epoch, &s));
    assert(epoch > old.native_epoch && strcmp(old.connection_nonce, s.connection_nonce) != 0);
    assert(s.decode_units_submitted_total == 1 && s.payload_bytes_total > old.payload_bytes_total);
    assert(!nova_video_frame_submission_snapshot(old.native_epoch, &s));
    nova_video_frame_submission_record(100, 200); /* Duplicate poisons new evidence only. */
    assert(!nova_video_frame_submission_snapshot(epoch, &s));
    assert(nova_video_payload_snapshot(&legacy) && legacy.bytes == 5200);
    nova_video_frame_submission_record(101, 200);
    assert(!nova_video_frame_submission_snapshot(epoch, &s));

    const uint32_t invalid_after[] = {9, 0, 1};
    for (size_t i = 0; i < 3; i++) {
        epoch = begin();
        nova_video_frame_submission_record(i == 2 ? UINT32_MAX : 10, 1);
        assert(nova_video_frame_submission_snapshot(epoch, &s));
        nova_video_frame_submission_record(invalid_after[i], 1);
        assert(!nova_video_frame_submission_snapshot(epoch, &s));
        assert(nova_video_payload_snapshot(&legacy) && legacy.bytes == 2);
    }
    epoch = begin();
    nova_video_frame_submission_record(1, 0);
    nova_video_frame_submission_record(2, 5);
    assert(!nova_video_frame_submission_snapshot(epoch, &s));
    assert(nova_video_payload_snapshot(&legacy) && legacy.bytes == 5);

    for (int mode = 0; mode < 3; mode++) {
        epoch = begin();
        nova_video_frame_submission_record(1, 1);
        nova_video_frame_submission_test_limits(mode == 0 ? INT64_MAX : 1,
            mode == 1 ? INT64_MAX : 1, mode == 2 ? INT64_MAX : 0);
        if (mode < 2) nova_video_frame_submission_record(2, 1);
        assert(!nova_video_frame_submission_snapshot(epoch, &s));
        assert(nova_video_payload_snapshot(&legacy));
    }
    epoch = begin();
    nova_video_frame_submission_record(1, 1);
    assert(nova_video_frame_submission_snapshot(epoch, &s));
    tick = false;
    const int64_t before_equal_sequence = s.snapshot_sequence;
    clock_ms = s.monotonic_timestamp_ms; /* Same clock tick: omit, do not poison or consume sequence. */
    assert(!nova_video_frame_submission_snapshot(epoch, &s));
    tick = true;
    assert(nova_video_frame_submission_snapshot(epoch, &s));
    assert(s.snapshot_sequence == before_equal_sequence + 1);
    epoch = begin();
    nova_video_frame_submission_record(1, 1);
    clock_ms = -10; /* Native clock failure/regression is sticky for new evidence. */
    assert(!nova_video_frame_submission_snapshot(epoch, &s));
    clock_ms = 10000;
    assert(!nova_video_frame_submission_snapshot(epoch, &s));
    assert(nova_video_payload_snapshot(&legacy));
    epoch = begin();
    nova_video_frame_submission_record(1, 1);
    clock_ms -= 100;
    nova_video_frame_submission_record(2, 1);
    assert(!nova_video_frame_submission_snapshot(epoch, &s));

    entropy = false;
    assert(begin() == 0);
    nova_video_frame_submission_record(1, 100);
    assert(nova_video_payload_snapshot(&legacy) && legacy.bytes == 100);
    entropy = true; zero_nonce = true;
    assert(begin() == 0);
    zero_nonce = false;

    epoch = begin();
    pthread_t thread;
    assert(pthread_create(&thread, NULL, writer, NULL) == 0);
    int64_t sequence = 0;
    do {
        if (nova_video_frame_submission_snapshot(epoch, &s)) {
            assert(s.snapshot_sequence > sequence);
            assert(s.last_frame_index == s.decode_units_submitted_total);
            assert(s.payload_bytes_total == s.decode_units_submitted_total * 123);
            assert(s.last_submission_timestamp_ms <= s.monotonic_timestamp_ms);
            sequence = s.snapshot_sequence;
        }
    } while (!atomic_load(&writer_done));
    assert(pthread_join(thread, NULL) == 0);
    assert(nova_video_frame_submission_snapshot(epoch, &s));
    assert(s.last_frame_index == 50000 && s.payload_bytes_total == 50000 * 123);
    nova_video_payload_stop();
    assert(!nova_video_frame_submission_snapshot(epoch, &s));

    nova_video_frame_submission_test_epoch(INT64_MAX - 1);
    assert(begin() == INT64_MAX);
    assert(begin() == 0); /* Never wrap or reuse a native process epoch. */
    assert(!nova_video_payload_snapshot(&legacy));
    puts("frame submission production observer: lifecycle, coherence, nonce, cursor, clocks and limits passed");
    return 0;
}
