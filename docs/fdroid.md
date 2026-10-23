# F-Droid and IzzyOnDroid Packaging Notes

Nova's Android APK can be distributed through app repositories, but a working
APK does not establish repository eligibility. This guide records the work to
prepare a possible IzzyOnDroid re-review **after stable 1.5.0 is released**.
Nova is not listed in IzzyOnDroid or F-Droid main.

Last policy and source review: **2026-10-23**. The source observations below
refer to `df41dabb548170e9f9cc7080e2fcf0c481e56c3b`; repeat the affected checks
on the final release inputs. No stable 1.5.0 APK has been qualified by this guide.

## Eligibility comes first

Read the current [IzzyOnDroid inclusion policy](https://izzyondroid.org/docs/general/AppInclusionPolicy/)
before requesting another review. Two unresolved policy barriers apply:

- **Gaming focus.** Nova is a game-streaming client. Not bundling games does
  not remove the policy's restriction on gaming-focused apps.
- **Development provenance.** The policy rejects vibe-coded apps and says app
  code should be free of generative-AI output. Nova includes Codex/Claude-authored
  implementation changes. Human review and tests do not change that provenance.
  Documentation and read-only research are treated differently by the policy.

The [original review](https://codeberg.org/IzzyOnDroid/repodata/issues/159#issuecomment-13759757)
declined Nova over category/space limits. Its scan asked for explanations of
CAMERA and AccessibilityService. In the [follow-up](https://codeberg.org/IzzyOnDroid/repodata/issues/159#issuecomment-13952963),
the maintainer accepted the earlier AI disclosure on the understanding that it
covered documentation, testing and triage without touching app code. That
description must not be reused for the current codebase.

An honest eligibility inquiry is needed before presenting a new submission as
compliant. Neither this preparation nor an APK scan promises acceptance. Do not
add an IzzyOnDroid badge until Nova is actually listed.

## Which distribution path?

| Path | What remains |
| --- | --- |
| IzzyOnDroid | Maintainer eligibility decision, then review of the exact public Android release APK and metadata. |
| F-Droid main | Separate source-build recipe and dependency/license review; the checked-in native binaries are unresolved. Izzy preparation does not qualify this path. |
| Self-hosted repository | A possible separate distribution project, requiring its own index signing, maintenance and user setup. None is provided here. |

Nova also has native Linux/Steam Deck and Steam Frame builds. An Izzy request
concerns the **Android APK**; it is not a request to distribute the Linux packages.

## Android release identity

The current Gradle pins are `versionName = 1.5.0`, stable `versionCode = 55`,
and application ID `com.papi.nova` for `nonRoot_gameRelease`. Read the actual
final APK rather than relying on these source values.

The release workflow names per-ABI stable assets
`Nova-Android-arm64-v8a.apk`, `Nova-Android-armeabi-v7a.apk`, and
`Nova-Android-x86_64.apk`. Agree the repository's preferred ABI and size budget
with its maintainer; supplying every ABI is not an inclusion requirement.
Use the matching tagged release URL, not an unqualified `releases/latest` link.

Debug (`com.papi.nova.debug`), Beta (`com.papi.nova.pre`), dirty, benchmark,
root and unsigned outputs are not substitutes for the stable submission APK.
Keep the established release signing identity; a valid debug signature is not a
release signature. The two channels have different application IDs, so the
Beta channel's larger derived versionCode is not the stable upgrade sequence.

## Permissions and network behavior

These explanations accompany an actual merged-manifest review; they are not
waivers for unexpected permissions in the final APK.

- **Camera:** QR host pairing. Camera hardware is optional and manual host
  setup remains available. Denying camera access must leave ordinary pairing
  and streaming usable.
- **AccessibilityService:** an optional physical-keyboard passthrough helper
  while a stream is connected. The user enables it in Android settings; it is
  non-exported and protected by `BIND_ACCESSIBILITY_SERVICE`. Its XML declares
  key filtering and window-content access; the current implementation forwards
  key events to the connected game and ignores accessibility-event callbacks.
  Explain the declared capabilities as well as the intended use.
- **Install packages:** the built-in updater can hand a downloaded APK to
  Android's installer after the user asks. Android's unknown-source permission
  does not itself explain bypassing a repository's update review.
- **Bluetooth:** controller connectivity; permission availability varies by
  Android version. No controller is required for installing Nova.
- **Network:** host discovery, pairing, streaming, artwork and requested online
  services such as update checks. `network_security_config.xml` currently permits
  cleartext in its **base configuration**, not only for selected LAN domains.
  Moonlight-compatible initial host communication requires HTTP; do not describe
  the current configuration as a LAN-enforced allowlist or as all-encrypted.

Also review the final exported activities/providers/services and dependency
manifest additions. In particular, the exported poster provider serves launcher
and TV artwork; its input validation belongs in the security review.

## Updater consent and the F-Droid switch

The inclusion policy requires explicit opt-in for executable downloads and a
clear explanation when using an updater bypasses repository checks. At the
reviewed source, automatic checks fetch release information, while APK download
starts from a user action. The dialogs explain Android install permission but
**do not yet explain bypassing repository checks**. This remains a submission
gap; declining must be straightforward too.

For source-build experiments, Nova supports:

```sh
./gradlew -PnovaFdroid=true -PnovaAbis=arm64-v8a assembleNonRoot_gameRelease
```

This sets `BuildConfig.FDROID_BUILD=true`, hides the Releases/Obtainium settings
entries and suppresses the automatic update prompt/check. At the reviewed source
the manual dashboard update action is still wired. The switch does not remove
the install permission or updater implementation, change signing/application ID,
or certify a repository-only update channel. Audit every entry point before
claiming that in-app updates are disabled. Building this variant is not required
merely to request Izzy inclusion.

## Metadata preparation

Store metadata lives in `fastlane/metadata/android/en-US/`:

- Keep Nova's own title, icon, descriptions and screenshots, with Moonlight and
  other upstream credit. Explain what the Android app actually does today.
- Refresh screenshots from a reviewed release build; check for private host
  addresses, names, notifications and pairing data before committing them.
- Match `changelogs/<actual-versionCode>.txt` to the submitted APK. The reviewed
  tree ends at `54.txt`, while the stable pin is 55: the final 1.5.0 entry is
  still needed after its release scope is settled.
- Explain optional camera and accessibility use in store-facing text, and link
  the detailed permission/update explanations here.
- Check description limits, image dimensions, links, license notices and release
  availability on the public default branch. Private preview metadata is not
  public delivery.

## Exact-APK release checklist

Use one receipt for each final candidate; repeat only checks whose inputs changed.
Keep unresolved findings visible rather than claiming a universally clean scan.

- [ ] Record the public tag/commit, build workflow and toolchain, selected ABI,
  actual APK byte count/SHA-256 and matching checksum asset.
- [ ] Verify `apksigner verify --verbose --print-certs`; compare the public
  release certificate with the previous stable APK. Check upgrade versionCode,
  `com.papi.nova`, versionName, and absence of `debuggable`/`testOnly` in the
  **merged APK manifest**. Preserve existing signing keys.
- [ ] Inventory merged permissions/components, included SDKs and native
  libraries. Review tracker/security/license findings locally; do not infer
  absence from a source-name search. Record scanner versions and limitations.
- [ ] Confirm upstream/native provenance and packaged notices, including the
  PyroWave library and SGSR/Steam Controller notices when actually included.
  Apply dependency/vulnerability findings to the versions shipped, not just a
  current upstream branch. No APK upload to an external scanner is implied.
- [ ] Check ZIP integrity/alignment, native ELF 16 KB page alignment, and actual
  release size against the maintainer's budget. Debug APK size is not release
  size. Existing runtime acceptance carries only for unchanged affected inputs.
- [ ] Close the updater-consent gap and qualify the chosen variant's real update
  entry points, including Cancel/decline behavior.
- [ ] Finish Fastlane's release changelog and screenshots, then read back the
  published assets and exact tagged-source/download links after release.
- [ ] Obtain the eligibility decision above and prepare an accurate disclosure
  plus the exact asset/update-match details for the maintainer. Papi sends the
  request after release; this checklist does not submit anything.

## Separate F-Droid main work

The native build imports checked-in Opus/OpenSSL static archives under
`app/src/main/jni/moonlight-core/{libopus,openssl}/` and PyroWave shared libraries
under `app/src/main/jni/pyrowave-core/<abi>/`. The [PyroWave notes](../app/src/main/jni/pyrowave-core/README.md)
record its upstream revision and build procedure. A release that hides an
experimental picker may still package its native libraries: inspect actual bytes.

Before proposing a F-Droid main recipe, replace the prebuilt dependency path
with pinned source builds, inventory transitive sources/licenses and generated
shader provenance, and demonstrate the full clean build plus affected runtime
checks. Review the current F-Droid submission requirements separately. The old
1.0.3 example recipe was incomplete and is intentionally removed rather than
presented as a working 1.5.0 recipe.
