"""Serial helper for the BtCarKey ESP32 simulator (needs pyserial; PlatformIO's Python has it).

  python sim_serial.py detect                       print the first USB-UART port that looks like an ESP32
  python sim_serial.py probe   --port COM7          exit 0 if the simulator firmware answers on the port
  python sim_serial.py ota     --port COM7 --firmware .pio/build/esp32dev/firmware.bin
  python sim_serial.py monitor --port COM7 --seconds 8 [--reset] [--send "status;help"]

The port is opened with DTR/RTS released: on boards with the usual auto-reset wiring, asserting
RTS alone holds the chip in reset.
"""
import argparse
import hashlib
import sys
import time

import serial
import serial.tools.list_ports

CONSOLE_BAUD = 115200
CHUNK = 4096
# USB-UART bridges found on ESP32 boards: CP210x, CH340/CH9102, FTDI, Espressif native USB
KNOWN_VIDS = {0x10C4, 0x1A86, 0x0403, 0x303A}


def open_port(port, baud=CONSOLE_BAUD, timeout=0.2):
    s = serial.Serial()
    s.port = port
    s.baudrate = baud
    s.timeout = timeout
    s.dtr = False
    s.rts = False
    s.open()
    return s


def detect():
    for p in serial.tools.list_ports.comports():
        if p.vid in KNOWN_VIDS:
            return p.device
    return None


def read_lines_until(s, predicate, timeout):
    """Reads lines until predicate(line) is true; returns that line or None on timeout."""
    end = time.time() + timeout
    buf = b""
    while time.time() < end:
        buf += s.read(256)
        while b"\n" in buf:
            line, buf = buf.split(b"\n", 1)
            text = line.decode("utf-8", "replace").strip()
            if predicate(text):
                return text
    return None


def probe(port):
    with open_port(port) as s:
        s.reset_input_buffer()
        s.write(b"\nversion\n")
        return read_lines_until(s, lambda l: "BtCarKey-Sim fw" in l, 2.0) is not None


def ota(port, firmware, baud):
    data = open(firmware, "rb").read()
    md5 = hashlib.md5(data).hexdigest()
    with open_port(port) as s:
        s.reset_input_buffer()
        s.write(f"\nota {len(data)} {md5} {baud}\n".encode())
        line = read_lines_until(s, lambda l: l.startswith("OTA READY") or l.startswith("OTA ERROR"), 10)
        if line is None or line.startswith("OTA ERROR"):
            raise SystemExit(f"simulator did not accept the update: {line or 'no answer'}")
        if baud != CONSOLE_BAUD:
            s.baudrate = baud
            time.sleep(0.05)
        t0 = time.time()
        sent = 0
        while sent < len(data):
            chunk = data[sent:sent + CHUNK]
            s.write(chunk)
            sent += len(chunk)
            line = read_lines_until(s, lambda l: l.startswith("OTA ACK") or l.startswith("OTA ERROR"), 15)
            if line is None or line.startswith("OTA ERROR"):
                raise SystemExit(f"\nupdate failed at {sent} bytes: {line or 'no ack'}")
            print(f"\r  {sent * 100 // len(data):3d}%  {sent}/{len(data)} bytes", end="", flush=True)
        line = read_lines_until(s, lambda l: l.startswith("OTA DONE") or l.startswith("OTA ERROR"), 30)
        print()
        if line != "OTA DONE":
            raise SystemExit(f"update not confirmed: {line or 'no answer'}")
        print(f"  written in {time.time() - t0:.1f} s, MD5 verified by the device, rebooting")


def monitor(port, seconds, reset, send):
    with open_port(port) as s:
        if reset:
            s.rts = True  # EN low
            time.sleep(0.1)
            s.rts = False
        if send:
            # Give a chip that is (re)booting time to reach its console
            time.sleep(1.5)
            for cmd in send.split(";"):
                s.write((cmd.strip() + "\n").encode())
                time.sleep(0.3)
        end = time.time() + seconds
        while time.time() < end:
            d = s.read(1024)
            if d:
                sys.stdout.write(d.decode("utf-8", "replace"))
                sys.stdout.flush()


def main():
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    ap = argparse.ArgumentParser()
    ap.add_argument("action", choices=["detect", "probe", "ota", "monitor"])
    ap.add_argument("--port")
    ap.add_argument("--firmware")
    ap.add_argument("--baud", type=int, default=921600, help="transfer speed for ota")
    ap.add_argument("--seconds", type=float, default=8)
    ap.add_argument("--reset", action="store_true", help="pulse EN (RTS) before monitoring")
    ap.add_argument("--send", help="console commands to send, separated by ';'")
    a = ap.parse_args()

    if a.action == "detect":
        port = detect()
        if not port:
            raise SystemExit("no ESP32-looking serial port found")
        print(port)
        return
    port = a.port or detect()
    if not port:
        raise SystemExit("no serial port given and none detected")
    if a.action == "probe":
        sys.exit(0 if probe(port) else 1)
    if a.action == "ota":
        if not a.firmware:
            raise SystemExit("--firmware is required")
        ota(port, a.firmware, a.baud)
    if a.action == "monitor":
        monitor(port, a.seconds, a.reset, a.send)


if __name__ == "__main__":
    main()
