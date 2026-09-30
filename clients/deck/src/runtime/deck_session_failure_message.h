#pragma once

#include <string>

namespace nova::deck::runtime {

  /// Why a stream did not start, as the session runtime knows it.
  struct DeckSessionFailure {
    bool cancelled = false;  ///< the person backed out before it started
    bool displayRateChanged = false;  ///< the display's rate moved under a prepared request
    bool sessionSelectionRejected = false;  ///< the host answered with a session this client did not ask for
    bool launchRefused = false;  ///< the host declined to start or resume the title
    int hostStatusCode = 0;  ///< safe numeric status from a refused launch
    bool resumed = false;  ///< the attempt was a resume rather than a fresh launch
    std::string builderError;  ///< the builder's own words, for a rejected session selection
    std::string hostMessage;  ///< what the host said when it refused, when it said anything
  };

  /**
   * @brief What to tell someone whose stream did not start.
   *
   * A host that refuses a launch is the only party that knows why: desktop Steam is already running,
   * a desktop game is holding the display, a Space is not ready. It answers with the reason and with
   * what to do about it, so its words win over anything this side could guess.
   *
   * A silent refusal still belongs to the PC. Pairing guidance is reserved for an unclassified
   * failure; it cannot establish the cause of a host refusal.
   */
  inline std::string deckSessionFailureMessage(const DeckSessionFailure &failure) {
    if (failure.cancelled) {
      return "Stream cancelled.";
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
    if (failure.launchRefused) {
      if (failure.hostStatusCode == 401 || failure.hostStatusCode == 403)
        return "The PC refused permission to start the stream. Check this device's permissions on the PC, then try again.";
      return "The PC refused to start this stream (status " + std::to_string(failure.hostStatusCode) +
        "). Check its capture settings and current sessions, then try again.";
    }
    if (failure.resumed) {
      return "The game could not be resumed. It was not asked to quit; return to the library and try again.";
    }
    return "The PC could not start this stream. Check pairing and host availability.";
  }

}  // namespace nova::deck::runtime
