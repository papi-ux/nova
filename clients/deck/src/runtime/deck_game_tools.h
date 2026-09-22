#pragma once
#include "polaris/deck_polaris_client.h"
#include <QObject>
#include <QThread>
#include <QTimer>
#include <atomic>

namespace nova::deck::runtime {
struct DeckGameToolsTarget {
    std::function<polaris::DeckPolarisResult<QVariantMap>(const QString&, const QString&, const QVariantMap&, const std::function<bool()>&)> request;
    std::function<polaris::DeckPolarisResult<std::vector<polaris::DeckPolarisGame>>(const std::function<bool()>&)> games;
    std::function<bool()> identityValid;
};
using DeckGameToolsResolver = std::function<std::optional<DeckGameToolsTarget>()>;
class DeckGameTools final : public QObject {
    Q_OBJECT
    Q_PROPERTY(QVariantMap state READ state NOTIFY stateChanged)
public:
    explicit DeckGameTools(QObject* parent = nullptr);
    ~DeckGameTools() override;
    QVariantMap state() const;
    bool busy() const { return worker_ != nullptr; }
    bool writing() const;
    void setTarget(QString host, DeckGameToolsResolver resolver);
    void setSessionActive(bool active);
    void setPreviewPublisher(std::function<QVariantList(const QString&, const QString&, QVariantList)> publish,
        std::function<void()> retire = {}) { previews_ = std::move(publish); retirePreviews_ = std::move(retire); }
    Q_INVOKABLE bool prepare(const QString& host, const QString& game, const QVariantMap& configuration);
    Q_INVOKABLE bool review(const QVariantMap& configuration);
    Q_INVOKABLE bool search(const QString& query);
    Q_INVOKABLE bool selectCandidate(int index);
    Q_INVOKABLE bool selectKind(const QString& kind);
    Q_INVOKABLE bool selectArtwork(int index);
    Q_INVOKABLE bool applyArtwork();
    Q_INVOKABLE void discard();
    Q_INVOKABLE bool resetArtwork();
    Q_INVOKABLE bool refreshArtwork();
    Q_INVOKABLE bool checkArtwork();
    Q_INVOKABLE bool setSteamMode(const QString& mode);
    Q_INVOKABLE void close();
signals:
    void stateChanged();
    void libraryChanged();
private:
    struct Job;
    bool start(const QString& action, QVariantMap values = {});
    void poll();
    void clearEditing();
    DeckGameToolsResolver resolver_;
    std::function<QVariantList(const QString&, const QString&, QVariantList)> previews_;
    std::function<void()> retirePreviews_;
    QString host_, game_, kind_ = "poster", copy_;
    QVariantMap configuration_, settings_, plan_, steam_, candidate_, selections_, artworkResolution_;
    QVariantList candidates_, choices_;
    QHash<QString, QVariantList> choiceCache_;
    bool active_ = false, blocked_ = false, available_ = false, uncertain_ = false;
    quint64 generation_ = 0;
    QThread* worker_ = nullptr;
    QTimer timer_;
    std::shared_ptr<Job> job_;
    std::optional<QVariantMap> pendingReview_;
};
}
