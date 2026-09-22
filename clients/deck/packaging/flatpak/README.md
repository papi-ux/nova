# Nova Flatpak (Deck and Linux desktops)

`com.papi_ux.Nova` runs on `org.kde.Platform` 6.10. The desktop entry and newly registered Steam shortcuts open the standalone Nova interface, with Nova-owned pairing and in-app streaming. This is a development preview; release acceptance is still tracked in the Deck parity checklist.

Build a bundle from a checkout with its submodules initialised (moonlight-common-c is in-tree):

    flatpak install --user flathub org.kde.Platform//6.10 org.kde.Sdk//6.10
    flatpak-builder --user --force-clean --repo=build/flatpak-repo build/flatpak clients/deck/packaging/flatpak/com.papi_ux.Nova.json
    flatpak build-bundle build/flatpak-repo build/Nova.flatpak com.papi_ux.Nova

Install and run it (Desktop Mode on a Deck, or any desktop):

    flatpak install --user build/Nova.flatpak
    flatpak run com.papi_ux.Nova --standalone

Permissions, and why: network for paired hosts; display sockets and dri for the shell; `input` for the Deck's controls; read-only access to Moonlight's config and `org.freedesktop.Flatpak` for the explicit legacy `--live` route; the Steam userdata directories so `--register-steam-shortcut` can add Nova to Game Mode. Standalone mode stores its own pairing and never falls back to Moonlight's identity.

The native streaming path uses `xdg-run/pipewire-0` for direct
PipeWire audio output. This exposes the default PipeWire socket, not the whole
runtime directory. Packaging this audio backend does not establish standalone
Deck release readiness.

Register Nova with Steam from Desktop Mode, with Steam closed:

    flatpak run com.papi_ux.Nova --register-steam-shortcut

The shortcut runs `flatpak run com.papi_ux.Nova --standalone`; Steam shows it as "Nova" and Game Mode launches it like any other non-Steam game. Updating the Flatpak alone does not change an existing Steam shortcut. Re-register it with Steam closed to migrate an older `--live` shortcut while retaining its app ID, artwork and player customizations.
