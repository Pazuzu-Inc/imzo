# Demo runbook (DRAFT)

Draft written before the first test on a real phone. The "still pending" section lists everything that has only been compiled or unit-tested so far; tick those off on the phone and then update the tuning numbers here and in `docs/demo-plan.md`.

The stage story is slide `deck/project/slides/demo.html` (1:00 to 3:15): the presenter speaks, the operator drives. Run 1 builds the 12-person payroll, plays the QR frames, a judge reviews on the phone and confirms, the mock core executes. Run 2 flips the tamper switch and repeats: the laptop still shows the original list, the phone shows the changed account, a second judge holds the printed list and says no. Finish on the audit log entry. If the live demo breaks, switch to the recorded clip and say so.

## Setup checklist

### Phone (Android 11 or newer, app installed with `./gradlew :app:installDebug`, or the release build for the FLAG_SECURE check)

- [ ] Screen lock set (PIN, pattern or password) **and a fingerprint enrolled**. Without a screen lock the app shows "SET A SCREEN LOCK".
- [ ] Airplane mode on (the app has no internet permission anyway; this shows the audience nothing leaves the phone). Wi-Fi and Bluetooth off.
- [ ] Brightness at maximum, auto-brightness off (the answer QR is read by the laptop webcam).
- [ ] Do not disturb on, notifications hidden on the lock screen, no incoming-call pop-ups over the camera.
- [ ] Auto-rotate off (portrait) unless a rotation check is being done; screen timeout set to the longest value; battery above 50 percent.
- [ ] Phone stand in place, camera lens clean, phone about 25 to 40 cm from the laptop screen.
- [ ] Camera permission granted to Imzo (first "Scan order" asks).
- [ ] Spare phone charged, with the app installed and enrolled the same way, if one is available.

### Laptop

- [ ] Start the backend from the repo root: `.venv/bin/python backend/app.py` (serves on `127.0.0.1:8080`, state is in memory).
- [ ] Open **Chrome** at <http://127.0.0.1:8080/>. Use exactly this address: the backend refuses any other `Host` header (for example the machine's LAN address or a tunnel) with 403 FORBIDDEN.
- [ ] Allow the camera for that page. Choose the webcam deliberately: the built-in camera by default, an external webcam if it sees the phone screen better; if Chrome picks the wrong one, change it in the camera icon in the address bar and reload.
- [ ] Display brightness at maximum, screen lock and sleep disabled, notifications off, battery saver off.
- [ ] Browser zoom so the request QR is large (the page sizes the QR to the column; start at 100 percent and increase if the phone struggles); QR canvas not covered by anything; no glare on the screen.
- [ ] fps field at 3 and error correction at M to start (see tuning below); adjust after the first real scan test.
- [ ] Printed payroll list ready for the judge in Run 2 (the 12 names and accounts from `web/payroll.json`; total 53,515,500.00 UZS). Row 7, Jasur Nazarov, is the one the tamper switch changes: account `86001536904661629361` becomes `...629362` on the phone.
- [ ] Recorded fallback clip open in a second window or tab, paused at the start (see "Fallback clip").
- [ ] Optional: `.venv/bin/python -m unittest discover -s backend` and `node --test "web/test/*.test.mjs"` pass on this machine.

## Enrolment script (once, before the demo, then keep the state)

Do this before the audience arrives. Enrolment survives only as long as the backend process runs.

1. On the page, press **Reset server** (clean audit log).
2. Section "1. Enrolment": user `alice`, press **Begin enrolment**. The big status shows PENDING and an enrolment QR plays.
3. Phone: **Enrol this device**. Scan the QR on the laptop screen. The phone shows a 6-digit code and "Check it matches the laptop" and then a reply QR.
4. Show the phone's reply QR to the laptop webcam (the "answer frames" counter on the page climbs). The page shows ENROLLED and a large code.
5. **Both screens must show the same 6-digit code.** If not, stop: do not continue, press Reset server and start again.
6. Press **Done** on the phone. Press **Fetch audit log**: `enrolment_started`, `device_registered`, chain ok.

Run 1 and Run 2 use maker `mike`, approver `alice`, reference "Payroll October" (the defaults on the page). The maker and the approver must be different users, otherwise the server refuses with MAKER CANNOT APPROVE.

## Run 1: normal (operator steps)

1. Page: "Demo: infected laptop" is **unchecked**. The table shows the 12 people and the total.
2. Press **Send to approver**. The status shows PENDING and the request QR plays (3 frames per second by default). The webcam preview starts.
3. Phone: **Scan order**. Hold the phone steady at the laptop screen until the progress completes and the review screen opens.
4. The judge reads the review screen on the phone: it recomputes the total from the signed bytes and lists every beneficiary. The presenter points out that the total matches the laptop.
5. Judge presses **Approve** and authenticates with the fingerprint (or the screen-lock PIN).
6. Phone shows APPROVED and the answer QR. Show it to the laptop webcam.
7. Page status turns **EXECUTED**. Press **Fetch audit log**: `order_created`, `approval_recorded`, `order_executed`, chain ok.

## Run 2: the laptop is infected (operator steps)

1. Page: tick **Demo: infected laptop**. The table on the laptop does not change (it still shows the original list).
2. Press **Send to approver**. Request QR plays as before.
3. Phone: **Scan order**, scan, review screen opens. The account of row 7 differs from the laptop and from the printed list in its last digit. The total is the same, which is why only a person comparing accounts can catch it.
4. The second judge holds the printed list, compares row 7 and says no. Press **Reject** and authenticate with the fingerprint.
5. Phone shows REJECTED and the answer QR. Show it to the webcam.
6. Page status turns **REJECTED**. Press **Fetch audit log**: the `order_rejected` row is the last entry, chain ok. Finish on that row.
7. Remember (and say it if asked): had the judge approved, the server would have executed what it stored, the tampered list. Only the approver can catch the swap. `backend/test_app.py` documents this.

Between runs without a reset: nothing to do; each press of Send creates a new order. Before a full second rehearsal, press **Reset server** and enrol again.

## Still pending: checks that need the phone

None of these has been run on a real device; the on-device tests (`connectedDebugAndroidTest`) are only compiled so far. Copied from the reviewers' lists:

- Prepare phone: secure lock + fingerprint; run `./gradlew :app:connectedDebugAndroidTest` BEFORE any demo enrolment (it uninstalls the app and wipes key/counter; or pass `-Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true`).
- KeyInfo checks: auth required, 0 s validity, security level (StrongBox vs TEE).
- Prompt behaviour: per-use prompt every time, PIN path, cancel, wrong finger, lockout notice.
- Scan the laptop page's request frames: time for the 12-person payroll, 50-item payroll; tune chunk size/fps; answer QR readable by the laptop webcam; max brightness.
- Review screen: 50 items scroll fully, header + buttons visible in portrait, landscape and at max font size; wrong-device order shows WRONG DEVICE without buttons; rotation during the prompt.
- Enrolment: code matches laptop; cancel during enrolment; no-screen-lock message; key-lost message.
- Release build: FLAG_SECURE blocks screenshots; no probe button.
- Enrol an extra fingerprint then sign (observe key invalidation); remove and reset screen lock then sign (expect "device key missing; enrol this device again").

Results to record here once known: scan time 12-person payroll ___ s; 50-item payroll ___ s; chunk size ___ bytes; fps ___; ECC ___; security level ___ (StrongBox or TEE).

## Recovery

| Symptom | What to do |
| --- | --- |
| **The phone does not pick up the request QR** | Check distance (try 20 to 40 cm) and steadiness (use the stand). Raise the laptop display brightness and remove glare. Lower fps to 2 or 1 (field on the page) so each frame stays long enough; if frames are too dense, try error correction L or M and zoom the browser in. Keep the QR fully visible and the screen clean. Progress on the phone counts parts found, so frames can arrive in any order and repeats are fine: just keep scanning. |
| **The laptop does not read the answer QR** | Phone brightness at maximum, hold the phone close and flat to the webcam, screen free of glare, "answer frames" counter on the page shows how many parts arrived. Try the other webcam. Last resort (rehearsal only): the dev-only paste box on the page. |
| **Page shows an error such as BAD SIGNATURE, NOT PENDING, UNKNOWN ORDER, NO DEVICE** | NO DEVICE: nobody is enrolled under that approver name; enrol again. NOT PENDING: this answer was already used; send a new order. BAD SIGNATURE on enrolment: the QR belongs to an older challenge (challenges expire after 5 minutes and only the 32 newest are kept); press Begin enrolment again. |
| **Biometric lockout** (too many wrong fingers) | The app shows a lockout notice. Wait for the lockout to end, or use the screen-lock PIN in the prompt. Fall back to the spare phone or the clip. |
| **Key lost** (phone shows DEVICE KEY LOST, or "device key missing; enrol this device again") | The key was removed or invalidated (screen lock removed, extra fingerprint enrolled). Android Settings, Apps, Imzo, Storage, **Clear data**. Reopen the app, press **Reset server** on the page (or `curl -X POST -H 'Content-Type: application/json' -d '{}' http://127.0.0.1:8080/api/reset`), then run the enrolment script again. |
| **Backend restarted or crashed** | All state is in memory: devices, orders and audit log are gone and the phone is no longer registered. Restart `.venv/bin/python backend/app.py`, reload the page, run the enrolment script again. The phone keeps its own key and counter; no data clearing is needed. |
| **Page returns 403 FORBIDDEN** | The address in the browser is not `127.0.0.1:8080` or `localhost:8080`. Open <http://127.0.0.1:8080/>. |
| **Camera blocked on the page** | Chrome camera icon in the address bar, allow and reload. Check no other app holds the webcam. |
| **Anything else is on fire** | Switch to the fallback clip and say so. |

## Fallback clip

Record the clip during the last full rehearsal (screen capture of the laptop plus a view of the phone): Run 1 and Run 2 end to end, ending on the audit log. Keep it on the laptop, open and paused in a second window before going on stage. If the live demo breaks, switch to it, say plainly that this is a recording, and carry on with the same talking points. Do not try to fix a live failure on stage for more than a few seconds.
