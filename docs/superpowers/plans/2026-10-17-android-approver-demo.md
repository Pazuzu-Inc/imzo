# Imzo Android Approver Demo Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** The pitch demo runs live: a laptop "initiator" page plays a payroll as QR frames, an Android approver app with no internet permission scans it, shows every beneficiary, signs with a Keystore key, and the laptop reads the signed answer QR; then the same flow with a tampered laptop is caught.

**Architecture:** One pure-Kotlin `:protocol` module ports `protocol/imzo_ref.py` and is checked against it with generated test cases (differential testing). The `:app` module adds Keystore signing, CameraX and ZXing scanning, QR display and Compose screens. A stdlib Python backend wraps the existing `imzo_ref.Server`; a static web page is the (untrusted) initiator. All of it runs on one laptop; the phone only talks through camera and screen.

**Tech Stack:** Kotlin 2.0.21, Gradle 8.9 and AGP 8.7.3, JDK 17, Jetpack Compose (BOM 2024.10.01), CameraX 1.4.0, ZXing core 3.5.3, androidx.biometric 1.1.0, Python 3.11 with `cryptography` (existing `.venv`), plain JS with `jsQR` and `qrcode-generator` vendored.

**Spec:** `docs/demo-plan.md` (what, schedule, decisions), `docs/editions.md` (what "no internet" means), `protocol/imzo_ref.py` and `protocol/vectors.json` (the protocol itself).

## Global Constraints

- Android app: `minSdk 30`, `compileSdk 35`, package `inc.pazuzu.imzo`, directory `android/`.
- The merged manifest declares **only** `CAMERA` and `USE_BIOMETRIC`. `INTERNET` must never appear. A script fails the build if it does.
- QR scanning uses ZXing core, **not ML Kit**. No analytics, crash-reporting or update libraries.
- The signing key is EC P-256 in the Android Keystore, non-exportable, user authentication required per use.
- Signatures on the wire are raw 64 bytes `r||s`; the Keystore produces DER, so convert. Signing input is `b"IMZO/1/approve\x00" + dumps(body)`.
- Protocol limits are exactly those in `imzo_ref.py`: 50 items, name and ref at most 64 UTF-8 bytes, messages at most 16 KiB, nesting depth 4, currency `UZS`, frames at most 256 per message, default chunk 200 bytes.
- The review screen never truncates or ellipsizes a name, account or amount.
- The Python reference is the source of truth. Where Kotlin and Python disagree, Kotlin is wrong.
- Every commit message ends with `Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>`.
- Installing toolchains and vendoring JS libraries downloads files: ask the user before each download step.

## Review Focus

1. **Names with code points that Python 3.11 and Android classify differently** (unassigned or newly added characters): both sides reject unassigned (`Cn`) as "has control characters", but the two Unicode tables differ (Android 14+ ICU is newer than Python 3.11), so a character can make either side stricter or looser than the other; the shared cases are pinned in Task 3.
2. **Frames from two different orders alternating on screen** (an old QR still playing, then a new one): the assembler must not mix them; a new session id resets and the screen says so. Pinned in Task 4.
3. **App killed after signing but before the answer is shown, or the counter store failing**: the counter is persisted before the answer is returned, so a later signature never reuses a counter. Pinned in Task 7.
4. **Request QR for a different device** (another approver's order): shows WRONG DEVICE and offers no way to sign. Pinned in Task 4 and Task 9.
5. **Long names, 20-digit accounts and 50 items on a small screen**: everything is shown in full and scrollable; the total and fingerprint stay visible. Pinned in Task 9.

---

### Task 0: Toolchain and a hello-camera app on the phone

**Files:**
- Create: `android/` (via Android Studio or Gradle wrapper), `android/local.properties` (git-ignored)
- Modify: `.gitignore` (add `android/local.properties`, `android/.gradle/`, `android/**/build/`, `*.apk`)

**Interfaces:**
- Produces: working `adb devices` showing the user's phone, `./gradlew :app:installDebug` installing a trivial app on it, `ANDROID_HOME` documented in `docs/dev-setup.md`.

- [ ] **Step 1: Ask the user to approve the downloads, then install JDK 17 and the Android command-line tools** (`brew install --cask temurin@17 android-commandlinetools`; `sdkmanager "platform-tools" "platforms;android-35" "build-tools;35.0.0"`; accept licenses). Android Studio is an acceptable alternative if the user prefers it.
- [ ] **Step 2: User enables Developer options and USB debugging on the phone and connects it.** Run `adb devices`; expected: the device listed as `device`, not `unauthorized`. Record the phone's Android version with `adb shell getprop ro.build.version.release`; if below 11 (API 30), stop and tell the user.
- [ ] **Step 3: Create the Gradle project** with modules `:protocol` (Kotlin JVM library) and `:app` (Compose, empty screen with a CameraX preview), pinned to the versions above, using a version catalog `android/gradle/libs.versions.toml`.
- [ ] **Step 4: Run `./gradlew :app:installDebug`; open the app on the phone; expected: live camera preview after granting the permission.**
- [ ] **Step 5: Write `docs/dev-setup.md` (commands that worked) and commit** `chore: android project skeleton and dev setup`.

### Task 1: Differential test cases generated from the Python reference

**Files:**
- Create: `protocol/make_cases.py`, `protocol/cases.json` (generated, committed), `protocol/test_cases.py`

**Interfaces:**
- Produces `cases.json` with this shape, all binary as lowercase hex:
  - `cbor`: list of `{name, hex, ok: bool, error?: str}`; for `ok` cases `dumps(loads(hex)) == hex`.
  - `requests`: list of `{name, hex, error?: str, total?: int, count?: int, fingerprint?: str}`.
  - `frames`: list of `{name, frames: [str], kind?: str, body_hex?: str, error?: str}`.

- [ ] **Step 1: Write `protocol/test_cases.py::TestCases`** asserting every entry matches what `imzo_ref` actually does when run on it (so the file can never drift from the reference), and that these named cases exist: `cbor`: non-canonical int (`18 01`), indefinite-length array, duplicate map key, trailing byte, nesting depth 5, invalid UTF-8, map with integer key, 16385-byte string; `requests`: valid 12-person payroll, valid 50-item, 51 items, total off by one (`TOTAL MISMATCH`), account of 15 digits, account of 20 digits with a letter, amount `0`, amount `2**53+1`, name in NFD form, name containing U+202E, name containing an unassigned code point (U+0378), name of exactly 64 bytes and of 65 bytes, `ap` `[2, 1]`, unknown `op`, extra field; `frames`: valid 5-frame request, frames shuffled with repeats, one frame with a flipped base64 character (`CRC`), missing frame (`INCOMPLETE`), two sessions mixed (`BAD FORMAT`), index 257 of 257.
- [ ] **Step 2: Run `cd protocol && ../.venv/bin/python -m unittest test_cases -v`; expected: FAIL (`make_cases`/`cases.json` missing).**
- [ ] **Step 3: Implement `protocol/make_cases.py` (`def main() -> None`)** building those inputs with `imzo_ref` helpers and hand-assembled bytes, recording the observed outcome for each; write `cases.json`.
- [ ] **Step 4: Run `../.venv/bin/python make_cases.py > /dev/null && ../.venv/bin/python -m unittest -v`; expected: all existing 31 tests plus the new ones PASS.**
- [ ] **Step 5: Commit** `test: differential cases generated from the reference`.

### Task 2: Kotlin CBOR (strict, canonical)

**Files:**
- Create: `android/protocol/src/main/kotlin/inc/pazuzu/imzo/protocol/Cbor.kt`, `ProtocolError.kt`
- Create: `android/protocol/src/test/kotlin/inc/pazuzu/imzo/protocol/CborTest.kt`, `Cases.kt` (loads `cases.json`)
- Copy: `protocol/cases.json` and `protocol/vectors.json` into `android/protocol/src/test/resources/` via a Gradle copy task so there is one source of truth.

**Interfaces:**
- Produces: `class ProtocolError(val code: String, val detail: String = "") : Exception`; `object Cbor { fun dumps(value: Any): ByteArray; fun loads(data: ByteArray): Any }` where values are `Long` (unsigned only), `ByteArray`, `String`, `List<Any>`, `Map<String, Any>`. Errors use the same `code` strings as Python (`BAD FORMAT`).

- [ ] **Step 1: Write `CborTest`**: one parameterized test over `cases.json` `cbor` entries, asserting for `ok` cases `Cbor.dumps(Cbor.loads(h)).contentEquals(h)` and for failing ones `ProtocolError.code == error`; plus `maps sort by encoded key bytes` (keys `"b"`, `"aa"`, `"a"` encode in the order `a, b, aa`).
- [ ] **Step 2: Run `cd android && ./gradlew :protocol:test`; expected: FAIL (classes not defined).**
- [ ] **Step 3: Implement `Cbor` and `ProtocolError`**, mirroring `_decode`, `loads`, `dumps` in `imzo_ref.py`; reject with `BAD FORMAT` on every case the Python does (including `not canonical` by re-encoding and comparing).
- [ ] **Step 4: Run `./gradlew :protocol:test`; expected: PASS.**
- [ ] **Step 5: Commit** `feat(protocol): strict canonical CBOR in Kotlin`.

### Task 3: Kotlin request parsing, review fields and identifiers

**Files:**
- Create: `.../protocol/Messages.kt`, `.../protocol/Identity.kt`
- Test: `.../protocol/MessagesTest.kt`

**Interfaces:**
- Consumes: `Cbor`, `ProtocolError` (Task 2).
- Produces:
  - `data class Item(val name: String, val account: String, val amount: Long)`
  - `data class Request(val rid: ByteArray, val dev: ByteArray, val nonce: ByteArray, val op: String, val cur: String, val ref: String, val items: List<Item>, val total: Long, val ap: Pair<Int, Int>)`
  - `fun parseRequest(data: ByteArray): Request` (strict; same error codes and order of checks as `validate_request`)
  - `fun fingerprint(canonicalRequest: ByteArray): String`, `fun enrolmentCode(publicKey: ByteArray): String`, `fun deviceId(publicKey: ByteArray): ByteArray`
  - `fun rawFromDer(der: ByteArray): ByteArray`, `fun derFromRaw(raw: ByteArray): ByteArray`

- [ ] **Step 1: Write `MessagesTest`**: over `cases.json` `requests` assert the error code or the `total`, `count` and `fingerprint`; over `vectors.json` assert `fingerprint(request_hex)`, `enrolmentCode(public_key_hex)`, `deviceId(public_key_hex)` equal the stored values; `derFromRaw(rawFromDer(x)) == x` for a DER signature with a short `r` (31 bytes); and `Signature.getInstance("SHA256withECDSA")` verifies `signature_hex` over `response_signing_input_hex` with the vector public key (after `derFromRaw`).
- [ ] **Step 2: Run `./gradlew :protocol:test --tests '*MessagesTest*'`; expected: FAIL.**
- [ ] **Step 3: Implement the functions in `Messages.kt` and `Identity.kt`.** Text rules: NFC via `java.text.Normalizer`, then reject any code point whose `Character.getType` is CONTROL, FORMAT, SURROGATE, PRIVATE_USE or UNASSIGNED (this is the Python `C*` set). Byte lengths use UTF-8.
- [ ] **Step 4: Run the tests; expected: PASS, including U+0378 and U+202E rejection.**
- [ ] **Step 5: Commit** `feat(protocol): request parsing, fingerprint and identifiers`.

### Task 4: Kotlin approver logic and IMQ1 frames, cross-checked by Python

**Files:**
- Create: `.../protocol/Approver.kt`, `.../protocol/Frames.kt`
- Test: `.../protocol/ApproverTest.kt`, `FramesTest.kt`, `CrossCheckTest.kt`
- Create: `protocol/test_kotlin_cross.py`

**Interfaces:**
- Consumes: Tasks 2 and 3.
- Produces:
  - `interface Signer { val publicKey: ByteArray; suspend fun sign(message: ByteArray): ByteArray }` (returns raw 64 bytes)
  - `interface CounterStore { fun next(): Long }` (increments and **persists before returning**)
  - `data class Review(val rid: ByteArray, val h: ByteArray, val fingerprint: String, val op: String, val ref: String, val total: Long, val count: Int, val items: List<Item>, val approval: Pair<Int, Int>)`
  - `class Approver(signer: Signer, counters: CounterStore) { val id: ByteArray; fun review(requestBytes: ByteArray): Review; suspend fun respond(review: Review, approve: Boolean): ByteArray }`; `review` throws `ProtocolError("WRONG DEVICE")` when `dev` is not this device's id; `total` is recomputed from items.
  - `object Frames { fun encode(kind: String, body: ByteArray, chunk: Int = 200, sid: String? = null): List<String>; fun decode(frames: Iterable<String>): Pair<String, ByteArray> }`
  - `class FrameAssembler { fun add(frame: String): Progress; fun result(): Pair<String, ByteArray>?; fun reset() }` with `data class Progress(val have: Int, val total: Int, val sid: String)`; a frame with a different session id resets the assembler and reports `have = 1`; counters and base64 are validated before any allocation.

- [ ] **Step 1: Write the tests.** `ApproverTest`: review of the vector request has `total == total_tiyin`, `count == item_count`, `fingerprint` equal to the vector; a request with `dev` of another key throws `WRONG DEVICE`; `respond(approve=true)` returns bytes that `Cbor.loads` to a map with `ctr == 1`, `dec == 1`, `h == sha256(request)`, and whose signature verifies with the signer's public key; two responses have counters 1 and 2; `approve=false` gives `dec == 0`. `FramesTest`: every `frames` case in `cases.json`; the vector's `frames_chunk_200` decode to `request_hex`; `FrameAssembler`: out-of-order and repeated frames complete once; a frame from a second session resets; 257 declared frames rejected without allocating; a 100 KB frame string rejected. `CrossCheckTest`: `cases.json` also carries `cross_key_secret_hex` (a P-256 scalar derived from `sha256("imzo-kotlin-cross-key")`) and `cross_request_hex` (a request addressed to that key's device id); the test builds a software signer from the scalar, reviews and responds to the request, and writes `build/cross/response.json` with `public_key_hex`, `request_hex`, `response_hex`.
- [ ] **Step 2: Run `./gradlew :protocol:test`; expected: FAIL.**
- [ ] **Step 3: Implement `Approver` and `Frames`** mirroring `Device.review`, `Device.respond`, `frames_encode`, `frames_decode`. Add `cross_key_secret_hex` and `cross_request_hex` generation to `protocol/make_cases.py` (and its test) in this step.
- [ ] **Step 4: Write `protocol/test_kotlin_cross.py`**: skipped unless `android/protocol/build/cross/response.json` exists; registers the public key on an `imzo_ref.Server`, creates the matching order, and asserts `submit_response` returns `EXECUTED`. Run `./gradlew :protocol:test` then `../.venv/bin/python -m unittest test_kotlin_cross -v` from `protocol/`; expected: PASS.
- [ ] **Step 5: Commit** `feat(protocol): approver and IMQ1 frames, cross-checked against the reference`.

### Task 5: Backend HTTP service

**Files:**
- Modify: `protocol/imzo_ref.py` (add enrolment to `Server`)
- Create: `backend/app.py`, `backend/test_app.py`, `backend/__init__.py` is not needed (run with `python backend/app.py`)

**Interfaces:**
- Consumes: `imzo_ref` (`Server`, `Device`, `frames_encode`, `frames_decode`, `enrolment_code`).
- Produces in `imzo_ref.Server`: `begin_enrolment(user: str, org: str = "demo") -> tuple[bytes, bytes]` (returns `nonce` and the `en` message body `dumps({"v":1,"nonce":nonce,"org":org})`) and `finish_enrolment(answer_bytes: bytes) -> bytes` (verifies the proof of possession from `Device.enrolment_answer`, registers the device, returns its id; raises `ProtocolError("BAD SIGNATURE")` otherwise; an enrolment nonce is single use).
- Produces HTTP (JSON, localhost only, `127.0.0.1:8080`, also serves `web/` as static files):
  - `POST /api/enrol/begin {user}` -> `{frames: [str], nonce_hex}`
  - `POST /api/enrol/finish {frames: [str]}` -> `{device_id_hex, code}`
  - `POST /api/orders {maker, approver, ref, items: [[name, account, amount]]}` -> `{order_id, frames: [str], fingerprint}`
  - `POST /api/answers {frames: [str]}` -> `{status}`; protocol errors -> HTTP 400 `{error: code}`
  - `GET /api/orders/<id>` -> `{status}`; `GET /api/audit` -> `{ok: bool, rows: [{kind, t, fields..., hash_hex}]}`; `GET /api/executed` -> the mock core's received orders.
  - `POST /api/reset` clears all state (demo convenience).

- [ ] **Step 1: Write `backend/test_app.py`** (starts the server on an ephemeral port in a thread; drives it over `http.client` with a Python `Device` as the approver): enrolment round trip returns the same six-digit code the device computes; normal run -> `EXECUTED` and `/api/executed` holds the original items; **tampered run** (the test submits a changed account, the approver `Device` signs the *tampered* request's hash) -> still `EXECUTED` with the tampered list, and `/api/executed` shows exactly the submitted items (this documents that the server executes what it stored, and that the approver must catch it); rejection (`dec=0`) -> `REJECTED`; replay of the same answer -> 400 `NOT PENDING`; maker equals approver -> 400 `MAKER CANNOT APPROVE`; `/api/audit` returns `ok: true` and contains `order_rejected`; a second enrolment `finish` with the same nonce -> 400.
- [ ] **Step 2: Run `.venv/bin/python -m unittest discover -s backend -v`; expected: FAIL.**
- [ ] **Step 3: Add `begin_enrolment` and `finish_enrolment` to `imzo_ref.Server`** (extend `protocol/test_imzo.py` with a test for each of the signature and single-use checks), then implement `backend/app.py` with `http.server.ThreadingHTTPServer`; serialize audit bytes as hex.
- [ ] **Step 4: Run both test suites; expected: PASS.**
- [ ] **Step 5: Commit** `feat(backend): HTTP service around the reference server`.

### Task 6: Initiator web page (the untrusted laptop)

**Files:**
- Create: `web/index.html`, `web/app.js`, `web/player.js`, `web/scanner.js`, `web/vendor/` (`qrcode.js`, `jsQR.js`), `web/test/player.test.mjs`
- Create: `web/payroll.json` (the 12 demo people)

**Interfaces:**
- Consumes: Task 5 HTTP API.
- Produces: `web/player.js` exports `class FramePlayer { constructor(canvas, {fps = 3, ecc = "M", scale = 8}); play(frames: string[]); stop() }`; `web/scanner.js` exports `class FrameScanner { constructor(video, onFrame: (text: string) => void); start(): Promise<void>; stop() }`; `app.js` has a **tamper switch** (checkbox, off by default) that changes the account of the 7th payroll row by one digit in the submitted order only; the on-screen table always shows the original list.

- [ ] **Step 1: Write `web/test/player.test.mjs`** with Node's `node:test`, covering the pure helper `nextIndex(i, n)` (wraps around), and `applyTamper(items)` (changes exactly one account's last digit, leaves names and amounts unchanged, returns a new array) and `tamperOff` returning the original values.
- [ ] **Step 2: Run `node --test web/test`; expected: FAIL.**
- [ ] **Step 3: Vendor the two libraries** after the user approves the download (`npm pack jsqr@1.4.0 qrcode-generator@1.4.4`, copy the built files into `web/vendor/`, keep their licence files). Implement helpers and the page: payroll table, "Send to approver" (POST order, play request frames), webcam scan of answer frames with a progress indicator, status line, audit-log panel with the chain check, reset button, an enrolment panel (begin, show `en` frames, scan `er` frames, show the six-digit code in large type), and a dev-only "paste frames" box.
- [ ] **Step 4: Run `node --test web/test` (PASS). Start `.venv/bin/python backend/app.py`, open `http://127.0.0.1:8080/` in Chrome, and with the dev paste box and a Python `Device` run the normal and tampered flows by hand; expected: normal run shows `EXECUTED`, audit chain `ok`.**
- [ ] **Step 5: Commit** `feat(web): initiator page with tamper switch, QR player and scanner`.

### Task 7: App shell: manifest guard, Keystore signer, counter store

**Files:**
- Create: `android/app/src/main/kotlin/inc/pazuzu/imzo/KeystoreSigner.kt`, `PrefsCounterStore.kt`, `MainActivity.kt`
- Create: `android/scripts/check_permissions.sh`
- Modify: `android/app/src/main/AndroidManifest.xml`, `android/app/build.gradle.kts`
- Test: `android/app/src/androidTest/kotlin/inc/pazuzu/imzo/KeystoreSignerTest.kt`, `PrefsCounterStoreTest.kt`

**Interfaces:**
- Consumes: `Signer`, `CounterStore` (Task 4), `rawFromDer`.
- Produces: `class KeystoreSigner(activity: FragmentActivity) : Signer` (generates `imzo-device-key` on first use: `EC secp256r1`, `setUserAuthenticationRequired(true)`, per-use authentication with `BIOMETRIC_STRONG or DEVICE_CREDENTIAL`, StrongBox requested with fallback; `sign` shows the system prompt titled "Approve this order" and returns raw 64 bytes); `class PrefsCounterStore(context: Context) : CounterStore` (uses `SharedPreferences.Editor.commit()` and throws if it returns false); `MainActivity` extends `FragmentActivity` (needed for `BiometricPrompt`) and hosts Compose; `check_permissions.sh <apk>` exits non-zero if the merged manifest has any permission other than `android.permission.CAMERA` and `android.permission.USE_BIOMETRIC` (via `aapt2 dump permissions`).

- [ ] **Step 1: Write the instrumented tests** (run on the phone): `PrefsCounterStoreTest.persistsBeforeReturning` (`next()` twice gives 1 then 2; a new instance on the same context continues at 3), `...failedCommitThrows` (using a store built on a read-only fake `SharedPreferences`); `KeystoreSignerTest.publicKeyIsStableAndUncompressed` (65 bytes, first byte `0x04`, same after re-creating the signer, no prompt needed because only the public key is read) and `keyIsNotExportable` (`key.encoded == null` for the private key).
- [ ] **Step 2: Run `./gradlew :app:connectedDebugAndroidTest`; expected: FAIL.**
- [ ] **Step 3: Implement the classes, set `android:allowBackup="false"`, add `FLAG_SECURE` in release builds, add the permission script and a Gradle task `checkPermissions` wired into `assembleDebug`/`assembleRelease` via `finalizedBy`.**
- [ ] **Step 4: Run the instrumented tests (PASS) and `./gradlew :app:assembleDebug`; expected: `checkPermissions` prints the two allowed permissions and passes. Add a deliberate `INTERNET` permission, confirm the build fails, then remove it.**
- [ ] **Step 5: Manual check on the phone: a throwaway button calls `KeystoreSigner.sign` and a system fingerprint or PIN prompt appears; cancelling it returns an error without a signature. Commit** `feat(app): Keystore signer, durable counter and permission guard`.

### Task 8: Scanner and QR player components

**Files:**
- Create: `android/app/src/main/kotlin/inc/pazuzu/imzo/ui/CameraScanner.kt`, `QrPlayer.kt`, `QrBitmap.kt`
- Test: `android/app/src/test/kotlin/inc/pazuzu/imzo/ui/QrBitmapTest.kt`

**Interfaces:**
- Consumes: `FrameAssembler` (Task 4).
- Produces: `fun qrBitmap(text: String, sizePx: Int, ecc: ErrorCorrectionLevel = M): Bitmap` using ZXing `QRCodeWriter` with a quiet zone of 4 modules, black on white (high contrast for a laptop webcam); `@Composable fun QrPlayer(frames: List<String>, fps: Int = 3)` loops the frames full width; `@Composable fun CameraScanner(onText: (String) -> Unit)` binds CameraX `Preview` and `ImageAnalysis` (back camera, `STRATEGY_KEEP_ONLY_LATEST`), decodes the Y plane with ZXing `MultiFormatReader` restricted to `QR_CODE` and `TRY_HARDER`, and calls `onText` at most once per distinct string per 300 ms.

- [ ] **Step 1: Write `QrBitmapTest`** (Robolectric is not used; use ZXing's own `QRCodeReader` on the `BitMatrix` that `qrBitmap` is built from, exposed as `internal fun qrMatrix(text, ecc)`): a 270-character frame string round-trips; the matrix includes the quiet zone.
- [ ] **Step 2: Run `./gradlew :app:testDebugUnitTest`; expected: FAIL.**
- [ ] **Step 3: Implement the three files.** Ask for the camera permission at the point of first use.
- [ ] **Step 4: Run the unit test (PASS). Manual check on the phone: add a temporary screen that scans any QR and shows its text; scan the laptop page's request frames (Task 6) and confirm a frame string appears and the progress counter completes within 3 seconds for a 12-person payroll. Note the chunk size and fps that work best in `docs/demo-plan.md` under a "Tuning" heading.**
- [ ] **Step 5: Commit** `feat(app): CameraX and ZXing scanner and QR player`.

### Task 9: Approver screens and flow

**Files:**
- Create: `android/app/src/main/kotlin/inc/pazuzu/imzo/ui/` `HomeScreen.kt`, `EnrolScreen.kt`, `ScanRequestScreen.kt`, `ReviewScreen.kt`, `AnswerScreen.kt`, `ApproverViewModel.kt`, `Theme.kt`
- Test: `android/app/src/test/kotlin/inc/pazuzu/imzo/ui/ApproverViewModelTest.kt`

**Interfaces:**
- Consumes: `Approver`, `Signer`, `CounterStore`, `FrameAssembler`, `Frames`, `enrolmentCode` (Tasks 3, 4, 7, 8).
- Produces: `class ApproverViewModel(approver: Approver, scanner: ...)` exposing `state: StateFlow<UiState>` with `sealed interface UiState { Home; Enrolling(code: String, frames: List<String>); Scanning(progress: Progress?); Reviewing(review: Review); Rejected(reason: String); Answer(frames: List<String>, approved: Boolean) }`; functions `startScan()`, `onFrame(text)`, `approve()`, `reject()`, `startEnrol()`, `onEnrolRequestFrame(text)`, `reset()`.
- Screen content: ReviewScreen shows `op`, `ref`, "Approval `k` of `n`", the **count and recomputed total in large type**, the fingerprint in monospace groups, then every item in full (name, account, amount) in a scrollable list; Approve and Reject at the bottom. EnrolScreen shows the six-digit code in large type and the `er` QR frames. Home shows a persistent line "No internet permission: talks only through QR codes" and the device id fingerprint.

- [ ] **Step 1: Write `ApproverViewModelTest`** (fake `Signer` and `CounterStore`): feeding request frames of the vector request moves `Scanning` to `Reviewing` with `count == item_count`; a request for another device moves to `Rejected("WRONG DEVICE")` and `approve()` is a no-op there; `approve()` yields `Answer` whose frames decode (via `Frames.decode`) to a response with `dec == 1`; `reject()` yields `dec == 0`; a signer that throws (cancelled biometric) returns to `Reviewing` with the same review and no counter consumed beyond the one already persisted; garbage text in `Scanning` is ignored without crashing; after `reset()` state is `Home`.
- [ ] **Step 2: Run `./gradlew :app:testDebugUnitTest`; expected: FAIL.**
- [ ] **Step 3: Implement the view model and screens** in the standard Compose style, a dark high-contrast theme, no truncation in the item list (`softWrap = true`, no `maxLines`), `keepScreenOn` while scanning and while the answer QR plays, the answer QR shown at maximum brightness.
- [ ] **Step 4: Run the unit tests (PASS). Install on the phone and check manually: a 50-item request (use the backend with 50 rows) scrolls fully and the total stays visible; rotate the phone during review and confirm the review survives; the wrong-device request shows WRONG DEVICE.**
- [ ] **Step 5: Commit** `feat(app): enrolment, review, sign and answer flow`.

### Task 10: End to end on the real phone and laptop (the demo-ready line)

**Files:**
- Create: `docs/demo-runbook.md`
- Modify: `docs/demo-plan.md` (Tuning section), `README.md` (status section, how to run), `deck/project/slides/limits.html` if what is real or mocked changed

**Interfaces:**
- Consumes: everything above.
- Produces: `docs/demo-runbook.md` with: setup checklist, enrolment script, Run 1 and Run 2 operator steps (matching `deck/project/slides/demo.html`), recovery steps for each failure seen in rehearsal, and the fallback-clip instruction.

- [ ] **Step 1: Enrol the phone** with the laptop (begin on the page, scan with the phone, show the `er` QR to the laptop webcam); expected: both screens show the same six-digit code and the page lists the device.
- [ ] **Step 2: Run 1 (normal):** send the payroll, scan, review, approve with the fingerprint, scan the answer; expected: page status `EXECUTED`, `/api/executed` lists the original 12 people, audit chain `ok`.
- [ ] **Step 3: Run 2 (attack):** turn on the tamper switch, send; expected: the page still shows the original list, the phone shows the changed account, Reject produces `REJECTED` and an `order_rejected` audit entry; the mock core received nothing for this order.
- [ ] **Step 4: Rehearse three full runs back to back, with the room's lighting and the phone on its stand; record failures and fixes in the runbook; record a screen-and-phone clip of both runs as the fallback.** Run `android/scripts/check_permissions.sh` on the final APK and keep its output for the slide.
- [ ] **Step 5: Update `README.md` (status: Android approver and backend now built and what is still mocked), run all test suites once more, and commit** `docs: demo runbook and status after end-to-end rehearsal`. Push.

### Task 11 (stretch, separate plan): Imzo Device on the T-Display S3 AMOLED

Not planned here. Start only after Task 10 passes. It needs the camera decision (UART QR scanner module recommended), a firmware port of Tasks 2 to 4 checked against the same `cases.json` and `vectors.json`, and its own plan.
