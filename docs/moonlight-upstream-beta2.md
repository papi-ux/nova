# Moonlight upstream intake for beta.2

Checked 2026-10-04 against live Nova staging `1c5adb9f8d920bb33a6b3089510a35a4351c55a1`. This is a source comparison. It does not establish a port, installed build or hardware acceptance.

| Source | Release or pin | Our relationship |
| --- | --- | --- |
| [Moonlight PC 6.2.0](https://github.com/moonlight-stream/moonlight-qt/releases/tag/v6.2.0) | Published 2026-10-04 00:44:48 UTC; `de2467e433821664cdd2224aad8c89a625be1ad9` | Compatibility reference and design input. Nova's native Linux client has its own Qt/FFmpeg adapters; it is not a checkout of Moonlight Qt. |
| [Moonlight Android 12.2](https://github.com/moonlight-stream/moonlight-android/releases/tag/v12.2) | Published 2026-09-12 19:28:27 UTC; `b48494cb96bff23d8886c4775cc4f39a1075495d` | Nova's Android lineage; compare behavior against our Kotlin implementations rather than replacing the app. |
| [Nova's ClassicOldSong common-c pin](https://github.com/ClassicOldSong/moonlight-common-c/commit/c999436858471dfefa7617af3b7dc03ec1644ce4) | `c999436858471dfefa7617af3b7dc03ec1644ce4` | Android and native Linux share this pin, plus their recorded transport patches. |
| [PC 6.2.0 common-c pin](https://github.com/moonlight-stream/moonlight-common-c/commit/f900dd4767759c7b9d0e93bcea666b55c69ea62f) | `f900dd4767759c7b9d0e93bcea666b55c69ea62f` | 44 commits reachable here are absent from Nova's pin. Nova has six commits absent here: four merges and the host-command / empty-payload additions. A wholesale pin change needs an explicit compatibility review. |
| [Android 12.2 common-c pin](https://github.com/moonlight-stream/moonlight-common-c/commit/874ac9548f1bd6f095ef2b435c42cdde460e7821) | `874ac9548f1bd6f095ef2b435c42cdde460e7821` | Distinct from PC 6.2.0's pin; do not treat their release numbers as one streaming-library version. |

## Focused decisions

| Upstream change | Nova evidence and next step |
| --- | --- |
| [RTSP response bounds and Session validation](https://github.com/moonlight-stream/moonlight-common-c/commit/7b026e77be62175104640e7e722b758df6d3d0d7) | Missing from the pinned core. [Nova #271](https://gitea.centaur-frog.ts.net/papi-ux/nova/issues/271) owns a focused attributed backport across Android and Linux, with original failure retention and healthy handshake controls. This does not migrate the core pin or timing ABI. |
| PC release security fixes | The release lists CVE-2026-33546, CVE-2026-33547 and CVE-2026-41210. The [published SPS advisory](https://github.com/moonlight-stream/moonlight-qt/security/advisories/GHSA-5rvh-v25g-vrgv) identifies Qt's bundled H.264 fixup parser. Nova's inspected tree does not contain that parser or call `read_nal_unit`; that supports a path-specific non-applicability inference, not a general security clearance. Public technical mappings for the other two CVEs were unavailable at this readback; keep them unresolved until primary details are published. Do not label #271 as their verified fix. |
| [Higher resolution host timestamps](https://github.com/moonlight-stream/moonlight-common-c/commit/e356b2cfde604c35904793aaf485a6ede7102cae) | Upstream changes `DECODE_UNIT.presentationTimeMs` to microseconds and adds raw RTP timestamps. Nova's older core ABI lacks this change. Review field width, units, wrap handling, JNI and Linux consumers under [#241](https://gitea.centaur-frog.ts.net/papi-ux/nova/issues/241) / [#242](https://gitea.centaur-frog.ts.net/papi-ux/nova/issues/242), after Polaris's host cadence gate. |
| PC VRR renderers | 6.2.0 adds NVIDIA D3D11 VRR and rewrites KMSDRM with adaptive refresh support. These client capabilities do not prove variable host supply or authorize importing their renderer into Nova. Keep them as a separate receiver comparison for Polaris #249 and Nova #241. |
| Common-c frame-loss recovery | [Partially lost IDR](https://github.com/moonlight-stream/moonlight-common-c/commit/be438856af5bf5b87cc43715b82eb7b503f9c3a6), [multi-block RFI](https://github.com/moonlight-stream/moonlight-common-c/commit/d85371cd8cb6304e66ef90a02e2e1609a8621906), and [speculative loss reporting](https://github.com/moonlight-stream/moonlight-common-c/commit/62e066388f1a1b133e0bee947b9a374311a3354b) are absent from Nova's pin. Reproduce with recorded codec/RFI conditions before selecting a port; host control loss and client video loss remain different evidence. |
| Android API level | Both inspected apps target API 36. That existing overlap does not establish feature parity. |
| Android keyboard capture / newer keys | 12.2 has API 36.1 keyboard capture and Android F13–F24 handling. Those additions were not found in the corresponding Nova Game / keyboard translator at the comparison SHA. Nova's Linux input adapter already has F13–F24 mapping; assess Android separately. |
| Android RGB controller work | 12.2 queues LED work on its background handler and catches session-close failures. Nova's inspected callback opens and updates light sessions directly. Capture the callback thread, hotplug and shutdown behavior before porting; a physical controller remains a separate acceptance requirement. |
| PC color, identity and decoder changes | Inspect full-range output, supplied client identifiers, FFmpeg compatibility and Linux decoder fixes in the relevant adapters before accepting a change. Pairing identity must remain stable. External Moonlight 6.2.0 compatibility requires a new run with its actual package identity. |

## Evidence and attribution

Every upstream intake records the repository, release publication time, immutable source and dependency pins, original/fixed producer hashes, patch origin, applied local changes, and the affected tests. Link an existing ticket when it already owns the behavior; open a narrow issue only for a demonstrated gap. Keep upstream fixes, Nova changes and physical observations as separate rows in a run receipt.

Retain the first failing trace and healthy controls on identical inputs. A new upstream client version is a changed test input, so do not reuse an older Moonlight/Nova acceptance result. Include upstream authors and license notices when carrying code. Preserve the existing ClassicOldSong host commands and Nova protocol extensions when considering a later core migration.

The public release, advisory and source readbacks, pinned comparison, original patch and fixture results are retained in private beta.2 evidence. Raw support data and credentials are not part of this document.
