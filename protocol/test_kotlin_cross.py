"""Cross-check: the reference Server accepts an answer produced by the Kotlin approver.

CrossCheckTest (Kotlin, `./gradlew :protocol:test` from android/) answers `cross_request_hex` from
cases.json with a key derived from `cross_key_secret_hex` and writes
android/protocol/build/cross/response.json (approve), reject.json (reject) and enrol.json. These tests are
skipped until those files exist, unless IMZO_REQUIRE_CROSS=1 is set: then a missing file is a failure
(use that in CI / before a demo so a skipped cross-check cannot pass unnoticed).

How the order is built: Server.create_order draws rid and nonce from os.urandom. We patch
`imzo_ref.os.urandom` to return the two fixed values that make_cases used (rid, then nonce), so the
REAL create_order produces a request byte-identical to cross_request_hex; that is asserted. Then
submit_response runs unmodified: it checks the hash, the Kotlin ECDSA signature against the registered
public key, the counter, and maker-checker.

Run: python -m unittest test_kotlin_cross -v
"""

import json
import os
import unittest
from pathlib import Path
from unittest import mock

import imzo_ref as imzo
from imzo_ref import ProtocolError

HERE = Path(__file__).parent
CASES = json.loads((HERE / "cases.json").read_text())
CROSS_DIR = HERE.parent / "android" / "protocol" / "build" / "cross"
RESPONSE_PATH = CROSS_DIR / "response.json"
REJECT_PATH = CROSS_DIR / "reject.json"
ENROL_PATH = CROSS_DIR / "enrol.json"
REQUIRE_CROSS = os.environ.get("IMZO_REQUIRE_CROSS") == "1"


def needs_cross_file(path: Path):
    """Skip while the Kotlin output is missing, but fail instead when IMZO_REQUIRE_CROSS=1."""
    if REQUIRE_CROSS:
        def require(cls):
            original = cls.setUpClass.__func__

            def setUpClass(klass):
                if not path.exists():
                    raise AssertionError(f"IMZO_REQUIRE_CROSS=1 but {path} is missing; run ./gradlew :protocol:test in android/")
                original(klass)

            cls.setUpClass = classmethod(setUpClass)
            return cls

        return require
    return unittest.skipUnless(path.exists(), "run ./gradlew :protocol:test in android/ first")

RID = bytes(range(0x40, 0x50))
NONCE = bytes(range(0x50, 0x60))


@needs_cross_file(RESPONSE_PATH)
class KotlinCrossCheck(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.data = json.loads(RESPONSE_PATH.read_text())
        cls.request = bytes.fromhex(cls.data["request_hex"])
        cls.response = bytes.fromhex(cls.data["response_hex"])
        cls.public_key = bytes.fromhex(cls.data["public_key_hex"])

    def world(self):
        """A fresh server with the Kotlin device registered as 'alice' and an order made by 'mike'."""
        server = imzo.Server(now=lambda: 1_800_000_000.0)
        dev = server.register_device(self.public_key, "alice")
        req = imzo.parse_request(self.request)
        with mock.patch.object(imzo.os, "urandom", side_effect=[RID, NONCE]):
            out = server.create_order(
                order_id="ORD-KOTLIN", maker="mike", op=req["op"], ref=req["ref"],
                items=req["items"], approvers=[dev],
            )
        return server, dev, out[dev]

    def test_inputs_are_the_ones_python_generated(self):
        self.assertEqual(self.data["request_hex"], CASES["cross_request_hex"])
        from cryptography.hazmat.primitives.asymmetric import ec

        key = ec.derive_private_key(int(CASES["cross_key_secret_hex"], 16), ec.SECP256R1())
        self.assertEqual(self.public_key, imzo.public_bytes(key))  # Kotlin derived the same public key

    def test_order_request_equals_cross_request(self):
        _, _, issued = self.world()
        self.assertEqual(issued, self.request)

    def test_response_is_canonical_and_well_formed(self):
        resp = imzo.validate_response(imzo.loads(self.response))
        self.assertEqual(resp["dec"], 1)
        self.assertEqual(resp["ctr"], 1)
        self.assertEqual(resp["rid"], RID)

    def test_reference_server_executes_the_kotlin_answer(self):
        server, dev, _ = self.world()
        self.assertEqual(server.devices[dev]["user"], "alice")
        self.assertEqual(server.orders["ORD-KOTLIN"].maker, "mike")  # different user than the device owner
        self.assertEqual(server.submit_response(self.response), "EXECUTED")
        self.assertEqual(len(server.executed), 1)
        self.assertEqual(server.executed[0]["order"], "ORD-KOTLIN")
        self.assertEqual(sum(i[2] for i in server.executed[0]["items"]), 1_350_050_000)
        self.assertEqual(server.devices[dev]["last_ctr"], 1)
        self.assertTrue(server.verify_audit())

    def test_replay_is_refused(self):
        server, _, _ = self.world()
        server.submit_response(self.response)
        with self.assertRaises(ProtocolError) as cm:
            server.submit_response(self.response)
        self.assertEqual(cm.exception.code, "NOT PENDING")

    def test_same_user_as_maker_is_refused(self):
        server = imzo.Server(now=lambda: 1_800_000_000.0)
        dev = server.register_device(self.public_key, "mike")
        req = imzo.parse_request(self.request)
        with mock.patch.object(imzo.os, "urandom", side_effect=[RID, NONCE]):
            server.create_order(order_id="O", maker="mike", op=req["op"], ref=req["ref"], items=req["items"], approvers=[dev])
        with self.assertRaises(ProtocolError) as cm:
            server.submit_response(self.response)
        self.assertEqual(cm.exception.code, "MAKER CANNOT APPROVE")

    def test_every_single_bit_flip_is_rejected(self):
        for pos in range(len(self.response) * 8):
            mutated = bytearray(self.response)
            mutated[pos // 8] ^= 1 << (pos % 8)
            server, _, _ = self.world()
            with self.subTest(bit=pos):
                with self.assertRaises(ProtocolError):
                    server.submit_response(bytes(mutated))
                self.assertEqual(server.executed, [])
                self.assertEqual(server.orders["ORD-KOTLIN"].status, "PENDING")

    def test_signature_bit_flip_is_a_bad_signature(self):
        sig_at = self.response.index(imzo.loads(self.response)["sig"])
        mutated = bytearray(self.response)
        mutated[sig_at + 10] ^= 0x01
        server, _, _ = self.world()
        with self.assertRaises(ProtocolError) as cm:
            server.submit_response(bytes(mutated))
        self.assertEqual(cm.exception.code, "BAD SIGNATURE")


@needs_cross_file(REJECT_PATH)
class KotlinRejectCrossCheck(unittest.TestCase):
    """The reference Server accepts a Kotlin REJECT (dec = 0): order REJECTED, nothing executed, audit row."""

    @classmethod
    def setUpClass(cls):
        cls.data = json.loads(REJECT_PATH.read_text())
        cls.request = bytes.fromhex(cls.data["request_hex"])
        cls.response = bytes.fromhex(cls.data["response_hex"])
        cls.public_key = bytes.fromhex(cls.data["public_key_hex"])

    def world(self):
        server = imzo.Server(now=lambda: 1_800_000_000.0)
        dev = server.register_device(self.public_key, "alice")
        req = imzo.parse_request(self.request)
        with mock.patch.object(imzo.os, "urandom", side_effect=[RID, NONCE]):
            out = server.create_order(
                order_id="ORD-KOTLIN", maker="mike", op=req["op"], ref=req["ref"],
                items=req["items"], approvers=[dev],
            )
        self.assertEqual(out[dev], self.request)
        return server, dev

    def test_response_is_a_canonical_reject(self):
        resp = imzo.validate_response(imzo.loads(self.response))
        self.assertEqual((resp["dec"], resp["ctr"], resp["rid"]), (0, 1, RID))
        self.assertEqual(imzo.dumps(imzo.loads(self.response)), self.response)

    def test_reference_server_records_the_rejection(self):
        server, dev = self.world()
        self.assertEqual(server.submit_response(self.response), "REJECTED")
        self.assertEqual(server.orders["ORD-KOTLIN"].status, "REJECTED")
        self.assertEqual(server.executed, [])
        rows = [r["event"] for r in server.audit if r["event"]["kind"] == "order_rejected"]
        self.assertEqual(len(rows), 1)
        self.assertEqual(rows[0]["order"], "ORD-KOTLIN")
        self.assertEqual(rows[0]["dev"], dev)
        self.assertEqual(server.devices[dev]["last_ctr"], 1)
        self.assertTrue(server.verify_audit())

    def test_replayed_reject_is_refused(self):
        server, _ = self.world()
        server.submit_response(self.response)
        with self.assertRaises(ProtocolError) as cm:
            server.submit_response(self.response)
        self.assertEqual(cm.exception.code, "NOT PENDING")

    def test_signature_bit_flip_is_a_bad_signature(self):
        sig_at = self.response.index(imzo.loads(self.response)["sig"])
        mutated = bytearray(self.response)
        mutated[sig_at + 10] ^= 0x01
        server, _ = self.world()
        with self.assertRaises(ProtocolError) as cm:
            server.submit_response(bytes(mutated))
        self.assertEqual(cm.exception.code, "BAD SIGNATURE")
        self.assertEqual(server.orders["ORD-KOTLIN"].status, "PENDING")


@needs_cross_file(ENROL_PATH)
class KotlinEnrolmentCrossCheck(unittest.TestCase):
    """The reference Server accepts the Kotlin enrolment answer (proof of possession over its challenge)."""

    @classmethod
    def setUpClass(cls):
        cls.data = json.loads(ENROL_PATH.read_text())
        cls.public_key = bytes.fromhex(cls.data["public_key_hex"])
        cls.nonce = bytes.fromhex(cls.data["nonce_hex"])
        cls.org = cls.data["org"]
        cls.answer = bytes.fromhex(cls.data["answer_hex"])

    def pending(self):
        """A server with a pending enrolment whose nonce is the one Kotlin signed."""
        server = imzo.Server(now=lambda: 1_800_000_000.0)
        with mock.patch.object(imzo.os, "urandom", return_value=self.nonce):
            nonce, body = server.begin_enrolment("alice", self.org)
        self.assertEqual(nonce, self.nonce)
        return server, body

    def test_answer_is_canonical_cbor_with_the_two_fields(self):
        obj = imzo.loads(self.answer)
        self.assertEqual(set(obj), {"pub", "sig"})
        self.assertEqual(obj["pub"], self.public_key)
        self.assertEqual(imzo.dumps(obj), self.answer)

    def test_reference_server_registers_the_kotlin_device(self):
        server, _ = self.pending()
        dev = server.finish_enrolment(self.answer)
        self.assertEqual(dev, imzo.device_id(self.public_key))
        self.assertEqual(server.devices[dev]["user"], "alice")

    def test_challenge_is_single_use(self):
        server, _ = self.pending()
        server.finish_enrolment(self.answer)
        with self.assertRaises(ProtocolError) as cm:
            server.finish_enrolment(self.answer)
        self.assertEqual(cm.exception.code, "BAD SIGNATURE")

    def test_wrong_org_is_refused(self):
        server = imzo.Server(now=lambda: 1_800_000_000.0)
        with mock.patch.object(imzo.os, "urandom", return_value=self.nonce):
            server.begin_enrolment("alice", "other-org")
        with self.assertRaises(ProtocolError) as cm:
            server.finish_enrolment(self.answer)
        self.assertEqual(cm.exception.code, "BAD SIGNATURE")

    def test_signature_bit_flip_is_refused(self):
        sig_at = self.answer.index(imzo.loads(self.answer)["sig"])
        mutated = bytearray(self.answer)
        mutated[sig_at + 10] ^= 0x01
        server, _ = self.pending()
        with self.assertRaises(ProtocolError) as cm:
            server.finish_enrolment(bytes(mutated))
        self.assertEqual(cm.exception.code, "BAD SIGNATURE")


if __name__ == "__main__":
    unittest.main()
