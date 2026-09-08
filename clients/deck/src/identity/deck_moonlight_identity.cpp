#include "identity/deck_moonlight_identity.h"

#include <QByteArray>
#include <QCryptographicHash>
#include <QSettings>
#include <QSslCertificate>
#include <QString>

#include <cstdlib>
#include <utility>

namespace nova::deck::identity {

namespace {

constexpr int kDefaultMoonlightHttpPort = 47989;
constexpr int kGameStreamHttpsOffset = 5;

std::string toStd(const QString& value) {
    return value.toStdString();
}

std::string toStd(const QByteArray& value) {
    return std::string(value.constData(), static_cast<std::size_t>(value.size()));
}

std::filesystem::path homeDirectory() {
    if (const char* home = std::getenv("HOME"); home != nullptr && *home != '\0') {
        return std::filesystem::path(home);
    }
    return {};
}

std::filesystem::path configHome() {
    if (const char* xdg = std::getenv("XDG_CONFIG_HOME"); xdg != nullptr && *xdg != '\0') {
        return std::filesystem::path(xdg);
    }
    const auto home = homeDirectory();
    return home.empty() ? std::filesystem::path{} : home / ".config";
}

DeckMoonlightAppRecord readApp(QSettings& settings) {
    DeckMoonlightAppRecord app;
    app.id = settings.value(QStringLiteral("id")).toInt();
    app.name = toStd(settings.value(QStringLiteral("name")).toString());
    app.hdr = settings.value(QStringLiteral("hdr")).toBool();
    app.hidden = settings.value(QStringLiteral("hidden")).toBool();
    return app;
}

DeckMoonlightHostRecord readHost(QSettings& settings) {
    DeckMoonlightHostRecord host;
    host.uuid = toStd(settings.value(QStringLiteral("uuid")).toString());
    host.hostname = toStd(settings.value(QStringLiteral("hostname")).toString());
    host.hasCustomName = settings.value(QStringLiteral("customname")).toBool();
    host.localAddress = toStd(settings.value(QStringLiteral("localaddress")).toString());
    host.localPort = settings.value(QStringLiteral("localport")).toInt();
    host.manualAddress = toStd(settings.value(QStringLiteral("manualaddress")).toString());
    host.manualPort = settings.value(QStringLiteral("manualport")).toInt();
    host.remoteAddress = toStd(settings.value(QStringLiteral("remoteaddress")).toString());
    host.remotePort = settings.value(QStringLiteral("remoteport")).toInt();
    host.ipv6Address = toStd(settings.value(QStringLiteral("ipv6address")).toString());
    host.ipv6Port = settings.value(QStringLiteral("ipv6port")).toInt();
    host.serverCertificatePem = toStd(settings.value(QStringLiteral("srvcert")).toByteArray());

    const int appCount = settings.beginReadArray(QStringLiteral("apps"));
    host.apps.reserve(static_cast<std::size_t>(appCount > 0 ? appCount : 0));
    for (int index = 0; index < appCount; ++index) {
        settings.setArrayIndex(index);
        host.apps.push_back(readApp(settings));
    }
    settings.endArray();
    return host;
}

} // namespace

std::string DeckMoonlightHostRecord::stableId() const {
    return uuid.empty() ? hostname : uuid;
}

std::string DeckMoonlightHostRecord::displayName() const {
    if (!hostname.empty()) {
        return hostname;
    }
    return uuid.empty() ? std::string{"Moonlight host"} : uuid;
}

std::string DeckMoonlightHostRecord::preferredAddress() const {
    if (!manualAddress.empty()) {
        return manualAddress;
    }
    if (!localAddress.empty()) {
        return localAddress;
    }
    if (!remoteAddress.empty()) {
        return remoteAddress;
    }
    return ipv6Address;
}

int DeckMoonlightHostRecord::preferredHttpPort() const {
    int port = 0;
    if (!manualAddress.empty()) {
        port = manualPort;
    } else if (!localAddress.empty()) {
        port = localPort;
    } else if (!remoteAddress.empty()) {
        port = remotePort;
    } else if (!ipv6Address.empty()) {
        port = ipv6Port;
    }
    return port > 0 ? port : kDefaultMoonlightHttpPort;
}

bool DeckMoonlightHostRecord::hasServerCertificate() const {
    return !serverCertificatePem.empty();
}

bool DeckMoonlightIdentity::hasClientIdentity() const {
    return loaded && !clientCertificatePem.empty() && !clientPrivateKeyPemForBackendOnly.empty();
}

std::string DeckMoonlightIdentity::clientCertificateFingerprintSha256() const {
    const QSslCertificate certificate(QByteArray::fromStdString(clientCertificatePem), QSsl::Pem);
    if (certificate.isNull()) {
        return {};
    }
    return toStd(QString::fromLatin1(certificate.digest(QCryptographicHash::Sha256).toHex()).toLower());
}

const DeckMoonlightHostRecord* DeckMoonlightIdentity::hostById(const std::string_view hostId) const {
    for (const auto& host : hosts) {
        if (host.stableId() == hostId) {
            return &host;
        }
    }
    return nullptr;
}

std::vector<DeckMoonlightIdentityCandidate> defaultIdentityCandidates() {
    std::vector<DeckMoonlightIdentityCandidate> candidates;
    if (const char* override = std::getenv("NOVA_DECK_MOONLIGHT_CONF"); override != nullptr && *override != '\0') {
        candidates.push_back({std::filesystem::path(override), "moonlight-override"});
    }
    const auto confRelative = std::filesystem::path("Moonlight Game Streaming Project") / "Moonlight.conf";
    if (const auto home = homeDirectory(); !home.empty()) {
        candidates.push_back({home / ".var" / "app" / "com.moonlight_stream.Moonlight" / "config" / confRelative, "moonlight-flatpak"});
    }
    if (const auto config = configHome(); !config.empty()) {
        candidates.push_back({config / confRelative, "moonlight-native"});
    }
    return candidates;
}

std::optional<DeckMoonlightIdentity> loadMoonlightIdentity(const std::filesystem::path& confPath, std::string sourceLabel) {
    std::error_code ec;
    if (!std::filesystem::is_regular_file(confPath, ec)) {
        return std::nullopt;
    }
    QSettings settings(QString::fromStdString(confPath.string()), QSettings::IniFormat);
    if (settings.status() != QSettings::NoError) {
        return std::nullopt;
    }

    DeckMoonlightIdentity identity;
    identity.loaded = true;
    identity.sourceLabel = std::move(sourceLabel);
    identity.sourcePathForBackendOnly = confPath.string();
    identity.clientCertificatePem = toStd(settings.value(QStringLiteral("certificate")).toByteArray());
    identity.clientPrivateKeyPemForBackendOnly = toStd(settings.value(QStringLiteral("key")).toByteArray());

    // Moonlight-Qt writes `hostsbackup` before rewriting `hosts` and reads the
    // backup first when it exists, so an interrupted flush is recovered the
    // same way here.
    for (const auto arrayName : {kMoonlightHostsBackupArray, kMoonlightHostsArray}) {
        const int hostCount = settings.beginReadArray(QString::fromUtf8(arrayName.data(), static_cast<int>(arrayName.size())));
        for (int index = 0; index < hostCount; ++index) {
            settings.setArrayIndex(index);
            auto host = readHost(settings);
            if (host.stableId().empty()) {
                continue;
            }
            identity.hosts.push_back(std::move(host));
        }
        settings.endArray();
        if (!identity.hosts.empty()) {
            break;
        }
    }
    return identity;
}

std::optional<DeckMoonlightIdentity> loadDefaultMoonlightIdentity() {
    for (const auto& candidate : defaultIdentityCandidates()) {
        if (auto identity = loadMoonlightIdentity(candidate.path, candidate.label)) {
            return identity;
        }
    }
    return std::nullopt;
}

int polarisHttpsPortForMoonlightHttpPort(const int httpPort) {
    const int base = httpPort > 0 ? httpPort : kDefaultMoonlightHttpPort;
    return base - kGameStreamHttpsOffset;
}

std::string shortFingerprint(const std::string& fingerprintHex) {
    return fingerprintHex.substr(0, 8);
}

} // namespace nova::deck::identity
