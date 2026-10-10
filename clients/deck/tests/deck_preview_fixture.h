#pragma once
#include <QObject>
#include "runtime/deck_support_report.h"
#include <QVariantMap>
#include <QVariantList>
#include <QStringList>

class PreviewSession : public QObject {
    Q_OBJECT
    Q_PROPERTY(QVariantMap state READ state NOTIFY stateChanged)
    Q_PROPERTY(bool controlsVisible READ controlsVisible NOTIFY controlsChanged)
    Q_PROPERTY(QString controllerHint READ controllerHint NOTIFY controlsChanged)
    Q_PROPERTY(QVariantMap hud READ hud NOTIFY hudChanged)
public:
    QVariantMap hud() const { auto value = tuning; value.insert("fps", "59.8"); value.insert("rtt", "17ms"); value.insert("target", "/ 60 target"); return value; }
    QVariantMap tuning;
    int diagnosticsRefreshes = 0, reportExports = 0;
    QString reportDirectory;
    Q_INVOKABLE QVariantMap exportSupportReport() { ++reportExports; return nova::deck::runtime::saveDeckSupportReport(hud(), reportDirectory); }
    int doctorApplies = 0, doctorUndos = 0, doctorChecks = 0;
    Q_INVOKABLE bool applyDoctorFix() { return doctorOperation("doctorCanApply", doctorApplies); }
    Q_INVOKABLE bool undoDoctorFix() { return doctorOperation("doctorCanUndo", doctorUndos); }
    Q_INVOKABLE bool checkDoctorResult() { return doctorOperation("doctorCanCheck", doctorChecks); }
    bool doctorOperation(const char* permission, int& counter) {
        if (!tuning.value(permission).toBool() || tuning.value("doctorActionBusy").toBool()) return false;
        ++counter; tuning["doctorActionBusy"] = true;
        tuning["doctorCanApply"] = tuning["doctorCanUndo"] = tuning["doctorCanCheck"] = false;
        emit hudChanged(); return true;
    }
    Q_INVOKABLE bool refreshDiagnostics() {
        if (!tuning.value("canRefreshDiagnostics").toBool() || tuning.value("diagnosticsRefreshing").toBool()) return false;
        ++diagnosticsRefreshes;
        tuning["canRefreshDiagnostics"] = false; tuning["diagnosticsRefreshing"] = true;
        tuning["hostFresh"] = false; tuning["doctor"] = QVariantMap{};
        emit hudChanged(); return true;
    }
    int tuningWrites = 0;
    bool tuningRequested = false;
    Q_INVOKABLE bool setLiveTuningEnabled(bool enabled) {
        if (!tuning.value("canTune").toBool() || tuning.value("tuningBusy").toBool()) return false;
        ++tuningWrites; tuningRequested = enabled;
        tuning["canTune"] = false; tuning["tuningBusy"] = true; tuning["tuningCopy"] = "Saving Live Tuning…";
        emit hudChanged(); return true;
    }
    int bitrateWrites = 0, requestedBitrate = 0;
    int syncWrites = 0;
    QString syncHost, syncDisplay;
    int syncBitrate = 0;
    bool syncClear = false;
    QVariantMap syncReview;
    Q_INVOKABLE bool setSyncProfile(const QString& host, const QString& display, int kbps, bool clear, const QVariantMap& review) {
        if (!tuning.value("canSyncProfile").toBool() || tuning.value("syncBusy").toBool() ||
            (!clear && !tuning.value("canSetBitrate").toBool())) return false;
        ++syncWrites; syncHost = host; syncDisplay = display; syncBitrate = kbps; syncClear = clear; syncReview = review;
        tuning["canSyncProfile"] = tuning["canSetBitrate"] = tuning["canTune"] = false;
        tuning["syncBusy"] = tuning["tuningBusy"] = true; tuning["syncPhase"] = "checking";
        tuning["syncCopy"] = "Rechecking the session and saved profile…";
        tuning["syncVersion"] = tuning.value("syncVersion").toInt() + 1;
        emit hudChanged(); return true;
    }
    Q_INVOKABLE bool setFixedBitrate(int kbps) {
        if (!tuning.value("canSetBitrate").toBool() || tuning.value("tuningBusy").toBool()) return false;
        ++bitrateWrites; requestedBitrate = kbps;
        tuning["canSetBitrate"] = false; tuning["tuningBusy"] = true; tuning["bitrateBusy"] = true;
        tuning["bitrateCopy"] = "Requested 21 Mbps. Waiting for the encoder…";
        emit hudChanged(); return true;
    }
    // Working baseline fixture: counts every call and models the run generation.
    int baselineGeneration = 1, baselineStatusReads = 0, baselineSaves = 0, baselineClears = 0, baselineCompares = 0;
    bool baselineAvailable = true, baselineFresh = true, baselineStored = false;
    QString baselineSavedGeneration, baselineClearedGeneration, baselineReason;
    Q_INVOKABLE QVariantMap workingBaselineStatus() {
        ++baselineStatusReads;
        return {{"generation", QString::number(baselineGeneration)}, {"available", baselineAvailable},
            {"reason", !baselineAvailable ? QString("no_scope") : !baselineReason.isEmpty() ? baselineReason : baselineStored ? QString("ready") : QString("no_baseline")},
            {"hasBaseline", baselineAvailable && baselineStored}, {"canSave", baselineAvailable && baselineFresh},
            {"markedAtUtc", baselineStored ? QVariant("2026-10-10T12:00:00.000Z") : QVariant()}};
    }
    Q_INVOKABLE QVariantMap saveWorkingBaseline(const QString& generation) {
        ++baselineSaves; baselineSavedGeneration = generation;
        if (generation != QString::number(baselineGeneration)) return {{"result", "stream_changed"}};
        if (!baselineFresh) return {{"result", "stale_readings"}};
        baselineStored = true; return {{"result", "saved"}};
    }
    Q_INVOKABLE QVariantMap clearWorkingBaseline(const QString& generation) {
        ++baselineClears; baselineClearedGeneration = generation;
        if (generation != QString::number(baselineGeneration)) return {{"result", "stream_changed"}};
        const bool had = baselineStored; baselineStored = false;
        return {{"result", had ? "cleared" : "no_baseline"}};
    }
    Q_INVOKABLE QVariantMap compareWorkingBaseline() {
        ++baselineCompares;
        if (!baselineStored) return {{"status", "no_baseline"}};
        const QVariantList context{QVariantMap{{"key", "observed.codec"}, {"label", "Video codec"}, {"baseline", "HEVC"}, {"now", "H.264"}, {"state", "different"}}};
        const QVariantList readings{QVariantMap{{"key", "composed_fps"}, {"label", "Frames drawn"}, {"scope", "app draws, not panel presentation"},
            {"baseline", "59.8 fps"}, {"now", baselineFresh ? "58.1 fps" : "unknown"}, {"state", baselineFresh ? "reading" : "unknown"}}};
        return {{"status", "ready"}, {"markedAtUtc", "2026-10-10T12:00:00.000Z"}, {"sampleReceivedUtc", "2026-10-10T11:59:59.600Z"},
            {"currentFresh", baselineFresh}, {"currentSampleReceivedUtc", baselineFresh ? "2026-10-10T12:05:00.000Z" : ""},
            {"contextState", "different"}, {"differentFields", QStringList{"Video codec"}}, {"unknownFields", QStringList{"Nova version"}},
            {"context", context}, {"readings", readings},
            {"notCompared", QStringList{"Game scene", "Host build and driver"}}};
    }
    QVariantMap state() const { return state_; }
    bool controlsVisible() const { return controls_; }
    QString controllerHint() const { return inputNotice; }
    QString inputNotice;
    Q_INVOKABLE bool startConfigured(const QString& host, const QString& game, const QVariantMap& configuration) {
        ++starts; ++baselineGeneration;
        selectedHost = host;
        selectedGame = game;
        selectedConfiguration = configuration;
        showControls();
        transition("starting", true, "Connecting to your game…");
        return true;
    }
    Q_INVOKABLE bool disconnectFromHost() {
        if (state_.value("phase") != "active" || !state_.value("canDisconnect").toBool()) return false;
        ++disconnects; transition("stopping", true, "Disconnecting…"); return true;
    }
    Q_INVOKABLE bool resumeDisconnected(const QString& host, const QString& game) {
        if (rejectResume || !state_.value("canResume").toBool() || host != selectedHost || game != selectedGame) return false;
        ++resumes; ++baselineGeneration;
        showControls();
        transition("starting", true, "Resuming your game…");
        return true;
    }
    Q_INVOKABLE bool reconnect(const QString& host, const QString& game) {
        if (rejectResume || !state_.value("canReconnect").toBool() || host != selectedHost || game != selectedGame) return false;
        ++reconnects; ++baselineGeneration;
        showControls();
        transition("starting", true, "Checking your existing game…");
        return true;
    }
    Q_INVOKABLE void stop() { ++stops; transition("stopping", true, "Ending the game…"); }
    Q_INVOKABLE void showControls() { controls_ = true; emit controlsChanged(); }
    Q_INVOKABLE void resumeInput() { controls_ = false; emit controlsChanged(); }
    void transition(const QString& phase, bool busy, const QString& copy) {
        state_ = {{"phase", phase}, {"busy", busy}, {"copy", copy}, {"canDisconnect", phase == "active" && allowDisconnect},
            {"canResume", phase == "disconnected" && !busy && allowResume},
            {"canReconnect", phase == "interrupted" && !busy && allowResume},
            {"automaticReconnect", automaticAttempt > 0 && busy && phase != "active"},
            {"reconnectAttempt", automaticAttempt}, {"sleeping", sleeping}, {"audioCopy", audioCopy}};
        emit stateChanged();
    }
    int starts = 0, stops = 0, disconnects = 0, resumes = 0, reconnects = 0;
    int automaticAttempt = 0;
    bool allowDisconnect = true, allowResume = true, rejectResume = false;
    bool sleeping = false;
    QString audioCopy;
    QString selectedHost, selectedGame;
    QVariantMap selectedConfiguration;
signals:
    void hudChanged();
    void stateChanged();
    void controlsChanged();
private:
    bool controls_ = true;
    QVariantMap state_{{"phase", "idle"}, {"busy", false}, {"copy", "Ready"}};
};

class PreviewPlayers : public QObject {
    Q_OBJECT
    Q_PROPERTY(QVariantList players READ players NOTIFY playersChanged)
public:
    explicit PreviewPlayers(PreviewSession& session) : session_(session) {}
    QVariantList players() const {
        if (!connected) return {};
        return {QVariantMap{{"name", "Steam Deck"}, {"player", reassigned ? -1 : 0}, {"waiting", reassigned}},
            QVariantMap{{"name", "Wireless controller"}, {"player", reassigned ? -1 : 1}, {"waiting", reassigned}},
            QVariantMap{{"name", "Waiting controller"}, {"player", -1}, {"waiting", true}}};
    }
    Q_INVOKABLE bool reassignPlayers() {
        ++reassignments;
        reassigned = true;
        emit playersChanged();
        session_.resumeInput();
        return true;
    }
    int reassignments = 0;
    bool connected = true;
    bool reassigned = false;
 signals:
    void playersChanged();
 private:
    PreviewSession& session_;
};
