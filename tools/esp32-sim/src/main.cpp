// BtCarKey box simulator.
//
// Pretends to be the BLE car "box" described in PROTOCOL.md so the BtCarKey app can be tested
// without the real hardware: same advertising, same GATT layout, same framing and the same
// answers as the JVM test double (app/src/test/.../FakeBox.kt), plus a serial console to
// provoke edge cases.
//
// TEST DEVICE ONLY: it logs every frame in hex, device keys included.
//
// Threading: NimBLE callbacks run on the BLE host task and only push events into a queue;
// all protocol state is owned by loop(), so nothing below needs locking.

#include <Arduino.h>
#include <NimBLEDevice.h>
#include <Preferences.h>
#include <Update.h>
#include <esp_bt.h>
#include <esp_ota_ops.h>
#include <esp_system.h>
#include <mbedtls/aes.h>

#include <cstdarg>
#include <ctime>
#include <string>
#include <vector>

#ifndef SIM_LED_PIN
#define SIM_LED_PIN 2
#endif

// ---------------------------------------------------------------------------------------------
// Constants
// ---------------------------------------------------------------------------------------------

namespace sim {
constexpr const char* kDeviceName = "BtCarKey-Sim";
constexpr const char* kServiceUuid = "8849a9d0-d75f-0c8c-2348-de2d580f2f24";
constexpr const char* kDataUuid = "8849a9d1-d75f-0c8c-2348-de2d580f2f24";
constexpr uint16_t kCompanyId = 0xF715;
constexpr uint16_t kPreferredMtu = 247;
constexpr uint16_t kDefaultMtu = 23;
// Fake serial, clearly not a real box's
constexpr uint8_t kDefaultSerial[6] = {0x5A, 0x11, 0x4D, 0x00, 0x00, 0x01};

// What the AUTH answer reports (same values as FakeBox)
constexpr uint32_t kNumber = 6;
constexpr uint32_t kDeviceType = 2;  // CAR_BOX
constexpr uint32_t kPlatform = 2;    // NORDIC
constexpr uint16_t kFwMajor = 1, kFwMiddle = 2, kFwMinor = 3;

constexpr size_t kHeaderSize = 16;
// Larger declared lengths are treated as garbage: no command here carries more than a few bytes
constexpr size_t kMaxPayload = 1024;
}  // namespace sim

namespace cmd {
constexpr uint16_t PING = 1, INFO_SYNC = 2, INFO_REPORT = 3, SYSTEM_CTRL = 4, FLASH_OPT = 5, AUTH = 6,
                   SYSTEM_CFG = 7;
}
namespace authkey {
constexpr uint16_t AUTH = 0, SET_DEVICE_KEY = 1, VERIFY_DEVICE_KEY = 2, UNBIND = 3;
}
namespace ctrlkey {
constexpr uint16_t UNLOCK = 100, LOCK = 101, HOLD_TRUNK = 102, HOLD_FUNCTION = 103, HOLD_UNLOCK = 104,
                   HOLD_LOCK = 105, REMOTE_START = 106, RSSI = 107, NFC_REGISTER = 113, DROP_WINDOWS = 114,
                   TRUNK = 115;
}
namespace cfgkey {
constexpr uint16_t AUTO_LOCK = 0, LOCK_CONFIG = 1, REMOTE_START_CONFIG = 3, UNLOCK_CONFIG = 4, CAR_TYPE = 7,
                   AUTO_LOCK_READ = 32;
}
namespace tlv {
constexpr uint8_t TIMESTAMP = 1, TIME_FORMAT = 2, TIME_ZONE = 3, LANGUAGE = 4, CAR_NUMBER = 13, PHONE_TYPE = 19,
                  BATTERY = 23, FW_MAJOR = 24, FW_MIDDLE = 25, FW_MINOR = 26, VOLTAGE = 32;
}
namespace result {
constexpr uint32_t OK = 0, ERROR = 1, UNKNOWN = 2, DATA_LENGTH_ERROR = 3, DEVICE_KEY_EXIST = 4;
}
namespace ftype {
constexpr uint8_t REQUEST = 0, RESPONSE = 1, REQUEST_NO_ANSWER = 2, REPORT = 3;
}

// ---------------------------------------------------------------------------------------------
// Logging and byte helpers
// ---------------------------------------------------------------------------------------------

// Set during a serial firmware update: the console line then carries only the update protocol
static bool logMuted = false;

static void logf(const char* fmt, ...) {
  if (logMuted) return;
  va_list ap;
  va_start(ap, fmt);
  int n = vsnprintf(nullptr, 0, fmt, ap);
  va_end(ap);
  if (n < 0) return;
  std::string text(static_cast<size_t>(n) + 1, '\0');
  va_start(ap, fmt);
  vsnprintf(&text[0], text.size(), fmt, ap);
  va_end(ap);
  text.resize(n);
  uint32_t ms = millis();
  Serial.printf("[%6lu.%03lu] %s\n", (unsigned long)(ms / 1000), (unsigned long)(ms % 1000), text.c_str());
}

static std::string hex(const uint8_t* data, size_t n, const char* sep = " ") {
  static const char* digits = "0123456789abcdef";
  std::string out;
  out.reserve(n * 3);
  for (size_t i = 0; i < n; i++) {
    if (i) out += sep;
    out += digits[data[i] >> 4];
    out += digits[data[i] & 0x0F];
  }
  return out;
}
static std::string hex(const std::vector<uint8_t>& v, const char* sep = " ") { return hex(v.data(), v.size(), sep); }

static std::string serialString(const uint8_t* s) {
  char buf[18];
  snprintf(buf, sizeof buf, "%02X:%02X:%02X:%02X:%02X:%02X", s[0], s[1], s[2], s[3], s[4], s[5]);
  return buf;
}

static uint16_t rdU16(const uint8_t* p) { return p[0] | (p[1] << 8); }
static uint32_t rdU32(const uint8_t* p) { return rdU16(p) | ((uint32_t)rdU16(p + 2) << 16); }

static uint16_t sum16(const uint8_t* p, size_t n) {
  uint32_t s = 0;
  for (size_t i = 0; i < n; i++) s += p[i];
  return s & 0xFFFF;
}

/** Little-endian payload builder. */
struct Bytes {
  std::vector<uint8_t> v;
  Bytes& u8(uint8_t x) {
    v.push_back(x);
    return *this;
  }
  Bytes& u16(uint16_t x) { return u8(x & 0xFF).u8(x >> 8); }
  Bytes& u32(uint32_t x) { return u16(x & 0xFFFF).u16(x >> 16); }
  Bytes& raw(const uint8_t* p, size_t n) {
    v.insert(v.end(), p, p + n);
    return *this;
  }
  Bytes& tlv(uint8_t tag, const Bytes& value) {
    u8(tag).u8(value.v.size());
    return raw(value.v.data(), value.v.size());
  }
};

static Bytes errorCode(uint32_t code) { return Bytes().u32(code); }

static const char* cmdName(uint16_t c) {
  switch (c) {
    case 0: return "NULL";
    case cmd::PING: return "PING";
    case cmd::INFO_SYNC: return "INFO_SYNC";
    case cmd::INFO_REPORT: return "INFO_REPORT";
    case cmd::SYSTEM_CTRL: return "SYSTEM_CTRL";
    case cmd::FLASH_OPT: return "FLASH_OPT";
    case cmd::AUTH: return "AUTH";
    case cmd::SYSTEM_CFG: return "SYSTEM_CFG";
    default: return "CMD_?";
  }
}

// ---------------------------------------------------------------------------------------------
// Framing
// ---------------------------------------------------------------------------------------------

struct Frame {
  uint8_t type = 0;
  uint8_t channel = 0;
  uint16_t seq = 0;
  uint16_t cmd = 0;
  uint16_t key = 0;
  std::vector<uint8_t> payload;
  bool checksumOk = true;
};

/** A device->app frame: magic AA 55, then the header fields, checksums and payload. */
static std::vector<uint8_t> encodeFrame(uint8_t type, uint16_t seq, uint16_t command, uint16_t key,
                                        const std::vector<uint8_t>& payload) {
  Bytes b;
  b.u8(0xAA).u8(0x55).u8(0).u8(type & 0x0F).u16(seq).u16(command).u16(key).u16(payload.size());
  b.u16(sum16(payload.data(), payload.size()));
  b.u16(sum16(b.v.data(), 14));
  b.raw(payload.data(), payload.size());
  return b.v;
}

/**
 * Rebuilds app->device frames from GATT writes: the app may split a frame across several writes,
 * and a frame is complete once 16 + length bytes are buffered. Bytes before a 55 AA magic are
 * dropped so one garbled write can't wedge the stream.
 */
class FrameAssembler {
 public:
  void reset() { buf_.clear(); }

  std::vector<Frame> push(const uint8_t* data, size_t n) {
    buf_.insert(buf_.end(), data, data + n);
    std::vector<Frame> frames;
    while (true) {
      int start = findMagic();
      if (start < 0) {
        // A trailing 0x55 may be the first half of the next magic
        size_t keep = (!buf_.empty() && buf_.back() == 0x55) ? 1 : 0;
        discard(buf_.size() - keep);
        return frames;
      }
      if (start > 0) discard(start);
      if (buf_.size() < sim::kHeaderSize) return frames;
      size_t length = rdU16(&buf_[10]);
      if (length > sim::kMaxPayload) {
        logf("RX header declares %u payload bytes: treated as garbage, resyncing", (unsigned)length);
        discard(2);
        continue;
      }
      size_t total = sim::kHeaderSize + length;
      if (buf_.size() < total) return frames;
      frames.push_back(decode(buf_.data(), total));
      buf_.erase(buf_.begin(), buf_.begin() + total);
    }
  }

  size_t pending() const { return buf_.size(); }

 private:
  std::vector<uint8_t> buf_;

  int findMagic() const {
    for (size_t i = 0; i + 1 < buf_.size(); i++)
      if (buf_[i] == 0x55 && buf_[i + 1] == 0xAA) return i;
    return -1;
  }

  void discard(size_t n) {
    if (n == 0) return;
    logf("RX discarded %u bytes without a 55 AA magic: %s", (unsigned)n, hex(buf_.data(), n).c_str());
    buf_.erase(buf_.begin(), buf_.begin() + n);
  }

  static Frame decode(const uint8_t* p, size_t total) {
    Frame f;
    f.type = p[3] & 0x0F;
    f.channel = p[3] >> 4;
    f.seq = rdU16(p + 4);
    f.cmd = rdU16(p + 6);
    f.key = rdU16(p + 8);
    f.payload.assign(p + sim::kHeaderSize, p + total);
    f.checksumOk = rdU16(p + 14) == sum16(p, 14) && rdU16(p + 12) == sum16(f.payload.data(), f.payload.size());
    return f;
  }
};

// ---------------------------------------------------------------------------------------------
// State
// ---------------------------------------------------------------------------------------------

/** What survives a reboot (NVS). */
struct Persisted {
  bool hasKey = false;
  uint8_t key[16] = {};
  uint8_t serial[6] = {};
  uint8_t autoLock[5] = {0, 75, 0, 65, 0};  // en, lock -dBm (u16), unlock -dBm (u16): app defaults
};

/** Console switches that bend the protocol on purpose. */
struct TestSwitches {
  bool silent = false;              // swallow SYSTEM_CTRL without answering
  bool dropNextSetAnswer = false;   // store the next SET_DEVICE_KEY but lose its answer
};

struct NfcWait {
  bool active = false;
  uint32_t dueMs = 0;
  Frame request;
};

static Preferences prefs;
static Persisted store;
static TestSwitches switches;
static NfcWait nfcWait;
static FrameAssembler assembler;

static bool authenticated = false;
static uint8_t challenge[16] = {};
static uint8_t battery = 87;
static uint16_t reportSeq = 0;

static NimBLEServer* server = nullptr;
static NimBLECharacteristic* dataChar = nullptr;

// Written by BLE callbacks, read by loop()
static volatile uint16_t connHandle = BLE_HS_CONN_HANDLE_NONE;
static volatile uint16_t connMtu = sim::kDefaultMtu;

static uint32_t ledOffAt = 0;

static bool connected() { return connHandle != BLE_HS_CONN_HANDLE_NONE; }

// ---------------------------------------------------------------------------------------------
// Persistence
// ---------------------------------------------------------------------------------------------

static void loadStore() {
  prefs.begin("btcarkey-sim", false);
  store.hasKey = prefs.getBytes("key", store.key, 16) == 16;
  if (prefs.getBytes("serial", store.serial, 6) != 6) memcpy(store.serial, sim::kDefaultSerial, 6);
  uint8_t al[5];
  if (prefs.getBytes("autolock", al, 5) == 5) memcpy(store.autoLock, al, 5);
}

static void saveKey(const uint8_t* key) {
  memcpy(store.key, key, 16);
  store.hasKey = true;
  prefs.putBytes("key", store.key, 16);
}

static void clearKey() {
  memset(store.key, 0, 16);
  store.hasKey = false;
  prefs.remove("key");
}

// ---------------------------------------------------------------------------------------------
// LED
// ---------------------------------------------------------------------------------------------

static void blink(uint32_t ms) {
  if (SIM_LED_PIN < 0) return;
  digitalWrite(SIM_LED_PIN, HIGH);
  ledOffAt = millis() + ms;
  if (ledOffAt == 0) ledOffAt = 1;
}

static void serviceLed() {
  if (SIM_LED_PIN < 0 || ledOffAt == 0) return;
  if ((int32_t)(millis() - ledOffAt) >= 0) {
    digitalWrite(SIM_LED_PIN, LOW);
    ledOffAt = 0;
  }
}

// ---------------------------------------------------------------------------------------------
// BLE output
// ---------------------------------------------------------------------------------------------

/** Notifies a whole frame, split to (MTU - 3) bytes per notification. */
static void notifyFrame(const std::vector<uint8_t>& bytes) {
  uint16_t handle = connHandle;
  if (handle == BLE_HS_CONN_HANDLE_NONE) {
    logf("TX dropped: no central connected");
    return;
  }
  size_t chunk = connMtu > 23 ? connMtu - 3 : 20;
  for (size_t i = 0; i < bytes.size(); i += chunk) {
    size_t len = std::min(chunk, bytes.size() - i);
    bool ok = false;
    // notify() fails when the stack is out of buffers: give it a moment instead of losing the frame
    for (int attempt = 0; attempt < 20 && !ok; attempt++) {
      ok = dataChar->notify(bytes.data() + i, len, handle);
      if (!ok) delay(5);
    }
    if (!ok) {
      logf("TX notify failed at offset %u", (unsigned)i);
      return;
    }
  }
}

static void sendFrame(uint8_t type, uint16_t seq, uint16_t command, uint16_t key, const Bytes& payload) {
  std::vector<uint8_t> bytes = encodeFrame(type, seq, command, key, payload.v);
  logf("TX %s/%u seq=%u type=%u len=%u: %s", cmdName(command), key, seq, type, (unsigned)payload.v.size(),
       hex(bytes).c_str());
  notifyFrame(bytes);
}

/** Answers a request: type 1, echoing its sequence id, cmd and key. */
static void answer(const Frame& request, const Bytes& payload) {
  if (request.type == ftype::REQUEST_NO_ANSWER) {
    logf("   (request type 2 asks for no answer, not sending)");
    return;
  }
  sendFrame(ftype::RESPONSE, request.seq, request.cmd, request.key, payload);
}

// ---------------------------------------------------------------------------------------------
// Advertising
// ---------------------------------------------------------------------------------------------

/** Manufacturer data = company id 0xF715 (LE on air: 15 F7) + the 6 serial bytes: how the app finds us. */
static void applyAdvertising() {
  NimBLEAdvertising* adv = NimBLEDevice::getAdvertising();
  adv->stop();

  uint8_t mfg[8] = {sim::kCompanyId & 0xFF, sim::kCompanyId >> 8};
  memcpy(mfg + 2, store.serial, 6);

  NimBLEAdvertisementData data;
  data.setFlags(BLE_HS_ADV_F_DISC_GEN | BLE_HS_ADV_F_BREDR_UNSUP);
  data.setManufacturerData(std::string(reinterpret_cast<const char*>(mfg), sizeof mfg));
  data.setName(sim::kDeviceName);

  // The 128-bit service UUID doesn't fit next to the rest in 31 bytes: scan response
  NimBLEAdvertisementData scan;
  scan.setCompleteServices(NimBLEUUID(sim::kServiceUuid));

  adv->setAdvertisementData(data);
  adv->setScanResponseData(scan);
  if (!connected()) adv->start();
}

// ---------------------------------------------------------------------------------------------
// Command handlers
// ---------------------------------------------------------------------------------------------

static void aes128EcbEncrypt(const uint8_t* key, const uint8_t* in, uint8_t* out) {
  mbedtls_aes_context ctx;
  mbedtls_aes_init(&ctx);
  mbedtls_aes_setkey_enc(&ctx, key, 128);
  mbedtls_aes_crypt_ecb(&ctx, MBEDTLS_AES_ENCRYPT, in, out);
  mbedtls_aes_free(&ctx);
}

static void handleAuth(const Frame& f) {
  switch (f.key) {
    case authkey::AUTH: {
      // Every AUTH starts over: a new challenge, and earlier proof no longer counts
      authenticated = false;
      esp_fill_random(challenge, sizeof challenge);
      logf("   AUTH: bindState=%d, challenge %s", store.hasKey ? 1 : 0, hex(challenge, 16, "").c_str());
      Bytes p;
      p.u8(store.hasKey ? 1 : 0).raw(store.serial, 6).u32(sim::kNumber).u32(sim::kDeviceType).u32(sim::kPlatform);
      p.u16(sim::kFwMajor).u16(sim::kFwMiddle).u16(sim::kFwMinor).raw(challenge, 16);
      answer(f, p);
      break;
    }
    case authkey::SET_DEVICE_KEY: {
      uint32_t code = store.hasKey ? result::DEVICE_KEY_EXIST
                      : f.payload.size() != 16 ? result::DATA_LENGTH_ERROR
                                               : result::OK;
      if (code == result::OK) {
        saveKey(f.payload.data());
        authenticated = true;
        logf("   SET_DEVICE_KEY: stored key %s (bound + authenticated)", hex(store.key, 16, "").c_str());
      } else {
        logf("   SET_DEVICE_KEY refused: %s", code == result::DEVICE_KEY_EXIST ? "a key is already stored"
                                                                                : "payload is not 16 bytes");
      }
      if (switches.dropNextSetAnswer) {
        switches.dropNextSetAnswer = false;
        logf("   dropset: answer to SET_DEVICE_KEY deliberately NOT sent");
        return;
      }
      answer(f, errorCode(code));
      break;
    }
    case authkey::VERIFY_DEVICE_KEY: {
      bool ok = false;
      if (!store.hasKey) {
        logf("   VERIFY_DEVICE_KEY: no key stored");
      } else {
        uint8_t expected[16];
        aes128EcbEncrypt(store.key, challenge, expected);
        ok = f.payload.size() == 16 && memcmp(expected, f.payload.data(), 16) == 0;
        logf("   VERIFY_DEVICE_KEY: expected %s, got %s -> %s", hex(expected, 16, "").c_str(),
             hex(f.payload, "").c_str(), ok ? "OK" : "MISMATCH");
      }
      authenticated = ok;
      answer(f, errorCode(ok ? result::OK : result::ERROR));
      break;
    }
    case authkey::UNBIND: {
      bool ok = authenticated;
      if (ok) clearKey();
      logf("   UNBIND: %s", ok ? "key cleared" : "refused, not authenticated");
      answer(f, errorCode(ok ? result::OK : result::ERROR));
      authenticated = false;
      break;
    }
    default:
      logf("   AUTH key %u unknown", f.key);
      answer(f, errorCode(result::UNKNOWN));
  }
}

static void handleSystemCtrl(const Frame& f) {
  if (!authenticated) {
    logf("   SYSTEM_CTRL/%u refused: not authenticated", f.key);
    answer(f, errorCode(result::ERROR));
    return;
  }

  const char* action = nullptr;
  switch (f.key) {
    case ctrlkey::UNLOCK: action = "UNLOCK"; break;
    case ctrlkey::LOCK: action = "LOCK"; break;
    case ctrlkey::TRUNK: action = "TRUNK"; break;
    case ctrlkey::REMOTE_START: action = "REMOTE START"; break;
    case ctrlkey::DROP_WINDOWS: action = "DROP WINDOWS"; break;
  }
  if (action) {
    logf("   >>> CAR: %s <<<", action);
    blink(300);
    if (switches.silent) return logf("   silent: not answering");
    answer(f, errorCode(result::OK));
    return;
  }

  const char* button = nullptr;
  switch (f.key) {
    case ctrlkey::HOLD_UNLOCK: button = "UNLOCK"; break;
    case ctrlkey::HOLD_LOCK: button = "LOCK"; break;
    case ctrlkey::HOLD_TRUNK: button = "TRUNK"; break;
    case ctrlkey::HOLD_FUNCTION: button = "FUNCTION"; break;
  }
  if (button) {
    if (f.payload.size() != 1) {
      logf("   hold %s: expected 1 payload byte, got %u", button, (unsigned)f.payload.size());
      answer(f, errorCode(result::DATA_LENGTH_ERROR));
      return;
    }
    bool pressed = f.payload[0] == 0;
    logf("   >>> CAR: button %s %s <<<", button, pressed ? "PRESSED" : "RELEASED");
    if (SIM_LED_PIN >= 0) digitalWrite(SIM_LED_PIN, pressed ? HIGH : LOW);
    if (switches.silent) return logf("   silent: not answering");
    answer(f, errorCode(result::OK));
    return;
  }

  switch (f.key) {
    case ctrlkey::RSSI: {
      int8_t rssi = 0;
      int rc = ble_gap_conn_rssi(connHandle, &rssi);
      uint8_t positive = rc == 0 ? (uint8_t)abs(rssi) : 0;
      logf("   RSSI: %d dBm (rc=%d) -> answering %u", rssi, rc, positive);
      if (switches.silent) return logf("   silent: not answering");
      answer(f, Bytes().u8(positive));
      return;
    }
    case ctrlkey::NFC_REGISTER: {
      if (f.payload.size() < 2) {
        answer(f, errorCode(result::DATA_LENGTH_ERROR));
        return;
      }
      uint16_t seconds = rdU16(f.payload.data());
      logf("   NFC register: waiting %u s for a card (answer comes after the wait)", seconds);
      nfcWait.active = true;
      nfcWait.dueMs = millis() + seconds * 1000UL;
      nfcWait.request = f;
      return;
    }
    default:
      logf("   SYSTEM_CTRL key %u not simulated", f.key);
      answer(f, errorCode(result::UNKNOWN));
  }
}

static void logInfoSyncTlvs(const std::vector<uint8_t>& p) {
  size_t i = 0;
  while (i + 2 <= p.size()) {
    uint8_t tag = p[i], len = p[i + 1];
    if (i + 2 + len > p.size()) {
      logf("   TLV tag %u truncated (len %u, %u bytes left)", tag, len, (unsigned)(p.size() - i - 2));
      return;
    }
    const uint8_t* v = &p[i + 2];
    std::string text(reinterpret_cast<const char*>(v), len);
    switch (tag) {
      case tlv::TIMESTAMP:
        if (len >= 4) {
          time_t t = rdU32(v);
          struct tm tm;
          gmtime_r(&t, &tm);
          char buf[32];
          strftime(buf, sizeof buf, "%Y-%m-%d %H:%M:%S UTC", &tm);
          logf("   timestamp: %lu (%s)", (unsigned long)rdU32(v), buf);
        }
        break;
      case tlv::TIME_FORMAT: logf("   time format: %u (%s)", v[0], v[0] ? "24h" : "12h"); break;
      case tlv::TIME_ZONE:
        if (len >= 4) {
          int32_t off = (int32_t)rdU32(v);
          logf("   time zone: %ld s (UTC%c%02ld:%02ld)", (long)off, off < 0 ? '-' : '+', labs(off) / 3600,
               (labs(off) % 3600) / 60);
        }
        break;
      case tlv::LANGUAGE: logf("   language: \"%s\"", text.c_str()); break;
      case tlv::PHONE_TYPE: logf("   phone type: %u%s", v[0], v[0] == 1 ? " (Android)" : ""); break;
      case tlv::CAR_NUMBER: logf("   car number (plate): \"%s\"", text.c_str()); break;
      default: logf("   TLV tag %u: %s", tag, hex(v, len).c_str());
    }
    i += 2 + len;
  }
  if (i != p.size()) logf("   %u trailing byte(s) ignored", (unsigned)(p.size() - i));
}

/** Rough 1-cell voltage for a battery percentage, so voltage and percentage move together. */
static uint16_t voltageMv() { return 2700 + battery * 360 / 100; }

static Bytes statusTlvs() {
  Bytes p;
  p.tlv(tlv::BATTERY, Bytes().u8(battery));
  p.tlv(tlv::VOLTAGE, Bytes().u16(voltageMv()));
  p.tlv(tlv::FW_MAJOR, Bytes().u16(sim::kFwMajor));
  p.tlv(tlv::FW_MIDDLE, Bytes().u16(sim::kFwMiddle));
  p.tlv(tlv::FW_MINOR, Bytes().u16(sim::kFwMinor));
  return p;
}

static void handleInfoSync(const Frame& f) {
  logInfoSyncTlvs(f.payload);
  answer(f, statusTlvs());
}

static void handleSystemCfg(const Frame& f) {
  switch (f.key) {
    case cfgkey::AUTO_LOCK_READ: {
      logf("   auto-lock read: en=%u lock=-%u dBm unlock=-%u dBm", store.autoLock[0], rdU16(store.autoLock + 1),
           rdU16(store.autoLock + 3));
      answer(f, Bytes().raw(store.autoLock, 5).u32(result::OK));
      return;
    }
    case cfgkey::AUTO_LOCK: {
      if (f.payload.size() != 5) {
        logf("   auto-lock write: expected 5 bytes, got %u", (unsigned)f.payload.size());
        answer(f, errorCode(result::DATA_LENGTH_ERROR));
        return;
      }
      memcpy(store.autoLock, f.payload.data(), 5);
      prefs.putBytes("autolock", store.autoLock, 5);
      logf("   auto-lock write: en=%u lock=-%u dBm unlock=-%u dBm (stored)", store.autoLock[0],
           rdU16(store.autoLock + 1), rdU16(store.autoLock + 3));
      answer(f, errorCode(result::OK));
      return;
    }
    case cfgkey::LOCK_CONFIG:
    case cfgkey::REMOTE_START_CONFIG:
    case cfgkey::UNLOCK_CONFIG:
    case cfgkey::CAR_TYPE:
      // Vendor-defined blobs: accept and show them, nothing to simulate
      logf("   vendor config key %u: %s", f.key, hex(f.payload).c_str());
      answer(f, errorCode(result::OK));
      return;
    default:
      logf("   SYSTEM_CFG key %u unknown", f.key);
      answer(f, errorCode(result::UNKNOWN));
  }
}

static void handleFrame(const Frame& f) {
  logf("RX %s/%u seq=%u type=%u ch=%u len=%u%s", cmdName(f.cmd), f.key, f.seq, f.type, f.channel,
       (unsigned)f.payload.size(), f.checksumOk ? "" : " (BAD CHECKSUM, processed anyway)");
  switch (f.cmd) {
    case cmd::AUTH: handleAuth(f); break;
    case cmd::SYSTEM_CTRL: handleSystemCtrl(f); break;
    case cmd::INFO_SYNC: handleInfoSync(f); break;
    case cmd::SYSTEM_CFG: handleSystemCfg(f); break;
    case cmd::PING: answer(f, errorCode(result::OK)); break;
    default:
      logf("   unknown command %u/%u, payload %s", f.cmd, f.key, hex(f.payload).c_str());
      answer(f, errorCode(result::UNKNOWN));
  }
}

static void serviceNfcWait() {
  if (!nfcWait.active || (int32_t)(millis() - nfcWait.dueMs) < 0) return;
  nfcWait.active = false;
  logf("NFC register wait over: answering OK");
  answer(nfcWait.request, errorCode(result::OK));
}

// ---------------------------------------------------------------------------------------------
// BLE callbacks -> event queue
// ---------------------------------------------------------------------------------------------

enum class EventKind : uint8_t { Connect, Disconnect, Mtu, Subscribe, Write };

struct Event {
  EventKind kind;
  int arg;
  uint16_t len;
  uint8_t data[512];  // a long (prepared) write can carry up to 512 bytes
};

static QueueHandle_t events;
static volatile uint32_t eventsLost = 0;

static void postEvent(EventKind kind, int arg, const uint8_t* data = nullptr, size_t len = 0) {
  static Event e;  // only the BLE host task posts, so one scratch buffer is enough
  e.kind = kind;
  e.arg = arg;
  e.len = std::min(len, sizeof e.data);
  if (data && e.len) memcpy(e.data, data, e.len);
  if (xQueueSend(events, &e, 0) != pdTRUE) eventsLost++;
}

class ServerCallbacks : public NimBLEServerCallbacks {
  void onConnect(NimBLEServer*, NimBLEConnInfo& info) override {
    connMtu = sim::kDefaultMtu;
    connHandle = info.getConnHandle();
    std::string addr = info.getAddress().toString();
    postEvent(EventKind::Connect, info.getConnHandle(), reinterpret_cast<const uint8_t*>(addr.c_str()), addr.size());
  }
  void onDisconnect(NimBLEServer*, NimBLEConnInfo&, int reason) override {
    connHandle = BLE_HS_CONN_HANDLE_NONE;
    connMtu = sim::kDefaultMtu;
    postEvent(EventKind::Disconnect, reason);
  }
  void onMTUChange(uint16_t mtu, NimBLEConnInfo&) override {
    connMtu = mtu;
    postEvent(EventKind::Mtu, mtu);
  }
};

class DataCallbacks : public NimBLECharacteristicCallbacks {
  void onWrite(NimBLECharacteristic* c, NimBLEConnInfo&) override {
    NimBLEAttValue v = c->getValue();
    postEvent(EventKind::Write, 0, v.data(), v.size());
  }
  void onSubscribe(NimBLECharacteristic*, NimBLEConnInfo&, uint16_t subValue) override {
    postEvent(EventKind::Subscribe, subValue);
  }
};

static ServerCallbacks serverCallbacks;
static DataCallbacks dataCallbacks;

// ---------------------------------------------------------------------------------------------
// Distance: the phone measures the signal itself, so the console makes the box look nearer or
// further by changing its transmit power, or gone altogether by going silent ("away")
// ---------------------------------------------------------------------------------------------

/** millis() at which "away" ends; 0 when not away. */
static uint32_t awayUntil = 0;

static bool isAway() { return awayUntil != 0; }

/** Classic ESP32: -12 to +9 dBm in 3 dB steps (ESP_PWR_LVL_N12 = 0 ... ESP_PWR_LVL_P9 = 7). */
static bool setTxPower(long dbm) {
  if (dbm < -12 || dbm > 9 || (dbm + 12) % 3 != 0) return false;
  auto level = (esp_power_level_t)((dbm + 12) / 3);
  // NimBLE's own setter first, then every power type the controller has (the connection in progress has its own)
  bool nimble = NimBLEDevice::setPower(dbm);
  int failed = 0;
  for (int t = ESP_BLE_PWR_TYPE_CONN_HDL0; t <= ESP_BLE_PWR_TYPE_DEFAULT; t++) {
    if (esp_ble_tx_power_set((esp_ble_power_type_t)t, level) != ESP_OK) failed++;
  }
  logf("   tx power: NimBLE %s, %d controller setting(s) refused", nimble ? "ok" : "refused", failed);
  return true;
}

/** What setTxPower last set (the per-connection settings read back as invalid while nobody is connected). */
static int txPowerDbm() { return (int)esp_ble_tx_power_get(ESP_BLE_PWR_TYPE_ADV) * 3 - 12; }

static void serviceAway() {
  if (!isAway() || (int32_t)(millis() - awayUntil) < 0) return;
  awayUntil = 0;
  logf("away: back in range, advertising again");
  NimBLEDevice::startAdvertising();
}

static void resetSession() {
  authenticated = false;
  nfcWait.active = false;
  assembler.reset();
}

static void processEvents() {
  static Event e;
  while (xQueueReceive(events, &e, 0) == pdTRUE) {
    switch (e.kind) {
      case EventKind::Connect:
        resetSession();
        logf("BLE connected: %.*s (handle %d)", e.len, (const char*)e.data, e.arg);
        break;
      case EventKind::Disconnect:
        resetSession();
        if (isAway()) {
          logf("BLE disconnected (reason 0x%x), staying silent (away)", e.arg);
        } else {
          logf("BLE disconnected (reason 0x%x), advertising again", e.arg);
          NimBLEDevice::startAdvertising();
        }
        break;
      case EventKind::Mtu: logf("BLE MTU = %d (notifications up to %d bytes)", e.arg, e.arg - 3); break;
      case EventKind::Subscribe:
        logf("BLE notifications %s", e.arg & 1 ? "ENABLED" : (e.arg & 2 ? "indications enabled" : "disabled"));
        break;
      case EventKind::Write: {
        logf("RX write %u bytes: %s", e.len, hex(e.data, e.len).c_str());
        for (const Frame& f : assembler.push(e.data, e.len)) handleFrame(f);
        if (assembler.pending()) logf("   (%u bytes buffered, waiting for the rest of the frame)", (unsigned)assembler.pending());
        break;
      }
    }
  }
  if (eventsLost) {
    logf("WARNING: %lu BLE events lost (queue full)", (unsigned long)eventsLost);
    eventsLost = 0;
  }
}

// ---------------------------------------------------------------------------------------------
// Serial firmware update
// ---------------------------------------------------------------------------------------------

namespace serialota {
constexpr size_t kChunk = 4096;          // host sends one chunk, then waits for "OTA ACK"
constexpr uint32_t kTimeoutMs = 10000;   // per chunk
constexpr uint32_t kConsoleBaud = 115200;
}  // namespace serialota

static void otaFail(const char* what) {
  Serial.printf("OTA ERROR %s: %s\n", what, Update.errorString());
  Serial.flush();
  Update.abort();
}

/**
 * Receives a new app image over the console UART and writes it to the idle OTA slot, so boards
 * whose USB bridge can't pull IO0 low (no auto-reset circuit) can still be updated without
 * touching the BOOT button. Protocol (driven by sim_serial.py):
 *   host: "ota <size> <md5> [baud]"   device: "OTA READY" (then switches to baud)
 *   host: up to 4096 bytes            device: "OTA ACK <bytes so far>"   ... repeated ...
 *   device: "OTA DONE" and reboots, or "OTA ERROR <reason>" and stays on the old firmware.
 * Flow control is one chunk in flight: flash writes stall the CPU, and the UART FIFO would
 * overflow if the host kept streaming meanwhile.
 */
static void runSerialOta(size_t size, const String& md5, uint32_t baud) {
  if (size == 0) return logf("usage: ota <size> <md5 hex> [baud]");
  logf("serial OTA: %u bytes at %lu baud, stopping BLE", (unsigned)size, (unsigned long)baud);
  if (connected()) server->disconnect(connHandle);
  NimBLEDevice::stopAdvertising();

  if (!Update.begin(size)) {
    otaFail("begin");
    NimBLEDevice::startAdvertising();
    return;
  }
  if (md5.length() == 32) Update.setMD5(md5.c_str());

  logMuted = true;
  Serial.println("OTA READY");
  Serial.flush();
  if (baud != serialota::kConsoleBaud) Serial.updateBaudRate(baud);

  static uint8_t buf[serialota::kChunk];
  size_t done = 0;
  bool ok = true;
  while (done < size && ok) {
    size_t want = std::min(serialota::kChunk, size - done);
    size_t got = 0;
    uint32_t last = millis();
    while (got < want) {
      int n = Serial.read(buf + got, want - got);
      if (n > 0) {
        got += n;
        last = millis();
      } else if (millis() - last > serialota::kTimeoutMs) {
        break;
      } else {
        delay(1);
      }
    }
    if (got < want) {
      Serial.printf("OTA ERROR timeout after %u bytes\n", (unsigned)(done + got));
      Update.abort();
      ok = false;
    } else if (Update.write(buf, want) != want) {
      otaFail("write");
      ok = false;
    } else {
      done += want;
      Serial.printf("OTA ACK %u\n", (unsigned)done);
    }
  }

  if (ok && !Update.end()) {
    otaFail("end");
    ok = false;
  }
  if (ok) {
    Serial.println("OTA DONE");
    Serial.flush();
    delay(100);
    ESP.restart();
  }

  // Failed: back to normal service on the old firmware
  Serial.flush();
  if (baud != serialota::kConsoleBaud) Serial.updateBaudRate(serialota::kConsoleBaud);
  while (Serial.available()) Serial.read();
  logMuted = false;
  logf("serial OTA failed, still running the previous firmware");
  NimBLEDevice::startAdvertising();
}

// ---------------------------------------------------------------------------------------------
// Serial console
// ---------------------------------------------------------------------------------------------

static void printHelp() {
  Serial.println(
      "Commands:\n"
      "  help                 this list\n"
      "  status               bind/auth/connection state, MTU, RSSI, settings\n"
      "  version              firmware name and version\n"
      "  unbind               forget the stored device key (factory reset: bindState 0)\n"
      "  battery <0-100>      battery %% reported in INFO_SYNC answers and reports\n"
      "  report               push an unsolicited INFO_SYNC frame (type 3) with the battery TLVs\n"
      "  silent on|off        don't answer SYSTEM_CTRL (app sees 'sent, unconfirmed')\n"
      "  dropset on|off       store the next SET_DEVICE_KEY but drop its answer\n"
      "  kick                 disconnect the central\n"
      "  tx <dBm>             transmit power, -12..9 in steps of 3: lower looks further to the phone\n"
      "  away <seconds>       out of range: disconnect and stop advertising for a while\n"
      "  back                 end 'away' now\n"
      "  serial <12 hex>      change the advertised serial (persisted), e.g. serial 5A114D000001\n"
      "  reboot               restart the ESP32\n"
      "  ota <size> <md5> [baud]  receive a new firmware over this port (used by flash.ps1)");
}

static void printStatus() {
  int8_t rssi = 0;
  bool haveRssi = connected() && ble_gap_conn_rssi(connHandle, &rssi) == 0;
  logf("status: serial %s, bound=%s, authenticated=%s", serialString(store.serial).c_str(),
       store.hasKey ? "yes" : "no", authenticated ? "yes" : "no");
  if (store.hasKey) logf("        device key %s (TEST DEVICE: printed on purpose)", hex(store.key, 16, "").c_str());
  if (connected())
    logf("        connected, handle %u, MTU %u, RSSI %s%d dBm", connHandle, connMtu, haveRssi ? "" : "? ", rssi);
  else if (isAway())
    logf("        away (silent) for %lu more s", (unsigned long)((awayUntil - millis()) / 1000));
  else
    logf("        not connected (advertising)");
  logf("        tx power %d dBm", txPowerDbm());
  logf("        battery %u%% (%u mV), auto-lock en=%u lock=-%u unlock=-%u, silent=%s, dropset=%s", battery,
       voltageMv(), store.autoLock[0], rdU16(store.autoLock + 1), rdU16(store.autoLock + 3),
       switches.silent ? "on" : "off", switches.dropNextSetAnswer ? "armed" : "off");
  if (nfcWait.active) logf("        NFC register wait pending");
}

static bool parseOnOff(const String& arg, bool& out) {
  if (arg == "on" || arg == "1") return out = true, true;
  if (arg == "off" || arg == "0") return out = false, true;
  return false;
}

static bool parseHex6(String s, uint8_t* out) {
  s.replace(":", "");
  s.replace("-", "");
  s.replace(" ", "");
  if (s.length() != 12) return false;
  for (int i = 0; i < 6; i++) {
    char* end = nullptr;
    String byte = s.substring(i * 2, i * 2 + 2);
    long v = strtol(byte.c_str(), &end, 16);
    if (*end != '\0') return false;
    out[i] = (uint8_t)v;
  }
  return true;
}

static void handleConsoleLine(String line) {
  line.trim();
  if (line.isEmpty()) return;
  int space = line.indexOf(' ');
  String word = space < 0 ? line : line.substring(0, space);
  String arg = space < 0 ? String() : line.substring(space + 1);
  word.toLowerCase();
  arg.trim();

  if (word == "help" || word == "?") {
    printHelp();
  } else if (word == "status") {
    printStatus();
  } else if (word == "version") {
    logf("%s fw %u.%u.%u (built %s %s)", sim::kDeviceName, sim::kFwMajor, sim::kFwMiddle, sim::kFwMinor, __DATE__,
         __TIME__);
  } else if (word == "unbind") {
    clearKey();
    authenticated = false;
    logf("console: device key cleared, bindState is now 0");
  } else if (word == "battery") {
    long n = arg.toInt();
    if (arg.isEmpty() || n < 0 || n > 100) return logf("usage: battery <0-100>");
    battery = (uint8_t)n;
    logf("console: battery %u%% (%u mV)", battery, voltageMv());
  } else if (word == "report") {
    if (!connected()) return logf("report: no central connected");
    logf("console: pushing INFO_SYNC report");
    sendFrame(ftype::REPORT, reportSeq++, cmd::INFO_SYNC, 0, statusTlvs());
  } else if (word == "silent") {
    if (!parseOnOff(arg, switches.silent)) return logf("usage: silent on|off");
    logf("console: silent %s", switches.silent ? "on: SYSTEM_CTRL will not be answered" : "off");
  } else if (word == "dropset") {
    if (!parseOnOff(arg, switches.dropNextSetAnswer)) return logf("usage: dropset on|off");
    logf("console: dropset %s", switches.dropNextSetAnswer ? "armed for the next SET_DEVICE_KEY" : "off");
  } else if (word == "kick") {
    if (!connected()) return logf("kick: no central connected");
    logf("console: disconnecting the central");
    server->disconnect(connHandle);
  } else if (word == "tx") {
    if (arg.isEmpty() || !setTxPower(arg.toInt())) return logf("usage: tx <-12|-9|-6|-3|0|3|6|9> (dBm)");
    logf("console: tx power %d dBm", txPowerDbm());
  } else if (word == "away") {
    long seconds = arg.toInt();
    if (seconds <= 0 || seconds > 3600) return logf("usage: away <1-3600 seconds>");
    awayUntil = millis() + (uint32_t)seconds * 1000;
    if (awayUntil == 0) awayUntil = 1;
    NimBLEDevice::stopAdvertising();
    logf("console: away for %ld s (silent, no advertising)", seconds);
    if (connected()) server->disconnect(connHandle);
  } else if (word == "back") {
    if (!isAway()) return logf("back: not away");
    awayUntil = millis();
    serviceAway();
  } else if (word == "serial") {
    uint8_t s[6];
    if (!parseHex6(arg, s)) return logf("usage: serial <12 hex digits>, e.g. serial 5A114D000001");
    memcpy(store.serial, s, 6);
    prefs.putBytes("serial", store.serial, 6);
    applyAdvertising();
    logf("console: serial is now %s (persisted, advertising updated)", serialString(store.serial).c_str());
  } else if (word == "reboot") {
    logf("console: rebooting");
    Serial.flush();
    ESP.restart();
  } else if (word == "ota") {
    // ota <size> <md5> [baud]
    int s1 = arg.indexOf(' ');
    String size = s1 < 0 ? arg : arg.substring(0, s1);
    String rest = s1 < 0 ? String() : arg.substring(s1 + 1);
    rest.trim();
    int s2 = rest.indexOf(' ');
    String md5 = s2 < 0 ? rest : rest.substring(0, s2);
    long baud = s2 < 0 ? serialota::kConsoleBaud : rest.substring(s2 + 1).toInt();
    if (baud <= 0) baud = serialota::kConsoleBaud;
    runSerialOta((size_t)size.toInt(), md5, (uint32_t)baud);
  } else {
    logf("unknown command '%s', type help", word.c_str());
  }
}

static void serviceConsole() {
  static String line;
  while (Serial.available()) {
    char c = (char)Serial.read();
    if (c == '\r' || c == '\n') {
      if (!line.isEmpty()) handleConsoleLine(line);
      line = "";
    } else if (line.length() < 200) {
      line += c;
    }
  }
}

// ---------------------------------------------------------------------------------------------
// Arduino entry points
// ---------------------------------------------------------------------------------------------

void setup() {
  // Room for a whole serial OTA chunk (the default RX buffer is 256 bytes)
  Serial.setRxBufferSize(2 * serialota::kChunk);
  Serial.begin(serialota::kConsoleBaud);
  delay(50);
  // If the bootloader keeps a fresh OTA image on probation, confirm it: we got this far
  esp_ota_mark_app_valid_cancel_rollback();

  if (SIM_LED_PIN >= 0) {
    pinMode(SIM_LED_PIN, OUTPUT);
    digitalWrite(SIM_LED_PIN, LOW);
  }

  loadStore();
  events = xQueueCreate(16, sizeof(Event));

  NimBLEDevice::init(sim::kDeviceName);
  NimBLEDevice::setMTU(sim::kPreferredMtu);
  server = NimBLEDevice::createServer();
  server->setCallbacks(&serverCallbacks);
  NimBLEService* service = server->createService(sim::kServiceUuid);
  dataChar = service->createCharacteristic(
      sim::kDataUuid, NIMBLE_PROPERTY::WRITE | NIMBLE_PROPERTY::WRITE_NR | NIMBLE_PROPERTY::NOTIFY);
  dataChar->setCallbacks(&dataCallbacks);
  server->start();
  applyAdvertising();

  Serial.println();
  logf("=== %s fw %u.%u.%u: BtCarKey box simulator (TEST DEVICE, logs keys) ===", sim::kDeviceName, sim::kFwMajor,
       sim::kFwMiddle, sim::kFwMinor);
  logf("BLE address %s, advertising serial %s (company 0x%04X)", NimBLEDevice::getAddress().toString().c_str(),
       serialString(store.serial).c_str(), sim::kCompanyId);
  logf("bindState=%d%s", store.hasKey ? 1 : 0,
       store.hasKey ? (std::string(", device key ") + hex(store.key, 16, "")).c_str() : "");
  logf("type 'help' for console commands");
}

void loop() {
  processEvents();
  serviceNfcWait();
  serviceAway();
  serviceConsole();
  serviceLed();
  delay(2);
}
