# ESP32 box simulator

Firmware that turns a classic ESP32 dev board into a stand-in for the BLE car "box", so the
BtCarKey app can be tested without the real hardware. It speaks the protocol in
[`PROTOCOL.md`](../../PROTOCOL.md) and gives the same answers as the JVM test double
`app/src/test/java/com/simoneluconi/btcarkey/FakeBox.kt`, plus a serial console for edge cases.

> **Test device only.** The simulator logs every frame in hex on the serial console, **including
> device keys, challenges and expected AES answers**, and keeps its key in plain NVS. Never reuse a
> key you set on it for anything real, and never point it at a real car.

## What it simulates

- **Advertising**: connectable, name `BtCarKey-Sim`, Manufacturer Specific Data with company id
  `0xF715` (on air `15 F7`) followed by the 6-byte serial. Default serial `5A:11:4D:00:00:01`
  (fake). The 128-bit service UUID is in the scan response.
- **GATT**: service `8849a9d0-d75f-0c8c-2348-de2d580f2f24`, one characteristic
  `8849a9d1-d75f-0c8c-2348-de2d580f2f24` (Write, Write Without Response, Notify). MTU up to 247.
- **Framing**: 16-byte little-endian header, app->device `55 AA`, device->app `AA 55`; answers are
  type 1 and echo the request's sequence id, cmd and key; byte-sum checksums. Writes split across
  several GATT writes are reassembled; answers longer than MTU-3 are split into several notifications.
  Frames with a bad checksum are logged and processed anyway (the official app doesn't check them
  either). Requests of type 2 ("no answer wanted") are processed but not answered.

| cmd / key | Behaviour |
|---|---|
| 6/0 AUTH | bindState (1 if a key is stored), serial, number 6, deviceType 2 (CAR_BOX), platform 2, fw 1.2.3, fresh 16-byte random challenge. Resets authentication. |
| 6/1 SET_DEVICE_KEY | 0 = stored (bound + authenticated), 4 = a key already exists, 3 = not 16 bytes |
| 6/2 VERIFY_DEVICE_KEY | 0 if payload == AES-128-ECB(storedKey, challenge), else 1 |
| 6/3 UNBIND | when authenticated: clear the key, 0; otherwise 1 |
| 4/100, 101, 115, 106, 114 | unlock, lock, trunk, remote start, drop windows: logged, LED blink, 0 |
| 4/104, 105, 102, 103 | hold unlock / lock / trunk / function, 1 byte (0 pressed, 1 released): logged, LED follows, 0 |
| 4/107 RSSI | 1 byte: the connection's RSSI as a positive number (-dBm) |
| 4/113 NFC register | u16 seconds: answers 0 after that many seconds (non-blocking) |
| 4/* while not authenticated | 1 |
| 4/other | 2 |
| 2 INFO_SYNC | logs the app's TLVs (timestamp, time format, time zone, language, phone type, plate); answers TLVs 23 battery %, 32 voltage mV, 24/25/26 fw version |
| 7/32 | auto-lock config: en(1) lock(u16) unlock(u16) err(u32 0) |
| 7/0 | stores the 5-byte auto-lock config, 0 (3 if not 5 bytes) |
| 7/1, 3, 4, 7 | vendor blobs: logged, 0 |
| 1 PING | 0 |
| anything else | logged, 2 |

The device key, the auto-lock config and the serial are kept in NVS, so the bind state survives
reboots.

## Hardware

Any classic ESP32 board (ESP32-WROOM-32 / DevKit, `board = esp32dev`). The onboard LED on GPIO2
blinks on car actions; change or disable it with `-DSIM_LED_PIN=<pin>` / `-DSIM_LED_PIN=-1` in
`platformio.ini`. For ESP32-C3/S3 boards change `board` accordingly.

## Build and flash

Requires [PlatformIO Core](https://platformio.org/install/cli) (`python -m pip install --user platformio`).

```powershell
cd tools\esp32-sim
.\flash.ps1                 # or flash.cmd from cmd.exe
```

`flash.ps1` builds, finds the ESP32's serial port (or `-Port COM7`), uploads, then prints the first
seconds of the serial log (`-MonitorSeconds N`) so you can see the new firmware boot. It never asks
you to press BOOT or EN:

- **`-Method serial`**: if the simulator is already running on the board, the new build is sent
  through its console (`ota` command) into the idle OTA slot, checked against its MD5, and the
  board reboots into it. Works on every board, including those whose USB bridge can't drive IO0.
- **`-Method esptool`**: plain `pio run -t upload`; esptool puts the chip in download mode
  through the DTR/RTS auto-reset circuit (`--before default_reset`) and reboots it afterwards
  (`--after hard_reset`).
- **`-Method auto`** (default): `serial` if the simulator answers on the port, else `esptool`.

If esptool fails with *"Wrong boot mode detected (0x13)"*, the board has no working auto-reset
circuit: the classic ESP32 can only enter its ROM bootloader with IO0 held low at reset, and unlike
the ESP32-S2/S3/C3 it has no register that firmware can set to reboot into download mode. Install
the simulator once with BOOT held down while esptool connects (or with any OTA mechanism the
board's current firmware offers); from then on `flash.ps1` updates it over serial with no buttons.

Manual equivalents:

```powershell
pio run                     # build
pio run -t upload           # esptool flash
pio device monitor          # console at 115200 (DTR/RTS kept released)
python sim_serial.py ota --port COM7 --firmware .pio\build\esp32dev\firmware.bin
python sim_serial.py monitor --port COM7 --seconds 10 --send "status"
```

## Console commands (115200 baud)

| Command | Effect |
|---|---|
| `help` | list commands |
| `status` | serial, bound?, authenticated?, key (hex), connection handle, MTU, RSSI, battery, auto-lock, test switches |
| `version` | firmware name and version |
| `unbind` | forget the stored key (factory reset, AUTH reports bindState 0) |
| `battery <0-100>` | battery % used in INFO_SYNC answers and reports (voltage follows) |
| `report` | push an unsolicited INFO_SYNC frame (type 3) with the battery TLVs |
| `silent on\|off` | don't answer SYSTEM_CTRL commands (tests the app's "sent, unconfirmed" path) |
| `dropset on\|off` | store the next SET_DEVICE_KEY but drop its answer (tests pending-key recovery) |
| `kick` | disconnect the central |
| `tx <dBm>` | transmit power, -12 to 9 in steps of 3 (resets to 9 on reboot). The phone measures the signal itself, so a lower power looks further away: about 15 dB of swing, enough to cross smart unlock's thresholds from a fixed spot |
| `away <seconds>` | out of range: disconnect and stop advertising for a while (the phone sees the car leave) |
| `back` | end `away` now |
| `serial <12 hex>` | change the advertised serial, persisted (`serial 5A114D000001`) |
| `reboot` | restart the ESP32 |
| `ota <size> <md5> [baud]` | receive a firmware image over the console (used by `sim_serial.py`) |

Every GATT write, every reassembled frame and every notification is printed with a timestamp,
e.g.:

```
[  12.345] RX write 16 bytes: 55 aa 00 00 00 00 06 00 00 00 00 00 00 00 05 01
[  12.346] RX AUTH/0 seq=0 type=0 ch=0 len=0
[  12.346]    AUTH: bindState=0, challenge 3f9c...
[  12.347] TX AUTH/0 seq=0 type=1 len=41: aa 55 00 01 00 00 06 00 ...
```
