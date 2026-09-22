// Real loopback transport regression: silent HTTP must not suppress pinned HTTPS.
#include "backend/deck_live_read_only_state.h"
#include "runtime/deck_native_target.h"
#include "deck_doctor_fixture.h"
#include "deck_host_settings_fixture.h"
#include "stream/deck_gamestream_library.h"

#include <QCoreApplication>
#include <QFile>
#include <QProcess>
#include <QSslCertificate>
#include <QSslKey>
#include <QSslSocket>
#include <QSslConfiguration>
#include <QTcpServer>
#include <QTemporaryDir>
#include <QUrlQuery>
#include <QElapsedTimer>
#include <QTimer>
#include <QJsonDocument>
#include <QJsonObject>
#include <QJsonArray>

#include <cstdlib>
#include <chrono>
#include <string>

namespace {

// Keep setup and verification active in every CMake build type.
void require(bool condition) {
    if (!condition) std::abort();
}

QByteArray readFile(const QString& path) {
    QFile file(path);
    require(file.open(QIODevice::ReadOnly));
    return file.readAll();
}

struct TestIdentity {
    QByteArray cert;
    QByteArray key;
};

TestIdentity createIdentity(const QString& directory, const QString& name) {
    const auto certPath = directory + "/" + name + ".crt";
    const auto keyPath = directory + "/" + name + ".key";
    QProcess process;
    process.start(QStringLiteral(NOVA_DECK_TEST_OPENSSL), {"req", "-x509", "-newkey", "rsa:2048", "-nodes",
        "-keyout", keyPath, "-out", certPath, "-days", "1", "-subj", "/CN=nova-loopback-test"});
    require(process.waitForFinished(15000));
    require(process.exitStatus() == QProcess::NormalExit && process.exitCode() == 0);
    return {readFile(certPath), readFile(keyPath)};
}

TestIdentity createDerivedServer(const QString& directory) {
    const auto run = [](const QStringList& args) {
        QProcess process;
        process.start(QStringLiteral(NOVA_DECK_TEST_OPENSSL), args);
        require(process.waitForFinished(15000));
        require(process.exitStatus() == QProcess::NormalExit && process.exitCode() == 0);
    };
    run({"req", "-new", "-newkey", "rsa:2048", "-nodes", "-keyout", directory + "/derived.key",
         "-out", directory + "/derived.csr", "-subj", "/CN=127.0.0.1"});
    QFile extensions(directory + "/derived.ext");
    require(extensions.open(QIODevice::WriteOnly));
    extensions.write("basicConstraints=CA:FALSE\nkeyUsage=digitalSignature,keyEncipherment\nextendedKeyUsage=serverAuth\nsubjectAltName=IP:127.0.0.1\n");
    extensions.close();
    run({"x509", "-req", "-in", directory + "/derived.csr", "-CA", directory + "/server.crt",
         "-CAkey", directory + "/server.key", "-set_serial", "2", "-days", "1",
         "-extfile", directory + "/derived.ext", "-out", directory + "/derived.crt"});
    return {readFile(directory + "/derived.crt"), readFile(directory + "/derived.key")};
}

class TlsServer final : public QTcpServer {
public:
    // Decode fixture credentials before accepting connections. Re-decoding on
    // each accept raced OpenSSL's decoder-cache teardown on the client thread.
    explicit TlsServer(const TestIdentity& identity)
        : certificate_(identity.cert), privateKey_(identity.key, QSsl::Rsa) {
        require(!certificate_.isNull() && !privateKey_.isNull());
    }
    void trustClient(const QByteArray& certificatePem) {
        trustedClient_ = QSslCertificate(certificatePem);
        require(!trustedClient_.isNull());
    }
    int requests = 0;
    std::function<std::pair<int, QByteArray>(const QUrl&)> handler;
    std::vector<QString> paths;
    bool dribbleReply = false, dropReply = false;
    std::vector<QByteArray> methods, bodies;
    QByteArray redirectLocation;
    std::function<void(QSslSocket*)> eventStream;
    std::vector<QByteArray> headers;

protected:
    void incomingConnection(qintptr descriptor) override {
        auto* socket = new QSslSocket(this);
        socket->setLocalCertificate(certificate_);
        socket->setPrivateKey(privateKey_);
        socket->setPeerVerifyMode(QSslSocket::VerifyNone);
        if (!trustedClient_.isNull()) {
            auto config = socket->sslConfiguration();
            config.setCaCertificates({trustedClient_});
            socket->setSslConfiguration(config);
            socket->setPeerVerifyMode(QSslSocket::VerifyPeer);
        }
        require(socket->setSocketDescriptor(descriptor));
        connect(socket, &QSslSocket::disconnected, socket, &QObject::deleteLater);
        connect(socket, &QSslSocket::readyRead, socket, [this, socket, request = QByteArray{}, completed = false]() mutable {
            if (completed) return;
            request += socket->readAll();
            const auto end = request.indexOf("\r\n\r\n");
            if (end < 0) return;
            int length = 0;
            for (const auto& line : request.left(end).split('\n'))
                if (line.toLower().startsWith("content-length:")) length = line.mid(15).trimmed().toInt();
            if (request.size() < end + 4 + length) return;
            completed = true;
            methods.push_back(request.split(' ').first());
            bodies.push_back(request.mid(end + 4, length));
            ++requests;
            const QUrl url = QUrl::fromEncoded(request.split(' ').at(1));
            paths.push_back(url.path());
            headers.push_back(request.first(end));
            if (!trustedClient_.isNull()) require(socket->peerCertificate().toDer() == trustedClient_.toDer());
            if (eventStream) { eventStream(socket); request.clear(); return; }
            if (dropReply) { socket->abort(); return; }
            if (dribbleReply) {
                socket->write("HTTP/1.1 200 OK\r\nContent-Length: 10000\r\n\r\n");
                auto* timer = new QTimer(socket);
                connect(timer, &QTimer::timeout, socket, [socket] { socket->write("x"); });
                timer->start(10);
                request.clear();
                return;
            }
            QByteArray body = request.startsWith("GET /polaris/v1/capabilities ")
                ? QByteArray(R"({"server":"polaris","version":"test","features":{"game_library":true}})")
                : QByteArray(R"({"games":[{"id":"test-game","name":"Test game"}],"total":1})");
            int status = 200;
            if (handler) std::tie(status, body) = handler(url);
            const QByteArray redirect = redirectLocation.isEmpty() ? QByteArray{} : "Location: " + redirectLocation + "\r\n";
            socket->write("HTTP/1.1 " + QByteArray::number(status) + " Test response\r\n" + redirect + "Content-Type: application/xml\r\nConnection: close\r\nContent-Length: "
                + QByteArray::number(body.size()) + "\r\n\r\n" + body);
            socket->disconnectFromHost();
        });
        socket->startServerEncryption();
    }

private:
    QSslCertificate certificate_, trustedClient_;
    QSslKey privateKey_;
};

void testSilentHttpStillUsesPinnedHttps(bool nativePort) {
    using namespace nova::deck;
    QTemporaryDir directory;
    require(directory.isValid());
    const auto serverIdentity = createIdentity(directory.path(), "server");
    const auto clientIdentity = createIdentity(directory.path(), "client");
    TlsServer https(serverIdentity);
    QTcpServer http;
    // Imported hosts use HTTP - 5; native pairings retain their confirmed port.
    for (int attempt = 0; attempt < 100; ++attempt) {
        require(https.listen(QHostAddress::LocalHost, 0));
        if (nativePort) {
            if (http.listen(QHostAddress::LocalHost, 0) && http.serverPort() != https.serverPort() + 5) break;
            http.close();
        } else if (https.serverPort() < 65530 && http.listen(QHostAddress::LocalHost, https.serverPort() + 5)) break;
        https.close();
    }
    require(http.isListening() && https.isListening());
    int httpRequests = 0;
    QObject::connect(&http, &QTcpServer::newConnection, &http, [&] {
        while (auto* socket = http.nextPendingConnection()) {
            QObject::connect(socket, &QTcpSocket::readyRead, socket, [&, socket] {
                if (socket->readAll().contains("/serverinfo")) ++httpRequests;
                // Accept and consume the request, but never send an HTTP response.
            });
            QObject::connect(socket, &QTcpSocket::disconnected, socket, &QObject::deleteLater);
        }
    });

    identity::DeckMoonlightIdentity identity;
    identity.clientCertificatePem = clientIdentity.cert.toStdString();
    identity.clientPrivateKeyPemForBackendOnly = clientIdentity.key.toStdString();
    identity::DeckMoonlightHostRecord host;
    host.localAddress = "127.0.0.1";
    host.localPort = http.serverPort();
    host.nativeHttpsPort = nativePort ? https.serverPort() : 0;
    host.serverCertificatePem = serverIdentity.cert.toStdString();
    const auto timeout = std::chrono::milliseconds(750);
    const auto httpProbe = polaris::probeServerInfoHttpsPort(host.localAddress, host.localPort, timeout);
    require(httpProbe.timedOut && !httpProbe.httpsPort);
    const auto fetcher = backend::polarisNetworkFetcher(identity, timeout);
    const auto fetched = fetcher(host, true);
    require(fetched.status == polaris::DeckPolarisRequestStatus::Ok);
    require(fetched.httpsPort == https.serverPort());
    require(fetched.games.size() == 1 && fetched.games[0].id == "test-game");
    require(httpRequests == 2 && https.requests == 2);

    // The fallback must retain exact certificate pinning even after HTTP timeout.
    host.serverCertificatePem = clientIdentity.cert.toStdString();
    const auto rejected = fetcher(host, true);
    require(rejected.status == polaris::DeckPolarisRequestStatus::CertMismatch);
    require(httpRequests == 3 && https.requests == 2);
}

void testDerivedCertificateCannotReceiveHttp() {
    using namespace nova::deck::polaris;
    QTemporaryDir directory;
    require(directory.isValid());
    const auto pinned = createIdentity(directory.path(), "server");
    const auto client = createIdentity(directory.path(), "client");
    const auto derived = createDerivedServer(directory.path());
    // The leaf is signed by the pinned CA and has the correct IP SAN: ordinary
    // TLS verification succeeds, but exact paired identity admission must fail.
    TlsServer https(derived);
    require(https.listen(QHostAddress::LocalHost, 0));
    DeckPolarisClient connection({"127.0.0.1", https.serverPort()},
        {client.cert.toStdString(), client.key.toStdString(), pinned.cert.toStdString()});
    const auto reply = connection.get("/polaris/v1/capabilities");
    require(reply.status == DeckPolarisRequestStatus::CertMismatch);
    require(https.requests == 0);
}

void testResponseDeadlineSurvivesIncomingBytes() {
    using namespace nova::deck::polaris;
    QTemporaryDir directory;
    const auto server = createIdentity(directory.path(), "dribble-server");
    const auto client = createIdentity(directory.path(), "dribble-client");
    TlsServer https(server);
    https.dribbleReply = true;
    require(https.listen(QHostAddress::LocalHost, 0));
    DeckPolarisClient connection({"127.0.0.1", https.serverPort()},
        {client.cert.toStdString(), client.key.toStdString(), server.cert.toStdString()}, std::chrono::milliseconds(150));
    QElapsedTimer timer; timer.start();
    const auto reply = connection.get("/applist");
    require(reply.status == DeckPolarisRequestStatus::Timeout && !reply.value && timer.elapsed() < 1200 && https.requests == 1);
}

void testStandardHostLibraryAndLaunch() {
    using namespace nova::deck;
    using polaris::DeckPolarisRequestStatus;
    QTemporaryDir directory;
    const auto server = createIdentity(directory.path(), "standard-server");
    const auto client = createIdentity(directory.path(), "standard-client");
    TlsServer https(server);
    https.trustClient(client.cert);
    require(https.listen(QHostAddress::LocalHost, 0));
    QTcpServer http;
    require(http.listen(QHostAddress::LocalHost, 0));
    QObject::connect(&http, &QTcpServer::newConnection, &http, [&] {
        auto* socket = http.nextPendingConnection();
        QObject::connect(socket, &QTcpSocket::disconnected, socket, &QObject::deleteLater);
        QObject::connect(socket, &QTcpSocket::readyRead, socket, [&, socket] {
            socket->readAll();
            const QByteArray body = "<root status_code=\"200\"><HttpsPort>" + QByteArray::number(https.serverPort()) + "</HttpsPort></root>";
            socket->write("HTTP/1.1 200 OK\r\nContent-Length: " + QByteArray::number(body.size()) + "\r\nConnection: close\r\n\r\n" + body);
            socket->disconnectFromHost();
        });
    });
    identity::DeckMoonlightIdentity identity;
    identity.loaded = true; identity.sourceLabel = "nova-native";
    identity.clientCertificatePem = client.cert.toStdString();
    identity.clientPrivateKeyPemForBackendOnly = client.key.toStdString();
    identity::DeckMoonlightHostRecord host;
    host.uuid = "standard-host"; host.hostname = "Fixture PC";
    host.manualAddress = "127.0.0.1"; host.manualPort = http.serverPort();
    host.nativeHttpsPort = https.serverPort(); host.serverCertificatePem = server.cert.toStdString();
    identity.hosts.push_back(host);
    const auto xml = [](QByteArray body, int status = 200) { return "<root status_code=\"" + QByteArray::number(status) + "\">" + body + "</root>"; };
    for (int scenario = 0; scenario < 15; ++scenario) {
        https.paths.clear();
        https.handler = [&](const QUrl& url) -> std::pair<int, QByteArray> {
            const auto path = url.path();
            if (path == "/polaris/v1/capabilities") {
                if (scenario == 1) return {401, "denied"};
                if (scenario == 2) return {403, "denied"};
                if (scenario == 3) return {500, "failure"};
                if (scenario == 4) return {200, "<html>not capabilities</html>"};
                if (scenario == 5) return {302, "redirect"};
                if (scenario == 6) return {200, R"({"server":"polaris","version":"test","features":{"game_library":true}})"};
                return {scenario == 14 ? 501 : 404, xml({}, 404)};
            }
            if (path == "/polaris/v1/games") { require(scenario == 6); return {403, "denied library"}; }
            const QUrlQuery query(url);
            require(query.queryItemValue("uniqueid").toStdString() == identity.clientCertificateFingerprintSha256());
            require(!query.queryItemValue("uuid").isEmpty() && query.queryItemValue("devicename") == "Nova Deck");
            if (path == "/serverinfo") return {200, xml(
                QByteArray("<uniqueid>") + (scenario == 7 ? "different-host" : "standard-host") + "</uniqueid><PairStatus>" +
                (scenario == 8 ? "0" : "1") + "</PairStatus><appversion>7.1.431.0</appversion><GfeVersion>3.23</GfeVersion><ServerCodecModeSupport>1</ServerCodecModeSupport>")};
            if (path == "/applist") {
                if (scenario == 9) return {200, xml({}, 403)};
                if (scenario == 10) return {200, xml("<App><ID>7</ID><ID>8</ID><AppTitle>Ambiguous</AppTitle></App>")};
                if (scenario == 11) return {200, xml({})};
                if (scenario == 12) return {200, QByteArray(int(stream::kStandardAppListLimit + 1), 'x')};
                return {200, xml("<App><AppTitle>Fixture &amp; Game</AppTitle><ID>7</ID><IsHdrSupported>1</IsHdrSupported></App>")};
            }
            if (path == "/launch") {
                require(query.queryItemValue("appid") == "7" && !query.hasQueryItem("appuuid"));
                require(query.hasQueryItem("rikey") && query.hasQueryItem("rikeyid"));
                return {200, xml("<gamesession>1</gamesession><sessionUrl0>rtsp://127.0.0.1:48010</sessionUrl0>")};
            }
            require(path == "/cancel");
            return {200, xml("<cancel>1</cancel>")};
        };
        auto candidate = host;
        if (scenario == 13) candidate.serverCertificatePem = client.cert.toStdString();
        const auto fetch = backend::polarisNetworkFetcher(identity, std::chrono::milliseconds(1500))(candidate, true);
        if (scenario == 0 || scenario == 14 || scenario == 11) {
            require(fetch.status == DeckPolarisRequestStatus::Ok && fetch.standardHost);
            require(fetch.games.size() == (scenario == 11 ? 0U : 1U));
            if (!fetch.games.empty()) require(fetch.games.front().id == "gamestream-app-7" && fetch.games.front().appId == 7 &&
                fetch.games.front().name == "Fixture & Game" && fetch.games.front().hdrSupported);
            require(https.paths == std::vector<QString>{"/polaris/v1/capabilities", "/serverinfo", "/applist"});
            if (scenario == 0) {
                const auto snapshot = backend::buildLiveSnapshot(identity, [fetch](const auto&, bool) { return fetch; });
                const auto resolve = runtime::nativeTargetResolver(identity, snapshot);
                require(!resolve("different-host", "gamestream-app-7") && !resolve("standard-host", "Fixture & Game"));
                const auto target = resolve("standard-host", "gamestream-app-7");
                require(target && target->appId == 7 && target->appUuid.empty());
                stream::DeckLaunchRequest launch; launch.appId = target->appId; launch.appUuid = target->appUuid;
                const auto built = stream::buildStreamConnection(target->fetch, target->serverAddress, launch, stream::buildStreamKeys({}, 42));
                require(built.hostSessionStarted && built.ok);
                const auto ended = stream::requestHostSessionCancel(target->fetch, {});
                require(ended.cancelled);
                require(https.paths == std::vector<QString>{"/polaris/v1/capabilities", "/serverinfo", "/applist", "/serverinfo", "/launch", "/cancel"});
                auto denied = snapshot;
                denied.probes.front().status = DeckPolarisRequestStatus::Unauthorized;
                require(!runtime::nativeTargetResolver(identity, denied)("standard-host", "gamestream-app-7"));
                https.paths.clear();
                const auto probe = backend::polarisNetworkFetcher(identity, std::chrono::milliseconds(1500))(candidate, false);
                require(probe.status == DeckPolarisRequestStatus::Ok && probe.standardHost && probe.games.empty());
                require(https.paths == std::vector<QString>{"/polaris/v1/capabilities", "/serverinfo"});
            }
        } else {
            require(fetch.status != DeckPolarisRequestStatus::Ok && fetch.games.empty());
            if (scenario >= 1 && scenario <= 5) require(https.paths == std::vector<QString>{"/polaris/v1/capabilities"});
            if (scenario == 6) require(https.paths == std::vector<QString>{"/polaris/v1/capabilities", "/polaris/v1/games"});
            if (scenario == 7 || scenario == 8) require(https.paths == std::vector<QString>{"/polaris/v1/capabilities", "/serverinfo"});
            if (scenario == 13) require(https.paths.empty() && fetch.status == DeckPolarisRequestStatus::CertMismatch);
        }
    }
}

} // namespace

void testFreshLaunchModeAuthority() {
    using namespace nova::deck;
    QTemporaryDir directory;
    const auto server = createIdentity(directory.path(), "modes-server");
    const auto client = createIdentity(directory.path(), "modes-client");
    TlsServer https(server);
    https.trustClient(client.cert);
    require(https.listen(QHostAddress::LocalHost, 0));
    QTcpServer silentHttp;
    require(silentHttp.listen(QHostAddress::LocalHost, 0));
    identity::DeckMoonlightIdentity identity;
    identity.loaded = true; identity.sourceLabel = "nova-native";
    identity.clientCertificatePem = client.cert.toStdString();
    identity.clientPrivateKeyPemForBackendOnly = client.key.toStdString();
    identity::DeckMoonlightHostRecord host;
    host.uuid = "mode-host"; host.hostname = "Fixture PC";
    host.manualAddress = "127.0.0.1"; host.manualPort = silentHttp.serverPort();
    host.nativeHttpsPort = https.serverPort(); host.serverCertificatePem = server.cert.toStdString();
    identity.hosts.push_back(host);
    int scenario = 0;
    https.handler = [&](const QUrl& url) -> std::pair<int, QByteArray> {
        if (url.path() == "/polaris/v1/capabilities") {
            if (scenario == 10) return {200, R"({"server":"polaris","capture":{"codecs":["hevc"],"max_fps":30}})"};
            if (scenario == 11) return {200, R"({"server":"polaris","capture":{"max_fps":"60"}})"};
            return {200, scenario == 8
            ? R"({"server":"polaris","features":{"game_library":true}})"
            : R"({"server":"polaris","features":{"game_library":true,"client_settings_v1":true}})"};
        }
        if (url.path() == "/polaris/v1/client-settings") {
            if (scenario == 5) return {200, "{}"};
            if (scenario == 6 || scenario == 7) return {scenario == 6 ? 401 : 500, "unavailable"};
            return {200, QByteArray(R"({"version":1,"desired":{"stream_display_mode":"headless_stream"},
                "effective":{"stream_display_mode":"headless_stream"},"capabilities":{"modes":[
                {"value":"headless_stream","available":)") + (scenario == 1 ? "false" : "true") +
                R"(,"session_overridable":)" + (scenario == 2 ? "false" : "true") + "}]}}"};
        }
        require(url.path() == "/polaris/v1/games"); // No writes/launches in an authority read.
        if (scenario == 4) return {200, R"({"games":[],"total":0})"};
        return {200, QByteArray(R"({"games":[{"id":"mode-game","name":"Fixture","app_id":)") +
            (scenario == 3 ? "99" : "7") + R"(,"launch_mode":{"allowed_modes":[")" +
            (scenario == 9 ? "desktop_display" : "headless_stream") + R"("]}}],"total":1})"};
    };
    const auto fetcher = backend::polarisNetworkFetcher(identity, std::chrono::milliseconds(200));
    const auto fetch = fetcher(host, true);
    require(fetch.status == polaris::DeckPolarisRequestStatus::Ok && fetch.games.front().launchPolicy.known);
    backend::DeckLiveHostLibrarySnapshot snapshot;
    snapshot.selectedHostId = host.uuid;
    snapshot.library.games.push_back(backend::toLibraryGame(fetch.games.front()));
    const auto resolver = runtime::nativeTargetResolver(identity, snapshot);
    const auto target = resolver("mode-host", "mode-game");
    require(target && target->authorizeLaunchMode && target->authorizeLaunchMode("headless_stream", {}));
    require(target->verifyStreamCapabilities && target->verifyStreamCapabilities({})->supports(1920, 1200, 60));
    scenario = 10;
    const auto withdrawn = target->verifyStreamCapabilities({});
    require(withdrawn && !withdrawn->h264 && withdrawn->maxFps == 30);
    const auto updated = fetcher(host, true);
    require(updated.status == polaris::DeckPolarisRequestStatus::Ok && !updated.games.front().streamCapabilities.h264);
    scenario = 11;
    require(!target->verifyStreamCapabilities({})->valid);
    scenario = 0;
    auto countBefore = https.requests;
    require(!target->verifyStreamCapabilities([] { return true; }) && https.requests == countBefore);
    require(!target->verifyStreamCapabilities([&] { return https.requests > countBefore; }) && https.requests == countBefore + 1);
    require(!target->authorizeLaunchMode("headless_dongle", {}));
    const auto beforeCancelled = https.requests;
    require(!target->authorizeLaunchMode("headless_stream", [] { return true; }) && https.requests == beforeCancelled);
    // Cancellation after each network boundary prevents any following read.
    for (int stopAfter = 1; stopAfter <= 3; ++stopAfter) {
        const auto before = https.requests;
        require(!target->authorizeLaunchMode("headless_stream", [&] { return https.requests >= before + stopAfter; }));
        require(https.requests == before + stopAfter);
    }
    for (scenario = 1; scenario <= 9; ++scenario) require(!target->authorizeLaunchMode("headless_stream", {}));
    scenario = 6;
    const auto denied = fetcher(host, true);
    require(denied.status == polaris::DeckPolarisRequestStatus::Unauthorized && denied.games.empty());
    scenario = 5;
    const auto malformed = fetcher(host, true);
    require(malformed.status == polaris::DeckPolarisRequestStatus::Ok && !malformed.games.front().launchPolicy.known);
    scenario = 0;
    identity.hosts.front().serverCertificatePem = client.cert.toStdString();
    const auto wrongPin = runtime::nativeTargetResolver(identity, snapshot)("mode-host", "mode-game");
    const auto count = https.requests;
    require(wrongPin && !wrongPin->authorizeLaunchMode("headless_stream", {}) && https.requests == count);
}

void testPinnedOwnedResume() {
    using namespace nova::deck;
    QTemporaryDir directory;
    const auto server = createIdentity(directory.path(), "resume-server");
    const auto client = createIdentity(directory.path(), "resume-client");
    TlsServer https(server);
    https.trustClient(client.cert);
    require(https.listen(QHostAddress::LocalHost, 0));
    identity::DeckMoonlightIdentity identity;
    identity.loaded = true;
    identity.clientCertificatePem = client.cert.toStdString();
    identity.clientPrivateKeyPemForBackendOnly = client.key.toStdString();
    identity::DeckMoonlightHostRecord host;
    host.uuid = "resume-host";
    host.manualAddress = "127.0.0.1";
    host.nativeHttpsPort = https.serverPort();
    host.serverCertificatePem = server.cert.toStdString();
    identity.hosts.push_back(host);
    backend::DeckLiveHostLibrarySnapshot snapshot;
    snapshot.selectedHostId = "resume-host";
    snapshot.library.games.push_back({.id = "resume-game", .appId = 17, .name = "Fixture Game"});
    for (int scenario = 0; scenario < 11; ++scenario) {
        https.paths.clear();
        https.handler = [&](const QUrl& url) -> std::pair<int, QByteArray> {
            if (url.path() == "/serverinfo") {
                if (scenario == 4) return {401, "denied"};
                QByteArray body = "<root status_code=\"200\"><appversion>7.1</appversion><PairStatus>";
                body += scenario == 6 ? "0" : "1";
                body += "</PairStatus><currentgame>";
                body += scenario == 2 ? "19" : "17";
                body += "</currentgame><currentgameuuid>resume-game</currentgameuuid>";
                if (scenario != 7) {
                    body += "<currentgameowned>";
                    body += scenario == 1 ? "0" : "1";
                    body += "</currentgameowned>";
                }
                if (scenario == 5) body += "<currentgameowned>1</currentgameowned>";
                body += "<currentgamesessiontoken>";
                body += scenario == 3 ? "replacement" : "resume &amp; token";
                body += "</currentgamesessiontoken></root>";
                return {200, body};
            }
            require(url.path() == "/resume"); // Never launch or cancel in a resume attempt.
            const QUrlQuery query(url);
            require(query.queryItemValue("sessiontoken", QUrl::FullyDecoded) == "resume & token");
            require(query.queryItemValue("appid") == "17" && query.queryItemValue("appuuid") == "resume-game");
            require(!query.hasQueryItem("watch") && !query.hasQueryItem("streamMode"));
            if (scenario == 9) return {200, "<root status_code=\"470\"><resume>0</resume></root>"};
            QByteArray body = "<root status_code=\"200\"><resume>1</resume><sessionToken>";
            body += scenario == 10 ? "replacement" : "resume &amp; token";
            body += "</sessionToken><sessionUrl0>rtsp://192.0.2.10:48010</sessionUrl0></root>";
            return {200, body};
        };
        identity.hosts.front().serverCertificatePem = (scenario == 8 ? client.cert : server.cert).toStdString();
        const auto target = runtime::nativeTargetResolver(identity, snapshot)("resume-host", "resume-game");
        require(target.has_value());
        const auto result = stream::buildStreamConnection(target->fetch, target->serverAddress,
            stream::launchRequestForStream(target->request, target->appId, target->appUuid), stream::buildStreamKeys({}, 7), {},
            stream::DeckSessionStartMode::ResumeOnly, "resume & token");
        require(result.ok == (scenario == 0));
        require(!result.retryableTransportFailure); // Pin/auth/ownership/protocol errors never retry automatically.
        if (scenario == 8) require(https.paths.empty());
        else if (scenario == 0 || scenario >= 9) require(https.paths == std::vector<QString>{"/serverinfo", "/resume"});
        else require(https.paths == std::vector<QString>{"/serverinfo"});
    }
}

void testSpacesSelectionAndLaunch() {
    using namespace nova::deck;
    using namespace nova::deck::polaris;
    QTemporaryDir directory;
    const auto server = createIdentity(directory.path(), "spaces-server");
    const auto client = createIdentity(directory.path(), "spaces-client");
    TlsServer https(server); https.trustClient(client.cert);
    require(https.listen(QHostAddress::LocalHost, 0));
    DeckPolarisClient transport({"127.0.0.1", https.serverPort()},
        {client.cert.toStdString(), client.key.toStdString(), server.cert.toStdString()}, std::chrono::milliseconds(700));
    QString selected = "arcade", state = "ready";
    bool canOpen = true;
    const auto spacesBody = [&] {
        return QJsonDocument(QJsonObject{{"schema", 1}, {"status", true}, {"enabled", true},
            {"available", true}, {"can_switch", true}, {"desktop_allowed", true}, {"selected_space_id", selected},
            {"spaces", QJsonArray{QJsonObject{{"id", "arcade"}, {"name", "Arcade"}, {"state", state},
                {"can_open", canOpen}, {"selected", selected == "arcade"}, {"library_enabled", true}}}}}).toJson(QJsonDocument::Compact);
    };
    https.handler = [&](const QUrl& url) -> std::pair<int, QByteArray> {
        require(url.path() == "/polaris/v1/spaces/select" && !url.hasQuery());
        return {200, spacesBody()};
    };
    auto changed = transport.selectSpace("arcade", "desktop");
    require(changed.ok() && changed.value->selectedId == "arcade" && https.requests == 1);
    const auto body = QJsonDocument::fromJson(https.bodies.back()).object();
    require(https.methods.back() == "POST" && body == QJsonObject{{"space_id", "arcade"}, {"previous_space_id", "desktop"}});
    https.dropReply = true;
    require(!transport.selectSpace("desktop", "arcade").ok() && https.requests == 2);
    https.dropReply = false;
    for (const int status : {202, 307, 401, 403, 409, 500}) {
        https.redirectLocation = status == 307 ? QByteArray("/must-not-follow") : QByteArray{};
        https.handler = [&](const QUrl& url) -> std::pair<int, QByteArray> {
            require(url.path() == "/polaris/v1/spaces/select");
            return {status, spacesBody()};
        };
        const int before = https.requests;
        require(!transport.selectSpace("arcade", "desktop").ok() && https.requests == before + 1);
    }
    const int before = https.requests;
    require(!transport.selectSpace("arcade", "desktop", [] { return true; }).ok());
    require(!transport.selectSpace("../escape", "desktop").ok() && https.requests == before);
    DeckPolarisClient wrongPin({"127.0.0.1", https.serverPort()},
        {client.cert.toStdString(), client.key.toStdString(), client.cert.toStdString()}, std::chrono::milliseconds(700));
    require(wrongPin.selectSpace("arcade", "desktop").status == DeckPolarisRequestStatus::CertMismatch && https.requests == before);

    identity::DeckMoonlightIdentity saved;
    saved.loaded = true; saved.clientCertificatePem = client.cert.toStdString();
    saved.clientPrivateKeyPemForBackendOnly = client.key.toStdString();
    identity::DeckMoonlightHostRecord host;
    host.uuid = "spaces-host"; host.manualAddress = "127.0.0.1"; host.nativeHttpsPort = https.serverPort();
    host.serverCertificatePem = server.cert.toStdString(); saved.hosts.push_back(host);
    backend::DeckLiveHostLibrarySnapshot snapshot; snapshot.selectedHostId = host.uuid;
    backend::DeckLiveHostProbe probe; probe.hostId = host.uuid; probe.status = DeckPolarisRequestStatus::Ok;
    probe.spacesSupported = true; probe.spaces = parseSpaces(spacesBody().toStdString());
    snapshot.probes.push_back(probe);
    snapshot.library.games.push_back({.id = "space.arcade.7", .appId = kSpaceAppId, .name = "Fixture Game"});
    auto target = runtime::nativeTargetResolver(saved, snapshot)("spaces-host", "space.arcade.7");
    require(target && !target->automaticReconnect && !target->hostTelemetry);
    int launches = 0, status = 200;
    https.redirectLocation.clear();
    https.handler = [&](const QUrl& url) -> std::pair<int, QByteArray> {
        if (url.path() == "/polaris/v1/spaces") return {status, spacesBody()};
        if (url.path().contains("space-artwork")) return {200, "fixture-image"};
        require(url.path() == "/launch" || url.path() == "/resume");
        require(QUrlQuery(url).queryItemValue("appuuid") == "space.arcade.7");
        ++launches; return {200, "<root status_code=\"200\"/>"};
    };
    const std::string launch = "/launch?appid=1347244801&appuuid=space.arcade.7";
    require(target->fetch(launch).transportOk && launches == 1);
    selected = "desktop";
    require(!target->fetch(launch).transportOk && launches == 1);
    require(!target->fetch("/resume?appid=1347244801&appuuid=space.arcade.7").transportOk && launches == 1);
    selected = "arcade"; state = "in_use"; canOpen = false;
    require(!target->fetch(launch).transportOk && launches == 1);
    require(target->fetch("/polaris/v1/games/space.arcade.7/space-artwork/poster").transportOk);
    state = "ready"; canOpen = true; status = 403;
    require(!target->fetch(launch).transportOk && launches == 1);
    status = 200;
    require(target->fetch(launch).transportOk && launches == 2);
    snapshot.library.games.front().id = "space.other.7";
    require(!runtime::nativeTargetResolver(saved, snapshot)("spaces-host", "space.other.7"));
    snapshot.library.games.front().id = "space.arcade";
    require(!runtime::nativeTargetResolver(saved, snapshot)("spaces-host", "space.arcade"));
    snapshot.library.games.front().id = "desktop-game";
    snapshot.library.games.front().appId = 7;
    require(!runtime::nativeTargetResolver(saved, snapshot)("spaces-host", "desktop-game"));
    selected = "desktop"; snapshot.probes.front().spaces = parseSpaces(spacesBody().toStdString());
    target = runtime::nativeTargetResolver(saved, snapshot)("spaces-host", "desktop-game");
    require(target.has_value());
    selected = "arcade";
    require(!target->fetch("/launch?appid=7&appuuid=desktop-game").transportOk && launches == 2);

    // The production reader uses only the selected library. A missing legacy
    // route is distinct from denied/malformed Spaces and a concurrent change.
    QTcpServer discovery;
    require(discovery.listen(QHostAddress::LocalHost, 0));
    QObject::connect(&discovery, &QTcpServer::newConnection, &discovery, [&] {
        while (auto* socket = discovery.nextPendingConnection()) {
            QObject::connect(socket, &QTcpSocket::readyRead, socket, [&, socket] {
                if (!socket->readAll().contains("/serverinfo")) return;
                const QByteArray body = "<root status_code=\"200\"><HttpsPort>" + QByteArray::number(https.serverPort()) + "</HttpsPort></root>";
                socket->write("HTTP/1.1 200 OK\r\nConnection: close\r\nContent-Length: " + QByteArray::number(body.size()) + "\r\n\r\n" + body);
                socket->disconnectFromHost();
            });
            QObject::connect(socket, &QTcpSocket::disconnected, socket, &QObject::deleteLater);
        }
    });
    host.manualPort = discovery.serverPort();
    int spacesStatus = 200, libraryStatus = 200;
    bool changeDuringRead = false, disabled = false;
    int scopedReads = 0, desktopReads = 0, legacyReads = 0;
    https.handler = [&](const QUrl& url) -> std::pair<int, QByteArray> {
        if (url.path() == "/polaris/v1/capabilities")
            return {200, R"({"server":"polaris","features":{"game_library":true,"spaces_v1":true}})"};
        if (url.path() == "/polaris/v1/spaces") return {spacesStatus, disabled
            ? QByteArray(R"({"schema":1,"status":true,"enabled":false,"available":false,"can_switch":false,"selected_space_id":"","spaces":[]})")
            : spacesBody()};
        if (url.path() == "/polaris/v1/spaces/library") {
            ++scopedReads;
            require(QUrlQuery(url).queryItemValue("space_id") == "arcade");
            if (changeDuringRead) selected = "desktop";
            return {libraryStatus, R"({"schema":1,"status":true,"space_id":"arcade","library_available":true,"games":[{"id":"space.arcade.7","app_id":1347244801,"name":"Fixture","space":{"id":"arcade","name":"Arcade","target":"7"}}],"total":1})"};
        }
        require(url.path() == "/polaris/v1/games");
        if (QUrlQuery(url).queryItemValue("environment") == "desktop") ++desktopReads;
        else ++legacyReads;
        return {200, R"({"games":[{"id":"desktop-game","app_id":7,"name":"Fixture"}],"total":1})"};
    };
    const auto fetch = backend::polarisNetworkFetcher(saved, std::chrono::milliseconds(700));
    auto library = fetch(host, true);
    require(library.status == DeckPolarisRequestStatus::Ok && library.games.size() == 1 &&
        library.games.front().id == "space.arcade.7" && scopedReads == 1 && desktopReads == 0 && legacyReads == 0);
    libraryStatus = 403; library = fetch(host, true);
    require(library.status == DeckPolarisRequestStatus::Unauthorized && library.games.empty() && desktopReads == 0 && legacyReads == 0);
    libraryStatus = 200; changeDuringRead = true; library = fetch(host, true);
    require(library.status == DeckPolarisRequestStatus::MalformedBody && library.games.empty() && desktopReads == 0 && legacyReads == 0);
    changeDuringRead = false; library = fetch(host, true);
    require(library.status == DeckPolarisRequestStatus::Ok && library.games.front().id == "desktop-game" && desktopReads == 1);
    spacesStatus = 403; library = fetch(host, true);
    require(library.status == DeckPolarisRequestStatus::Unauthorized && library.games.empty() && legacyReads == 0 && desktopReads == 1);
    spacesStatus = 404; library = fetch(host, true);
    require(library.status == DeckPolarisRequestStatus::Ok && !library.spacesSupported && legacyReads == 1);
    spacesStatus = 200; disabled = true; library = fetch(host, true);
    require(library.status == DeckPolarisRequestStatus::Ok && library.spaces && !library.spaces->enabled && legacyReads == 2);
}

void testSleepNeverReplays() {
    using namespace nova::deck::polaris;
    QTemporaryDir directory;
    const auto server = createIdentity(directory.path(), "server");
    const auto client = createIdentity(directory.path(), "client");
    TlsServer https(server); https.trustClient(client.cert);
    require(https.listen(QHostAddress::LocalHost, 0));
    DeckPolarisClient transport({"127.0.0.1", https.serverPort()},
        {client.cert.toStdString(), client.key.toStdString(), server.cert.toStdString()}, std::chrono::milliseconds(700));
    https.handler = [](const QUrl& url) -> std::pair<int, QByteArray> {
        require(url.path() == "/polaris/v1/host/sleep" && !url.hasQuery());
        return {200, R"({"status":true})"};
    };
    auto result = transport.requestHostSleep();
    require(result.ok() && result.value->accepted && https.requests == 1);
    require(https.methods.back() == "POST" && https.bodies.back() == "{}");
    https.dropReply = true;
    result = transport.requestHostSleep();
    require(!result.ok() && https.requests == 2); // Includes all Qt transport retries.
    https.dropReply = false;
    for (const int status : {202, 307, 401, 403, 409, 500}) {
        https.redirectLocation = status == 307 ? QByteArray("/must-not-follow") : QByteArray{};
        https.handler = [status](const QUrl& url) -> std::pair<int, QByteArray> {
            require(url.path() == "/polaris/v1/host/sleep");
            return {status, status == 202 ? R"({"status":true})" : R"({"status":false,"code":"blocked","error":"Sleep is disabled."})"};
        };
        const int before = https.requests;
        result = transport.requestHostSleep();
        require((!result.ok() || !result.value->accepted) && https.requests == before + 1);
        if (status == 403 || status == 500) require(result.value && result.value->message == "Sleep is disabled.");
    }
    const int before = https.requests;
    result = transport.requestHostSleep([] { return true; });
    require(!result.ok() && https.requests == before);
    const auto derived = createDerivedServer(directory.path());
    TlsServer wrong(derived); wrong.trustClient(client.cert);
    require(wrong.listen(QHostAddress::LocalHost, 0));
    DeckPolarisClient rejected({"127.0.0.1", wrong.serverPort()},
        {client.cert.toStdString(), client.key.toStdString(), server.cert.toStdString()}, std::chrono::milliseconds(700));
    result = rejected.requestHostSleep();
    require(!result.ok() && result.status == DeckPolarisRequestStatus::CertMismatch && wrong.requests == 0);
}


void testHudStatusRead() {
    using namespace nova::deck::polaris;
    QTemporaryDir directory;
    const auto server = createIdentity(directory.path(), "server");
    const auto client = createIdentity(directory.path(), "client");
    TlsServer https(server); https.trustClient(client.cert);
    require(https.listen(QHostAddress::LocalHost, 0));
    DeckPolarisClient transport({"127.0.0.1", https.serverPort()},
        {client.cert.toStdString(), client.key.toStdString(), server.cert.toStdString()}, std::chrono::milliseconds(700));
    const QByteArray sample = R"({"streaming_active":true,"owned_by_client":true,"client_role":"owner","controls":{"host_tuning_allowed":false},"game_id":17,"game_uuid":"fixture-game","session_token":"fixture-token"})";
    for (const int status : {200, 202, 307, 401, 403, 404, 500}) {
        https.redirectLocation = status == 307 ? QByteArray("/must-not-follow") : QByteArray{};
        https.handler = [&, status](const QUrl& url) -> std::pair<int, QByteArray> {
            require(url.path() == "/polaris/v1/session/status" && !url.hasQuery()); return {status, sample};
        };
        const int before = https.requests;
        const auto result = transport.fetchHostTelemetry();
        require(result.ok() == (status == 200) && https.requests == before + 1);
        require(https.methods.back() == "GET" && https.bodies.back().isEmpty());
        if (status == 200) require(result.value->authorityValid && result.value->gameId == 17);
    }
    https.redirectLocation.clear();
    const int before = https.requests;
    require(!transport.fetchHostTelemetry([] { return true; }).ok() && https.requests == before);
    https.dribbleReply = true;
    QElapsedTimer elapsed; elapsed.start();
    const auto result = transport.fetchHostTelemetry([&] { return elapsed.elapsed() >= 80; });
    require(!result.ok() && elapsed.elapsed() < 400 && https.requests == before + 1);
    https.dribbleReply = false;
    https.handler = [](const QUrl&) -> std::pair<int, QByteArray> { return {200, QByteArray(128 * 1024 + 1, ' ')}; };
    require(transport.fetchHostTelemetry().status == DeckPolarisRequestStatus::MalformedBody);
    const auto derived = createDerivedServer(directory.path());
    TlsServer wrong(derived); wrong.trustClient(client.cert);
    require(wrong.listen(QHostAddress::LocalHost, 0));
    DeckPolarisClient rejected({"127.0.0.1", wrong.serverPort()},
        {client.cert.toStdString(), client.key.toStdString(), server.cert.toStdString()}, std::chrono::milliseconds(700));
    require(rejected.fetchHostTelemetry().status == DeckPolarisRequestStatus::CertMismatch && wrong.requests == 0);
}


void testLiveTuningNeverReplays() {
    using namespace nova::deck::polaris;
    QTemporaryDir directory;
    const auto server = createIdentity(directory.path(), "server");
    const auto client = createIdentity(directory.path(), "client");
    TlsServer https(server); https.trustClient(client.cert);
    require(https.listen(QHostAddress::LocalHost, 0));
    DeckPolarisClient transport({"127.0.0.1", https.serverPort()},
        {client.cert.toStdString(), client.key.toStdString(), server.cert.toStdString()}, std::chrono::milliseconds(700));
    const DeckLiveTuningTelemetry observed{false, true, "off", QString(64, 'a'), "instance", "fixture-session", 1, 41, 20000, 0, 20000};
    for (const int status : {200, 202, 307, 401, 403, 409, 412, 500}) {
        https.redirectLocation = status == 307 ? QByteArray("/must-not-follow") : QByteArray{};
        https.handler = [status](const QUrl& url) -> std::pair<int, QByteArray> {
            require(url.path() == "/polaris/v1/session/adaptive-bitrate" && !url.hasQuery());
            return {status, R"({"status":true})"};
        };
        const int before = https.requests;
        const auto result = transport.setLiveTuningEnabled(true, observed);
        require((result.ok() && *result.value) == (status == 200) && https.requests == before + 1);
        require(https.methods.back() == "POST");
        const auto payload = QJsonDocument::fromJson(https.bodies.back()).object();
        require(payload.size() == 4 && payload.value("enabled").isBool() && payload.value("enabled").toBool() &&
            payload.value("app_session_id") == "fixture-session" && payload.value("session_generation") == 41 &&
            payload.value("configuration_revision") == QString(64, 'a'));
    }
    https.redirectLocation.clear(); https.dropReply = true;
    int before = https.requests;
    require(!transport.setLiveTuningEnabled(false, observed).ok() && https.requests == before + 1);
    require(QJsonDocument::fromJson(https.bodies.back()).object().value("enabled") == false);
    https.dropReply = false;
    for (const auto* body : {R"({"status":false})", R"({"status":"true"})", R"({})"}) {
        https.handler = [body](const QUrl&) -> std::pair<int, QByteArray> { return {200, body}; };
        const auto result = transport.setLiveTuningEnabled(true, observed);
        require(!result.ok() || !*result.value);
    }
    before = https.requests;
    require(!transport.setLiveTuningEnabled(true, observed, [] { return true; }).ok() && https.requests == before);
    auto invalid = observed; invalid.revision = "invalid";
    require(!transport.setLiveTuningEnabled(true, invalid).ok() && https.requests == before);
    invalid = observed; invalid.generation = 0;
    require(!transport.setLiveTuningEnabled(true, invalid).ok() && https.requests == before);
    https.dribbleReply = true;
    QElapsedTimer elapsed; elapsed.start();
    require(!transport.setLiveTuningEnabled(true, observed, [&] { return elapsed.elapsed() >= 80; }).ok());
    require(elapsed.elapsed() < 400 && https.requests == before + 1);
    https.dribbleReply = false;
    https.handler = [](const QUrl&) -> std::pair<int, QByteArray> { return {200, QByteArray(128 * 1024 + 1, ' ')}; };
    require(transport.setLiveTuningEnabled(true, observed).status == DeckPolarisRequestStatus::MalformedBody);
    const auto derived = createDerivedServer(directory.path());
    TlsServer wrong(derived); wrong.trustClient(client.cert);
    require(wrong.listen(QHostAddress::LocalHost, 0));
    DeckPolarisClient rejected({"127.0.0.1", wrong.serverPort()},
        {client.cert.toStdString(), client.key.toStdString(), server.cert.toStdString()}, std::chrono::milliseconds(700));
    require(rejected.setLiveTuningEnabled(true, observed).status == DeckPolarisRequestStatus::CertMismatch && wrong.requests == 0);

    // Exercise the real observer-thread client as well as the direct transport.
    // The only listening host is this isolated paired loopback fixture.
    int sequence = 0, mutations = 0; bool enabled = false;
    https.handler = [&](const QUrl& url) -> std::pair<int, QByteArray> {
        if (url.path() == "/polaris/v1/session/adaptive-bitrate") {
            ++mutations; enabled = true; return {200, R"({"status":true})"};
        }
        require(url.path() == "/polaris/v1/session/status");
        const QJsonObject live{{"version", 1}, {"scope", "host"}, {"enabled", enabled}, {"supported", true},
            {"state", enabled ? "stable" : "off"}, {"quality_limit_kbps", 20000}, {"requested_bitrate_kbps", 14000},
            {"applied_bitrate_kbps", 20000}, {"session_generation", 41}, {"app_session_id", "fixture-session"},
            {"host_instance", "instance"}, {"sequence", ++sequence}, {"configuration_revision", QString(64, enabled ? 'b' : 'a')}};
        const QJsonObject status{{"streaming_active", true}, {"owned_by_client", true}, {"client_role", "owner"},
            {"controls", QJsonObject{{"host_tuning_allowed", true}}}, {"game_id", 17}, {"game_uuid", "fixture-game"},
            {"session_token", "fixture-token"}, {"session_generation", 41}, {"app_session_id", "fixture-session"}, {"live_tuning", live}};
        return {200, QJsonDocument(status).toJson(QJsonDocument::Compact)};
    };
    const int port = https.serverPort();
    nova::deck::runtime::DeckHudHostObserver observer([&, port]() -> std::optional<nova::deck::runtime::DeckHudHostTarget> {
        auto network = std::make_shared<DeckPolarisClient>(DeckPolarisEndpoint{"127.0.0.1", port},
            DeckPolarisTlsIdentity{client.cert.toStdString(), client.key.toStdString(), server.cert.toStdString()}, std::chrono::milliseconds(700));
        return nova::deck::runtime::DeckHudHostTarget{
            [network](const std::function<bool()>& stop) { return network->fetchHostTelemetry(stop); }, [] { return true; },
            [network](bool on, const DeckLiveTuningTelemetry& seen, const std::function<bool()>& stop) { return network->setLiveTuningEnabled(on, seen, stop); }};
    }, {17, "fixture-game", "fixture-token"});
    const auto until = [](const std::function<bool()>& predicate) {
        QElapsedTimer timer; timer.start();
        while (!predicate() && timer.elapsed() < 3000) { QCoreApplication::processEvents(); QThread::msleep(1); }
        require(predicate());
    };
    until([&] { return observer.snapshot().value("canTune").toBool(); });
    require(observer.setLiveTuningEnabled(true));
    until([&] { return !observer.snapshot().value("tuningBusy").toBool(); });
    require(mutations == 1 && observer.snapshot().value("tuningEnabled").toBool() && observer.snapshot().value("appliedBitrate") == "20.0M");
}


void testFixedBitrateNeverReplays() {
    using namespace nova::deck::polaris;
    QTemporaryDir directory;
    const auto server = createIdentity(directory.path(), "server"), client = createIdentity(directory.path(), "client");
    TlsServer https(server); https.trustClient(client.cert);
    require(https.listen(QHostAddress::LocalHost, 0));
    DeckPolarisClient transport({"127.0.0.1", https.serverPort()},
        {client.cert.toStdString(), client.key.toStdString(), server.cert.toStdString()}, std::chrono::milliseconds(700));
    DeckLiveTuningTelemetry observed{true, true, "stable", QString(64, 'a'), "instance", "fixture-session", 1, 41, 20000, 14000, 20000};
    for (int status : {200, 202, 307, 401, 403, 409, 412, 500}) {
        https.redirectLocation = status == 307 ? QByteArray("/must-not-follow") : QByteArray{};
        https.handler = [status](const QUrl& url) -> std::pair<int, QByteArray> {
            require(url.path() == "/polaris/v1/session/bitrate" && !url.hasQuery());
            return {status, R"({"status":true,"bitrate_kbps":15000})"};
        };
        const int before = https.requests;
        auto result = transport.setFixedBitrate(15000, observed);
        require((result.ok() && *result.value) == (status == 200) && https.requests == before + 1 && https.methods.back() == "POST");
        const auto body = QJsonDocument::fromJson(https.bodies.back()).object();
        require(body.size() == 3 && body.value("bitrate_kbps") == 15000 && body.value("session_generation") == 41 && body.value("app_session_id") == "fixture-session");
    }
    https.redirectLocation.clear(); https.dropReply = true;
    int before = https.requests;
    require(!transport.setFixedBitrate(15000, observed).ok() && https.requests == before + 1);
    https.dropReply = false;
    before = https.requests;
    for (int invalid : {-1, 0, 999, 300001}) require(!transport.setFixedBitrate(invalid, observed).ok());
    require(!transport.setFixedBitrate(15000, observed, [] { return true; }).ok() && https.requests == before);
    auto invalid = observed; invalid.generation = 0;
    require(!transport.setFixedBitrate(15000, invalid).ok() && https.requests == before);
    for (const auto* body : {R"({"status":false})", R"({"status":"true"})", R"({})"}) {
        https.handler = [body](const QUrl&) -> std::pair<int, QByteArray> { return {200, body}; };
        const auto result = transport.setFixedBitrate(15000, observed); require(!result.ok() || !*result.value);
    }
    https.handler = [](const QUrl&) -> std::pair<int, QByteArray> { return {200, R"({"status":true})"}; };
    require(transport.setFixedBitrate(1000, observed).ok() && transport.setFixedBitrate(300000, observed).ok());
    before = https.requests; https.dribbleReply = true; QElapsedTimer elapsed; elapsed.start();
    require(!transport.setFixedBitrate(15000, observed, [&] { return elapsed.elapsed() >= 80; }).ok());
    require(elapsed.elapsed() < 400 && https.requests == before + 1); https.dribbleReply = false;
    https.handler = [](const QUrl&) -> std::pair<int, QByteArray> { return {200, QByteArray(128 * 1024 + 1, ' ')}; };
    require(transport.setFixedBitrate(15000, observed).status == DeckPolarisRequestStatus::MalformedBody);
    const auto derived = createDerivedServer(directory.path()); TlsServer wrong(derived); wrong.trustClient(client.cert);
    require(wrong.listen(QHostAddress::LocalHost, 0));
    DeckPolarisClient rejected({"127.0.0.1", wrong.serverPort()},
        {client.cert.toStdString(), client.key.toStdString(), server.cert.toStdString()}, std::chrono::milliseconds(700));
    require(rejected.setFixedBitrate(15000, observed).status == DeckPolarisRequestStatus::CertMismatch && wrong.requests == 0);
}

void testSessionEventTransport() {
    using namespace nova::deck::polaris;
    QTemporaryDir directory;
    const auto server = createIdentity(directory.path(), "server"), client = createIdentity(directory.path(), "client");
    TlsServer https(server); https.trustClient(client.cert);
    require(https.listen(QHostAddress::LocalHost, 0));
    // Deliberately different base port: only the authenticated advertisement is used.
    DeckPolarisClient transport({"127.0.0.1", 1},
        {client.cert.toStdString(), client.key.toStdString(), server.cert.toStdString()}, std::chrono::milliseconds(400));
    int notices = 0;
    const auto refresh = [&] { ++notices; };
    const QByteArray head = "HTTP/1.1 200 OK\r\nContent-Type: text/event-stream; charset=utf-8\r\nConnection: close\r\n\r\n";
    https.eventStream = [head](QSslSocket* socket) {
        socket->write(head + "id: epoch:1\nevent: session\ndata: {\"event\":\"stream_active\"}\n\n");
        socket->disconnectFromHost();
    };
    auto result = transport.watchSessionEvents(https.serverPort(), refresh, [] { return false; });
    require(result.ok() && notices == 2 && https.requests == 1 && https.paths.back() == "/api/polaris/events");
    require(https.methods.back() == "GET" && https.headers.back().contains("Accept: text/event-stream") &&
        !https.headers.back().toLower().contains("last-event-id") && !https.headers.back().contains("Authorization:"));
    // Fresh connection resynchronizes, never resumes a retained cursor.
    require(transport.watchSessionEvents(https.serverPort(), refresh, [] { return false; }).ok() && notices == 4);
    int before = https.requests;
    require(!transport.watchSessionEvents(0, refresh, [] { return false; }).ok());
    require(!transport.watchSessionEvents(65536, refresh, [] { return false; }).ok());
    require(!transport.watchSessionEvents(https.serverPort(), refresh, [] { return true; }).ok() && https.requests == before);
    for (int status : {204, 302, 401, 403, 404, 405, 500}) {
        const int n = notices;
        https.eventStream = [status](QSslSocket* socket) {
            socket->write("HTTP/1.1 " + QByteArray::number(status) + " Reply\r\nLocation: https://127.0.0.1:1/elsewhere\r\nContent-Length: 0\r\n\r\n");
            socket->disconnectFromHost();
        };
        result = transport.watchSessionEvents(https.serverPort(), refresh, [] { return false; });
        require(!result.ok() && result.httpStatus == status && notices == n && https.requests == ++before);
        if (status == 401 || status == 403) require(result.status == DeckPolarisRequestStatus::Unauthorized);
    }
    https.eventStream = [](QSslSocket* socket) {
        socket->write("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: 2\r\n\r\n{}"); socket->disconnectFromHost();
    };
    require(transport.watchSessionEvents(https.serverPort(), refresh, [] { return false; }).status == DeckPolarisRequestStatus::MalformedBody);
    for (const QByteArray bad : {QByteArray("data: []\n\n"), QByteArray("data: {}\n"), QByteArray(65537, 'x')}) {
        https.eventStream = [head, bad](QSslSocket* socket) { socket->write(head + bad); socket->disconnectFromHost(); };
        require(transport.watchSessionEvents(https.serverPort(), refresh, [] { return false; }).status == DeckPolarisRequestStatus::MalformedBody);
    }
    // Keep the socket active with incomplete bytes: only complete events extend idle time.
    https.eventStream = [head](QSslSocket* socket) {
        socket->write(head);
        auto* timer = new QTimer(socket);
        QObject::connect(timer, &QTimer::timeout, socket, [socket] { socket->write("x"); }); timer->start(10);
    };
    QElapsedTimer elapsed; elapsed.start();
    result = transport.watchSessionEvents(https.serverPort(), refresh, [] { return false; }, std::chrono::milliseconds(100));
    require(result.status == DeckPolarisRequestStatus::Timeout && elapsed.elapsed() < 600);
    elapsed.restart();
    result = transport.watchSessionEvents(https.serverPort(), refresh, [&] { return elapsed.elapsed() > 60; });
    require(result.status == DeckPolarisRequestStatus::Timeout && elapsed.elapsed() < 400);
    // Cancelling from a delivered event suppresses later records in that chunk.
    https.eventStream = [head](QSslSocket* socket) {
        socket->write(head + "id: e:1\nevent: session\ndata: {}\n\nid: e:2\nevent: session\ndata: {}\n\n");
        socket->disconnectFromHost();
    };
    int delivered = 0;
    result = transport.watchSessionEvents(https.serverPort(), [&] { ++delivered; }, [&] { return delivered >= 2; });
    require(delivered == 2); // Connection boundary plus first event, no late callback.
    // Healthy long-lived records survive multiple idle windows and are cancellable.
    https.eventStream = [head](QSslSocket* socket) {
        socket->write(head);
        auto* timer = new QTimer(socket);
        QObject::connect(timer, &QTimer::timeout, socket, [socket, seq = 0]() mutable {
            socket->write("id: epoch:" + QByteArray::number(++seq) + "\nevent: session\ndata: {}\n\n");
        }); timer->start(20);
    };
    elapsed.restart();
    result = transport.watchSessionEvents(https.serverPort(), refresh, [&] { return elapsed.elapsed() > 250; }, std::chrono::milliseconds(80));
    require(result.status == DeckPolarisRequestStatus::Timeout && elapsed.elapsed() >= 250 && elapsed.elapsed() < 600);
    const auto derived = createDerivedServer(directory.path()); TlsServer wrong(derived);
    require(wrong.listen(QHostAddress::LocalHost, 0));
    const int n = notices;
    require(transport.watchSessionEvents(wrong.serverPort(), refresh, [] { return false; }).status == DeckPolarisRequestStatus::CertMismatch);
    require(wrong.requests == 0 && notices == n);

    // Production target factory: status and events use distinct authenticated
    // ports/threads. Event content only prompts a status GET, never paints HUD.
    using namespace nova::deck;
    TlsServer status(server); status.trustClient(client.cert);
    require(status.listen(QHostAddress::LocalHost, 0));
    QSslSocket* eventSocket = nullptr;
    https.eventStream = [&](QSslSocket* socket) { eventSocket = socket; socket->write(head); };
    int sequence = 0, applied = 20000; bool owned = true;
    status.handler = [&](const QUrl& url) -> std::pair<int, QByteArray> {
        require(url.path() == "/polaris/v1/session/status");
        const QJsonObject live{{"version", 1}, {"scope", "host"}, {"enabled", false}, {"supported", true},
            {"state", "off"}, {"configuration_revision", QString(64, 'a')}, {"host_instance", "instance"},
            {"app_session_id", "fixture-session"}, {"session_generation", 41}, {"sequence", ++sequence},
            {"quality_limit_kbps", 20000}, {"requested_bitrate_kbps", 20000}, {"applied_bitrate_kbps", applied}};
        const QJsonObject envelope{{"streaming_active", true}, {"owned_by_client", owned}, {"client_role", owned ? "owner" : "viewer"},
            {"controls", QJsonObject{{"host_tuning_allowed", owned}}}, {"game_id", 17}, {"game_uuid", "fixture-game"},
            {"session_token", "fixture-token"}, {"session_generation", 41}, {"app_session_id", "fixture-session"},
            {"events_https_port", https.serverPort()}, {"live_tuning", live},
            {"doctor", QJsonObject{{"version", 2}, {"result_id", "private-result"}, {"status", "ok"},
                {"severity", "info"}, {"traffic_light", "green"}, {"primary_issue", "none"}, {"evidence", QJsonArray{}}}}};
        return {200, QJsonDocument(envelope).toJson(QJsonDocument::Compact)};
    };
    identity::DeckMoonlightIdentity saved;
    saved.loaded = true; saved.sourceLabel = "nova-native";
    saved.clientCertificatePem = client.cert.toStdString(); saved.clientPrivateKeyPemForBackendOnly = client.key.toStdString();
    identity::DeckMoonlightHostRecord host;
    host.uuid = "event-host"; host.manualAddress = "127.0.0.1"; host.nativeHttpsPort = status.serverPort();
    host.serverCertificatePem = server.cert.toStdString(); saved.hosts.push_back(host);
    backend::DeckLiveHostLibrarySnapshot snapshot; snapshot.selectedHostId = host.uuid;
    DeckPolarisGame game; game.id = "fixture-game"; game.appId = 17; game.name = "Fixture";
    snapshot.library.games.push_back(backend::toLibraryGame(game));
    const auto target = runtime::nativeTargetResolver(saved, snapshot)("event-host", "fixture-game");
    require(target && target->hostTelemetry);
    auto observer = std::make_unique<runtime::DeckHudHostObserver>(target->hostTelemetry,
        runtime::DeckHudHostContext{17, "fixture-game", "fixture-token"}, runtime::DeckHudHostTiming{3000, 3500, 6000});
    const auto until = [](const std::function<bool()>& predicate) {
        QElapsedTimer timer; timer.start();
        while (!predicate() && timer.elapsed() < 1500) { QCoreApplication::processEvents(); QThread::msleep(1); }
        require(predicate());
    };
    until([&] { return eventSocket && status.requests >= 2 && observer->snapshot().value("canTune").toBool(); });
    require(observer->snapshot().value("doctor").toMap().value("available").toBool());
    const int refreshReads = status.requests;
    require(observer->refreshDiagnostics() && !observer->refreshDiagnostics());
    until([&] { return status.requests > refreshReads && !observer->snapshot().value("diagnosticsRefreshing").toBool(); });
    require(observer->snapshot().value("doctor").toMap().value("title") == "No confirmed issue");
    for (const auto& method : status.methods) require(method == "GET");
    applied = 15000; const int prior = status.requests;
    eventSocket->write("id: stream:7\nevent: session\ndata: {\"event\":\"session_ended\",\"applied_bitrate_kbps\":300000}\n\n");
    until([&] { return status.requests > prior && observer->snapshot().value("appliedBitrateKbps") == 15000; });
    require(observer->snapshot().value("canTune").toBool()); // Foreign terminal hint cannot end our stream.
    owned = false;
    eventSocket->write("id: stream:9\nevent: state\ndata: {}\n\n");
    const int ownerReads = status.requests;
    until([&] { return status.requests > ownerReads && !observer->snapshot().value("hostFresh").toBool(); });
    require(!observer->setLiveTuningEnabled(true));
    require(observer->snapshot().value("doctor").toMap().isEmpty());
    elapsed.restart(); observer.reset(); require(elapsed.elapsed() < 500);
}

void testDoctorTransportAndFactory() {
    using namespace nova::deck;
    using namespace nova::deck::polaris;
    QTemporaryDir directory;
    const auto server = createIdentity(directory.path(), "server"), client = createIdentity(directory.path(), "client");
    TlsServer https(server); https.trustClient(client.cert);
    require(https.listen(QHostAddress::LocalHost, 0));
    DeckPolarisClient transport({"127.0.0.1", https.serverPort()},
        {client.cert.toStdString(), client.key.toStdString(), server.cert.toStdString()}, std::chrono::milliseconds(700));
    const auto offer = *parseDoctorOffer(doctor_fixture::doctor());
    DeckDoctorRequest request{offer.action, offer.appSession, "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee", {}, offer.generation, offer};
    for (int status : {200, 202, 307, 401, 403, 409, 500}) {
        https.redirectLocation = status == 307 ? QByteArray("/must-not-follow") : QByteArray{};
        https.handler = [&](const QUrl& url) -> std::pair<int, QByteArray> {
            require(url.path() == "/polaris/v1/doctor/action" && !url.hasQuery());
            return {status, QJsonDocument(doctor_fixture::receipt(request)).toJson(QJsonDocument::Compact)};
        };
        const int before = https.requests;
        const auto result = transport.runDoctorAction(request);
        require(result.ok() == (status == 200) && https.requests == before + 1 && https.methods.back() == "POST");
        require(QJsonDocument::fromJson(https.bodies.back()).object() == *doctorRequestBody(request));
    }
    https.redirectLocation.clear(); https.dropReply = true;
    int before = https.requests;
    require(!transport.runDoctorAction(request).ok() && https.requests == before + 1);
    https.dropReply = false;
    for (const auto* outcome : {"rolled_back", "superseded", "rollback_unconfirmed"}) {
        https.handler = [&](const QUrl&) -> std::pair<int, QByteArray> {
            return {QString(outcome) == "rollback_unconfirmed" ? 409 : 200,
                QJsonDocument(doctor_fixture::receipt(request, outcome)).toJson(QJsonDocument::Compact)};
        };
        require(transport.runDoctorAction(request).ok());
    }
    https.handler = [&](const QUrl&) -> std::pair<int, QByteArray> {
        auto bad = doctor_fixture::receipt(request); bad["session_generation"] = 42;
        return {200, QJsonDocument(bad).toJson(QJsonDocument::Compact)};
    };
    require(transport.runDoctorAction(request).status == DeckPolarisRequestStatus::MalformedBody);
    before = https.requests;
    auto invalid = request; invalid.requestId.clear();
    require(!transport.runDoctorAction(invalid).ok() && !transport.runDoctorAction(request, [] { return true; }).ok() && https.requests == before);
    https.dribbleReply = true; QElapsedTimer elapsed; elapsed.start();
    require(!transport.runDoctorAction(request, [&] { return elapsed.elapsed() >= 80; }).ok());
    require(elapsed.elapsed() < 400 && https.requests == before + 1); https.dribbleReply = false;
    https.handler = [](const QUrl&) -> std::pair<int, QByteArray> { return {200, QByteArray(128 * 1024 + 1, ' ')}; };
    require(transport.runDoctorAction(request).status == DeckPolarisRequestStatus::MalformedBody);
    const auto derived = createDerivedServer(directory.path()); TlsServer wrong(derived); wrong.trustClient(client.cert);
    require(wrong.listen(QHostAddress::LocalHost, 0));
    DeckPolarisClient rejected({"127.0.0.1", wrong.serverPort()},
        {client.cert.toStdString(), client.key.toStdString(), server.cert.toStdString()}, std::chrono::milliseconds(700));
    require(rejected.runDoctorAction(request).status == DeckPolarisRequestStatus::CertMismatch && wrong.requests == 0);

    // Exercise the actual native-target factory, including scoped GET preflight,
    // the fixed paired POST and readback, without touching a real host.
    int sequence = 0, actions = 0; bool owned = true;
    https.handler = [&](const QUrl& url) -> std::pair<int, QByteArray> {
        if (url.path() == "/polaris/v1/session/status") {
            auto e = doctor_fixture::envelope(++sequence); e["owned_by_client"] = owned;
            return {200, QJsonDocument(e).toJson(QJsonDocument::Compact)};
        }
        require(url.path() == "/polaris/v1/doctor/action"); ++actions;
        const auto body = QJsonDocument::fromJson(https.bodies.back()).object();
        DeckDoctorRequest dispatched;
        dispatched.action = body["action_id"].toString(); dispatched.appSession = body["app_session_id"].toString();
        dispatched.generation = body["session_generation"].toInteger(); dispatched.requestId = body["request_id"].toString();
        dispatched.runId = body["run_id"].toString();
        return {200, QJsonDocument(doctor_fixture::receipt(dispatched)).toJson(QJsonDocument::Compact)};
    };
    identity::DeckMoonlightIdentity saved; saved.loaded = true; saved.sourceLabel = "nova-native";
    saved.clientCertificatePem = client.cert.toStdString(); saved.clientPrivateKeyPemForBackendOnly = client.key.toStdString();
    identity::DeckMoonlightHostRecord host; host.uuid = "doctor-host"; host.manualAddress = "127.0.0.1"; host.nativeHttpsPort = https.serverPort();
    host.serverCertificatePem = server.cert.toStdString(); saved.hosts.push_back(host);
    backend::DeckLiveHostLibrarySnapshot snapshot; snapshot.selectedHostId = host.uuid;
    DeckPolarisGame game; game.id = "private-game"; game.appId = 17; game.name = "Fixture";
    snapshot.library.games.push_back(backend::toLibraryGame(game));
    const auto target = runtime::nativeTargetResolver(saved, snapshot)("doctor-host", "private-game");
    require(target && target->hostTelemetry);
    auto observer = std::make_unique<runtime::DeckHudHostObserver>(target->hostTelemetry,
        runtime::DeckHudHostContext{17, "private-game", "private-token"}, runtime::DeckHudHostTiming{30, 3500, 100});
    const auto until = [](const auto& predicate) {
        QElapsedTimer timer; timer.start();
        while (!predicate() && timer.elapsed() < 2000) { QCoreApplication::processEvents(); QThread::msleep(1); }
        require(predicate());
    };
    until([&] { return observer->snapshot().value("doctorCanApply").toBool(); });
    require(observer->applyDoctorFix() && !observer->applyDoctorFix());
    until([&] { return observer->snapshot().value("doctorCanUndo").toBool(); });
    observer.reset();
    observer = std::make_unique<runtime::DeckHudHostObserver>(target->hostTelemetry,
        runtime::DeckHudHostContext{17, "private-game", "private-token"}, runtime::DeckHudHostTiming{30, 3500, 100});
    until([&] { return observer->snapshot().value("doctorCanCheck").toBool(); });
    require(actions == 1 && observer->snapshot().value("doctorActionState") == "recovered" &&
        !observer->undoDoctorFix() && !observer->applyDoctorFix());
    require(observer->checkDoctorResult());
    until([&] { return observer->snapshot().value("doctorActionState") == "resolved" && observer->snapshot().value("doctorCanUndo").toBool(); });
    require(actions == 2 && observer->undoDoctorFix());
    until([&] { return observer->snapshot().value("doctorActionState") == "undone"; }); require(actions == 3);
    owned = false; until([&] { return !observer->snapshot().value("hostFresh").toBool(); });
    require(!observer->applyDoctorFix() && !observer->undoDoctorFix());
    observer.reset();
}

void testArtworkMutationsNeverReplay() {
    using namespace nova::deck::polaris;
    QTemporaryDir directory;
    const auto server = createIdentity(directory.path(), "art-server");
    const auto client = createIdentity(directory.path(), "art-client");
    const auto wrong = createIdentity(directory.path(), "art-wrong");
    TlsServer https(server); https.trustClient(client.cert);
    require(https.listen(QHostAddress::LocalHost, 0));
    DeckPolarisClient api({"127.0.0.1", https.serverPort()}, {client.cert.toStdString(), client.key.toStdString(), server.cert.toStdString()}, std::chrono::milliseconds(300));
    const QVariantMap candidate{{"provider","steamgriddb"},{"provider_game_id","42"},{"title","A & B"}};
    const QVariantMap values{{"candidate",candidate},{"selections",QVariantMap{{"poster",QString(32,'a')}}}};
    https.handler = [&](const QUrl& url) -> std::pair<int,QByteArray> {
        require(!url.hasQuery());
        require(url.path() == "/polaris/v1/games/game/artwork/match" || url.path() == "/polaris/v1/games/game/artwork/override");
        if (url.path().endsWith("override")) require(https.methods.back() == "DELETE");
        else { require(https.methods.back() == "POST"); const auto body = QJsonDocument::fromJson(https.bodies.back()).object(); require(body["selections"].toObject()["poster"] == QJsonValue(QString(32,'a'))); }
        return {200,R"({"status":true,"artwork":{"revision":"2"}})"};
    };
    require(api.gameTool("game","apply",values).ok());
    require(api.gameTool("game","reset",{}).ok());
    https.handler = [&](const QUrl& url) -> std::pair<int,QByteArray> {
        require(url.path()=="/polaris/v1/games/space.room.42/space-artwork/resolve" && !url.hasQuery());
        require(https.methods.back()=="POST" && QJsonDocument::fromJson(https.bodies.back()).object()==QJsonObject{{"policy","missing_or_stale"}});
        return {200,R"({"version":1,"revision":"one","assets":{},"resolution":{"status":"healthy","requested_kinds":[],"remaining_kinds":[]}})"};
    };
    require(api.gameTool("space.room.42","spaceRefresh",{}).ok());
    int spaceBefore=https.requests;https.dropReply=true;
    require(!api.gameTool("space.room.42","spaceRefresh",{}).ok() && https.requests==spaceBefore+1);https.dropReply=false;
    spaceBefore=https.requests;
    require(!api.gameTool("space.room.big-picture-v1","spaceRefresh",{}).ok() && !api.gameTool("space.room.42","reset",{}).ok() && https.requests==spaceBefore);
    https.handler = [](const QUrl&) { return std::pair{200,QByteArray("{}")}; };
    for (const auto* action : {"apply","reset"}) {
        int before = https.requests;
        https.dropReply = true;
        require(!api.gameTool("game",action,values).ok() && https.requests == before + 1);
        https.dropReply = false;
    }
    for (const int status : {302,403,409,503}) {
        https.redirectLocation = "/polaris/v1/games/game/artwork/override";
        https.handler = [status](const QUrl&) { return std::pair{status,QByteArray("{}")}; };
        const int before = https.requests;
        require(!api.gameTool("game","reset",{}).ok() && https.requests == before + 1);
        require(!api.gameTool("space.room.42","spaceRefresh",{}).ok() && https.requests == before + 2);
    }
    DeckPolarisClient badPin({"127.0.0.1", https.serverPort()}, {client.cert.toStdString(),client.key.toStdString(),wrong.cert.toStdString()},std::chrono::milliseconds(300));
    const int before = https.requests;
    require(badPin.gameTool("game","reset",{}).status == DeckPolarisRequestStatus::CertMismatch && https.requests == before);
    require(!api.gameTool("../game","reset",{}).ok() && !api.gameTool("game","reset",{},[]{return true;}).ok() && https.requests == before);
}

void testHostSettingsNeverReplays() {
    using namespace nova::deck::polaris;
    QTemporaryDir directory;
    const auto server = createIdentity(directory.path(), "server");
    const auto client = createIdentity(directory.path(), "client");
    const auto wrong = createIdentity(directory.path(), "wrong");
    TlsServer https(server); https.trustClient(client.cert);
    require(https.listen(QHostAddress::LocalHost, 0));
    DeckPolarisClient api({"127.0.0.1", https.serverPort()}, {client.cert.toStdString(), client.key.toStdString(), server.cert.toStdString()}, std::chrono::milliseconds(300));
    auto settings = host_settings_fixture::settings();
    https.handler = [&](const QUrl& url) -> std::pair<int, QByteArray> {
        require(!url.hasQuery());
        if (url.path() == "/polaris/v1/session/status") return {200, R"({"state":"idle","streaming_active":false,"game_uuid":""})"};
        require(url.path() == "/polaris/v1/client-settings");
        if (https.methods.back() == "POST") {
            const auto body = QJsonDocument::fromJson(https.bodies.back()).object();
            require(body.size() == 1 && body["stream_display_mode"] == QJsonValue("desktop_display"));
            auto desired = settings["desired"].toObject(); desired["stream_display_mode"] = "desktop_display";
            settings["desired"] = desired; settings["relaunch_required"] = true; settings["revision"] = "2";
        }
        return {200, QJsonDocument(QJsonObject{{"status", true}, {"client_settings", settings}}).toJson(QJsonDocument::Compact)};
    };
    require(api.fetchHostSettings().ok() && api.fetchHostSettingsIdle().value == true);
    auto receipt = api.setHostDefaultMode("desktop_display");
    require(receipt.ok() && receipt.value->desiredMode == "desktop_display" && receipt.value->effectiveMode == "headless_stream");
    int before = https.requests;
    require(!api.setHostDefaultMode("desktop_display&appid=7").ok() && https.requests == before);
    require(!api.setHostDefaultMode("desktop_display", [] { return true; }).ok() && https.requests == before);
    https.dropReply = true;
    require(!api.setHostDefaultMode("desktop_display").ok() && https.requests == before + 1);
    https.dropReply = false;
    require(api.fetchHostSettings().ok() && https.requests == before + 2); // GET only after uncertain POST.
    for (const int status : {302, 403, 409, 503}) {
        https.redirectLocation = "/polaris/v1/client-settings";
        https.handler = [status](const QUrl&) { return std::pair{status, QByteArray("{\"status\":false}")}; };
        before = https.requests;
        require(!api.setHostDefaultMode("desktop_display").ok() && https.requests == before + 1);
    }
    DeckPolarisClient badPin({"127.0.0.1", https.serverPort()}, {client.cert.toStdString(), client.key.toStdString(), wrong.cert.toStdString()}, std::chrono::milliseconds(300));
    before = https.requests;
    require(badPin.setHostDefaultMode("desktop_display").status == DeckPolarisRequestStatus::CertMismatch && https.requests == before);
    QJsonObject expectedProfile{{"display_mode", "1920x1080x30"}, {"target_bitrate_kbps", 30000}};
    https.handler = [&](const QUrl& url) -> std::pair<int, QByteArray> {
        require(url.path() == "/polaris/v1/client-settings" && !url.hasQuery());
        if (https.methods.back() == "POST") {
            const auto body = QJsonDocument::fromJson(https.bodies.back()).object();
            require(body == expectedProfile);
            auto desired = settings["desired"].toObject();
            desired["display_mode"] = body.contains("clear_display_mode") ? QJsonValue("") : body["display_mode"];
            desired["target_bitrate_kbps"] = body.contains("clear_target_bitrate") ? QJsonValue(0) : body["target_bitrate_kbps"];
            settings["desired"] = desired;
        }
        return {200, QJsonDocument(QJsonObject{{"status", true}, {"client_settings", settings}}).toJson(QJsonDocument::Compact)};
    };
    receipt = api.setHostProfile("1920x1080x30", 30000, false);
    require(receipt.ok() && receipt.value->desiredDisplay == "1920x1080x30" && receipt.value->desiredBitrate == 30000 &&
        receipt.value->effectiveDisplay == "1280x800x60");
    before = https.requests;
    require(!api.setHostProfile("1920x1080x30&launch=7", 30000, false).ok() &&
        !api.setHostProfile("1920x1080x30", 30000, true).ok() &&
        !api.setHostProfile("", 0, false).ok() &&
        !api.setHostProfile("1920x1080x30", 30000, false, [] { return true; }).ok() && https.requests == before);
    https.dropReply = true;
    require(!api.setHostProfile("1920x1080x30", 30000, false).ok() && https.requests == before + 1);
    https.dropReply = false;
    require(api.fetchHostSettings().ok() && https.requests == before + 2);
    expectedProfile = {{"clear_display_mode", true}, {"clear_target_bitrate", true}};
    receipt = api.setHostProfile("", 0, true);
    require(receipt.ok() && receipt.value->desiredDisplay.isEmpty() && receipt.value->desiredBitrate == 0);
    DeckLiveTuningTelemetry observed;
    observed.supported = true; observed.appSession = "private-session"; observed.generation = 41;
    expectedProfile = {{"display_mode", "1920x1080x30"}, {"target_bitrate_kbps", 30000},
        {"app_session_id", "private-session"}, {"session_generation", 41}};
    receipt = api.setSessionProfile("1920x1080x30", 30000, false, observed);
    require(receipt.ok() && receipt.value->desiredBitrate == 30000 && receipt.value->effectiveBitrate == 20000);
    before = https.requests;
    require(!api.setSessionProfile("1920x1080x30",30000,false,observed,[]{ return true; }).ok() &&
        !api.setSessionProfile("",0,false,observed).ok() && !api.setSessionProfile("1920x1080x30",30000,true,observed).ok());
    for (int invalid = 0; invalid < 5; ++invalid) {
        auto bad = observed;
        if (invalid == 0) bad.generation = 0;
        if (invalid == 1) bad.generation = 9007199254740992LL;
        if (invalid == 2) bad.appSession = " ";
        if (invalid == 3) bad.appSession = QString(257, 's');
        if (invalid == 4) bad.supported = false;
        require(!api.setSessionProfile("1920x1080x30",30000,false,bad).ok());
    }
    require(https.requests == before);
    https.dropReply = true;
    require(!api.setSessionProfile("1920x1080x30",30000,false,observed).ok() && https.requests == before + 1);
    https.dropReply = false;
    expectedProfile = {{"clear_display_mode",true}, {"clear_target_bitrate",true},
        {"app_session_id","private-session"}, {"session_generation",41}};
    observed.supported = false;
    receipt = api.setSessionProfile("",0,true,observed);
    require(receipt.ok() && receipt.value->desiredDisplay.isEmpty() && receipt.value->desiredBitrate == 0);
    before = https.requests;
    require(badPin.setSessionProfile("",0,true,observed).status == DeckPolarisRequestStatus::CertMismatch && https.requests == before);
    https.handler = [](const QUrl&) { return std::pair{200,QByteArray("{\"status\":true}")}; };
    require(!api.setSessionProfile("",0,true,observed).ok() && https.requests == before + 1);
    for (const int status : {302, 403, 409, 503}) {
        https.handler = [status](const QUrl&) { return std::pair{status, QByteArray("{\"status\":false}")}; };
        before = https.requests;
        require(!api.setHostProfile("1920x1080x30", 30000, false).ok() && https.requests == before + 1);
        before = https.requests;
        require(!api.setSessionProfile("",0,true,observed).ok() && https.requests == before + 1);
    }
    before = https.requests;
    require(badPin.setHostProfile("", 0, true).status == DeckPolarisRequestStatus::CertMismatch && https.requests == before);
    https.handler = [&](const QUrl& url) -> std::pair<int, QByteArray> {
        require(url.path() == "/polaris/v1/client-settings" && !url.hasQuery());
        if (https.methods.back() == "POST") {
            const auto body = QJsonDocument::fromJson(https.bodies.back()).object();
            require(body == QJsonObject{{"disconnect_resume_timeout_seconds",600}});
            auto desired=settings["desired"].toObject(); desired["disconnect_resume_timeout_seconds"]=600; settings["desired"]=desired;
        }
        return {200,QJsonDocument(settings).toJson(QJsonDocument::Compact)};
    };
    receipt=api.setHostResumeTimeout(600);
    require(receipt.ok() && receipt.value->desiredResumeTimeout==600 && receipt.value->effectiveResumeTimeout==300);
    before=https.requests;
    require(!api.setHostResumeTimeout(-1).ok() && !api.setHostResumeTimeout(86401).ok()
        && !api.setHostResumeTimeout(600,[]{return true;}).ok() && https.requests==before);
    https.dropReply=true; require(!api.setHostResumeTimeout(600).ok() && https.requests==before+1);
    https.dropReply=false; require(api.fetchHostSettings().ok() && https.requests==before+2);
    for (const int status : {302,403,409,503}) {
        https.handler=[status](const QUrl&) { return std::pair{status,QByteArray("{\"status\":false}")}; };
        before=https.requests; require(!api.setHostResumeTimeout(600).ok() && https.requests==before+1);
    }
    before=https.requests;
    require(badPin.setHostResumeTimeout(600).status==DeckPolarisRequestStatus::CertMismatch && https.requests==before);

}

int main(int argc, char** argv) {
    QTemporaryDir appData;
    qputenv("XDG_DATA_HOME", appData.path().toUtf8());
    QCoreApplication app(argc, argv);
    testSilentHttpStillUsesPinnedHttps(false);
    testSilentHttpStillUsesPinnedHttps(true);
    testDerivedCertificateCannotReceiveHttp();
    testStandardHostLibraryAndLaunch();
    testResponseDeadlineSurvivesIncomingBytes();
    testFreshLaunchModeAuthority();
    testPinnedOwnedResume();
    testSpacesSelectionAndLaunch();
    testSleepNeverReplays();
    testHostSettingsNeverReplays();
    testArtworkMutationsNeverReplay();
    testHudStatusRead();
    testLiveTuningNeverReplays();
    testFixedBitrateNeverReplays();
    testSessionEventTransport();
    testDoctorTransportAndFactory();
    return 0;
}
