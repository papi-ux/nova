#pragma once

#include "polaris/deck_polaris_client.h"

namespace nova::deck::stream {
inline constexpr std::size_t kStandardServerInfoLimit = 64 * 1024;
inline constexpr std::size_t kStandardAppListLimit = 1024 * 1024;

struct DeckStandardHostInfo {
    std::string id;
    bool paired = false;
    DeckStreamCapabilities streamCapabilities;
    // The game the host reports running as this paired client sees it (0 for none): a
    // Polaris worker profile reports only this client's own session, so it is not an
    // inventory of every user. Unset when absent, malformed, repeated or while the host is
    // still cleaning up a session, never a guessed 0. Meaningful only over paired HTTPS.
    std::optional<int> currentGame;
};
struct DeckStandardApp {
    int id = 0;
    std::string title;
    bool hdrSupported = false;
};

// GameStream XML is bounded and parsed as a complete document. Never return a
// partial library after invalid XML, duplicate IDs or an authorization error.
polaris::DeckPolarisResult<DeckStandardHostInfo> parseStandardHostInfo(std::string_view xml);
polaris::DeckPolarisResult<std::vector<DeckStandardApp>> parseStandardAppList(std::string_view xml);

// Android's NvHTTP adds these identifiers to every GameStream request. The
// client ID is public certificate-derived metadata, never the private key.
std::string standardHostTarget(const std::string& path, const std::string& clientId);
} // namespace nova::deck::stream
