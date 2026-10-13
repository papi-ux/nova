# PyroWave on Android

PyroWave is an experimental codec that reconstructs streamed pictures on the
client's GPU. It appears in **Video Codec** in Nova Beta and debug builds;
stable Nova does not offer it. Use it with a compatible host and a device that
passes Nova's PyroWave check. HEVC or AV1, when supported by both ends, are useful
alternatives when the required bandwidth is too high.

## Choose the source size

The stream resolution and the screen resolution are separate choices. A 720p
stream displayed on a 1080p screen has fewer source pixels to reconstruct, but
scaling cannot recover all of the detail that a 1080p source contains. Fine text
and thin edges are useful places to compare the difference.

At the same frame rate, 1280 × 720 has about 56% fewer source pixels than
1920 × 1080. That does **not** promise a 56% reduction in bandwidth, decoder
time or power use. Those depend on the picture, quality target and device.
Whether a smaller stream lowers the game's render resolution depends on the
host's display mode. When Polaris makes its display the stream's size, many
games render at that size. When it streams an existing display, the game's own
settings decide.

In **Play Setup**, check **Resolution**, **Frame Rate** and **Video Codec**, and
any **Limited by · Bitrate** note. The bitrate itself is **Video bitrate** in
Settings. Start with Nova's recommendation. If the requested quality exceeds
the available budget, compare a smaller source size or a more compressed codec.
Keep your previous choices so you can restore them after the comparison.

## Read bitrate numbers in their units

Nova's recommendation uses the calibrated PyroWave model and compatible host
advice. Its requested bitrate is the total Polaris splits between video,
recovery data (FEC), audio, and a reserve of up to 500 kbps for packet overhead
and control traffic. The video encoder gets what remains. Traffic on the
network is a separate measurement and can differ from the request. An external
**encoder bitrate** is therefore not a number to copy directly into Nova's
bitrate setting.

RubyLight's [representative-picture measurement method](https://github.com/RamazanKara/Rubylight/blob/6756dcde79ddb1c6a568181c14ea8325befd67f6/rust/PERFORMANCE.md#october-7-pyrowave-bitrate-from-representative-pictures)
and [quality guidance](https://github.com/RamazanKara/Rubylight/blob/6756dcde79ddb1c6a568181c14ea8325befd67f6/docs/configuration.md)
give clean-picture recommendations of 277 Mbps at 720p60 and 399 Mbps at
1080p60, supplied directly to the encoder. These come from synthetic pictures
on AMD hardware; their severe-loss floors are lower, at 139 and 187 Mbps. The
delivery tests used 20% critical FEC and 1392-byte packets. Nova's calibrated
handheld and room quality targets and Polaris's rate split differ, so those
numbers are not interchangeable with Nova's requested bitrate.

They are not measured sustainable Wi-Fi rates, Nova defaults, or guarantees for
another host or client. The smaller-source budget illustrates a tradeoff
between source detail and traffic; it does not replace Nova's model or limits.

## Compare what you can see and measure

Use the same game scene, host settings, network, codec, frame rate and output
screen. Compare 1080p and 720p sources, recording the requested bitrate for each.
Inspect text, moving edges, gradients and dark areas, then check audio, controls
and reconnect behavior. A smaller picture can save work while still looking
less detailed; choose the result that works for your device.

Frame counters and CPU call timings do not prove when a picture reaches the
display. Keep unavailable readings unavailable, and pair software readings with
what you observe during the stream. See the
[stream report guide](stream-reports.md) for retaining a comparison locally.
