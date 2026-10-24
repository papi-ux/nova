# Nova updates on Linux

For player-facing beta opt-in, Android updates and Linux channel selection, see
[Nova updates and beta releases](updates.md). This page also covers preparing
and publishing the signed Linux update feed.

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

The publication workflow defines **stable** and **beta** refs. Stable accepts
stable releases only. Beta includes prereleases and stable releases intentionally
delivered to that ref; publishing a stable release does not update Beta by itself.
Use the bundles attached to the selected release until a signed channel installer
is announced. A channel defined in the workflow is not evidence that its public
feed exists. Starting with 1.5.0, both channels include PyroWave with a lazy
device check; the separate codec channel is retired. Regular downloaded bundles and native
builds show installation guidance instead of claiming that a feed is configured.

## Architecture and candidate scope

The Linux and Steam Deck client is in Beta. Its release bundle retains the
filename `Nova-Linux-x86_64-alpha.flatpak` for compatibility. The separate
[Steam Frame Beta](../clients/deck/docs/steam-frame.md) currently uses an ARM64
bundle and manual updates, with its own qualification record. The feed workflow
can stage `aarch64/beta` on a native ARM runner; `aarch64/stable` is refused until
separately qualified. A stable source tag on the Beta ref keeps Frame at Beta
maturity. An available ARM container image and ARM-aware tests do not qualify a
build, runner, public feed or channel installer.
Do not install an x86_64 bundle as a Frame update.

For the 1.5.0 candidate, the compiled feed address is
`https://updates.papi-ux.com/nova`, and the app retains
Retry after a failed check. An ordinary private or downloaded bundle can still
show installation guidance because no signed channel was configured for it.
Preparing source or a private rehearsal does not publish a feed.

## First publication

The implementation and workflow do not make the feed live by themselves. Before
the first public channel is available:

1. Merge the updater and publish a reviewed release containing it, version 1.5.0
   or later. Feed preparation requires the standard package with PyroWave, so
   1.4.13 is not eligible for the first feed publication. Use the exact published
   tag: its stable/beta/RC suffix and GitHub prerelease flag must agree. Before
   tagging, include the [Linux update channels](https://updates.papi-ux.com/nova/) link in that
   release's checked-in notes so **Open Nova Downloads** leads users to the
   installer. Publish the feed before announcing the channel's availability.
2. Enable GitHub Pages for this repository with **GitHub Actions** as its source.
   Configure its custom domain `updates.papi-ux.com` and HTTPS before activation.
   The artifact is rooted at `site/nova/`; the fixed root index links to the
   existing product page `https://papi-ux.com/nova/`, which is not replaced.
   Verify that the final feed URL serves directly without redirects: the app
   does not follow catalog redirects. DNS/Pages changes are owner activation,
   not an effect of preparing this source.
3. Keep a durable backup of a dedicated Flatpak signing key. Store its base64
   encoded private export under the workflow's existing secret name
   `FLATPAK_GPG_PRIVATE_KEY`.
   The workflow expects one primary signing key usable noninteractively. Never
   commit private signing material. Keep this key across releases; changing it
   is a separate trust migration. The owner must publish the public fingerprint
   here before announcing availability; no production fingerprint is recorded
   yet. Descriptors embed the public key. A production signing environment or
   reviewer is an owner configuration choice at activation; this source adds
   no manual approval gate or environment prerequisite to private rehearsal.
4. Run **Stage or publish Linux updates** from master with the published tag,
   the intended channel and architecture, `bootstrap=true`, and `publish=false`.
   Supported builds are `x86_64/stable`, `x86_64/beta` and `aarch64/beta`; the job
   checks that its native runner matches the selected architecture. Inspect the
   staged site and bundle. The job verifies the generated repository's summary
   and commit signatures using a separate repository.
5. Run with `publish=true` when ready to publish. Subsequent updates require
   `bootstrap=false`; the workflow pulls the existing signature-verified feed
   and preserves every other supported architecture/channel ref and catalog.
   A network error, missing/stale catalog or bad signature aborts
   publication. Bootstrap is accepted only when no existing summary is present.

The workflow checks out the release's resolved commit, builds the app with its
channel, signs it, creates `.flatpakref` installers and a `.flatpakrepo`, and
stages the complete site as a Pages artifact. It also retains a bundle that
includes the repository URL and public key. Publication is an explicit workflow
input, off by default and separate from ordinary PR builds and Android releases.
The channel installer is also retained as a review artifact. Public installers
are served by Pages; existing release assets retain their eight-file contract.
This workflow does not create or publish a release tag or change release notes.

### Deliver a stable release to Beta subscribers

Choose the published stable tag (for example `v1.5.0`) and dispatch separately
for each intended target ref: `x86_64/stable`, `x86_64/beta`, and, after ARM
qualification, `aarch64/beta`. Each dispatch resolves and builds that exact tag's
commit. A Beta-target build from stable source reports `1.5.0`, without an
invented `-beta.N` suffix, and still installs/updates branch `beta`. The release
flag remains stable. A prerelease cannot enter the Stable ref, and inconsistent
tag/flag metadata is refused on either ref.

There is no automatic promotion, branch switch or old-beta seed. Select the new
tag and target deliberately, review a `publish=false` artifact first, then use
the separately authorized publication step. Publishing Stable alone leaves Beta
subscribers on their current Beta ref until that ref receives the intended
release. Frame stays Beta even when its Beta ref carries stable 1.5.0 source.

## Existing bundle installations

Existing standalone `.flatpak` downloads need a one-time channel installation.
Once the feed is published, open its `nova-beta.flatpakref` (or the chosen channel)
in the software manager from Desktop Mode. For example:

```sh
flatpak install --user https://updates.papi-ux.com/nova/nova-beta.flatpakref
flatpak run --user --arch=x86_64 --branch=beta app/com.papi_ux.Nova/x86_64/beta --standalone
```

Use the new channel once before reopening the existing Steam shortcut. Nova
keeps the same app ID and data directory, preserving pairing and preferences.
Do not uninstall with `--delete-data`. User and system installations can coexist;
verify the intended channel with `flatpak info --user --arch=x86_64 com.papi_ux.Nova//beta` rather
than deleting the older installation as part of an update. The app's Updates
screen shows its channel.
These examples are for Linux/Deck x86_64; use the separate
[Frame migration](../clients/deck/docs/steam-frame.md#signed-beta-channel-migration)
for its ARM Beta and private identity paths. Installing another branch can
change Flatpak's default branch, so channel launchers select the full ref
explicitly. This source changes neither existing remotes nor application data.
The optional guided installer/Add to Steam companion is separate; signed
`.flatpakref` installers open in the system software manager without PyGObject.
Existing pinned v1/v2 installer deliveries are not changed by this feed source.

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
tests cover release admission, the standard package codec requirement, signed installer
metadata and preservation of other channel catalogs.
The optional signing smoke needs Linux with Flatpak, OSTree and GnuPG. It
creates an ephemeral test key and repositories, exports a no-op fixture,
verifies signed metadata, and rejects untrusted keys and unsigned commits.
It never installs or launches the fixture.

The portal API is documented in Flatpak's
[interface definition](https://github.com/flatpak/flatpak/blob/main/data/org.freedesktop.portal.Flatpak.xml).
