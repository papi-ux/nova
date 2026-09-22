#include "identity/deck_pairing.h"
#include "runtime/deck_pairing_controller.h"
#include "backend/deck_live_read_only_state.h"

#include <QCoreApplication>
#include <QElapsedTimer>
#include <QFile>
#include <QJsonDocument>
#include <QSslSocket>
#include <QSslKey>
#include <QSslConfiguration>
#include <QTcpServer>
#include <QTemporaryDir>
#include <QThread>
#include <QTimer>

#include <atomic>
#include <cstdlib>
#include <iostream>
#include <fcntl.h>
#include <sys/file.h>
#include <sys/stat.h>
#include <unistd.h>

using namespace nova::deck::identity;
namespace {
void require(bool ok, const char* message) {
    if (!ok) { std::cerr << message << '\n'; std::exit(1); }
}
QByteArray xml(const QByteArray& body) { return "<root status_code=\"200\">" + body + "</root>"; }
QByteArray element(const char* name, const QByteArray& body) {
    return QByteArray("<") + name + ">" + body + "</" + name + ">";
}
crypto::Credentials credentials() {
    auto result = crypto::generate();
    require(result.has_value(), "credential generation failed");
    return *result;
}

// Independent host-side transcript: it knows only the typed PIN and wire
// requests, and verifies the client's challenge/hash/signature before pairing.
struct Peer {
    crypto::Credentials server;
    QString pin = "1234";
    QString id = "fixture-host";
    int httpsPort = 47984;
    QByteArray clientCertificate, key, clientHash;
    QByteArray serverChallenge = QByteArray::fromHex("11223344556677889900aabbccddeeff");
    QByteArray serverSecret = QByteArray::fromHex("ffeeddccbbaa00998877665544332211");
    QByteArray responseHash;
    QString transaction;
    int step = 0, cleanupCount = 0, requests = 0;
    bool authorized = false, tamperSignature = false, malformedChallenge = false, wrongAuthenticatedId = false;
    bool denyCleanup = false, httpCleanup = false, tlsCleanup = false;
    bool trusted = false;
    QByteArray tofu;
    explicit Peer(crypto::Credentials server) : server(std::move(server)) {}

    PairReply handle(const PairRequest& request) {
        ++requests;
        const auto& query = request.query;
        const auto currentId = query.queryItemValue("uniqueid");
        require(!currentId.isEmpty(), "pairing requests need a transaction ID");
        if (transaction.isEmpty()) transaction = currentId;
        require(transaction == currentId, "pairing transaction changed mid-handshake");
        const bool trustedRequest = trusted && request.path == "/pair" && query.queryItemValue("phrase") == "getservercert";
        require(!query.hasQueryItem("pin"), "PIN leaked to query");
        require(trustedRequest ? query.queryItemValue("trustedpair") == "1" : !query.hasQueryItem("trustedpair"),
                "trusted opt-in missing or sent outside its certificate request");
        if (request.path == "/unpair") {
            ++cleanupCount;
            httpCleanup = !request.tls;
            tlsCleanup = request.tls;
            if (denyCleanup) return {PairStatus::Unreachable};
            if (request.tls) authorized = false;
            return {PairStatus::Ok, xml("<paired>0</paired>")};
        }
        if (request.path == "/serverinfo") {
            return {PairStatus::Ok, xml(element("uniqueid", (request.tls && wrongAuthenticatedId ? "other-host" : id).toUtf8()) +
                "<hostname>Local test PC</hostname><appversion>7.1.431.0</appversion>" +
                element("HttpsPort", QByteArray::number(httpsPort)) + element("PairStatus", authorized && request.tls ? "1" : "0") +
                (tofu.isEmpty() ? QByteArray{} : element("TofuEnabled", tofu)))};
        }
        require(request.path == "/pair", "unexpected pairing path");
        QByteArray reply = "<paired>1</paired>";
        if (query.queryItemValue("phrase") == "getservercert") {
            require(step++ == 0 && !request.tls, "getservercert out of order");
            const auto salt = QByteArray::fromHex(query.queryItemValue("salt").toLatin1());
            require(salt.size() == 16, "salt must be 16 bytes");
            key = crypto::sha256(salt + pin.toLatin1()).first(16);
            clientCertificate = QByteArray::fromHex(query.queryItemValue("clientcert").toLatin1());
            require(!crypto::certificateDer(clientCertificate).isEmpty(), "invalid client PEM on wire");
            reply += element("plaincert", server.certificate.toHex());
        } else if (query.hasQueryItem("clientchallenge")) {
            require(step++ == 1 && !request.tls, "clientchallenge out of order");
            const auto challenge = crypto::aes(QByteArray::fromHex(query.queryItemValue("clientchallenge").toLatin1()), key, false);
            require(challenge.size() == 16, "challenge must be one AES block");
            responseHash = crypto::sha256(challenge + crypto::certificateSignature(server.certificate) + serverSecret);
            reply += element("challengeresponse", malformedChallenge ? QByteArray("zz00") :
                crypto::aes(responseHash + serverChallenge, key, true).toHex());
        } else if (query.hasQueryItem("serverchallengeresp")) {
            require(step++ == 2 && !request.tls, "serverchallengeresp out of order");
            clientHash = crypto::aes(QByteArray::fromHex(query.queryItemValue("serverchallengeresp").toLatin1()), key, false);
            auto signature = crypto::sign(serverSecret, server.privateKey);
            if (tamperSignature) signature[0] ^= 1;
            reply += element("pairingsecret", (serverSecret + signature).toHex());
        } else if (query.hasQueryItem("clientpairingsecret")) {
            require(step++ == 3 && !request.tls, "clientpairingsecret out of order");
            const auto secret = QByteArray::fromHex(query.queryItemValue("clientpairingsecret").toLatin1());
            require(secret.size() == 272, "client RSA secret must include 16-byte secret and 256-byte signature");
            require(crypto::equal(clientHash, crypto::sha256(serverChallenge + crypto::certificateSignature(clientCertificate) + secret.first(16))),
                    "client challenge response does not match the host transcript");
            require(crypto::verify(secret.first(16), secret.sliced(16), clientCertificate), "client secret signature is invalid");
            authorized = true;
        } else if (query.queryItemValue("phrase") == "pairchallenge") {
            require(step++ == 4 && request.tls && authorized, "final confirmation must use mTLS after authorization");
            require(crypto::equal(crypto::certificateDer(request.serverCertificate), crypto::certificateDer(server.certificate)),
                    "confirmation must pin the PIN-authenticated certificate");
        } else require(false, "unexpected pairing command");
        return {PairStatus::Ok, xml(reply)};
    }
};

void testCryptoAndStore(const crypto::Credentials& client, const crypto::Credentials& server) {
    const auto key = QByteArray::fromHex("000102030405060708090a0b0c0d0e0f");
    const auto plain = QByteArray::fromHex("00112233445566778899aabbccddeeff");
    const auto cipher = QByteArray::fromHex("69c4e0d86a7b0430d8cdb78070b4c55a");
    require(crypto::aes(plain, key, true) == cipher && crypto::aes(cipher, key, false) == plain, "NIST AES-128 known-answer failed");
    require(crypto::sha256("abc").toHex() == "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", "SHA-256 known-answer failed");
    require(crypto::aes("bad-size", key, true).isEmpty(), "AES must reject partial blocks");
    require(crypto::valid(client) && !crypto::valid({client.certificate, server.privateKey}), "mismatched identity accepted");
    require(!crypto::verify("different", crypto::sign("secret", client.privateKey), client.certificate), "tampered signature accepted");
    require(pairingEndpoint("pc.example", 47989).has_value() && pairingEndpoint("::1", 47989).has_value(), "valid endpoint rejected");
    for (const auto input : {"http://pc", "pc/path", "pc?query", "user@pc", "bad host", "-host", "host..name", "224.0.0.1"})
        require(!pairingEndpoint(input, 47989), "unsafe endpoint accepted");
    require(!pairingEndpoint("pc", 0) && !pairingEndpoint("pc", 65536), "invalid port accepted");

    QTemporaryDir temporary;
    require(temporary.isValid(), "temporary directory failed");
    const auto directory = temporary.path() + "/nova";
    require(loadNativeIdentity(directory).status == NativeStoreStatus::Missing, "missing store not distinguished");
    auto first = initializeNativeIdentity(directory);
    require(first.ok() && first.identity->hosts.empty(), "native identity initialization failed");
    auto again = initializeNativeIdentity(directory);
    require(again.ok() && again.identity->clientCertificatePem == first.identity->clientCertificatePem, "identity rotated on restart");
    struct stat info{};
    require(stat(QFile::encodeName(directory).constData(), &info) == 0 && (info.st_mode & 0777) == 0700, "store directory is not private");
    require(stat(QFile::encodeName(directory + "/identity.json").constData(), &info) == 0 && (info.st_mode & 0777) == 0600, "identity file is not private");
    auto lease = lockNativePairing(directory);
    require(lease && !lockNativePairing(directory), "concurrent pairing lease accepted");
    lease.reset();
    require(lockNativePairing(directory) != nullptr, "pairing lease was not released");
    const crypto::Credentials stored{QByteArray::fromStdString(first.identity->clientCertificatePem),
        QByteArray::fromStdString(first.identity->clientPrivateKeyPemForBackendOnly)};
    DeckMoonlightHostRecord host;
    host.uuid = "test-host"; host.hostname = "Local test PC"; host.manualAddress = "127.0.0.1"; host.manualPort = 47989;
    host.serverCertificatePem = server.certificate.toStdString();
    require(saveNativeHost(directory, stored, host).ok(), "confirmed host could not be saved");
    // Artwork, library refresh and launch may validate the same identity on
    // different workers. Readers can coexist, but no reader may overlap RMW.
    const int guard = open(QFile::encodeName(directory).constData(), O_RDONLY | O_DIRECTORY | O_CLOEXEC);
    require(guard >= 0 && flock(guard, LOCK_SH | LOCK_NB) == 0, "cannot hold reader fixture lock");
    const auto concurrentRead = loadNativeIdentity(directory);
    require(concurrentRead.ok() && concurrentRead.identity->clientCertificatePem == first.identity->clientCertificatePem,
        "concurrent identity readers were mistaken for a changed pairing");
    require(saveNativeHost(directory, stored, host).status == NativeStoreStatus::Busy &&
        forgetNativeHost(directory, stored, host).status == NativeStoreStatus::Busy &&
        initializeNativeIdentity(directory).status == NativeStoreStatus::Busy,
        "credential writes overlapped a reader");
    require(flock(guard, LOCK_UN) == 0 && flock(guard, LOCK_EX | LOCK_NB) == 0, "cannot hold writer fixture lock");
    require(loadNativeIdentity(directory).status == NativeStoreStatus::Busy &&
        saveNativeHost(directory, stored, host).status == NativeStoreStatus::Busy &&
        forgetNativeHost(directory, stored, host).status == NativeStoreStatus::Busy,
        "credential readers or writers overlapped another writer");
    close(guard);
    const auto owned = loadNativeIdentity(directory);
    const auto snapshot = nova::deck::backend::buildLiveSnapshot(*owned.identity, [](const auto&, bool) {
        nova::deck::backend::DeckLivePolarisFetch fetched;
        fetched.status = nova::deck::polaris::DeckPolarisRequestStatus::Ok;
        nova::deck::polaris::DeckPolarisGame game;
        game.id = "fixture-game"; game.appId = 7; game.name = "Local test game";
        fetched.games.push_back(game);
        return fetched;
    });
    require(snapshot.library.games.size() == 1 && snapshot.selectedHostId == host.uuid &&
            snapshot.hosts.front().publicStatusLabel.find("paired through Nova") != std::string::npos,
            "native identity did not supply the selected library");
    host.serverCertificatePem = client.certificate.toStdString();
    require(saveNativeHost(directory, stored, host).status == NativeStoreStatus::Conflict, "saved pin was overwritten");
    require(loadNativeIdentity(directory).identity->hosts.front().serverCertificatePem == server.certificate.toStdString(), "saved pin changed");
    host.uuid = "second"; host.manualAddress = "other-pc";
    require(saveNativeHost(directory, client, host).status == NativeStoreStatus::Conflict, "stale client identity was accepted");
    require(forgetNativeHost(directory, stored, loadNativeIdentity(directory).identity->hosts.front()).identity->hosts.empty(), "local forget failed");
    require(loadNativeIdentity(directory).identity->clientCertificatePem == first.identity->clientCertificatePem, "forget rotated client credentials");
    QFile identityFile(directory + "/identity.json");
    require(identityFile.open(QIODevice::WriteOnly | QIODevice::Truncate), "cannot create corrupt-store fixture");
    identityFile.write("corrupt"); identityFile.close();
    require(initializeNativeIdentity(directory).status == NativeStoreStatus::Invalid, "corrupt store was silently replaced");
    require(identityFile.open(QIODevice::ReadOnly) && identityFile.readAll() == "corrupt", "corrupt store was mutated");
    identityFile.close();
    require(QFile::remove(identityFile.fileName()), "cannot prepare link fixture");
    require(QFile::link("/dev/null", identityFile.fileName()), "cannot create link fixture");
    require(initializeNativeIdentity(directory).status == NativeStoreStatus::Unsafe, "identity symlink was followed");
    require(QFile::remove(identityFile.fileName()), "cannot remove link fixture");
    require(chmod(QFile::encodeName(directory).constData(), 0755) == 0, "cannot prepare permissions fixture");
    require(initializeNativeIdentity(directory).status == NativeStoreStatus::Unsafe, "public store directory was accepted");
}

void testTranscript(const crypto::Credentials& client, const crypto::Credentials& server) {
    const PairEndpoint endpoint{"fixture-pc", 47989};
    for (const bool trusted : {false, true}) {
    for (int scenario = 0; scenario < 10; ++scenario) {
        Peer peer(server);
        peer.trusted = trusted;
        peer.tofu = "1"; // A manual PIN must never opt into trusted pairing, even when advertised.
        peer.pin = trusted ? "0000" : "1234";
        if (scenario == 1) peer.pin = "4321";
        peer.tamperSignature = scenario == 2;
        peer.malformedChallenge = scenario == 3;
        peer.wrongAuthenticatedId = scenario == 4;
        peer.denyCleanup = scenario == 8;
        bool cancelled = scenario == 5;
        int commits = 0;
        const auto reply = pairHost(endpoint, client, trusted ? "0000" : "1234", {}, [&](const PairRequest& request) {
            auto response = peer.handle(request);
            if ((scenario == 6 && peer.step == 1) || (scenario == 7 && peer.step == 4)) cancelled = true;
            return response;
        }, [&] { return cancelled; }, [&](const auto&) { ++commits; return scenario != 8 && scenario != 9; },
        trusted ? PairMode::Trusted : PairMode::Pin);
        if (scenario == 0) require(reply.status == PairStatus::Ok && reply.host && commits == 1 && peer.cleanupCount == 0, "valid handshake failed");
        else {
            require(reply.status != PairStatus::Ok && !reply.host, "bad handshake accepted");
            require(commits == ((scenario == 8 || scenario == 9) ? 1 : 0), "unconfirmed host reached persistence");
            require(peer.cleanupCount == (scenario == 5 ? 0 : 1), "failed attempt cleanup count incorrect");
        }
        if (scenario == 1) require(reply.status == PairStatus::WrongPin && peer.step == 3 && peer.httpCleanup, "wrong PIN disclosed client secret");
        if (scenario == 2) require(reply.status == PairStatus::CertificateMismatch && peer.step == 3, "bad server signature accepted");
        if (scenario == 3) require(reply.status == PairStatus::Malformed && peer.step == 2, "bad hex accepted");
        if (scenario == 4 || scenario == 7 || scenario == 9) require(peer.tlsCleanup && !peer.authorized, "post-authorization failure did not revoke via pinned TLS");
        if (scenario == 8) require(reply.authorizationMayRemain, "failed rollback was hidden");
    }
    }
    Peer peer(server);
    DeckMoonlightHostRecord saved;
    saved.uuid = "fixture-host";
    require(pairHost(endpoint, client, "1234", {saved}, [&](const auto& r) { return peer.handle(r); }, {}).status == PairStatus::AlreadySaved && peer.step == 0,
            "existing host started re-pairing");
    Peer alias(server);
    alias.authorized = true;
    saved.uuid = "saved-before-the-http-uuid-changed";
    saved.serverCertificatePem = server.certificate.toStdString();
    const auto aliasResult = pairHost(endpoint, client, "1234", {saved}, [&](const auto& r) { return alias.handle(r); }, {});
    require(aliasResult.status == PairStatus::AlreadySaved && alias.step == 1 && alias.httpCleanup && !alias.tlsCleanup && alias.authorized,
            "an alternate address or spoofed HTTP UUID revoked a saved host identity");
    int requests = 0;
    require(pairHost(endpoint, client, "1234", {}, [&](const auto&) {
        ++requests;
        return PairReply{PairStatus::Ok, xml("<uniqueid>one</uniqueid><uniqueid>two</uniqueid>")};
    }, {}).status == PairStatus::Malformed && requests == 1, "duplicate identity fields accepted");
}

void testTrustedAdmission(const crypto::Credentials& client, const crypto::Credentials& server) {
    const PairEndpoint endpoint{"fixture-pc", 47989};
    for (const QByteArray hint : {QByteArray{}, QByteArray("0"), QByteArray("true"), QByteArray("2")}) {
        Peer peer(server); peer.tofu = hint;
        const auto result = pairHost(endpoint, client, "0000", {}, [&](const auto& r) { return peer.handle(r); }, {}, {}, PairMode::Trusted);
        require(result.status == (hint.isEmpty() || hint == "0" ? PairStatus::TrustedUnavailable : PairStatus::Malformed) &&
            peer.requests == 1 && peer.step == 0 && peer.cleanupCount == 0 && !result.host,
            "unadvertised or malformed trusted capability started pairing");
    }
    int requests = 0;
    require(pairHost(endpoint, client, "1234", {}, [&](const auto&) { ++requests; return PairReply{}; }, {}, {}, PairMode::Trusted).status == PairStatus::Malformed &&
        requests == 0, "trusted pairing accepted a non-protocol PIN");
    require(pairHost(endpoint, client, "0000", {}, [&](const auto&) {
        ++requests;
        return PairReply{PairStatus::Ok, xml("<TofuEnabled>1</TofuEnabled><TofuEnabled>0</TofuEnabled>")};
    }, {}, {}, PairMode::Trusted).status == PairStatus::Malformed && requests == 1, "duplicate trusted capability accepted");
    Peer peer(server); peer.trusted = true; peer.tofu = "1"; peer.pin = "0000";
    DeckMoonlightHostRecord saved; saved.uuid = peer.id.toStdString();
    require(pairHost(endpoint, client, "0000", {saved}, [&](const auto& r) { return peer.handle(r); }, {}, {}, PairMode::Trusted).status == PairStatus::AlreadySaved &&
        peer.step == 0, "trusted pairing replaced a saved host");
    saved.uuid = "saved-alias"; saved.serverCertificatePem = server.certificate.toStdString();
    peer.transaction.clear(); peer.authorized = true;
    require(pairHost(endpoint, client, "0000", {saved}, [&](const auto& r) { return peer.handle(r); }, {}, {}, PairMode::Trusted).status == PairStatus::AlreadySaved &&
        peer.step == 1 && peer.httpCleanup && !peer.tlsCleanup && peer.authorized,
        "trusted pairing alias revoked an existing authorization");
}

class LocalHost final : public QTcpServer {
public:
    Peer& peer;
    bool tls;
    crypto::Credentials tlsIdentity;
    int httpRequests = 0;
    explicit LocalHost(Peer& peer, bool tls, crypto::Credentials identity) : peer(peer), tls(tls), tlsIdentity(std::move(identity)) {}
protected:
    void incomingConnection(qintptr descriptor) override {
        auto* socket = new QSslSocket(this);
        require(socket->setSocketDescriptor(descriptor), "cannot accept loopback connection");
        if (tls) {
            socket->setLocalCertificate(QSslCertificate(tlsIdentity.certificate));
            socket->setPrivateKey(QSslKey(tlsIdentity.privateKey, QSsl::Rsa));
            auto config = socket->sslConfiguration();
            config.setCaCertificates({QSslCertificate(peer.clientCertificate)});
            socket->setSslConfiguration(config);
            socket->setPeerVerifyMode(QSslSocket::VerifyPeer);
        }
        connect(socket, &QSslSocket::disconnected, socket, &QObject::deleteLater);
        connect(socket, &QSslSocket::readyRead, socket, [this, socket, bytes = QByteArray{}]() mutable {
            bytes += socket->readAll();
            if (!bytes.contains("\r\n\r\n")) return;
            ++httpRequests;
            if (tls) require(socket->peerCertificate().toDer() == QSslCertificate(peer.clientCertificate).toDer(), "mTLS did not authenticate the Nova client");
            const QUrl url = QUrl::fromEncoded(bytes.split(' ').at(1));
            PairRequest request;
            request.path = url.path(); request.query = QUrlQuery(url); request.tls = tls;
            request.serverCertificate = peer.server.certificate;
            const auto response = peer.handle(request);
            socket->write("HTTP/1.1 200 OK\r\nConnection: close\r\nContent-Length: " + QByteArray::number(response.body.size()) + "\r\n\r\n" + response.body);
            socket->disconnectFromHost();
            bytes.clear();
        });
        if (tls) socket->startServerEncryption();
    }
};

void testRealNetwork(const crypto::Credentials& client, const crypto::Credentials& server) {
    for (bool trusted : {false, true}) {
    for (bool wrongCertificate : {false, true}) {
        Peer peer(server);
        peer.trusted = trusted; peer.tofu = "1"; peer.pin = trusted ? "0000" : "1234";
        LocalHost http(peer, false, server), https(peer, true, wrongCertificate ? client : server);
        require(http.listen(QHostAddress::LocalHost, 0) && https.listen(QHostAddress::LocalHost, 0), "loopback listeners failed");
        peer.httpsPort = https.serverPort();
        const auto reply = pairHost({"127.0.0.1", http.serverPort()}, client, peer.pin, {}, pairingNetworkTransport(client, {}), {}, {},
            trusted ? PairMode::Trusted : PairMode::Pin);
        if (wrongCertificate) require(reply.status == PairStatus::CertificateMismatch && https.httpRequests == 0 && reply.authorizationMayRemain,
                                      "wrong TLS pin reached HTTP or hid unconfirmed cleanup");
        else require(reply.status == PairStatus::Ok && https.httpRequests == 2, "real HTTP/PIN/mTLS handshake failed");
    }
    }
    QTcpServer silent;
    require(silent.listen(QHostAddress::LocalHost, 0), "silent listener failed");
    PairRequest request;
    request.endpoint = {"127.0.0.1", silent.serverPort()}; request.path = "/serverinfo"; request.timeoutMs = 70;
    auto transport = pairingNetworkTransport(client, {});
    require(transport(request).status == PairStatus::Timeout, "absolute network deadline failed");
    bool cancelled = false;
    request.timeoutMs = 1000;
    QTimer::singleShot(20, [&] { cancelled = true; });
    require(pairingNetworkTransport(client, [&] { return cancelled; })(request).status == PairStatus::Cancelled, "in-flight network cancellation failed");

    for (bool redirect : {true, false}) {
        QTcpServer bounded;
        require(bounded.listen(QHostAddress::LocalHost, 0), "bounded reply listener failed");
        int requests = 0;
        QObject::connect(&bounded, &QTcpServer::newConnection, &bounded, [&] {
            auto* socket = bounded.nextPendingConnection();
            QObject::connect(socket, &QTcpSocket::disconnected, socket, &QObject::deleteLater);
            QObject::connect(socket, &QTcpSocket::readyRead, socket, [&, socket] {
                socket->readAll(); ++requests;
                if (redirect) socket->write("HTTP/1.1 302 Found\r\nLocation: http://127.0.0.1:" +
                    QByteArray::number(bounded.serverPort()) + "/redirected\r\nContent-Length: 0\r\nConnection: close\r\n\r\n");
                else socket->write("HTTP/1.1 200 OK\r\nContent-Length: 70000\r\nConnection: close\r\n\r\n" + QByteArray(70000, 'x'));
                socket->disconnectFromHost();
            });
        });
        request.endpoint.httpPort = bounded.serverPort();
        const auto response = transport(request);
        require(response.status == (redirect ? PairStatus::Rejected : PairStatus::Malformed) && requests == 1,
                "redirect followed or oversized reply accepted");
        require(response.body.size() <= 65537, "pairing reply buffer was unbounded");
    }
}

void testUnpair(const crypto::Credentials& client, const crypto::Credentials& server) {
    DeckMoonlightHostRecord host;
    host.uuid = "fixture-host"; host.hostname = "Test PC";
    host.manualAddress = "fixture-pc"; host.manualPort = 47989;
    host.nativeHttpsPort = 49001; host.serverCertificatePem = server.certificate.toStdString();
    for (int scenario = 0; scenario < 8; ++scenario) {
        int requests = 0;
        bool cancelled = scenario == 5;
        const auto result = unpairHost(host, [&](const PairRequest& request) {
            ++requests;
            require(request.tls && request.httpsPort == 49001 && request.serverCertificate == server.certificate,
                    "unpair did not use the saved TLS endpoint and exact pin");
            if (request.path == "/serverinfo") {
                if (scenario == 1) return PairReply{PairStatus::CertificateMismatch};
                if (scenario == 6) cancelled = true;
                return PairReply{PairStatus::Ok, xml(element("uniqueid", scenario == 2 ? "different-host" : "fixture-host") +
                    element("PairStatus", scenario == 7 ? "0" : "1"))};
            }
            require(request.path == "/unpair", "unexpected removal path");
            if (scenario == 3) return PairReply{PairStatus::Timeout};
            return PairReply{PairStatus::Ok, xml(element("paired", scenario == 4 ? "1" : "0"))};
        }, [&] { return cancelled; });
        require((result.status == PairStatus::Ok) == (scenario == 0 || scenario == 7), "unpair result was overclaimed");
        require(requests == (scenario == 5 ? 0 : scenario == 0 || scenario == 3 || scenario == 4 ? 2 : 1),
                "unpair mutated before pinned identity checks or despite cancellation");
        require(result.requestMayHaveReachedHost == (scenario == 0 || scenario == 3 || scenario == 4), "ambiguous unpair response was hidden");
    }
    // Exercise a real, fresh mTLS connection for both probe and mutation.
    Peer peer(server); peer.authorized = true; peer.clientCertificate = client.certificate;
    LocalHost https(peer, true, server);
    require(https.listen(QHostAddress::LocalHost, 0), "unpair loopback listener failed");
    host.manualAddress = "127.0.0.1"; host.nativeHttpsPort = https.serverPort();
    require(unpairHost(host, pairingNetworkTransport(client, {}), {}).status == PairStatus::Ok &&
            https.httpRequests == 2 && peer.tlsCleanup && !peer.authorized, "real pinned unpair failed");
}

void testManagement(const crypto::Credentials& server) {
    using nova::deck::runtime::DeckPairingController;
    QTemporaryDir temporary;
    const auto directory = temporary.path() + "/nova";
    const auto initial = initializeNativeIdentity(directory);
    require(initial.ok(), "management identity failed");
    const crypto::Credentials client{QByteArray::fromStdString(initial.identity->clientCertificatePem),
        QByteArray::fromStdString(initial.identity->clientPrivateKeyPemForBackendOnly)};
    DeckMoonlightHostRecord host;
    host.uuid = "fixture-host"; host.hostname = "Saved test PC";
    host.manualAddress = "private-endpoint"; host.manualPort = 47989; host.nativeHttpsPort = 49001;
    host.serverCertificatePem = server.certificate.toStdString();
    require(saveNativeHost(directory, client, host).ok(), "management host save failed");
    require(loadNativeIdentity(directory).identity->hosts.front().nativeHttpsPort == 49001, "confirmed TLS port lost after restart");
    auto changed = host; changed.serverCertificatePem = client.certificate.toStdString();
    require(forgetNativeHost(directory, client, changed).status == NativeStoreStatus::Conflict, "stale host pin was removed");
    require(forgetNativeHost(directory, server, host).status == NativeStoreStatus::Conflict, "stale client identity removed a pairing");
    auto wait = [](DeckPairingController& controller) {
        QElapsedTimer deadline; deadline.start();
        while (controller.busy() && deadline.elapsed() < 5000) { QCoreApplication::processEvents(); QThread::msleep(2); }
        require(!controller.busy(), "management worker did not finish");
    };
    std::atomic<int> requests{0};
    DeckPairingController failed(directory, [&](auto, auto) {
        return [&](const PairRequest&) { ++requests; return PairReply{PairStatus::CertificateMismatch}; };
    });
    const auto projected = QJsonDocument::fromVariant(failed.savedHosts()).toJson();
    require(!projected.contains("private-endpoint") && !projected.contains("CERTIFICATE") && !projected.contains("PRIVATE KEY"),
            "saved-PC QML list leaked identity material");
    require(failed.removeHost("fixture-host", false) && !failed.removeHost("fixture-host", true), "duplicate removal admitted");
    wait(failed);
    require(failed.state().value("phase") == "failed" && failed.savedHosts().size() == 1 && requests == 1,
            "failed remote removal discarded the local pairing");
    std::atomic<bool> reachedProbe{false};
    DeckPairingController cancelling(directory, [&](auto, auto cancelled) {
        return [&, cancelled](const PairRequest& request) {
            require(request.path == "/serverinfo", "cancelled probe reached remote unpair");
            reachedProbe = true;
            while (!cancelled()) QThread::msleep(2);
            return PairReply{PairStatus::Cancelled};
        };
    });
    require(cancelling.removeHost("fixture-host", false), "cancellable removal did not start");
    QElapsedTimer probeDeadline; probeDeadline.start();
    while (!reachedProbe && probeDeadline.elapsed() < 5000) { QCoreApplication::processEvents(); QThread::msleep(2); }
    require(reachedProbe, "management probe did not reach its worker");
    cancelling.cancel();
    wait(cancelling);
    require(cancelling.state().value("phase") == "cancelled" && cancelling.savedHosts().size() == 1,
            "cancelled removal lost the local pairing");
    {
        auto lease = lockNativePairing(directory);
        require(failed.removeHost("fixture-host", true), "local removal did not start");
        wait(failed);
        require(failed.state().value("phase") == "failed" && failed.savedHosts().size() == 1, "removal ignored another pairing process");
    }
    require(failed.removeHost("fixture-host", true), "explicit local forget did not start");
    wait(failed);
    require(failed.state().value("phase") == "removed" && failed.savedHosts().isEmpty() && requests == 1,
            "local forget made a network request or failed to update saved list");
    require(loadNativeIdentity(directory).identity->clientCertificatePem == initial.identity->clientCertificatePem,
            "forget rotated the identity used by other hosts");
    require(saveNativeHost(directory, client, host).ok(), "re-pair persistence after removal failed");
    DeckPairingController successful(directory, [server](auto, auto) {
        auto peer = std::make_shared<Peer>(server); peer->authorized = true;
        return [peer](const PairRequest& request) { return peer->handle(request); };
    }, [] { return "1234"; });
    require(successful.removeHost("fixture-host", false), "authenticated removal did not start");
    wait(successful);
    require(successful.state().value("phase") == "removed" && loadNativeIdentity(directory).identity->hosts.empty(),
            "confirmed remote removal did not update storage");
    require(successful.start("private-endpoint", 47989), "re-pair could not start after confirmed removal");
    wait(successful);
    require(successful.state().value("phase") == "paired" && loadNativeIdentity(directory).identity->hosts.size() == 1,
            "fresh PIN handshake after removal did not restore the pairing");
    const auto repaired = loadNativeIdentity(directory).identity->hosts.front();
    require(forgetNativeHost(directory, client, repaired).ok(), "re-pair test cleanup failed");
    // A stale UI selection cannot revoke a host after another process updates it.
    require(saveNativeHost(directory, client, host).ok(), "stale selection setup failed");
    successful.reset();
    require(forgetNativeHost(directory, client, host).ok() && saveNativeHost(directory, client, changed).ok(), "replace fixture failed");
    require(successful.removeHost("fixture-host", false), "stale selection did not start");
    wait(successful);
    require(successful.state().value("phase") == "failed" && successful.savedHosts().size() == 1,
            "stale UI selection changed the replacement pairing");
}

void testController(const crypto::Credentials& server) {
    using nova::deck::runtime::DeckPairingController;
    QTemporaryDir temporary;
    const auto directory = temporary.path() + "/nova";
    int heartbeats = 0;
    QTimer heartbeat;
    QObject::connect(&heartbeat, &QTimer::timeout, [&] { ++heartbeats; });
    heartbeat.start(5);
    std::atomic<bool> reachedPin{false};
    DeckPairingController controller(directory, [&](auto, auto cancelled) {
        return [peer = std::make_shared<Peer>(server), cancelled, &reachedPin](const PairRequest& request) {
            if (request.query.queryItemValue("phrase") == "getservercert") {
                reachedPin = true;
                while (!cancelled()) QThread::msleep(2);
                return PairReply{PairStatus::Cancelled};
            }
            return peer->handle(request);
        };
    }, [] { return "1234"; });
    require(controller.start("fixture-pc", 47989) && !controller.start("other", 47989), "duplicate GUI pairing admitted");
    QElapsedTimer deadline; deadline.start();
    while (!reachedPin && deadline.elapsed() < 5000) { QCoreApplication::processEvents(); QThread::msleep(2); }
    require(reachedPin && heartbeats > 0, "pairing blocked the GUI heartbeat");
    controller.cancel();
    require(controller.state().value("pin").toString().isEmpty(), "cancel did not clear the displayed PIN");
    while (controller.busy() && deadline.elapsed() < 6000) { QCoreApplication::processEvents(); QThread::msleep(2); }
    require(!controller.busy() && controller.state().value("phase") == "cancelled", "GUI pairing failed to cancel");
    require(loadNativeIdentity(directory).identity->hosts.empty(), "cancelled host was saved");
    const auto json = QJsonDocument::fromVariant(controller.state()).toJson();
    require(!json.contains("fixture-pc") && !json.contains("PRIVATE KEY") && !json.contains("CERTIFICATE") && !json.contains("1234"), "private state leaked to QML");

    DeckPairingController successful(directory, [server](auto, auto) {
        return [peer = std::make_shared<Peer>(server)](const PairRequest& request) { return peer->handle(request); };
    }, [] { return "1234"; });
    require(successful.start("fixture-pc", 47989), "controller could not retry after cancellation");
    deadline.restart();
    while (successful.busy() && deadline.elapsed() < 5000) { QCoreApplication::processEvents(); QThread::msleep(2); }
    require(!successful.busy() && successful.state().value("phase") == "paired" &&
            successful.state().value("pin").toString().isEmpty(), "successful controller did not complete or clear the PIN");
    auto saved = loadNativeIdentity(directory);
    require(saved.ok() && saved.identity->hosts.size() == 1 && saved.identity->hosts.front().serverCertificatePem == server.certificate.toStdString(),
            "successful controller did not persist the verified host");
    const auto originalCert = saved.identity->clientCertificatePem;
    require(successful.start("fixture-pc", 47989), "saved-host attempt did not start");
    deadline.restart();
    while (successful.busy() && deadline.elapsed() < 5000) { QCoreApplication::processEvents(); QThread::msleep(2); }
    require(!successful.busy() && successful.state().value("phase") == "failed", "saved host was silently paired again");
    saved = loadNativeIdentity(directory);
    require(saved.identity->hosts.size() == 1 && saved.identity->clientCertificatePem == originalCert, "retry replaced a saved identity");
}

void testTrustedController(const crypto::Credentials& server) {
    using nova::deck::runtime::DeckPairingController;
    for (int scenario = 0; scenario < 8; ++scenario) {
        QTemporaryDir temporary;
        const auto directory = temporary.path() + "/nova";
        std::atomic<int> attempts{0}, generatedPins{0};
        std::atomic<bool> reachedWaiting{false}, sawPin{false};
        bool sawTrusted = false, leakedProtocolPin = false;
        DeckPairingController controller(directory, [&, scenario](auto, auto cancelled) {
            const int attempt = ++attempts;
            auto peer = std::make_shared<Peer>(server);
            peer->trusted = attempt == 1;
            peer->pin = peer->trusted ? "0000" : "1234";
            peer->tofu = scenario == 1 ? QByteArray{} : scenario == 2 ? QByteArray("0") : scenario == 7 ? QByteArray("bad") : QByteArray("1");
            peer->tamperSignature = scenario == 3;
            peer->wrongAuthenticatedId = scenario == 6;
            peer->denyCleanup = scenario == 6;
            return PairTransport([&, peer, cancelled, scenario](const PairRequest& request) {
                if (request.query.queryItemValue("phrase") == "getservercert") {
                    require(request.timeoutMs == (peer->trusted ? 5000 : 120000), "wrong pairing approval deadline");
                    reachedWaiting = true;
                    while (!cancelled() && ((!peer->trusted && !sawPin) || scenario == 5)) QThread::msleep(2);
                    if (cancelled()) return PairReply{PairStatus::Cancelled};
                    if (scenario == 4) return PairReply{PairStatus::Timeout};
                }
                return peer->handle(request);
            });
        }, [&] { ++generatedPins; return "1234"; });
        QObject::connect(&controller, &DeckPairingController::stateChanged, [&] {
            const auto state = controller.state();
            const auto pin = state.value("pin").toString();
            leakedProtocolPin = leakedProtocolPin || pin == "0000";
            sawTrusted = sawTrusted || state.value("phase") == "trusted";
            if (pin == "1234") {
                sawPin = true;
                require(state.value("copy").toString().contains("Trusted Pair is unavailable"), "PIN fallback was not explained");
            }
        });
        require(controller.startTrusted("fixture-pc", 47989) && !controller.startTrusted("other-pc", 47989) &&
            !controller.start("other-pc", 47989), "concurrent PIN/trusted pairing admitted");
        QElapsedTimer deadline; deadline.start();
        while (controller.busy() && deadline.elapsed() < 6000) {
            QCoreApplication::processEvents();
            if (scenario == 5 && reachedWaiting) controller.cancel();
            QThread::msleep(2);
        }
        require(!controller.busy() && !leakedProtocolPin, "trusted pairing stuck or exposed its protocol PIN");
        const bool fallback = scenario == 1 || scenario == 2;
        require(attempts == (fallback ? 2 : 1) && generatedPins == (fallback ? 1 : 0) && sawPin == fallback,
            "trusted fallback occurred after failure or skipped a fresh PIN");
        const auto state = controller.state();
        const auto saved = loadNativeIdentity(directory);
        require(saved.ok(), "trusted flow damaged its identity store");
        if (scenario <= 2) {
            require(state.value("phase") == "paired" && state.value("pin").toString().isEmpty() && saved.identity->hosts.size() == 1 &&
                saved.identity->hosts.front().serverCertificatePem == server.certificate.toStdString(), "trusted/fallback handshake did not persist verified host");
        } else {
            require(state.value("phase") == (scenario == 5 ? "cancelled" : "failed") && saved.identity->hosts.empty(),
                "trusted failure persisted a host or was misreported");
        }
        if (scenario == 6) require(state.value("copy").toString().contains("cleanup could not be confirmed"), "uncertain trusted rollback was hidden");
        if (scenario == 5) require(sawTrusted || reachedWaiting, "trusted cancellation was not exercised in flight");
    }
}
}

int main(int argc, char** argv) {
    QCoreApplication app(argc, argv);
    const auto client = credentials(), server = credentials();
    testCryptoAndStore(client, server);
    testTranscript(client, server);
    testTrustedAdmission(client, server);
    testRealNetwork(client, server);
    testController(server);
    testTrustedController(server);
    testUnpair(client, server);
    testManagement(server);
    std::cout << "Pairing passed: crypto vectors, private persistence, negative transcripts, loopback mTLS, saved-PC management and UI cancellation\n";
}
