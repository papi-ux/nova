#pragma once

#include "identity/deck_native_identity.h"

#include <QUrlQuery>
#include <functional>

namespace nova::deck::identity {
enum class PairStatus { Ok, Cancelled, Timeout, Unreachable, CertificateMismatch, Rejected,
                        Malformed, WrongPin, Unsupported, AlreadySaved, StoreFailed, TrustedUnavailable };
enum class PairMode { Pin, Trusted };
QString pairingCopy(PairStatus status);

struct PairEndpoint { QString address; int httpPort = 47989; };
std::optional<PairEndpoint> pairingEndpoint(const QString& address, int port);
QString generatePairingPin();

struct PairRequest {
    PairEndpoint endpoint;
    int httpsPort = 0;
    bool tls = false;
    QString path;
    QUrlQuery query;
    QByteArray serverCertificate;
    int timeoutMs = 5000;
    bool cleanup = false;
};
struct PairReply {
    PairStatus status = PairStatus::Unreachable;
    QByteArray body;
};
using PairTransport = std::function<PairReply(const PairRequest&)>;
using PairCancelled = std::function<bool()>;
PairTransport pairingNetworkTransport(crypto::Credentials credentials, PairCancelled cancelled);

struct PairResult {
    PairStatus status = PairStatus::Unreachable;
    std::optional<DeckMoonlightHostRecord> host;
    bool authorizationMayRemain = false;
};
// Pair and confirm mTLS before returning a host pin. Save only this successful
// result. A fresh request ID prevents cancellation from targeting another pair.
PairResult pairHost(const PairEndpoint& endpoint, const crypto::Credentials& credentials,
                    const QString& pin, const std::vector<DeckMoonlightHostRecord>& savedHosts,
                    const PairTransport& transport, const PairCancelled& cancelled,
                    const std::function<bool(const DeckMoonlightHostRecord&)>& commit = {},
                    PairMode mode = PairMode::Pin);

struct UnpairResult {
    PairStatus status = PairStatus::Unreachable;
    bool requestMayHaveReachedHost = false;
};
// Authenticated server identity first, then unpair on that exact pinned peer.
// Failed/ambiguous requests retain the local record for an explicit retry/forget.
UnpairResult unpairHost(const DeckMoonlightHostRecord& host, const PairTransport& transport,
                        const PairCancelled& cancelled);

} // namespace nova::deck::identity
