// Tests for the live read-only route: identity plus a fake Polaris fetcher in,
// sanitized DTOs out, with the cached Moonlight app list as the offline fallback.
#include "backend/deck_live_read_only_state.h"

#include <atomic>
#include <cassert>
#include <chrono>
#include <condition_variable>
#include <mutex>
#include <string>
#include <string_view>

namespace {

using namespace nova::deck;
using namespace nova::deck::backend;
using polaris::DeckPolarisRequestStatus;

bool contains(const std::string& text, const std::string_view needle) {
    return text.find(needle) != std::string::npos;
}

identity::DeckMoonlightIdentity pairedIdentity() {
    identity::DeckMoonlightIdentity identity;
    identity.loaded = true;
    identity.sourceLabel = "moonlight-flatpak";
    identity.clientCertificatePem = "cert";
    identity.clientPrivateKeyPemForBackendOnly = "key";
    identity::DeckMoonlightHostRecord home;
    home.uuid = "home-uuid";
    home.hostname = "home-pc";
    home.localAddress = "home.lan";
    home.localPort = 47989;
    home.serverCertificatePem = "srv";
    home.apps = {{10, "Steam Big Picture", true, false}, {11, "Desktop", false, true}, {12, "Portal 2", false, false}};
    identity::DeckMoonlightHostRecord office;
    office.uuid = "office-uuid";
    office.hostname = "Office";
    office.hasCustomName = true;
    office.manualAddress = "office.lan";
    office.manualPort = 47989;
    office.serverCertificatePem = "srv2";
    identity.hosts = {home, office};
    return identity;
}

polaris::DeckPolarisGame game(std::string id, std::string name) {
    polaris::DeckPolarisGame entry;
    entry.id = std::move(id);
    entry.name = std::move(name);
    entry.source = "steam";
    entry.launchRecommendedMode = "headless";
    entry.steamLaunchRecommendedMode = "direct";
    return entry;
}

void assertNoPrivateMaterial(const DeckPublicReadOnlyHostLibraryState& state) {
    for (const auto& host : state.hosts) {
        for (const auto* text : {&host.displayName, &host.statusLabel, &host.subtitle, &host.provenanceLabel}) {
            assert(!contains(*text, ".lan"));
            assert(!contains(*text, "47989"));
            assert(!contains(*text, "srv"));
        }
    }
    assert(!contains(state.preflight.publicCopy, ".lan"));
}

void testLivePolarisLibraryWins() {
    const auto identity = pairedIdentity();
    std::atomic<int> calls{0};
    const auto snapshot = buildLiveSnapshot(identity, [&calls](const identity::DeckMoonlightHostRecord& host, const bool wantLibrary) {
        ++calls;
        assert(wantLibrary == (host.uuid == "home-uuid") && "only the first reachable host is asked for its library");
        DeckLivePolarisFetch fetch;
        if (host.uuid == "home-uuid") {
            fetch.status = DeckPolarisRequestStatus::Ok;
            fetch.serverVersion = "1.4.4";
            fetch.games = {game("g1", "Slay the Spire 2"), game("g2", "ARC Raiders")};
        } else {
            fetch.status = DeckPolarisRequestStatus::Unreachable;
            fetch.detail = "connection refused";
        }
        return fetch;
    });
    assert(calls == 2);
    assert(snapshot.identityLoaded);
    assert(snapshot.clientIdentityUsable);
    assert(snapshot.identitySourceLabel == "moonlight-flatpak");
    assert(snapshot.selectedHostId == "home-uuid");
    assert(snapshot.library.games.size() == 2);
    assert(snapshot.library.games[0].name == "Slay the Spire 2");
    assert(snapshot.library.games[0].launchMode.recommendedMode == "headless");
    assert(contains(snapshot.library.sourceLabel, "Polaris library"));
    assert(snapshot.hosts.size() == 2);
    assert(snapshot.hosts[0].state == DeckHostState::Online);
    assert(snapshot.hosts[0].polarisAvailable);
    assert(snapshot.hosts[0].standardAppListAvailable);
    assert(!snapshot.hosts[0].fixtureOnly);
    assert(contains(snapshot.hosts[0].publicStatusLabel, "Polaris 1.4.4"));
    assert(snapshot.hosts[1].state == DeckHostState::Offline);
    assert(!snapshot.hosts[1].polarisAvailable);
    assert(snapshot.probes[1].librarySource == "none");

    const DeckLaunchPreflightService preflightService;
    const DeckLiveReadOnlyStateProvider provider(snapshot, preflightService);
    const auto matrix = provider.stateMatrix();
    assert(matrix.size() == 1);
    const auto state = provider.stateForScenario("anything");
    assert(state.scenarioId == "live");
    assert(state.scenarioLabel == "Live · 1 Polaris host reachable");
    assert(state.hosts.size() == 2);
    assert(state.hosts[0].displayName == "home-pc");
    assert(state.hosts[0].initialFocus);
    assert(state.hosts[1].displayName == "Office");
    assert(state.games.size() == 2);
    assert(state.games[1].title == "ARC Raiders");
    assert(state.games[0].launchModeLabel == "Stream: headless · Steam: direct");
    assert(!state.preflight.backendPowerStarted);
    assert(!state.preflight.streamAllowed);
    assert(contains(state.preflight.publicCopy, "source=moonlight-pairing-live-read-only"));
    assert(!contains(state.preflight.publicCopy, "source=backend-owned-read-only-model"));
    assert(contains(state.preflight.publicCopy, "handoff=not-yet-executable"));
    for (const auto& code : state.preflight.blockerCodes) {
        assert(code != "pairing-required" && "a host reached over the Moonlight certificate is paired");
        assert(code != "app-not-found");
    }
    assert(state.dtoParity.contractId == "backend-owned-read-only-dto-v1");
    // The player-state copy is owned by the shared preflight defaults, which
    // rewrite the title per blocker; the live provider only has to feed it.
    assert(!state.playerState.title.empty());
    assert(state.playerState.provenanceLabel == "dto-player-state/backend-owned/redacted-public");
    assertNoPrivateMaterial(state);
}

void testOfflineFallsBackToMoonlightCachedApps() {
    const auto identity = pairedIdentity();
    const auto snapshot = buildLiveSnapshot(identity, [](const identity::DeckMoonlightHostRecord&, bool) {
        DeckLivePolarisFetch fetch;
        fetch.status = DeckPolarisRequestStatus::Timeout;
        fetch.detail = "no answer within the timeout";
        return fetch;
    });
    assert(snapshot.selectedHostId == "home-uuid");
    assert(snapshot.library.games.size() == 2);
    assert(snapshot.library.games[0].id == "moonlight-app-10");
    assert(snapshot.library.games[0].name == "Steam Big Picture");
    assert(snapshot.library.games[0].source == "moonlight");
    assert(snapshot.library.games[0].hdrSupported);
    assert(snapshot.library.games[1].name == "Portal 2");
    assert(contains(snapshot.library.sourceLabel, "cached apps"));
    assert(snapshot.hosts[0].state == DeckHostState::Offline);
    assert(contains(snapshot.hosts[0].publicSubtitle, "3 apps remembered by Moonlight"));
    assert(snapshot.probes[0].librarySource == "moonlight-cached-app-list");

    const DeckLaunchPreflightService preflightService;
    const DeckLiveReadOnlyStateProvider provider(snapshot, preflightService);
    const auto state = provider.stateForScenario("live");
    assert(state.scenarioLabel == "Offline · Moonlight's cached apps");
    assert(state.games.size() == 2);
    assert(!state.preflight.streamAllowed);
    assertNoPrivateMaterial(state);
}

void testCertMismatchAndUnauthorizedAreNamed() {
    const auto identity = pairedIdentity();
    const auto snapshot = buildLiveSnapshot(identity, [](const identity::DeckMoonlightHostRecord& host, bool) {
        DeckLivePolarisFetch fetch;
        fetch.status = host.uuid == "home-uuid" ? DeckPolarisRequestStatus::CertMismatch : DeckPolarisRequestStatus::Unauthorized;
        return fetch;
    });
    assert(snapshot.hosts[0].state == DeckHostState::CertMismatch);
    assert(contains(snapshot.hosts[0].publicStatusLabel, "certificate changed"));
    assert(snapshot.hosts[1].state == DeckHostState::AuthRejected);
    assert(contains(snapshot.hosts[1].publicStatusLabel, "rejected this client"));

    // The preflight judges the selected host's real credential facts.
    const auto credentials = credentialsForSelectedHost(snapshot);
    assert(credentials.hostId == "home-uuid");
    assert(credentials.paired);
    assert(credentials.certMismatch);
    assert(!credentials.authRejected);
    const DeckLaunchPreflightService preflightService;
    const DeckLiveReadOnlyStateProvider provider(snapshot, preflightService);
    const auto state = provider.stateForScenario("live");
    bool sawCertMismatch = false;
    for (const auto& code : state.preflight.blockerCodes) {
        sawCertMismatch = sawCertMismatch || code == "cert-mismatch";
    }
    assert(sawCertMismatch);
}

void testMissingIdentityAndMissingCertificates() {
    const auto empty = buildLiveSnapshot(identity::DeckMoonlightIdentity{}, [](const identity::DeckMoonlightHostRecord&, bool) {
        assert(false && "no host should be probed without an identity");
        return DeckLivePolarisFetch{};
    });
    assert(!empty.identityLoaded);
    assert(empty.hosts.empty());
    const DeckLaunchPreflightService preflightService;
    const DeckLiveReadOnlyStateProvider provider(empty, preflightService);
    const auto state = provider.stateForScenario("live");
    assert(state.scenarioLabel == "Moonlight not paired on this device");
    assert(state.hosts.empty());
    assert(state.games.empty());

    auto unpinned = pairedIdentity();
    unpinned.hosts[0].serverCertificatePem.clear();
    unpinned.hosts.resize(1);
    std::atomic<int> calls{0};
    const auto snapshot = buildLiveSnapshot(unpinned, [&calls](const identity::DeckMoonlightHostRecord&, bool) {
        ++calls;
        return DeckLivePolarisFetch{};
    });
    assert(calls == 0);
    assert(snapshot.hosts[0].state == DeckHostState::PairingNeeded);
    assert(snapshot.probes[0].status == DeckPolarisRequestStatus::InvalidIdentity);
    assert(!credentialsForSelectedHost(snapshot).paired);
}

void testSelectedHostLeadsEvenWhenMoonlightListsItSecond() {
    auto identity = pairedIdentity();
    std::swap(identity.hosts[0], identity.hosts[1]);  // Office first in Moonlight's order, no cached apps
    std::atomic<int> homeLibraryAsks{0};
    std::atomic<int> homeProbes{0};
    const auto snapshot = buildLiveSnapshot(identity, [&](const identity::DeckMoonlightHostRecord& host, const bool wantLibrary) {
        DeckLivePolarisFetch fetch;
        if (host.uuid == "home-uuid") {
            (wantLibrary ? homeLibraryAsks : homeProbes)++;
            fetch.status = DeckPolarisRequestStatus::Ok;
            if (wantLibrary) {
                fetch.games = {game("g1", "Portal 2")};
            }
        } else {
            assert(wantLibrary && "the first host in Moonlight's order is asked for the library up front");
        }
        return fetch;
    });
    // Office was asked for the library first and did not answer, so Home was
    // probed for capabilities in the same round and then asked once more.
    assert(homeProbes == 1 && homeLibraryAsks == 1);
    assert(snapshot.library.games.size() == 1);
    assert(snapshot.selectedHostId == "home-uuid");
    assert(snapshot.hosts[0].id == "office-uuid" && "snapshot keeps Moonlight's order");
    const auto ordered = hostsSelectedFirst(snapshot);
    assert(ordered[0].id == "home-uuid");
    assert(ordered[1].id == "office-uuid");

    const DeckLaunchPreflightService preflightService;
    const DeckLiveReadOnlyStateProvider provider(snapshot, preflightService);
    const auto state = provider.stateForScenario("live");
    assert(state.hosts[0].id == "home-uuid" && state.hosts[0].initialFocus);
    assert(!state.hosts[1].initialFocus);
    for (const auto& code : state.preflight.blockerCodes) {
        assert(code != "host-unreachable" && "the preflight judges the host the library came from");
    }
}

void testReachableHostWithoutMoonlightCacheStillHasApps() {
    auto identity = pairedIdentity();
    identity.hosts[0].apps.clear();
    identity.hosts.resize(1);
    const auto snapshot = buildLiveSnapshot(identity, [](const identity::DeckMoonlightHostRecord&, bool) {
        DeckLivePolarisFetch fetch;
        fetch.status = DeckPolarisRequestStatus::Ok;
        fetch.games = {game("g1", "Portal 2")};
        return fetch;
    });
    assert(snapshot.hosts[0].polarisAvailable);
    assert(snapshot.hosts[0].standardAppListAvailable && "a live library counts as an app list");
}

void testHostsAreProbedConcurrently() {
    // Three paired hosts whose probes each wait for the other two before
    // answering. A builder that probed one host at a time would never let the
    // second probe start while the first is waiting, so the wait below would
    // hit its bound; a concurrent builder releases all three at once.
    auto identity = pairedIdentity();
    identity::DeckMoonlightHostRecord garage;
    garage.uuid = "garage-uuid";
    garage.hostname = "garage";
    garage.localAddress = "garage.lan";
    garage.serverCertificatePem = "srv3";
    identity.hosts.push_back(garage);
    const int hostCount = static_cast<int>(identity.hosts.size());

    std::mutex mutex;
    std::condition_variable allStarted;
    int started = 0;
    std::atomic<bool> waitedOut{false};
    const auto snapshot = buildLiveSnapshot(identity, [&](const identity::DeckMoonlightHostRecord& host, bool) {
        {
            std::unique_lock lock(mutex);
            ++started;
            allStarted.notify_all();
            if (!allStarted.wait_for(lock, std::chrono::seconds(2), [&] { return started >= hostCount; })) {
                waitedOut = true;
            }
        }
        DeckLivePolarisFetch fetch;
        fetch.status = host.uuid == "office-uuid" ? DeckPolarisRequestStatus::Ok : DeckPolarisRequestStatus::Timeout;
        if (fetch.status == DeckPolarisRequestStatus::Ok) {
            fetch.games = {game("g1", "Portal 2")};
        }
        return fetch;
    });
    assert(!waitedOut && "hosts must be probed concurrently, not one after another");
    assert(snapshot.hosts.size() == 3 && "results keep Moonlight's order");
    assert(snapshot.hosts[0].id == "home-uuid" && snapshot.hosts[1].id == "office-uuid" && snapshot.hosts[2].id == "garage-uuid");
    assert(snapshot.selectedHostId == "office-uuid" && "the first reachable host still wins");
    assert(snapshot.library.games.size() == 1);
}

void testTerminalSummaryIsSanitized() {
    const auto identity = pairedIdentity();
    const auto snapshot = buildLiveSnapshot(identity, [](const identity::DeckMoonlightHostRecord&, bool) {
        DeckLivePolarisFetch fetch;
        fetch.status = DeckPolarisRequestStatus::Ok;
        fetch.serverVersion = "1.4.4";
        fetch.games = {game("g1", "Portal 2")};
        return fetch;
    });
    const auto summary = describeLiveSnapshotForTerminal(snapshot);
    assert(contains(summary, "identity: moonlight-flatpak client=usable"));
    assert(contains(summary, "home-pc [home-uuid] status=ok library=polaris-live games=1"));
    assert(contains(summary, "- Portal 2 [steam]"));
    assert(!contains(summary, "home.lan"));
    assert(!contains(summary, "47989"));
    assert(!contains(summary, "key"));
}

} // namespace

int main() {
    testLivePolarisLibraryWins();
    testOfflineFallsBackToMoonlightCachedApps();
    testCertMismatchAndUnauthorizedAreNamed();
    testMissingIdentityAndMissingCertificates();
    testSelectedHostLeadsEvenWhenMoonlightListsItSecond();
    testReachableHostWithoutMoonlightCacheStillHasApps();
    testHostsAreProbedConcurrently();
    testTerminalSummaryIsSanitized();
    return 0;
}
