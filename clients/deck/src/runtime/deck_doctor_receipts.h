#pragma once
#include <QJsonObject>
#include <QLockFile>
#include <QString>
#include <memory>

namespace nova::deck::runtime {
// A private, bounded journal for one paired host/client/game. Its contents are
// never diagnostics. The lock spans the observer lifetime, not just a write.
class DeckDoctorReceiptStore {
public:
    DeckDoctorReceiptStore(QString directory, QString key);
    bool ready() const { return ready_; }
    QJsonObject load();
    bool save(const QJsonObject& checkpoint);
    static QString directory();
    static QString key(const QByteArray& hostCertificate, const QByteArray& clientCertificate, const QString& gameUuid);
private:
    QString path_, key_;
    std::unique_ptr<QLockFile> lock_;
    bool ready_ = false;
};
}
