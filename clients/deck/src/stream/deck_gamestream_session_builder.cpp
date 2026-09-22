#include "stream/deck_gamestream_session_builder.h"

#include <QByteArray>
#include <QString>
#include <QXmlStreamReader>
#include <QMap>
#include <QSet>

#include <chrono>
#include <thread>

namespace nova::deck::stream {

std::optional<DeckServerInfo> parseServerInfo(std::string_view xml) {
    QXmlStreamReader reader(QByteArray(xml.data(), static_cast<int>(xml.size())));
    if (!reader.readNextStartElement() || reader.name() != QStringLiteral("root") ||
        reader.attributes().value(QStringLiteral("status_code")) != QStringLiteral("200")) return {};
    // Only direct, unique scalar fields may supply identity/ownership. This
    // also avoids last-value-wins behavior on conflicting session snapshots.
    const QSet<QString> fields{"appversion", "GfeVersion", "ServerCodecModeSupport",
        "currentgame", "currentgameuuid", "currentgameowned", "currentgamesessiontoken", "PairStatus"};
    QMap<QString, QString> values;
    while (reader.readNextStartElement()) {
        const auto name = reader.name().toString();
        if (!fields.contains(name)) { reader.skipCurrentElement(); continue; }
        if (values.contains(name)) return {};
        values.insert(name, reader.readElementText(QXmlStreamReader::ErrorOnUnexpectedElement).trimmed());
    }
    while (!reader.atEnd()) reader.readNext();
    if (reader.hasError() || values.value("appversion").isEmpty()) return {};
    DeckServerInfo info;
    info.appVersion = values.value("appversion").toStdString();
    info.gfeVersion = values.value("GfeVersion").toStdString();
    auto integer = [&](const QString& name, int& result) {
        const auto value = values.value(name);
        if (value.isEmpty()) return false;
        for (const auto ch : value) if (ch < QChar('0') || ch > QChar('9')) return false;
        bool ok = false;
        result = value.toInt(&ok);
        return ok && result >= 0;
    };
    if (values.contains("ServerCodecModeSupport") && !integer("ServerCodecModeSupport", info.serverCodecModeSupport)) return {};
    int number = 0;
    if (values.contains("currentgame")) {
        if (!integer("currentgame", number)) return {};
        info.currentGame = number;
    }
    for (const auto& field : {QString("currentgameowned"), QString("PairStatus")}) {
        if (!values.contains(field)) continue;
        if (!integer(field, number) || number > 1) return {};
        (field == "PairStatus" ? info.paired : info.currentGameOwned) = number == 1;
    }
    info.currentGameUuid = values.value("currentgameuuid").toStdString();
    info.currentSessionToken = values.value("currentgamesessiontoken").toStdString();
    return info;
}

DeckHttpFetcher fetcherOverPolarisClient(const polaris::DeckPolarisClient& client) {
    return [&client](const std::string& target) -> DeckHttpResponse {
        const auto reply = client.get(target);
        DeckHttpResponse response;
        response.retryableTransportFailure = reply.status == polaris::DeckPolarisRequestStatus::Unreachable ||
            reply.status == polaris::DeckPolarisRequestStatus::Timeout;
        switch (reply.status) {
        case polaris::DeckPolarisRequestStatus::Unreachable:
        case polaris::DeckPolarisRequestStatus::Timeout:
        case polaris::DeckPolarisRequestStatus::InvalidIdentity:
            response.transportOk = false;
            break;
        default:
            response.transportOk = true;
            break;
        }
        response.status = reply.httpStatus;
        if (reply.value) {
            response.body = *reply.value;
        }
        return response;
    };
}

DeckSessionBuildResult buildStreamConnection(
    const DeckHttpFetcher& fetch,
    const std::string& serverAddress,
    const DeckLaunchRequest& request,
    const DeckStreamKeys& keys,
    const std::function<bool()>& cancelled, DeckSessionStartMode mode,
    const std::string& expectedSessionToken) {
    DeckSessionBuildResult result;
    auto selected = request;

    if (cancelled && cancelled()) {
        result.error = "launch cancelled";
        return result;
    }

    const DeckHttpResponse serverInfoReply = fetch("/serverinfo");
    if (!serverInfoReply.transportOk) {
        result.retryableTransportFailure = serverInfoReply.retryableTransportFailure;
        result.error = "could not reach the host for serverinfo";
        return result;
    }
    if (serverInfoReply.status != 200) {
        result.error = "host serverinfo returned an unexpected status";
        return result;
    }
    const auto serverInfo = parseServerInfo(serverInfoReply.body);
    if (!serverInfo) {
        result.error = "host serverinfo was unreadable";
        return result;
    }
    // Resolve against fresh serverinfo before any launch/resume mutation. Zero
    // is the legacy H.264 default, never implicit HEVC or Main10 support.
    const int requiredCodec = request.videoCodec == "h264" ? SCM_H264 : request.videoCodec == "hevc" ? SCM_HEVC : 0;
    const int availableCodecs = serverInfo->serverCodecModeSupport == 0 ? SCM_H264 : serverInfo->serverCodecModeSupport;
    if (!requiredCodec || !(availableCodecs & requiredCodec)) {
        result.sessionSelectionRejected = true;
        result.error = "The PC no longer offers the selected video codec. Refresh the PC and review Play Setup again.";
        return result;
    }

    if (mode != DeckSessionStartMode::Launch) {
        const bool running = serverInfo->currentGame.value_or(0) != 0 || !serverInfo->currentGameUuid.empty() ||
            !serverInfo->currentSessionToken.empty() || serverInfo->currentGameOwned.value_or(false);
        auto reject = [&](const char* copy) {
            result.sessionSelectionRejected = true;
            result.error = copy;
            return result;
        };
        if (serverInfo->paired == false)
            return reject("This PC no longer recognizes Nova's pairing. Pair it again before playing.");
        if (!running && mode == DeckSessionStartMode::ResumeOnly)
            return reject("That game is no longer running. Return to the library to start it again.");
        if (running) {
            if (serverInfo->currentGame != request.appId ||
                (!request.appUuid.empty() && serverInfo->currentGameUuid != request.appUuid))
                return reject("A different game is running on this PC. Return to the library and choose that game.");
            if (serverInfo->currentGameOwned == false)
                return reject("This game belongs to another device. Resume it from that device or end it on the PC.");
            if (serverInfo->currentGameOwned != true || serverInfo->paired != true || serverInfo->currentSessionToken.empty())
                return reject("This PC cannot verify a safe resume. End the existing game on the PC before starting again.");
            if (!expectedSessionToken.empty() && expectedSessionToken != serverInfo->currentSessionToken)
                return reject("That session has changed. Return to the library and review the game again.");
            selected.resume = true;
            selected.sessionToken = serverInfo->currentSessionToken;
            // A resume retains the host's running display/launch mode.
            selected.streamMode.clear();
        }
    }
    result.resumed = selected.resume;
    if (cancelled && cancelled()) {
        result.error = "launch cancelled";
        return result;
    }
    const DeckHttpResponse launchReply = fetch(buildLaunchTarget(selected, keys));
    if (!launchReply.transportOk) {
        result.retryableTransportFailure = launchReply.retryableTransportFailure;
        result.error = "could not reach the host to start the session";
        return result;
    }
    if (launchReply.status != 200) {
        result.launchRefused = true;
        result.launchStatusCode = launchReply.status;
        result.error = "host launch returned an unexpected status";
        return result;
    }
    const DeckLaunchResult launch = parseLaunchResponse(selected.resume, launchReply.body);
    result.launchStatusCode = launch.statusCode;
    result.launchStatusMessage = launch.statusMessage;
    if (!launch.started || launch.statusCode != 200) {
        result.launchRefused = true;
        result.error = "the host did not start the session";
        return result;
    }
    result.hostSessionStarted = true;
    result.connectionInfo.hostSessionToken = launch.sessionToken;
    if (selected.resume && !selected.sessionToken.empty() && launch.sessionToken != selected.sessionToken) {
        result.error = "The host changed the session during resume. Return to the library and try again.";
        result.sessionSelectionRejected = true;
        return result;
    }
    if (launch.rtspSessionUrl.empty()) {
        result.launchRefused = true;
        result.error = "the host started the session without an RTSP url";
        return result;
    }

    result.ok = true;
    result.connectionInfo.serverAddress = serverAddress;
    result.connectionInfo.appVersion = serverInfo->appVersion;
    result.connectionInfo.gfeVersion = serverInfo->gfeVersion;
    result.connectionInfo.rtspSessionUrl = launch.rtspSessionUrl;
    result.connectionInfo.serverCodecModeSupport = serverInfo->serverCodecModeSupport;
    result.connectionInfo.keys = keys;
    result.connectionInfo.hostSessionToken = launch.sessionToken;
    return result;
}

DeckHostCancelOutcome requestHostSessionCancel(const DeckHttpFetcher& fetch, const std::string& sessionToken) {
    DeckHostCancelOutcome outcome;
    if (!fetch) {
        outcome.summary = "host cancel not requested: no host fetcher";
        return outcome;
    }
    const std::string target = buildCancelTarget(sessionToken);
    // The host refuses (409) while it still counts a session as attached; the
    // RTSP teardown lands a moment after LiStopConnection returns.
    constexpr int kAttempts = 4;
    for (int attempt = 1; attempt <= kAttempts; ++attempt) {
        const DeckHttpResponse reply = fetch(target);
        outcome.requested = true;
        outcome.transportOk = reply.transportOk;
        outcome.httpStatus = reply.status;
        if (!reply.transportOk) {
            outcome.summary = "host cancel requested but the host could not be reached";
            return outcome;
        }
        const DeckCancelResult parsed = parseCancelResponse(reply.body);
        outcome.cancelled = reply.status == 200 && parsed.cancelled;
        outcome.hostStatusCode = parsed.statusCode;
        outcome.hostStatusMessage = parsed.statusMessage;
        if (outcome.cancelled) {
            outcome.summary = "host confirmed the app ended (attempt " + std::to_string(attempt) + ")";
            return outcome;
        }
        const bool retryable = reply.status == 200 && parsed.statusCode == 409 && attempt < kAttempts;
        if (!retryable) {
            break;
        }
        std::this_thread::sleep_for(std::chrono::milliseconds(500));
    }
    outcome.summary = "host did not confirm the app ended (http " + std::to_string(outcome.httpStatus) +
        ", host status " + std::to_string(outcome.hostStatusCode) +
        (outcome.hostStatusMessage.empty() ? std::string{} : ": " + outcome.hostStatusMessage) + ")";
    return outcome;
}

}  // namespace nova::deck::stream
