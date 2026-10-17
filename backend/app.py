"""Imzo demo backend: a small JSON/HTTP layer around the reference Server.

Run from the repo root:  .venv/bin/python backend/app.py [--host 127.0.0.1] [--port 8080]
Serves the API under /api and the static files in web/ at /. Standard library plus imzo_ref only.
Not for production: the Server is in memory and guarded by one lock.
Only requests whose Host is 127.0.0.1:<port> or localhost:<port> are served (DNS-rebinding guard), so
binding to another address does not make the demo reachable from other machines.
"""

from __future__ import annotations

import argparse
import json
import logging
import mimetypes
import os
import sys
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import unquote, urlsplit

ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT / "protocol"))

import imzo_ref as imzo  # noqa: E402
from imzo_ref import ProtocolError  # noqa: E402

WEB_DIR = ROOT / "web"
MAX_BODY = 1024 * 1024
MAX_FRAMES = 1024
MAX_NAME = 64
log = logging.getLogger("imzo.backend")


class BadRequest(Exception):
    pass


class State:
    """The one Server instance and the lock every access goes through."""

    def __init__(self) -> None:
        self.lock = threading.Lock()
        self.server = imzo.Server()


STATE = State()


# --------------------------------------------------------------------------- request validation


def _name(body: dict, key: str) -> str:
    v = body.get(key)
    if not isinstance(v, str) or not v or len(v) > MAX_NAME:
        raise BadRequest(key)
    try:  # same text rules as the protocol: NFC, no control/format/surrogate/private-use/unassigned, <= 64 bytes
        imzo._check_text(v, imzo.MAX_NAME_BYTES, key)
    except ProtocolError:
        raise BadRequest(key) from None
    return v


def _frames(body: dict, expected_kind: str) -> bytes:
    frames = body.get("frames")
    if not isinstance(frames, list) or not frames or len(frames) > MAX_FRAMES:
        raise BadRequest("frames")
    if not all(isinstance(f, str) for f in frames):
        raise BadRequest("frames")
    kind, payload = imzo.frames_decode(frames)  # ProtocolError -> 400 with its code
    if kind != expected_kind:
        raise ProtocolError("BAD FORMAT", f"expected {expected_kind} frames")
    return payload


def _items(body: dict) -> list[list]:
    items = body.get("items")
    if not isinstance(items, list):
        raise BadRequest("items")
    for it in items:
        if not isinstance(it, list) or len(it) != 3:
            raise BadRequest("items")
        if isinstance(it[2], bool) or not isinstance(it[2], int):
            raise BadRequest("items")
    return items


def _jsonable(value):
    return value.hex() if isinstance(value, bytes) else value


# --------------------------------------------------------------------------- API handlers
# Each takes the parsed JSON body (dict) and returns a JSON-able object. Called with STATE.lock held.


def api_enrol_begin(body: dict):
    user = _name(body, "user")
    nonce, msg = STATE.server.begin_enrolment(user)
    return {"frames": imzo.frames_encode("en", msg), "nonce_hex": nonce.hex()}


def api_enrol_finish(body: dict):
    answer = _frames(body, "er")
    dev = STATE.server.finish_enrolment(answer)
    pub = STATE.server.devices[dev]["pub"]
    return {"device_id_hex": dev.hex(), "code": imzo.enrolment_code(pub)}


def api_orders_create(body: dict):
    maker, approver = _name(body, "maker"), _name(body, "approver")
    ref = body.get("ref")
    if not isinstance(ref, str):
        raise BadRequest("ref")
    items = _items(body)
    server = STATE.server
    active = [d for d, info in server.devices.items() if info["user"] == approver and info["status"] == "ACTIVE"]
    if not active:
        raise ProtocolError("NO DEVICE")
    device = active[-1]  # most recently enrolled
    order_id = "ord-" + os.urandom(3).hex()
    while order_id in server.orders:
        order_id = "ord-" + os.urandom(3).hex()
    requests = server.create_order(
        order_id=order_id, maker=maker, op="payroll", ref=ref, items=items, approvers=[device]
    )
    request = requests[device]
    return {"order_id": order_id, "frames": imzo.frames_encode("rq", request), "fingerprint": imzo.fingerprint(request)}


def api_answers(body: dict):
    status = STATE.server.submit_response(_frames(body, "rs"))
    return {"status": status}


def api_reset(_body: dict):
    STATE.server = imzo.Server()
    return {"ok": True}


def api_order_status(order_id: str):
    order = STATE.server.orders.get(order_id)
    return None if order is None else {"status": order.status}


def api_executed():
    return STATE.server.executed


def api_audit():
    rows = []
    for row in STATE.server.audit:
        event = {k: _jsonable(v) for k, v in row["event"].items()}
        rows.append({**event, "hash_hex": row["hash"].hex()})
    return {"ok": STATE.server.verify_audit(), "rows": rows}


POST_ROUTES = {
    "/api/enrol/begin": api_enrol_begin,
    "/api/enrol/finish": api_enrol_finish,
    "/api/orders": api_orders_create,
    "/api/answers": api_answers,
    "/api/reset": api_reset,
}


# --------------------------------------------------------------------------- HTTP


class Handler(BaseHTTPRequestHandler):
    server_version = "ImzoDemo/1"
    timeout = 10  # seconds; a slow or stalled client cannot hold a thread forever

    def log_message(self, fmt, *args):  # quiet unless serve(..., verbose=True)
        if getattr(self.server, "verbose", False):
            super().log_message(fmt, *args)

    # -- plumbing

    def _send(self, status: int, payload, content_type: str = "application/json") -> None:
        data = payload if isinstance(payload, bytes) else json.dumps(payload).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", content_type)
        self.send_header("Content-Length", str(len(data)))
        self.send_header("Cache-Control", "no-store")
        self.send_header("X-Content-Type-Options", "nosniff")
        self.end_headers()
        self.wfile.write(data)

    def _error(self, status: int, code: str) -> None:
        self._send(status, {"error": code})

    def _local_host(self) -> str | None:
        """The Host header if it names this server on a loopback name, else None (DNS-rebinding guard)."""
        host = self.headers.get("Host") or ""
        port = self.server.server_address[1]
        return host if host in (f"127.0.0.1:{port}", f"localhost:{port}") else None

    def _same_origin(self, host: str) -> bool:
        origin = self.headers.get("Origin")
        return origin is None or origin == f"http://{host}"

    def _read_json(self) -> dict:
        try:
            length = int(self.headers.get("Content-Length") or 0)
        except ValueError:
            raise BadRequest("length") from None
        if length < 0:
            raise BadRequest("length")
        if length > MAX_BODY:
            self.close_connection = True
            if length <= 8 * MAX_BODY:  # drain so the client can read our reply
                remaining = length
                while remaining > 0:
                    chunk = self.rfile.read(min(65536, remaining))
                    if not chunk:
                        break
                    remaining -= len(chunk)
            raise _TooLarge()
        raw = self.rfile.read(length) if length else b""
        try:
            body = json.loads(raw.decode("utf-8")) if raw else {}
        except (UnicodeDecodeError, ValueError, RecursionError):
            raise BadRequest("json") from None
        if not isinstance(body, dict):
            raise BadRequest("json")
        return body

    # -- verbs

    def do_POST(self):
        path = urlsplit(self.path).path
        route = POST_ROUTES.get(path)
        if route is None:
            return self._error(404, "NOT FOUND")
        host = self._local_host()
        if host is None or not self._same_origin(host):
            return self._error(403, "FORBIDDEN")
        ctype = (self.headers.get("Content-Type") or "").split(";", 1)[0].strip().lower()
        if ctype != "application/json":
            return self._error(415, "BAD REQUEST")
        try:
            body = self._read_json()
            with STATE.lock:
                result = route(body)
            self._send(200, result)
        except _TooLarge:
            self._error(413, "BAD REQUEST")
        except BadRequest:
            self._error(400, "BAD REQUEST")
        except ProtocolError as exc:
            self._error(400, exc.code)
        except (TypeError, ValueError, KeyError, IndexError) as exc:
            log.debug("lookup/type error in %s: %s", path, type(exc).__name__, exc_info=True)
            self._error(400, "BAD REQUEST")
        except TimeoutError:  # client stalled mid-body; nothing useful to answer
            self.close_connection = True
        except Exception:  # noqa: BLE001 - never leak a traceback to the client
            self.log_error("unhandled error in %s", path)
            self._error(500, "INTERNAL")

    def do_GET(self):
        path = urlsplit(self.path).path
        if self._local_host() is None:
            return self._error(403, "FORBIDDEN")
        try:
            if path == "/api/audit":
                with STATE.lock:
                    return self._send(200, api_audit())
            if path == "/api/executed":
                with STATE.lock:
                    return self._send(200, api_executed())
            if path.startswith("/api/orders/"):
                with STATE.lock:
                    result = api_order_status(unquote(path[len("/api/orders/"):]))
                if result is None:
                    return self._error(404, "NOT FOUND")
                return self._send(200, result)
            if path == "/api" or path.startswith("/api/"):
                return self._error(404, "NOT FOUND")
            self._static(path)
        except Exception:  # noqa: BLE001
            self.log_error("unhandled error in %s", path)
            self._error(500, "INTERNAL")

    def _method_not_allowed(self) -> None:
        data = json.dumps({"error": "METHOD NOT ALLOWED"}).encode("utf-8")
        self.send_response(405)
        self.send_header("Allow", "GET, POST")
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(data)))
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        if self.command != "HEAD":
            self.wfile.write(data)

    do_PUT = do_DELETE = do_PATCH = do_HEAD = do_OPTIONS = _method_not_allowed

    def _static(self, url_path: str) -> None:
        rel = unquote(url_path).lstrip("/")
        try:
            base = WEB_DIR.resolve()
            target = (base / rel).resolve() if rel else base
            if target.is_dir():
                target = target / "index.html"
            ok = target.is_relative_to(base) and target.is_file()
        except (OSError, ValueError):  # missing dir, NUL byte, loop
            ok = False
        if not ok:
            return self._error(404, "NOT FOUND")
        ctype = mimetypes.guess_type(target.name)[0] or "application/octet-stream"
        if ctype.startswith("text/") or ctype in ("application/javascript", "application/json"):
            ctype += "; charset=utf-8"
        self._send(200, target.read_bytes(), ctype)


class _TooLarge(Exception):
    pass


def serve(host: str = "127.0.0.1", port: int = 8080, verbose: bool = False) -> ThreadingHTTPServer:
    """Create (not start) the server. Port 0 picks a free port: read it from server_address[1]."""
    httpd = ThreadingHTTPServer((host, port), Handler)
    httpd.daemon_threads = True
    httpd.verbose = verbose
    return httpd


def main() -> None:
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("--host", default="127.0.0.1")
    ap.add_argument("--port", type=int, default=8080)
    args = ap.parse_args()
    httpd = serve(args.host, args.port, verbose=True)
    print(f"Imzo demo backend on http://{args.host}:{httpd.server_address[1]}  (web root: {WEB_DIR})")
    try:
        httpd.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        httpd.server_close()


if __name__ == "__main__":
    main()
