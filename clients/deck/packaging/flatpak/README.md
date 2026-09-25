# Nova Flatpak (Deck and Linux desktops)

`com.papi_ux.Nova` runs on `org.kde.Platform` 6.10. The desktop entry and newly registered Steam shortcuts open the standalone Nova interface, with Nova-owned pairing and in-app streaming. This is a development preview; release acceptance is still tracked in the Deck parity checklist.

Signed channel builds support **Settings → Nova → Nova Updates**, including
optional automatic installation while idle. Ordinary bundles built with the
commands below do not configure an update feed. See the
[Linux update guide](../../../../docs/linux-updates.md) for feed staging,
publication and migration from an existing bundle.

Build a bundle from a checkout with its submodules initialised (moonlight-common-c is in-tree):

    flatpak install --user flathub org.kde.Platform//6.10 org.kde.Sdk//6.10
    flatpak-builder --user --force-clean --repo=build/flatpak-repo build/flatpak clients/deck/packaging/flatpak/com.papi_ux.Nova.json
    flatpak build-bundle build/flatpak-repo build/Nova.flatpak com.papi_ux.Nova

Install and run it (Desktop Mode on a Deck, or any desktop):

    flatpak install --user build/Nova.flatpak
    flatpak run com.papi_ux.Nova --standalone

Permissions, and why: network for paired hosts and local service discovery; `org.freedesktop.Avahi` on the system bus for explicit Find PCs searches; display sockets and dri for the shell; `input` for the Deck's controls; read-only access to Moonlight's config and `org.freedesktop.Flatpak` for the explicit legacy `--live` route; the Steam userdata directories so `--register-steam-shortcut` can add Nova to Game Mode. Standalone mode stores its own pairing and never falls back to Moonlight's identity.

The native streaming path uses `xdg-run/pipewire-0` for direct
PipeWire audio output. This exposes the default PipeWire socket, not the whole
runtime directory. Packaging this audio backend does not establish standalone
Deck release readiness.

Register Nova with Steam from Desktop Mode, with Steam closed:

    flatpak run com.papi_ux.Nova --register-steam-shortcut

The shortcut runs `flatpak run com.papi_ux.Nova --standalone`; Steam shows it as "Nova" and Game Mode launches it like any other non-Steam game. Updating the Flatpak alone does not change an existing Steam shortcut. Re-register it with Steam closed to migrate an older `--live` shortcut while retaining its app ID, artwork and player customizations.

For right-trackpad pointer movement in Game Mode, open Nova's page in Steam,
select the controller icon, and set **Right Trackpad Behavior → As Mouse**.
Steam's default **Gamepad With Joystick Trackpad** layout sends joystick input
from that pad. Changing only this behavior keeps the other gamepad mappings.
Leave the editor and reopen it to check that **As Mouse** was saved. If Steam
reverts the setting, close Nova, restart Steam, and retry the change.

In Nova, **Direct Pointer** follows the pointer within the streamed picture;
close Command Center to send mouse input to the PC. **View + Menu** opens
Command Center again. Steam Input controls the trackpad's output; installing or
updating Nova does not replace a player's Steam Input layout.

Local PC search uses the device's Avahi 0.8+ service to browse local
`_nvstream._tcp` advertisements. It does not scan IP ranges or label advertised
PCs trusted. Multiple addresses for the same service appear as one PC, preferring
the physical LAN over VPN and local container interfaces. Different service
targets or ports remain separate even when their display names match.
Results only fill the address/HTTP port for Nova's existing Trusted
Pair or PIN flow. The search stops after eight seconds, supports multiple
interfaces and scoped IPv6 addresses, and expires retained choices after one
minute. Without Avahi or its sandbox permission, manual entry remains available.

Desktop is the default destination for a device with no Space assignment. Nova
loads the normal Desktop library under the host's ordinary permissions; it does
not create or select a Space. Assigned Space restrictions, denied Desktop
access and destination changes continue to be checked before launching.

The KDE 6.10 SDK supplies the WaylandClient/GuiPrivate headers and
wayland-protocols used by relative mouse capture. Match Qt private headers to the
runtime version. Both compositor protocols are required for Relative Aiming on
Wayland; Direct Pointer remains available when they are absent. The existing
Wayland and fallback-X11 display permissions cover those input paths.
