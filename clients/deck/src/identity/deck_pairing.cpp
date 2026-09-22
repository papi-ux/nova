#include "identity/deck_pairing.h"

#include <QEventLoop>
#include <QHostAddress>
#include <QNetworkAccessManager>
#include <QNetworkProxy>
#include <QNetworkReply>
#include <QRegularExpression>
#include <QSet>
#include <QSslError>
#include <QSslCertificate>
#include <QSslConfiguration>
#include <QSslKey>
#include <QSslSocket>
#include <QTimer>
#include <QUuid>
#include <QXmlStreamReader>

namespace nova::deck::identity {
namespace {
constexpr int maxReplyBytes = 65536;
using Fields = QMap<QString, QString>;
std::optional<Fields> fields(const QByteArray& bytes) {
    if (bytes.size() > maxReplyBytes) return std::nullopt;
    QXmlStreamReader reader(bytes);
    Fields values;
    bool root = false;
    int depth = 0;
    while (!reader.atEnd()) {
        const auto token = reader.readNext();
        if (token == QXmlStreamReader::DTD || token == QXmlStreamReader::EntityReference) return std::nullopt;
        if (token == QXmlStreamReader::StartElement) {
            ++depth;
            if (depth == 1) {
                if (root || reader.name() != "root") return std::nullopt;
                root = true;
                values["status_code"] = reader.attributes().value("status_code").toString();
            } else if (depth == 2) {
                const auto name = reader.name().toString();
                static const QSet<QString> wanted{"uniqueid", "hostname", "appversion", "HttpsPort", "PairStatus",
                    "paired", "plaincert", "challengeresponse", "pairingsecret", "TofuEnabled"};
                if (wanted.contains(name)) {
                    if (values.contains(name)) return std::nullopt;
                    values[name] = reader.readElementText(QXmlStreamReader::ErrorOnUnexpectedElement);
                } else reader.skipCurrentElement();
                --depth;
            }
        } else if (token == QXmlStreamReader::EndElement) --depth;
    }
    return root && !reader.hasError() && depth == 0 ? std::optional(values) : std::nullopt;
}
QByteArray hex(const QString& text, int exactSize = -1) {
    if (text.isEmpty() || text.size() % 2 != 0 || text.size() > maxReplyBytes ||
        (exactSize >= 0 && text.size() != exactSize * 2)) return {};
    for (const auto c : text) {
        if (!((c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F'))) return {};
    }
    return QByteArray::fromHex(text.toLatin1());
}
}

QString pairingCopy(PairStatus status) {
    switch (status) {
    case PairStatus::Ok: return "PC paired with Nova. Open your library to choose a game.";
    case PairStatus::Cancelled: return "Pairing cancelled.";
    case PairStatus::Timeout: return "Pairing timed out. Check the host, then try again with a new PIN.";
    case PairStatus::Unreachable: return "Could not reach that PC. Check its address and that streaming is enabled.";
    case PairStatus::CertificateMismatch: return "The PC's certificate did not match. Nova did not trust the connection.";
    case PairStatus::Rejected: return "The host did not approve pairing. Check its pairing settings and try again.";
    case PairStatus::Malformed: return "The host sent an invalid pairing response.";
    case PairStatus::WrongPin: return "The PIN did not match. Try again with a new PIN.";
    case PairStatus::Unsupported: return "This host uses an older pairing protocol that Nova's Deck preview does not support yet.";
    case PairStatus::AlreadySaved: return "This PC is already saved in Nova. Its existing certificate has been kept.";
    case PairStatus::StoreFailed: return "Nova could not safely read or save its pairing. Existing credentials have been kept.";
    case PairStatus::TrustedUnavailable: return "Trusted Pair is not available from this network. Use PIN pairing instead.";
    }
    return "Pairing failed.";
}

std::optional<PairEndpoint> pairingEndpoint(const QString& input, int port) {
    const QString address = input.trimmed();
    if (address.isEmpty() || address.size() > 253 || port < 1 || port > 65535) return std::nullopt;
    QHostAddress literal;
    if (literal.setAddress(address)) {
        if (literal.isNull() || literal.isMulticast() || literal == QHostAddress::AnyIPv4 || literal == QHostAddress::AnyIPv6)
            return std::nullopt;
        return PairEndpoint{literal.toString(), port};
    }
    static const QRegularExpression hostname("^(?=.{1,253}$)[A-Za-z0-9](?:[A-Za-z0-9.-]*[A-Za-z0-9])?\\.?$");
    if (!hostname.match(address).hasMatch()) return std::nullopt;
    for (const auto& label : address.chopped(address.endsWith('.') ? 1 : 0).split('.')) {
        if (label.isEmpty() || label.size() > 63 || label.startsWith('-') || label.endsWith('-')) return std::nullopt;
    }
    return PairEndpoint{address.toLower(), port};
}

QString generatePairingPin() {
    // Rejection sampling avoids bias across the 10,000 possible display PINs.
    for (;;) {
        const auto bytes = crypto::random(2);
        if (bytes.size() != 2) return {};
        const auto value = (static_cast<unsigned char>(bytes[0]) << 8) | static_cast<unsigned char>(bytes[1]);
        if (value < 60000) return QString::number(value % 10000).rightJustified(4, '0');
    }
}

PairTransport pairingNetworkTransport(crypto::Credentials credentials, PairCancelled cancelled) {
    return [credentials = std::move(credentials), cancelled = std::move(cancelled)](const PairRequest& input) {
        PairReply result;
        if (!input.cleanup && cancelled && cancelled()) return PairReply{PairStatus::Cancelled};
        QUrl url;
        url.setScheme(input.tls ? "https" : "http");
        url.setHost(input.endpoint.address);
        url.setPort(input.tls ? input.httpsPort : input.endpoint.httpPort);
        url.setPath(input.path);
        url.setQuery(input.query);
        QNetworkRequest request(url);
        request.setAttribute(QNetworkRequest::RedirectPolicyAttribute, QNetworkRequest::ManualRedirectPolicy);
        request.setAttribute(QNetworkRequest::Http2AllowedAttribute, false);
        QSslCertificate pin;
        if (input.tls) {
            pin = QSslCertificate(input.serverCertificate);
            if (pin.isNull()) return PairReply{PairStatus::CertificateMismatch};
            auto ssl = QSslConfiguration::defaultConfiguration();
            ssl.setCaCertificates({pin});
            ssl.setPeerVerifyMode(QSslSocket::VerifyPeer);
            ssl.setLocalCertificate(QSslCertificate(credentials.certificate));
            ssl.setPrivateKey(QSslKey(credentials.privateKey, QSsl::Rsa));
            request.setSslConfiguration(ssl);
        }
        // One manager per request guarantees encrypted() runs for each pinned
        // handshake, without pooled sessions skipping certificate admission.
        QNetworkAccessManager manager;
        manager.setProxy(QNetworkProxy::NoProxy);
        auto* reply = manager.get(request);
        reply->setReadBufferSize(maxReplyBytes + 1);
        bool mismatch = false, timeout = false, wasCancelled = false, tooLarge = false;
        bool pinChecked = !input.tls;
        QObject::connect(reply, &QNetworkReply::sslErrors, reply, [&](const QList<QSslError>& errors) {
            QList<QSslError> accepted;
            for (const auto& error : errors) {
                if (error.certificate().toDer() == pin.toDer() &&
                    (error.error() == QSslError::SelfSignedCertificate || error.error() == QSslError::HostNameMismatch ||
                     error.error() == QSslError::CertificateUntrusted)) accepted.push_back(error);
                else mismatch = true;
            }
            if (!mismatch) reply->ignoreSslErrors(accepted);
        });
        QObject::connect(reply, &QNetworkReply::encrypted, reply, [&] {
            pinChecked = reply->sslConfiguration().peerCertificate().toDer() == pin.toDer();
            if (!pinChecked) { mismatch = true; reply->abort(); }
        });
        const auto consume = [&] {
            if (!reply->isOpen()) return;
            result.body += reply->read(maxReplyBytes + 1 - result.body.size());
            if (result.body.size() > maxReplyBytes) { tooLarge = true; reply->abort(); }
        };
        QObject::connect(reply, &QNetworkReply::readyRead, reply, consume);
        QEventLoop loop;
        QTimer deadline, cancellation;
        deadline.setSingleShot(true);
        QObject::connect(&deadline, &QTimer::timeout, reply, [&] { timeout = true; reply->abort(); });
        QObject::connect(&cancellation, &QTimer::timeout, reply, [&] {
            if (!input.cleanup && cancelled && cancelled()) { wasCancelled = true; reply->abort(); }
        });
        QObject::connect(reply, &QNetworkReply::finished, &loop, &QEventLoop::quit);
        deadline.start(input.timeoutMs);
        cancellation.start(20);
        if (!reply->isFinished()) loop.exec();
        consume();
        if (wasCancelled) result.status = PairStatus::Cancelled;
        else if (timeout) result.status = PairStatus::Timeout;
        else if (mismatch) result.status = PairStatus::CertificateMismatch;
        else if (tooLarge) result.status = PairStatus::Malformed;
        else if (const int status = reply->attribute(QNetworkRequest::HttpStatusCodeAttribute).toInt(); status != 200)
            result.status = status > 0 ? PairStatus::Rejected : PairStatus::Unreachable;
        else if (!pinChecked || reply->error() != QNetworkReply::NoError) result.status = PairStatus::Unreachable;
        else result.status = PairStatus::Ok;
        return result;
    };
}

PairResult pairHost(const PairEndpoint& endpoint, const crypto::Credentials& credentials,
                    const QString& pin, const std::vector<DeckMoonlightHostRecord>& savedHosts,
                    const PairTransport& transport, const PairCancelled& cancelled,
                    const std::function<bool(const DeckMoonlightHostRecord&)>& commit, PairMode mode) {
    PairResult result;
    if (!pairingEndpoint(endpoint.address, endpoint.httpPort) || !crypto::valid(credentials) ||
        !QRegularExpression("^[0-9]{4}$").match(pin).hasMatch() || !transport ||
        (mode == PairMode::Trusted && pin != "0000")) return {PairStatus::Malformed};
    for (const auto& saved : savedHosts) {
        if (QString::fromStdString(saved.preferredAddress()).compare(endpoint.address, Qt::CaseInsensitive) == 0 &&
            saved.preferredHttpPort() == endpoint.httpPort) return {PairStatus::AlreadySaved};
    }
    const QString transactionId = QUuid::createUuid().toString(QUuid::WithoutBraces);
    PairRequest request;
    request.endpoint = endpoint;
    bool started = false, mayBeAuthorized = false, authenticatedServer = false;
    auto query = [&](const QString& path, QUrlQuery args = {}) {
        request.path = path;
        request.query = std::move(args);
        request.query.addQueryItem("uniqueid", transactionId);
        request.query.addQueryItem("devicename", "Nova Deck");
        request.query.addQueryItem("uuid", QUuid::createUuid().toString(QUuid::WithoutBraces));
        return transport(request);
    };
    const auto failed = [&](PairStatus status) {
        result.status = status;
        if (started) {
            request.cleanup = true;
            request.timeoutMs = 2000;
            request.tls = mayBeAuthorized && authenticatedServer;
            const auto cleanup = query("/unpair");
            const auto cleanupFields = fields(cleanup.body);
            result.authorizationMayRemain = mayBeAuthorized &&
                (cleanup.status != PairStatus::Ok || !cleanupFields || cleanupFields->value("status_code") != "200" ||
                 cleanupFields->value("paired") != "0");
        }
        return result;
    };
    const auto fetchFields = [&](const QString& path, QUrlQuery args = {}) -> std::optional<Fields> {
        if (cancelled && cancelled()) { result.status = PairStatus::Cancelled; return std::nullopt; }
        const auto reply = query(path, std::move(args));
        result.status = reply.status;
        if (reply.status != PairStatus::Ok) return std::nullopt;
        auto parsed = fields(reply.body);
        if (!parsed) result.status = PairStatus::Malformed;
        else if (parsed->value("status_code") != "200" || (path == "/pair" && parsed->value("paired") != "1")) {
            result.status = PairStatus::Rejected;
            return std::nullopt;
        }
        return parsed;
    };
    const auto server = fetchFields("/serverinfo");
    if (!server) return failed(result.status);
    const QString id = server->value("uniqueid");
    bool portOk = false, versionOk = false;
    request.httpsPort = server->value("HttpsPort").toInt(&portOk);
    const int generation = server->value("appversion").section('.', 0, 0).toInt(&versionOk);
    if (id.isEmpty() || id.size() > 128 || !portOk || request.httpsPort < 1 || request.httpsPort > 65535 || !versionOk)
        return failed(PairStatus::Malformed);
    if (generation < 7) return failed(PairStatus::Unsupported);
    for (const auto& saved : savedHosts) {
        if (saved.uuid == id.toStdString() || (QString::fromStdString(saved.preferredAddress()).compare(
            endpoint.address, Qt::CaseInsensitive) == 0 && saved.preferredHttpPort() == endpoint.httpPort))
            return failed(PairStatus::AlreadySaved);
    }
    // Polaris advertises this only when trusted-subnet pairing is enabled for
    // this peer. The hint selects a protocol; it never replaces the signed
    // challenge, exact TLS certificate pin or authenticated host confirmation.
    if (mode == PairMode::Trusted) {
        const auto advertised = server->value("TofuEnabled");
        if (server->contains("TofuEnabled") && advertised != "0" && advertised != "1")
            return failed(PairStatus::Malformed);
        if (advertised != "1") return failed(PairStatus::TrustedUnavailable);
    }
    const auto salt = crypto::random(16), challenge = crypto::random(16), secret = crypto::random(16);
    const auto key = crypto::sha256(salt + pin.toLatin1()).left(16);
    if (salt.size() != 16 || challenge.size() != 16 || secret.size() != 16 || key.size() != 16)
        return failed(PairStatus::Malformed);
    request.timeoutMs = mode == PairMode::Trusted ? 5000 : 120000;
    started = true;
    QUrlQuery certificateQuery{{"phrase", "getservercert"}, {"salt", salt.toHex()},
        {"clientcert", credentials.certificate.toHex()}, {"updateState", "1"}};
    if (mode == PairMode::Trusted) certificateQuery.addQueryItem("trustedpair", "1");
    const auto certificate = fetchFields("/pair", certificateQuery);
    if (!certificate) return failed(result.status);
    request.serverCertificate = hex(certificate->value("plaincert"));
    const auto serverSignature = crypto::certificateSignature(request.serverCertificate);
    if (serverSignature.isEmpty()) return failed(PairStatus::Malformed);
    // A saved PC can be reached through another address, and the initial HTTP
    // UUID is unauthenticated. Never let that alias initiate authorization (or
    // later TLS rollback) against an identity we already have saved.
    const auto candidateDer = crypto::certificateDer(request.serverCertificate);
    for (const auto& saved : savedHosts) {
        if (crypto::equal(candidateDer, crypto::certificateDer(QByteArray::fromStdString(saved.serverCertificatePem))))
            return failed(PairStatus::AlreadySaved);
    }
    request.timeoutMs = 5000;
    const auto response = fetchFields("/pair", {{"clientchallenge", crypto::aes(challenge, key, true).toHex()}});
    if (!response) return failed(result.status);
    const auto decoded = crypto::aes(hex(response->value("challengeresponse"), 48), key, false);
    if (decoded.size() != 48) return failed(PairStatus::Malformed);
    const auto clientHash = crypto::sha256(decoded.mid(32, 16) + crypto::certificateSignature(credentials.certificate) + secret);
    const auto secretReply = fetchFields("/pair", {{"serverchallengeresp", crypto::aes(clientHash, key, true).toHex()}});
    if (!secretReply) return failed(result.status);
    const auto serverSecret = hex(secretReply->value("pairingsecret"));
    if (serverSecret.size() <= 16 || !crypto::verify(serverSecret.first(16), serverSecret.sliced(16), request.serverCertificate))
        return failed(PairStatus::CertificateMismatch);
    if (!crypto::equal(decoded.first(32), crypto::sha256(challenge + serverSignature + serverSecret.first(16))))
        return failed(PairStatus::WrongPin);
    authenticatedServer = true;
    const auto signature = crypto::sign(secret, credentials.privateKey);
    if (signature.isEmpty()) return failed(PairStatus::Malformed);
    // From this point a lost response may still mean the host accepted us.
    mayBeAuthorized = true;
    const auto accepted = fetchFields("/pair", {{"clientpairingsecret", (secret + signature).toHex()}});
    if (!accepted) return failed(result.status);
    request.tls = true;
    if (!fetchFields("/pair", {{"phrase", "pairchallenge"}, {"updateState", "1"}})) return failed(result.status);
    const auto confirmed = fetchFields("/serverinfo");
    if (!confirmed) return failed(result.status);
    if (confirmed->value("uniqueid") != id || confirmed->value("PairStatus") != "1") return failed(PairStatus::Rejected);
    if (cancelled && cancelled()) return failed(PairStatus::Cancelled);
    DeckMoonlightHostRecord host;
    host.uuid = id.toStdString();
    host.hostname = confirmed->value("hostname").left(256).toStdString();
    host.manualAddress = endpoint.address.toStdString();
    host.manualPort = endpoint.httpPort;
    host.nativeHttpsPort = request.httpsPort;
    host.serverCertificatePem = request.serverCertificate.toStdString();
    if (commit && !commit(host)) return failed(PairStatus::StoreFailed);
    result.status = PairStatus::Ok;
    result.host = std::move(host);
    return result;
}

UnpairResult unpairHost(const DeckMoonlightHostRecord& host, const PairTransport& transport,
                        const PairCancelled& cancelled) {
    const auto endpoint = pairingEndpoint(QString::fromStdString(host.preferredAddress()), host.preferredHttpPort());
    if (!endpoint || host.uuid.empty() || !transport ||
        crypto::certificateDer(QByteArray::fromStdString(host.serverCertificatePem)).isEmpty()) return {PairStatus::Malformed};
    PairRequest request;
    request.endpoint = *endpoint;
    request.tls = true;
    request.httpsPort = host.nativeHttpsPort > 0 ? host.nativeHttpsPort : polarisHttpsPortForMoonlightHttpPort(endpoint->httpPort);
    if (request.httpsPort < 1 || request.httpsPort > 65535) return {PairStatus::Malformed};
    request.serverCertificate = QByteArray::fromStdString(host.serverCertificatePem);
    request.query = QUrlQuery{{"uniqueid", QUuid::createUuid().toString(QUuid::WithoutBraces)}, {"devicename", "Nova Deck"}};
    request.path = "/serverinfo";
    if (cancelled && cancelled()) return {PairStatus::Cancelled};
    const auto info = transport(request);
    if (info.status != PairStatus::Ok) return {info.status};
    const auto verified = fields(info.body);
    if (!verified) return {PairStatus::Malformed};
    if (verified->value("status_code") != "200" || verified->value("uniqueid").toStdString() != host.uuid)
        return {PairStatus::Rejected};
    // A pinned host may already report this client as unpaired.
    if (verified->value("PairStatus") == "0") return {PairStatus::Ok};
    if (verified->value("PairStatus") != "1") return {PairStatus::Malformed};
    if (cancelled && cancelled()) return {PairStatus::Cancelled};
    request.path = "/unpair";
    request.query.addQueryItem("uuid", QUuid::createUuid().toString(QUuid::WithoutBraces));
    const auto reply = transport(request);
    if (reply.status != PairStatus::Ok) return {reply.status, true};
    const auto confirmation = fields(reply.body);
    if (!confirmation) return {PairStatus::Malformed, true};
    if (confirmation->value("status_code") != "200" || confirmation->value("paired") != "0")
        return {PairStatus::Rejected, true};
    return {PairStatus::Ok, true};
}
} // namespace nova::deck::identity
