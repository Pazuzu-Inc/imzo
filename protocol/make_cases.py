"""Generate cases.json: differential test inputs for ports of imzo_ref (e.g. the Kotlin approver).

Every input is built here (with imzo_ref helpers or hand-assembled bytes) and the *observed*
outcome of running the reference on it is recorded. Output is deterministic. Binary values are
lowercase hex. Run: python make_cases.py   (writes cases.json next to this file)
"""

from __future__ import annotations

import hashlib
import json
from pathlib import Path

from cryptography.hazmat.primitives.asymmetric import ec

import imzo_ref as imzo
from imzo_ref import ProtocolError

RID = bytes(range(16))
DEV = bytes(range(16, 32))
NONCE = bytes(range(32, 48))
ACCOUNT16 = "8600123412341234"


def _account(i: int, length: int = 20) -> str:
    return f"86{i:0{length - 2}d}"


def _items(n: int) -> list[list]:
    names = [
        "Aziza Karimova", "Bobur Tursunov", "Dilnoza Yusupova", "Eldor Rahimov",
        "Feruza Nazarova", "Gulnora Saidova", "Hamid Abdullaev", "Iroda Ergasheva",
        "Jasur Mirzayev", "Kamola Sobirova", "Laziz Normatov", "Madina Qodirova",
    ]
    out = []
    for i in range(n):
        name = names[i % len(names)] if n <= len(names) else f"Employee {i + 1:03d}"
        acct = _account(i + 1, 16 if i % 3 == 2 else 20)
        out.append([name, acct, 100_000_000 + i * 1_234_567])
    return out


def _req_obj(items: list[list], **over) -> dict:
    obj = {
        "v": 1, "rid": RID, "dev": DEV, "nonce": NONCE, "op": "payroll", "cur": "UZS",
        "ref": "Payroll October 2026", "items": items,
        "total": sum(i[2] for i in items if len(i) > 2), "ap": [1, 1],
    }
    obj.update(over)
    return obj


def _req_hex(items: list[list], **over) -> str:
    # dumps on a hand-built dict bypasses build_request, so parse_request does the rejecting
    return imzo.dumps(_req_obj(items, **over)).hex()


def _one(name: str = "Aziza Karimova", account: str = ACCOUNT16, amount: int = 1_000, **over) -> str:
    return _req_hex([[name, account, amount]], **over)


# --------------------------------------------------------------------------- cbor


def _cbor_case(name: str, data: bytes) -> dict:
    try:
        value = imzo.loads(data)
    except ProtocolError as exc:
        return {"name": name, "hex": data.hex(), "ok": False, "error": exc.code}
    assert imzo.dumps(value) == data
    return {"name": name, "hex": data.hex(), "ok": True}


def _cbor_cases() -> list[dict]:
    inputs: list[tuple[str, bytes]] = [
        # accepted
        ("uint 0", b"\x00"),
        ("uint 23", b"\x17"),
        ("uint 24 minimal", b"\x18\x18"),
        ("uint 255", b"\x18\xff"),
        ("uint 256", b"\x19\x01\x00"),
        ("uint 65536", b"\x1a\x00\x01\x00\x00"),
        ("uint 2**32", b"\x1b\x00\x00\x00\x01\x00\x00\x00\x00"),
        ("uint 2**64-1", b"\x1b" + b"\xff" * 8),
        ("empty byte string", b"\x40"),
        ("empty text string", b"\x60"),
        ("empty array", b"\x80"),
        ("empty map", b"\xa0"),
        ("text string", imzo.dumps("Aziza Karimova")),
        ("byte string", imzo.dumps(bytes(range(16)))),
        ("unicode text", imzo.dumps("Тошкент é")),
        ("map sorted keys", imzo.dumps({"b": 1, "a": [b"\x01", "x", 300], "ccc": {"z": 0}})),
        ("nesting depth 4", b"\x81" * 4 + b"\x00"),
        ("max size message 16384 bytes", b"\x59\x3f\xfd" + b"\x00" * (imzo.MAX_MESSAGE_BYTES - 3)),
        # rejected
        ("non-canonical int", b"\x18\x01"),
        ("non-canonical int 2 byte", b"\x19\x00\x01"),
        ("non-canonical int 4 byte", b"\x1a\x00\x00\x01\x00"),
        ("non-canonical int 8 byte", b"\x1b\x00\x00\x00\x00\x00\x00\x01\x00"),
        ("indefinite-length array", b"\x9f\x01\xff"),
        ("indefinite-length map", b"\xbf\x61\x61\x01\xff"),
        ("indefinite-length byte string", b"\x5f\x41\x00\xff"),
        ("duplicate map key", b"\xa2\x61\x61\x01\x61\x61\x02"),
        ("unsorted map keys", b"\xa2\x61\x62\x01\x61\x61\x02"),
        ("trailing byte", b"\x01\x00"),
        ("empty input", b""),
        ("nesting depth 5", b"\x81" * 5 + b"\x00"),
        ("nesting depth 10", b"\x81" * 10 + b"\x00"),
        ("invalid utf-8", b"\x62\xc3\x28"),
        ("map with integer key", b"\xa1\x01\x01"),
        ("map with byte string key", b"\xa1\x41\x61\x01"),
        ("negative int", b"\x20"),
        ("float", b"\xf9\x3c\x00"),
        ("tag", b"\xc1\x01"),
        ("simple value true", b"\xf5"),
        ("null", b"\xf6"),
        ("reserved additional info 28", b"\x1c"),
        ("truncated byte string", b"\x44\x01"),
        ("truncated 2-byte head", b"\x19\x01"),
        ("huge array header", b"\x9b" + b"\xff" * 8),
        ("huge map header", b"\xbb" + b"\xff" * 8),
        ("16385-byte string", b"\x59\x40\x01" + b"\x00" * 16385),
        ("oversize message 16385 bytes", b"\x00" * (imzo.MAX_MESSAGE_BYTES + 1)),
    ]
    return [_cbor_case(n, d) for n, d in inputs]


# --------------------------------------------------------------------------- requests


def _request_case(name: str, data: bytes) -> dict:
    try:
        req = imzo.parse_request(data)
    except ProtocolError as exc:
        return {"name": name, "hex": data.hex(), "error": exc.code}
    return {
        "name": name,
        "hex": data.hex(),
        "total": sum(i[2] for i in req["items"]),
        "count": len(req["items"]),
        "fingerprint": imzo.fingerprint(data),
    }


def _request_cases() -> list[dict]:
    payroll12 = _items(12)
    valid_hex = _req_hex(payroll12)
    inputs: list[tuple[str, bytes]] = []

    def add(name: str, hex_text: str) -> None:
        inputs.append((name, bytes.fromhex(hex_text)))

    add("valid 12-person payroll", valid_hex)
    add("valid 50-item", _req_hex(_items(50)))
    add("valid 1 item", _one())
    add("valid built by build_request", imzo.build_request(
        rid=RID, dev=DEV, nonce=NONCE, op="transfer", ref="Supplier invoice 42",
        items=[["Tashkent Textile LLC", "86001111222233334444", 7_500_000_000]], ap=(2, 3)).hex())
    for op in ("payroll", "transfer", "beneficiary", "limit"):
        add(f"valid op {op}", _one(op=op))
    add("valid amount 2**53", _one(amount=2**53))
    add("valid amount 1", _one(amount=1))
    add("valid ap [5, 5]", _one(ap=[5, 5]))
    add("valid ref 64 bytes", _one(ref="r" * 64))
    add("valid name 32 two-byte chars (64 bytes)", _one(name="ж" * 32))
    add("name of exactly 64 bytes", _one(name="N" * 64))
    add("valid account of 16 digits", _one(account="1234567890123456"))
    add("valid account of 20 digits", _one(account="12345678901234567890"))

    add("51 items", _req_hex(_items(51)))
    add("0 items", _req_hex([]))
    add("total off by one", _req_hex(payroll12, total=sum(i[2] for i in payroll12) + 1))
    add("total off by minus one", _req_hex(payroll12, total=sum(i[2] for i in payroll12) - 1))
    add("total is text", _one(total="1000"))
    add("account of 15 digits", _one(account="860012341234123"))
    add("account of 17 digits", _one(account="86001234123412341"))
    add("account of 20 digits with a letter", _one(account="8600123412341234567A"))
    add("account with space", _one(account="8600 123412341234"))
    add("account with arabic-indic digit", _one(account="860012341234123٤"))
    add("account is empty", _one(account=""))
    add("amount 0", _one(amount=0))
    add("amount 2**53+1", _one(amount=2**53 + 1))
    add("name in NFD form", _one(name="José Garcia"))
    add("name in NFC form", _one(name="José Garcia"))
    add("name containing U+202E", _one(name="Aziza‮Karimova"))
    add("name containing unassigned U+0378", _one(name="Aziza͸Karimova"))
    add("name containing newline", _one(name="Aziza\nKarimova"))
    add("name containing NUL", _one(name="Aziza\x00Karimova"))
    add("name empty", _one(name=""))
    add("name of 65 bytes", _one(name="N" * 65))
    add("name of 33 two-byte chars (66 bytes)", _one(name="ж" * 33))
    add("ref empty", _one(ref=""))
    add("ref of 65 bytes", _one(ref="r" * 65))
    add("ref in NFD form", _one(ref="Café"))
    add("ap [2, 1]", _one(ap=[2, 1]))
    add("ap [0, 1]", _one(ap=[0, 1]))
    add("ap [1, 6]", _one(ap=[1, 6]))
    add("ap of three", _one(ap=[1, 1, 1]))
    add("unknown op", _one(op="refund"))
    add("unknown currency", _one(cur="USD"))
    add("version 2", _one(v=2))
    add("rid of 15 bytes", _one(rid=bytes(15)))
    add("dev of 17 bytes", _one(dev=bytes(17)))
    add("nonce as text", _one(nonce="0123456789abcdef"))
    add("item with two fields", _req_hex([["Aziza Karimova", ACCOUNT16]], total=0))
    add("item with four fields", _req_hex([["Aziza Karimova", ACCOUNT16, 1, 2]], total=1))
    add("extra field", imzo.dumps({**_req_obj(_items(1)), "x": 1}).hex())
    obj_missing = _req_obj(_items(1))
    del obj_missing["nonce"]
    add("missing field", imzo.dumps(obj_missing).hex())
    add("not a map", imzo.dumps([1, 2, 3]).hex())
    add("empty input", "")
    # reference rejects at the CBOR layer before validate_request
    add("valid request plus trailing byte", valid_hex + "00")
    add("valid request truncated by one byte", valid_hex[:-2])
    return [_request_case(n, d) for n, d in inputs]


# --------------------------------------------------------------------------- frames


def _frame_case(name: str, frames: list[str]) -> dict:
    try:
        kind, body = imzo.frames_decode(frames)
    except ProtocolError as exc:
        return {"name": name, "frames": frames, "error": exc.code}
    return {"name": name, "frames": frames, "kind": kind, "body_hex": body.hex()}


def _flip(frame: str) -> str:
    head, data = frame.rsplit(".", 1)
    i = len(data) // 2
    return f"{head}.{data[:i]}{'B' if data[i] == 'A' else 'A'}{data[i + 1:]}"


def _frame_cases() -> list[dict]:
    request = bytes.fromhex(_req_hex(_items(18)))
    five = imzo.frames_encode("rq", request, 200, sid="00000001")
    assert len(five) == 5, len(five)
    other = imzo.frames_encode("rq", request, 200, sid="00000002")
    one = imzo.frames_encode("rq", bytes.fromhex(_one()), 200, sid="0000000a")
    response = imzo.frames_encode("rs", b"\x01\x02\x03", 200, sid="0000000b")
    chunk_small = imzo.frames_encode("rq", request, 50, sid="0000000c")
    # 3-byte bodies give 7-byte payloads: 10 base64 chars with 4 trailing bits (canonical: all zero)
    dash = imzo.frames_encode("rq", b"\x00\x00\x02", 200, sid="00000010")[0]
    under = imzo.frames_encode("rq", b"\x00\x00\x00", 200, sid="00000011")[0]
    plain = imzo.frames_encode("rq", b"abc", 200, sid="00000012")[0]
    assert dash.endswith("AAACEU-4Pg") and under.endswith("AAAA_0HZEg") and plain.endswith("g")
    head = plain.rsplit(".", 1)[0]
    data = plain.rsplit(".", 1)[1]
    tail = five[0].rsplit(".", 1)[0].replace(".1.5", ".257.257")

    inputs: list[tuple[str, list[str]]] = [
        ("valid 5-frame request", five),
        ("valid single frame request", one),
        ("valid response kind rs", response),
        ("valid enrolment kinds en", imzo.frames_encode("en", b"hello", 200, sid="0000000d")),
        ("valid enrolment kinds er", imzo.frames_encode("er", b"hello", 200, sid="0000000e")),
        ("valid small chunks", chunk_small),
        ("valid sid with dots-free text", imzo.frames_encode("rq", b"abc", 200, sid="s")),
        ("shuffled with repeats", [five[i] for i in (2, 0, 4, 4, 1, 3, 0, 2)]),
        ("reversed order", list(reversed(five))),
        ("flipped base64 character", [five[0], _flip(five[1]), *five[2:]]),
        ("missing frame", [five[0], five[1], five[3], five[4]]),
        ("only first frame", [five[0]]),
        ("no frames", []),
        ("two sessions mixed", [five[0], other[1], five[2], five[3], five[4]]),
        ("different totals mixed", [one[0], five[1]]),
        ("different kinds mixed", [five[0], response[0]]),
        ("index 257 of 257", [tail + ".AAAA"]),
        ("index 0 of 1", ["IMQ1.rq.00000001.0.1.AAAA"]),
        ("index 6 of 5", [*five[:4], five[4].replace(".5.5.", ".6.5.")]),
        ("count 256 incomplete", ["IMQ1.rq.00000001.1.256.AAAA"]),
        ("wrong magic", ["IMQ2" + five[0][4:]]),
        ("unknown kind", [five[0].replace("IMQ1.rq.", "IMQ1.xx.")]),
        ("too few pieces", ["IMQ1.rq.00000001.1.1"]),
        ("non-digit index", ["IMQ1.rq.00000001.a.1.AAAA"]),
        ("negative index", ["IMQ1.rq.00000001.-1.1.AAAA"]),
        ("payload shorter than crc", ["IMQ1.rq.00000001.1.1.AAA"]),
        ("bad base64 length", ["IMQ1.rq.00000001.1.1.A"]),
        ("empty data", ["IMQ1.rq.00000001.1.1."]),
        # strictness: each of these decodes fine in a lenient base64 decoder, so they pin the strict rules
        ("base64 plus instead of dash", [dash.replace("-", "+")]),
        ("base64 slash instead of underscore", [under.replace("_", "/")]),
        ("base64 with padding", [plain + "="] if len(data) % 4 == 3 else [plain + "=="]),
        ("base64 with embedded space", [f"{head}.{data[:4]} {data[4:]}"]),
        ("base64 with out-of-alphabet char", [f"{head}.{data[:4]}*{data[4:]}"]),
        ("base64 with newline", [f"{head}.{data[:4]}\n{data[4:]}"]),
        ("base64 non-canonical trailing bits", [f"{head}.{data[:-1]}{chr(ord(data[-1]) + 1)}"]),
        ("arabic-indic digit counters", ["IMQ1.rq.00000013.\u0661.\u0661." + data]),
        ("arabic-indic digit index only", [f"IMQ1.rq.00000013.\u0661.1.{data}"]),
        ("superscript digit counters", ["IMQ1.rq.00000013.\u00b2.\u00b2." + data]),
        ("crc of empty body", imzo.frames_encode("rq", b"", 200, sid="0000000f")),
    ]
    return [_frame_case(n, f) for n, f in inputs]


# --------------------------------------------------------------------------- output


# --------------------------------------------------------------------------- Kotlin cross-check

P256_ORDER = 0xFFFFFFFF00000000FFFFFFFFFFFFFFFFBCE6FAADA7179E84F3B9CAC2FC632551


def _cross_cases() -> dict:
    """A key and a request addressed to it, for the Kotlin approver to answer (see test_kotlin_cross.py).

    The scalar is derived the same way make_vectors derives its key, from a different label."""
    secret = int.from_bytes(hashlib.sha256(b"imzo-kotlin-cross-key").digest(), "big") % (P256_ORDER - 1) + 1
    key = ec.derive_private_key(secret, ec.SECP256R1())
    request = imzo.build_request(
        rid=bytes(range(0x40, 0x50)), dev=imzo.device_id(imzo.public_bytes(key)),
        nonce=bytes(range(0x50, 0x60)), op="payroll", ref="Cross-check payroll",
        items=[
            ["Aziza Karimova", "86000000000000000001", 450_000_000],
            ["Bobur Tursunov", "86000000000000000002", 520_000_000],
            ["Dilnoza Yusupova", "8600123412341234", 380_050_000],
        ],
        ap=(1, 1),
    )
    return {"cross_key_secret_hex": secret.to_bytes(32, "big").hex(), "cross_request_hex": request.hex()}


def build_cases() -> dict:
    return {
        "cbor": _cbor_cases(), "requests": _request_cases(), "frames": _frame_cases(), **_cross_cases(),
    }


def main() -> None:
    cases = build_cases()
    path = Path(__file__).with_name("cases.json")
    path.write_text(json.dumps(cases, indent=1, ensure_ascii=True) + "\n")
    print(
        f"wrote {path.name}: {len(cases['cbor'])} cbor, "
        f"{len(cases['requests'])} requests, {len(cases['frames'])} frames"
    )


if __name__ == "__main__":
    main()
