# Imzo Device (ESP32-S3) approver: design

Status: draft, written 2026-10-17. Scope decided with the user in chat: the Android app stays as it is; the LilyGO T-Display S3 AMOLED becomes a second approver for the same protocol, so the presentation can show both editions. The camera is not chosen yet (arrives 2026-10-18), so everything is built against a text-line frame input and the camera is added after.

## Goal

Run 1 and Run 2 of `docs/demo-runbook.md` work with the device in place of the phone: it takes the request QR frames, shows every beneficiary on its own screen, signs only after a physical button hold, and shows the answer as a QR the laptop webcam reads. The backend and the page are unchanged: `IMQ1` frames, deterministic CBOR and P-256 signatures are the only contract.

## Non-goals

- No secure element, no flash encryption, no secure boot: the demo key sits in NVS flash. Said plainly on the slide.
- No radios in the shipping firmware path: Wi-Fi and Bluetooth are never initialised.
- Changes to the protocol, backend, web page or Android app.
- Camera driver for a raw OV2640 (only if the camera turns out to be that; see "Camera").

## Anti-goals

- Showing or signing anything the strict parser did not accept. A message the device cannot display fully is refused, never shortened.
- A second source of truth for the protocol. The Python reference stays the truth; the C++ core must pass `protocol/cases.json`.

## Parts

| Part | Where | What |
| --- | --- | --- |
| Protocol core | `device/lib/imzo_core/` | Portable C++17, no Arduino or ESP headers: strict CBOR, request validation, SHA-256, fingerprint, enrolment code, `IMQ1` frames, review, response and enrolment-answer building. Signing goes through a `Signer` interface. |
| Host tests | `device/host/` | The core built with the host compiler and driven by `test_core.py` over `protocol/cases.json`, plus byte-for-byte checks of what gets signed against `imzo_ref.py`. |
| Firmware | `device/src/` | PlatformIO project for the board: display, two buttons, NVS key and counter, mbedTLS ECDSA, QR drawing, frame input. |
| Dev bridge | `device/tools/bridge.py` | Camera stand-in for rehearsal without a camera: moves frames between the real backend and the device over USB serial. Not part of the shipping path. |

## Decisions (my calls unless marked)

- Toolchain: PlatformIO, Arduino-ESP32 framework (user approved the install).
- Frame input: one `IMQ1...` text line per frame, newline terminated. Tonight it arrives on USB serial from the bridge; with a UART QR scanner module it arrives on a hardware UART in the same format (such modules print the decoded text). Both routes feed the same assembler. USB serial input is a build flag for development.
- Text safety: the device has no Unicode tables. It accepts names and references only from a fixed set it can both render and prove NFC for (ASCII printable, Latin-1 supplement, Latin Extended-A/B, basic Cyrillic, U+02BB/U+02BC modifier letters, common punctuation) and refuses everything else with `UNSUPPORTED TEXT`. This is stricter than the server and fails closed. Combining marks are refused outright. The shared cases are checked with this allowance written out in the test, not hidden.
- Key: P-256, generated on first boot with mbedTLS, stored in NVS. Counter is written to NVS before the answer is shown (persist before show), as in the app.
- Consent: one button only (BOOT; the board's second button is the chip reset). One click advances through the pages; on the decision page 1 click = review again, 2 clicks = reject, 3 clicks = approve. Reject is the safe action, so it is the easy one; approve is the deliberate one. Each choice is then confirmed on a second page (2 clicks yes, 1 click no; clicks in the first 0.7 s are ignored; 20 s idle goes back unsigned). No PIN (no keypad).
- Enrolment: the device is enrolment-ready whenever it is idle; an `en` message shows the 6-digit code and waits for a button press before it shows the `er` reply QR.
- Screen: 536x240 landscape. Pages: summary (operation, reference, total, count, fingerprint), one beneficiary per page (name, full account, amount, position `7/12`), decision. Nothing is truncated; a name that does not fit is wrapped.

## Camera

Undecided. If a UART QR scanner module: read lines from the UART (4 wires, 3.3 V). If an ESP32-CAM or OV2640: it is a separate project (QR decode in firmware) and the device would stay on the bridge for the presentation; we decide tomorrow.

## What is left to the builder

Pin numbers beyond the two buttons, font choice, exact page layout, QR module size, serial baud rate.
