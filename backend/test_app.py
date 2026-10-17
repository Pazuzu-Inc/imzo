"""Backend HTTP tests. Run from the repo root: .venv/bin/python -m unittest discover -s backend -v"""

import http.client
import json
import os
import sys
import tempfile
import threading
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
sys.path.insert(0, str(Path(__file__).resolve().parent.parent / "protocol"))

import app as backend  # noqa: E402
import imzo_ref as imzo  # noqa: E402

ITEMS = [
    ["Aziza Karimova", "86000000000000000001", 450_000_000],
    ["Bobur Tursunov", "86000000000000000002", 520_000_000],
]


class BackendCase(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.httpd = backend.serve("127.0.0.1", 0)
        cls.port = cls.httpd.server_address[1]
        cls.thread = threading.Thread(target=cls.httpd.serve_forever, daemon=True)
        cls.thread.start()

    @classmethod
    def tearDownClass(cls):
        cls.httpd.shutdown()
        cls.httpd.server_close()
        cls.thread.join(5)

    def setUp(self):
        self.call("POST", "/api/reset", {})

    def call(self, method, path, body=None, raw=None, headers=None):
        conn = http.client.HTTPConnection("127.0.0.1", self.port, timeout=10)
        try:
            data = raw if raw is not None else (json.dumps(body).encode() if body is not None else None)
            hdrs = {"Content-Type": "application/json"} if data is not None else {}
            hdrs.update(headers or {})
            conn.request(method, path, body=data, headers=hdrs)
            resp = conn.getresponse()
            payload = resp.read()
            ctype = resp.getheader("Content-Type", "")
            parsed = json.loads(payload) if ctype.startswith("application/json") else payload
            return resp.status, parsed
        finally:
            conn.close()

    # -- helpers acting as the approver phone

    def enrol(self, user="alice"):
        dev = imzo.Device()
        status, out = self.call("POST", "/api/enrol/begin", {"user": user})
        self.assertEqual(status, 200)
        kind, body = imzo.frames_decode(out["frames"])
        self.assertEqual(kind, "en")
        msg = imzo.loads(body)
        self.assertEqual(msg["nonce"].hex(), out["nonce_hex"])
        answer = imzo.frames_encode("er", imzo.dumps(dev.enrolment_answer(msg["nonce"], msg["org"])))
        status, fin = self.call("POST", "/api/enrol/finish", {"frames": answer})
        return dev, answer, status, fin

    def order(self, maker="mike", approver="alice", items=ITEMS):
        return self.call(
            "POST", "/api/orders", {"maker": maker, "approver": approver, "ref": "Payroll October", "items": items}
        )

    def answer(self, dev, order_frames, approve=True):
        kind, request = imzo.frames_decode(order_frames)
        self.assertEqual(kind, "rq")
        resp = dev.respond(dev.review(request), approve)
        return self.call("POST", "/api/answers", {"frames": imzo.frames_encode("rs", resp)}), resp


class FlowTests(BackendCase):
    def test_enrolment_round_trip_returns_the_devices_code(self):
        dev, _, status, fin = self.enrol()
        self.assertEqual(status, 200)
        self.assertEqual(fin["code"], imzo.enrolment_code(dev.public_key))
        self.assertEqual(len(fin["code"]), 6)
        self.assertEqual(fin["device_id_hex"], dev.id.hex())

    def test_second_finish_with_the_same_nonce_is_refused(self):
        _, answer, status, _ = self.enrol()
        self.assertEqual(status, 200)
        status, out = self.call("POST", "/api/enrol/finish", {"frames": answer})
        self.assertEqual(status, 400)
        self.assertEqual(out, {"error": "BAD SIGNATURE"})

    def test_normal_run_executes_the_original_items(self):
        dev, *_ = self.enrol()
        status, order = self.order()
        self.assertEqual(status, 200)
        self.assertTrue(order["order_id"].startswith("ord-"))
        _, request = imzo.frames_decode(order["frames"])
        self.assertEqual(order["fingerprint"], imzo.fingerprint(request))
        self.assertEqual(self.call("GET", f"/api/orders/{order['order_id']}")[1], {"status": "PENDING"})
        (status, out), _ = self.answer(dev, order["frames"])
        self.assertEqual((status, out), (200, {"status": "EXECUTED"}))
        self.assertEqual(self.call("GET", f"/api/orders/{order['order_id']}")[1], {"status": "EXECUTED"})
        status, executed = self.call("GET", "/api/executed")
        self.assertEqual(status, 200)
        self.assertEqual(len(executed), 1)
        self.assertEqual(executed[0]["items"], ITEMS)
        self.assertEqual(executed[0]["op"], "payroll")
        self.assertEqual(executed[0]["order"], order["order_id"])

    def test_tampered_run_executes_what_was_submitted(self):
        """Documents the threat: the server executes what it stored. Only the approver can catch a swap."""
        dev, *_ = self.enrol()
        tampered = [list(i) for i in ITEMS]
        tampered[0][1] = "86009999999999999999"
        status, order = self.order(items=tampered)
        self.assertEqual(status, 200)
        # The approver signs the hash of the (tampered) request it was actually shown.
        (status, out), _ = self.answer(dev, order["frames"])
        self.assertEqual((status, out), (200, {"status": "EXECUTED"}))
        _, executed = self.call("GET", "/api/executed")
        self.assertEqual(executed[0]["items"], tampered)
        self.assertNotEqual(executed[0]["items"], ITEMS)

    def test_rejection(self):
        dev, *_ = self.enrol()
        _, order = self.order()
        (status, out), _ = self.answer(dev, order["frames"], approve=False)
        self.assertEqual((status, out), (200, {"status": "REJECTED"}))
        self.assertEqual(self.call("GET", f"/api/orders/{order['order_id']}")[1], {"status": "REJECTED"})
        self.assertEqual(self.call("GET", "/api/executed")[1], [])

    def test_replaying_an_answer_is_refused(self):
        dev, *_ = self.enrol()
        _, order = self.order()
        _, resp = self.answer(dev, order["frames"])
        status, out = self.call("POST", "/api/answers", {"frames": imzo.frames_encode("rs", resp)})
        self.assertEqual((status, out), (400, {"error": "NOT PENDING"}))

    def test_maker_cannot_approve_own_order(self):
        dev, *_ = self.enrol("alice")
        _, order = self.order(maker="alice", approver="alice")
        (status, out), _ = self.answer(dev, order["frames"])
        self.assertEqual((status, out), (400, {"error": "MAKER CANNOT APPROVE"}))

    def test_unknown_order_status_is_404(self):
        status, out = self.call("GET", "/api/orders/ord-nope")
        self.assertEqual((status, out), (404, {"error": "NOT FOUND"}))

    def test_audit_is_hex_encoded_and_verifies(self):
        dev, *_ = self.enrol()
        _, order = self.order()
        self.answer(dev, order["frames"], approve=False)
        status, audit = self.call("GET", "/api/audit")
        self.assertEqual(status, 200)
        self.assertTrue(audit["ok"])
        kinds = [r["kind"] for r in audit["rows"]]
        for k in ("enrolment_started", "device_registered", "order_created", "order_rejected"):
            self.assertIn(k, kinds)
        reg = next(r for r in audit["rows"] if r["kind"] == "device_registered")
        self.assertEqual(reg["dev"], dev.id.hex())
        self.assertEqual(reg["user"], "alice")
        self.assertIsInstance(reg["t"], int)
        self.assertEqual(len(reg["hash_hex"]), 64)

    def test_reset_clears_state(self):
        dev, *_ = self.enrol()
        _, order = self.order()
        self.answer(dev, order["frames"])
        self.assertEqual(self.call("POST", "/api/reset", {})[0], 200)
        self.assertEqual(self.call("GET", "/api/executed")[1], [])
        self.assertEqual(self.call("GET", f"/api/orders/{order['order_id']}")[0], 404)
        self.assertEqual(self.call("GET", "/api/audit")[1], {"ok": True, "rows": []})


class ErrorTests(BackendCase):
    def test_order_for_user_without_device(self):
        status, out = self.order(approver="nobody")
        self.assertEqual((status, out), (400, {"error": "NO DEVICE"}))

    def test_malformed_json_and_missing_fields(self):
        self.assertEqual(self.call("POST", "/api/orders", raw=b"{not json")[1], {"error": "BAD REQUEST"})
        self.assertEqual(self.call("POST", "/api/orders", raw=b"[]")[1], {"error": "BAD REQUEST"})
        self.assertEqual(self.call("POST", "/api/orders", {"maker": "m"}), (400, {"error": "BAD REQUEST"}))
        self.assertEqual(self.call("POST", "/api/enrol/begin", {}), (400, {"error": "BAD REQUEST"}))
        self.assertEqual(self.call("POST", "/api/answers", {"frames": "x"}), (400, {"error": "BAD REQUEST"}))
        self.assertEqual(self.call("POST", "/api/answers", {"frames": [1]}), (400, {"error": "BAD REQUEST"}))

    def test_wrong_item_shape_is_a_clean_400(self):
        self.enrol()
        for items in ([["a", "b"]], "x", [], [[1, 2, 3]], [["Name", "86000000000000000001", -5]], [["Name", "86000000000000000001", True]]):
            status, out = self.order(items=items)
            self.assertEqual(status, 400, items)
            self.assertIn(out["error"], ("BAD REQUEST", "BAD FORMAT"))

    def test_garbage_frames_return_protocol_error(self):
        status, out = self.call("POST", "/api/answers", {"frames": ["nonsense"]})
        self.assertEqual((status, out), (400, {"error": "BAD FORMAT"}))
        status, out = self.call("POST", "/api/enrol/finish", {"frames": ["nonsense"]})
        self.assertEqual((status, out), (400, {"error": "BAD FORMAT"}))

    def test_wrong_frame_kind_is_refused(self):
        dev, *_ = self.enrol()
        _, order = self.order()
        status, out = self.call("POST", "/api/answers", {"frames": order["frames"]})
        self.assertEqual(status, 400)
        self.assertEqual(out, {"error": "BAD FORMAT"})

    def test_body_over_one_mib_is_refused(self):
        status, out = self.call("POST", "/api/orders", raw=b"x" * (1024 * 1024 + 1))
        self.assertEqual(status, 413)
        self.assertEqual(out, {"error": "BAD REQUEST"})

    def test_unknown_routes_and_methods(self):
        self.assertEqual(self.call("GET", "/api/nope")[0], 404)
        self.assertEqual(self.call("POST", "/api/audit", {})[0], 404)


class StaticTests(BackendCase):
    def test_serves_web_files_and_blocks_traversal(self):
        with tempfile.TemporaryDirectory() as tmp:
            web = Path(tmp) / "web"
            web.mkdir()
            (web / "index.html").write_text("<h1>hi</h1>")
            (web / "app.js").write_text("console.log(1)")
            (Path(tmp) / "secret.txt").write_text("secret")
            old = backend.WEB_DIR
            backend.WEB_DIR = web
            try:
                status, body = self.call("GET", "/")
                self.assertEqual((status, body), (200, b"<h1>hi</h1>"))
                status, body = self.call("GET", "/app.js")
                self.assertEqual((status, body), (200, b"console.log(1)"))
                self.assertEqual(self.call("GET", "/missing.html")[0], 404)
                self.assertEqual(self.call("GET", "/../secret.txt")[0], 404)
                self.assertEqual(self.call("GET", "/%2e%2e/secret.txt")[0], 404)
                self.assertEqual(self.call("GET", "/..%2fsecret.txt")[0], 404)
            finally:
                backend.WEB_DIR = old

    def test_missing_web_dir_is_404(self):
        old = backend.WEB_DIR
        backend.WEB_DIR = Path(tempfile.gettempdir()) / "imzo-no-such-web-dir"
        try:
            self.assertEqual(self.call("GET", "/")[0], 404)
        finally:
            backend.WEB_DIR = old


if __name__ == "__main__":
    unittest.main()
