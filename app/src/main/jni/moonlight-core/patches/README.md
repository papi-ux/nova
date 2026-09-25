# moonlight-common-c patches

What Nova adds to the streaming library so it can negotiate PyroWave, kept here as a patch series
rather than as a fork.

Eight commits, 281 lines across five files, most of it comment: the format numbers, the masks and the
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

From the repository root, on a freshly initialised submodule:

```bash
cd app/src/main/jni/moonlight-core/moonlight-common-c
git checkout -b polaris-pyrowave
git am ../patches/*.patch
```

The build refuses to go further without them when PyroWave is reachable, and says so, so a clone
that skipped this step finds out at build time rather than at stream time.

## When upstream moves

The submodule pin is what these apply to. If it moves, rebase the branch and export the series
again with `git format-patch --no-signature -o ../patches <old-pin>..HEAD`. The diff was kept small
and free of refactoring precisely so that this stays a rebase rather than a merge.
