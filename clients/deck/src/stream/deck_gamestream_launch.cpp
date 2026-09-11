#include "stream/deck_gamestream_launch.h"

#include <QUrl>
#include <QXmlStreamReader>

#include <array>
#include <cstdint>
#include <random>
#include <string>

namespace nova::deck::stream {

namespace {

// Write the id big-endian into the first four IV bytes, zero the rest. This is
// the same layout the Android client builds with ByteBuffer.putInt.
void writeIvFromKeyId(std::array<std::uint8_t, 16>& iv, std::int32_t rikeyId) {
    iv.fill(0);
    const auto id = static_cast<std::uint32_t>(rikeyId);
    iv[0] = static_cast<std::uint8_t>((id >> 24) & 0xFF);
    iv[1] = static_cast<std::uint8_t>((id >> 16) & 0xFF);
    iv[2] = static_cast<std::uint8_t>((id >> 8) & 0xFF);
    iv[3] = static_cast<std::uint8_t>(id & 0xFF);
}

void appendParam(std::string& out, bool& first, std::string_view key, const std::string& value) {
    out += first ? '?' : '&';
    first = false;
    out += key;
    out += '=';
    out += value;
}

// Percent-encode one query key or value so it stays a single parameter.
std::string percentEncoded(std::string_view value) {
    return QUrl::toPercentEncoding(QString::fromUtf8(value.data(), static_cast<qsizetype>(value.size()))).toStdString();
}

// Re-emit a caller-supplied "&k=v&k2=v2" tail with every key and value
// encoded. Empty pieces are dropped; a piece without '=' is kept as a bare key.
void appendEncodedExtraQuery(std::string& out, std::string_view extraQuery) {
    std::size_t position = 0;
    while (position <= extraQuery.size()) {
        const std::size_t next = extraQuery.find('&', position);
        const std::string_view piece = extraQuery.substr(position, next == std::string_view::npos ? std::string_view::npos : next - position);
        if (!piece.empty()) {
            const std::size_t equals = piece.find('=');
            out += '&';
            out += percentEncoded(piece.substr(0, equals));
            if (equals != std::string_view::npos) {
                out += '=';
                out += percentEncoded(piece.substr(equals + 1));
            }
        }
        if (next == std::string_view::npos) {
            break;
        }
        position = next + 1;
    }
}

// Read the root element's status attributes; shared by every host answer.
void readRootStatus(const QXmlStreamReader& reader, int& statusCode, std::string& statusMessage) {
    const auto status = reader.attributes().value(QStringLiteral("status_code"));
    if (!status.isEmpty()) {
        statusCode = status.toInt();
    }
    const auto message = reader.attributes().value(QStringLiteral("status_message"));
    if (!message.isEmpty()) {
        statusMessage = message.toString().toStdString();
    }
}

}  // namespace

DeckStreamKeys buildStreamKeys(const std::array<std::uint8_t, 16>& aesKey, std::int32_t rikeyId) {
    DeckStreamKeys keys;
    keys.aesKey = aesKey;
    keys.rikeyId = rikeyId;
    writeIvFromKeyId(keys.aesIv, rikeyId);
    return keys;
}

DeckStreamKeys generateStreamKeys() {
    // On Linux, std::random_device is backed by the kernel CSPRNG (/dev/urandom),
    // which is what the Deck runs. Use every bit of each 32-bit draw for the key.
    std::random_device source;
    std::array<std::uint8_t, 16> key{};
    for (std::size_t i = 0; i < key.size(); i += 4) {
        const std::uint32_t word = source();
        key[i] = static_cast<std::uint8_t>(word & 0xFF);
        key[i + 1] = static_cast<std::uint8_t>((word >> 8) & 0xFF);
        key[i + 2] = static_cast<std::uint8_t>((word >> 16) & 0xFF);
        key[i + 3] = static_cast<std::uint8_t>((word >> 24) & 0xFF);
    }
    const auto rikeyId = static_cast<std::int32_t>(source());
    return buildStreamKeys(key, rikeyId);
}

std::string toHexLower(const std::array<std::uint8_t, 16>& bytes) {
    static constexpr char kDigits[] = "0123456789abcdef";
    std::string out;
    out.reserve(bytes.size() * 2);
    for (const auto byte : bytes) {
        out.push_back(kDigits[(byte >> 4) & 0xF]);
        out.push_back(kDigits[byte & 0xF]);
    }
    return out;
}

std::string buildLaunchTarget(const DeckLaunchRequest& request, const DeckStreamKeys& keys) {
    std::string out = request.resume ? "/resume" : "/launch";
    bool first = true;
    appendParam(out, first, "appid", std::to_string(request.appId));
    if (!request.appUuid.empty()) {
        appendParam(out, first, "appuuid", percentEncoded(request.appUuid));
    }
    appendParam(out, first, "mode",
                std::to_string(request.width) + "x" + std::to_string(request.height) + "x" +
                    std::to_string(request.fps));
    appendParam(out, first, "additionalStates", "1");
    appendParam(out, first, "sops", request.sops ? "1" : "0");
    appendParam(out, first, "rikey", toHexLower(keys.aesKey));
    appendParam(out, first, "rikeyid", std::to_string(keys.rikeyId));
    appendParam(out, first, "localAudioPlayMode", request.playLocalAudio ? "1" : "0");
    appendParam(out, first, "surroundAudioInfo", std::to_string(request.surroundAudioInfo));
    appendParam(out, first, "remoteControllersBitmap", std::to_string(request.gamepadMask));
    appendParam(out, first, "gcmap", std::to_string(request.gamepadMask));
    appendParam(out, first, "gcpersist", request.persistGamepads ? "1" : "0");
    appendEncodedExtraQuery(out, request.extraQuery);
    return out;
}

DeckLaunchResult parseLaunchResponse(bool resume, std::string_view xml) {
    DeckLaunchResult result;
    QXmlStreamReader reader(QByteArray(xml.data(), static_cast<int>(xml.size())));
    QString current;
    const QString startedTag = resume ? QStringLiteral("resume") : QStringLiteral("gamesession");
    while (!reader.atEnd()) {
        const auto token = reader.readNext();
        if (token == QXmlStreamReader::StartElement) {
            current = reader.name().toString();
            if (current == QStringLiteral("root")) {
                readRootStatus(reader, result.statusCode, result.statusMessage);
            }
        } else if (token == QXmlStreamReader::Characters && !reader.isWhitespace()) {
            const QString text = reader.text().toString();
            if (current == startedTag) {
                result.started = text.trimmed() != QStringLiteral("0") && !text.trimmed().isEmpty();
            } else if (current == QStringLiteral("sessionUrl0")) {
                result.rtspSessionUrl = text.trimmed().toStdString();
            } else if (current == QStringLiteral("sessionToken")) {
                result.sessionToken = text.trimmed().toStdString();
            }
        } else if (token == QXmlStreamReader::EndElement) {
            current.clear();
        }
    }
    if (reader.hasError()) {
        return DeckLaunchResult{};
    }
    return result;
}

std::string buildCancelTarget(std::string_view sessionToken) {
    std::string out = "/cancel";
    if (!sessionToken.empty()) {
        out += "?sessiontoken=";
        out += percentEncoded(sessionToken);
    }
    return out;
}

DeckCancelResult parseCancelResponse(std::string_view xml) {
    DeckCancelResult result;
    QXmlStreamReader reader(QByteArray(xml.data(), static_cast<int>(xml.size())));
    QString current;
    while (!reader.atEnd()) {
        const auto token = reader.readNext();
        if (token == QXmlStreamReader::StartElement) {
            current = reader.name().toString();
            if (current == QStringLiteral("root")) {
                readRootStatus(reader, result.statusCode, result.statusMessage);
            }
        } else if (token == QXmlStreamReader::Characters && !reader.isWhitespace()) {
            if (current == QStringLiteral("cancel")) {
                const QString text = reader.text().toString().trimmed();
                result.cancelled = !text.isEmpty() && text != QStringLiteral("0");
            }
        } else if (token == QXmlStreamReader::EndElement) {
            current.clear();
        }
    }
    if (reader.hasError()) {
        return DeckCancelResult{};
    }
    return result;
}

}  // namespace nova::deck::stream
