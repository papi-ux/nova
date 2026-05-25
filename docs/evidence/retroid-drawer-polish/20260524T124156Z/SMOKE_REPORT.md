# Retroid live Command Center + NovaHUD tactile smoke

Task: `t_890f011a`
Date: 2026-05-24 UTC
Target: Retroid Pocket 6, serial `24c12bdd`
Package: `com.papi.nova.debug`
Scope: Retroid only. Pixel and Shield appeared in `adb devices -l` but were not targeted.

## Build/install proof

- Remote repo: `/home/papi/Documents/github/nova`
- Branch: `nova/next-level-ui-polish`
- Build command: `./gradlew -PnovaAbis=arm64-v8a :app:assembleNonRoot_gameDebug --no-daemon --console=plain`
- Build result: `BUILD SUCCESSFUL in 8s`
- APK: `app/build/outputs/apk/nonRoot_game/debug/app-nonRoot_game-arm64-v8a-debug.apk`
- APK SHA-256: `4c2b214b47fa706ebcd1643797cd8311d2dbcfd61890bff7112b26b6e4e07fcb`
- Install command: `adb -s 24c12bdd install -r -d app/build/outputs/apk/nonRoot_game/debug/app-nonRoot_game-arm64-v8a-debug.apk`
- Install result: `Success`
- Device package proof: `versionName=1.1.0`, `versionCode=26`, `primaryCpuAbi=arm64-v8a`, `lastUpdateTime=2026-05-24 08:42:07`

Primary evidence:
- `build.log`
- `install.log`
- `device_proof.txt`
- `package_proof.txt`
- `00_smoke_environment.txt`

## Stream used

- Populated Nova library launched on Retroid from paired `pc-papi.lan` / Polaris host: `19 shown`, `Polaris ready`.
- Stream path used: `Steam Big Picture` / `Headless Stream` from the populated library.
- First stream paint showed the expected locked-host surface: `Server is locked` / `TAP TO UNLOCK`.
- After unlocking, the stream rendered the Steam Big Picture library/game carousel and stayed interactive through the smoke.
- Clean disconnect returned to the populated Nova library shell.

Primary evidence:
- `01_library_launch.png/xml/txt`
- `02_after_game_tap.png/xml/txt`
- `03_stream_first_paint.png/xml/txt`
- `04_stream_after_unlock_tap.png/xml/txt`
- `21_disconnect_menu_before_tap.png/xml/txt`
- `22_after_disconnect_return.png/xml/txt`

## Command Center flow

Result: PASS with one shortcut caveat.

Verified:
- Command Center opened in-stream using the Retroid/controller chord path (`Start + Select` synthetic chord in ADB), with `Disconnect`, `End`, quick keys, AI Auto Quality, and overlay/control sections readable.
- Outside tap dismissed the drawer back to the stream surface.
- Reopen after outside-tap worked.
- Horizontal/touch swipe dismissal closed the drawer back to the stream surface.
- Reopen after swipe dismissal worked.
- Vertical scroll inside the Command Center did not accidentally dismiss the drawer; after scroll, the drawer remained open and exposed lower sections including `Nova HUD`, `Stats Overlay`, `Mouse`, `Touch Controls`, and `Keyboard`.
- Disconnect action from Command Center returned to the Nova library.

Caveat:
- A lone synthetic `KEYCODE_MENU` did not visibly open Command Center in this run (`05_command_center_menu_key` captured plain stream). The Start+Select path did open it. If the physical Retroid menu button is intended to be a single-button in-stream shortcut, that deserves a focused follow-up on hardware input mapping; it did not block this smoke because the expected chord/overlay path worked.

Primary evidence:
- `05_command_center_menu_key.png/xml/txt`
- `06_command_center_start_select.png/xml/txt`
- `07_outside_tap_dismiss.png/xml/txt`
- `08_reopened_after_outside_tap.png/xml/txt`
- `09_swipe_drag_dismiss.png/xml/txt`
- `10_reopened_before_scroll.png/xml/txt`
- `11_vertical_scroll_inside_drawer.png/xml/txt`
- `21_disconnect_menu_before_tap.png/xml/txt`
- `22_after_disconnect_return.png/xml/txt`

## NovaHUD flow

Result: PASS.

Verified:
- NovaHUD toggled on from Command Center (`Nova HUD` row changed to `On`).
- HUD appeared over the stream in compact mode and remained readable.
- Tapping the HUD cycled it into the expanded/detail mode; UIAutomator saw 15 HUD text nodes including FPS, RTT, bitrate, codec, and resolution.
- Drag/reposition worked: HUD bounds moved from the upper-left compact/expanded position to a central/right position after drag.
- HUD stayed readable after drag.
- HUD remained visible/readable over an alternate/busier scene.
- Command Center could open while HUD was visible; controls stayed readable and did not fight HUD gestures.
- NovaHUD toggled off from Command Center (`Nova HUD` row changed to `Off`) and disappeared from the stream surface.

Primary evidence:
- `12_nova_hud_toggled_on_in_drawer.png/xml/txt`
- `13_hud_visible_stream.png/xml/txt`
- `14_hud_after_tap_cycle.png/xml/txt`
- `15_hud_after_drag.png/xml/txt`
- `16_hud_over_alternate_scene.png/xml/txt`
- `17_command_center_open_with_hud.png/xml/txt`
- `18_command_center_hud_row_on.png/xml/txt`
- `19_nova_hud_toggled_off_in_drawer.png/xml/txt`
- `20_hud_hidden_stream.png/xml/txt`

## Opacity/readability

Result: PASS.

Observed:
- Command Center panel/scrim/nested controls stayed legible over both dark and bright/busy stream-library scenes.
- Panel opacity reads as the same dark-purple glass language as NovaHUD; nested cards and pills have enough contrast without becoming opaque slabs.
- The stream/game library remains visible behind the Command Center, but the drawer content is the clear focus.
- Compact NovaHUD is readable over bright cover art and darker/busier areas without blocking too much screen.
- Expanded NovaHUD is readable over the alternate scene; it uses the same glass/accent vocabulary as Command Center.

Minor visual note:
- When Command Center is open while the expanded HUD is underneath, the HUD can faintly show through the drawer glass. It is not a functional blocker, but it is the one surface that could look slightly busy if both overlays are intentionally stacked.

Primary evidence:
- `06_command_center_start_select.png`
- `13_hud_visible_stream.png`
- `16_hud_over_alternate_scene.png`
- `18_command_center_hud_row_on.png`

## Logcat/crash scan

Result: PASS for app stability.

- Raw bounded logcat: `99_logcat_tail_2500_raw.txt`
- Filtered Nova/stream/error slice: `99_logcat_nova_errors.txt`
- Crash scan: `99_logcat_crash_scan.txt`
- Crash scan results: `fatal_exception=0`, `anr=0`, `androidruntime_error=0`, `native_crash=0`.
- Non-blocking warnings observed: Nova SSE TLS/read warnings around disconnect/reconnect, followed by reconnect; UIAutomator/AccessibilityNodeInfoDumper noise from screenshot/XML capture. No Nova app crash, ANR, or fatal exception was found in the bounded window.

## Follow-up bugs / notes

- Confirm whether physical/lone Retroid Menu button should open in-stream Command Center. Synthetic `KEYCODE_MENU` alone did not, while Start+Select did.
- Optional polish: if simultaneous HUD + Command Center stacking is expected, consider whether the expanded HUD should be hidden/dimmed more aggressively beneath Command Center to reduce ghosting through the drawer glass.
