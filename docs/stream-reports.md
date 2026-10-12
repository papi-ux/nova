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

Android only for now.

After a connection ends, that PC's Library can show a **Last Stream** card. **View** shows how long
the connection lasted, how much of it was measured, per-metric averages with their sample counts,
missing-frame evidence, how old the last sample is and why the connection ended. **Save Report**
saves a report on the device and offers a separate **Share Report**; **Dismiss** removes the card.
Nova keeps one recap in memory, so it is gone if Android stops the app.

A reconnect is not proof that the picture came back, and a connection ending is not proof that the
game quit on the PC.
