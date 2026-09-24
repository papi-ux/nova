#pragma once
#include <stdbool.h>
#include <string.h>

#define NOVA_PYROWAVE_PROFILE_TOKEN "pyrowave-186f0393-sdr420-v1"

// Shared by the native C transport and its regression test. Match complete SDP
// attributes: a token elsewhere or a longer token cannot select this decoder.
static inline bool nova_pyrowave_description_compatible(const char* description) {
    if (!description) return false;
    unsigned maps = 0, profiles = 0;
    for (const char* line = description; *line;) {
        const char* newline = strchr(line, '\n');
        const char* end = newline ? newline : line + strlen(line);
        if (end > line && end[-1] == '\r') --end;
        const size_t length = (size_t)(end - line);
        const char map_prefix[] = "a=rtpmap:99 ";
        const char profile_prefix[] = "a=fmtp:99 ";
        const char map[] = "a=rtpmap:99 PYROWAVE/90000";
        const char profile[] = "a=fmtp:99 " NOVA_PYROWAVE_PROFILE_TOKEN;
        if (length >= sizeof(map_prefix) - 1 && !memcmp(line, map_prefix, sizeof(map_prefix) - 1)) {
            if (++maps != 1 || length != sizeof(map) - 1 || memcmp(line, map, length)) return false;
        }
        if (length >= sizeof(profile_prefix) - 1 && !memcmp(line, profile_prefix, sizeof(profile_prefix) - 1)) {
            if (++profiles != 1 || length != sizeof(profile) - 1 || memcmp(line, profile, length)) return false;
        }
        if (!newline) break;
        line = newline + 1;
    }
    return maps == 1 && profiles == 1;
}
