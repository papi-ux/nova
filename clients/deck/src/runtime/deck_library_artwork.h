#pragma once

#include "runtime/deck_native_target.h"
#include <QMutex>
#include <QHash>
#include <QQuickImageProvider>
#include <QVariantList>

namespace nova::deck::runtime {

// QML receives opaque image keys only. Network work uses the same saved-pin
// checks as the library, on Qt's image worker, with a fixed read-only route.
class DeckLibraryArtwork final : public QQuickImageProvider {
public:
    DeckLibraryArtwork();
    QVariantList publish(const backend::DeckLiveHostLibrarySnapshot& snapshot,
        DeckNativeTargetResolver resolver, QVariantList games);
    QVariantList publishPreviews(const QString& host, const QString& game, QVariantList items);
    void clearPreviews();
    void invalidateGame(const QString& host, const QString& game);
    QImage requestImage(const QString& id, QSize* size, const QSize& requestedSize) override;
    // The tint read from a published hero or poster when it was decoded, by the
    // image URL QML holds; empty when none was read or it has none. Never fetches.
    QString tintFor(const QString& source);
private:
    struct Entry { QString host, game; std::string path; QByteArray identity; QSize bounds; QString kind; };
    QMutex mutex_;
    quint64 nextKey_ = 0;
    QHash<QString, Entry> entries_;
    QHash<QString, Entry> previews_;
    // Only keys still published keep a tint, so this never outgrows the library.
    QHash<QString, QString> tints_;
    DeckNativeTargetResolver resolver_;
};

} // namespace nova::deck::runtime
