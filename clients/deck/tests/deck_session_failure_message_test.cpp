#include "runtime/deck_session_failure_message.h"

#include <cstdlib>
#include <iostream>

using namespace nova::deck::runtime;

namespace {
  void require(bool ok, const char* message) {
    if (!ok) {
      std::cerr << message << '\n';
      std::exit(1);
    }
  }
}  // namespace

int main() {
  const std::string hostWords =
    "Unsafe private stream launch refused because desktop Steam or a desktop game is active. "
    "Quit it on the host, or turn on \"Close desktop Steam for private launches\" for this app.";

  // The refusal this exists for: the host knows desktop Steam is running and says what to do, and
  // that is what the person needs to read. The generic line would send them to check pairing.
  DeckSessionFailure refused;
  refused.launchRefused = true;
  refused.hostMessage = hostWords;
  require(deckSessionFailureMessage(refused) == hostWords, "a host refusal must be quoted to the person");

  // A refusal during a resume is still the host's to explain.
  DeckSessionFailure refusedResume = refused;
  refusedResume.resumed = true;
  require(deckSessionFailureMessage(refusedResume) == hostWords, "a refused resume must still quote the host");

  // A silent refusal is a host refusal, not an invented pairing failure.
  DeckSessionFailure silent;
  silent.launchRefused = true;
  silent.hostStatusCode = 503;
  require(deckSessionFailureMessage(silent).find("PC refused") != std::string::npos &&
          deckSessionFailureMessage(silent).find("503") != std::string::npos &&
          deckSessionFailureMessage(silent).find("pairing") == std::string::npos,
          "a silent refusal must preserve its host status without guessing pairing");

  // Everything the client knows better than the host still wins.
  DeckSessionFailure cancelled = refused;
  cancelled.cancelled = true;
  require(deckSessionFailureMessage(cancelled) == "Stream cancelled.", "cancelling wins over a refusal");

  DeckSessionFailure rateChanged = refused;
  rateChanged.displayRateChanged = true;
  require(deckSessionFailureMessage(rateChanged).find("display rate changed") != std::string::npos,
          "a changed display rate wins over a refusal");

  DeckSessionFailure rejected = refused;
  rejected.sessionSelectionRejected = true;
  rejected.builderError = "The host changed the session during resume.";
  require(deckSessionFailureMessage(rejected) == rejected.builderError,
          "a rejected session selection keeps its own words");

  DeckSessionFailure resume;
  resume.resumed = true;
  require(deckSessionFailureMessage(resume).find("could not be resumed") != std::string::npos,
          "a failed resume without a host message keeps the resume advice");

  DeckSessionFailure nothing;
  require(deckSessionFailureMessage(nothing).find("Check pairing") != std::string::npos,
          "an unexplained failure keeps the generic line");

  std::cout << "deck session failure messages ok" << std::endl;
  return 0;
}
