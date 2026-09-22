#include "polaris/deck_doctor.h"
#include <QJsonArray>
#include <QSet>
#include <cmath>

namespace nova::deck::polaris {
namespace {
QString code(const QJsonObject& o, const char* key) {
    const auto v = o.value(key);
    return v.isString() && v.toString().size() <= 80 ? v.toString().trimmed().toLower() : QString{};
}
bool oneOf(const QString& v, std::initializer_list<const char*> choices) {
    for (auto choice : choices) if (v == choice) return true;
    return false;
}
struct Finding { const char* id; const char* group; const char* title; const char* unit; const char* detail; };
constexpr Finding findings[] = {
    {"streaming", "HOST", "Active stream", "", "Whether the host is reporting an active stream."},
    {"capture_path", "HOST", "Capture path", "", "Capture readiness and frame-age budget on the host."},
    {"encoder", "HOST", "Encoder", "", "Host encoder timing against the low-latency budget."},
    {"encoder_selection", "HOST", "Encoder selection", "", "Whether the host selected its preferred encoder or a fallback."},
    {"display_mode_decision", "HOST", "Display mode override", "", "A host display override may replace the requested resolution. Review Display Mode Override on the PC."},
    {"packet_loss", "NET", "Video packet loss", "%", "Only media-path measurements establish video packet loss."},
    {"control_channel_packet_loss", "NET", "Control-channel retries", "%", "Reliable-channel loss estimate. This is not video packet loss and cannot justify reducing quality."},
    {"latency", "NET", "Round-trip latency", "ms", "Round-trip latency from the active client control channel."},
    {"bitrate", "HOST", "Host bitrate reading", "kbps", "This host reading may be a controller target. Encoder-applied bitrate is shown separately under Session."},
    {"fec_protection", "NET", "Frames outside FEC protection", "frames", "Oversized encoded frames are sender evidence, not proof of media packet loss."},
    {"live_bitrate_owner", "HOST", "Live bitrate ownership", "", "Whether Live Tuning or a verified Doctor action can own the bitrate target."},
    {"live_bitrate_control", "HOST", "Live bitrate support", "", "Whether the active encoder accepts bitrate changes without restarting the stream."},
    {"live_bitrate_retune", "HOST", "Live bitrate support", "", "Whether the active encoder accepts bitrate changes without restarting the stream."},
    {"effective_quality_ceiling", "HOST", "Quality ceiling", "kbps", "The paired preference after host capability validation; this is not encoder acknowledgement."},
    {"target_fps_gap", "HOST", "Target FPS gap", "FPS", "A shortfall needs source cadence and complete observation windows before it becomes a pacing finding."},
    {"frame_pacing", "HOST", "Frame interval error", "ms", "Source-frame timing against the requested interval. This is not network jitter."},
    {"source_capture", "HOST", "Source capture", "", "Evidence from the host capture stage."},
    {"encode_cadence", "HOST", "Encode cadence", "", "Evidence from the host's encoded-frame cadence."},
    {"steam_input_compatibility", "HOST", "Steam Input compatibility", "", "Host Steam Input compatibility with the stream's controller isolation."},
    {"decoder", "CLIENT", "Decoder", "", "Decoder findings require client-side measurements."},
    {"delivery_cadence", "CLIENT", "Delivery cadence", "", "Cadence observed at the client delivery stage."},
    {"receive_decode_render", "CLIENT", "Receive, decode and render", "", "Client stages are separate measurements; a completed draw does not prove a panel flip."},
    {"presentation", "CLIENT", "Presentation", "", "Presentation findings require measured client evidence."},
};
QString sourceLabel(const QString& source) {
    if (source == "stream_stats") return "Host stream telemetry";
    if (source == "media_transport") return "Media transport";
    if (source == "enet_control_channel" || source == "control_channel") return "Control channel";
    if (source == "launch_policy" || source == "deterministic_launch_policy" || source == "launch") return "Launch policy";
    if (source == "encoder_capability" || source == "encoder") return "Encoder capability";
    if (source == "deterministic_controller") return "Bitrate controller";
    if (source == "local_steam_config") return "Host Steam configuration";
    if (source == "sender_packetizer") return "Host packetizer";
    if (source == "client_telemetry" || source == "client") return "Client telemetry";
    return "Source unavailable";
}
QString measurement(const QJsonValue& value, const Finding& finding, const QString& source) {
    // Unknown units or numeric strings are not measurements. Explicit missing
    // media evidence never becomes 0% loss, even if a legacy value is attached.
    if (QString(finding.id) == "packet_loss" && source != "media_transport") return "Unavailable";
    if (sourceLabel(source) == "Source unavailable") return "Unavailable";
    if (value.isBool()) {
        if (QString(finding.id) == "streaming") return value.toBool() ? "Active" : "Inactive";
        if (QString(finding.id) == "live_bitrate_control" || QString(finding.id) == "live_bitrate_retune")
            return value.toBool() ? "Supported" : "Not supported";
    }
    const double n = value.toDouble(-1);
    if (!value.isDouble() || !std::isfinite(n) || n < 0 || n > 1000000000 || !*finding.unit) return {};
    if (QString(finding.unit) == "%" && n > 100) return "Unavailable";
    if (QString(finding.unit) == "kbps") return QString::number(n / 1000, 'f', 1) + " Mbps";
    return QString::number(n, 'f', QString(finding.unit) == "frames" ? 0 : 1) + " " + finding.unit;
}
}
QVariantMap doctorPresentation(const QJsonObject& d) {
    const auto version = d.value("version"), result = d.value("result_id");
    const auto status = code(d, "status"), severity = code(d, "severity"), light = code(d, "traffic_light");
    if (!version.isDouble() || version.toDouble() != 2 || !result.isString() || result.toString().trimmed().isEmpty() ||
        result.toString().size() > 256 || !oneOf(status, {"ok", "needs_action", "unknown"}) ||
        !oneOf(severity, {"info", "warning", "critical"}) || !oneOf(light, {"green", "amber", "red"}) ||
        !d.value("evidence").isArray() || d.value("evidence").toArray().size() > 128) return {};
    const bool healthy = status == "ok" && severity == "info" && light == "green";
    const QString primary = code(d, "primary_issue");
    QVariantList rows;
    QSet<QString> seen;
    QString highlight, highlightedGroup, highlightedId;
    bool networkFailure = false;
    for (const auto& value : d.value("evidence").toArray()) {
        if (!value.isObject()) return {};
        const auto item = value.toObject();
        const auto id = code(item, "id"), state = code(item, "status"), source = code(item, "source");
        const Finding* finding = nullptr;
        for (const auto& candidate : findings) if (id == candidate.id) { finding = &candidate; break; }
        if (!finding) continue;
        if (seen.contains(id)) return {}; // Ambiguous evidence cannot choose a verdict.
        seen.insert(id);
        const bool known = oneOf(state, {"pass", "info", "watch", "warning", "fail", "degraded", "needs_action", "unknown"});
        bool actionable = known && oneOf(state, {"watch", "warning", "fail", "degraded", "needs_action"});
        if (id == "control_channel_packet_loss" || id == "fec_protection") actionable = false;
        if (id == "packet_loss") actionable &= state == "fail" && source == "media_transport";
        if (id == "latency") actionable &= state == "fail" && source == "stream_stats";
        if (id != "display_mode_decision" && healthy && state == "watch") actionable = false;
        const QString reading = measurement(item.value("value"), *finding, source);
        const bool unproven = (id == "packet_loss" && source != "media_transport") ||
            ((id == "packet_loss" || id == "latency") && (reading.isEmpty() || reading == "Unavailable")) ||
            sourceLabel(source) == "Source unavailable";
        if (unproven) actionable = false;
        networkFailure |= actionable && (id == "packet_loss" || id == "latency");
        const QString grade = !known || unproven ? "Unavailable" : id == "control_channel_packet_loss" || id == "fec_protection" ? "Observation"
            : actionable ? "Needs attention" : state == "pass" ? "Pass" : state == "unknown" ? "Unavailable" : "Observation";
        QVariantMap row{{"id", id}, {"group", finding->group}, {"title", finding->title}, {"reading", reading},
            {"grade", grade}, {"tone", actionable ? "warning" : grade == "Pass" ? "stable" : "muted"},
            {"source", sourceLabel(source)}, {"detail", finding->detail}};
        if (highlight.isEmpty() && actionable) {
            highlight = QString(finding->title) + (reading.isEmpty() || reading == "Unavailable" ? "" : " · " + reading);
            highlightedGroup = finding->group;
            highlightedId = id;
        }
        rows.push_back(row);
    }
    QString title = "Review the measured stream evidence", group = highlightedGroup;
    QString advice = "Review the evidence and refresh after more gameplay.";
    if (primary == "none" || primary.isEmpty()) {
        title = healthy && highlight.isEmpty() ? "No confirmed issue" : "Stream evidence needs attention";
        advice = healthy && highlight.isEmpty() ? "Keep playing. Refresh if the stream changes." : advice;
    } else if (primary == "control_channel_observation") {
        title = "Link retries observed; video loss is not confirmed"; group = "NET";
        advice = "Keep video loss separate from control-channel retries. Refresh to check current evidence.";
    } else if (oneOf(primary, {"network_observation", "network_jitter", "packet_loss", "network_limited"})) {
        title = networkFailure ? "Network pressure is affecting this stream" : "Network readings need another check"; group = "NET";
        advice = "Review media loss and round-trip latency together, then refresh after more gameplay.";
    } else if (primary == "frame_pacing") {
        title = "Frame pacing needs attention"; group = "HOST";
        advice = "Review source cadence and frame interval error after the startup observation window.";
    } else if (primary == "encoder_load") {
        title = "Encoder load is above the latency budget"; group = "HOST";
        advice = "Review host encoder timing and encoder selection.";
    } else if (primary == "steam_input_conflict") {
        title = "Steam Input conflicts with controller isolation"; group = "HOST";
        advice = "Review Steam Input compatibility on the PC before changing controller settings.";
    } else if (primary == "quality_reduced_live") {
        title = "The live bitrate target is below the quality ceiling"; group = "HOST";
        advice = "Compare the quality ceiling, encoder-applied bitrate and current network readings.";
    } else if (primary.startsWith("capture_")) {
        title = "Review the host capture path"; group = "HOST";
    } else if (primary.contains("decoder") || primary.contains("client") || primary.contains("presentation")) {
        title = "Review client delivery and presentation"; group = "CLIENT";
    } else if (primary == "host_render_limited") {
        title = "The host is limiting frame delivery"; group = "HOST";
    }
    // Android v1.4.11 keeps actionable display override evidence visible even
    // when the overall envelope says ok/info/green.
    if (healthy && !highlight.isEmpty()) title = "Stream evidence needs attention";
    if (highlightedId == "display_mode_decision") {
        title = "A host display override changed the requested mode";
        advice = "Review Display Mode Override on the PC to use the resolution requested by Nova.";
    }
    const auto confidence = code(d.value("confidence").toObject(), "level");
    return {{"available", true}, {"title", title}, {"group", group.isEmpty() ? "SESSION" : group},
        {"tone", !highlight.isEmpty() ? "warning" : healthy && primary == "none" ? "stable" : "muted"},
        {"advice", advice}, {"highlight", highlight}, {"evidence", rows},
        {"confidence", oneOf(confidence, {"low", "medium", "high"}) ? confidence : "unknown"}};
}
}
