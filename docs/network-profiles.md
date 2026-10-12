# Connection readout and per-host profiles

Nova for Android, in Play Setup. Nova Linux does not have these yet.

## How Nova reaches the PC

With a Polaris 1.5.0 beta.2 host or newer, Play Setup shows a **Host connection** fact: whether
Nova's paired connection to the PC runs over Tailscale and, when it does, whether it is direct,
through a DERP relay or through a peer relay. The PC answers from its own Tailscale daemon.

An installed Tailscale app, a VPN, a MagicDNS name or an address in `100.64.0.0/10` never counts as
proof. An older host, a PC without Tailscale, and stale or ambiguous evidence all read as unknown.
The fact describes the paired control connection; video and audio can take a different route, and
it never changes bitrate on its own. Setup can open the installed Tailscale app and its help.

## Per-host profile rules

Rules are off until you turn them on for a PC. Each rule names one of your saved Settings Profiles
and the conditions it applies under:

| Condition | Choices |
| --- | --- |
| Link | Any, Wi-Fi, Ethernet or cellular |
| Metered | Any, yes or no |
| VPN on this device | Any, yes or no |
| Host connection | Any, or confirmed Tailscale |

Nova checks the rules in order and uses the first that matches. Play Setup shows which rule matched,
and you can pick a different profile for one launch. The chosen profile drives the preview, the
preflight and the launch without changing your active profile or the PC's saved settings. If the
network, the rule or the evidence changes after you review the plan, Nova asks you to press Play
again. Spaces, Watch and Resume keep their existing settings.
