#include "runtime/deck_source_clock_mapper.h"
#include <cstdlib>
#include <iostream>
#include <string>
#include <vector>
using namespace nova::deck::runtime;
struct ReplayStep {
    std::uint32_t frame, rtp; std::int64_t anchor, decoded; bool reset;
    std::int64_t target, source, reserve, reserveLessThan; std::string state, reason;
};
struct ReplayCase { std::string name; DeckSourceClockPolicy policy; unsigned capacity; std::int64_t period; std::vector<ReplayStep> steps; };
#include NOVA_SOURCE_REPLAY_HEADER
int main(int argc, char** argv) {
    bool arrivalOnly = argc == 2 && std::string(argv[1]) == "--arrival-only";
    unsigned failures = replayPolicyDefaultsMatch() ? 0 : 1, steps = 0;
    for (const auto& c : replayCases()) {
        DeckSourceClockMapper mapper(c.policy, c.capacity, c.period);
        for (const auto& s : c.steps) {
            if (s.reset) mapper.reset();
            auto p = mapper.observe(s.frame, s.rtp, s.anchor); ++steps;
            if (arrivalOnly && p.valid) p.targetUs = s.anchor + mapper.reserveUs();
            if (s.decoded >= 0) mapper.ready(p, s.decoded);
            const std::string state = !p.valid ? "unavailable" : p.active ? "source_time" : "pending";
            const bool ok = state == s.state && (s.target < 0 || p.targetUs == s.target) &&
                (s.source < 0 || p.sourceUs == s.source) && (s.reserve < 0 || mapper.reserveUs() == s.reserve) &&
                (s.reserveLessThan < 0 || mapper.reserveUs() < s.reserveLessThan) &&
                (s.reason.empty() || p.reason == s.reason) && mapper.reserveUs() >= 0 &&
                mapper.reserveUs() <= mapper.reserveCapUs() && mapper.historySize() <= 1024;
            if (!ok) { ++failures; std::cerr << "FAIL " << c.name << " frame=" << s.frame << " target=" << p.targetUs
                << " expected=" << s.target << " source=" << p.sourceUs << " state=" << state << " reason=" << p.reason << " reserve=" << mapper.reserveUs() << '\n'; }
        }
    }
    // Long wrap replay, stale frames, bounded history and teardown are independent of fixture oracle generation.
    DeckSourceClockPolicy policy; policy.warmupFrames = 1;
    DeckSourceClockMapper longRun(policy, 2, 10000);
    std::uint32_t raw = 0xfffffff0U;
    for (unsigned i=1;i<=6000;++i) { auto p=longRun.observe(i,raw,100000+std::int64_t(i)*10000); if (!p.valid || longRun.historySize()>1024) ++failures; raw+=900; }
    auto old=longRun.observe(6001,raw,60110000); longRun.reset(); longRun.ready(old,old.targetUs); if (longRun.reserveUs()!=3000) ++failures;
    std::cout << "cases=" << replayCases().size() << " steps=" << steps << " failures=" << failures << " arrivalOnly=" << arrivalOnly << '\n';
    return failures ? 1 : 0;
}
