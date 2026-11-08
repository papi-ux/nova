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
2. Use the dedicated publisher repository **`papi-ux/nova-updates`**, branch
   **`main`**. Copy the reviewed `.github/workflows/flatpak-feed.yml` there and
   enable GitHub Pages for **that publisher repository** with **GitHub Actions**
   as its source. Keep Pages disabled on `papi-ux/nova`: GitHub reserves the
   repository-name route `/nova/` even with a custom domain, which redirects
   the existing product page. The separate publisher avoids that route.
   Configure DNS, the publisher's custom domain `updates.papi-ux.com`, and direct HTTPS
   **before any rooted-feed workflow run**, including a `publish=false`,
   `bootstrap=true` rehearsal. DNS/TLS/transport failures remain fatal; they are
   never treated as the HTTP 404 that permits a first-feed bootstrap.
   The artifact remains rooted at `site/nova/`; the fixed root index links to
   the product page `https://papi-ux.com/nova/` on the separate product site.
   Verify that the final feed URL serves directly without redirects: the app
   does not follow catalog redirects. DNS/Pages changes are owner activation,
   not an effect of preparing this source.
3. Keep a durable backup of a dedicated Flatpak signing key. Store its base64
   encoded private export under the workflow's existing secret name
   `FLATPAK_GPG_PRIVATE_KEY` in **`papi-ux/nova-updates`**, not in Nova or the
   product-site repository. An existing package-signing key is a feed identity
   only after the owner selects it and its private availability and backup are
   verified; finding its public key alone does not establish either.
   The workflow expects one primary signing key usable noninteractively. Never
   commit private signing material. Keep this key across releases; changing it
   is a separate trust migration. The owner must publish the public fingerprint
   here before announcing availability; no production fingerprint is recorded
   yet. Descriptors embed the public key. A production signing environment or
   reviewer is an owner configuration choice at activation; this source adds
   no manual approval gate or environment prerequisite to private rehearsal.
4. Run **Stage or publish Linux updates** in **`papi-ux/nova-updates` from main**
   with the published **Nova** tag, the intended channel and architecture,
   `bootstrap=true`, and `publish=false`. Do not dispatch the old Nova-hosted
   workflow. The publisher's own `GITHUB_TOKEN` reads public Nova source/releases
   and deploys its own Pages artifact; no cross-repository PAT is needed.
   Supported builds are `x86_64/stable`, `x86_64/beta` and `aarch64/beta`; the job
   checks that its native runner matches the selected architecture. Inspect the
   staged site and bundle. The job verifies the generated repository's summary
   and commit signatures using a separate repository.
5. Run with `publish=true` when ready to publish. Subsequent updates require
   `bootstrap=false`; the workflow pulls the existing signature-verified feed
   and preserves every other supported architecture/channel ref and catalog.
   A network error, missing/stale catalog or bad signature aborts
   publication. Bootstrap is accepted only when no existing summary is present.

All source and release requests explicitly target **`papi-ux/nova`**, while
workflow runs, artifacts, secrets and Pages belong to **`papi-ux/nova-updates`**.
The source job checks out Nova master with full history and requires the
qualified source baseline `c9b0104f7fc17cb9ec06577d354d105ff1a386e6` in both
its automation history and the selected release commit's history. The old
public automation is refused before its helpers run. The build then pins its
Nova automation checkout to that exact admitted commit, independently of the
release checkout, so a later master update cannot replace the admitted helper.
This does not promote source or qualify a public release by itself.

The workflow checks out the Nova release's resolved commit, builds the app with
its channel, signs it, creates `.flatpakref` installers and a `.flatpakrepo`, and
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

Each selected architecture/channel must advance to a strictly newer canonical
release version before catalog, descriptor or page outputs are written. Numeric
major/minor/patch and suffix numbers are compared, with beta before RC before
stable for the same release number. Equal-version rebuilds and older versions
are refused, with no rollback override. A genuinely new selected ref without a
prior catalog remains eligible for the existing bootstrap flow; other refs have
independent floors and retain their existing validation/preservation.
The previous selected catalog is validated against its own prior commit, already
bound to the signature-verified mirror by the preservation step. Its commit is
not compared with the intentionally different newly exported selected commit.

## Existing bundle installations

Existing standalone `.flatpak` downloads need a one-time channel installation.
Guided-installer x86 shortcuts pin `--branch=master` and must wait for a versioned
guided channel migration that preserves the same Steam identity, artwork,
pairing and settings. Do not hand-edit a managed launcher or create a duplicate
Steam entry. Until that migration is qualified, continue using the existing
bundle/manual update route.

Once the feed is published, open its `nova-beta.flatpakref` (or the chosen channel)
in the software manager from Desktop Mode for an independent manual installation.
For example:

```sh
flatpak install --user https://updates.papi-ux.com/nova/nova-beta.flatpakref
flatpak run --user --arch=x86_64 --branch=beta app/com.papi_ux.Nova/x86_64/beta --standalone
```

Launch the selected channel explicitly; installing it does not migrate an
existing Steam shortcut. Nova keeps the same app ID and data directory.
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
