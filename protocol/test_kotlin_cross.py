"""Cross-check: the reference Server accepts an answer produced by the Kotlin approver.

CrossCheckTest (Kotlin, `./gradlew :protocol:test` from android/) answers `cross_request_hex` from
cases.json with a key derived from `cross_key_secret_hex` and writes
android/protocol/build/cross/response.json. This test is skipped until that file exists.

How the order is built: Server.create_order draws rid and nonce from os.urandom. We patch
`imzo_ref.os.urandom` to return the two fixed values that make_cases used (rid, then nonce), so the
REAL create_order produces a request byte-identical to cross_request_hex; that is asserted. Then
submit_response runs unmodified: it checks the hash, the Kotlin ECDSA signature against the registered
public key, the counter, and maker-checker.

Run: python -m unittest test_kotlin_cross -v
"""

import json
import unittest
from pathlib import Path
from unittest import mock

import imzo_ref as imzo
from imzo_ref import ProtocolError

HERE = Path(__file__).parent
CASES = json.loads((HERE / "cases.json").read_text())
RESPONSE_PATH = HERE.parent / "android" / "protocol" / "build" / "cross" / "response.json"

RID = bytes(range(0x40, 0x50))
NONCE = bytes(range(0x50, 0x60))


@unittest.skipUnless(RESPONSE_PATH.exists(), "run ./gradlew :protocol:test in android/ first")
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


if __name__ == "__main__":
    unittest.main()
