#include "runtime/deck_doctor_receipts.h"
#include <QCryptographicHash>
#include <QDir>
#include <QFile>
#include <QFileInfo>
#include <QJsonArray>
#include <QJsonDocument>
#include <QRegularExpression>
#include <QSaveFile>
#include <QStandardPaths>
#ifdef Q_OS_UNIX
#include <unistd.h>
#endif

namespace nova::deck::runtime {
namespace {
constexpr auto filePermissions = QFileDevice::ReadOwner | QFileDevice::WriteOwner;
constexpr auto directoryPermissions = filePermissions | QFileDevice::ExeOwner;
bool owned(const QFileInfo& info) {
#ifdef Q_OS_UNIX
    return info.ownerId() == ::geteuid();
#else
    return true;
#endif
}
bool privateFile(const QFileInfo& info) {
    return !info.isSymLink() && info.isFile() && owned(info) && info.size() <= 16384 &&
        !(info.permissions() & (QFileDevice::ReadGroup | QFileDevice::WriteGroup | QFileDevice::ExeGroup |
            QFileDevice::ReadOther | QFileDevice::WriteOther | QFileDevice::ExeOther));
}
}
QString DeckDoctorReceiptStore::directory() {
    const auto root = QStandardPaths::writableLocation(QStandardPaths::AppLocalDataLocation);
    return root.isEmpty() ? QString{} : root + "/doctor-receipts";
}
QString DeckDoctorReceiptStore::key(const QByteArray& host, const QByteArray& client, const QString& game) {
    if (host.isEmpty() || client.isEmpty() || game.isEmpty()) return {};
    const QJsonArray parts{"nova-deck-doctor-journal-v1", QString::fromLatin1(host.toHex()), QString::fromLatin1(client.toHex()), game};
    return QString::fromLatin1(QCryptographicHash::hash(QJsonDocument(parts).toJson(QJsonDocument::Compact), QCryptographicHash::Sha256).toHex());
}
DeckDoctorReceiptStore::DeckDoctorReceiptStore(QString directory, QString key) : key_(std::move(key)) {
    static const QRegularExpression digest("^[a-f0-9]{64}$");
    if (directory.isEmpty() || !QDir::isAbsolutePath(directory) || !digest.match(key_).hasMatch()) return;
    QFileInfo info(directory);
    if (info.isSymLink() || (info.exists() && (!info.isDir() || !owned(info)))) return;
    if (!QDir().mkpath(directory) || !QFile::setPermissions(directory, directoryPermissions)) return;
    path_ = QDir(directory).filePath(key_ + ".json");
    lock_ = std::make_unique<QLockFile>(path_ + ".lock"); lock_->setStaleLockTime(0);
    ready_ = lock_->tryLock(0);
}
QJsonObject DeckDoctorReceiptStore::load() {
    if (!ready_) return {};
    const QFileInfo info(path_);
    if (!info.exists() && !info.isSymLink()) return {};
    if (!privateFile(info)) { ready_ = false; return {}; }
    QFile file(path_);
    if (!file.open(QIODevice::ReadOnly)) { ready_ = false; return {}; }
    const auto bytes = file.read(16385);
    QJsonParseError error; const auto document = QJsonDocument::fromJson(bytes, &error);
    if (bytes.size() > 16384 || error.error != QJsonParseError::NoError || !document.isObject() ||
        document.object().value("key") != key_ || !document.object().value("checkpoint").isObject()) {
        ready_ = false; return {};
    }
    return document.object().value("checkpoint").toObject();
}
bool DeckDoctorReceiptStore::save(const QJsonObject& checkpoint) {
    if (!ready_) return false;
    const QFileInfo info(path_);
    if ((info.exists() || info.isSymLink()) && !privateFile(info)) return false;
    if (checkpoint.isEmpty()) return !info.exists() || QFile::remove(path_);
    if (!info.exists() && QDir(info.absolutePath()).entryList({"*.json"}, QDir::Files).size() >= 64) return false;
    const auto bytes = QJsonDocument(QJsonObject{{"key", key_}, {"checkpoint", checkpoint}}).toJson(QJsonDocument::Compact);
    if (bytes.size() > 16384) return false;
    QSaveFile file(path_); file.setDirectWriteFallback(false);
    return file.open(QIODevice::WriteOnly) && file.setPermissions(filePermissions) && file.write(bytes) == bytes.size() && file.commit();
}
}
