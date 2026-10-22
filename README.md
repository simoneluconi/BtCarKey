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
- **Comfort entry** (the box locks/unlocks by the phone's distance): thresholds, presets, a guided
  calibration and a live signal view.
- **Box details**: battery, firmware, licence plate, NFC card registration, raw vendor configuration.
- **Clear status**: search → connect → verify → ready, always on screen; every step and command is
  logged on screen, in logcat (`BtCarKey`) and in a file you can pull from the PC.
- **Modern UI**: light/dark theme, accent colors, home buttons you choose and order; English and Italian.

## Battery

- No background scanning. A saved box is connected by address; short, bounded scans are used only
  to add a box or to learn its address.
- Waiting for the car uses Android's autoConnect: the Bluetooth controller connects by itself when
  the box comes into range, the phone sleeps meanwhile. Link losses reconnect the same way.
- No polling: status is read once per connection; the signal is read only on the comfort-entry
  screens while they are open.
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
