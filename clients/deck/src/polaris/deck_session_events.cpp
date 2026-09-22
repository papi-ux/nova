#include "polaris/deck_session_events.h"
#include <QJsonDocument>
#include <QStringDecoder>
#include <limits>

namespace nova::deck::polaris {
namespace {
bool consecutive(const QByteArray& previous, const QByteArray& next) {
    const int a = previous.lastIndexOf(':'), b = next.lastIndexOf(':');
    if (a <= 0 || b <= 0 || previous.first(a) != next.first(b)) return false;
    const auto number = [](QByteArrayView digits) -> std::optional<quint64> {
        if (digits.empty() || digits.size() > 20) return {};
        quint64 value = 0;
        for (char c : digits) {
            if (c < '0' || c > '9' || value > (std::numeric_limits<quint64>::max() - (c - '0')) / 10) return {};
            value = value * 10 + (c - '0');
        }
        return value;
    };
    const auto x = number(QByteArrayView(previous).sliced(a + 1)), y = number(QByteArrayView(next).sliced(b + 1));
    return x && y && *x < std::numeric_limits<quint64>::max() && *y == *x + 1;
}
bool sameReading(const DeckLiveTuningTelemetry& a, const DeckLiveTuningTelemetry& b) {
    return a.instance == b.instance && a.appSession == b.appSession && a.generation == b.generation &&
        a.revision == b.revision && a.enabled == b.enabled && a.supported == b.supported &&
        a.state == b.state && a.qualityLimit == b.qualityLimit && a.requested == b.requested && a.applied == b.applied;
}
}
bool DeckSessionEventParser::feed(QByteArrayView bytes) {
    if (failed_) return false;
    for (char c : bytes) {
        if (c == '\n' && cr_) { cr_ = false; continue; }
        cr_ = c == '\r';
        if (++recordBytes_ > 65536) { failed_ = true; return false; }
        if (c == '\r' || c == '\n') {
            if (!line()) { failed_ = true; return false; }
            line_.clear();
        } else line_ += c;
    }
    return true;
}
bool DeckSessionEventParser::line() {
    if (firstLine_) { firstLine_ = false; if (line_.startsWith("\xef\xbb\xbf")) line_.remove(0, 3); }
    if (line_.isEmpty()) {
        const bool ok = dispatch();
        event_.clear(); id_.clear(); data_.clear(); recordBytes_ = 0;
        return ok;
    }
    if (line_.startsWith(':')) return true;
    const int colon = line_.indexOf(':');
    const auto field = colon < 0 ? line_ : line_.first(colon);
    auto value = colon < 0 ? QByteArray{} : line_.mid(colon + 1);
    if (value.startsWith(' ')) value.remove(0, 1);
    if (field == "event") { if (value.size() > 80) return false; event_ = value; }
    else if (field == "id") { if (value.size() > 256 || value.contains('\0')) return false; id_ = value; }
    else if (field == "data") { data_ += value; data_ += '\n'; }
    // retry and unknown fields never override the client's bounded backoff.
    return true;
}
bool DeckSessionEventParser::dispatch() {
    if (data_.isEmpty()) return true;
    QStringDecoder utf8(QStringDecoder::Utf8);
    const QString decoded = utf8.decode(data_);
    if (utf8.hasError()) return false;
    QJsonParseError error;
    const auto doc = QJsonDocument::fromJson(data_, &error);
    if (error.error != QJsonParseError::NoError || !doc.isObject()) return false;
    const auto object = doc.object();
    bool refresh = id_.isEmpty() || lastId_.isEmpty() || !consecutive(lastId_, id_);
    lastId_ = id_;
    ++records_;
    if (event_ == "session") {
        // Terminal events may belong to another session. Only a full paired
        // status refresh can establish which session is still ours.
        refresh = true;
    } else if (event_ == "state") {
        const auto state = object.value("session_state");
        const auto live = parseLiveTuningTelemetry(object.value("live_tuning").toObject());
        if (!state.isString() || state.toString().isEmpty() || state.toString().size() > 80 || !live) refresh = true;
        else {
            refresh |= state.toString() != lastState_ || !lastLive_ || !sameReading(*lastLive_, *live) ||
                (lastLive_->instance == live->instance && live->sequence <= lastLive_->sequence);
        }
        lastState_ = state.toString(); lastLive_ = live;
    }
    if (refresh && refresh_) refresh_();
    return true;
}
}
