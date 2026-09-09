#include "stream/deck_gamestream_session_builder.h"

#include <QByteArray>
#include <QString>
#include <QXmlStreamReader>

namespace nova::deck::stream {

namespace {

// Read the flat GameStream serverinfo elements this needs in one pass.
struct RawServerInfo {
    bool rootOk = false;
    int statusCode = 0;
    QString appVersion;
    QString gfeVersion;
    QString codecModeSupport;
    bool sawAppVersion = false;
};

RawServerInfo readServerInfo(std::string_view xml) {
    RawServerInfo out;
    QXmlStreamReader reader(QByteArray(xml.data(), static_cast<int>(xml.size())));
    QString current;
    while (!reader.atEnd()) {
        const auto token = reader.readNext();
        if (token == QXmlStreamReader::StartElement) {
            current = reader.name().toString();
            if (current == QStringLiteral("root")) {
                out.rootOk = true;
                const auto status = reader.attributes().value(QStringLiteral("status_code"));
                if (!status.isEmpty()) {
                    out.statusCode = status.toInt();
                }
            }
        } else if (token == QXmlStreamReader::Characters && !reader.isWhitespace()) {
            const QString text = reader.text().toString().trimmed();
            if (current == QStringLiteral("appversion")) {
                out.appVersion = text;
                out.sawAppVersion = true;
            } else if (current == QStringLiteral("GfeVersion")) {
                out.gfeVersion = text;
            } else if (current == QStringLiteral("ServerCodecModeSupport")) {
                out.codecModeSupport = text;
            }
        } else if (token == QXmlStreamReader::EndElement) {
            current.clear();
        }
    }
    if (reader.hasError()) {
        out.rootOk = false;
    }
    return out;
}

}  // namespace

std::optional<DeckServerInfo> parseServerInfo(std::string_view xml) {
    const RawServerInfo raw = readServerInfo(xml);
    // The GameStream root carries its own status_code; an authorization or
    // pairing error can still include an appversion, so gate on it too.
    if (!raw.rootOk || raw.statusCode != 200 || !raw.sawAppVersion || raw.appVersion.isEmpty()) {
        return std::nullopt;
    }
    DeckServerInfo info;
    info.appVersion = raw.appVersion.toStdString();
    info.gfeVersion = raw.gfeVersion.toStdString();
    info.serverCodecModeSupport = raw.codecModeSupport.isEmpty() ? 0 : raw.codecModeSupport.toInt();
    return info;
}

DeckSessionBuildResult buildStreamConnection(
    const DeckHttpFetcher& fetch,
    const std::string& serverAddress,
    const DeckLaunchRequest& request,
    const DeckStreamKeys& keys) {
    DeckSessionBuildResult result;

    const DeckHttpResponse serverInfoReply = fetch("/serverinfo");
    if (!serverInfoReply.transportOk) {
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

    const DeckHttpResponse launchReply = fetch(buildLaunchTarget(request, keys));
    if (!launchReply.transportOk) {
        result.error = "could not reach the host to start the session";
        return result;
    }
    if (launchReply.status != 200) {
        result.error = "host launch returned an unexpected status";
        return result;
    }
    const DeckLaunchResult launch = parseLaunchResponse(request.resume, launchReply.body);
    if (!launch.started) {
        result.error = "the host did not start the session";
        return result;
    }
    if (launch.rtspSessionUrl.empty()) {
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
    return result;
}

}  // namespace nova::deck::stream
