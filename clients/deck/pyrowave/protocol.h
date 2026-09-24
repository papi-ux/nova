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
        const bool mapping = length >= 9 && !memcmp(line, "a=rtpmap:", 9);
        const bool profile = length >= 7 && !memcmp(line, "a=fmtp:", 7);
        if (mapping || profile) {
            const char* value = line + (mapping ? 9 : 7);
            unsigned payload = 0;
            bool digits = false;
            while (value < end && *value >= '0' && *value <= '9') {
                // Payload identifiers are bounded; huge decimal values must
                // never wrap around and impersonate payload 99.
                payload = payload <= 127 ? payload * 10 + (*value - '0') : 128;
                digits = true; ++value;
            }
            if (digits && payload == 99) {
                if (value == end || (*value != ' ' && *value != '\t')) return false;
                while (value < end && (*value == ' ' || *value == '\t')) ++value;
                while (end > value && (end[-1] == ' ' || end[-1] == '\t')) --end;
                const char* expected = mapping ? "PYROWAVE/90000" : NOVA_PYROWAVE_PROFILE_TOKEN;
                unsigned* count = mapping ? &maps : &profiles;
                if (++*count != 1 || (size_t)(end - value) != strlen(expected) ||
                    memcmp(value, expected, (size_t)(end - value))) return false;
            }
        }
        if (!newline) break;
        line = newline + 1;
    }
    return maps == 1 && profiles == 1;
}
