# Nova updates on Linux

The Linux app has **Settings → Nova → Nova Updates**. Channel builds can check
for a new version, install it, and show when Nova needs reopening. Automatic
installation is off initially. When enabled, it waits for 15 seconds without a
stream or host operation. New streams wait until installation finishes. Nova
never closes automatically; **Close Nova to Finish** is an explicit action.

Updates use the installed Flatpak's remote and branch. Flatpak verifies packages
with that remote's configured trust settings. Nova's small HTTPS catalog provides
the immediate version check; it cannot select an executable, remote, signing key,
or installation command. The app handles offline checks, portal loss and update
errors without reporting success. An update that requires extra sandbox
permissions must be completed in the system software manager.

The channels are **stable**, **beta**, and **pyrowave**. PyroWave-enabled packages
stay on their own experimental channel. There is no automatic channel switch.
Regular downloaded bundles and native builds show installation guidance instead
of claiming that an update feed is configured.

## First publication

The implementation and workflow do not make the feed live by themselves. Before
the first public channel is available:

1. Merge the updater and publish a reviewed release containing it. Use an
   explicitly marked prerelease for beta or PyroWave. Before tagging, include
   the [Linux update channels](https://papi-ux.github.io/nova/) link in that
   release's checked-in notes so **Open Nova Downloads** leads users to the
   installer. Publish the feed before announcing the channel's availability.
2. Enable GitHub Pages for this repository with **GitHub Actions** as its source.
   The workflow targets `https://papi-ux.github.io/nova`.
3. Keep a durable backup of a dedicated Flatpak signing key. Store its base64
   encoded private export in the repository secret `FLATPAK_GPG_PRIVATE_KEY`.
   The workflow expects one primary signing key usable noninteractively. Never
   commit private signing material. Keep this key across releases; changing it
   is a separate trust migration.
4. Run **Stage or publish Linux updates** from master with the published tag,
   the intended channel, `bootstrap=true`, and `publish=false`. Inspect the
   staged site and bundle. The job verifies the generated repository's summary
   and commit signatures using a separate repository.
5. Run with `publish=true` when ready to publish. Subsequent updates require
   `bootstrap=false`; the workflow pulls the existing signature-verified feed
   and preserves the other channels. A network error or bad signature aborts
   publication. Bootstrap is accepted only when no existing summary is present.

The workflow checks out the release's resolved commit, builds the app with its
channel, signs it, creates `.flatpakref` installers and a `.flatpakrepo`, and
stages the complete site as a Pages artifact. It also retains a bundle that
includes the repository URL and public key. Publication is an explicit workflow
input, separate from ordinary PR builds and Android releases.
The channel installer is also retained as a review artifact. Public installers
are served by Pages; existing release assets retain their eight-file contract.
This workflow does not create or publish a release tag or change release notes.

## Existing bundle installations

Existing standalone `.flatpak` downloads need a one-time channel installation.
Once the feed is published, open its `nova-beta.flatpakref` (or the chosen channel)
in the software manager from Desktop Mode. For example:

```sh
flatpak install --user https://papi-ux.github.io/nova/nova-beta.flatpakref
flatpak run com.papi_ux.Nova//beta --standalone
```

Use the new channel once before reopening the existing Steam shortcut. Nova
keeps the same app ID and data directory, preserving pairing and preferences.
Do not uninstall with `--delete-data`. User and system installations can coexist;
verify the intended channel with `flatpak info com.papi_ux.Nova//beta` rather
than deleting the older installation as part of an update. The app's Updates
screen shows its channel.

The feed's package upgrade, signature rejection, channel preservation and
existing user/system installation migration should be qualified before calling
the first channel generally available. Controller navigation and idle gating
can be tested headlessly with the private portal fixture; that is separate from
physical Deck acceptance.

## Development validation

```sh
python3 -m unittest tools.test_nova_flatpak_feed
NOVA_FLATPAK_SIGNED_TEST=1 python3 -m unittest tools.test_nova_flatpak_signing
ctest --test-dir build-deck -R 'nova_deck_(updates_test|settings_hub_qml_test)$' --output-on-failure
```

The update test starts a private D-Bus service through `dbus-run-session`; it
never calls the real system updater or installs an application. Feed preparation
tests cover release admission, codec/channel separation, signed installer
metadata and preservation of other channel catalogs.
The optional signing smoke needs Linux with Flatpak, OSTree and GnuPG. It
creates an ephemeral test key and repositories, exports a no-op fixture,
verifies signed metadata, and rejects untrusted keys and unsigned commits.
It never installs or launches the fixture.

The portal API is documented in Flatpak's
[interface definition](https://github.com/flatpak/flatpak/blob/main/data/org.freedesktop.portal.Flatpak.xml).
