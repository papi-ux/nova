#include "runtime/deck_source_delivery_status.h"
#include <cstdlib>
#include <iostream>
using nova::deck::runtime::DeckSourceDeliveryStatus;
void require(bool ok, const char* why) { if (!ok) { std::cerr << why << '\n'; std::exit(1); } }
int main() {
    DeckSourceDeliveryStatus s;
    require(s.effective()=="legacy" && s.fallback()=="not_requested", "OFF default changed");
    s.configure(true);
    // A selected valid target (identity 1), then B admitted without PTS (2).
    s.invalidate(2, "timing_metadata_missing");
    s.complete(1, true, "none");
    require(s.sequence()==2 && s.effective()=="pending" && s.fallback()=="timing_metadata_missing", "older A erased newer untimed B");
    s.complete(2, false, "timing_metadata_missing");
    require(s.effective()=="pending" && s.fallback()=="timing_metadata_missing", "fallback B claimed source pacing");
    // An eligible newer source-target delivery recovers, but old completions do not.
    s.complete(3, true, "none");
    require(s.effective()=="source_time_fixed" && s.fallback()=="none", "newer C did not recover");
    s.complete(2, false, "timing_metadata_missing");
    require(s.sequence()==3 && s.effective()=="source_time_fixed", "old fallback completion regressed C");
    s.invalidate(4, "renderer_not_participating"); s.complete(3,true,"none");
    require(s.effective()=="unavailable", "older success erased renderer fallback");
    s.invalidate(5, "clock_mapping_unstable"); s.complete(4,true,"none");
    require(s.effective()=="pending" && s.fallback()=="clock_mapping_unstable", "old completion crossed reset");
    std::cout << "PASS: A-valid/B-untimed admission and completion, newer recovery, old fallback and reset ordering\n";
}
