# Imzo: hackathon workspace

Concept: a separate approver that shows exactly what a business payment order contains and signs only that.
This folder is outside any project repository on purpose.

## What is here

| Path | What it is |
| --- | --- |
| `protocol/imzo_ref.py` | Reference implementation of protocol v1: deterministic CBOR, strict parser, request/response, signing, verification, maker-checker, hash-chained audit log, `IMQ1` QR framing |
| `protocol/test_imzo.py` | 31 tests (hostile input, replay, tampering, counters, expiry, lock-out, framing, vectors) |
| `protocol/demo.py` | Terminal replay of the on-stage demo: normal run, then the attack run |
| `protocol/vectors.json` | Test vectors for the Kotlin and backend code (verify the signature; signatures are randomised) |
| `diagrams/*.mmd` | Mermaid sources: architecture, approval sequence, enrolment sequence, order and device state machines, class model. Render at https://mermaid.live |
| `diagrams/payroll-approval.bpmn` | BPMN 2.0 swimlane (four lanes) with a real loop for the second approver. Open in https://demo.bpmn.io or Camunda Modeler |
| `diagrams/make_bpmn.py` | Generator for the `.bpmn` file; edit its tables and re-run |
| `deck/` | Slide sources (see the notes below) |
| `.venv/` | Python virtual environment holding the `cryptography` package; safe to delete and recreate |

## Run the reference implementation

```bash
cd protocol
../.venv/bin/python -m unittest -v     # 31 tests
../.venv/bin/python demo.py            # the stage story in the terminal
../.venv/bin/python imzo_ref.py > vectors.json
```

To recreate the environment: `python3 -m venv .venv && .venv/bin/pip install cryptography`.

## Status and honest limits

- The protocol is a draft. It has had no independent security review.
- Tested here: the Python reference only. The Android approver, backend service and initiator page are not built.
- `payroll-approval.bpmn` was checked for structure (parses, every element has a lane and a diagram shape, no flow crosses a task). It has not been opened in a BPMN editor.
- The Mermaid files parse; they have not been rendered to images.
