#pragma once

#include <array>
#include <cstdio>
#include <optional>
#include <string_view>
#include <utility>

namespace nova::pyrowave {
// Device-check schema only. The independently negotiated stream profile stays v1.
inline constexpr int probeProtocolVersion = 2;
enum class RefusalCause { None, Api, Device, Decoder, Interop, DmaBuf, Queue, Limits, Unavailable };
inline constexpr std::array<std::pair<RefusalCause, std::string_view>, 9> refusalCodes{{
    {RefusalCause::None, "none"}, {RefusalCause::Api, "api"},
    {RefusalCause::Device, "device"}, {RefusalCause::Decoder, "decoder"},
    {RefusalCause::Interop, "interop"}, {RefusalCause::DmaBuf, "dmabuf"},
    {RefusalCause::Queue, "queue"}, {RefusalCause::Limits, "limits"},
    {RefusalCause::Unavailable, "unavailable"},
}};
inline std::optional<RefusalCause> parseRefusalCause(std::string_view code) {
    for (const auto& [cause, token] : refusalCodes) if (token == code) return cause;
    return std::nullopt;
}
inline const char* refusalCode(RefusalCause cause) {
    for (const auto& [candidate, token] : refusalCodes) if (candidate == cause) return token.data();
    return nullptr;
}
inline bool validProbeResult(bool available, int width, int height, RefusalCause cause) {
    return refusalCode(cause) && width >= 0 && height >= 0 && width <= 65536 && height <= 65536 &&
        (available ? cause == RefusalCause::None && width > 0 && height > 0 :
            cause != RefusalCause::None && width == 0 && height == 0);
}
// Only fixed tokens reach stdout. Codec/loader/driver diagnostic text never does.
inline int formatProbeResult(char* output, std::size_t capacity, bool available,
                             int width, int height, RefusalCause cause) {
    if (!validProbeResult(available, width, height, cause)) return 0;
    const auto size = std::snprintf(output, capacity,
        "{\"version\":%d,\"available\":%s,\"maxWidth\":%d,\"maxHeight\":%d,\"reason\":\"%s\"}\n",
        probeProtocolVersion, available ? "true" : "false", width, height, refusalCode(cause));
    return size > 0 && static_cast<std::size_t>(size) < capacity ? size : 0;
}
} // namespace nova::pyrowave
