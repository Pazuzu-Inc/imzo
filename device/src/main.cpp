// Imzo Device: offline approver on the LilyGO T-Display S3 AMOLED.
//
// Input: IMQ1 text frames, one per line. In the shipping build they come only from the UART QR scanner
// module (Serial1). The development build (IMZO_DEV_SERIAL) also reads them from USB serial so the whole
// flow can be rehearsed without a camera, and accepts a few test commands (see handleDevCommand).
// Output: the screen. Nothing is ever sent anywhere; in the dev build the answer frames are also printed
// on USB serial for the rehearsal bridge.
#include <Arduino.h>

#include <string>
#include <vector>

#include "buttons.h"
#include "device_key.h"
#include "imzo_core.h"
#include "ui.h"

#ifndef IMZO_SCANNER_RX
#define IMZO_SCANNER_RX 44
#endif
#ifndef IMZO_SCANNER_TX
#define IMZO_SCANNER_TX 43
#endif
#ifndef IMZO_SCANNER_BAUD
#define IMZO_SCANNER_BAUD 9600
#endif

SET_LOOP_TASK_STACK_SIZE(24 * 1024);

using imzo::Bytes;
using imzo::ProtocolError;

namespace {

enum class Mode { Idle, Review, Enrol, Answer, Error };

Ui ui;
Buttons buttons;
DeviceKey key;
imzo::FrameAssembler assembler;

Mode mode = Mode::Idle;
Bytes ownId;
imzo::Review review;
size_t page = 0;  // 0 summary, 1..n beneficiaries, n+1 decision
imzo::EnrolmentChallenge challenge;
std::vector<std::string> answerFrames;
size_t answerIdx = 0;
uint32_t answerShownAt = 0;
std::string answerTitle;
std::string answerKind;
uint32_t errorAt = 0;
size_t answerChunk = 64;  // bytes of body per answer frame: small frames give a small, easy-to-read QR
uint32_t progressDrawnAt = 0;
float lastHold = 0;

struct LineReader {
    std::string buf;
    bool poll(Stream& s, std::string& out) {
        while (s.available()) {
            int c = s.read();
            if (c == '\n' || c == '\r') {
                if (buf.empty()) continue;
                out.swap(buf);
                buf.clear();
                return true;
            }
            if (buf.size() < 4096) buf.push_back(char(c));
        }
        return false;
    }
};
LineReader scannerLine;
#ifdef IMZO_DEV_SERIAL
LineReader usbLine;
#endif

void devlog(const std::string& s) {
#ifdef IMZO_DEV_SERIAL
    Serial.println(("IMZO " + s).c_str());
#else
    (void)s;
#endif
}

std::string hex(const Bytes& b) {
    static const char* d = "0123456789abcdef";
    std::string s;
    for (uint8_t x : b) { s.push_back(d[x >> 4]); s.push_back(d[x & 15]); }
    return s;
}

void toIdle() {
    mode = Mode::Idle;
    assembler.reset();
    answerFrames.clear();
    ui.showIdle(hex(ownId).substr(0, 8), key.counter());
    devlog("STATE idle");
}

void toError(const std::string& code, const std::string& hint) {
    assembler.reset();
    mode = Mode::Error;
    errorAt = millis();
    ui.showError(code, hint);
    devlog("STATE error " + code);
}

void showReview() {
    ui.showReviewPage(review, page);
    devlog("STATE review page=" + std::to_string(page) + "/" + std::to_string(review.count + 1));
}

std::string randomSid() {
    char b[16];
    snprintf(b, sizeof b, "%08x", (unsigned)esp_random());
    return b;
}

void showAnswerFrame() {
    ui.showQr(answerTitle, answerFrames[answerIdx], answerIdx + 1, answerFrames.size());
    answerShownAt = millis();
}

/** Counter is already persisted by respond(); only now is the answer shown. */
void toAnswer(const std::string& kind, const Bytes& body, const std::string& title) {
    answerKind = kind;
    answerTitle = title;
    answerFrames = imzo::framesEncode(kind, body, answerChunk, randomSid());
    answerIdx = 0;
    mode = Mode::Answer;
    showAnswerFrame();
    for (const std::string& f : answerFrames) devlog("ANSWER " + f);
    devlog("STATE answer " + title);
}

void dispatch(const std::string& kind, const Bytes& body) {
    if (kind == "rq") {
        review = imzo::review(body, ownId);
        page = 0;
        mode = Mode::Review;
        lastHold = 0;
        showReview();
    } else if (kind == "en") {
        challenge = imzo::parseEnrolmentChallenge(body);
        mode = Mode::Enrol;
        ui.showEnrolCode(imzo::enrolmentCode(key.publicKey()), challenge.org);
        devlog("STATE enrol code=" + imzo::enrolmentCode(key.publicKey()));
    } else {
        throw ProtocolError("WRONG KIND", "this device only takes rq and en");
    }
}

void onFrameLine(const std::string& line) {
    if (mode != Mode::Idle && mode != Mode::Error) return;  // ignore the camera while a decision is open
    try {
        if (mode == Mode::Error) mode = Mode::Idle;
        auto st = assembler.add(line);
        if (st == imzo::FrameAssembler::Status::Complete) {
            std::string kind = assembler.kind();
            Bytes body = assembler.body();
            assembler.reset();
            dispatch(kind, body);
        } else if (millis() - progressDrawnAt > 120) {
            progressDrawnAt = millis();
            ui.showProgress(assembler.kind(), assembler.have(), assembler.count());
        }
    } catch (const ProtocolError& e) {
        toError(e.code, e.detail);
    } catch (const std::exception& e) {
        toError("INTERNAL", e.what());
    }
}

void decide(bool approve) {
    try {
        Bytes resp = imzo::respond(review, approve, key, key);
        toAnswer("rs", resp, approve ? "APPROVED" : "REJECTED");
    } catch (const ProtocolError& e) {
        toError(e.code, e.detail);
    }
}

void onButton(Btn ev) {
    if (ev == Btn::None) return;
    switch (mode) {
        case Mode::Idle: break;
        case Mode::Error: toIdle(); break;
        case Mode::Review: {
            size_t last = review.count + 1;
            if (ev == Btn::AShort && page < last) { page++; showReview(); }
            else if (ev == Btn::BShort && page > 0) { page--; showReview(); }
            else if (ev == Btn::BShort && page == 0) { toIdle(); }  // abandon the order, nothing is signed
            else if (page == last && ev == Btn::AHold) decide(true);
            else if (page == last && ev == Btn::BHold) decide(false);
            break;
        }
        case Mode::Enrol:
            if (ev == Btn::AHold) {
                try {
                    Bytes ans = imzo::enrolmentAnswer(challenge, key);
                    toAnswer("er", ans, "ENROLMENT");
                } catch (const ProtocolError& e) {
                    toError(e.code, e.detail);
                }
            } else if (ev == Btn::BShort || ev == Btn::BHold) {
                toIdle();
            }
            break;
        case Mode::Answer:
            if (ev == Btn::AShort || ev == Btn::AHold) toIdle();
            break;
    }
}

#ifdef IMZO_DEV_SERIAL
/** Test commands from USB serial. Compiled out of the shipping firmware. */
void handleDevCommand(const std::string& line) {
    if (line == "PING") {
        devlog("INFO dev=" + hex(ownId) + " pub=" + hex(key.publicKey()) + " ctr=" + std::to_string((unsigned long)key.counter()));
    } else if (line == "BTN A") onButton(Btn::AShort);
    else if (line == "BTN B") onButton(Btn::BShort);
    else if (line == "HOLD A") onButton(Btn::AHold);
    else if (line == "HOLD B") onButton(Btn::BHold);
    else if (line.rfind("CHUNK ", 0) == 0) {
        long n = atol(line.c_str() + 6);
        if (n >= 20 && n <= 200) answerChunk = size_t(n);
        devlog("CHUNK " + std::to_string(answerChunk));
    } else if (line == "WIPE") {
        key.wipe();
        devlog("WIPED, rebooting");
        delay(200);
        ESP.restart();
    } else {
        devlog("UNKNOWN " + line.substr(0, 20));
    }
}
#endif

}  // namespace

void setup() {
#ifdef IMZO_DEV_SERIAL
    Serial.setRxBufferSize(8192);  // a frame line is ~300 characters; the default 256-byte buffer truncates it
    Serial.begin(115200);
#endif
    Serial1.setRxBufferSize(4096);
    Serial1.begin(IMZO_SCANNER_BAUD, SERIAL_8N1, IMZO_SCANNER_RX, IMZO_SCANNER_TX);
    buttons.begin();
    ui.begin();
    try {
        key.begin();
        ownId = imzo::deviceId(key.publicKey());
    } catch (const ProtocolError& e) {
        ui.showError(e.code, e.detail);
        while (true) delay(1000);  // no key, nothing to do
    }
    toIdle();
}

void loop() {
    std::string line;
    while (scannerLine.poll(Serial1, line)) onFrameLine(line);
#ifdef IMZO_DEV_SERIAL
    while (usbLine.poll(Serial, line)) {
        if (line.rfind("IMQ1.", 0) == 0) onFrameLine(line);
        else handleDevCommand(line);
    }
#endif
    onButton(buttons.poll());

    if (mode == Mode::Review && page == review.count + 1) {
        char side = 'A';
        float f = buttons.holdProgress(side);
        if (f > 0) {
            ui.showHold(side, f);
        } else if (lastHold > 0) {
            showReview();  // released early: redraw the page without the bar
        }
        lastHold = f;
    }
    if (mode == Mode::Answer && answerFrames.size() > 1 && millis() - answerShownAt > 900) {
        answerIdx = (answerIdx + 1) % answerFrames.size();
        showAnswerFrame();
    }
    if (mode == Mode::Error && millis() - errorAt > 10000) toIdle();
    delay(10);
}
