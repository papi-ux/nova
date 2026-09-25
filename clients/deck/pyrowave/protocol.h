#pragma once
#include <stdbool.h>
#include <string.h>

#define NOVA_PYROWAVE_PROFILE_TOKEN "pyrowave-186f0393-sdr420-v1"

// A host can advertise several colour profiles on the same fmtp line. Select
// only our complete SDR token, without treating HDR or a newer revision as SDR.
static inline bool nova_pyrowave_profile_list_compatible(const char* value, const char* end) {
    unsigned matches = 0;
    const size_t expected_length = strlen(NOVA_PYROWAVE_PROFILE_TOKEN);
    while (value < end) {
        while (value < end && (*value == ' ' || *value == '\t')) ++value;
        const char* token_end = value;
        while (token_end < end && *token_end != ' ' && *token_end != '\t') ++token_end;
        if ((size_t)(token_end - value) == expected_length &&
            !memcmp(value, NOVA_PYROWAVE_PROFILE_TOKEN, expected_length)) ++matches;
        value = token_end;
    }
    return matches == 1;
}

// Shared by the native C transport and its regression test. Require one mapping
// and one profile list: conflicting or duplicate attributes remain invalid.
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
                if (++*count != 1) return false;
                if (mapping) {
                    if ((size_t)(end - value) != strlen(expected) ||
                        memcmp(value, expected, (size_t)(end - value))) return false;
                } else if (!nova_pyrowave_profile_list_compatible(value, end)) return false;
            }
        }
        if (!newline) break;
        line = newline + 1;
    }
    return maps == 1 && profiles == 1;
}
