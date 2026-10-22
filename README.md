# BtCarKey

**An open Android app for Bluetooth LE car boxes, written from scratch to replace the vendor's app.**

The box (a small module wired into the car, sold with an app called "redriverbear") turns a phone
into the car's key over Bluetooth Low Energy. The vendor app works poorly and drains the battery;
BtCarKey talks to the same hardware directly, with no account and no cloud.

## Features

- **Car commands**: unlock, lock, trunk, remote start, lower windows, plus the remote's buttons
  held down (long press, including the Function button). Risky commands need a long press.
- **Automatic pairing**: on every connection the app asks the box whether it already holds a key.
  - Paired box: the saved key answers the box's challenge (AES-128).
  - New or reset box: a fresh random key is generated, saved *before* it is sent (so a lost answer
    can't lose it), and set on the box.
  - "Pair again" clears the box's key and sets a new one (every other phone stops working).
- **Several boxes**, each with its own name and key; add by scanning or by importing a known key.
- **Keys stay on the phone**, encrypted with a key held in the Android Keystore. Showing, copying
  or exporting a key asks for the fingerprint or the phone's PIN; the screen can't be captured
  meanwhile and a copied key leaves the clipboard after a minute.
- **Smart unlock**: unlocks when you get close and locks when you walk away, with the app closed
  (see below). The box's own **comfort entry (legacy)** is still there too; only one of the two can be on.
- **Box details**: battery, firmware, licence plate, NFC card registration, raw vendor configuration.
- **Clear status**: search → connect → verify → ready, always on screen; every step and command is
  logged on screen, in logcat (`BtCarKey`) and in a file you can pull from the PC.
- **Modern UI**: light/dark theme, accent colors, home buttons you choose and order; English and Italian.

## Hands-free: smart unlock and comfort entry (legacy)

Two ways to open the car as you walk up to it; turning one on turns the other off.

**Smart unlock** (Android 12+, any box that can unlock and lock):

1. The car is paired once as a *companion device* (a system dialog). From then on Android itself
   notices when the car comes into range or leaves it, with its own low-power scanning, even with
   the app closed.
2. Only then does the phone connect and read the link's signal strength (a local reading, about
   once a second). When it is stronger than the unlock threshold for a few readings in a row, the car
   is unlocked; when, walking away, it drops below the lock threshold, the car is locked and the
   phone disconnects.
3. Thresholds are bounded on purpose: unlocking needs you within a few metres (never weaker than
   -75 dBm), and locking happens while the phone is still connected (never weaker than -88 dBm),
   because with no signal the lock can't be sent. A short drop of the link is waited out. If the link
   drops for good before the lock (walking away very fast), a notification says the car may still be
   unlocked, and the app locks it at the first chance: as soon as the link comes back, or the next
   time the car is in range.
4. Cooldown (an option, on by default): an unlock followed by a lock within three minutes looks like passing by, and each
   pass-by makes the next unlock need you closer; three pass-bys in half an hour suspend smart unlock
   (a notification offers *Resume*; it also resumes by itself after three hours). Staying by the car,
   or driving away with it, counts as real use and clears this.
5. Coming back: after a lock the phone stays connected for a short while (2, 5 or 10 minutes, 5 by
   default), sampling every second, so coming back for something you forgot unlocks again even
   without having left the Bluetooth range (Android wouldn't report a new arrival). After that it
   rests until the car has been out of range once.
6. Optional safety: unlock only while the phone is unlocked, against signal relay attacks.
7. Safety net, always on: four automatic arrivals that unlocked within ten minutes, whatever the
   reason, suspend smart unlock (same notification and *Resume* button). Coming back within one visit
   counts once.
8. *Pause*: the notification shown near the car has a Pause button. Nothing opens or locks the car
   by itself until you tap *Resume* (a silent notification stays meanwhile); the car is left as it is.
9. *Guided calibration*: stand by the door, then where the car should lock; the app measures both
   spots and proposes the thresholds, shown on a map of the zones (the same map shows the live
   signal next to the sliders).
10. *Confirm automatic locks* (on by default): a short double vibration and a quiet notification when
    the car locks by itself; if it couldn't be locked, a pop-up warning with a long vibration.

A box doesn't advertise while a phone is connected to it, and Android's presence watching is based
on advertising: while connected, Android soon reports the car "out of range". The app ignores any
report while the link is up (the link itself proves the car is there) and relies on the link
dropping for real departures. Android 16 also reports the phone's own connections and disconnections
through the same callbacks; only its BLE presence events count. Limit of any presence-based approach: if the car is parked within
Bluetooth range of where you are (e.g. right outside the house), Android never sees you leave and
come back, so walking out to it doesn't trigger smart unlock; use the app's buttons.

**Comfort entry (legacy)** is built into the box: the *box* measures the phone's signal and opens or
locks by itself, so the phone must stay connected all the time ("Stay connected", a background
connection with a permanent notification), which costs more battery. It has presets, a guided
calibration and a live signal view. The app reads its state from the box on every connection, so a
legacy mode left on (for example by the vendor app) shows up on the smart unlock screen, with a
button to turn it off.

On both screens the live signal is shown next to the threshold sliders while the car is in range.

## Battery

- No background scanning. A saved box is connected by address; short, bounded scans are used only
  to add a box or to learn its address.
- Waiting for the car uses Android's autoConnect: the Bluetooth controller connects by itself when
  the box comes into range, the phone sleeps meanwhile. Link losses reconnect the same way.
- No polling: status is read once per connection; the signal is read only while a hands-free
  screen is open, or by smart unlock while the car is in range.
- Smart unlock connects only when Android reports the car in range, and rests after each visit.
  During a visit (usually a few minutes) it keeps the CPU awake so the lock is never late with the
  screen off; far from the car it costs nothing.
- The link is closed 20 s after the app leaves the screen, unless **Stay connected** is on (needed
  by comfort entry). Then a minimal, silent notification is shown (Android requires one for a
  background connection) and the link runs at the low-power interval.

## Build and install

Requirements: Android Studio (its JDK is used), a phone with USB debugging on.

```
scripts\deploy.cmd            build, install and open the app (add -Tests to run the unit tests first)
scripts\pull-log.cmd          copy the app's log from the phone into logs\
```

### Using an existing pairing

If the box is already paired (for example with the vendor app) and you know its 16-byte device key,
give it to the app instead of pairing again:

- in the app: *Add a box → Import a key*, or
- from the PC: put it in `device_secrets.json` (ignored by git) and run `scripts\push-key.cmd -Name "My car"`

```json
{ "serial": "AA:BB:CC:DD:EE:FF", "deviceKey": "00112233445566778899aabbccddeeff", "bleMac": "C0:00:00:00:00:01" }
```

The receiver accepts this only from the adb shell. An exported key file has the same fields and can
be pushed back the same way (`-File`).

## Testing without the car

`tools/esp32-sim` is firmware that turns an ESP32 into a box simulator (same advertising, GATT and
protocol), with a serial console to reset it, change the battery level or simulate lost answers.
The protocol and pairing logic are also covered by JVM unit tests against a software box (`FakeBox`).

## Protocols

The app is built around a small protocol interface, so other kinds of BLE car keys can be added
without touching the connection logic or the UI:

- `KeyProtocol` says how a device advertises (to recognise it in a scan), which GATT service and
  characteristics it uses, how long its key is, and what it can do (commands, remote buttons,
  comfort entry, NFC...); the UI only shows what the protocol supports.
- `ProtocolSession` runs one connection: authentication (reuse a stored key or pair a new one),
  commands, status.
- `Protocols.all` lists the known ones; each saved device remembers its protocol, shown in its details.

Supported today: **FTK** ("redriverbear" boxes), described in [PROTOCOL.md](PROTOCOL.md): advertising,
GATT layout, frame format, authentication and commands, reconstructed for interoperability with
hardware the author owns.

## Disclaimer

Use it only with your own car and box. This project is not affiliated with the box's vendor.
