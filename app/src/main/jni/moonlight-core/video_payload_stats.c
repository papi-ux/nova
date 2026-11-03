#include "video_payload_stats.h"
#include <limits.h>
#include <stddef.h>
#include <pthread.h>

/* Moonlight owns one connection per process. This observer has no controller input. */
static pthread_mutex_t mutex = PTHREAD_MUTEX_INITIALIZER;
static int64_t generation;
static int64_t received_bytes;
static bool active;
static bool exhausted;

void nova_video_payload_begin(void) {
    pthread_mutex_lock(&mutex);
    active = false;
    received_bytes = 0;
    if (generation == INT64_MAX) {
        exhausted = true;
    } else if (!exhausted) {
        generation++;
    }
    pthread_mutex_unlock(&mutex);
}

void nova_video_payload_start(void) {
    pthread_mutex_lock(&mutex);
    active = generation > 0 && !exhausted;
    pthread_mutex_unlock(&mutex);
}

void nova_video_payload_stop(void) {
    pthread_mutex_lock(&mutex);
    active = false;
    pthread_mutex_unlock(&mutex);
}

void nova_video_payload_record(int bytes) {
    if (bytes <= 0) return;
    pthread_mutex_lock(&mutex);
    if (active) {
        if (received_bytes > INT64_MAX - bytes) {
            active = false;
        } else {
            received_bytes += bytes;
        }
    }
    pthread_mutex_unlock(&mutex);
}

bool nova_video_payload_snapshot(nova_video_payload_snapshot_t* snapshot) {
    if (snapshot == NULL) return false;
    bool available = false;
    pthread_mutex_lock(&mutex);
    if (active) {
        const int64_t now_ms = nova_video_payload_clock_ms();
        if (now_ms >= 0) {
            *snapshot = (nova_video_payload_snapshot_t) {generation, received_bytes, now_ms};
            available = true;
        }
    }
    pthread_mutex_unlock(&mutex);
    return available;
}
