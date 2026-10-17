# Imzo editions: one protocol, two approvers

Imzo is the protocol and the rule that the approver shows what it signs. Clients choose where the approver runs.

| | **Imzo Device** | **Imzo Approver app** |
| --- | --- | --- |
| What it is | Dedicated pocket device: small screen, camera, secure element, no radios | Android app on a phone the client already owns or buys cheaply |
| Network | No Wi-Fi, Bluetooth, cellular or data port. Power-only USB | No `INTERNET` permission in the manifest, so the app cannot open a socket. Camera is the only permission it asks for at runtime (plus install-time biometric) |
| Key | In the secure element, never exported | In the Android Keystore (StrongBox where present), never exported. Requires a screen lock; removing the lock destroys the key and the device must be enrolled again |
| Channel to the world | Camera in, QR on screen out | Camera in, QR on screen out |
| Assurance | Highest: nothing else runs on it | Good: depends on the phone and OS being kept clean |
| Cost and speed | Hardware, supply chain, shipping | Install in minutes; the pilot and the hackathon demo use this edition |
| Best for | High-value payrolls and treasury | SMEs, pilots, backup approvers |

Both editions speak the same protocol (`IMQ1` QR framing, deterministic CBOR, P-256 signatures), so the backend does not care which one signed. The device registry records the edition so policy can differ, for example "payrolls above a threshold need an Imzo Device".

## The app edition: what "no internet" means

Properties we can state and check:

- The manifest declares no `INTERNET` permission. `aapt dump permissions` shows only camera and biometric (plus an app-internal receiver permission). Without it the app cannot open a network socket itself, with no code path to audit.
- No analytics, crash reporter, ad or update SDKs. The dependency list is short enough to review.
- Backup and device transfer excluded; screenshots blocked in release builds; the signing key is non-exportable.
- StrongBox is used where the phone has it, else the TEE. Which one a given phone's key ended up in is checked with `KeyInfo` on the device and is not claimed otherwise.
- A screen lock is required; removing the lock destroys the key and the device must be enrolled again.
- Install from a signed APK or a managed store, never from the open internet.

Limits, said plainly:

- A phone is a general-purpose device. The app cannot stop other apps, a rooted OS or a compromised OS from doing harm. This is why the Device edition exists.
- Recommended deployment: a spare phone kept in airplane mode, used for nothing else.
- Key attestation at enrolment (not built yet) lets the server refuse unverified phones.
- No independent security review yet.

## The device edition: target spec

Not built. Needs hardware partners and a design review. Target:

- Camera for scanning request QR frames; display big enough for the order list and for the answer QR.
- Secure element holding the P-256 key; confirm by physical button plus PIN.
- No radios. Charging only, no data lines.
- Signed firmware; the QR protocol is its only input.
- The firmware reuses the reference logic in `protocol/imzo_ref.py` (strict parser, recomputed totals, counters).

## Where each edition appears

- **Demo and pilot:** the app edition on spare Android phones in airplane mode.
- **Roadmap:** the device edition, then key attestation, then batch transfer above 50 items.
