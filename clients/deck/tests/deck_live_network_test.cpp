// Real loopback transport regression: silent HTTP must not suppress pinned HTTPS.
#include "backend/deck_live_read_only_state.h"

#include <QCoreApplication>
#include <QFile>
#include <QProcess>
#include <QSslCertificate>
#include <QSslKey>
#include <QSslSocket>
#include <QTcpServer>
#include <QTemporaryDir>

#include <cassert>
#include <chrono>
#include <string>

namespace {

QByteArray readFile(const QString& path) {
    QFile file(path);
    assert(file.open(QIODevice::ReadOnly));
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
    assert(process.waitForFinished(15000));
    assert(process.exitStatus() == QProcess::NormalExit && process.exitCode() == 0);
    return {readFile(certPath), readFile(keyPath)};
}

class TlsServer final : public QTcpServer {
public:
    explicit TlsServer(const TestIdentity& identity) : identity_(identity) {}
    int requests = 0;

protected:
    void incomingConnection(qintptr descriptor) override {
        auto* socket = new QSslSocket(this);
        socket->setLocalCertificate(QSslCertificate(identity_.cert));
        socket->setPrivateKey(QSslKey(identity_.key, QSsl::Rsa));
        socket->setPeerVerifyMode(QSslSocket::VerifyNone);
        assert(socket->setSocketDescriptor(descriptor));
        connect(socket, &QSslSocket::disconnected, socket, &QObject::deleteLater);
        connect(socket, &QSslSocket::readyRead, socket, [this, socket, request = QByteArray{}]() mutable {
            request += socket->readAll();
            if (!request.contains("\r\n\r\n")) return;
            ++requests;
            const QByteArray body = request.startsWith("GET /polaris/v1/capabilities ")
                ? QByteArray(R"({"server":"polaris","version":"test","features":{"game_library":true}})")
                : QByteArray(R"({"games":[{"id":"test-game","name":"Test game"}],"total":1})");
            socket->write("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nConnection: close\r\nContent-Length: "
                + QByteArray::number(body.size()) + "\r\n\r\n" + body);
            socket->disconnectFromHost();
        });
        socket->startServerEncryption();
    }

private:
    TestIdentity identity_;
};

void testSilentHttpStillUsesPinnedHttps() {
    using namespace nova::deck;
    QTemporaryDir directory;
    assert(directory.isValid());
    const auto serverIdentity = createIdentity(directory.path(), "server");
    const auto clientIdentity = createIdentity(directory.path(), "client");
    TlsServer https(serverIdentity);
    QTcpServer http;
    // Reserve both ports simultaneously; the production fallback is HTTP - 5.
    for (int attempt = 0; attempt < 100; ++attempt) {
        assert(https.listen(QHostAddress::LocalHost, 0));
        if (https.serverPort() < 65530 && http.listen(QHostAddress::LocalHost, https.serverPort() + 5)) break;
        https.close();
    }
    assert(http.isListening() && https.isListening());
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
    host.serverCertificatePem = serverIdentity.cert.toStdString();
    const auto timeout = std::chrono::milliseconds(750);
    const auto httpProbe = polaris::probeServerInfoHttpsPort(host.localAddress, host.localPort, timeout);
    assert(httpProbe.timedOut && !httpProbe.httpsPort);
    const auto fetcher = backend::polarisNetworkFetcher(identity, timeout);
    const auto fetched = fetcher(host, true);
    assert(fetched.status == polaris::DeckPolarisRequestStatus::Ok);
    assert(fetched.httpsPort == https.serverPort());
    assert(fetched.games.size() == 1 && fetched.games[0].id == "test-game");
    assert(httpRequests == 2 && https.requests == 2);

    // The fallback must retain exact certificate pinning even after HTTP timeout.
    host.serverCertificatePem = clientIdentity.cert.toStdString();
    const auto rejected = fetcher(host, true);
    assert(rejected.status == polaris::DeckPolarisRequestStatus::CertMismatch);
    assert(httpRequests == 3 && https.requests == 2);
}

} // namespace

int main(int argc, char** argv) {
    QCoreApplication app(argc, argv);
    testSilentHttpStillUsesPinnedHttps();
    return 0;
}
