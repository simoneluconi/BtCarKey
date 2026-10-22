# BtCarKey — notes for working on this project

Android app (Kotlin, Jetpack Compose foundation only, no Material, minSdk 29) that replaces the vendor
app of a Bluetooth LE car box. Protocol: `PROTOCOL.md`. User docs: `README.md`.

This repository will be **public**: never commit device keys, serials, BLE addresses, logs or anything
from the vendor's APK. `device_secrets.json` and `logs/` are git-ignored on purpose.

## Working agreements

- **Language**: code, comments, logs, docs and commit messages in English. UI strings in English
  (`res/values`) with an Italian translation (`res/values-it`) for every string. The user talks in Italian.
- Edit files with the editor tools, not shell scripts.
- Every batch ends with: `scripts\deploy.cmd -Tests` (build, unit tests, install on the phone).
  The PC can't be near the car: field tests happen later with the phone alone, so every connection
  step and command must be logged (`EventLog`: screen, logcat tag `BtCarKey`, file pulled with
  `scripts\pull-log.cmd`). Never log key material (use `KeyFingerprint.of`).
- Ask before big designs or user-visible behaviour changes. Commit only when asked.

## Map of the code (`app/src/main/java/com/simoneluconi/btcarkey/`)

| Package | What lives there |
|---|---|
| `protocol/` | The protocol-agnostic API, pure Kotlin: `KeyProtocol` (identify from advertising, GATT profile, capabilities, key size, `newSession`), `ProtocolSession` (authenticate, commands, remote buttons, status, comfort entry, NFC, plate, raw config), `Protocols` (the registry), neutral types (`CarCommand`, `RemoteButton`, `DeviceInfo`, `BoxStatus`, `AutoLockConfig`), byte helpers |
| `protocol/ftk/` | The only protocol so far, the "redriverbear" boxes (PROTOCOL.md): `FtkProtocol` + `FtkSession`, frames (`Frame`, `FrameAssembler`), `FrameSession` (seq matching, timeouts), `FtkAuthenticator` (AUTH → verify a stored key, or pair a new random key; repair = UNBIND + new key), messages, TLVs, AES challenge |
| `auth/` | Generic: `KeyVault`, `AuthOutcome`, `AuthStep`, `KeyFingerprint`, `Serials` |
| `link/` | `BoxBleManager` (Nordic BLE, driven by the protocol's GATT profile), `BoxScanner` (scans for every registered protocol), `CarKeyController` (the process-wide connection: lifecycle, reconnection, battery rules; protocol work goes through the session), `KeepConnectedService` |
| `keys/` | `DeviceStore` (boxes and their keys: active / pending / backup per serial), `SecretStore` (Keystore AES-GCM), `KeyImportReceiver` (adb import, DUMP-protected) |
| `settings/` | `AppSettings`: theme, accent, home buttons, confirmations, haptics, stay connected |
| `smart/` | Smart unlock: `CompanionLink` (companion association + presence watching), `SmartPresenceService` (system presence events), `SmartEntryService` (near the car: signal sampling, unlock/lock), `SmartEntryLogic` + `Cooldown` (pure decisions, tested), `SmartEntry` (coordinator, status), `SmartNotifications`. Never on together with legacy comfort entry (the box's AUTO_LOCK) |
| `ui/` | Screens (`HomeScreen`, `GarageScreen`, `DeviceScreen`, `ComfortScreen`, `SettingsScreen`), `App` (navigation, messages), `Components`, `AppIcons`, `theme/` |

Tests: `app/src/test` runs the protocol registry and, in `protocol/ftk`, the FTK wire format, pairing
flows and session against `FakeBox`, a software box. `tools/esp32-sim` is an ESP32 FTK box simulator
for over-the-air tests.

## Protocols

Nothing outside `protocol/<name>/` may know a protocol's details. A new protocol implements
`KeyProtocol` + `ProtocolSession` in its own package and is added to `Protocols.all`; the UI hides
what its capabilities (`commands`, `buttons`, `features`, `rawConfigs`) leave out. Saved devices store
the protocol id (devices from before ids existed are FTK). Keys are opaque bytes of `keySize`.

## Battery rules (don't break them)

No background scanning, no polling, autoConnect for waiting, disconnect 20 s after the app leaves the
screen unless "Stay connected" is on, low-power connection interval in the background, and no
automatic retries on problems the driver must solve (wrong key...).

## UI style

Same look as the author's BetterAAuto: colors only from `LocalAppColors`, text through `Label`
(Manrope), icons only from `AppIcons` (24-unit grid, 2-unit round strokes), cards rounded 24 dp,
pill buttons, every interactive element through `Modifier.pressable` (or `holdable`).
