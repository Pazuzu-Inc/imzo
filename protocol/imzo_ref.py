"""Imzo protocol v1: reference implementation (hackathon draft).

Not security reviewed. Its job is to give the backend and mobile developers one
executable definition of the protocol and shared test vectors.

Layers:
  1. Deterministic CBOR encoder and a strict decoder (rejects anything non-canonical)
  2. Order request, approval response, fingerprint, enrolment code
  3. Device (review + sign) and Server (verify + maker-checker + audit chain)
  4. IMQ1 animated-QR framing

Only dependency: `cryptography` (ECDSA P-256, SHA-256).
"""

from __future__ import annotations

import base64
import hashlib
import json
import os
import struct
import time
import unicodedata
import zlib
from dataclasses import dataclass, field
from typing import Callable, Iterable

from cryptography.exceptions import InvalidSignature
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import ec
from cryptography.hazmat.primitives.asymmetric.utils import (
    decode_dss_signature,
    encode_dss_signature,
)

# --------------------------------------------------------------------------- limits

DOMAIN_APPROVE = b"IMZO/1/approve\x00"
MAX_ITEMS = 50
MAX_NAME_BYTES = 64
MAX_REF_BYTES = 64
MAX_APPROVERS = 5
MAX_MESSAGE_BYTES = 16 * 1024
MAX_DEPTH = 4
MAX_AMOUNT = 2**53
OPS = frozenset({"payroll", "transfer", "beneficiary", "limit"})
CURRENCIES = frozenset({"UZS"})

REQUEST_KEYS = frozenset(
    {"v", "rid", "dev", "nonce", "op", "cur", "ref", "items", "total", "ap"}
)
RESPONSE_KEYS = frozenset({"v", "rid", "dev", "dec", "ctr", "h", "sig"})


class ProtocolError(Exception):
    """Raised with a short, device-screen-friendly code such as BAD FORMAT."""

    def __init__(self, code: str, detail: str = ""):
        super().__init__(f"{code}: {detail}" if detail else code)
        self.code = code
        self.detail = detail


# --------------------------------------------------------------------------- CBOR


def _head(major: int, n: int) -> bytes:
    if n < 24:
        return bytes([(major << 5) | n])
    if n < 0x100:
        return bytes([(major << 5) | 24, n])
    if n < 0x10000:
        return bytes([(major << 5) | 25]) + n.to_bytes(2, "big")
    if n < 0x100000000:
        return bytes([(major << 5) | 26]) + n.to_bytes(4, "big")
    if n < 2**64:
        return bytes([(major << 5) | 27]) + n.to_bytes(8, "big")
    raise ValueError("integer too large")


def dumps(obj) -> bytes:
    """Deterministic CBOR (RFC 8949 core deterministic form) for the types we use:
    unsigned ints, byte strings, text strings, arrays, maps with text keys."""
    if isinstance(obj, bool):
        raise TypeError("booleans are not used in Imzo messages")
    if isinstance(obj, int):
        if obj < 0:
            raise TypeError("negative integers are not used in Imzo messages")
        return _head(0, obj)
    if isinstance(obj, (bytes, bytearray)):
        return _head(2, len(obj)) + bytes(obj)
    if isinstance(obj, str):
        raw = obj.encode("utf-8")
        return _head(3, len(raw)) + raw
    if isinstance(obj, (list, tuple)):
        return _head(4, len(obj)) + b"".join(dumps(x) for x in obj)
    if isinstance(obj, dict):
        pairs = sorted(((dumps(k), dumps(v)) for k, v in obj.items()), key=lambda kv: kv[0])
        return _head(5, len(pairs)) + b"".join(k + v for k, v in pairs)
    raise TypeError(f"unsupported type {type(obj).__name__}")


def _read_head(data: bytes, i: int) -> tuple[int, int, int]:
    if i >= len(data):
        raise ProtocolError("BAD FORMAT", "truncated")
    b = data[i]
    major, ai = b >> 5, b & 31
    i += 1
    if ai < 24:
        return major, ai, i
    if ai in (24, 25, 26, 27):
        size = {24: 1, 25: 2, 26: 4, 27: 8}[ai]
        if i + size > len(data):
            raise ProtocolError("BAD FORMAT", "truncated")
        return major, int.from_bytes(data[i : i + size], "big"), i + size
    raise ProtocolError("BAD FORMAT", "indefinite or reserved length")


def _decode(data: bytes, i: int, depth: int):
    if depth > MAX_DEPTH:
        raise ProtocolError("BAD FORMAT", "nesting too deep")
    major, n, i = _read_head(data, i)
    if major == 0:
        return n, i
    if major in (2, 3):
        end = i + n
        if end > len(data):
            raise ProtocolError("BAD FORMAT", "truncated")
        raw = bytes(data[i:end])
        if major == 2:
            return raw, end
        try:
            return raw.decode("utf-8"), end
        except UnicodeDecodeError as exc:
            raise ProtocolError("BAD FORMAT", "invalid utf-8") from exc
    if major == 4:
        if n > len(data) - i:
            raise ProtocolError("BAD FORMAT", "array longer than message")
        out = []
        for _ in range(n):
            v, i = _decode(data, i, depth + 1)
            out.append(v)
        return out, i
    if major == 5:
        if n > len(data) - i:
            raise ProtocolError("BAD FORMAT", "map longer than message")
        out = {}
        for _ in range(n):
            k, i = _decode(data, i, depth + 1)
            if not isinstance(k, str):
                raise ProtocolError("BAD FORMAT", "map keys must be text")
            if k in out:
                raise ProtocolError("BAD FORMAT", "duplicate key")
            v, i = _decode(data, i, depth + 1)
            out[k] = v
        return out, i
    raise ProtocolError("BAD FORMAT", f"unsupported major type {major}")


def loads(data: bytes):
    """Strict decode: bounded size, no trailing bytes, canonical form only."""
    data = bytes(data)
    if len(data) > MAX_MESSAGE_BYTES:
        raise ProtocolError("BAD FORMAT", "message too large")
    value, end = _decode(data, 0, 0)
    if end != len(data):
        raise ProtocolError("BAD FORMAT", "trailing bytes")
    if dumps(value) != data:
        raise ProtocolError("BAD FORMAT", "not canonical")
    return value


# --------------------------------------------------------------------------- helpers

_CONSONANTS = "bdfgklmnprstvzjh"  # 16
_VOWELS = "aeiu"  # 4


def _syllable(x: int) -> str:
    return _CONSONANTS[x >> 2] + _VOWELS[x & 3]


def fingerprint(canonical_request: bytes) -> str:
    """Six pronounceable two-syllable words (72 bits) from SHA-256 of the request.
    Used so the approver can compare two screens; needs no word list."""
    n = int.from_bytes(hashlib.sha256(canonical_request).digest()[:9], "big")
    words = []
    for i in range(6):
        chunk = (n >> (72 - 12 * (i + 1))) & 0xFFF
        words.append(_syllable(chunk >> 6) + _syllable(chunk & 63))
    return " ".join(words)


def enrolment_code(public_key: bytes) -> str:
    """Six digits derived from the public key; both screens must show the same."""
    return f"{int.from_bytes(hashlib.sha256(public_key).digest()[:4], 'big') % 1_000_000:06d}"


def device_id(public_key: bytes) -> bytes:
    return hashlib.sha256(public_key).digest()[:16]


def raw_from_der(der: bytes) -> bytes:
    r, s = decode_dss_signature(der)
    return r.to_bytes(32, "big") + s.to_bytes(32, "big")


def der_from_raw(raw: bytes) -> bytes:
    if len(raw) != 64:
        raise ProtocolError("BAD FORMAT", "signature must be 64 bytes")
    return encode_dss_signature(int.from_bytes(raw[:32], "big"), int.from_bytes(raw[32:], "big"))


def public_bytes(key: ec.EllipticCurvePrivateKey | ec.EllipticCurvePublicKey) -> bytes:
    pub = key.public_key() if isinstance(key, ec.EllipticCurvePrivateKey) else key
    return pub.public_bytes(serialization.Encoding.X962, serialization.PublicFormat.UncompressedPoint)


def load_public(raw: bytes) -> ec.EllipticCurvePublicKey:
    return ec.EllipticCurvePublicKey.from_encoded_point(ec.SECP256R1(), raw)


# --------------------------------------------------------------------------- messages


def _is_digits(s: str) -> bool:
    return len(s) > 0 and all("0" <= c <= "9" for c in s)


def _check_text(s: str, max_bytes: int, what: str) -> None:
    if not isinstance(s, str) or not s:
        raise ProtocolError("BAD FORMAT", f"{what} must be non-empty text")
    try:
        size = len(s.encode("utf-8"))
    except UnicodeEncodeError:  # lone surrogate
        raise ProtocolError("BAD FORMAT", f"{what} has control characters") from None
    if size > max_bytes:
        raise ProtocolError("BAD FORMAT", f"{what} too long")
    if unicodedata.normalize("NFC", s) != s:
        raise ProtocolError("BAD FORMAT", f"{what} not NFC")
    if any(unicodedata.category(c).startswith("C") for c in s):
        raise ProtocolError("BAD FORMAT", f"{what} has control characters")


def build_request(
    *,
    rid: bytes,
    dev: bytes,
    nonce: bytes,
    op: str,
    ref: str,
    items: list[list],
    ap: tuple[int, int] = (1, 1),
    cur: str = "UZS",
) -> bytes:
    """Server side: canonical request bytes. `items` are [name, account, amount_tiyin]."""
    total = sum(i[2] for i in items)
    obj = {
        "v": 1, "rid": rid, "dev": dev, "nonce": nonce, "op": op, "cur": cur,
        "ref": ref, "items": [list(i) for i in items], "total": total, "ap": list(ap),
    }
    validate_request(obj)
    return dumps(obj)


def validate_request(obj) -> dict:
    if not isinstance(obj, dict) or set(obj) != REQUEST_KEYS:
        raise ProtocolError("BAD FORMAT", "wrong or missing fields")
    if obj["v"] != 1:
        raise ProtocolError("BAD FORMAT", "unsupported version")
    for key in ("rid", "dev", "nonce"):
        if not isinstance(obj[key], bytes) or len(obj[key]) != 16:
            raise ProtocolError("BAD FORMAT", f"{key} must be 16 bytes")
    if obj["op"] not in OPS:
        raise ProtocolError("BAD FORMAT", "unknown op")
    if obj["cur"] not in CURRENCIES:
        raise ProtocolError("BAD FORMAT", "unsupported currency")
    _check_text(obj["ref"], MAX_REF_BYTES, "ref")
    items = obj["items"]
    if not isinstance(items, list) or not 1 <= len(items) <= MAX_ITEMS:
        raise ProtocolError("BAD FORMAT", "item count out of range")
    total = 0
    for it in items:
        if not isinstance(it, list) or len(it) != 3:
            raise ProtocolError("BAD FORMAT", "item must be [name, account, amount]")
        name, account, amt = it
        _check_text(name, MAX_NAME_BYTES, "name")
        if not isinstance(account, str) or not _is_digits(account) or len(account) not in (16, 20):
            raise ProtocolError("BAD FORMAT", "account must be 16 or 20 digits")
        if not isinstance(amt, int) or not 1 <= amt <= MAX_AMOUNT:
            raise ProtocolError("BAD FORMAT", "amount out of range")
        total += amt
    if not isinstance(obj["total"], int) or obj["total"] != total:
        raise ProtocolError("TOTAL MISMATCH", f"claimed {obj['total']}, computed {total}")
    ap = obj["ap"]
    if (
        not isinstance(ap, list) or len(ap) != 2
        or not all(isinstance(x, int) for x in ap)
        or not 1 <= ap[0] <= ap[1] <= MAX_APPROVERS
    ):
        raise ProtocolError("BAD FORMAT", "ap must be [k, n]")
    return obj


def parse_request(data: bytes) -> dict:
    return validate_request(loads(data))


def validate_response(obj) -> dict:
    if not isinstance(obj, dict) or set(obj) != RESPONSE_KEYS:
        raise ProtocolError("BAD FORMAT", "wrong or missing fields")
    if obj["v"] != 1 or obj["dec"] not in (0, 1):
        raise ProtocolError("BAD FORMAT", "bad version or decision")
    for key, size in (("rid", 16), ("dev", 16), ("h", 32), ("sig", 64)):
        if not isinstance(obj[key], bytes) or len(obj[key]) != size:
            raise ProtocolError("BAD FORMAT", f"{key} must be {size} bytes")
    if not isinstance(obj["ctr"], int) or not 1 <= obj["ctr"] <= MAX_AMOUNT:
        raise ProtocolError("BAD FORMAT", "counter out of range")
    return obj


def response_signing_input(body_without_sig: dict) -> bytes:
    return DOMAIN_APPROVE + dumps(body_without_sig)


# --------------------------------------------------------------------------- device


@dataclass
class Review:
    """What the device shows. Every field is derived from the signed bytes."""

    rid: bytes
    h: bytes
    fingerprint: str
    op: str
    ref: str
    total: int
    count: int
    items: list[list]
    approval: tuple[int, int]


class Device:
    """The approver. In the Android app the key lives in the Keystore; here it is an object."""

    def __init__(self, private_key: ec.EllipticCurvePrivateKey | None = None, counter: int = 0):
        self._key = private_key or ec.generate_private_key(ec.SECP256R1())
        self.counter = counter

    @property
    def public_key(self) -> bytes:
        return public_bytes(self._key)

    @property
    def id(self) -> bytes:
        return device_id(self.public_key)

    def enrolment_answer(self, nonce: bytes, org: str) -> dict:
        """Proof of possession over the server's challenge."""
        msg = b"IMZO/1/enrol\x00" + nonce + org.encode("utf-8")
        return {"pub": self.public_key, "sig": self._sign(msg)}

    def review(self, request_bytes: bytes) -> Review:
        req = parse_request(request_bytes)
        if req["dev"] != self.id:
            raise ProtocolError("WRONG DEVICE")
        return Review(
            rid=req["rid"],
            h=hashlib.sha256(request_bytes).digest(),
            fingerprint=fingerprint(request_bytes),
            op=req["op"],
            ref=req["ref"],
            total=sum(i[2] for i in req["items"]),  # recomputed, never trusted
            count=len(req["items"]),
            items=req["items"],
            approval=(req["ap"][0], req["ap"][1]),
        )

    def respond(self, review: Review, approve: bool) -> bytes:
        self.counter += 1  # a real device stores this BEFORE showing the answer
        body = {
            "v": 1, "rid": review.rid, "dev": self.id, "dec": 1 if approve else 0,
            "ctr": self.counter, "h": review.h,
        }
        sig = self._sign(response_signing_input(body))
        return dumps({**body, "sig": sig})

    def _sign(self, message: bytes) -> bytes:
        return raw_from_der(self._key.sign(message, ec.ECDSA(hashes.SHA256())))


# --------------------------------------------------------------------------- server


@dataclass
class _Request:
    rid: bytes
    order_id: str
    dev: bytes
    canonical: bytes
    expires_at: float
    status: str = "PENDING"  # PENDING, APPROVED, REJECTED, EXPIRED
    approver: str = ""


@dataclass
class _Order:
    order_id: str
    maker: str
    items: list[list]
    op: str
    ref: str
    requests: list[bytes] = field(default_factory=list)
    status: str = "PENDING"  # PENDING, EXECUTED, REJECTED, EXPIRED


class Server:
    """Approval service: stores orders, verifies answers, runs maker-checker, keeps a hash-chained audit log."""

    LOCK_AFTER_FAILURES = 5
    ENROL_TTL = 300  # seconds a pending enrolment challenge stays valid
    MAX_PENDING_ENROLMENTS = 32

    def __init__(self, now: Callable[[], float] = time.time):
        self._now = now
        self.devices: dict[bytes, dict] = {}
        self.requests: dict[bytes, _Request] = {}
        self.orders: dict[str, _Order] = {}
        self.audit: list[dict] = []
        self.executed: list[dict] = []  # what the mock core received
        self._pending_enrol: dict[bytes, dict] = {}  # nonce -> {user, org, expires_at}

    # -- devices

    def register_device(self, public_key: bytes, user: str) -> bytes:
        load_public(public_key)  # raises if not a valid P-256 point
        dev = device_id(public_key)
        if dev in self.devices:  # never reset counter, failures or status of a known device
            raise ProtocolError("ALREADY ENROLLED")
        self.devices[dev] = {"pub": public_key, "user": user, "status": "ACTIVE", "last_ctr": 0, "fails": 0}
        self._log("device_registered", dev=dev, user=user)
        return dev

    def begin_enrolment(self, user: str, org: str = "demo") -> tuple[bytes, bytes]:
        """Issue a single-use challenge. Returns (nonce, body of the `en` message)."""
        now = self._now()
        self._pending_enrol = {n: p for n, p in self._pending_enrol.items() if p["expires_at"] >= now}
        while len(self._pending_enrol) >= self.MAX_PENDING_ENROLMENTS:
            self._pending_enrol.pop(next(iter(self._pending_enrol)))  # drop the oldest
        nonce = os.urandom(16)
        self._pending_enrol[nonce] = {"user": user, "org": org, "expires_at": now + self.ENROL_TTL}
        self._log("enrolment_started", user=user, org=org)
        return nonce, dumps({"v": 1, "nonce": nonce, "org": org})

    def finish_enrolment(self, answer_bytes: bytes) -> bytes:
        """Verify the proof of possession from Device.enrolment_answer and register the device.

        The answer does not name its nonce, so it is checked against each pending challenge;
        the one it verifies against is consumed (single use). Returns the device id."""
        obj = loads(answer_bytes)
        if not isinstance(obj, dict) or set(obj) != {"pub", "sig"}:
            raise ProtocolError("BAD FORMAT", "wrong or missing fields")
        pub, sig = obj["pub"], obj["sig"]
        if not isinstance(pub, bytes) or not isinstance(sig, bytes) or len(sig) != 64:
            raise ProtocolError("BAD FORMAT", "pub and sig must be bytes")
        try:
            key = load_public(pub)
        except ValueError as exc:
            raise ProtocolError("BAD FORMAT", "invalid public key") from exc
        der = der_from_raw(sig)
        now = self._now()
        for nonce, pending in list(self._pending_enrol.items()):
            if pending["expires_at"] < now:
                del self._pending_enrol[nonce]
                continue
            msg = b"IMZO/1/enrol\x00" + nonce + pending["org"].encode("utf-8")
            try:
                key.verify(der, msg, ec.ECDSA(hashes.SHA256()))
            except InvalidSignature:
                continue
            del self._pending_enrol[nonce]  # consumed even if the enrolment is refused below
            if device_id(pub) not in self.devices and any(
                d["user"] == pending["user"] and d["status"] in ("ACTIVE", "LOCKED") for d in self.devices.values()
            ):
                # one live (ACTIVE or LOCKED) device per user, so neither a later enrolment nor locking the device
                # by forged answers lets someone take over a user's orders; only REVOKED (or a reset) frees the slot
                raise ProtocolError("USER ALREADY HAS A DEVICE")
            return self.register_device(pub, pending["user"])
        raise ProtocolError("BAD SIGNATURE")

    def revoke_device(self, dev: bytes) -> None:
        self.devices[dev]["status"] = "REVOKED"
        self._log("device_revoked", dev=dev)

    # -- orders

    def create_order(
        self, *, order_id: str, maker: str, op: str, ref: str, items: list[list], approvers: list[bytes], ttl: int = 300
    ) -> dict[bytes, bytes]:
        """One request per approver device, same items, own rid/nonce. Returns {dev: request bytes}."""
        if not 1 <= len(approvers) <= MAX_APPROVERS:
            raise ProtocolError("BAD FORMAT", "approver count out of range")
        order = _Order(order_id=order_id, maker=maker, items=[list(i) for i in items], op=op, ref=ref)
        out: dict[bytes, bytes] = {}
        for k, dev in enumerate(approvers, start=1):
            rid = os.urandom(16)
            data = build_request(
                rid=rid, dev=dev, nonce=os.urandom(16), op=op, ref=ref, items=items, ap=(k, len(approvers))
            )
            self.requests[rid] = _Request(rid, order_id, dev, data, self._now() + ttl)
            order.requests.append(rid)
            out[dev] = data
        self.orders[order_id] = order
        self._log("order_created", order=order_id, maker=maker, approvers=len(approvers))
        return out

    def submit_response(self, response_bytes: bytes) -> str:
        """Verify one device answer. Returns the order status after processing."""
        try:
            resp = validate_response(loads(response_bytes))
            req = self.requests.get(resp["rid"])
            if req is None:
                raise ProtocolError("UNKNOWN ORDER")
            order = self.orders[req.order_id]
            dev = self.devices.get(resp["dev"])
            if dev is None or dev["status"] != "ACTIVE":
                raise ProtocolError("DEVICE NOT ACTIVE")
            if req.dev != resp["dev"]:
                raise ProtocolError("WRONG DEVICE")
            if req.status != "PENDING" or order.status != "PENDING":
                raise ProtocolError("NOT PENDING")
            if self._now() > req.expires_at:
                req.status = "EXPIRED"
                self._maybe_expire(order)
                raise ProtocolError("EXPIRED")
            if resp["h"] != hashlib.sha256(req.canonical).digest():
                raise ProtocolError("HASH MISMATCH")
            body = {k: resp[k] for k in ("v", "rid", "dev", "dec", "ctr", "h")}
            try:
                load_public(dev["pub"]).verify(
                    der_from_raw(resp["sig"]), response_signing_input(body), ec.ECDSA(hashes.SHA256())
                )
            except InvalidSignature as exc:
                raise ProtocolError("BAD SIGNATURE") from exc
            if resp["ctr"] <= dev["last_ctr"]:
                raise ProtocolError("COUNTER")
            if dev["user"] == order.maker:
                raise ProtocolError("MAKER CANNOT APPROVE")
        except ProtocolError as exc:
            self._record_failure(response_bytes, exc.code)
            raise

        dev["last_ctr"] = resp["ctr"]
        dev["fails"] = 0
        req.approver = dev["user"]
        if resp["dec"] == 0:
            req.status = "REJECTED"
            order.status = "REJECTED"
            self._log("order_rejected", order=order.order_id, dev=resp["dev"])
            return order.status
        req.status = "APPROVED"
        self._log("approval_recorded", order=order.order_id, dev=resp["dev"], user=dev["user"])
        approvers = [self.requests[r].approver for r in order.requests if self.requests[r].status == "APPROVED"]
        if len(approvers) == len(order.requests) and len(set(approvers)) == len(approvers):
            order.status = "EXECUTED"
            # Execute ONLY the stored items. Nothing from the client is used.
            self.executed.append({"order": order.order_id, "op": order.op, "items": order.items})
            self._log("order_executed", order=order.order_id)
        return order.status

    # -- audit

    def _maybe_expire(self, order: _Order) -> None:
        if all(self.requests[r].status in ("EXPIRED", "REJECTED") for r in order.requests):
            order.status = "EXPIRED"

    def _record_failure(self, response_bytes: bytes, code: str) -> None:
        dev_id = None
        try:
            obj = loads(response_bytes)
            if isinstance(obj, dict) and isinstance(obj.get("dev"), bytes):
                dev_id = obj["dev"]
        except ProtocolError:
            pass
        self._log("verification_failed", code=code, dev=dev_id or b"")
        if dev_id in self.devices:
            d = self.devices[dev_id]
            d["fails"] += 1
            if d["fails"] >= self.LOCK_AFTER_FAILURES and d["status"] == "ACTIVE":
                d["status"] = "LOCKED"
                self._log("device_locked", dev=dev_id)

    def _log(self, kind: str, **fields) -> None:
        prev = self.audit[-1]["hash"] if self.audit else b"\x00" * 32
        event = {"kind": kind, "t": int(self._now()), **{k: v for k, v in fields.items()}}
        body = dumps({k: (v if isinstance(v, (bytes, int, str)) else str(v)) for k, v in event.items()})
        self.audit.append({"event": event, "prev": prev, "hash": hashlib.sha256(prev + body).digest(), "_body": body})

    def verify_audit(self) -> bool:
        prev = b"\x00" * 32
        for row in self.audit:
            if row["prev"] != prev or row["hash"] != hashlib.sha256(prev + row["_body"]).digest():
                return False
            prev = row["hash"]
        return True


# --------------------------------------------------------------------------- IMQ1 framing


def _b64u(raw: bytes) -> str:
    return base64.urlsafe_b64encode(raw).rstrip(b"=").decode("ascii")


_B64U_ALPHABET = frozenset("ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_")


def _unb64u(text: str) -> bytes:
    """Strict unpadded base64url: only [A-Za-z0-9_-], canonical trailing bits (so one encoding per value)."""
    if not all(c in _B64U_ALPHABET for c in text):
        raise ProtocolError("BAD FORMAT", "bad base64")
    try:
        raw = base64.urlsafe_b64decode(text + "=" * (-len(text) % 4))
    except Exception as exc:  # noqa: BLE001 - e.g. impossible length
        raise ProtocolError("BAD FORMAT", "bad base64") from exc
    if _b64u(raw) != text:
        raise ProtocolError("BAD FORMAT", "bad base64")
    return raw


KINDS = ("rq", "rs", "en", "er")


def frames_encode(kind: str, body: bytes, chunk: int = 200, sid: str | None = None) -> list[str]:
    """Split body (+ CRC32) into frames `IMQ1.<kind>.<sid>.<i>.<n>.<chunk>`; show them in a loop."""
    if kind not in KINDS:
        raise ValueError("unknown kind")
    payload = body + struct.pack(">I", zlib.crc32(body))
    parts = [payload[i : i + chunk] for i in range(0, len(payload), chunk)]
    sid = sid or os.urandom(4).hex()
    return [f"IMQ1.{kind}.{sid}.{i + 1}.{len(parts)}.{_b64u(p)}" for i, p in enumerate(parts)]


def frames_decode(frames: Iterable[str]) -> tuple[str, bytes]:
    """Reassemble from frames in any order, with repeats. Raises INCOMPLETE until every part is present."""
    kind = sid = None
    total = 0
    parts: dict[int, bytes] = {}
    for frame in frames:
        pieces = frame.split(".", 5)
        if len(pieces) != 6 or pieces[0] != "IMQ1" or pieces[1] not in KINDS:
            raise ProtocolError("BAD FORMAT", "not an IMQ1 frame")
        _, k, s, i_text, n_text, data = pieces
        if not all(t.isascii() and t.isdigit() for t in (i_text, n_text)):
            raise ProtocolError("BAD FORMAT", "bad frame counters")
        i, n = int(i_text), int(n_text)
        if not 1 <= i <= n <= 256:
            raise ProtocolError("BAD FORMAT", "frame index out of range")
        if kind is None:
            kind, sid, total = k, s, n
        elif (k, s, n) != (kind, sid, total):
            raise ProtocolError("BAD FORMAT", "frames from different messages")
        parts[i] = _unb64u(data)
    if kind is None or len(parts) != total:
        raise ProtocolError("INCOMPLETE")
    payload = b"".join(parts[i] for i in range(1, total + 1))
    if len(payload) < 4:
        raise ProtocolError("BAD FORMAT", "too short")
    body, crc = payload[:-4], struct.unpack(">I", payload[-4:])[0]
    if zlib.crc32(body) != crc:
        raise ProtocolError("CRC")
    return kind, body


# --------------------------------------------------------------------------- vectors


def make_vectors() -> dict:
    """Deterministic key and request so other implementations can check their own code.
    Signatures are randomised, so consumers must VERIFY the signature, not compare bytes."""
    order = 0xFFFFFFFF00000000FFFFFFFFFFFFFFFFBCE6FAADA7179E84F3B9CAC2FC632551
    secret = int.from_bytes(hashlib.sha256(b"imzo-test-vector-key").digest(), "big") % (order - 1) + 1
    device = Device(ec.derive_private_key(secret, ec.SECP256R1()))
    items = [
        ["Aziza Karimova", "86000000000000000001", 450_000_000],
        ["Bobur Tursunov", "86000000000000000002", 520_000_000],
        ["Dilnoza Yusupova", "8600123412341234", 380_050_000],
    ]
    request = build_request(
        rid=bytes(range(16)), dev=device.id, nonce=bytes(range(16, 32)),
        op="payroll", ref="Payroll October 2026", items=items, ap=(1, 1),
    )
    review = device.review(request)
    response = device.respond(review, True)
    sig = loads(response)["sig"]
    body = {k: loads(response)[k] for k in ("v", "rid", "dev", "dec", "ctr", "h")}
    return {
        "note": "Imzo v1 test vectors. Verify signature_hex over response_signing_input_hex with public_key_hex (ECDSA P-256, SHA-256, raw r||s).",
        "public_key_hex": device.public_key.hex(),
        "device_id_hex": device.id.hex(),
        "enrolment_code": enrolment_code(device.public_key),
        "request_hex": request.hex(),
        "request_sha256_hex": hashlib.sha256(request).hexdigest(),
        "fingerprint": review.fingerprint,
        "total_tiyin": review.total,
        "item_count": review.count,
        "response_hex": response.hex(),
        "response_signing_input_hex": response_signing_input(body).hex(),
        "signature_hex": sig.hex(),
        "frames_chunk_200": frames_encode("rq", request, 200, sid="00000001"),
    }


if __name__ == "__main__":
    print(json.dumps(make_vectors(), indent=2))
