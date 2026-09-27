# Nova updates and beta releases

Stable and beta updates are separate choices. A Polaris host's beta preference
does not switch Nova to a beta, and a Nova beta does not update Polaris.

## Android: install a beta

1. Open [Nova releases](https://github.com/papi-ux/nova/releases) and choose a
   published release marked **Pre-release**. The **Latest** link follows stable
   releases and does not opt you into betas.
2. Read that beta's requirements, then download its APK for your device:

   | Device | Beta APK |
   | --- | --- |
   | Most current phones, handhelds and ARM64 Android TV | `Nova-Beta-Android-arm64-v8a.apk` |
   | 32-bit ARM Android devices | `Nova-Beta-Android-armeabi-v7a.apk` |
   | Android x86_64 devices and emulators | `Nova-Beta-Android-x86_64.apk` |

3. Install the APK and open **Nova Beta (Game)** (or **Nova Beta** in other
   variants). This is the beta app. It uses application ID
   `com.papi.nova.pre` and installs beside stable Nova (`com.papi.nova`). Pair your
   host in the beta app; the two apps keep separate settings and pairing data.

**Known beta.2 and beta.3 limitation:** their PyroWave library lacks the native
alignment required by Android devices using 16 KB memory pages and may not load
there. PyroWave still appears in Settings; if the library cannot load, a PyroWave
stream fails during startup. Other codecs are unaffected. This has not been
validated on a 16 KB device and does not establish an APK installation failure.
Use a later beta whose release notes confirm the fix.
See Android's [16 KB compatibility guidance](https://developer.android.com/guide/practices/page-sizes)
for the separate native-library and APK packaging requirements.

Older prereleases may use `Nova-Android-…` filenames. Follow the assets and notes
of the prerelease you selected; do not point the stable updater at an older beta
asset just because its filename looks the same. Public APKs have SHA-256 sidecars
on their release page.

## Android: follow betas with Obtainium

Keep your existing stable Nova entry. Add **Nova Beta** as a separate app:

1. Use `https://github.com/papi-ux/nova` as the source and enable **Include
   prereleases** for this entry.
2. Set the APK filename filter for your architecture, for example
   `Nova-Beta-Android-arm64-v8a\.apk$` for ARM64. Use the beta app ID
   `com.papi.nova.pre` if you need to identify the installed app.
3. Check that Obtainium offers **Nova Beta**, then install or update it. Keep
   stable Nova's filename filter and prerelease setting unchanged.

The [changelog's beta section](../CHANGELOG.md) also provides an ARM64 **Add Nova
Beta to Obtainium** link with those settings. If an older beta used different
asset names, update that beta entry's filename filter before following newer
builds.

## Android: update a beta or return to stable

Install the next signed beta APK for the same architecture over the installed beta app, or
update its separate Obtainium entry. Pairing and preferences remain with that
beta app. Check the full version, including `beta.N` or `rc.N`, after updating.
If Android refuses the package, check the release notes, architecture and
signature; do not uninstall or clear app data as a routine update step.

To return to stable, open the stable **Nova** app and disable or remove the beta
entry from Obtainium. Stable and beta app data do not transfer automatically.
Uninstalling the beta app is optional and removes its own local data. Disabling
prereleases in Obtainium does not convert the installed beta into stable Nova.

## Nova Linux: choose an update channel

Nova Linux runs on Linux desktops, laptops and handhelds, including Steam Deck.
It uses Flatpak rather than Android APKs. The Linux app ID remains
`com.papi_ux.Nova`.

**Channel installations:** when a signed channel installer is published, choose
the **stable**, **beta**, or experimental **pyrowave** installer named in the
release. Open **Settings → Nova → Nova Updates** to check or install an update on
that installed channel. Automatic installation is optional and waits until Nova
is idle. Reopen Nova when it says the update is ready. Selecting a channel is
explicit; the app does not switch channels automatically.

**Downloaded bundles:** an ordinary `.flatpak` bundle may have no update feed.
Download the bundle from the intended release, verify its checksum, and install
that newer bundle with the software manager or `flatpak install --user`.
Current bundles are named `Nova-Linux-x86_64-alpha.flatpak`; older releases used
`Nova-Deck-x86_64-alpha.flatpak`. Use the filename attached to your release.

Existing bundle installations need a one-time channel installation before they
can use an in-app feed. Follow the [Linux update and migration guide](linux-updates.md)
only after the selected channel's installer is published. The updater code and
publication workflow being present do not make a public feed available.

To change back to stable, use the published stable channel installer and launch
that channel explicitly. Switching channels does not mean an older build is a
safe downgrade; follow its release notes. Preserve pairing and preferences by
keeping the app data, and never use `--delete-data` as an update step.

For installation and Steam shortcuts, see the
[Nova Linux install guide](../clients/deck/packaging/flatpak/README.md).

## Test PyroWave

PyroWave needs a compatible client decoder as well as a host encoder. Standard
Moonlight clients cannot use it, and Polaris has no frontend switch that adds
the decoder to those clients.

- **Nova Android beta:** with a compatible Polaris host, open a game's **Play Setup →
  This Game → Video Codec** and select **PyroWave (experimental)**. The choice is saved
  for that game on that PC; **App setting** returns to the codec in Nova Settings.
  Older builds, including v1.4.13-beta.3, do not have this Play Setup row: use
  **Settings → Client Stream Defaults → Change codec settings → PyroWave (experimental)**.
  Auto does not select PyroWave, and stable Android builds do not expose it.
- **Nova Linux:** use a PyroWave-enabled build, made with the separate
  `com.papi_ux.Nova.pyrowave.json` manifest. The ordinary Linux Alpha Flatpak
  attached to **v1.4.13-beta.3** uses the standard manifest and does **not** contain
  PyroWave. Releases using the separate-asset workflow attach
  `Nova-Linux-PyroWave-x86_64-alpha.flatpak` and its checksum beside the standard
  bundle. Use that explicitly named package when present; a beta tag alone does
  not enable the codec. It replaces the standard Linux app and has no automatic
  stable/beta feed. Follow the [experimental install and return instructions](../clients/deck/docs/pyrowave.md#packaging-and-compatibility).

In an enabled Nova Linux build, open a game's **Play Setup → Video Codec →
PyroWave · Experimental**. The host and the Linux device's Vulkan decoder must
both support it. Use the normal Desktop destination; PyroWave is unavailable in
Spaces. Auto does not select PyroWave.

The official Polaris v1.4.13 Linux packages include the host encoder, selected by
the client's request; it is not restricted to the earlier beta.3 host release.
For selection steps and troubleshooting on both clients, see the
[Nova PyroWave guide](../clients/deck/docs/pyrowave.md).
For the first Linux test, use the supported SDR 4:2:0 profile on a fast wired
local connection and confirm **PyroWave** in stream diagnostics. Decoder startup
checks alone do not prove a streamed image, audio, input, HDR, or sustained frame
rate. The [Polaris PyroWave guide](https://github.com/papi-ux/polaris/blob/master/docs/pyrowave.md) describes
host and client requirements.
