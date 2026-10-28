# Steam Controller (2026) on Android

Nova's Android driver reads the Steam Controller over a USB cable, a Steam
Controller Puck, or an already paired Bluetooth LE connection. USB controllers
also navigate Nova's library after USB access has been allowed. Outside the
library and a stream, the controller keeps its own mouse mode.
Linux, Steam Deck and Steam Frame use their existing input paths.

This driver is being qualified for 1.5.0. Source tests do not establish that a
particular controller, Puck, Android device or host combination has passed a
hands-on check.

## Connect

- **USB or Puck:** leave **Settings → Input & Controllers → USB controller
  driver** on. Connect the controller or Puck, then open **System → Set Up Steam
  Controller** in Nova's library. If access is needed, choose **Allow USB Access** and accept Android's
  prompt. **Access allowed** confirms USB permission; **Nova is reading this
  controller here** means native input has arrived. A Puck's four controller channels stay separate;
  an empty channel does not reserve a player.
  Opening the library or checking the status never asks for permission on its own.
  After reconnecting USB or rebooting, use the setup page again if Android needs
  access. If access is still missing when a stream starts, Android asks then.
  Leave **Override native Xbox
  gamepad support** off; the Steam Controller has its own driver path.
- **Bluetooth:** pair in Android's Bluetooth settings first, then enable
  **Steam Controller over Bluetooth** in Nova. On Android 12 and later, allow
  Nearby devices when asked. Nova does not scan, pair or request location access.
  Bluetooth support is off by default, including on Shield.
- A Shield with a USB port can use a cable or Puck. Models without USB need
  Bluetooth. See the Puck pairing steps below for a receiver that isn't connecting.

Changes apply to the next stream. If access is denied or the native connection
fails, Nova releases it and the controller returns to mouse mode. Check the
connection and permissions before trying another stream.

Android does not expose a reliable physical parent for every lizard-mode input
device. To avoid swallowing another controller's mouse or keyboard events, this
version refuses an ambiguous group: more than one attached USB device with the
same Steam Controller product ID, or more than one paired Steam Controller over
Bluetooth. Paired means saved in Android, including controllers currently switched
off. One Puck can still carry its four independent channels; leave **Multiple
controllers** enabled for separate players. A surviving USB device is reconsidered
after its duplicate is unplugged.
A real kernel-provided gamepad keeps its existing Android input route.

## Check the Puck pairing

A controller usually comes paired to its included Puck. Nova receives Puck input
once that link connects; Android's USB permission is a separate setup step.

- **White blinking light:** the controller is searching for its Puck. Turn it off
  by holding **Steam** for about five seconds. From off, hold **A + R1 + Steam**
  for about two seconds to use the right saved Puck slot. **A + L1 + Steam** uses
  the left slot. A connected Puck link shows a **solid white** light (breathing
  while charging); blue is Bluetooth.
- **Neither slot connects:** connect the Puck to a PC running Steam with no game
  running, place the controller on the Puck, and complete Steam's pairing prompt.
  Pairing replaces the Puck saved in the slot you started in. To keep another
  Puck's pairing, use the other slot. Reconnect the Puck to your Android device
  and use Nova's **Set Up Steam Controller** page again if USB access is needed.

See Valve's [connection and pairing guide](https://help.steampowered.com/en/faqs/view/33E8-5EDF-24E6-4CFB)
and [mode-switching combinations](https://help.steampowered.com/en/faqs/view/345E-F55E-E96C-E0CF).

## Play

In the library, **Y** cycles the layout. D-pad and the left stick move focus;
**A** selects and **B** goes back. While Command Center is open, native controller
input belongs to its menu. The game receives neutral input, and held buttons
must be released before they can act after the menu closes. The library's native
navigation uses USB; Bluetooth remains a stream-only connection in this version.

Face buttons, sticks, D-pad, bumpers, triggers, View and Menu use Nova's normal
controller path. **Steam** follows the existing Guide tap and shortcut rules;
**…** opens Nova's Command Center locally. **Players** shows the assigned player
and connection, and **Reassign players** releases the old assignment before
the next input joins again.

Choose **Steam Controller trackpads**:

- **Mouse** (default): the right pad moves the host pointer and clicks it;
  clicking the left pad right-clicks, and moving on the left pad scrolls.
- **Game touchpad**: the two pads become separate contacts on the left and right
  halves of one host PlayStation touchpad. Clicking either pad clicks that pad.

L4, R4, L5 and R5 can each map to a face button, bumper or stick click in the
same settings page. They default to Off. A mapped grip shares its target with
the physical button: releasing one does not release the other while held.

The controller's gyro and accelerometer are used when **Allow use of gamepad
motion sensors** is enabled and the host requests motion. **Always use device's
motion sensors** remains an explicit phone-sensor override. Basic two-motor
rumble follows **Enable Rumble**. Trigger rumble, HD haptic waveforms, battery
readouts and full capacitive Steam Input layouts are not advertised.

With Polaris's automatic host controller type, Steam Controller type 4 is
emulated as a DualSense, preserving motion and the split touchpad. This is not
a native Steam Input Steam Controller identity or a Valve community-layout
implementation. Choosing an Xbox host controller type loses motion/touchpad
features. Other streaming hosts may handle the type differently.

Disconnecting a controller cancels held buttons, mouse clicks and touch contacts,
stops motion and rumble, and releases its player. A reconnected controller must
confirm native mode again before it can join. When a stream ends, a USB controller
or Puck returns to library navigation, while Bluetooth leaves native mode. Leaving
the library also releases USB. Without the native driver, the controller's watchdog
restores mouse mode, which can take a moment.

## Implementation sources

The report layout is adapted from SDL's Steam Triton driver at
`d0183f0ee3b2497f49c6322c62354d114c22af29` (zlib). Android transport work also
consulted Moonlight Android PR 1619 at
`6e83831838b3cd80ee325c6dac4611263af2d00d` (GPLv3). Its open-PR status is not
an upstream or hardware qualification claim. Adapted source preserves its
license notices. Nova's pinned common-c API carries both contacts through the
single-touchpad interface; Nova does not advertise an unsupported dual-pad
wire extension.
