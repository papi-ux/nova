#include "polaris/deck_polaris_client.h"

#include <QByteArray>
#include <QEventLoop>
#include <QJsonArray>
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
#include <QUrl>

#include <memory>
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
    game.steamAppid = toStd(object.value(QStringLiteral("steam_appid")).toString());
    game.category = toStd(object.value(QStringLiteral("category")).toString());
    game.coverUrl = toStd(object.value(QStringLiteral("cover_url")).toString());
    game.installed = object.value(QStringLiteral("installed")).toBool(true);
    game.hdrSupported = object.value(QStringLiteral("hdr_supported")).toBool(false);
    game.lastLaunched = static_cast<long long>(object.value(QStringLiteral("last_launched")).toDouble(0));
    game.genres = stringList(object.value(QStringLiteral("genres")));

    const auto launchMode = object.value(QStringLiteral("launch_mode")).toObject();
    game.launchPreferredMode = toStd(launchMode.value(QStringLiteral("preferred_mode")).toString());
    game.launchRecommendedMode = toStd(launchMode.value(QStringLiteral("recommended_mode")).toString());
    game.launchAllowedModes = stringList(launchMode.value(QStringLiteral("allowed_modes")));
    game.launchModeReason = toStd(launchMode.value(QStringLiteral("mode_reason")).toString());

    const auto steamLaunch = object.value(QStringLiteral("steam_launch")).toObject();
    game.steamLaunchAvailable = steamLaunch.value(QStringLiteral("available")).toBool(false);
    game.steamLaunchMode = toStd(steamLaunch.value(QStringLiteral("mode")).toString());
    game.steamLaunchRecommendedMode = toStd(steamLaunch.value(QStringLiteral("recommended_mode")).toString());
    game.steamLaunchAllowedModes = stringList(steamLaunch.value(QStringLiteral("allowed_modes")));
    game.steamLaunchModeReason = toStd(steamLaunch.value(QStringLiteral("mode_reason")).toString());
    return game;
}

bool sameCertificate(const QSslCertificate& peer, const QSslCertificate& pinned) {
    return !peer.isNull() && !pinned.isNull() && peer.toDer() == pinned.toDer();
}

} // namespace

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
    const auto capture = object->value(QStringLiteral("capture")).toObject();
    capabilities.captureBackend = toStd(capture.value(QStringLiteral("backend")).toString());
    capabilities.codecs = stringList(capture.value(QStringLiteral("codecs")));
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
        auto game = parseGame(entry.toObject());
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

std::optional<int> resolveHttpsPortFromServerInfo(const std::string& address, const int httpPort, const std::chrono::milliseconds timeout) {
    if (address.empty() || httpPort <= 0) {
        return std::nullopt;
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
    const bool ok = reply->error() == QNetworkReply::NoError;
    reply->deleteLater();
    if (!ok) {
        return std::nullopt;
    }
    return parseServerInfoHttpsPort(std::string_view(body.constData(), static_cast<std::size_t>(body.size())));
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

DeckPolarisResult<std::string> DeckPolarisClient::get(const std::string& path) const {
    DeckPolarisResult<std::string> result;
    if (!session_->usable() || endpoint_.address.empty()) {
        result.status = DeckPolarisRequestStatus::InvalidIdentity;
        result.detail = "client certificate, private key, pinned server certificate and address are all required";
        return result;
    }

    QUrl url;
    url.setScheme(QStringLiteral("https"));
    url.setHost(QString::fromStdString(endpoint_.address));
    url.setPort(endpoint_.httpsPort);
    url.setPath(QString::fromStdString(path));

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
    QNetworkReply* reply = session_->manager.get(request);
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
    QObject::connect(reply, &QNetworkReply::finished, &loop, &QEventLoop::quit);
    loop.exec();

    const auto peer = reply->sslConfiguration().peerCertificate();
    const auto networkError = reply->error();
    result.httpStatus = reply->attribute(QNetworkRequest::HttpStatusCodeAttribute).toInt();
    const auto body = reply->readAll();
    reply->deleteLater();

    if (foreignCertificateSeen || (!peer.isNull() && !sameCertificate(peer, pinned))) {
        result.status = DeckPolarisRequestStatus::CertMismatch;
        result.detail = "the host presented a certificate that is not the one Moonlight pinned at pairing";
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

DeckPolarisResult<DeckPolarisCapabilities> DeckPolarisClient::fetchCapabilities() const {
    return parsed<DeckPolarisCapabilities>(get("/polaris/v1/capabilities"), [](const std::string& body) {
        return parseCapabilities(body);
    });
}

DeckPolarisResult<DeckPolarisGamesPage> DeckPolarisClient::fetchGamesPage(const int limit, const int offset) const {
    return parsed<DeckPolarisGamesPage>(
        get("/polaris/v1/games?limit=" + std::to_string(limit) + "&offset=" + std::to_string(offset)),
        [](const std::string& body) {
            return parseGamesPage(body);
        });
}

DeckPolarisResult<std::vector<DeckPolarisGame>> DeckPolarisClient::fetchAllGames(const int pageSize) const {
    DeckPolarisResult<std::vector<DeckPolarisGame>> result;
    std::vector<DeckPolarisGame> games;
    const int limit = pageSize > 0 ? pageSize : 100;
    constexpr int kMaxPages = 50;
    for (int page = 0; page < kMaxPages; ++page) {
        auto pageResult = fetchGamesPage(limit, static_cast<int>(games.size()));
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

} // namespace nova::deck::polaris
