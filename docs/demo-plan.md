# Imzo demo plan (draft for approval)

Goal: the five-minute pitch runs live. Run 1: a 12-person payroll is scanned, reviewed on the approver and signed. Run 2: the laptop is "infected", the approver shows one changed account, the judge rejects. Finish on the audit log.

The protocol already exists and is tested in `protocol/imzo_ref.py`. The demo is mostly porting it and building the pieces around it.

## What the demo is made of

| # | Piece | What it does | Built with | Status |
| --- | --- | --- | --- | --- |
| 1 | **Approver app (Android)** | Scans request QR frames, strict-parses, recomputes totals, shows every beneficiary, signs with a Keystore key after biometric or PIN, plays the answer as QR. Enrols itself: scans the server's `en` challenge, shows the 6-digit code and a signed `er` proof-of-possession QR. No `INTERNET` permission | Kotlin, Jetpack Compose, CameraX, ZXing core (pure Java, no permissions), Android Keystore P-256, BiometricPrompt | Built; device checks pending |
| 2 | **Protocol core in Kotlin** | CBOR strict decoder and encoder, request validation, fingerprint, response signing input, `IMQ1` framing. Must pass `protocol/vectors.json` | Plain Kotlin, no Android dependencies, JVM unit tests | Done, cross-checked against the reference |
| 3 | **Backend** | Wraps the existing `imzo_ref.Server`: create order, verify answer, single use, maker-checker, hash-chained audit log, mock core banking, device registry | Python, runs on the laptop (stdlib `http.server`) | Done (stdlib http.server) |
| 4 | **Initiator page (the "infected laptop")** | Order builder with the payroll, the **tamper switch**, request QR player, webcam scan of the answer QR, audit log view | Static HTML and JS in the browser, a small QR library | Done; webcam path untested on demo hardware |
| 5 | **Demo kit** | Phone stand, laptop, webcam if the laptop camera is awkward, printed payroll list for the judge, spare phone, recorded fallback clip | Physical | To prepare |
| 6 | **Imzo Device (stretch)** | Same approver on the LilyGO T-Display S3 AMOLED with a camera | ESP32-S3 firmware | After the app works |

Everything runs on one laptop. The phone never touches the network: the only link is camera to screen and screen to camera, which is the point of the demo. Wi-Fi at the venue does not matter.

## Milestones

Pieces 2 and 3+4 only depend on the protocol, so they can run in parallel after M0.

- **M0 Toolchain.** Install Android command-line tools or Android Studio, JDK 17, adb; enable USB debugging on your phone; "hello camera" app runs on the phone.
- **M1 Protocol core in Kotlin.** Passes every vector in `vectors.json` and rejects the hostile cases from `test_imzo.py`. Runs on the Mac without a phone.
- **M2 Backend and initiator page.** Full loop with a Python stand-in for the approver, so the web side is tested before the app exists.
- **M3 Approver app.** Enrolment, scan, review screen, sign, answer QR. Check with `aapt dump permissions`: only CAMERA, USE_BIOMETRIC and an app-internal receiver permission, enforced by the checkPermissions task.
- **M4 End-to-end.** Run 1 and Run 2 on the real phone and the laptop; audit entry visible; rehearse three times; record the fallback clip. This is the demo-ready line.
- **M5 Device edition (stretch).** See below.

## Risks to watch

- **Scanning animated QR from a laptop screen** is the most likely demo failure: glare, frame rate, 200-byte chunks. Mitigation: tune chunk size and frame rate early (M2/M3), dark theme on the QR, fixed phone stand.
- **Signature format.** Android Keystore signs in DER; the protocol uses raw 64-byte r||s. Convert and test with the vectors.
- **No `INTERNET` permission can be undone by a library.** Check the merged manifest after every dependency change. This is why ZXing core, not ML Kit.
- **Emulator is not enough.** Camera and biometrics need your physical phone.
- **Honest limits stay on the slide:** protocol unreviewed, phone is not a secure element, mocked core banking.

## Imzo Device on the T-Display S3 AMOLED (stretch)

What I know and what I do not:

- The ESP32-S3 can do SHA-256 and ECDSA P-256 (mbedTLS), so signing on the device is feasible. Without a secure element, the demo key sits in flash; flash encryption or an add-on chip such as an ATECC608 would be the real design.
- I do not believe the AMOLED board has a camera connector, so a camera needs wiring. A raw OV2640 camera needs about a dozen GPIOs and QR decoding in firmware. A small **UART QR scanner module** (it decodes the QR itself and sends the text over serial) needs four wires and is far less risky. Decision for you below.
- Firmware must reimplement strict CBOR parsing and the checks in C++ and pass the same vectors. About as much work as the Kotlin port.
- The 536x240 screen is wide and short: it fits the order summary and the answer QR on separate screens, not a 12-row list at once. Scroll with the buttons.

Recommended order: finish the app first, then build the device against the same vectors, so Run 1 can also be shown on hardware.

## Decisions (answered 2026-10-17)

1. **Deadline:** end of next week, about 8 working days from 2026-10-17. Plan below assumes the demo is on Fri 2026-10-23 or Sat 2026-10-24.
2. **Order of work:** Android first, ESP32 device after. The camera for the device is not chosen yet; decide when the app is working (UART QR scanner module still recommended).
3. **Operator:** you drive the laptop on stage. Webcam availability not confirmed; the laptop's own camera is the default, and M2 tests it.

## Schedule (draft)

| Day | Work |
| --- | --- |
| 1 | M0 toolchain and hello-camera on your phone; start M1 (Kotlin protocol core) |
| 2 | M1 passes all vectors. In parallel: M2 backend HTTP layer |
| 3 | M2 initiator page with QR player and webcam scan; early scan-speed test against the phone |
| 4-5 | M3 approver app: enrolment, scan, review screen, sign, answer QR |
| 6 | M4 end-to-end Run 1 and Run 2 |
| 7 | Rehearsals, recorded fallback clip, slide check against the real app |
| 8 | Buffer. Device work starts only if everything above is done |

The device is **not on the critical path**. The pitch already presents it as the roadmap edition. If M4 lands early, a minimal device (scan, review, button, sign, answer QR) is the next target; otherwise it follows after the demo.

## My defaults (confirmed by going ahead)

Kotlin and Compose for the app; Python backend on the laptop; static web page for the initiator; ZXing instead of ML Kit; the app enrols by showing a QR that the laptop scans; payroll data is the 12 fake people from the demo script.

## Tuning (pending device test)

To fill in after the first device run of the Scan order screen: chunk size (default 200 bytes, about 290 characters per frame, QR version 13 at ECC M), request player fps, scan time for the 12-person payroll, and what worked on the laptop screen (brightness, distance, ECC level).
