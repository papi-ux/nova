# Source-time pacing acknowledgement

Nova's raw source-clock mapping and bounded playout design draws on Nonary's VRR18
controller at `1ad5848bd2190ab9dd9eea00d4730740d530ec29`:
[original controller](https://github.com/Nonary/moonlight-qt/blob/1ad5848bd2190ab9dd9eea00d4730740d530ec29/app/streaming/video/ffmpeg-renderers/pacer/vrr/vrrtimingcontroller.cpp).
The Nova implementation is a smaller state machine, with no cadence smoothing and
with its own shared replay fixtures. The original GPLv3 license is retained in
`licenses/Nonary-moonlight-qt-LICENSE.txt`; preserve existing author notices if
additional helpers are ported. Thanks to [Nonary moonlight-qt](https://github.com/Nonary/moonlight-qt)
and [Vibeshine](https://github.com/Nonary/Vibeshine) for the design and protocol work.
Public release wording remains subject to papi's approval.
