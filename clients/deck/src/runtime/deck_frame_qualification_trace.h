#pragma once
#include <QFile>
#include <QVariantMap>

namespace nova::deck::runtime {
// Owner-private, append-only receipt. Existing files are never overwritten.
class DeckFrameQualificationTrace {
public:
    explicit DeckFrameQualificationTrace(const QString& path, qint64 limit = 16 * 1024 * 1024);
    bool append(const QVariantMap& hud, qint64 sampleMs);
private:
    QFile file_;
    qint64 limit_;
};
void appendDeckFrameQualificationTrace(const QVariantMap& hud, qint64 sampleMs);
}
