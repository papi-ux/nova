# moonlight-common-c patches

Nova's protocol additions and focused upstream hardening, kept as an attributed patch series
rather than a change to the pinned streaming-library fork.

The first eight commits add 281 lines across five files, most of it comment: the format numbers, the masks and the
profile tokens in `Limelight.h`, the negotiation arm in `RtspConnection.c`, the SDP matcher in its own
`PyroWaveSdp.h`, one attribute in `SdpGenerator.c`, and two words in `VideoDepacketizer.c`. The
library never sees a PyroWave byte; it carries the payload opaquely and hands it to the renderer.

The matcher has a header of its own because it is the one protocol decision this client makes by
reading text, and `tools/test_pyrowave_sdp.py` compiles and runs it against the malformed and
adversarial payloads an RTSP connection would never let a test reach.

## Why a patch series and not a fork

A fork would be the tidier answer, and it is still available: these apply cleanly on the pinned
submodule commit and would make one in a minute. It is deliberately not the answer yet, because the
codec is behind a debug-only picker entry and a Polaris build flag, and a public repository is a
commitment that outlives changing your mind.

What this does buy, which a branch sitting in a submodule checkout does not, is durability. These
commits lived on one disk with no remote for a while; a `git submodule update` or a fresh clone
would have taken them with it. Here they are in Nova's own history, backed up wherever Nova is.

## Applying them

From the repository root, on a freshly initialised submodule or an earlier patched checkout:

```bash
bash tools/apply-native-patches.sh
```

The script compares stable commit patch IDs, so an earlier eight-patch checkout receives the
new patches without replaying its existing commits. It preserves developer changes and lets
Git refuse an incompatible apply. Android preflight requires the protocol and RTSP guards.

## RTSP hardening

Patch 0009 backports [upstream 7b026e7](https://github.com/moonlight-stream/moonlight-common-c/commit/7b026e77be62175104640e7e722b758df6d3d0d7)
by foxirain, retaining the author and cherry-pick origin. Only older comment context and whitespace
were adjusted. It caps application RTSP response buffers at 1 MiB and rejects an absent token before
duplicating a Session header. TCP refuses an exactly full buffer before extending it; ENet accepts
the exact cap and checks the combined header/payload length.

Patch 0010 frees parsed replies on all three Session-error paths and maps `EMSGSIZE` to the Windows
socket error. Android carries both in its normal native patch bootstrap. Native Linux prepares a
build-owned copy with these two patches for every codec setting, verifying exact reverse
applicability when the source already carries them. The original submodule remains unchanged.
The optional Linux PyroWave patch is applied afterward to its existing separate build copy.

`tools/test_moonlight_rtsp.py` compiles the actual selected core with ASan/UBSan when run standalone.
The Linux CTest uses a harness compiled with its effective core. TCP responses come from a bounded
loopback server; ENet delivery and audio-port notification are explicit fixtures. Fourteen runtime
regressions cover valid and parameterized handshakes, missing/empty/delimiter-only Session headers,
small and boundary-size TCP/ENet replies, both ENet packets' destruction, and maximum application
allocation. Five additional fixtures cover fresh/repeated application, upgrade from the previous
eight commits, preservation of conflicting developer edits, Linux source preparation and refusal
of a partial hardening patch. These tests do not establish device-visible behavior or real ENet
wire delivery.

See [the beta.2 upstream intake](../../../../../../docs/moonlight-upstream-beta2.md) for other changes
that require separate review. This backport does not move the core pin or timing ABI and does not
claim an unverified mapping to the CVEs listed in the PC release.

## When upstream moves

The submodule pin is what these apply to. If it moves, rebase the branch and export the series
again with `git format-patch --no-signature -o ../patches <old-pin>..HEAD`. The diff was kept small
and free of refactoring precisely so that this stays a rebase rather than a merge.
