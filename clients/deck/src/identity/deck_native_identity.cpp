#include "identity/deck_native_identity.h"

#include <QDir>
#include <QFile>
#include <QFileInfo>
#include <QJsonArray>
#include <QJsonDocument>
#include <QJsonObject>
#include <QSet>

#include <cerrno>
#include <fcntl.h>
#include <sys/file.h>
#include <sys/stat.h>
#include <unistd.h>
#include <utility>

namespace nova::deck::identity {
namespace {
constexpr auto fileName = "identity.json";
constexpr int maxFileSize = 1024 * 1024;
struct File {
    int fd = -1;
    explicit File(int fd = -1) : fd(fd) {}
    ~File() { if (fd >= 0) close(fd); }
    File(const File&) = delete;
    File& operator=(const File&) = delete;
};
bool ownerOnly(int fd, bool directory) {
    struct stat info{};
    return fstat(fd, &info) == 0 && info.st_uid == geteuid() && (info.st_mode & 0077) == 0 &&
        (directory ? S_ISDIR(info.st_mode) : S_ISREG(info.st_mode)) &&
        (directory || (info.st_nlink == 1 && info.st_size <= maxFileSize));
}
NativeStoreResult readIdentity(int directoryFd) {
    File file(openat(directoryFd, fileName, O_RDONLY | O_CLOEXEC | O_NOFOLLOW | O_NONBLOCK));
    if (file.fd < 0) return {errno == ENOENT ? NativeStoreStatus::Missing : NativeStoreStatus::Unsafe};
    if (!ownerOnly(file.fd, false)) return {NativeStoreStatus::Unsafe};
    QByteArray bytes;
    char buffer[8192];
    for (;;) {
        const auto count = read(file.fd, buffer, sizeof(buffer));
        if (count < 0 && errno == EINTR) continue;
        if (count < 0) return {NativeStoreStatus::IoError};
        if (count == 0) break;
        bytes.append(buffer, count);
        if (bytes.size() > maxFileSize) return {NativeStoreStatus::Invalid};
    }
    QJsonParseError error;
    const auto document = QJsonDocument::fromJson(bytes, &error);
    if (error.error != QJsonParseError::NoError || !document.isObject()) return {NativeStoreStatus::Invalid};
    const auto object = document.object();
    if (object.value("version").toInt(-1) != 1 || !object.value("hosts").isArray()) return {NativeStoreStatus::Invalid};
    const crypto::Credentials credentials{object.value("certificate").toString().toUtf8(), object.value("private_key").toString().toUtf8()};
    if (!crypto::valid(credentials)) return {NativeStoreStatus::Invalid};
    DeckMoonlightIdentity identity;
    identity.loaded = true;
    identity.sourceLabel = "nova-native";
    identity.clientCertificatePem = credentials.certificate.toStdString();
    identity.clientPrivateKeyPemForBackendOnly = credentials.privateKey.toStdString();
    QSet<QString> ids, endpoints;
    if (object.value("hosts").toArray().size() > 64) return {NativeStoreStatus::Invalid};
    for (const auto& value : object.value("hosts").toArray()) {
        if (!value.isObject()) return {NativeStoreStatus::Invalid};
        const auto record = value.toObject();
        const QString id = record.value("uuid").toString();
        const QString address = record.value("address").toString();
        const int port = record.value("http_port").toInt();
        const int httpsPort = record.value("https_port").toInt(0);
        const QByteArray certificate = record.value("server_certificate").toString().toUtf8();
        const QString endpoint = address.toLower() + ":" + QString::number(port);
        if (id.isEmpty() || id.size() > 128 || address.isEmpty() || address.size() > 253 || port < 1 || port > 65535 ||
            httpsPort < 0 || httpsPort > 65535 || ids.contains(id) || endpoints.contains(endpoint) || crypto::certificateDer(certificate).isEmpty()) return {NativeStoreStatus::Invalid};
        ids.insert(id);
        endpoints.insert(endpoint);
        DeckMoonlightHostRecord host;
        host.uuid = id.toStdString();
        host.hostname = record.value("name").toString().left(256).toStdString();
        host.manualAddress = address.toStdString();
        host.manualPort = port;
        host.nativeHttpsPort = httpsPort;
        host.serverCertificatePem = certificate.toStdString();
        identity.hosts.push_back(std::move(host));
    }
    return {NativeStoreStatus::Ok, std::move(identity)};
}

bool writeIdentity(int directoryFd, const DeckMoonlightIdentity& identity) {
    QJsonArray hosts;
    for (const auto& host : identity.hosts) {
        hosts.append(QJsonObject{{"uuid", QString::fromStdString(host.uuid)},
            {"name", QString::fromStdString(host.hostname)}, {"address", QString::fromStdString(host.preferredAddress())},
            {"http_port", host.preferredHttpPort()}, {"https_port", host.nativeHttpsPort},
            {"server_certificate", QString::fromStdString(host.serverCertificatePem)}});
    }
    const QByteArray bytes = QJsonDocument(QJsonObject{{"version", 1},
        {"certificate", QString::fromStdString(identity.clientCertificatePem)},
        {"private_key", QString::fromStdString(identity.clientPrivateKeyPemForBackendOnly)}, {"hosts", hosts}}).toJson();
    if (bytes.size() > maxFileSize) return false;
    const auto nonce = crypto::random(12).toHex();
    if (nonce.isEmpty()) return false;
    const auto temporary = QByteArray(".identity-") + nonce;
    File file(openat(directoryFd, temporary.constData(), O_WRONLY | O_CREAT | O_EXCL | O_NOFOLLOW | O_CLOEXEC, 0600));
    if (file.fd < 0) return false;
    bool ok = true;
    qsizetype offset = 0;
    while (offset < bytes.size()) {
        const auto written = write(file.fd, bytes.constData() + offset, bytes.size() - offset);
        if (written < 0 && errno == EINTR) continue;
        if (written <= 0) { ok = false; break; }
        offset += written;
    }
    if (ok) ok = fsync(file.fd) == 0;
    if (ok) ok = renameat(directoryFd, temporary.constData(), directoryFd, fileName) == 0;
    if (ok) ok = fsync(directoryFd) == 0;
    unlinkat(directoryFd, temporary.constData(), 0);
    return ok;
}

template<class Operation>
NativeStoreResult access(const QString& path, bool create, Operation operation, int lockMode = LOCK_EX) {
    if (path.isEmpty() || path.contains(QChar::Null) || !QDir::isAbsolutePath(path)) return {NativeStoreStatus::Unsafe};
    const QByteArray encoded = QFile::encodeName(path);
    if (create) {
        const QFileInfo info(path);
        if (!QDir().mkpath(info.absolutePath())) return {NativeStoreStatus::IoError};
        if (mkdir(encoded.constData(), 0700) != 0 && errno != EEXIST) return {NativeStoreStatus::IoError};
    }
    File directory(open(encoded.constData(), O_RDONLY | O_DIRECTORY | O_NOFOLLOW | O_CLOEXEC));
    if (directory.fd < 0) return {errno == ENOENT ? NativeStoreStatus::Missing : NativeStoreStatus::Unsafe};
    if (!ownerOnly(directory.fd, true)) return {NativeStoreStatus::Unsafe};
    // Lock the directory inode, not a replaceable lock file. Read-only identity
    // checks may coexist; read-modify-write stays exclusive across processes.
    // Neither path waits on the UI thread or reads through an active writer.
    if (flock(directory.fd, lockMode | LOCK_NB) != 0) return {NativeStoreStatus::Busy};
    return operation(directory.fd);
}
}

QString nativeIdentityDirectory() {
    if (const auto override = qEnvironmentVariable("NOVA_DECK_IDENTITY_DIR"); !override.isEmpty()) return override;
    auto config = qEnvironmentVariable("XDG_CONFIG_HOME");
    if (config.isEmpty() || !QDir::isAbsolutePath(config)) config = QDir::homePath() + "/.config";
    return config + "/nova-deck";
}

NativeStoreResult loadNativeIdentity(const QString& directory) {
    return access(directory, false, [](int fd) { return readIdentity(fd); }, LOCK_SH);
}

NativeStoreResult initializeNativeIdentity(const QString& directory) {
    return access(directory, true, [](int fd) {
        auto current = readIdentity(fd);
        if (current.status != NativeStoreStatus::Missing) return current;
        const auto generated = crypto::generate();
        if (!generated) return NativeStoreResult{NativeStoreStatus::IoError};
        DeckMoonlightIdentity identity;
        identity.loaded = true;
        identity.sourceLabel = "nova-native";
        identity.clientCertificatePem = generated->certificate.toStdString();
        identity.clientPrivateKeyPemForBackendOnly = generated->privateKey.toStdString();
        if (!writeIdentity(fd, identity)) return NativeStoreResult{NativeStoreStatus::IoError};
        return NativeStoreResult{NativeStoreStatus::Ok, std::move(identity)};
    });
}

NativeStoreResult saveNativeHost(const QString& directory, const crypto::Credentials& expected,
                                const DeckMoonlightHostRecord& host) {
    return access(directory, false, [&](int fd) {
        auto current = readIdentity(fd);
        if (!current.ok()) return current;
        if (!crypto::equal(crypto::certificateDer(expected.certificate),
                          crypto::certificateDer(QByteArray::fromStdString(current.identity->clientCertificatePem))))
            return NativeStoreResult{NativeStoreStatus::Conflict};
        if (host.uuid.empty() || host.uuid.size() > 128 || host.preferredAddress().empty() ||
            host.preferredAddress().size() > 253 || host.manualPort < 1 || host.manualPort > 65535 ||
            host.nativeHttpsPort < 0 || host.nativeHttpsPort > 65535 ||
            crypto::certificateDer(QByteArray::fromStdString(host.serverCertificatePem)).isEmpty() ||
            current.identity->hosts.size() >= 64) return NativeStoreResult{NativeStoreStatus::Invalid};
        for (const auto& saved : current.identity->hosts) {
            if (saved.uuid == host.uuid || (QString::fromStdString(saved.preferredAddress()).compare(
                    QString::fromStdString(host.preferredAddress()), Qt::CaseInsensitive) == 0 &&
                    saved.preferredHttpPort() == host.preferredHttpPort())) return NativeStoreResult{NativeStoreStatus::Conflict};
        }
        current.identity->hosts.push_back(host);
        if (!writeIdentity(fd, *current.identity)) return NativeStoreResult{NativeStoreStatus::IoError};
        return current;
    });
}

NativePairingLease::~NativePairingLease() { close(fd_); }

NativeStoreResult forgetNativeHost(const QString& directory, const crypto::Credentials& expected,
                                  const DeckMoonlightHostRecord& host) {
    return access(directory, false, [&](int fd) {
        auto current = readIdentity(fd);
        if (!current.ok()) return current;
        const auto* saved = current.identity->hostById(host.uuid);
        if (!saved || current.identity->clientCertificatePem != expected.certificate.toStdString() ||
            saved->serverCertificatePem != host.serverCertificatePem ||
            saved->preferredAddress() != host.preferredAddress() || saved->preferredHttpPort() != host.preferredHttpPort() ||
            saved->nativeHttpsPort != host.nativeHttpsPort)
            return NativeStoreResult{NativeStoreStatus::Conflict};
        std::erase_if(current.identity->hosts, [&](const auto& candidate) { return candidate.uuid == host.uuid; });
        if (!writeIdentity(fd, *current.identity)) return NativeStoreResult{NativeStoreStatus::IoError};
        return current;
    });
}

std::unique_ptr<NativePairingLease> lockNativePairing(const QString& directory) {
    File folder(open(QFile::encodeName(directory).constData(), O_RDONLY | O_DIRECTORY | O_NOFOLLOW | O_CLOEXEC));
    if (folder.fd < 0 || !ownerOnly(folder.fd, true)) return {};
    File lock(openat(folder.fd, "pairing.lock", O_RDWR | O_CREAT | O_NOFOLLOW | O_CLOEXEC | O_NONBLOCK, 0600));
    if (lock.fd < 0 || !ownerOnly(lock.fd, false) || flock(lock.fd, LOCK_EX | LOCK_NB) != 0) return {};
    return std::make_unique<NativePairingLease>(std::exchange(lock.fd, -1));
}
} // namespace nova::deck::identity
