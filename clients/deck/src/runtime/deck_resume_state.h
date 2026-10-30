#pragma once
#include "polaris/deck_polaris_client.h"
#include "polaris_game_fixture.h"
#include <QObject>
#include <QElapsedTimer>
#include <QThread>
#include <QTimer>
#include <QVariantMap>
#include <atomic>

namespace nova::deck::runtime {
// Transport and tokens stay on the backend. The factory runs on the observer
// worker, and every operation is bounded by the paired client's deadline.
struct DeckResumeTarget {
    std::function<bool()> valid;
    std::function<polaris::DeckPolarisResult<polaris::DeckPolarisSessionStatus>(const std::function<bool()>&)> read;
    std::function<polaris::DeckPolarisResult<bool>(const std::string&, const std::function<bool()>&)> stop;
    std::vector<PolarisGameFixture> games;
};
using DeckResumeResolver = std::function<std::optional<DeckResumeTarget>()>;
using DeckResumeStarter = std::function<bool(const QString&, const QString&, const QVariantMap&, const std::string&)>;

class DeckResumeState final : public QObject {
    Q_OBJECT
    Q_PROPERTY(QVariantMap state READ state NOTIFY stateChanged)
public:
    explicit DeckResumeState(DeckResumeStarter starter = {}, QObject* parent = nullptr,
        int pollMs = 2000, int freshnessMs = 6500);
    ~DeckResumeState() override;
    QVariantMap state() const { return state_; }
    bool busy() const { return pendingEnd_.has_value() || (worker_ && jobStopping_); }
    void setActionsBlocked(bool blocked);
    void setContext(QString host, QString scope, DeckResumeResolver resolver);
    Q_INVOKABLE void setActive(bool active);
    Q_INVOKABLE bool refresh();
    Q_INVOKABLE bool endSession();
    Q_INVOKABLE bool startConfigured(const QString& host, const QString& game, const QVariantMap& configuration);
signals:
    void stateChanged();
private:
    struct Job;
    bool request(bool stop);
    void tick();
    void clear();
    void publish();
    QString host_, scope_, game_;
    DeckResumeResolver resolver_;
    DeckResumeStarter starter_;
    std::optional<polaris::DeckPolarisSessionStatus> observed_, pendingEnd_;
    QVariantMap state_;
    QElapsedTimer clock_;
    qint64 observedAt_ = -1, nextRead_ = 0;
    int pollMs_, freshnessMs_;
    bool active_ = false, actionsBlocked_ = false, jobStopping_ = false;
    unsigned long long generation_ = 0;
    QTimer timer_;
    QThread* worker_ = nullptr;
    std::shared_ptr<Job> job_;
};
}
