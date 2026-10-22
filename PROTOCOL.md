# FTK: BLE protocol of the car box ("redriverbear" app v3.3.0)

Reconstructed for interoperability from the vendor's Android app (`com.zhc.redriverbear`), to talk to
hardware the author owns. Names of the app's classes are given in parentheses for reference; the app
calls the protocol "FTK", and so does BtCarKey (`protocol/ftk`).

## 1. Discovery (advertising)

- The box is recognised by **Manufacturer Specific Data** with company ID `0xF715` (63253, `FEJY`
  in `FilterUtils`); on air the ID is little-endian (`15 F7`).
- The manufacturer data payload is 6 bytes: the box's **serial** ("logical MAC"),
  e.g. `A1B2C3D4E5F6` -> `A1:B2:C3:D4:E5:F6`. It is not necessarily the BLE address.

## 2. GATT

| What           | UUID                                   |
|----------------|----------------------------------------|
| Service        | `8849a9d0-d75f-0c8c-2348-de2d580f2f24` |
| Write + Notify | `8849a9d1-d75f-0c8c-2348-de2d580f2f24` |

- One characteristic (`...a9d1`) for both directions.
- Writes are **Write Without Response**.
- After connecting: enable notifications (CCCD), request **MTU 247**.
- Frames longer than the MTU are split; the receiver reassembles until
  `len(buffer) == 16 + dataLength`.

## 3. Frame (`XPacket` / `FtkBean`)

16-byte header, every field a **little-endian uint16** unless noted, followed by the payload.

| Off | Len | Field          | Notes |
|-----|-----|----------------|-------|
| 0   | 2   | magic          | App->device: `55 AA` (0xAA55). Device->app: `AA 55` (0x55AA) |
| 2   | 1   | version        | 0 |
| 3   | 1   | type/channel   | low nibble = type, high nibble = channel. The app sends 0 |
| 4   | 2   | sequenceId     | incremented by the app; the answer repeats it |
| 6   | 2   | cmd            | see §4 |
| 8   | 2   | key            | sub-command |
| 10  | 2   | dataLength     | payload length |
| 12  | 2   | dataChecksum   | **sum** (not a CRC) of the payload bytes, truncated to 16 bits; 0 when empty |
| 14  | 2   | headerChecksum | sum of header bytes 0..13, truncated to 16 bits |
| 16  | n   | payload        | |

`type`: 0 = app->device (answer expected), 1 = device->app (answer/ACK), 2 = app->device without
answer, 3 = device->app unsolicited.

The vendor app keeps at most 5 commands in flight; a command without an answer within 10 s times out.

### Examples

```
AUTH          seq=0: 55 AA 00 00 00 00 06 00 00 00 00 00 00 00 05 01
QUICK UNLOCK  seq=1: 55 AA 00 00 01 00 04 00 64 00 00 00 00 00 68 01
```

## 4. Commands (cmd / key)

| cmd | Name        |
|-----|-------------|
| 0   | NULL        |
| 1   | PING        |
| 2   | INFO_SYNC   |
| 3   | INFO_REPORT |
| 4   | SYSTEM_CTRL |
| 5   | FLASH_OPT (OTA/DFU) |
| 6   | AUTHENTICATION |
| 7   | SYSTEM_CFG  |

### 4.1 cmd 6 – Authentication

| key | Direction | Payload |
|-----|-----------|---------|
| 0 AUTH | app->device | empty |
| 0 AUTH | device->app | see below |
| 1 SET_DEVICE_KEY | app->device | 16-byte deviceKey |
| 2 VERIFY_DEVICE_KEY | app->device | 16-byte answer to the challenge |
| 1/2 | device->app | uint32 errorCode (0 = OK) |
| 3 UNBIND | app->device | empty (only when authenticated) |

AUTH answer (key 0):

| Off | Len | Field |
|-----|-----|-------|
| 0   | 1   | bindState: 0 = no key set (new box or after UNBIND), 1 = a key is set |
| 1   | 6   | serial |
| 7   | 4   | number (model id) |
| 11  | 4   | deviceType: 1 = CAR_KEY, 2 = CAR_BOX |
| 15  | 4   | platform: 1 = ATK, 2 = NORDIC, 3 = REALTEK |
| 19  | 2+2+2 | firmware version major/middle/minor |
| 25  | 16  | random (challenge) |
| 41  | 2   | brand (optional, printed `%04d`) |
| 43  | 2   | model (optional) |

**How the vendor app makes the key** (`FtkService.setDeviceKey`):

```
pwd16     = user's password in UTF-8, zero-padded/truncated to 16 bytes
serial16  = "AA:BB:CC:DD:EE:FF" (upper case, 17 chars) truncated to 16 bytes
deviceKey = AES-128-ECB(key = pwd16, plaintext = serial16)
```

This only produces 16 bytes: at run time **only the deviceKey matters**, and any 16 bytes work.

**Flow:**

1. Connect, notifications on, MTU 247.
2. App -> `AUTH` (6/0).
3. `bindState == 0`: the app picks a deviceKey and sends `SET_DEVICE_KEY` (6/1) with the key in clear.
4. `bindState == 1`: the app sends `VERIFY_DEVICE_KEY` (6/2) with
   `AES-128-ECB(key = deviceKey, plaintext = random)`.
5. errorCode 0 -> authenticated; the vendor app then sends INFO_SYNC (time, time zone, language).

The vendor's server (`query_key`) only serves the deviceKey back after a reinstall: it is **not
needed** when the key is known.

**Reusing an existing pairing** (the key is known):

```
connect -> notify ON -> MTU 247
-> AUTH (6/0)
<- AUTH answer: bindState=1, random[16]
-> VERIFY_DEVICE_KEY (6/2, AES-128-ECB(deviceKey, random))
<- errorCode == 0  => authenticated
-> INFO_SYNC with time/zone/language
-> SYSTEM_CTRL commands
```

**Pairing from scratch** (new box, or after UNBIND):

```
connect -> notify ON -> MTU 247
-> AUTH (6/0)
<- AUTH answer: bindState=0
-> SET_DEVICE_KEY (6/1, deviceKey[16])
<- errorCode == 0  => paired and authenticated
```

To move a box from one app to another: reuse the existing deviceKey, or UNBIND (6/3, while
authenticated) and SET_DEVICE_KEY a new one.

### 4.2 cmd 4 – SYSTEM_CTRL (car commands)

| key | Name | Payload |
|-----|------|---------|
| 100 | QUICK UNLOCK | empty |
| 101 | QUICK LOCK | empty |
| 115 | QUICK TRUNK | empty |
| 106 | REMOTE START | empty |
| 114 | DROP WINDOW | empty |
| 104 | UNLOCK (hold) | 1 byte: 0 = pressed, 1 = released |
| 105 | LOCK (hold) | 1 byte: 0 = pressed, 1 = released |
| 102 | TRUNK (hold) | 1 byte: 0 = pressed, 1 = released |
| 103 | FUNCTION (hold) | 1 byte: 0 = pressed, 1 = released |
| 107 | RSSI_REPORT | empty; answer: 1 byte, the phone's signal as a **positive** number (-dBm) |
| 108 | RSSI_REPORT_ENABLE | |
| 113 | NFC_CARD_REGISTER | uint16 seconds to wait for a card |
| 15  | DFU_MODE | empty |
| 13 / 14 | SHUTDOWN / REBOOT | |

The hold variants press the remote's button on "pressed" and release it on "released" (long press);
the QUICK ones are a single click. The vendor app polls RSSI_REPORT every 2 s on its main screen when
comfort entry is on.

### 4.3 cmd 2 – INFO_SYNC (TLV payload)

TLV: `tag (1 byte) | length (1 byte) | value (little-endian)`.

App -> device after authenticating: `TIMESTAMP(1)` = uint32 epoch seconds, `TIME_FORMAT(2)` = 1 byte
(1 = 24 h), `TIME_ZONE(3)` = int32 UTC offset **in seconds**, `LANGUAGE(4)` = string (e.g. `it`),
`PHONE_TYPE(19)` = 1 (Android). Separately: `CAR_NUMBER(13)` = string (licence plate).

Device -> app (status):

| Tag | Field |
|-----|-------|
| 23 | battery % (1 byte) |
| 24/25/26 | firmware version major/middle/minor (uint16) |
| 27 | device type (u32) |
| 28 | platform (u32) |
| 29 | number (u32) |
| 30 | bond state (1 byte) |
| 31 | CPU id (bytes) |
| 32 | voltage (u16, mV) |
| 33 | charging (1 byte) |
| 34 | power state of box/key (1 byte) |

### 4.4 cmd 7 – SYSTEM_CFG

| key | Name | Payload |
|-----|------|---------|
| 0  | AUTO_LOCK ("comfort entry": lock/unlock by distance) | `en(1) lockDist(u16) unlockDist(u16)` |
| 32 | read AUTO_LOCK | answer: `en(1) lockDist(u16) unlockDist(u16) err(u32)` |
| 1  | lock behaviour (e.g. close windows) | opaque bytes from the vendor's catalog |
| 3  | remote start behaviour | opaque bytes |
| 4  | unlock behaviour | opaque bytes |
| 7  | car type | opaque bytes |

Answers to keys 0..4: `uint32 errorCode`. The distances are RSSI thresholds in -dBm: the box
unlocks when the signal is stronger than `unlockDist` and locks when weaker than `lockDist`.
Vendor defaults: lock 75, unlock 65. Its calibration samples the signal for a minute at each
place and takes the mean without the top and bottom 10%; lock is moved to unlock + 8 when it isn't
further than unlock.

### 4.5 cmd 5 – FLASH_OPT

Firmware/file updates (init, write, checksum, reboot...). Not needed for normal use.

### Error codes (CmdResult)

0 OK, 1 ERROR, 2 UNKNOWN, 3 DATA_LENGTH_ERROR, 4 DEVICE_KEY_EXIST, 5 USER_PAIRED_TOO_MANY,
100-104 NFC errors, 105/106 file system errors.
