# Imzo Device (ESP32-S3, LilyGO T-Display S3 AMOLED)

A second approver for the same protocol as the Android app: it takes `IMQ1` frames, shows every beneficiary on its 536x240 screen, signs after three clicks of the BOOT button and a second "are you sure" step and shows the answer as a QR. Only BOOT is usable as a control; the board's other button resets the chip. Design: [`docs/superpowers/specs/2026-10-17-imzo-device-design.md`](../docs/superpowers/specs/2026-10-17-imzo-device-design.md).

| Path | What |
| --- | --- |
| `lib/imzo_core/` | Portable C++17 protocol core (strict CBOR, request validation, SHA-256, fingerprint, `IMQ1` frames, review, respond). No Arduino headers. |
| `host/` | Host build of the core plus `test_core.py`, which checks it against `protocol/cases.json` and `imzo_ref.py` (including a 3000-case mutation fuzz, built with ASan/UBSan). |
| `src/` | Firmware: screen (`ui.cpp`), buttons, NVS key and counter with mbedTLS ECDSA (`device_key.cpp`), main state machine. |
| `lib/u8g2_stub/` | One-line stand-in for U8g2's header, so the GFX library's bundled Unifont can be used without downloading U8g2. |
| `tools/bridge.py` | Camera stand-in for rehearsal: moves frames between the real backend and the device over USB serial. Dev only. |

## Toolchain (one time)

PlatformIO lives in a venv, nothing is installed system-wide:

```bash
python3 -m venv ~/dev/pio-venv && ~/dev/pio-venv/bin/pip install platformio
cd device && ~/dev/pio-venv/bin/pio run -e tdisplay_amoled      # first build downloads the espressif32 platform (about 1.5 GB) and two libraries
```

Libraries (pinned in `platformio.ini`): GFX Library for Arduino 1.4.9 (newer versions need Arduino-ESP32 core 3, this platform is core 2.0.17) and ricmoo/QRCode 0.0.1.

## Tests (no hardware)

```bash
.venv/bin/python -m unittest discover -s device/host -v
```

The device refuses text it cannot prove NFC for or show (`UNSUPPORTED TEXT`); that is stricter than the reference and the tests only allow that direction.

## Flash

```bash
cd device && ~/dev/pio-venv/bin/pio run -e tdisplay_amoled -t upload --upload-port /dev/cu.usbmodem143301
```

If the upload fails with "The chip stopped responding" or "Device not configured", the board may be in a boot loop (a half-written image). Put it into download mode by hand: unplug USB, hold the BOOT button, plug in while holding, release after two seconds, then run the upload again.

## Rehearse without a camera

The dev firmware (`-DIMZO_DEV_SERIAL` in `platformio.ini`) also reads frames and a few test commands from USB serial. With the backend running (`.venv/bin/python backend/app.py`):

```bash
~/dev/pio-venv/bin/python device/tools/bridge.py reset
~/dev/pio-venv/bin/python device/tools/bridge.py enrol alice                      # the script clicks for the device
~/dev/pio-venv/bin/python device/tools/bridge.py order alice --decision approve   # Run 1
~/dev/pio-venv/bin/python device/tools/bridge.py order alice --tamper --decision reject   # Run 2
~/dev/pio-venv/bin/python device/tools/bridge.py order alice --decision manual    # you click the button on the device
```

For the shipping firmware remove `-DIMZO_DEV_SERIAL`: the device then reads frames only from the scanner UART (`Serial1`, RX GPIO44, TX GPIO43, 9600 baud, override with `-DIMZO_SCANNER_BAUD=...`).

## Honest limits

- The key sits in NVS flash: no secure element, no flash encryption, no secure boot. Anyone with the board and a USB cable can read it out.
- The camera path is untested until the scanner module is attached.
- The answer QR is small: the display is about 44 x 20 mm. A laptop webcam has to be close; chunk size is set with the `CHUNK n` dev command (default 64 bytes per frame).
- The screen layout has not been looked at by a person yet (see the runbook checks).
