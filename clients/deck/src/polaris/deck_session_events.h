#pragma once
#include "polaris/deck_host_telemetry.h"
#include <QByteArray>
#include <functional>

namespace nova::deck::polaris {
// Events are invalidation hints only. They cannot establish session ownership,
// finish a stream, authorize a write or acknowledge a requested bitrate.
class DeckSessionEventParser {
public:
    explicit DeckSessionEventParser(std::function<void()> refresh) : refresh_(std::move(refresh)) {}
    bool feed(QByteArrayView bytes);
    bool complete() const { return line_.isEmpty() && recordBytes_ == 0; }
    quint64 records() const { return records_; }
private:
    bool line();
    bool dispatch();
    std::function<void()> refresh_;
    QByteArray line_, event_, id_, data_, lastId_;
    QString lastState_;
    std::optional<DeckLiveTuningTelemetry> lastLive_;
    int recordBytes_ = 0;
    quint64 records_ = 0;
    bool cr_ = false, firstLine_ = true, failed_ = false;
};
}
