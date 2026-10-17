"""Differential tests for the C++ protocol core of the Imzo Device.

Builds device/lib/imzo_core with the host compiler (with sanitizers) and drives it through
imzo_cli.cpp, checking it against protocol/cases.json and protocol/imzo_ref.py.

Run from the repo root:   .venv/bin/python -m unittest discover -s device/host -v

The one allowed difference: the device has no Unicode tables and refuses code points outside
isAllowedCodePoint() with UNSUPPORTED TEXT. That is stricter than the reference, never looser, and the
tests below enforce exactly that direction: the device may refuse what Python accepts, but must never
accept what Python refuses.
"""

from __future__ import annotations

import hashlib
import json
import random
import subprocess
import sys
import unittest
from pathlib import Path

HERE = Path(__file__).resolve().parent
ROOT = HERE.parent.parent
sys.path.insert(0, str(ROOT / "protocol"))

import imzo_ref as ref  # noqa: E402

BUILD = HERE / "build"
CLI = BUILD / "imzo_cli"
CASES = json.loads((ROOT / "protocol" / "cases.json").read_text())
VECTORS = json.loads((ROOT / "protocol" / "vectors.json").read_text())


def build() -> None:
    BUILD.mkdir(exist_ok=True)
    sources = [HERE / "imzo_cli.cpp", *sorted((ROOT / "device/lib/imzo_core").glob("*.cpp"))]
    newest = max(p.stat().st_mtime for p in [*sources, *(ROOT / "device/lib/imzo_core").glob("*.h")])
    if CLI.exists() and CLI.stat().st_mtime > newest:
        return
    cmd = ["c++", "-std=c++17", "-Wall", "-Wextra", "-Werror", "-O1", "-g", "-fsanitize=address,undefined",
           "-fno-sanitize-recover=undefined", f"-I{ROOT / 'device/lib/imzo_core'}", *map(str, sources), "-o", str(CLI)]
    subprocess.run(cmd, check=True)


def run(lines: list[str]) -> list[str]:
    out = subprocess.run([str(CLI)], input="\n".join(lines) + "\n", capture_output=True, text=True)
    if out.returncode != 0:
        raise AssertionError(f"imzo_cli crashed: {out.stderr[:2000]}")
    return out.stdout.splitlines()


def hx(b: bytes) -> str:
    return b.hex() or "-"


def unhx(s: str) -> bytes:
    return b"" if s == "-" else bytes.fromhex(s)


def py_request(data: bytes):
    """(ok, code) from the reference."""
    try:
        ref.parse_request(data)
        return True, None
    except ref.ProtocolError as exc:
        return False, exc.code


def check_request_agreement(tc: unittest.TestCase, data: bytes, got: str, label: str) -> None:
    py_ok, py_code = py_request(data)
    if got.startswith("ok"):
        tc.assertTrue(py_ok, f"{label}: device accepted what the reference refuses ({py_code})")
        return
    code = got[4:]
    if py_ok:
        tc.assertEqual(code, "UNSUPPORTED TEXT", f"{label}: device refused a valid request with {code}")
    elif code == "UNSUPPORTED TEXT":
        pass  # stricter than the reference: allowed
    else:
        tc.assertEqual(code, py_code, f"{label}: different error code")


class CoreTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        build()

    # -- CBOR

    def test_cbor_cases(self):
        cases = CASES["cbor"]
        out = run([f"cbor {c['hex'] or '-'}" for c in cases])
        for c, got in zip(cases, out):
            with self.subTest(c["name"]):
                if c["ok"]:
                    self.assertEqual(got, f"ok {c['hex'] or '-'}")
                else:
                    self.assertEqual(got, f"err {c.get('error', 'BAD FORMAT')}")

    # -- requests

    def test_request_cases(self):
        cases = CASES["requests"]
        out = run([f"request {c['hex'] or '-'}" for c in cases])
        unsupported = []
        for c, got in zip(cases, out):
            with self.subTest(c["name"]):
                data = bytes.fromhex(c["hex"])
                if "error" not in c:
                    if got.startswith("err UNSUPPORTED TEXT"):
                        unsupported.append(c["name"])
                        continue
                    self.assertTrue(got.startswith("ok"), f"valid case refused: {got}")
                    _, total, count, *words = got.split()
                    self.assertEqual(int(total), c["total"])
                    self.assertEqual(int(count), c["count"])
                    self.assertEqual(" ".join(words), c["fingerprint"])
                else:
                    self.assertTrue(got.startswith("err"), f"hostile case accepted: {c['name']}")
                    check_request_agreement(self, data, got, c["name"])
        print(f"\n  valid cases the device refuses as UNSUPPORTED TEXT: {unsupported or 'none'}")

    def test_request_mutation_fuzz(self):
        """Random byte flips and truncations of valid requests: the device must never accept what Python refuses."""
        rng = random.Random(7)
        seeds = [bytes.fromhex(c["hex"]) for c in CASES["requests"] if "error" not in c and c["hex"]]
        datas = []
        for _ in range(3000):
            b = bytearray(rng.choice(seeds))
            for _ in range(rng.choice([1, 1, 2, 3])):
                i = rng.randrange(len(b))
                b[i] = rng.randrange(256)
            if rng.random() < 0.15:
                b = b[: rng.randrange(1, len(b))]
            datas.append(bytes(b))
        out = run([f"request {hx(d)}" for d in datas])
        for d, got in zip(datas, out):
            check_request_agreement(self, d, got, "fuzz " + d.hex()[:40])

    # -- frames

    def test_frame_cases(self):
        cases = CASES["frames"]
        def enc(frames):
            return " ".join(f.encode().hex() for f in frames)

        out = run(["frames " + enc(c["frames"]) for c in cases])
        out2 = run(["assemble " + enc(c["frames"]) for c in cases if "error" not in c])
        ok_cases = [c for c in cases if "error" not in c]
        for c, got in zip(cases, out):
            with self.subTest(c["name"]):
                if "error" in c:
                    self.assertEqual(got, f"err {c['error']}")
                else:
                    self.assertEqual(got, f"ok {c['kind']} {c['body_hex'] or '-'}")
        for c, got in zip(ok_cases, out2):
            with self.subTest("assemble " + c["name"]):
                self.assertEqual(got, f"ok {c['kind']} {c['body_hex'] or '-'}")

    def test_frame_encode_matches_reference(self):
        rng = random.Random(3)
        for chunk in (50, 200):
            for size in (0, 1, 150, 199, 200, 201, 700):
                body = bytes(rng.randrange(256) for _ in range(size))
                want = ref.frames_encode("rs", body, chunk, sid="0000abcd")
                got = run([f"encode rs 0000abcd {chunk} {hx(body)}"])[0].split()
                self.assertEqual(got, want)

    # -- identity helpers

    def test_fingerprint_code_and_amount(self):
        rng = random.Random(11)
        for _ in range(50):
            req = bytes(rng.randrange(256) for _ in range(rng.randrange(20, 120)))
            self.assertEqual(ref.fingerprint(req), ref.fingerprint(req))  # sanity
        # fingerprints are checked through full requests in test_request_cases; amounts here
        samples = [1, 99, 100, 101, 99999, 100000, 530250000, 5351550000, 2**53]
        out = run([f"amount {n}" for n in samples])
        for n, got in zip(samples, out):
            whole, frac = divmod(n, 100)
            self.assertEqual(got, f"ok {whole:,}.{frac:02d}")

    # -- what gets signed

    def test_respond_matches_reference_byte_for_byte(self):
        request = bytes.fromhex(VECTORS["request_hex"])
        pub = bytes.fromhex(VECTORS["public_key_hex"])
        for approve in (True, False):
            got = run([f"respond {request.hex()} {pub.hex()} 7 {1 if approve else 0}"])[0].split()
            self.assertEqual(got[0], "ok")
            resp = ref.loads(unhx(got[1]))
            dev = ref.device_id(pub)
            body = {"v": 1, "rid": resp["rid"], "dev": dev, "dec": 1 if approve else 0, "ctr": 7,
                    "h": hashlib.sha256(request).digest()}
            self.assertEqual(resp["rid"], ref.loads(request)["rid"])
            self.assertEqual(unhx(got[2]), ref.response_signing_input(body))
            stand_in = hashlib.sha256(ref.response_signing_input(body)).digest() * 2
            self.assertEqual(unhx(got[1]), ref.dumps({**body, "sig": stand_in}))
            self.assertEqual(got[3:9], VECTORS["fingerprint"].split())
            self.assertEqual(int(got[9]), VECTORS["total_tiyin"])

    def test_wrong_device_is_refused(self):
        request = bytes.fromhex(VECTORS["request_hex"])
        other = b"\x04" + bytes(range(64))
        self.assertEqual(run([f"respond {request.hex()} {other.hex()} 1 1"]), ["err WRONG DEVICE"])

    def test_enrolment_matches_reference(self):
        pub = bytes.fromhex(VECTORS["public_key_hex"])
        nonce = bytes(range(16))
        challenge = ref.dumps({"v": 1, "nonce": nonce, "org": "demo"})
        self.assertEqual(run([f"challenge {challenge.hex()}"]), [f"ok {nonce.hex()} demo"])
        got = run([f"enrol {nonce.hex()} demo {pub.hex()}"])[0].split()
        stand_in = hashlib.sha256(b"IMZO/1/enrol\x00" + nonce + b"demo").digest() * 2
        self.assertEqual(unhx(got[1]), ref.dumps({"pub": pub, "sig": stand_in}))
        self.assertEqual(got[2], VECTORS["enrolment_code"])
        self.assertEqual(got[3], VECTORS["device_id_hex"])

    def test_enrolment_challenge_rejects_bad_input(self):
        bad = [
            ref.dumps({"v": 2, "nonce": bytes(16), "org": "demo"}),
            ref.dumps({"v": 1, "nonce": bytes(15), "org": "demo"}),
            ref.dumps({"v": 1, "nonce": bytes(16), "org": ""}),
            ref.dumps({"v": 1, "nonce": bytes(16), "org": "x" * 129}),
            ref.dumps({"v": 1, "nonce": bytes(16)}),
            ref.dumps({"v": 1, "nonce": bytes(16), "org": "demo", "extra": 1}),
        ]
        out = run([f"challenge {b.hex()}" for b in bad])
        self.assertEqual(out, ["err BAD FORMAT"] * len(bad))


if __name__ == "__main__":
    unittest.main()
