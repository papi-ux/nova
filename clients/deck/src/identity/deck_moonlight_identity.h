#pragma once

#include <filesystem>
#include <optional>
#include <string>
#include <vector>

// Nova on the Deck borrows the pairing Moonlight-Qt already made instead of
// asking the player to pair twice. Moonlight-Qt keeps its client certificate,
// private key, paired hosts, pinned server certificates and cached app lists in
// one QSettings INI file; this module reads that file and nothing else. The
// private key never leaves the identity object into a public DTO.
namespace nova::deck::identity {

struct DeckMoonlightAppRecord {
    int id = 0;
    std::string name;
    bool hdr = false;
    bool hidden = false;
};

struct DeckMoonlightHostRecord {
    std::string uuid;
    std::string hostname;  ///< Moonlight's display name for the host, custom or reported
    bool hasCustomName = false;  ///< Moonlight only records whether the player renamed it
    std::string localAddress;
    int localPort = 0;
    std::string manualAddress;
    int manualPort = 0;
    std::string remoteAddress;
    int remotePort = 0;
    std::string ipv6Address;
    int ipv6Port = 0;
    std::string serverCertificatePem;
    std::vector<DeckMoonlightAppRecord> apps;

    /// Moonlight's host UUID, falling back to the hostname. Safe to show and to log.
    [[nodiscard]] std::string stableId() const;
    /// The name Moonlight shows: `hostname`, which already holds a custom name when the player set one.
    [[nodiscard]] std::string displayName() const;
    /// Manual address first (the player typed it), then local, remote, IPv6.
    [[nodiscard]] std::string preferredAddress() const;
    /// The HTTP port Moonlight recorded beside the preferred address (47989 by default).
    [[nodiscard]] int preferredHttpPort() const;
    [[nodiscard]] bool hasServerCertificate() const;
};

struct DeckMoonlightIdentity {
    bool loaded = false;
    std::string sourceLabel;
    std::string sourcePathForBackendOnly;
    std::string clientCertificatePem;
    std::string clientPrivateKeyPemForBackendOnly;
    std::vector<DeckMoonlightHostRecord> hosts;

    [[nodiscard]] bool hasClientIdentity() const;
    /// Lowercase hex SHA-256 of the client certificate DER; empty when the PEM does not parse.
    [[nodiscard]] std::string clientCertificateFingerprintSha256() const;
    [[nodiscard]] const DeckMoonlightHostRecord* hostById(std::string_view hostId) const;
};

struct DeckMoonlightIdentityCandidate {
    std::filesystem::path path;
    std::string label;
};

/// NOVA_DECK_MOONLIGHT_CONF first, then the Flatpak and native Moonlight-Qt locations.
std::vector<DeckMoonlightIdentityCandidate> defaultIdentityCandidates();

std::optional<DeckMoonlightIdentity> loadMoonlightIdentity(const std::filesystem::path& confPath, std::string sourceLabel);

/// The first candidate that exists and parses; nullopt when Moonlight-Qt has never run here.
std::optional<DeckMoonlightIdentity> loadDefaultMoonlightIdentity();

/// The array Moonlight-Qt persists hosts under, falling back to the `hostsbackup` copy it keeps while flushing.
inline constexpr std::string_view kMoonlightHostsArray = "hosts";
inline constexpr std::string_view kMoonlightHostsBackupArray = "hostsbackup";

/// Moonlight records the HTTP port; GameStream hosts serve HTTPS five ports below it.
int polarisHttpsPortForMoonlightHttpPort(int httpPort);

/// The first eight hex characters, enough to recognise an identity in a log without printing it.
std::string shortFingerprint(const std::string& fingerprintHex);

} // namespace nova::deck::identity
