"""Tests for the Imzo v1 reference implementation. Run: python -m unittest -v"""

import hashlib
import os
import unittest

from cryptography.hazmat.primitives import hashes
from cryptography.hazmat.primitives.asymmetric import ec

import imzo_ref as imzo
from imzo_ref import ProtocolError

ITEMS = [
    ["Aziza Karimova", "86000000000000000001", 450_000_000],
    ["Bobur Tursunov", "86000000000000000002", 520_000_000],
]


def code_of(fn, *args, **kwargs):
    try:
        fn(*args, **kwargs)
    except ProtocolError as exc:
        return exc.code
    return None


class Clock:
    def __init__(self):
        self.t = 1_800_000_000.0

    def __call__(self):
        return self.t


def make_world(approver_users=("alice",), maker="mike"):
    clock = Clock()
    server = imzo.Server(now=clock)
    devices = {u: imzo.Device() for u in approver_users}
    for user, dev in devices.items():
        server.register_device(dev.public_key, user)
    return clock, server, devices, maker


class CborTests(unittest.TestCase):
    def test_round_trip_and_sorted_keys(self):
        obj = {"b": 1, "a": [b"\x01", "x", 300], "ccc": {"z": 0, "y": 2**40}}
        data = imzo.dumps(obj)
        self.assertEqual(imzo.loads(data), obj)
        # keys are sorted by encoded bytes: "a" < "b" < "ccc"
        self.assertTrue(data.index(b"a") < data.index(b"b"))

    def test_rejects_non_canonical_and_hostile_input(self):
        bad = {
            "indefinite array": bytes([0x9F, 0x01, 0xFF]),
            "non-minimal int": bytes([0x18, 0x05]),
            "trailing bytes": imzo.dumps(1) + b"\x00",
            "float": bytes([0xF9, 0x3C, 0x00]),
            "tag": bytes([0xC1, 0x01]),
            "negative int": bytes([0x20]),
            "non-text key": bytes([0xA1, 0x01, 0x01]),
            "duplicate key": bytes([0xA2, 0x61, 0x61, 0x01, 0x61, 0x61, 0x02]),
            "unsorted keys": bytes([0xA2, 0x61, 0x62, 0x01, 0x61, 0x61, 0x02]),
            "truncated": bytes([0x44, 0x01]),
            "huge array header": bytes([0x9B, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF]),
        }
        for name, data in bad.items():
            with self.subTest(name):
                self.assertEqual(code_of(imzo.loads, data), "BAD FORMAT")

    def test_rejects_too_deep_and_too_large(self):
        deep = b"\x81" * 10 + b"\x00"
        self.assertEqual(code_of(imzo.loads, deep), "BAD FORMAT")
        self.assertEqual(code_of(imzo.loads, b"\x00" * (imzo.MAX_MESSAGE_BYTES + 1)), "BAD FORMAT")


class RequestTests(unittest.TestCase):
    def build(self, **over):
        args = dict(rid=bytes(16), dev=bytes(16), nonce=bytes(16), op="payroll", ref="Payroll", items=ITEMS)
        args.update(over)
        return imzo.build_request(**args)

    def test_valid_request_parses(self):
        req = imzo.parse_request(self.build())
        self.assertEqual(req["total"], 970_000_000)

    def test_validation_errors(self):
        self.assertEqual(code_of(self.build, op="wire"), "BAD FORMAT")
        self.assertEqual(code_of(self.build, items=[["A", "123", 5]]), "BAD FORMAT")
        self.assertEqual(code_of(self.build, items=[["A", "8600000000000000000x", 5]]), "BAD FORMAT")
        self.assertEqual(code_of(self.build, items=[["A", "86000000000000000001", 0]]), "BAD FORMAT")
        self.assertEqual(code_of(self.build, items=[["A", "86000000000000000001", 5]] * 51), "BAD FORMAT")
        self.assertEqual(code_of(self.build, ref="x" * 65), "BAD FORMAT")
        self.assertEqual(code_of(self.build, items=[["Zoé", "86000000000000000001", 5]]), "BAD FORMAT")  # not NFC

    def test_total_mismatch_is_caught(self):
        obj = imzo.loads(self.build())
        obj["total"] += 1
        self.assertEqual(code_of(imzo.parse_request, imzo.dumps(obj)), "TOTAL MISMATCH")


class DeviceTests(unittest.TestCase):
    def test_wrong_device_refuses(self):
        a, b = imzo.Device(), imzo.Device()
        req = imzo.build_request(rid=os.urandom(16), dev=a.id, nonce=os.urandom(16), op="payroll", ref="p", items=ITEMS)
        self.assertEqual(code_of(b.review, req), "WRONG DEVICE")

    def test_review_is_derived_from_bytes_and_fingerprint_is_stable(self):
        dev = imzo.Device()
        req = imzo.build_request(rid=os.urandom(16), dev=dev.id, nonce=os.urandom(16), op="payroll", ref="p", items=ITEMS)
        r1, r2 = dev.review(req), dev.review(req)
        self.assertEqual(r1.fingerprint, r2.fingerprint)
        self.assertEqual(len(r1.fingerprint.split()), 6)
        self.assertEqual(r1.total, 970_000_000)
        self.assertEqual(r1.count, 2)

    def test_changing_any_field_changes_the_fingerprint(self):
        dev = imzo.Device()
        base = dict(rid=bytes(16), dev=dev.id, nonce=bytes(16), op="payroll", ref="p")
        r1 = dev.review(imzo.build_request(**base, items=ITEMS))
        swapped = [ITEMS[0], ["Bobur Tursunov", "86000000000000000099", 520_000_000]]
        r2 = dev.review(imzo.build_request(**base, items=swapped))
        self.assertNotEqual(r1.fingerprint, r2.fingerprint)
        self.assertEqual(r2.items[1][1], "86000000000000000099")  # the approver sees the swapped account

    def test_counter_increases(self):
        dev = imzo.Device()
        req = imzo.build_request(rid=os.urandom(16), dev=dev.id, nonce=os.urandom(16), op="payroll", ref="p", items=ITEMS)
        rv = dev.review(req)
        c1 = imzo.loads(dev.respond(rv, True))["ctr"]
        c2 = imzo.loads(dev.respond(rv, True))["ctr"]
        self.assertEqual((c1, c2), (1, 2))

    def test_enrolment_code_matches_on_both_sides_and_changes_with_key(self):
        a, b = imzo.Device(), imzo.Device()
        self.assertEqual(imzo.enrolment_code(a.public_key), imzo.enrolment_code(a.public_key))
        self.assertNotEqual(imzo.enrolment_code(a.public_key), imzo.enrolment_code(b.public_key))


class ServerTests(unittest.TestCase):
    def test_happy_path_executes_the_stored_order(self):
        _, server, devs, maker = make_world()
        dev = devs["alice"]
        reqs = server.create_order(order_id="o1", maker=maker, op="payroll", ref="p", items=ITEMS, approvers=[dev.id])
        review = dev.review(reqs[dev.id])
        status = server.submit_response(dev.respond(review, True))
        self.assertEqual(status, "EXECUTED")
        self.assertEqual(server.executed[0]["items"], ITEMS)
        self.assertTrue(server.verify_audit())

    def test_man_in_the_browser_is_visible_and_rejection_stops_the_order(self):
        """Malware swaps the beneficiary before submission. The server stores what it receives,
        so only the approver can catch it. The device shows the swapped account; reject stops it."""
        _, server, devs, maker = make_world()
        dev = devs["alice"]
        tampered = [ITEMS[0], ["Bobur Tursunov", "86000000000000000099", 520_000_000]]
        reqs = server.create_order(order_id="o2", maker=maker, op="payroll", ref="p", items=tampered, approvers=[dev.id])
        review = dev.review(reqs[dev.id])
        self.assertEqual(review.items[1][1], "86000000000000000099")  # on the phone screen
        self.assertEqual(server.submit_response(dev.respond(review, False)), "REJECTED")
        self.assertEqual(server.executed, [])

    def test_client_side_swap_after_approval_cannot_change_what_executes(self):
        _, server, devs, maker = make_world()
        dev = devs["alice"]
        reqs = server.create_order(order_id="o3", maker=maker, op="payroll", ref="p", items=ITEMS, approvers=[dev.id])
        # an attacker shows the device a different request than the server stored
        evil = imzo.build_request(
            rid=imzo.loads(reqs[dev.id])["rid"], dev=dev.id, nonce=os.urandom(16), op="payroll", ref="p",
            items=[["Evil", "86000000000000000666", 970_000_000]],
        )
        answer = dev.respond(dev.review(evil), True)
        self.assertEqual(code_of(server.submit_response, answer), "HASH MISMATCH")
        self.assertEqual(server.executed, [])

    def test_replay_is_rejected(self):
        _, server, devs, maker = make_world()
        dev = devs["alice"]
        reqs = server.create_order(order_id="o4", maker=maker, op="payroll", ref="p", items=ITEMS, approvers=[dev.id])
        answer = dev.respond(dev.review(reqs[dev.id]), True)
        server.submit_response(answer)
        self.assertEqual(code_of(server.submit_response, answer), "NOT PENDING")
        self.assertEqual(len(server.executed), 1)

    def test_counter_regression_is_rejected(self):
        _, server, devs, maker = make_world()
        dev = devs["alice"]
        r1 = server.create_order(order_id="a", maker=maker, op="payroll", ref="p", items=ITEMS, approvers=[dev.id])
        r2 = server.create_order(order_id="b", maker=maker, op="payroll", ref="p", items=ITEMS, approvers=[dev.id])
        a1 = dev.respond(dev.review(r1[dev.id]), True)  # ctr 1
        a2 = dev.respond(dev.review(r2[dev.id]), True)  # ctr 2
        server.submit_response(a2)
        self.assertEqual(code_of(server.submit_response, a1), "COUNTER")

    def test_bad_signature_and_wrong_key(self):
        _, server, devs, maker = make_world()
        dev, other = devs["alice"], imzo.Device()
        reqs = server.create_order(order_id="o5", maker=maker, op="payroll", ref="p", items=ITEMS, approvers=[dev.id])
        rv = dev.review(reqs[dev.id])
        forged = imzo.loads(dev.respond(rv, True))
        forged["sig"] = imzo.loads(other.respond(rv, True))["sig"]
        self.assertEqual(code_of(server.submit_response, imzo.dumps(forged)), "BAD SIGNATURE")
        flipped = imzo.loads(dev.respond(rv, True))
        flipped["dec"] = 0  # attacker flips approve to reject (or reverse)
        self.assertEqual(code_of(server.submit_response, imzo.dumps(flipped)), "BAD SIGNATURE")

    def test_expiry(self):
        clock, server, devs, maker = make_world()
        dev = devs["alice"]
        reqs = server.create_order(order_id="o6", maker=maker, op="payroll", ref="p", items=ITEMS, approvers=[dev.id], ttl=60)
        answer = dev.respond(dev.review(reqs[dev.id]), True)
        clock.t += 61
        self.assertEqual(code_of(server.submit_response, answer), "EXPIRED")
        self.assertEqual(server.orders["o6"].status, "EXPIRED")

    def test_maker_checker(self):
        _, server, devs, maker = make_world(approver_users=("alice", "bella"))
        a, b = devs["alice"], devs["bella"]
        reqs = server.create_order(order_id="o7", maker=maker, op="payroll", ref="p", items=ITEMS, approvers=[a.id, b.id])
        self.assertEqual(server.submit_response(a.respond(a.review(reqs[a.id]), True)), "PENDING")
        self.assertEqual(server.executed, [])
        self.assertEqual(server.submit_response(b.respond(b.review(reqs[b.id]), True)), "EXECUTED")

    def test_maker_cannot_approve_own_order(self):
        _, server, devs, _ = make_world(approver_users=("mike",))
        dev = devs["mike"]
        reqs = server.create_order(order_id="o8", maker="mike", op="payroll", ref="p", items=ITEMS, approvers=[dev.id])
        self.assertEqual(code_of(server.submit_response, dev.respond(dev.review(reqs[dev.id]), True)), "MAKER CANNOT APPROVE")

    def test_revoked_device_is_refused(self):
        _, server, devs, maker = make_world()
        dev = devs["alice"]
        reqs = server.create_order(order_id="o9", maker=maker, op="payroll", ref="p", items=ITEMS, approvers=[dev.id])
        answer = dev.respond(dev.review(reqs[dev.id]), True)
        server.revoke_device(dev.id)
        self.assertEqual(code_of(server.submit_response, answer), "DEVICE NOT ACTIVE")

    def test_device_locks_after_five_failures(self):
        _, server, devs, maker = make_world()
        dev = devs["alice"]
        reqs = server.create_order(order_id="o10", maker=maker, op="payroll", ref="p", items=ITEMS, approvers=[dev.id])
        rv = dev.review(reqs[dev.id])
        bad = imzo.loads(dev.respond(rv, True))
        bad["sig"] = bytes(64)
        for _ in range(5):
            self.assertEqual(code_of(server.submit_response, imzo.dumps(bad)), "BAD SIGNATURE")
        self.assertEqual(server.devices[dev.id]["status"], "LOCKED")

    def test_audit_chain_detects_edits(self):
        _, server, devs, maker = make_world()
        dev = devs["alice"]
        reqs = server.create_order(order_id="o11", maker=maker, op="payroll", ref="p", items=ITEMS, approvers=[dev.id])
        server.submit_response(dev.respond(dev.review(reqs[dev.id]), True))
        self.assertTrue(server.verify_audit())
        server.audit[1]["_body"] = server.audit[1]["_body"] + b"x"
        self.assertFalse(server.verify_audit())

    def test_garbage_response_is_a_bad_format(self):
        _, server, _, _ = make_world()
        self.assertEqual(code_of(server.submit_response, b"\xff\xff"), "BAD FORMAT")


class FrameTests(unittest.TestCase):
    def setUp(self):
        self.body = os.urandom(1300)

    def test_round_trip_any_order_with_repeats(self):
        frames = imzo.frames_encode("rq", self.body, chunk=200)
        self.assertEqual(len(frames), 7)
        shuffled = list(reversed(frames)) + frames[:3]
        self.assertEqual(imzo.frames_decode(shuffled), ("rq", self.body))

    def test_missing_frame_is_incomplete(self):
        frames = imzo.frames_encode("rs", self.body, chunk=200)
        self.assertEqual(code_of(imzo.frames_decode, frames[:-1]), "INCOMPLETE")

    def test_corruption_is_caught(self):
        frames = imzo.frames_encode("rq", self.body, chunk=200)
        head, _, tail = frames[2].rpartition(".")
        frames[2] = head + "." + ("A" if tail[0] != "A" else "B") + tail[1:]
        self.assertIn(code_of(imzo.frames_decode, frames), ("CRC", "BAD FORMAT"))

    def test_mixed_messages_are_refused(self):
        a = imzo.frames_encode("rq", self.body, chunk=200, sid="aaaaaaaa")
        b = imzo.frames_encode("rq", self.body, chunk=200, sid="bbbbbbbb")
        self.assertEqual(code_of(imzo.frames_decode, a[:3] + b[3:]), "BAD FORMAT")

    def test_not_an_imq1_frame(self):
        self.assertEqual(code_of(imzo.frames_decode, ["hello"]), "BAD FORMAT")


class VectorTests(unittest.TestCase):
    def test_vectors_verify_independently(self):
        v = imzo.make_vectors()
        pub = imzo.load_public(bytes.fromhex(v["public_key_hex"]))
        pub.verify(
            imzo.der_from_raw(bytes.fromhex(v["signature_hex"])),
            bytes.fromhex(v["response_signing_input_hex"]),
            ec.ECDSA(hashes.SHA256()),
        )
        request = bytes.fromhex(v["request_hex"])
        self.assertEqual(hashlib.sha256(request).hexdigest(), v["request_sha256_hex"])
        self.assertEqual(imzo.fingerprint(request), v["fingerprint"])
        self.assertEqual(imzo.parse_request(request)["total"], v["total_tiyin"])
        self.assertEqual(imzo.frames_decode(v["frames_chunk_200"]), ("rq", request))

    def test_vector_request_and_key_are_deterministic(self):
        a, b = imzo.make_vectors(), imzo.make_vectors()
        for key in ("public_key_hex", "request_hex", "fingerprint", "enrolment_code", "frames_chunk_200"):
            self.assertEqual(a[key], b[key])


if __name__ == "__main__":
    unittest.main()
