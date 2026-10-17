"""Rehearsal bridge: a stand-in for the camera, so the Imzo Device can be tested without one.

It plays the part of the laptop page plus the webcam and the scanner module: it asks the real backend for
the frames, sends them to the device over USB serial (the dev firmware reads IMQ1 lines there), waits for
the answer frames the device prints, and posts them back to the backend. The backend and the protocol are
exactly the ones the Android app uses. Not part of the shipping path.

Needs the dev firmware (IMZO_DEV_SERIAL) and pyserial (installed with PlatformIO in ~/dev/pio-venv).

    ~/dev/pio-venv/bin/python device/tools/bridge.py enrol alice
    ~/dev/pio-venv/bin/python device/tools/bridge.py order alice --decision approve          # presses the buttons itself
    ~/dev/pio-venv/bin/python device/tools/bridge.py order alice --decision manual           # you press them on the device
    ~/dev/pio-venv/bin/python device/tools/bridge.py order alice --tamper --decision reject  # the "infected laptop" run
"""

from __future__ import annotations

import argparse
import glob
import json
import sys
import time
import urllib.request
from pathlib import Path

import serial

ROOT = Path(__file__).resolve().parent.parent.parent
BASE = "http://127.0.0.1:8080"


def api(path: str, body: dict | None = None):
    data = json.dumps(body).encode() if body is not None else None
    req = urllib.request.Request(BASE + path, data=data, headers={"Content-Type": "application/json"})
    try:
        with urllib.request.urlopen(req, timeout=10) as r:
            return json.loads(r.read())
    except urllib.error.HTTPError as e:
        return {"_http_error": e.code, **json.loads(e.read() or b"{}")}


class Device:
    def __init__(self, port: str | None):
        port = port or (glob.glob("/dev/cu.usbmodem*") or [None])[0]
        if not port:
            sys.exit("no device on /dev/cu.usbmodem*")
        self.s = serial.Serial(port, 115200, timeout=0.2)
        time.sleep(0.5)
        self.s.reset_input_buffer()

    def send(self, line: str) -> None:
        self.s.write(line.encode() + b"\n")
        self.s.flush()

    def lines(self, seconds: float):
        end = time.time() + seconds
        while time.time() < end:
            raw = self.s.readline()
            if raw:
                text = raw.decode(errors="replace").rstrip()
                if text.startswith("IMZO "):
                    yield text[5:]

    def wait_for(self, prefix: str, seconds: float, collect: list[str] | None = None) -> str | None:
        for line in self.lines(seconds):
            if collect is not None and line.startswith("ANSWER "):
                collect.append(line[7:])
            if line.startswith(prefix):
                return line
            if line.startswith("STATE error"):
                return line
        return None

    def play(self, frames: list[str]) -> None:
        for f in frames:
            self.send(f)
            time.sleep(0.15)

    def info(self) -> dict:
        self.send("PING")
        line = self.wait_for("INFO", 3)
        if not line:
            sys.exit("device did not answer PING (is the dev firmware flashed?)")
        return dict(kv.split("=", 1) for kv in line.split()[1:])


def collect_answer(dev: Device, seconds: float) -> list[str]:
    """Answer frames are printed right after the state line; wait for them and keep what arrives."""
    frames: list[str] = []
    for line in dev.lines(seconds):
        if line.startswith("ANSWER "):
            frames.append(line[7:])
            if len(frames) == int(line.split(".")[4]):
                break
    if frames:
        dev.send("BTN A")  # leave the answer-QR screen, like pressing A on the device
        time.sleep(0.3)
    return frames


def do_enrol(dev: Device, user: str, auto: bool) -> int:
    begin = api("/api/enrol/begin", {"user": user})
    if "frames" not in begin:
        print("backend refused:", begin)
        return 1
    dev.play(begin["frames"])
    st = dev.wait_for("STATE enrol", 5)
    if not st or "error" in st:
        print("device did not reach the enrolment screen:", st)
        return 1
    code = st.split("code=")[1]
    print(f"device shows enrolment code {code}")
    if auto:
        dev.send("HOLD A")
    else:
        print("hold A on the device for 1.5 s to continue ...")
    frames = collect_answer(dev, 60 if not auto else 10)
    if not frames:
        print("no enrolment answer from the device")
        return 1
    done = api("/api/enrol/finish", {"frames": frames})
    print("backend:", done)
    if done.get("code") != code:
        print("MISMATCH between device and backend codes")
        return 1
    print("enrolled; codes match")
    return 0


def do_order(dev: Device, approver: str, maker: str, tamper: bool, decision: str) -> int:
    items = json.loads((ROOT / "web" / "payroll.json").read_text())
    shown = [list(i) for i in items]
    sent = [list(i) for i in items]
    if tamper:  # what an infected laptop does: the order the server stores differs from what the page shows
        sent[6][1] = sent[6][1][:-1] + ("2" if sent[6][1][-1] != "2" else "3")
    order = api("/api/orders", {"maker": maker, "approver": approver, "ref": "Payroll October", "items": sent})
    if "frames" not in order:
        print("backend refused:", order)
        return 1
    print(f"order {order['order_id']}  server fingerprint: {order['fingerprint']}  frames: {len(order['frames'])}")
    dev.play(order["frames"])
    st = dev.wait_for("STATE review", 8)
    if not st or "error" in st:
        print("device refused the request:", st)
        return 1
    print("device is on the review screen")
    n = len(items)
    if decision == "manual":
        print("review on the device and hold A (approve) or B (reject) for 1.5 s ...")
    else:
        for _ in range(n + 1):  # summary -> every beneficiary -> decision page
            dev.send("BTN A")
            time.sleep(0.25)
        dev.send("HOLD A" if decision == "approve" else "HOLD B")
    frames = collect_answer(dev, 90 if decision == "manual" else 10)
    if not frames:
        print("no answer from the device")
        return 1
    result = api("/api/answers", {"frames": frames})
    print("backend:", result)
    executed = api("/api/executed")
    if isinstance(executed, list) and executed:
        row7 = executed[-1]["items"][6]
        print("executed row 7:", row7, "(the list on the laptop showed", shown[6], ")")
    audit = api("/api/audit")
    print("audit chain ok:", audit.get("ok"), " last:", audit.get("rows", [{}])[-1].get("kind"))
    return 0 if "_http_error" not in result else 1


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--port")
    sub = ap.add_subparsers(dest="cmd", required=True)
    e = sub.add_parser("enrol")
    e.add_argument("user")
    e.add_argument("--manual", action="store_true", help="wait for you to hold A on the device")
    o = sub.add_parser("order")
    o.add_argument("approver")
    o.add_argument("--maker", default="mike")
    o.add_argument("--tamper", action="store_true")
    o.add_argument("--decision", choices=["approve", "reject", "manual"], default="approve")
    sub.add_parser("info")
    sub.add_parser("reset")
    args = ap.parse_args()
    if args.cmd == "reset":
        print(api("/api/reset", {}))
        return 0
    dev = Device(args.port)
    if args.cmd == "info":
        print(dev.info())
        return 0
    if args.cmd == "enrol":
        return do_enrol(dev, args.user, auto=not args.manual)
    return do_order(dev, args.approver, args.maker, args.tamper, args.decision)


if __name__ == "__main__":
    sys.exit(main())
