// Request validation, fingerprint, enrolment code (ports of validate_request and helpers).
#include <cstdio>

#include "imzo_core.h"

namespace imzo {

namespace {

[[noreturn]] void bad(const std::string& why) { throw ProtocolError("BAD FORMAT", why); }

const char* const OPS[] = {"payroll", "transfer", "beneficiary", "limit"};

bool isDigits(const std::string& s) {
    if (s.empty()) return false;
    for (char c : s) {
        if (c < '0' || c > '9') return false;
    }
    return true;
}

/** Next code point of already-validated UTF-8. */
uint32_t nextCodePoint(const std::string& s, size_t& i) {
    uint8_t c = uint8_t(s[i]);
    if (c < 0x80) { i += 1; return c; }
    size_t len = (c & 0xE0) == 0xC0 ? 2 : (c & 0xF0) == 0xE0 ? 3 : 4;
    uint32_t cp = len == 2 ? (c & 0x1F) : len == 3 ? (c & 0x0F) : (c & 0x07);
    for (size_t k = 1; k < len; k++) cp = (cp << 6) | (uint8_t(s[i + k]) & 0x3F);
    i += len;
    return cp;
}

/**
 * Same checks, same order as _check_text in the reference, with one deliberate difference: this device
 * has no Unicode tables, so after the control-character test it accepts only code points it can prove
 * NFC for and show (isAllowedCodePoint) and refuses the rest with UNSUPPORTED TEXT. That is stricter
 * than the server, never looser.
 */
void checkText(const Value& v, size_t maxBytes, const char* what) {
    if (!v.isText() || v.s.empty()) bad(std::string(what) + " must be non-empty text");
    if (v.s.size() > maxBytes) bad(std::string(what) + " too long");
    bool unsupported = false;
    for (size_t i = 0; i < v.s.size();) {
        uint32_t cp = nextCodePoint(v.s, i);
        if (cp < 0x20 || (cp >= 0x7F && cp <= 0x9F)) bad(std::string(what) + " has control characters");
        if (!isAllowedCodePoint(cp)) unsupported = true;
    }
    if (unsupported) throw ProtocolError("UNSUPPORTED TEXT", std::string(what) + " has characters this device cannot show");
}

bool isBytes16(const Value* v) { return v && v->isBytes() && v->b.size() == 16; }

}  // namespace

bool isAllowedCodePoint(uint32_t cp) {
    if (cp >= 0x20 && cp <= 0x7E) return true;                  // ASCII printable
    if (cp >= 0xA1 && cp <= 0xFF) return cp != 0xAD;            // Latin-1 (no soft hyphen)
    if (cp >= 0x100 && cp <= 0x17F) return true;                // Latin Extended-A
    if (cp == 0x2BB || cp == 0x2BC) return true;                // Uzbek o' g' modifier letters
    if (cp == 0x2018 || cp == 0x2019) return true;              // single quotes
    if (cp >= 0x400 && cp <= 0x45F) return true;               // basic Cyrillic (none combining)
    if (cp >= 0x490 && cp <= 0x4FF) return true;               // Cyrillic extended letters (Ғ Қ Ҳ ...)
    return false;
}

Request parseRequest(const Bytes& data) {
    Value obj = loads(data);
    static const char* const KEYS[] = {"v", "rid", "dev", "nonce", "op", "cur", "ref", "items", "total", "ap"};
    if (!obj.isMap() || obj.m.size() != 10) bad("wrong or missing fields");
    for (const char* k : KEYS) {
        if (!obj.get(k)) bad("wrong or missing fields");
    }
    const Value* v = obj.get("v");
    if (!v->isUint() || v->u != 1) bad("unsupported version");
    for (const char* k : {"rid", "dev", "nonce"}) {
        if (!isBytes16(obj.get(k))) bad(std::string(k) + " must be 16 bytes");
    }
    Request r;
    r.rid = obj.get("rid")->b;
    r.dev = obj.get("dev")->b;
    r.nonce = obj.get("nonce")->b;

    const Value* op = obj.get("op");
    bool opOk = false;
    if (op->isText()) {
        for (const char* o : OPS) opOk = opOk || op->s == o;
    }
    if (!opOk) bad("unknown op");
    r.op = op->s;
    const Value* cur = obj.get("cur");
    if (!cur->isText() || cur->s != "UZS") bad("unsupported currency");
    r.cur = cur->s;
    checkText(*obj.get("ref"), MAX_REF_BYTES, "ref");
    r.ref = obj.get("ref")->s;

    const Value* items = obj.get("items");
    if (!items->isArray() || items->a.empty() || items->a.size() > MAX_ITEMS) bad("item count out of range");
    uint64_t total = 0;
    for (const Value& it : items->a) {
        if (!it.isArray() || it.a.size() != 3) bad("item must be [name, account, amount]");
        const Value& name = it.a[0];
        const Value& account = it.a[1];
        const Value& amt = it.a[2];
        checkText(name, MAX_NAME_BYTES, "name");
        if (!account.isText() || !isDigits(account.s) || (account.s.size() != 16 && account.s.size() != 20)) {
            bad("account must be 16 or 20 digits");
        }
        if (!amt.isUint() || amt.u < 1 || amt.u > MAX_AMOUNT) bad("amount out of range");
        total += amt.u;
        r.items.push_back(Item{name.s, account.s, amt.u});
    }
    const Value* claimed = obj.get("total");
    if (!claimed->isUint() || claimed->u != total) {
        throw ProtocolError("TOTAL MISMATCH", "claimed total differs from the computed one");
    }
    r.total = total;
    const Value* ap = obj.get("ap");
    if (!ap->isArray() || ap->a.size() != 2 || !ap->a[0].isUint() || !ap->a[1].isUint() ||
        ap->a[0].u < 1 || ap->a[0].u > ap->a[1].u || ap->a[1].u > 5) {
        bad("ap must be [k, n]");
    }
    r.apK = int(ap->a[0].u);
    r.apN = int(ap->a[1].u);
    return r;
}

EnrolmentChallenge parseEnrolmentChallenge(const Bytes& data) {
    Value obj = loads(data);
    if (!obj.isMap() || obj.m.size() != 3 || !obj.get("v") || !obj.get("nonce") || !obj.get("org")) {
        bad("wrong or missing fields");
    }
    if (!obj.get("v")->isUint() || obj.get("v")->u != 1) bad("unsupported version");
    if (!isBytes16(obj.get("nonce"))) bad("nonce must be 16 bytes");
    const Value* org = obj.get("org");
    if (!org->isText() || org->s.empty() || org->s.size() > MAX_ORG_BYTES) bad("org must be short non-empty text");
    return EnrolmentChallenge{obj.get("nonce")->b, org->s};
}

std::string fingerprint(const Bytes& canonicalRequest) {
    static const char CONS[] = "bdfgklmnprstvzjh";
    static const char VOW[] = "aeiu";
    Bytes d = sha256(canonicalRequest);  // first 9 bytes = 72 bits, taken 12 bits at a time from the top
    std::string out;
    for (int w = 0; w < 6; w++) {
        unsigned chunk = 0;
        for (int bit = w * 12; bit < w * 12 + 12; bit++) chunk = (chunk << 1) | ((d[bit / 8] >> (7 - bit % 8)) & 1u);
        if (w) out.push_back(' ');
        for (unsigned half : {chunk >> 6, chunk & 63u}) {
            out.push_back(CONS[half >> 2]);
            out.push_back(VOW[half & 3]);
        }
    }
    return out;
}

std::string enrolmentCode(const Bytes& publicKey) {
    Bytes d = sha256(publicKey);
    uint32_t n = (uint32_t(d[0]) << 24) | (uint32_t(d[1]) << 16) | (uint32_t(d[2]) << 8) | d[3];
    char buf[16];
    std::snprintf(buf, sizeof buf, "%06u", unsigned(n % 1000000u));
    return buf;
}

Bytes deviceId(const Bytes& publicKey) {
    Bytes d = sha256(publicKey);
    d.resize(16);
    return d;
}

std::string formatAmount(uint64_t tiyin) {
    std::string whole = std::to_string(tiyin / 100);
    std::string grouped;
    for (size_t i = 0; i < whole.size(); i++) {
        if (i && (whole.size() - i) % 3 == 0) grouped.push_back(',');
        grouped.push_back(whole[i]);
    }
    char frac[8];
    std::snprintf(frac, sizeof frac, ".%02u", unsigned(tiyin % 100));
    return grouped + frac;
}

}  // namespace imzo
