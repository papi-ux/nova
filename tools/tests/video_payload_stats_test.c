#include "video_payload_stats.h"
#ifdef NDEBUG
#undef NDEBUG /* These checks must also execute in the Release CTest lane. */
#endif
#include <assert.h>
#include <limits.h>
#include <pthread.h>
#include <stdio.h>

static int64_t now_ms;
int64_t nova_video_payload_clock_ms(void) { return now_ms; }

static void* receive_payload(void* unused) {
    (void) unused;
    for (int i = 0; i < 10000; i++) nova_video_payload_record(1024);
    return NULL;
}

int main(void) {
    nova_video_payload_snapshot_t sample;
    assert(!nova_video_payload_snapshot(&sample));
    nova_video_payload_begin();
    assert(!nova_video_payload_snapshot(&sample));
    nova_video_payload_start();
    now_ms = 1000;
    assert(nova_video_payload_snapshot(&sample));
    assert(sample.generation == 1 && sample.bytes == 0 && sample.now_ms == 1000);
    nova_video_payload_record(1500000);
    nova_video_payload_record(0);
    nova_video_payload_record(-7);
    now_ms = 2000;
    assert(nova_video_payload_snapshot(&sample));
    assert(sample.bytes == 1500000 && sample.now_ms == 2000);
    const int64_t first_generation = sample.generation;
    nova_video_payload_stop();
    nova_video_payload_record(100);
    assert(!nova_video_payload_snapshot(&sample));
    nova_video_payload_begin();
    nova_video_payload_start();
    now_ms = 3000;
    assert(nova_video_payload_snapshot(&sample));
    assert(sample.generation > first_generation && sample.bytes == 0);
    nova_video_payload_record(1234);
    now_ms = -1;
    assert(!nova_video_payload_snapshot(&sample));
    now_ms = 4000;
    assert(nova_video_payload_snapshot(&sample));
    assert(sample.bytes == 1234 && sample.now_ms == 4000);
    nova_video_payload_stop();
    nova_video_payload_stop();
    assert(!nova_video_payload_snapshot(&sample));

    /* The receive and diagnostic threads share a coherent cumulative snapshot. */
    nova_video_payload_begin();
    nova_video_payload_start();
    pthread_t receivers[4];
    for (int i = 0; i < 4; i++) assert(pthread_create(&receivers[i], NULL, receive_payload, NULL) == 0);
    int64_t previous_bytes = 0;
    for (int i = 0; i < 1000; i++) {
        assert(nova_video_payload_snapshot(&sample));
        assert(sample.bytes >= previous_bytes && sample.bytes <= 4 * 10000 * 1024);
        assert(sample.now_ms == 4000);
        previous_bytes = sample.bytes;
    }
    for (int i = 0; i < 4; i++) assert(pthread_join(receivers[i], NULL) == 0);
    assert(nova_video_payload_snapshot(&sample));
    assert(sample.bytes == 4 * 10000 * 1024);
    nova_video_payload_stop();
    puts("video payload counter lifecycle and byte-domain checks passed");
    return 0;
}
