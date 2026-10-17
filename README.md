# Imzo: hackathon workspace

Concept: a separate approver that shows exactly what a business payment order contains and signs only that.
This folder is its own git repository (Pazuzu-Inc/imzo).

## Two editions of the approver

Imzo is one protocol with two ways to run the approver, so a client can choose by risk and budget:

- **Imzo Device:** a dedicated, fully offline pocket device with a small screen (shows the order, then the answer QR), a camera and a secure element. No radios. This is the highest-assurance option. A first version runs on a LilyGO T-Display S3 AMOLED (key in flash, no secure element); the camera is not attached yet.
- **Imzo Approver app:** an Android app with no `INTERNET` permission; camera is the only runtime permission (biometric is an install-time permission); key in the Keystore. It is cheap and quick to roll out. The demo and the pilot use this edition.

Both editions talk to the world only through QR frames. Details and limits: [`docs/editions.md`](docs/editions.md).

## What is here

| Path | What it is |
| --- | --- |
| `protocol/imzo_ref.py` | Reference implementation of protocol v1: deterministic CBOR, strict parser, request/response, signing, verification, maker-checker, hash-chained audit log, `IMQ1` QR framing |
| `protocol/test_imzo.py` | Reference tests (hostile input, replay, tampering, counters, expiry, lock-out, enrolment, framing, vectors) |
| `protocol/demo.py` | Terminal replay of the on-stage demo: normal run, then the attack run |
| `protocol/vectors.json` | Test vectors for the Kotlin and backend code (verify the signature; signatures are randomised) |
| `protocol/make_cases.py` | Generates `protocol/cases.json` (shared accept/reject cases) from the reference |
| `protocol/cases.json` | The shared cases the Kotlin port is tested against |
| `protocol/test_cases.py` | Checks `cases.json` against the Python reference |
| `protocol/test_kotlin_cross.py` | Checks the answers the Kotlin approver produced (written by `./gradlew :protocol:test`) with the Python server |
| `backend/` | `app.py`: stdlib HTTP service around the reference `Server` (orders, answers, enrolment, audit log); `test_app.py`: its tests |
| `web/` | The initiator page (the "untrusted laptop"): order builder, tamper switch, QR player, webcam scanner; `web/test/` has its Node tests |
| `android/` | The Android approver app (`:app`) and its pure-Kotlin protocol port (`:protocol`) |
| `device/` | The Imzo Device firmware for the LilyGO T-Display S3 AMOLED (ESP32-S3): portable C++ protocol core, host tests, firmware, rehearsal bridge. See `device/README.md` |
| `docs/dev-setup.md` | Toolchain, build, test and phone setup on the Mac |
| `docs/demo-runbook.md` | Setup checklist, enrolment script, Run 1 and Run 2, recovery table, checks still pending on a phone |
| `docs/editions.md` | The two editions (device and app): what "no internet" means, limits, target spec |
| `docs/demo-plan.md` | The demo plan: pieces, status, milestones, risks |
| `diagrams/*.mmd` | Mermaid sources: architecture, approval sequence, enrolment sequence, order and device state machines, class model. Render at https://mermaid.live |
| `diagrams/payroll-approval.bpmn` | BPMN 2.0 swimlane (four lanes) with a real loop for the second approver. Open in https://demo.bpmn.io or Camunda Modeler |
| `diagrams/make_bpmn.py` | Generator for the `.bpmn` file; edit its tables and re-run |
| `deck/` | Slide sources (see the notes below) |
| `.venv/` | Python virtual environment holding the `cryptography` package; safe to delete and recreate |

## Run the reference implementation

```bash
cd protocol
../.venv/bin/python -m unittest -v     # reference tests
../.venv/bin/python demo.py            # the stage story in the terminal
../.venv/bin/python imzo_ref.py > vectors.json
```

To recreate the environment: `python3 -m venv .venv && .venv/bin/pip install cryptography`.

Other test suites (from the repo root; full setup in [`docs/dev-setup.md`](docs/dev-setup.md)):

```bash
.venv/bin/python -m unittest discover -s backend      # HTTP backend
node --test "web/test/*.test.mjs"                     # initiator page logic
(cd android && ./gradlew :protocol:test :app:testDebugUnitTest --offline)   # Kotlin port and Android app, JVM only
```

## Status and honest limits

- Built and unit-tested: the Python reference; the Kotlin protocol port, cross-checked against it; the backend HTTP service; the initiator page logic; the Android app, by JVM tests only.
- Run end to end once on a real phone (Redmi 9, Android 12): instrumented tests 6/6, enrol, approve, reject. Scan timings, other phones and the remaining checks are not done; they are listed in [`docs/demo-runbook.md`](docs/demo-runbook.md).
- Enrolment on the demo backend is open: no key attestation, no admin approval.
- The protocol is a draft. It has had no independent security review.
- `payroll-approval.bpmn` was checked for structure (parses, every element has a lane and a diagram shape, no flow crosses a task). It has not been opened in a BPMN editor.
- The Mermaid files parse; they have not been rendered to images.
