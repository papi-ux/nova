#include "runtime/deck_source_clock_mapper.h"
#include "runtime/deck_source_delivery_policy.h"
#include <algorithm>
#include <cstdlib>
#include "stream/deck_source_frame_timing.h"
#include <iostream>
#include <string>
#include <utility>
#include <vector>
using namespace nova::deck::runtime;
struct ReplayStep {
    std::uint32_t frame, rtp; std::int64_t anchor, decoded; bool reset;
    std::int64_t target, source, reserve, reserveLessThan; std::string state, reason; std::int64_t epoch;
};
// Integer series grammar is specified in the shared fixture for Kotlin/C++.
// These inputs/oracles never compute the mapper's lower envelope or slew.
struct ReplaySeries {
    std::int64_t frames = 0, ticksPerFrame = 1500, firstRtp = 90000, anchorBaseUs = 1'000'000;
    std::int64_t driftPpm = 0, jitterAmplitudeUs = 0, jitterSeed = 1, anchorResolutionUs = 1;
    std::int64_t stepAtFrame = 0, stepUs = 0, expectedRewarms = 0, expectedFirstRewarmFrame = 0;
    std::int64_t maxAppliedOffsetErrorUs = 0, errorCheckAfterFrame = 8;
    std::int64_t settledAfterFrame = 0, maxSettledAppliedOffsetErrorUs = 0;
};
struct ReplayCase {
    std::string name; DeckSourceClockPolicy policy; unsigned capacity; std::int64_t period;
    std::vector<ReplayStep> steps; ReplaySeries series;
};
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
                (s.reason.empty() || p.reason == s.reason) && (s.epoch < 0 || p.epoch == static_cast<std::uint64_t>(s.epoch)) && mapper.reserveUs() >= 0 &&
                mapper.reserveUs() <= mapper.reserveCapUs() && mapper.historySize() <= 1024;
            if (!ok) { ++failures; std::cerr << "FAIL " << c.name << " frame=" << s.frame << " target=" << p.targetUs
                << " expected=" << s.target << " source=" << p.sourceUs << " state=" << state << " reason=" << p.reason << " reserve=" << mapper.reserveUs() << '\n'; }
        }
        if (c.series.frames) {
            const auto& s = c.series;
            std::uint32_t random = static_cast<std::uint32_t>(s.jitterSeed);
            std::int64_t rewarms = 0, firstRewarm = 0, maxError = 0, settledError = 0, lastAnchor = -1;
            std::int64_t lastBase = 0, lastSource = 0, lastTarget = 0, creditRemainder = 0;
            std::int64_t epochObservations = 0;
            std::uint64_t epoch = mapper.epoch();
            unsigned invalid = 0, slewViolations = 0, nonMonotonic = 0;
            for (std::int64_t frame = 1; frame <= s.frames; ++frame) {
                const auto source = (frame - 1) * s.ticksPerFrame * 1000 / 90;
                // Signed division truncates toward zero, as on JVM integer Long.
                const auto truth = s.anchorBaseUs + source * s.driftPpm / 1'000'000 +
                    (s.stepAtFrame && frame >= s.stepAtFrame ? s.stepUs : 0);
                random = random * 1664525U + 1013904223U;
                const auto jitter = std::int64_t(random) % (s.jitterAmplitudeUs + 1);
                const auto anchor = (source + truth + jitter) / s.anchorResolutionUs * s.anchorResolutionUs;
                auto p = mapper.observe(static_cast<std::uint32_t>(frame),
                    static_cast<std::uint32_t>(s.firstRtp + (frame - 1) * s.ticksPerFrame), anchor);
                ++steps;
                if (p.epoch != epoch) { ++rewarms; if (!firstRewarm) firstRewarm = frame; }
                epochObservations = p.epoch != epoch ? 1 : epochObservations + 1;
                if (!p.valid || (p.epoch != epoch && p.reason != "clock_mapping_unstable")) ++invalid;
                if (arrivalOnly && p.valid) { p.baseUs = anchor; p.targetUs = anchor + mapper.reserveUs(); }
                const auto error = std::abs(p.baseUs - source - truth);
                if (frame >= s.errorCheckAfterFrame) maxError = std::max(maxError, error);
                if (s.settledAfterFrame && frame >= s.settledAfterFrame) settledError = std::max(settledError, error);
                if (lastAnchor >= 0 && p.epoch == epoch) {
                    const auto numerator = (anchor - lastAnchor) * c.policy.offsetSlewUsPerSecond + creditRemainder;
                    creditRemainder = epochObservations > c.policy.warmupFrames ? numerator % 1'000'000 : 0;
                    // Warmup can replace the startup offset. Steady updates can
                    // only spend this update's elapsed credit, including residue.
                    if (epochObservations > c.policy.warmupFrames &&
                        std::abs((p.baseUs - p.sourceUs) - (lastBase - lastSource)) >
                            std::min(c.policy.offsetMaxStepUs, numerator / 1'000'000)) ++slewViolations;
                    if (p.targetUs <= lastTarget) ++nonMonotonic;
                } else creditRemainder = 0;
                lastAnchor = anchor; lastBase = p.baseUs; lastSource = p.sourceUs; lastTarget = p.targetUs; epoch = p.epoch;
            }
            const bool ok = rewarms == s.expectedRewarms && firstRewarm == s.expectedFirstRewarmFrame &&
                maxError <= s.maxAppliedOffsetErrorUs && settledError <= s.maxSettledAppliedOffsetErrorUs &&
                !invalid && !slewViolations && !nonMonotonic;
            if (!ok) ++failures;
            std::cout << (ok ? "PASS " : "FAIL ") << c.name << " frames=" << s.frames
                << " rewarms=" << rewarms << " firstRewarm=" << firstRewarm << " maxErrorUs=" << maxError
                << " settledErrorUs=" << settledError << " invalid=" << invalid << " slewViolations=" << slewViolations
                << " nonMonotonic=" << nonMonotonic << '\n';
        }
    }
    // Long wrap replay, stale frames, bounded history and teardown are independent of fixture oracle generation.
    DeckSourceClockPolicy policy; policy.warmupFrames = 1;
    {
        DeckSourceClockMapper jump({}, 2, 33333);
        DeckSourceClockPoint last;
        for (unsigned i=1;i<=30;++i) last=jump.observe(i,90000+(i-1)*3000,1'000'000+(i-1)*33333);
        const auto epoch=jump.epoch();
        const auto decodedUs=1'000'000+29*33333+16000;
        const auto distant=jump.observe(31,last.rtp+81000,decodedUs);
        if (!distant.active || distant.epoch!=epoch ||
            !sourceHoldExceedsCeiling(distant.targetUs,decodedUs*1000)) ++failures;
        // There is deliberately no later observation. The delivery classifier
        // refuses the distant deadline using the same helper as production.
        if (sourceHoldExceedsCeiling(decodedUs+sourceHoldCeilingUs,decodedUs*1000) ||
            !sourceHoldExceedsCeiling(decodedUs+sourceHoldCeilingUs+1,decodedUs*1000)) ++failures;
    }
    DeckSourceClockMapper longRun(policy, 2, 10000);
    std::uint32_t raw = 0xfffffff0U;
    for (unsigned i=1;i<=6000;++i) { auto p=longRun.observe(i,raw,100000+std::int64_t(i)*10000); if (!p.valid || longRun.historySize()>1024) ++failures; raw+=900; }
    auto old=longRun.observe(6001,raw,60110000); longRun.reset(); longRun.ready(old,old.targetUs); if (longRun.reserveUs()!=3000) ++failures;
    {
        using namespace nova::deck::stream;
        DeckFrameTimingStore store;
        DeckSourceFrameTiming a, b;
        a.clock.valid = b.clock.valid = true; a.clock.active = b.clock.active = true;
        a.clock.epoch = b.clock.epoch = 1; a.clock.sourceUs = 1000; b.clock.sourceUs = 2000;
        a.clock.rtp = 90001; b.clock.rtp = 90091; a.callbackMonoNs = b.callbackMonoNs = 1'000'000;
        auto ap = store.packetPts(a.clock), bp = store.packetPts(b.clock);
        if (!ap || !bp || *bp - *ap != 1000 || !store.put(*ap,a) || !store.put(*bp,b)) ++failures;
        auto outputA = store.take(*ap, 2'000'000); // drained during submission of B
        if (!outputA || outputA->clock.rtp != 90001 || store.size()!=1) ++failures;
        store.clear(); a.clock.epoch=2; a.clock.sourceUs=0;
        auto newPts=store.packetPts(a.clock);
        if (!newPts || *newPts<=*bp || !store.stalePts(*bp) || store.stalePts(*newPts) || store.take(*bp,2'000'000)) ++failures;
        if (!store.put(*newPts,a) || store.put(*newPts,b) || store.take(*newPts,2'000'000)) ++failures;
        for (int i=0;i<64;++i) if(!store.put(i,a)) ++failures;
        if(store.put(65,b) || store.size()!=64) ++failures;
        store.expire(2'000'000'000); if(store.size()!=0) ++failures;
        store.resetDecoder(); auto reset=store.packetPts(a.clock); if(!reset || *reset!=0) ++failures;
    }
    std::cout << "cases=" << replayCases().size() << " steps=" << steps << " failures=" << failures << " arrivalOnly=" << arrivalOnly << '\n';
    return failures ? 1 : 0;
}
