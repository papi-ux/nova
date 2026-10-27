# Stream reports and baselines

Nova can keep evidence about a stream on the device that played it. Nothing on this page sends
anything to the PC or anywhere else by itself.

## Report This Moment

Android and Linux, from Command Center during a stream.

- It freezes the client's current readings. Requested settings stay separate from what the decoder
  observed, video frame loss over the sample window stays separate from control-channel round trip
  and retries, and a missing, stale or invalid reading stays unknown.
- **Android** adds a short redacted log tail, at most 64 KiB, with command tokens, addresses and
  host identities removed. The confirmation focuses **Keep Playing**. Only **Share Report** opens
  Android's share sheet, which can pause playback.
- **Linux** freezes the readings plus up to ten sampled readings from the last ten seconds, with no
  logs. **Save** writes the report to Documents, Nova reports; **Keep playing** or Cancel discards
  it. Reports are at most 32 KiB, Nova keeps at most 32, and it never deletes one of yours.

## Working baseline

Android and Linux, from Command Center during a stream.

- **Save** marks a stream you know is good. Nova first asks you to confirm that picture, audio and
  controls work, then freezes fresh readings at that press. **Replace** does the same over an
  existing baseline.
- **Compare** sets a later stream on the same PC and game beside the baseline: the readings, when
  each was taken, what differs and what is unknown. It gives no verdict and changes no setting.
- **Clear** removes the baseline for that PC and game.
- Baselines stay on the device, grouped by PC and game without storing raw identities. Android keeps
  up to 12, each under 2 KiB, outside Android backups.
- The bitrate recorded for a stream is what the launch requested after metered-network limits, host
  ceilings and AutoSafe. It is not the bitrate the host applied, and not a later Live Tuning change.

## Last Stream

Android and Linux, after a stream ends. The two differ.

- **Android**: after a connection ends, that PC's Library can show a **Last Stream** card. **View**
  shows how long the connection lasted, how much of it was measured, per-metric averages with their
  sample counts, missing-frame evidence, how old the last sample is and why the connection ended.
  **Save Report** saves a report on the device and offers a separate **Share Report**; **Dismiss**
  removes the card. Nova keeps one recap in memory, so it is gone if Android stops the app.
- **Linux**: only after you choose **End Session** or **Disconnect**, the ended stream's screen
  shows a **Last stream** card for that PC and game. It never appears during play, and a stream the
  PC ended, a failure, an interruption, sleep or a cancelled launch shows none. Showing it moves no
  controller focus; Up from the button below reaches **Dismiss**. It is held in memory only, with no
  Save or export, and starting the next stream clears it. It shows:
  - how long the stream was connected at least, up to Nova's last one-second reading;
  - frames received and decoded per second, and the received video payload rate, over the span
    between Nova's first and last readings of that stream. These are not frames drawn on screen, the
    encoder's bitrate or total network traffic. Fewer than two readings, a gap over 2.5 seconds, a
    decoder or frame-sequence restart, or a last reading already stale when you stopped leaves them
    Unknown;
  - missing video frames from the frame-sequence count, and average PC processing time (reported in
    the video) and decoder submit time, each with the frames it covers. These count from when the
    video decoder started, and the card says so when a restart cut that short. A reading Nova
    doesn't have says Unknown, never zero.

A reconnect is not proof that the picture came back, and a connection ending is not proof that the
game quit on the PC.
