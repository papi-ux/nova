#pragma once

#include <string>

namespace nova::deck::runtime {

  /// Why a preview did not start, as the session runtime knows it.
  struct DeckSessionFailure {
    bool cancelled = false;  ///< the person backed out before it started
    bool displayRateChanged = false;  ///< the display's rate moved under a prepared request
    bool sessionSelectionRejected = false;  ///< the host answered with a session this client did not ask for
    bool launchRefused = false;  ///< the host declined to start or resume the title
    bool resumed = false;  ///< the attempt was a resume rather than a fresh launch
    std::string builderError;  ///< the builder's own words, for a rejected session selection
    std::string hostMessage;  ///< what the host said when it refused, when it said anything
  };

  /**
   * @brief What to tell someone whose preview did not start.
   *
   * A host that refuses a launch is the only party that knows why: desktop Steam is already running,
   * a desktop game is holding the display, a Space is not ready. It answers with the reason and with
   * what to do about it, so its words win over anything this side could guess.
   *
   * The generic line is the last resort on purpose. It points at pairing, which is the wrong place to
   * look for every refusal above it, and sending someone to check permissions for a problem that was
   * never theirs costs more than saying nothing would have.
   */
  inline std::string deckSessionFailureMessage(const DeckSessionFailure &failure) {
    if (failure.cancelled) {
      return "Preview cancelled.";
    }
    if (failure.displayRateChanged) {
      return "The display rate changed. Review Play Setup again before starting.";
    }
    if (failure.sessionSelectionRejected) {
      return failure.builderError;
    }
    if (failure.launchRefused && !failure.hostMessage.empty()) {
      return failure.hostMessage;
    }
    if (failure.resumed) {
      return "The game could not be resumed. It was not asked to quit; return to the library and try again.";
    }
    return "The host could not start this preview. Check pairing and host availability.";
  }

}  // namespace nova::deck::runtime
