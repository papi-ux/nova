#include "polaris/deck_polaris_client.h"
#include "polaris/deck_session_events.h"

#include <QByteArray>
#include <QCryptographicHash>
#include <QRegularExpression>
#include <QEventLoop>
#include <QJsonArray>
#include <QSet>
#include <QJsonDocument>
#include <QJsonObject>
#include <QNetworkAccessManager>
#include <QNetworkReply>
#include <QNetworkRequest>
#include <QObject>
#include <QSslCertificate>
#include <QSslConfiguration>
#include <QSslError>
#include <QSslKey>
#include <QSslSocket>
#include <QString>
#include <QTimer>
#include <QUrl>

#include <memory>
#include <cmath>
#include <algorithm>
#include <set>
#include <utility>

namespace nova::deck::polaris {

namespace {

std::string toStd(const QString& value) {
    return value.toStdString();
}

std::vector<std::string> stringList(const QJsonValue& value) {
    std::vector<std::string> items;
    for (const auto& entry : value.toArray()) {
        if (entry.isString()) {
            items.push_back(toStd(entry.toString()));
        }
    }
    return items;
}

std::optional<QJsonObject> parseObject(const std::string_view json) {
    QJsonParseError error{};
    const auto document = QJsonDocument::fromJson(QByteArray(json.data(), static_cast<int>(json.size())), &error);
    if (error.error != QJsonParseError::NoError || !document.isObject()) {
        return std::nullopt;
    }
    return document.object();
}

DeckDisplayPlanner parseDisplayPlanner(const QJsonValue& value) {
    const auto object = value.toObject();
    if (!object.value("available").isBool() || !object.value("available").toBool() ||
        !object.value("choices").isArray() || object.value("choices").toArray().size() > 32) return {};
    DeckDisplayPlanner planner{true, {}};
    std::set<QString> ids;
    const auto recommended = object.value("recommended_id").toString().trimmed();
    static const QRegularExpression modePattern("^([0-9]{3,5})[xX]([0-9]{3,5})[xX]([0-9]{1,3}(?:\\.[0-9]{1,3})?)$");
    for (const auto& entry : object.value("choices").toArray()) {
        if (!entry.isObject()) continue;
        const auto choice = entry.toObject();
        const auto id = choice.value("id").toString().trimmed();
        if (id.isEmpty() || id.size() > 64) continue;
        if (!ids.insert(id).second) return {}; // Ambiguous recommendation IDs are not usable hints.
        const auto safe = choice.value("safe"), hidden = choice.value("hidden");
        if ((!safe.isUndefined() && (!safe.isBool() || !safe.toBool())) ||
            (!hidden.isUndefined() && (!hidden.isBool() || hidden.toBool()))) continue;
        const auto mode = choice.value("target_mode").toString().trimmed();
        if (mode.size() > 32) continue;
        const auto parsed = modePattern.match(mode);
        if (!parsed.hasMatch() || parsed.captured(3).toDouble() <= 0) continue;
        const int width = parsed.captured(1).toInt(), height = parsed.captured(2).toInt();
        if (!supportedDeckResolution(width, height)) continue;
        const auto advanced = choice.value("advanced"), custom = choice.value("custom");
        if ((!advanced.isUndefined() && !advanced.isBool()) || (!custom.isUndefined() && !custom.isBool())) continue;
        // A planner's trailing rate is a host suggestion, not a stream FPS pin.
        planner.choices.push_back({width, height, toStd(choice.value("title").toString().left(96)),
            toStd(choice.value("reason").toString(choice.value("intent").toString()).left(240)),
            id == (recommended.isEmpty() ? QStringLiteral("balanced") : recommended), advanced.toBool(), custom.toBool()});
    }
    return planner;
}

DeckGameTime parseGameTime(const QJsonObject& game) {
    const auto seconds = [](const QJsonValue& value, bool allowZero) -> std::optional<std::int64_t> {
        if (!value.isDouble()) return {};
        const auto number = value.toDouble();
        // Keep exact integer seconds through the QVariant/JavaScript boundary.
        if (!std::isfinite(number) || number < (allowZero ? 0 : 1) ||
            number > 9007199254740991.0 || std::floor(number) != number) return {};
        return static_cast<std::int64_t>(number);
    };
    const auto played = game.value("play_time").toObject();
    const auto beat = game.value("beat_time").toObject();
    DeckGameTime time;
    time.playedSeconds = seconds(played.value("seconds"), true);
    if (time.playedSeconds) time.playSource = toStd(played.value("source").toString().trimmed().left(64));
    time.mainSeconds = seconds(beat.value("main_seconds"), false);
    time.extrasSeconds = seconds(beat.value("extras_seconds"), false);
    time.completionistSeconds = seconds(beat.value("completionist_seconds"), false);
    if (time.mainSeconds || time.extrasSeconds || time.completionistSeconds)
        time.matchedName = toStd(beat.value("matched_name").toString().trimmed().left(256));
    return time;
}

DeckPolarisGame parseGame(const QJsonObject& object) {
    DeckPolarisGame game;
    game.id = toStd(object.value(QStringLiteral("id")).toString());
    // Polaris emits app_id as a string; the fixture and older hosts use a number.
    const auto appId = object.value(QStringLiteral("app_id"));
    game.appId = appId.isString() ? appId.toString().toInt() : appId.toInt();
    game.name = toStd(object.value(QStringLiteral("name")).toString());
    game.source = toStd(object.value(QStringLiteral("source")).toString());
    game.platform = toStd(object.value(QStringLiteral("platform")).toString());
    game.runtime = toStd(object.value(QStringLiteral("runtime")).toString());
    game.platformLabel = toStd(object.value(QStringLiteral("platform_label")).toString());
    game.runtimeLabel = toStd(object.value(QStringLiteral("runtime_label")).toString());
    game.steamAppid = toStd(object.value(QStringLiteral("steam_appid")).toString());
    game.category = toStd(object.value(QStringLiteral("category")).toString());
    game.coverUrl = toStd(object.value(QStringLiteral("cover_url")).toString());
    game.installed = object.value(QStringLiteral("installed")).toBool(true);
    game.hdrSupported = object.value(QStringLiteral("hdr_supported")).toBool(false);
    game.displayPlanner = parseDisplayPlanner(object.value("display_planner"));
    const auto lastLaunched = object.value(QStringLiteral("last_launched")).toDouble(0);
    // Polaris reports epoch seconds; bound to JavaScript Date's range before
    // converting or multiplying by 1000 for presentation.
    game.lastLaunched = lastLaunched > 0 && lastLaunched <= 8640000000000.0
        ? static_cast<long long>(lastLaunched) : 0;
    game.genres = stringList(object.value(QStringLiteral("genres")));
    game.gameTime = parseGameTime(object);
    const auto context = object.value("space").toObject();
    game.spaceId = context.value("id").toString().toStdString();
    game.spaceName = context.value("name").toString().toStdString();

    const auto launchMode = object.value(QStringLiteral("launch_mode")).toObject();
    game.launchPreferredMode = toStd(launchMode.value(QStringLiteral("preferred_mode")).toString());
    game.launchRecommendedMode = toStd(launchMode.value(QStringLiteral("recommended_mode")).toString());
    game.launchAllowedModes = stringList(launchMode.value(QStringLiteral("allowed_modes")));
    game.launchModeReason = toStd(launchMode.value(QStringLiteral("mode_reason")).toString());
    const auto contract = object.value("launch_mode");
    game.launchContractValid = contract.isUndefined() || contract.isObject();
    const auto allowed = launchMode.value("allowed_modes");
    if (!allowed.isUndefined()) {
        game.launchContractValid = game.launchContractValid && allowed.isArray();
        std::set<std::string> seen;
        for (const auto& mode : allowed.toArray()) {
            const auto normalized = normalizeLaunchMode(toStd(mode.toString()));
            if (!mode.isString() || normalized.empty() || !seen.insert(normalized).second)
                game.launchContractValid = false;
        }
    }

    const auto steamLaunch = object.value(QStringLiteral("steam_launch")).toObject();
    game.steamLaunchAvailable = steamLaunch.value(QStringLiteral("available")).toBool(false);
    game.steamLaunchMode = toStd(steamLaunch.value(QStringLiteral("mode")).toString());
    game.steamLaunchRecommendedMode = toStd(steamLaunch.value(QStringLiteral("recommended_mode")).toString());
    game.steamLaunchAllowedModes = stringList(steamLaunch.value(QStringLiteral("allowed_modes")));
    game.steamLaunchModeReason = toStd(steamLaunch.value(QStringLiteral("mode_reason")).toString());
    const auto artwork = object.value("artwork").toObject();
    const auto version = artwork.value("version");
    const auto revision = artwork.value("revision").toString();
    static const QRegularExpression safeId(QStringLiteral("^[A-Za-z0-9][A-Za-z0-9._-]{0,255}$"));
    if ((version.isUndefined() || (version.isDouble() && version.toDouble() == 1)) && revision.size() <= 256 &&
        safeId.match(QString::fromStdString(game.id)).hasMatch()) {
        game.artwork.revision = toStd(revision);
        const auto assets = artwork.value("assets").toObject();
        const auto assetPath = [&](const QString& kind) -> std::string {
            const auto asset = assets.value(kind).toObject();
            if (!asset.value("cached").toBool(false)) return {};
            const auto path = asset.value("url").toString().trimmed();
            const auto expected = QString("/polaris/v1/games/%1/%2/%3").arg(QString::fromStdString(game.id),
                spaceGameIdentity(game.id) ? "space-artwork" : "artwork", kind);
            const QRegularExpression allowed("^" + QRegularExpression::escape(expected) + "(?:\\?revision=[A-Za-z0-9._~-]{1,128})?$");
            // Only the selected game's fixed read routes. No foreign origins,
            // candidate/edit routes, traversal, redirects or cross-game assets.
            return allowed.match(path).hasMatch() ? toStd(path) : std::string{};
        };
        game.artwork.poster = assetPath("poster");
        game.artwork.hero = assetPath("hero");
        game.artwork.logo = assetPath("logo");
        game.artwork.icon = assetPath("icon");
        const auto transform = artwork.value("override").toObject().value("logo_transform").toObject();
        const auto number = [&](const char* name, double low, double high, double fallback) {
            const auto value = transform.value(name);
            return value.isDouble() && std::isfinite(value.toDouble()) && value.toDouble() >= low && value.toDouble() <= high
                ? value.toDouble() : fallback;
        };
        game.artwork.logoScale = number("scale", 0.25, 4.0, 1.0);
        game.artwork.logoX = number("x", 0.0, 1.0, 0.5);
        game.artwork.logoY = number("y", 0.0, 1.0, 0.5);
    }
    const QJsonArray artworkIdentity{QString::fromStdString(game.coverUrl), QString::fromStdString(game.artwork.revision),
        QString::fromStdString(game.artwork.poster), QString::fromStdString(game.artwork.hero),
        QString::fromStdString(game.artwork.logo), QString::fromStdString(game.artwork.icon)};
    game.artwork.key = QCryptographicHash::hash(QJsonDocument(artworkIdentity).toJson(QJsonDocument::Compact),
        QCryptographicHash::Sha256).toHex().toStdString();
    return game;
}

bool sameCertificate(const QSslCertificate& peer, const QSslCertificate& pinned) {
    return !peer.isNull() && !pinned.isNull() && peer.toDer() == pinned.toDer();
}

} // namespace

std::string normalizeLaunchMode(std::string_view value) {
    const auto mode = QString::fromUtf8(value.data(), static_cast<qsizetype>(value.size())).trimmed().toLower();
    if (mode == "headless") return "headless_stream";
    if (mode == "virtual_display") return "host_virtual_display";
    if (mode == "host_display") return "desktop_display";
    if (mode == "gpu_native" || mode == "gpu-native") return "windowed_stream";
    for (const auto* known : {"headless_stream", "host_virtual_display", "desktop_display", "desktop_takeover",
            "windowed_stream", "gamescope_stream", "headless_dongle"})
        if (mode == known) return known;
    return {};
}

bool isSessionLaunchMode(std::string_view value) {
    return value != "headless_dongle" && !value.empty() && normalizeLaunchMode(value) == value;
}

std::optional<DeckLaunchModeCatalog> parseLaunchModeCatalog(std::string_view json) {
    const auto root = parseObject(json);
    if (!root) return {};
    const auto object = root->contains("client_settings") ? root->value("client_settings").toObject() : *root;
    if (!object.value("version").isDouble() || object.value("version").toDouble() != 1 ||
        !object.value("desired").isObject() || !object.value("effective").isObject()) return {};
    const auto modes = object.value("capabilities").toObject().value("modes");
    if (!modes.isArray() || modes.toArray().isEmpty() || modes.toArray().size() > 16) return {};
    DeckLaunchModeCatalog catalog;
    std::set<std::string> seen;
    for (const auto& value : modes.toArray()) {
        if (!value.isObject()) return {};
        const auto mode = value.toObject();
        const auto id = normalizeLaunchMode(toStd(mode.value("value").toString()));
        const auto overridable = mode.value("session_overridable");
        if (id.empty() || !seen.insert(id).second || !mode.value("available").isBool() ||
            (!overridable.isUndefined() && !overridable.isBool())) return {};
        catalog.modes.push_back({id, mode.value("available").toBool(), overridable.toBool(true)});
    }
    catalog.desired = normalizeLaunchMode(toStd(object.value("desired").toObject().value("stream_display_mode").toString()));
    catalog.effective = normalizeLaunchMode(toStd(object.value("effective").toObject().value("stream_display_mode").toString()));
    if (!seen.contains(catalog.desired) || !seen.contains(catalog.effective)) return {};
    return catalog;
}

DeckLaunchModePolicy launchModePolicy(const DeckPolarisGame& game, const DeckLaunchModeCatalog& catalog) {
    if (!game.launchContractValid || game.id.starts_with("space.")) return {};
    DeckLaunchModePolicy result{true, catalog.desired, {}};
    for (const auto& mode : catalog.modes) {
        if (!mode.available || !mode.sessionOverridable || !isSessionLaunchMode(mode.value)) continue;
        if (!game.launchAllowedModes.empty() && std::none_of(game.launchAllowedModes.begin(), game.launchAllowedModes.end(),
            [&](const auto& allowed) { return normalizeLaunchMode(allowed) == mode.value; })) continue;
        result.allowed.push_back(mode.value);
    }
    return result;
}

std::string_view describe(const DeckPolarisRequestStatus status) {
    switch (status) {
    case DeckPolarisRequestStatus::Ok:
        return "ok";
    case DeckPolarisRequestStatus::InvalidIdentity:
        return "invalid-identity";
    case DeckPolarisRequestStatus::Unreachable:
        return "unreachable";
    case DeckPolarisRequestStatus::Timeout:
        return "timeout";
    case DeckPolarisRequestStatus::CertMismatch:
        return "cert-mismatch";
    case DeckPolarisRequestStatus::Unauthorized:
        return "unauthorized";
    case DeckPolarisRequestStatus::HttpError:
        return "http-error";
    case DeckPolarisRequestStatus::MalformedBody:
        return "malformed-body";
    }
    return "unknown";
}

std::optional<DeckHostPower> parseHostPower(std::string_view json) {
    const auto object = parseObject(json);
    if (!object) return {};
    DeckHostPower power;
    power.supported = object->value("sleep_supported").toBool(false);
    power.enabled = object->value("sleep_enabled").toBool(false);
    power.permitted = object->value("sleep_permitted").toBool(false);
    power.blockedMessage = object->value("sleep_blocked_message").toString().simplified().left(400).toStdString();
    power.lastOutcome = object->value("last_sleep_outcome").toString().left(64).toStdString();
    power.lastMessage = object->value("last_sleep_message").toString().simplified().left(400).toStdString();
    power.lastAt = object->value("last_sleep_at").toInteger(0);
    return power;
}

std::optional<DeckPolarisCapabilities> parseCapabilities(const std::string_view json) {
    const auto object = parseObject(json);
    if (!object) {
        return std::nullopt;
    }
    DeckPolarisCapabilities capabilities;
    capabilities.server = toStd(object->value(QStringLiteral("server")).toString());
    capabilities.version = toStd(object->value(QStringLiteral("version")).toString());
    const auto features = object->value(QStringLiteral("features")).toObject();
    capabilities.gameLibrary = features.value(QStringLiteral("game_library")).toBool(false);
    capabilities.sessionLifecycle = features.value(QStringLiteral("session_lifecycle")).toBool(false);
    capabilities.clientSettings = features.value(QStringLiteral("client_settings_v1")).toBool(false);
    capabilities.resolvedProfileProvenance = features.value(QStringLiteral("resolved_profile_provenance_v1")).toBool(false);
    capabilities.expectedTopologyAssertion = features.value(QStringLiteral("expected_topology_assertion_v1")).toBool(false);
    capabilities.hostSleep = features.value("host_sleep_v1").toBool(false);
    capabilities.spaces = features.value("spaces_v1").toBool(false);
    capabilities.hostPower = *parseHostPower(QJsonDocument(object->value("host_power").toObject()).toJson().toStdString());
    const auto capture = object->value(QStringLiteral("capture")).toObject();
    capabilities.captureBackend = toStd(capture.value(QStringLiteral("backend")).toString());
    capabilities.codecs = stringList(capture.value(QStringLiteral("codecs")));
    auto& stream = capabilities.streamCapabilities;
    if (object->contains("capture") && !object->value("capture").isObject()) stream.valid = false;
    if (capture.contains("codecs")) {
        const auto codecs = capture.value("codecs");
        stream.h264 = false;
        if (!codecs.isArray() || codecs.toArray().size() > 16) stream.valid = false;
        for (const auto& codec : codecs.toArray()) {
            if (!codec.isString() || codec.toString().size() > 64) stream.valid = false;
            if (codec.toString().trimmed().compare("h264", Qt::CaseInsensitive) == 0) stream.h264 = true;
            if (codec.toString().trimmed().compare("hevc", Qt::CaseInsensitive) == 0) stream.hevc = true;
        }
    }
    if (capture.contains("max_fps")) {
        const auto fps = capture.value("max_fps");
        const auto maximum = fps.toDouble();
        if (!fps.isDouble() || !std::isfinite(maximum) || maximum <= 0 || maximum > 1000) stream.valid = false;
        else stream.maxFps = maximum;
    }
    return capabilities;
}

std::optional<DeckPolarisGamesPage> parseGamesPage(const std::string_view json) {
    const auto object = parseObject(json);
    if (!object) {
        return std::nullopt;
    }
    const auto gamesValue = object->value(QStringLiteral("games"));
    if (!gamesValue.isArray()) {
        return std::nullopt;
    }
    DeckPolarisGamesPage page;
    for (const auto& entry : gamesValue.toArray()) {
        if (!entry.isObject()) {
            continue;
        }
        const auto fields = entry.toObject();
        auto game = parseGame(fields);
        const auto space = spaceGameIdentity(game.id);
        if (game.id.starts_with("space.") || fields.contains("space")) {
            const auto context = fields.value("space").toObject();
            const auto name = QString::fromStdString(game.spaceName);
            if (!space || game.appId != kSpaceAppId || game.spaceId != space->spaceId ||
                context.value("target").toString().toStdString() != space->target || name.trimmed().isEmpty() ||
                name.toUtf8().size() > 128 || std::any_of(name.begin(), name.end(), [](QChar c) { return c.unicode() < 32 || c.unicode() == 127; })) return {};
        }
        if (game.id.empty() || game.name.empty()) {
            continue;
        }
        page.games.push_back(std::move(game));
    }
    page.total = object->value(QStringLiteral("total")).toInt(static_cast<int>(page.games.size()));
    return page;
}

std::optional<DeckPolarisSessionStatus> parseSessionStatus(const std::string_view json) {
    const auto object = parseObject(json);
    if (!object) {
        return std::nullopt;
    }
    DeckPolarisSessionStatus status;
    status.state = toStd(object->value(QStringLiteral("state")).toString(QStringLiteral("unknown")));
    status.streamingActive = object->value(QStringLiteral("streaming_active")).toBool(false);
    status.game = toStd(object->value(QStringLiteral("game")).toString());
    status.gameUuid = toStd(object->value(QStringLiteral("game_uuid")).toString());
    status.ownerDeviceName = toStd(object->value(QStringLiteral("owner_device_name")).toString());
    status.clientRole = toStd(object->value(QStringLiteral("client_role")).toString(QStringLiteral("none")).toLower());
    status.ownedByClient = object->value(QStringLiteral("owned_by_client")).toBool(false);
    status.viewerCount = object->value(QStringLiteral("viewer_count")).toInt(0);
    return status;
}

DeckPolarisRequestTarget splitRequestTarget(const std::string_view pathWithQuery) {
    const auto mark = pathWithQuery.find('?');
    if (mark == std::string_view::npos) {
        return DeckPolarisRequestTarget{.path = std::string(pathWithQuery), .query = {}};
    }
    return DeckPolarisRequestTarget{
        .path = std::string(pathWithQuery.substr(0, mark)),
        .query = std::string(pathWithQuery.substr(mark + 1)),
    };
}

std::optional<int> parseServerInfoHttpsPort(const std::string_view xml) {
    constexpr std::string_view open = "<HttpsPort>";
    constexpr std::string_view close = "</HttpsPort>";
    const auto start = xml.find(open);
    if (start == std::string_view::npos) {
        return std::nullopt;
    }
    const auto valueStart = start + open.size();
    const auto end = xml.find(close, valueStart);
    if (end == std::string_view::npos || end == valueStart) {
        return std::nullopt;
    }
    const auto digits = xml.substr(valueStart, end - valueStart);
    int port = 0;
    for (const char c : digits) {
        if (c < '0' || c > '9') {
            return std::nullopt;
        }
        port = port * 10 + (c - '0');
        if (port > 65535) {
            return std::nullopt;
        }
    }
    return port > 0 ? std::optional<int>(port) : std::nullopt;
}

namespace {

std::string_view describeNetworkError(const QNetworkReply::NetworkError error) {
    // Fixed strings on purpose: Qt's errorString() embeds the host address.
    switch (error) {
    case QNetworkReply::ConnectionRefusedError:
        return "connection refused";
    case QNetworkReply::RemoteHostClosedError:
        return "connection closed by the host";
    case QNetworkReply::HostNotFoundError:
        return "host name did not resolve";
    case QNetworkReply::TimeoutError:
    case QNetworkReply::OperationCanceledError:
        return "no answer within the timeout";
    case QNetworkReply::SslHandshakeFailedError:
        return "TLS handshake failed";
    case QNetworkReply::NetworkSessionFailedError:
    case QNetworkReply::TemporaryNetworkFailureError:
        return "network unavailable";
    default:
        return "network error";
    }
}

} // namespace

DeckPolarisServerInfoProbe probeServerInfoHttpsPort(const std::string& address, const int httpPort, const std::chrono::milliseconds timeout) {
    DeckPolarisServerInfoProbe probe;
    if (address.empty() || httpPort <= 0) {
        return probe;
    }
    QUrl url;
    url.setScheme(QStringLiteral("http"));
    url.setHost(QString::fromStdString(address));
    url.setPort(httpPort);
    url.setPath(QStringLiteral("/serverinfo"));
    QNetworkRequest request(url);
    request.setTransferTimeout(static_cast<int>(timeout.count()));
    request.setAttribute(QNetworkRequest::RedirectPolicyAttribute, QNetworkRequest::ManualRedirectPolicy);

    QNetworkAccessManager manager;
    QNetworkReply* reply = manager.get(request);
    QEventLoop loop;
    QObject::connect(reply, &QNetworkReply::finished, &loop, &QEventLoop::quit);
    loop.exec();
    const auto body = reply->readAll();
    const auto networkError = reply->error();
    reply->deleteLater();
    probe.timedOut = networkError == QNetworkReply::OperationCanceledError || networkError == QNetworkReply::TimeoutError;
    if (networkError != QNetworkReply::NoError) {
        return probe;
    }
    probe.httpsPort = parseServerInfoHttpsPort(std::string_view(body.constData(), static_cast<std::size_t>(body.size())));
    return probe;
}

std::optional<int> resolveHttpsPortFromServerInfo(const std::string& address, const int httpPort, const std::chrono::milliseconds timeout) {
    return probeServerInfoHttpsPort(address, httpPort, timeout).httpsPort;
}

struct DeckPolarisClient::Session {
    QSslCertificate clientCertificate;
    QSslKey clientKey;
    QSslCertificate pinnedServerCertificate;
    QNetworkAccessManager manager;

    [[nodiscard]] bool usable() const {
        return !clientCertificate.isNull() && !clientKey.isNull() && !pinnedServerCertificate.isNull();
    }
};

DeckPolarisClient::DeckPolarisClient(
    DeckPolarisEndpoint endpoint,
    DeckPolarisTlsIdentity identity,
    const std::chrono::milliseconds timeout)
    : endpoint_(std::move(endpoint))
    , identity_(std::move(identity))
    , timeout_(timeout)
    , session_(std::make_shared<Session>()) {
    session_->clientCertificate = QSslCertificate(QByteArray::fromStdString(identity_.clientCertificatePem), QSsl::Pem);
    session_->clientKey = QSslKey(QByteArray::fromStdString(identity_.clientPrivateKeyPem), QSsl::Rsa, QSsl::Pem);
    session_->pinnedServerCertificate = QSslCertificate(QByteArray::fromStdString(identity_.pinnedServerCertificatePem), QSsl::Pem);
}

namespace {
class OneShotPostBody final : public QIODevice {
public:
    explicit OneShotPostBody(std::string body) : body_(std::move(body)) { open(QIODevice::ReadOnly | QIODevice::Unbuffered); }
    bool isSequential() const override { return true; }
    bool reset() override { return false; }
    bool seek(qint64) override { return false; }
    qint64 bytesAvailable() const override { return static_cast<qint64>(body_.size()) - offset_ + QIODevice::bytesAvailable(); }
protected:
    qint64 readData(char* data, qint64 length) override {
        const qint64 count = std::min(length, static_cast<qint64>(body_.size()) - offset_);
        if (!count) return -1;
        std::copy_n(body_.data() + offset_, count, data);
        offset_ += count;
        return count;
    }
    qint64 writeData(const char*, qint64) override { return -1; }
private:
    qint64 offset_ = 0;
    std::string body_;
};
}

DeckPolarisResult<std::string> DeckPolarisClient::get(const std::string& path, std::size_t maxBodyBytes) const {
    return request(path, maxBodyBytes, false);
}

DeckPolarisResult<std::string> DeckPolarisClient::request(const std::string& path,
    std::size_t maxBodyBytes, bool post, const std::function<bool()>& cancelled, std::string postBody, bool remove) const {
    DeckPolarisResult<std::string> result;
    if (maxBodyBytes == 0 || maxBodyBytes > 4 * 1024 * 1024) {
        result.status = DeckPolarisRequestStatus::MalformedBody;
        result.detail = "invalid response size limit";
        return result;
    }
    if (!session_->usable() || endpoint_.address.empty()) {
        result.status = DeckPolarisRequestStatus::InvalidIdentity;
        result.detail = "client certificate, private key, pinned server certificate and address are all required";
        return result;
    }

    const auto target = splitRequestTarget(path);
    QUrl url;
    url.setScheme(QStringLiteral("https"));
    url.setHost(QString::fromStdString(endpoint_.address));
    url.setPort(endpoint_.httpsPort);
    url.setPath(QString::fromStdString(target.path));
    if (!target.query.empty()) {
        url.setQuery(QString::fromStdString(target.query));
    }

    QNetworkRequest request(url);
    // The pinned certificate is the only trust anchor. Polaris serves a
    // self-signed certificate under a name that never matches the address,
    // so the two errors that certificate legitimately raises are ignored for
    // that certificate alone; any other certificate fails the handshake and
    // the request never leaves this client.
    QSslConfiguration ssl = QSslConfiguration::defaultConfiguration();
    ssl.setLocalCertificate(session_->clientCertificate);
    ssl.setPrivateKey(session_->clientKey);
    ssl.setCaCertificates({session_->pinnedServerCertificate});
    ssl.setPeerVerifyMode(QSslSocket::VerifyPeer);
    request.setSslConfiguration(ssl);
    request.setTransferTimeout(static_cast<int>(timeout_.count()));
    request.setAttribute(QNetworkRequest::RedirectPolicyAttribute, QNetworkRequest::ManualRedirectPolicy);

    const QSslCertificate pinned = session_->pinnedServerCertificate;
    bool foreignCertificateSeen = false;
    // A new manager forces a new pin check before a mutation can leave.
    // HTTP/1.1 + an unbuffered, non-resettable upload prevents Qt's automatic
    // resend path from replaying a POST (QHttpNetworkConnectionChannel::resetUploadData).
    const auto uploadSize = static_cast<qint64>(postBody.size());
    OneShotPostBody upload(std::move(postBody));
    QNetworkAccessManager singleRequestManager;
    if (post) {
        request.setAttribute(QNetworkRequest::Http2AllowedAttribute, false);
        request.setAttribute(QNetworkRequest::DoNotBufferUploadDataAttribute, true);
        request.setAttribute(QNetworkRequest::AuthenticationReuseAttribute, QNetworkRequest::Manual);
        request.setRawHeader("Connection", "close");
        request.setHeader(QNetworkRequest::ContentTypeHeader, "application/json");
        request.setHeader(QNetworkRequest::ContentLengthHeader, uploadSize);
    }
    if (cancelled && cancelled()) { result.status = DeckPolarisRequestStatus::Timeout; return result; }
    QNetworkReply* reply = post ? (remove ? singleRequestManager.sendCustomRequest(request, "DELETE", &upload)
        : singleRequestManager.post(request, &upload)) : session_->manager.get(request);
    reply->setReadBufferSize(static_cast<qint64>(maxBodyBytes + 1));
    QByteArray body;
    bool tooLarge = false;
    const auto consume = [&] {
        if (!reply->isOpen()) return;
        body += reply->read(static_cast<qint64>(maxBodyBytes + 1) - body.size());
        if (body.size() > static_cast<qsizetype>(maxBodyBytes)) { tooLarge = true; reply->abort(); }
    };
    QObject::connect(reply, &QNetworkReply::readyRead, reply, consume);
    QObject::connect(reply, &QNetworkReply::encrypted, reply, [reply, pinned, &foreignCertificateSeen, cancelled] {
        if (cancelled && cancelled()) { reply->abort(); return; }
        // A chain trusted by the pinned certificate is not the exact pinned
        // peer. Reject it before Qt sends any HTTP request on a new connection.
        if (!sameCertificate(reply->sslConfiguration().peerCertificate(), pinned)) {
            foreignCertificateSeen = true;
            reply->abort();
        }
    });
    QObject::connect(reply, &QNetworkReply::sslErrors, reply, [reply, pinned, &foreignCertificateSeen](const QList<QSslError>& errors) {
        QList<QSslError> tolerated;
        for (const auto& error : errors) {
            const bool onPinned = sameCertificate(error.certificate(), pinned);
            const bool expectedForSelfSigned = error.error() == QSslError::HostNameMismatch
                || error.error() == QSslError::SelfSignedCertificate
                || error.error() == QSslError::CertificateUntrusted;
            if (onPinned && expectedForSelfSigned) {
                tolerated.push_back(error);
            } else {
                foreignCertificateSeen = true;
            }
        }
        if (!foreignCertificateSeen && tolerated.size() == errors.size()) {
            reply->ignoreSslErrors(tolerated);
        }
    });
    QEventLoop loop;
    QTimer deadline;
    deadline.setSingleShot(true);
    QObject::connect(&deadline, &QTimer::timeout, reply, &QNetworkReply::abort);
    QObject::connect(reply, &QNetworkReply::finished, &loop, &QEventLoop::quit);
    QTimer cancellation;
    if (cancelled) {
        QObject::connect(&cancellation, &QTimer::timeout, reply, [reply, cancelled] { if (cancelled()) reply->abort(); });
        cancellation.start(20);
    }
    deadline.start(timeout_);
    if (!reply->isFinished()) loop.exec();
    consume();

    const auto peer = reply->sslConfiguration().peerCertificate();
    const auto networkError = reply->error();
    result.httpStatus = reply->attribute(QNetworkRequest::HttpStatusCodeAttribute).toInt();
    reply->deleteLater();

    if (foreignCertificateSeen || (!peer.isNull() && !sameCertificate(peer, pinned))) {
        result.status = DeckPolarisRequestStatus::CertMismatch;
        result.detail = "the host presented a certificate that is not the one pinned at pairing";
        return result;
    }
    if (tooLarge) {
        result.status = DeckPolarisRequestStatus::MalformedBody;
        result.detail = "the host response exceeded the size limit";
        return result;
    }
    if (networkError == QNetworkReply::OperationCanceledError || networkError == QNetworkReply::TimeoutError) {
        result.status = DeckPolarisRequestStatus::Timeout;
        result.detail = "no answer within the timeout";
        return result;
    }
    if (peer.isNull()) {
        result.status = DeckPolarisRequestStatus::Unreachable;
        result.detail = std::string(describeNetworkError(networkError));
        return result;
    }
    // Preserve only authenticated, bounded bodies for the host's refusal copy.
    if (post && result.httpStatus >= 200 && result.httpStatus < 600)
        result.value = std::string(body.constData(), static_cast<std::size_t>(body.size()));
    if (result.httpStatus == 401 || result.httpStatus == 403) {
        result.status = DeckPolarisRequestStatus::Unauthorized;
        result.detail = "the host does not accept this client certificate";
        return result;
    }
    if (networkError != QNetworkReply::NoError || result.httpStatus < 200 || result.httpStatus >= 300) {
        result.status = DeckPolarisRequestStatus::HttpError;
        result.detail = result.httpStatus > 0 ? "HTTP " + std::to_string(result.httpStatus) : std::string(describeNetworkError(networkError));
        return result;
    }
    result.status = DeckPolarisRequestStatus::Ok;
    result.value = std::string(body.constData(), static_cast<std::size_t>(body.size()));
    return result;
}

DeckPolarisResult<bool> DeckPolarisClient::watchSessionEvents(int advertisedPort,
    const std::function<void()>& refresh, const std::function<bool()>& cancelled,
    std::chrono::milliseconds idleTimeout) const {
    DeckPolarisResult<bool> result;
    if (!session_->usable() || endpoint_.address.empty() || advertisedPort < 1 || advertisedPort > 65535) {
        result.status = DeckPolarisRequestStatus::InvalidIdentity; return result;
    }
    if (cancelled && cancelled()) { result.status = DeckPolarisRequestStatus::Timeout; return result; }
    QUrl url;
    url.setScheme("https"); url.setHost(QString::fromStdString(endpoint_.address));
    url.setPort(advertisedPort); url.setPath("/api/polaris/events");
    QNetworkRequest request(url);
    QSslConfiguration ssl = QSslConfiguration::defaultConfiguration();
    ssl.setLocalCertificate(session_->clientCertificate); ssl.setPrivateKey(session_->clientKey);
    ssl.setCaCertificates({session_->pinnedServerCertificate}); ssl.setPeerVerifyMode(QSslSocket::VerifyPeer);
    request.setSslConfiguration(ssl);
    request.setAttribute(QNetworkRequest::RedirectPolicyAttribute, QNetworkRequest::ManualRedirectPolicy);
    request.setAttribute(QNetworkRequest::Http2AllowedAttribute, false);
    request.setAttribute(QNetworkRequest::AuthenticationReuseAttribute, QNetworkRequest::Manual);
    request.setRawHeader("Accept", "text/event-stream");
    request.setRawHeader("Cache-Control", "no-cache");
    QNetworkAccessManager manager; // A new exact-pin admission on every connection.
    auto* reply = manager.get(request);
    reply->setReadBufferSize(65536);
    const auto pinned = session_->pinnedServerCertificate;
    bool foreign = false, admitted = false, headers = false, malformed = false, rejected = false;
    QEventLoop loop;
    QTimer deadline, cancellation;
    deadline.setSingleShot(true);
    const int idleMs = static_cast<int>(std::clamp<qint64>(idleTimeout.count(), 20, 30000));
    DeckSessionEventParser parser([&] { if (refresh && !(cancelled && cancelled())) refresh(); });
    QObject::connect(reply, &QNetworkReply::encrypted, reply, [&] {
        if (cancelled && cancelled()) { reply->abort(); return; }
        if (!sameCertificate(reply->sslConfiguration().peerCertificate(), pinned)) {
            foreign = true; reply->abort(); return;
        }
        admitted = true;
    });
    QObject::connect(reply, &QNetworkReply::sslErrors, reply, [&](const QList<QSslError>& errors) {
        QList<QSslError> tolerated;
        for (const auto& error : errors) {
            const bool expected = error.error() == QSslError::HostNameMismatch ||
                error.error() == QSslError::SelfSignedCertificate || error.error() == QSslError::CertificateUntrusted;
            if (sameCertificate(error.certificate(), pinned) && expected) tolerated.push_back(error);
            else foreign = true;
        }
        if (!foreign && tolerated.size() == errors.size()) reply->ignoreSslErrors(tolerated);
    });
    const auto admitHeaders = [&] {
        if (headers || rejected) return;
        const int status = reply->attribute(QNetworkRequest::HttpStatusCodeAttribute).toInt();
        if (!status) return;
        if (status != 200) { rejected = true; reply->abort(); return; }
        const auto type = reply->rawHeader("Content-Type").split(';').first().trimmed().toLower();
        if (!admitted || type != "text/event-stream") { malformed = true; reply->abort(); return; }
        headers = true;
        deadline.start(idleMs);
        if (refresh && !(cancelled && cancelled())) refresh(); // Reconnect is a resync boundary.
    };
    QObject::connect(reply, &QNetworkReply::metaDataChanged, reply, admitHeaders);
    const auto consume = [&] {
        admitHeaders();
        if (!headers || !reply->isOpen() || malformed) return;
        // Bound work per delivery as well as parser storage. A host flooding
        // records cannot keep the cancellation timer from running indefinitely.
        int budget = 256 * 1024;
        while (reply->bytesAvailable() > 0) {
            if ((cancelled && cancelled()) || budget <= 0) {
                if (budget <= 0) malformed = true;
                reply->abort(); return;
            }
            const auto before = parser.records();
            const auto bytes = reply->read(std::min(4096, budget));
            if (bytes.isEmpty()) break;
            budget -= bytes.size();
            if (!parser.feed(bytes)) { malformed = true; reply->abort(); return; }
            // Incomplete dribbles do not keep a stalled stream alive.
            if (parser.records() != before) deadline.start(idleMs);
        }
    };
    QObject::connect(reply, &QNetworkReply::readyRead, reply, consume);
    QObject::connect(reply, &QNetworkReply::finished, &loop, &QEventLoop::quit);
    QObject::connect(&deadline, &QTimer::timeout, reply, &QNetworkReply::abort);
    QObject::connect(&cancellation, &QTimer::timeout, reply, [&] { if (cancelled && cancelled()) reply->abort(); });
    cancellation.start(20); deadline.start(timeout_);
    if (!reply->isFinished()) loop.exec();
    consume();
    result.httpStatus = reply->attribute(QNetworkRequest::HttpStatusCodeAttribute).toInt();
    const auto error = reply->error();
    if (foreign) result.status = DeckPolarisRequestStatus::CertMismatch;
    else if (result.httpStatus == 401 || result.httpStatus == 403) result.status = DeckPolarisRequestStatus::Unauthorized;
    else if (rejected) result.status = DeckPolarisRequestStatus::HttpError;
    else if (malformed) result.status = DeckPolarisRequestStatus::MalformedBody;
    else if (error == QNetworkReply::OperationCanceledError || error == QNetworkReply::TimeoutError)
        result.status = DeckPolarisRequestStatus::Timeout;
    else if (!headers || !admitted) result.status = DeckPolarisRequestStatus::Unreachable;
    else if (error != QNetworkReply::NoError) result.status = DeckPolarisRequestStatus::HttpError;
    else if (!parser.complete()) result.status = DeckPolarisRequestStatus::MalformedBody;
    else { result.status = DeckPolarisRequestStatus::Ok; result.value = true; }
    // Destroy the reply while its parser/timers and captured locals still live.
    delete reply;
    return result;
}

namespace {

template <typename T, typename Parser>
DeckPolarisResult<T> parsed(DeckPolarisResult<std::string> raw, Parser&& parser) {
    DeckPolarisResult<T> result;
    result.status = raw.status;
    result.httpStatus = raw.httpStatus;
    result.detail = std::move(raw.detail);
    if (!raw.ok()) {
        return result;
    }
    auto value = parser(*raw.value);
    if (!value) {
        result.status = DeckPolarisRequestStatus::MalformedBody;
        result.detail = "the host answered with a body this client could not read";
        return result;
    }
    result.value = std::move(*value);
    return result;
}

} // namespace

DeckPolarisResult<DeckDoctorReceipt> DeckPolarisClient::runDoctorAction(const DeckDoctorRequest& action,
    const std::function<bool()>& cancelled) const {
    const auto body = doctorRequestBody(action);
    if (!body) return {DeckPolarisRequestStatus::MalformedBody, 0, {}, {}};
    const auto raw = request("/polaris/v1/doctor/action", 128 * 1024, true, cancelled,
        QJsonDocument(*body).toJson(QJsonDocument::Compact).toStdString());
    DeckPolarisResult<DeckDoctorReceipt> result{raw.status, raw.httpStatus, {}, {}};
    // A scoped rollback_unconfirmed is meaningful even though the host returns
    // HTTP 409. No redirect, generic error body or raw prose becomes a receipt.
    if (!raw.value || (raw.httpStatus != 200 && raw.httpStatus != 409)) return result;
    const auto object = parseObject(*raw.value);
    const auto receipt = object ? parseDoctorReceipt(*object, raw.httpStatus, action) : std::nullopt;
    if (!receipt) { result.status = DeckPolarisRequestStatus::MalformedBody; return result; }
    result.status = DeckPolarisRequestStatus::Ok; result.value = receipt; return result;
}

DeckPolarisResult<bool> DeckPolarisClient::setLiveTuningEnabled(bool enabled,
    const DeckLiveTuningTelemetry& observed, const std::function<bool()>& cancelled) const {
    // Validate the backend-only decision context again before serializing it.
    static const QRegularExpression revision("^[a-fA-F0-9]{64}$");
    if (observed.generation <= 0 || observed.generation > 9007199254740991LL || observed.appSession.trimmed().isEmpty() ||
        observed.appSession.size() > 256 || !revision.match(observed.revision).hasMatch())
        return {DeckPolarisRequestStatus::MalformedBody, 0, {}, {}};
    const QJsonObject body{{"enabled", enabled}, {"app_session_id", observed.appSession},
        {"session_generation", observed.generation}, {"configuration_revision", observed.revision}};
    auto raw = request("/polaris/v1/session/adaptive-bitrate", 128 * 1024, true, cancelled,
        QJsonDocument(body).toJson(QJsonDocument::Compact).toStdString());
    if (raw.ok() && raw.httpStatus != 200) { raw.status = DeckPolarisRequestStatus::HttpError; raw.value.reset(); }
    return parsed<bool>(std::move(raw), [](const std::string& json) -> std::optional<bool> {
        const auto object = parseObject(json);
        if (!object || !object->value("status").isBool()) return {};
        return object->value("status").toBool();
    });
}

DeckPolarisResult<bool> DeckPolarisClient::setFixedBitrate(int bitrateKbps,
    const DeckLiveTuningTelemetry& observed, const std::function<bool()>& cancelled) const {
    if (bitrateKbps < 1000 || bitrateKbps > 300000 || !observed.supported || observed.generation <= 0 ||
        observed.generation > 9007199254740991LL || observed.appSession.trimmed().isEmpty() || observed.appSession.size() > 256)
        return {DeckPolarisRequestStatus::MalformedBody, 0, {}, {}};
    // This route has session-generation admission, not a revision CAS. The
    // observer checks the reviewed configuration immediately before dispatch.
    const QJsonObject body{{"bitrate_kbps", bitrateKbps}, {"app_session_id", observed.appSession},
        {"session_generation", observed.generation}};
    auto raw = request("/polaris/v1/session/bitrate", 128 * 1024, true, cancelled,
        QJsonDocument(body).toJson(QJsonDocument::Compact).toStdString());
    if (raw.ok() && raw.httpStatus != 200) { raw.status = DeckPolarisRequestStatus::HttpError; raw.value.reset(); }
    return parsed<bool>(std::move(raw), [](const std::string& json) -> std::optional<bool> {
        const auto object = parseObject(json);
        if (!object || !object->value("status").isBool()) return {};
        // The receipt's bitrate_kbps is a controller target, not encoder proof.
        return object->value("status").toBool();
    });
}

DeckPolarisResult<DeckHostPower> DeckPolarisClient::fetchHostPower() const {
    return parsed<DeckHostPower>(get("/polaris/v1/host/power", 32 * 1024), parseHostPower);
}

DeckPolarisResult<DeckHostSettings> DeckPolarisClient::fetchHostSettings(const std::function<bool()>& cancelled) const {
    return parsed<DeckHostSettings>(request("/polaris/v1/client-settings", 2 * 1024 * 1024, false, cancelled), parseHostSettings);
}
DeckPolarisResult<bool> DeckPolarisClient::fetchHostSettingsIdle(const std::function<bool()>& cancelled) const {
    return parsed<bool>(request("/polaris/v1/session/status", 2 * 1024 * 1024, false, cancelled), parseHostSettingsIdle);
}
DeckPolarisResult<DeckHostSettings> DeckPolarisClient::setHostResumeTimeout(int seconds,
    const std::function<bool()>& cancelled) const {
    if (seconds < 0 || seconds > 86400) return {DeckPolarisRequestStatus::MalformedBody, 0, {}, {}};
    const auto body = QJsonDocument(QJsonObject{{"disconnect_resume_timeout_seconds", seconds}}).toJson(QJsonDocument::Compact).toStdString();
    auto raw = request("/polaris/v1/client-settings", 2 * 1024 * 1024, true, cancelled, body);
    if (raw.ok() && raw.httpStatus != 200) { raw.status = DeckPolarisRequestStatus::HttpError; raw.value.reset(); }
    return parsed<DeckHostSettings>(std::move(raw), parseHostSettings);
}
DeckPolarisResult<DeckHostSettings> DeckPolarisClient::setHostProfile(const QString& display, int bitrate, bool clear,
    const std::function<bool()>& cancelled) const {
    if (clear ? (!display.isEmpty() || bitrate != 0) : !validHostProfile(display, bitrate))
        return {DeckPolarisRequestStatus::MalformedBody, 0, {}, {}};
    const auto values = clear ? QJsonObject{{"clear_display_mode", true}, {"clear_target_bitrate", true}}
                              : QJsonObject{{"display_mode", display}, {"target_bitrate_kbps", bitrate}};
    const auto body = QJsonDocument(values).toJson(QJsonDocument::Compact).toStdString();
    auto raw = request("/polaris/v1/client-settings", 2 * 1024 * 1024, true, cancelled, body);
    if (raw.ok() && raw.httpStatus != 200) { raw.status = DeckPolarisRequestStatus::HttpError; raw.value.reset(); }
    return parsed<DeckHostSettings>(std::move(raw), parseHostSettings);
}
DeckPolarisResult<DeckHostSettings> DeckPolarisClient::setSessionProfile(const QString& display, int bitrate, bool clear,
    const DeckLiveTuningTelemetry& observed, const std::function<bool()>& cancelled) const {
    if ((clear ? (!display.isEmpty() || bitrate != 0) : (!validHostProfile(display, bitrate) || !observed.supported)) ||
        observed.generation <= 0 || observed.generation > 9007199254740991LL ||
        observed.appSession.trimmed().isEmpty() || observed.appSession.size() > 256)
        return {DeckPolarisRequestStatus::MalformedBody, 0, {}, {}};
    auto body = clear ? QJsonObject{{"clear_display_mode", true}, {"clear_target_bitrate", true}}
                      : QJsonObject{{"display_mode", display}, {"target_bitrate_kbps", bitrate}};
    body["app_session_id"] = observed.appSession;
    body["session_generation"] = observed.generation;
    auto raw = request("/polaris/v1/client-settings", 2 * 1024 * 1024, true, cancelled,
        QJsonDocument(body).toJson(QJsonDocument::Compact).toStdString());
    if (raw.ok() && raw.httpStatus != 200) { raw.status = DeckPolarisRequestStatus::HttpError; raw.value.reset(); }
    // This receipt confirms only the durable profile. Encoder proof is separate.
    return parsed<DeckHostSettings>(std::move(raw), parseHostSettings);
}
DeckPolarisResult<DeckHostSettings> DeckPolarisClient::setHostDefaultMode(const QString& mode,
    const std::function<bool()>& cancelled) const {
    if (mode.isEmpty() || normalizeLaunchMode(mode.toStdString()) != mode.toStdString())
        return {DeckPolarisRequestStatus::MalformedBody, 0, {}, {}};
    const auto body = QJsonDocument(QJsonObject{{"stream_display_mode", mode}}).toJson(QJsonDocument::Compact).toStdString();
    auto raw = request("/polaris/v1/client-settings", 2 * 1024 * 1024, true, cancelled, body);
    if (raw.ok() && raw.httpStatus != 200) { raw.status = DeckPolarisRequestStatus::HttpError; raw.value.reset(); }
    return parsed<DeckHostSettings>(std::move(raw), parseHostSettings);
}

DeckPolarisResult<DeckHostSleepReceipt> DeckPolarisClient::requestHostSleep(const std::function<bool()>& cancelled) const {
    const auto raw = request("/polaris/v1/host/sleep", 32 * 1024, true, cancelled);
    DeckPolarisResult<DeckHostSleepReceipt> result{raw.status, raw.httpStatus, raw.detail, {}};
    if (!raw.value) return result;
    const auto object = parseObject(*raw.value);
    if (!object) { if (raw.ok()) result.status = DeckPolarisRequestStatus::MalformedBody; return result; }
    result.value = DeckHostSleepReceipt{raw.ok() && raw.httpStatus == 200 && object->value("status").toBool(false),
        object->value("code").toString().left(64).toStdString(),
        object->value("error").toString().simplified().left(400).toStdString()};
    return result;
}

DeckPolarisResult<DeckSpaces> DeckPolarisClient::fetchSpaces(const std::function<bool()>& cancelled) const {
    auto raw = request("/polaris/v1/spaces", 2 * 1024 * 1024, false, cancelled);
    if (raw.ok() && raw.httpStatus != 200) { raw.status = DeckPolarisRequestStatus::HttpError; raw.value.reset(); }
    return parsed<DeckSpaces>(std::move(raw), parseSpaces);
}
DeckPolarisResult<DeckSpaces> DeckPolarisClient::selectSpace(const std::string& id, const std::string& previous,
    const std::function<bool()>& cancelled) const {
    if ((id != "desktop" && !validSpaceId(id)) || (previous != "desktop" && !validSpaceId(previous)))
        return {DeckPolarisRequestStatus::MalformedBody, 0, "invalid destination", {}};
    const auto body = QJsonDocument(QJsonObject{{"space_id", QString::fromStdString(id)},
        {"previous_space_id", QString::fromStdString(previous)}}).toJson(QJsonDocument::Compact).toStdString();
    auto raw = request("/polaris/v1/spaces/select", 2 * 1024 * 1024, true, cancelled, body);
    if (raw.ok() && raw.httpStatus != 200) { raw.status = DeckPolarisRequestStatus::HttpError; raw.value.reset(); }
    return parsed<DeckSpaces>(std::move(raw), parseSpaces);
}
DeckPolarisResult<std::vector<DeckPolarisGame>> DeckPolarisClient::fetchSpaceLibrary(const std::string& id,
    const std::function<bool()>& cancelled) const {
    if (!validSpaceId(id)) return {DeckPolarisRequestStatus::MalformedBody, 0, "invalid Space", {}};
    auto raw = request("/polaris/v1/spaces/library?space_id=" + id, 2 * 1024 * 1024, false, cancelled);
    if (raw.ok() && raw.httpStatus != 200) { raw.status = DeckPolarisRequestStatus::HttpError; raw.value.reset(); }
    return parsed<std::vector<DeckPolarisGame>>(std::move(raw), [&](const std::string& body) -> std::optional<std::vector<DeckPolarisGame>> {
        if (!uniqueJsonFields(body)) return {};
        const auto o = parseObject(body);
        if (!o || o->value("schema") != QJsonValue(1) || o->value("status") != QJsonValue(true) ||
            o->value("space_id").toString().toStdString() != id) return {};
        auto page = parseGamesPage(body);
        if (!page || page->games.size() > 4097 || page->games.size() != static_cast<std::size_t>(o->value("games").toArray().size())) return {};
        QSet<QString> ids;
        for (const auto& game : page->games) {
            const auto key = QString::fromStdString(game.id);
            if (game.spaceId != id || !spaceGameIdentity(game.id) || ids.contains(key)) return {};
            ids.insert(key);
        }
        return std::move(page->games);
    });
}

DeckPolarisResult<DeckPolarisCapabilities> DeckPolarisClient::fetchCapabilities() const {
    return parsed<DeckPolarisCapabilities>(get("/polaris/v1/capabilities"), [](const std::string& body) {
        return parseCapabilities(body);
    });
}

DeckPolarisResult<DeckPolarisGamesPage> DeckPolarisClient::fetchGamesPage(const int limit, const int offset, bool desktop) const {
    return parsed<DeckPolarisGamesPage>(
        get("/polaris/v1/games?limit=" + std::to_string(limit) + "&offset=" + std::to_string(offset) + (desktop ? "&environment=desktop" : "")),
        [](const std::string& body) {
            return parseGamesPage(body);
        });
}

DeckPolarisResult<std::vector<DeckPolarisGame>> DeckPolarisClient::fetchAllGames(const int pageSize,
    const std::function<bool()>& cancelled, bool desktop) const {
    DeckPolarisResult<std::vector<DeckPolarisGame>> result;
    std::vector<DeckPolarisGame> games;
    const int limit = pageSize > 0 ? pageSize : 100;
    constexpr int kMaxPages = 50;
    for (int page = 0; page < kMaxPages; ++page) {
        if (cancelled && cancelled()) { result.detail = "library read cancelled"; return result; }
        auto pageResult = fetchGamesPage(limit, static_cast<int>(games.size()), desktop);
        if (cancelled && cancelled()) { result.detail = "library read cancelled"; return result; }
        if (!pageResult.ok()) {
            result.status = pageResult.status;
            result.httpStatus = pageResult.httpStatus;
            result.detail = std::move(pageResult.detail);
            return result;
        }
        const auto fetched = pageResult.value->games.size();
        for (auto& game : pageResult.value->games) {
            games.push_back(std::move(game));
        }
        // Polaris reports `total` as the index it stopped at, which equals
        // offset + limit on a full page, so only a short page ends the walk.
        if (fetched < static_cast<std::size_t>(limit)) {
            break;
        }
    }
    result.status = DeckPolarisRequestStatus::Ok;
    result.value = std::move(games);
    return result;
}

DeckPolarisResult<DeckPolarisSessionStatus> DeckPolarisClient::fetchSessionStatus() const {
    return parsed<DeckPolarisSessionStatus>(get("/polaris/v1/session/status"), [](const std::string& body) {
        return parseSessionStatus(body);
    });
}

DeckPolarisResult<DeckHostTelemetry> DeckPolarisClient::fetchHostTelemetry(const std::function<bool()>& cancelled) const {
    auto raw = request("/polaris/v1/session/status", 128 * 1024, false, cancelled);
    if (raw.ok() && raw.httpStatus != 200) { raw.status = DeckPolarisRequestStatus::HttpError; raw.value.reset(); }
    return parsed<DeckHostTelemetry>(std::move(raw), parseHostTelemetry);
}

DeckPolarisResult<QVariantMap> DeckPolarisClient::gameTool(const QString& game, const QString& action,
    const QVariantMap& values, const std::function<bool()>& cancelled) const {
    const auto spec = gameToolRequest(game, action, values);
    if (!spec) return {DeckPolarisRequestStatus::MalformedBody, 0, "invalid game action", {}};
    const auto raw = request(spec->path, 2 * 1024 * 1024, spec->method != "GET", cancelled, spec->body, spec->method == "DELETE");
    if (!raw.ok()) return {raw.status, raw.httpStatus, "game action unavailable", {}};
    const auto parsed = gameToolReply(game, action, values, *raw.value);
    return {parsed ? DeckPolarisRequestStatus::Ok : DeckPolarisRequestStatus::MalformedBody,
        raw.httpStatus, "game action response", parsed};
}

} // namespace nova::deck::polaris
