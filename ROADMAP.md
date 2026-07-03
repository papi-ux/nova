# Roadmap

Nova is public and usable today, but it is still early. This roadmap is meant to set expectations and make it easier to see where testing and contributions help most.

## Current Focus

- Keep the public Android release path simple through GitHub Releases, Obtainium, and GitHub Store.
- Keep ARM64, ARMv7, and x86_64 APK assets predictable for public releases.
- Make Polaris-backed pairing, launch modes, watch mode, and live tuning feel predictable.
- Keep handheld navigation fast and readable on real devices, especially Retroid-class landscape handhelds.
- Preserve standard Moonlight compatibility while adding Nova-specific polish around Polaris.

## Near-Term Work

- Better troubleshooting guidance for pairing, discovery, input, stream quality, and Polaris launch-mode issues.
- More device notes for Retroid, Android TV, phones, tablets, and high-refresh Android handhelds.
- Continued polish for Command Center, NovaHUD, library details, and per-game launch choices.
- Clearer issue templates and support paths now that the project is public.
- Matched Nova plus Polaris validation notes for release pairs.

## Later

- Evaluate additional Android release variants only if there is real demand.
- Continue studying native Steam Deck and iOS client options, without promising timelines.
- Broaden automated smoke coverage around pairing, launch, stream UI, Command Center, stop/cleanup, and watch/resume paths.
- Keep native streaming integration close to Moonlight lineage while improving Android/Kotlin host-aware UX.

## Known Limits

- Android is the only shipping client today.
- Public GitHub Releases ship separate ARM64, ARMv7, and x86_64 APKs; Play Store distribution is not available.
- Advanced launch modes, watch mode, live host tuning, Polaris Sync, and richer telemetry are Polaris-specific.
- Other Moonlight-compatible hosts remain usable for standard pairing, launch, and streaming, but they do not expose the full Polaris contract.

## Useful Feedback

- Device model, Android version, Nova version, installed APK ABI, host software, and network details.
- Screenshots or recordings for UI, video quality, focus, launch-mode, or input issues.
- Bounded logcat captures around crashes, ANRs, launch failures, or stream cleanup problems.
- Comparisons with standard Moonlight on the same host/game.
- Matched Nova plus Polaris version pairs when reporting Polaris-backed Library, launch, tuning, or session-state behavior.
