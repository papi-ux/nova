#include "stream/deck_gamestream_library.h"

#include <Limelight.h>
#include <QMap>
#include <QSet>
#include <QUuid>
#include <QUrl>
#include <QXmlStreamReader>

namespace nova::deck::stream {
namespace {
using polaris::DeckPolarisRequestStatus;
using Fields = QMap<QString, QString>;
struct Document {
    int status = 0;
    Fields host;
    std::vector<Fields> apps;
};

std::optional<Document> readDocument(std::string_view xml, bool appList) {
    if (xml.empty() || xml.size() > (appList ? kStandardAppListLimit : kStandardServerInfoLimit)) return std::nullopt;
    QXmlStreamReader reader(QByteArray(xml.data(), static_cast<qsizetype>(xml.size())));
    Document document;
    int depth = 0, nodes = 0;
    bool rootSeen = false, inApp = false;
    QString field;
    int fieldDepth = 0;
    Fields app;
    while (!reader.atEnd()) {
        const auto token = reader.readNext();
        if (++nodes > 65536 || token == QXmlStreamReader::DTD || token == QXmlStreamReader::EntityReference)
            return std::nullopt;
        if (token == QXmlStreamReader::StartElement) {
            if (++depth > 16 || !field.isEmpty()) return std::nullopt;
            const auto name = reader.name().toString();
            if (depth == 1) {
                if (rootSeen || name != "root" || !reader.namespaceUri().isEmpty()) return std::nullopt;
                rootSeen = true;
                bool ok = false;
                document.status = reader.attributes().value("status_code").toInt(&ok);
                if (!ok) return std::nullopt;
            } else if (name == "root") return std::nullopt;
            if (appList && name == "App") {
                if (depth != 2 || inApp || document.apps.size() >= 4096) return std::nullopt;
                inApp = true;
                app.clear();
            }
            const bool knownAppField = appList && inApp && depth == 3 &&
                (name == "ID" || name == "AppTitle" || name == "IsHdrSupported");
            const bool knownHostField = !appList && depth == 2 && (name == "uniqueid" || name == "PairStatus" || name == "ServerCodecModeSupport");
            if (knownAppField || knownHostField) {
                auto& values = appList ? app : document.host;
                if (values.contains(name) || !reader.namespaceUri().isEmpty()) return std::nullopt;
                values.insert(name, {});
                field = name;
                fieldDepth = depth;
            }
        } else if (token == QXmlStreamReader::Characters) {
            if (!field.isEmpty()) {
                auto& text = (appList ? app : document.host)[field];
                text += reader.text();
                if (text.size() > (field == "AppTitle" ? 1024 : 128)) return std::nullopt;
            } else if (depth <= 1 && !reader.isWhitespace()) return std::nullopt;
        } else if (token == QXmlStreamReader::EndElement) {
            if (depth == fieldDepth) { field.clear(); fieldDepth = 0; }
            if (appList && depth == 2 && reader.name() == "App") {
                document.apps.push_back(std::move(app));
                inApp = false;
            }
            --depth;
        }
    }
    if (reader.hasError() || !rootSeen || depth != 0) return std::nullopt;
    return document;
}

template <class T>
polaris::DeckPolarisResult<T> resultFor(const std::optional<Document>& document) {
    polaris::DeckPolarisResult<T> result;
    result.status = DeckPolarisRequestStatus::MalformedBody;
    result.detail = "the host sent an invalid GameStream response";
    if (document && document->status != 200) {
        result.status = document->status == 401 || document->status == 403
            ? DeckPolarisRequestStatus::Unauthorized : DeckPolarisRequestStatus::HttpError;
        result.detail = "the host refused the GameStream request";
    }
    return result;
}
} // namespace

polaris::DeckPolarisResult<DeckStandardHostInfo> parseStandardHostInfo(std::string_view xml) {
    const auto document = readDocument(xml, false);
    auto result = resultFor<DeckStandardHostInfo>(document);
    if (!document || document->status != 200) return result;
    const auto id = document->host.value("uniqueid").trimmed();
    const auto paired = document->host.value("PairStatus").trimmed();
    if (id.isEmpty() || (paired != "0" && paired != "1")) return result;
    result.status = DeckPolarisRequestStatus::Ok;
    result.detail.clear();
    DeckStreamCapabilities capabilities;
    if (document->host.contains("ServerCodecModeSupport")) {
        const auto value = document->host.value("ServerCodecModeSupport").trimmed();
        bool ok = false;
        const int mask = value.toInt(&ok);
        for (const auto c : value) if (c < '0' || c > '9') ok = false;
        if (!ok || mask < 0) return resultFor<DeckStandardHostInfo>(std::nullopt);
        capabilities.h264 = mask == 0 || (mask & SCM_H264);
        capabilities.hevc = (mask & SCM_HEVC) != 0;
    }
    result.value = DeckStandardHostInfo{id.toStdString(), paired == "1", capabilities};
    return result;
}

polaris::DeckPolarisResult<std::vector<DeckStandardApp>> parseStandardAppList(std::string_view xml) {
    const auto document = readDocument(xml, true);
    auto result = resultFor<std::vector<DeckStandardApp>>(document);
    if (!document || document->status != 200) return result;
    std::vector<DeckStandardApp> apps;
    QSet<int> ids;
    for (const auto& app : document->apps) {
        bool validId = false;
        const auto idText = app.value("ID").trimmed();
        const int id = idText.toInt(&validId);
        for (const auto c : idText) if (c < '0' || c > '9') validId = false;
        const auto title = app.value("AppTitle").trimmed();
        const auto hdr = app.value("IsHdrSupported", "0").trimmed();
        if (!validId || id <= 0 || title.isEmpty() || ids.contains(id) || (hdr != "0" && hdr != "1")) return result;
        ids.insert(id);
        apps.push_back({id, title.toStdString(), hdr == "1"});
    }
    result.status = DeckPolarisRequestStatus::Ok;
    result.detail.clear();
    result.value = std::move(apps);
    return result;
}

std::string standardHostTarget(const std::string& path, const std::string& clientId) {
    return path + (path.find('?') == std::string::npos ? "?" : "&") + "devicename=Nova%20Deck&uniqueid=" +
        QUrl::toPercentEncoding(QString::fromStdString(clientId)).toStdString() + "&uuid=" +
        QUuid::createUuid().toString(QUuid::WithoutBraces).toStdString();
}
} // namespace nova::deck::stream
