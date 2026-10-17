#include "ui.h"

#include <Arduino.h>
#include <Arduino_GFX_Library.h>
#include <qrcode.h>

// LilyGO T-Display S3 AMOLED (1.91", 240x536 RM67162 over QSPI), pins from the vendor board table.
static Arduino_DataBus* bus = new Arduino_ESP32QSPI(6 /*CS*/, 47 /*SCK*/, 18 /*D0*/, 7 /*D1*/, 48 /*D2*/, 5 /*D3*/);
static Arduino_RM67162* gfx = new Arduino_RM67162(bus, 17 /*RST*/, 0 /*rotation*/);

namespace {

const int W = 536;
const int H = 240;
const uint16_t C_BLACK = 0x0000, C_WHITE = 0xFFFF, C_GREY = 0x8410, C_AMBER = 0xFD20, C_GREEN = 0x07E0, C_RED = 0xF800, C_CYAN = 0x07FF;

// The font is Unifont (8 px per letter at size 1, every letter in the allowed set is single width).
const int CH = 8;   // glyph width at size 1
const int LINE = 18;  // line pitch at size 1

/** Number of code points in UTF-8 text. */
size_t cpLen(const std::string& s) {
    size_t n = 0;
    for (unsigned char c : s) n += (c & 0xC0) != 0x80;
    return n;
}

/** Split UTF-8 text into lines of at most `cols` code points. Never drops or abbreviates text. */
std::vector<std::string> wrap(const std::string& s, size_t cols) {
    std::vector<std::string> lines;
    std::string cur;
    size_t n = 0;
    for (size_t i = 0; i < s.size();) {
        size_t len = 1;
        unsigned char c = s[i];
        if (c >= 0xF0) len = 4; else if (c >= 0xE0) len = 3; else if (c >= 0xC0) len = 2;
        if (n == cols) { lines.push_back(cur); cur.clear(); n = 0; }
        cur.append(s, i, len);
        n++;
        i += len;
    }
    lines.push_back(cur);
    return lines;
}

void text(int x, int baseline, const std::string& s, int size, uint16_t color) {
    gfx->setTextSize(size);
    gfx->setTextColor(color);
    gfx->setCursor(x, baseline);
    gfx->print(s.c_str());
}

void textRight(int xRight, int baseline, const std::string& s, int size, uint16_t color) {
    text(xRight - int(cpLen(s)) * CH * size, baseline, s, size, color);
}

void textCentre(int baseline, const std::string& s, int size, uint16_t color) {
    text((W - int(cpLen(s)) * CH * size) / 2, baseline, s, size, color);
}

void header(const std::string& left, uint16_t color, const std::string& right = "") {
    gfx->fillRect(0, 0, W, 30, color);
    text(10, 22, left, 1, C_BLACK);
    if (!right.empty()) textRight(W - 10, 22, right, 1, C_BLACK);
}

void footer(const std::string& s) {
    gfx->fillRect(0, H - 26, W, 26, 0x2104);
    text(10, H - 8, s, 1, C_WHITE);
}

void clear() { gfx->fillScreen(C_BLACK); }

}  // namespace

void Ui::begin() {
    pinMode(38, OUTPUT);  // display power enable on the vendor board
    digitalWrite(38, HIGH);
    delay(100);
    gfx->begin();
    gfx->setRotation(1);  // landscape 536x240
    gfx->setUTF8Print(true);
    gfx->setFont(u8g2_font_unifont_h_utf8);
    setBrightness(255);
    clear();
    textCentre(130, "IMZO", 4, C_WHITE);
}

void Ui::setBrightness(uint8_t v) {
    bus->beginWrite();
    bus->writeC8D8(0x51, v);  // RM67162 "write display brightness"
    bus->endWrite();
}

void Ui::showIdle(const std::string& id, uint64_t counter) {
    clear();
    header("IMZO DEVICE", C_AMBER, "offline approver");
    textCentre(100, "Ready", 3, C_WHITE);
    textCentre(140, "Show the request QR to the camera", 1, C_GREY);
    text(10, 190, "Device " + id, 1, C_GREY);
    textRight(W - 10, 190, "Signed " + std::to_string((unsigned long)counter), 1, C_GREY);
    footer("No radios. Nothing leaves this device except the answer QR.");
}

void Ui::showProgress(const std::string& kind, int have, int count) {
    clear();
    header("SCANNING", C_CYAN, kind == "rq" ? "request" : kind == "en" ? "enrolment" : kind);
    textCentre(110, std::to_string(have) + " / " + std::to_string(count), 4, C_WHITE);
    int bw = 400, bx = (W - bw) / 2;
    gfx->drawRect(bx, 150, bw, 22, C_WHITE);
    if (count > 0) gfx->fillRect(bx + 2, 152, (bw - 4) * have / count, 18, C_GREEN);
    footer("Keep the QR in view until every part is read.");
}

void Ui::showError(const std::string& code, const std::string& hint) {
    clear();
    header("REFUSED", C_RED);
    textCentre(105, code, 2, C_WHITE);
    if (!hint.empty()) {
        auto lines = wrap(hint, (W - 20) / CH);
        int y = 145;
        for (size_t i = 0; i < lines.size() && i < 3; i++, y += LINE) text(10, y, lines[i], 1, C_GREY);
    }
    footer("Nothing was signed.  Press any button.");
}

void Ui::showMessage(const std::string& title, const std::string& line) {
    clear();
    header(title, C_AMBER);
    textCentre(120, line, 1, C_WHITE);
}

void Ui::showReviewPage(const imzo::Review& r, size_t page) {
    clear();
    const size_t n = r.count;
    const std::string total = imzo::formatAmount(r.total) + " UZS";
    if (page == 0) {
        header("REVIEW " + r.op, C_AMBER, "approval " + std::to_string(r.apK) + " of " + std::to_string(r.apN));
        auto refLines = wrap(r.ref, (W - 20) / CH);
        int y = 56;
        for (size_t i = 0; i < refLines.size() && i < 2; i++, y += LINE) text(10, y, refLines[i], 1, C_WHITE);
        text(10, 100, "TOTAL (recomputed)", 1, C_GREY);
        text(10, 140, total, 2, C_GREEN);
        text(10, 176, std::to_string((unsigned long)n) + (n == 1 ? " beneficiary" : " beneficiaries"), 2, C_WHITE);
        text(10, 206, "Check code: " + r.fingerprint, 1, C_CYAN);
        footer("A: first beneficiary    B: cancel");
    } else if (page <= n) {
        const imzo::Item& it = r.items[page - 1];
        header("BENEFICIARY", C_AMBER, std::to_string((unsigned long)page) + " / " + std::to_string((unsigned long)n));
        auto nameLines = wrap(it.name, (W - 20) / (CH * 2));
        int y = 62;
        for (const std::string& l : nameLines) { text(10, y, l, 2, C_WHITE); y += LINE * 2; }
        text(10, 130, "ACCOUNT", 1, C_GREY);
        text(10, 160, it.account, 2, C_CYAN);
        text(10, 190, "AMOUNT", 1, C_GREY);
        text(100, 192, imzo::formatAmount(it.amount) + " UZS", 2, C_GREEN);
        footer("A: next    B: back");
    } else {
        header("DECISION", C_AMBER);
        text(10, 62, "Total", 1, C_GREY);
        text(10, 100, total, 2, C_GREEN);
        text(10, 134, std::to_string((unsigned long)n) + (n == 1 ? " beneficiary" : " beneficiaries") + "   check " + r.fingerprint, 1, C_CYAN);
        gfx->fillRect(10, 156, 250, 50, 0x0320);
        text(22, 188, "HOLD A: APPROVE", 1, C_WHITE);
        gfx->fillRect(276, 156, 250, 50, 0x8000);
        text(288, 188, "HOLD B: REJECT", 1, C_WHITE);
        footer("Hold 1.5 s.  Short B: back to the list.");
    }
}

void Ui::showHold(char side, float f) {
    int x = side == 'A' ? 10 : 276;
    gfx->fillRect(x, 208, 250, 6, C_BLACK);
    gfx->fillRect(x, 208, int(250 * f), 6, side == 'A' ? C_GREEN : C_RED);
}

void Ui::showEnrolCode(const std::string& code, const std::string& org) {
    clear();
    header("ENROL THIS DEVICE", C_AMBER, org);
    text(10, 56, "Compare with the laptop. Same six digits?", 1, C_WHITE);
    textCentre(130, code, 4, C_CYAN);
    footer("HOLD A: yes, show enrolment QR    B: cancel");
}

void Ui::showQr(const std::string& title, const std::string& s, size_t index, size_t count) {
    clear();
    // The QR sits on a white square so it has its own quiet zone; modules are black on white.
    uint8_t version = 0;
    static uint8_t buf[512];
    QRCode qr;
    for (uint8_t v = 3; v <= 14; v++) {
        if (qrcode_getBufferSize(v) > sizeof buf) break;
        if (qrcode_initText(&qr, buf, v, ECC_MEDIUM, s.c_str()) == 0) { version = v; break; }
    }
    gfx->fillRect(0, 0, H, H, C_WHITE);
    if (version == 0) {
        text(250, 120, "QR TOO LARGE", 1, C_RED);
        return;
    }
    const int quiet = 3;
    int px = (H) / (qr.size + 2 * quiet);
    int side = qr.size * px;
    int ox = (H - side) / 2, oy = (H - side) / 2;
    for (uint8_t y = 0; y < qr.size; y++) {
        for (uint8_t x = 0; x < qr.size; x++) {
            if (qrcode_getModule(&qr, x, y)) gfx->fillRect(ox + x * px, oy + y * px, px, px, C_BLACK);
        }
    }
    uint16_t c = title == "APPROVED" ? C_GREEN : title == "REJECTED" ? C_RED : C_AMBER;
    text(260, 50, title, 2, c);
    text(260, 90, "Show this QR to the", 1, C_WHITE);
    text(260, 110, "laptop camera.", 1, C_WHITE);
    if (count > 1) text(260, 150, "Part " + std::to_string((unsigned long)index) + " of " + std::to_string((unsigned long)count), 1, C_GREY);
    text(260, 190, "A: done", 1, C_GREY);
}
