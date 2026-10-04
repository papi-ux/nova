# Steam controls

The selected PC menu and Command Center contain **Steam** on Android and the native Linux client. Open Steam and Open Big Picture start or raise desktop Steam on the paired PC. Quit Steam asks Steam to shut down gracefully. When a game is running, the player must confirm Quit Steam.

Every action uses the host's observed Steam revision. An accepted request is followed by status reads; a dropped or refused request is never repeated automatically. Closing the page, changing PC, or changing pairing discards the old control state. Older hosts and unavailable ownership show a reason and require a refresh.

Polaris supplies permission and session boundaries. Viewers, another client's session, private runtimes, and Game Mode cannot use these desktop controls. Use Disconnect, Resume, and End Session for private streams.

Play Setup reports the selected mode's Steam policy. Configure the host default or per-app override in Polaris. The controls do not put monitors to sleep and do not automatically restart Steam after ending a session.
