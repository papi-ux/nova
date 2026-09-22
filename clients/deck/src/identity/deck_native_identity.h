#pragma once

#include "identity/deck_moonlight_identity.h"
#include "identity/deck_pairing_crypto.h"

#include <QString>
#include <memory>

namespace nova::deck::identity {

enum class NativeStoreStatus { Ok, Missing, Busy, Unsafe, Invalid, IoError, Conflict };
struct NativeStoreResult {
    NativeStoreStatus status = NativeStoreStatus::IoError;
    std::optional<DeckMoonlightIdentity> identity;
    bool ok() const { return status == NativeStoreStatus::Ok && identity.has_value(); }
};

// The legacy DTO is shared with the read-only imported identity adapter. Native
// files are separate, versioned, owner-only, and never silently regenerated.
QString nativeIdentityDirectory();
NativeStoreResult loadNativeIdentity(const QString& directory = nativeIdentityDirectory());
NativeStoreResult initializeNativeIdentity(const QString& directory = nativeIdentityDirectory());
NativeStoreResult saveNativeHost(const QString& directory, const crypto::Credentials& expectedIdentity,
                                const DeckMoonlightHostRecord& host);
// Local forget only; host-side revocation is a separate authenticated action.
// Guard management against an identity or host pin changed since selection.
NativeStoreResult forgetNativeHost(const QString& directory, const crypto::Credentials& expectedIdentity,
                                  const DeckMoonlightHostRecord& expectedHost);

class NativePairingLease {
public:
    explicit NativePairingLease(int fd) : fd_(fd) {}
    ~NativePairingLease();
    NativePairingLease(const NativePairingLease&) = delete;
    NativePairingLease& operator=(const NativePairingLease&) = delete;
private:
    int fd_;
};
// Prevent two Nova processes from authorizing the same certificate concurrently
// and later revoking each other's successful pairing during rollback.
std::unique_ptr<NativePairingLease> lockNativePairing(const QString& directory);

} // namespace nova::deck::identity
