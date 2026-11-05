#include "stream/deck_source_frame_timing.h"
#include <chrono>
#include <cstdlib>
#include <future>
#include <iostream>
#include <thread>
using namespace nova::deck;
namespace {
void require(bool ok, const char* why) { if (!ok) { std::cerr << why << '\n'; std::exit(1); } }
}
int main() {
    auto fence = std::make_shared<runtime::DeckSourceSubmissionFence>();
    runtime::DeckSourceClockMapper mapper;
    mapper.setSubmissionFence(fence);
    fence->reset(7);
    const auto aimed = [&] {
        stream::DeckSourceFrameTiming t;
        t.clock.epoch = mapper.epoch(); t.presentationGeneration = 7;
        t.submissionFence = fence; t.submissionTicket = fence->ticket(t.clock.epoch, 7); return t;
    };
    int submissions = 0;
    // Selection under E, then mapper mutation before mailbox admission.
    auto selected = aimed(); mapper.reset();
    require(!selected.withCurrentSubmission([&] { ++submissions; }), "old selected frame reached mailbox");
    require(submissions == 0 && fence->stats().dropped == 1, "stale selection was not counted");
    // Mailbox/job creation succeeds, then an automatic mapper re-warm before submit.
    auto job = aimed(); require(job.withCurrentSubmission([] {}), "current mailbox rejected");
    mapper.observe(1, 900, 1000);
    mapper.observe(2, 900 + 450000, 2000); // source discontinuity starts E+1
    require(!job.withCurrentSubmission([&] { ++submissions; }), "old render job reached Vulkan");
    require(!job.withCurrentSubmission([] {}) && fence->stats().dropped == 2, "job copies double-counted a stale drop");
    auto surface = aimed(); fence->reset(8);
    require(!surface.withCurrentSubmission([&] { ++submissions; }), "old presentation generation reached Vulkan");
    require(fence->stats().dropped == 3, "surface rejection was not counted");
    fence->reset(7);
    // Like Android W34: a reset started inside the submit callback must wait.
    auto current = aimed();
    std::promise<void> started; auto entered = started.get_future();
    std::promise<void> finished; auto done = finished.get_future();
    std::thread reset;
    require(current.withCurrentSubmission([&] {
        reset = std::thread([&] { started.set_value(); mapper.reset(); finished.set_value(); });
        require(entered.wait_for(std::chrono::seconds(2)) == std::future_status::ready, "reset thread did not start");
        require(done.wait_for(std::chrono::milliseconds(50)) == std::future_status::timeout, "epoch moved inside Vulkan submit/present");
        ++submissions;
    }), "current submission rejected");
    reset.join(); require(done.wait_for(std::chrono::seconds(2)) == std::future_status::ready, "reset failed to finish");
    require(submissions == 1 && !current.withCurrentSubmission([] {}), "reset did not invalidate the next use");
    // Sustained instability recursively seeds warmup inside the same mutation.
    // It must use the unlocked helper, rather than trying to acquire the fence twice.
    auto stepFence = std::make_shared<runtime::DeckSourceSubmissionFence>();
    runtime::DeckSourceClockMapper step;
    step.setSubmissionFence(stepFence);
    for (int i=0; i<20; ++i) step.observe(i+1, 1500u*(i+1), 1'000'000+16'667LL*i);
    const auto beforeStep = step.epoch();
    for (int i=20; i<28; ++i) step.observe(i+1, 1500u*(i+1), 1'050'000+16'667LL*i);
    require(step.epoch()==beforeStep+1, "bound mapper failed sustained-step rewarm");
    auto submitted = aimed();
    require(submitted.withCurrentSubmission([&] { submitted.submissionTicket->submitted = true; }), "submitted fixture failed");
    const auto dropsBeforeRedraw = fence->stats().dropped;
    mapper.reset();
    require(!submitted.withCurrentSubmission([] {}) && fence->wasRejected(submitted.submissionTicket) &&
        fence->stats().dropped==dropsBeforeRedraw, "already-submitted redraw was counted as an unsent source drop");
    auto closing = aimed(); fence->close();
    require(!closing.withCurrentSubmission([&] { ++submissions; }), "closed delivery revived a job");
    stream::DeckSourceFrameTiming off;
    require(off.withCurrentSubmission([&] { ++submissions; }) && submissions == 2, "OFF handoff changed");
    std::cout << "PASS: selected/mailbox/job epoch races, generation, once-only drops, atomic submit/present, close and OFF\n";
}
