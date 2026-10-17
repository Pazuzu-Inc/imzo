// IMQ1 animated-QR framing: strict base64url, CRC32, batch and incremental reassembly.
#include <algorithm>
#include <map>

#include "imzo_core.h"

namespace imzo {

namespace {

[[noreturn]] void bad(const char* why) { throw ProtocolError("BAD FORMAT", why); }

const char B64[] = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_";

std::string b64uEncode(const Bytes& raw) {
    std::string out;
    size_t i = 0;
    for (; i + 3 <= raw.size(); i += 3) {
        uint32_t v = (uint32_t(raw[i]) << 16) | (uint32_t(raw[i + 1]) << 8) | raw[i + 2];
        for (int s = 18; s >= 0; s -= 6) out.push_back(B64[(v >> s) & 63]);
    }
    size_t rest = raw.size() - i;
    if (rest == 1) {
        uint32_t v = uint32_t(raw[i]) << 16;
        out.push_back(B64[(v >> 18) & 63]);
        out.push_back(B64[(v >> 12) & 63]);
    } else if (rest == 2) {
        uint32_t v = (uint32_t(raw[i]) << 16) | (uint32_t(raw[i + 1]) << 8);
        out.push_back(B64[(v >> 18) & 63]);
        out.push_back(B64[(v >> 12) & 63]);
        out.push_back(B64[(v >> 6) & 63]);
    }
    return out;
}

int b64Value(char c) {
    if (c >= 'A' && c <= 'Z') return c - 'A';
    if (c >= 'a' && c <= 'z') return c - 'a' + 26;
    if (c >= '0' && c <= '9') return c - '0' + 52;
    if (c == '-') return 62;
    if (c == '_') return 63;
    return -1;
}

/** Unpadded base64url, alphabet only, canonical trailing bits (one encoding per value). */
Bytes b64uDecode(const std::string& text) {
    if (text.size() % 4 == 1) bad("bad base64");
    Bytes out;
    uint32_t acc = 0;
    int bits = 0;
    for (char c : text) {
        int v = b64Value(c);
        if (v < 0) bad("bad base64");
        acc = (acc << 6) | uint32_t(v);
        bits += 6;
        if (bits >= 8) {
            bits -= 8;
            out.push_back(uint8_t(acc >> bits));
            acc &= (1u << bits) - 1;
        }
    }
    if (acc != 0) bad("bad base64");  // leftover bits must be zero
    return out;
}

bool knownKind(const std::string& k) { return k == "rq" || k == "rs" || k == "en" || k == "er"; }

int parseCounter(const std::string& t) {
    if (t.empty()) bad("bad frame counters");
    long v = 0;
    for (char c : t) {
        if (c < '0' || c > '9') bad("bad frame counters");
        v = v * 10 + (c - '0');
        if (v > 100000) v = 100000;  // clamp; the range check below refuses it
    }
    return int(v);
}

Bytes joinAndCheck(const std::vector<Bytes>& parts) {
    Bytes payload;
    for (const Bytes& p : parts) payload.insert(payload.end(), p.begin(), p.end());
    if (payload.size() < 4) bad("too short");
    size_t n = payload.size() - 4;
    uint32_t want = (uint32_t(payload[n]) << 24) | (uint32_t(payload[n + 1]) << 16) |
                    (uint32_t(payload[n + 2]) << 8) | payload[n + 3];
    if (crc32(payload.data(), n) != want) throw ProtocolError("CRC");
    payload.resize(n);
    return payload;
}

}  // namespace

Frame parseFrame(const std::string& line) {
    // IMQ1.<kind>.<sid>.<i>.<n>.<data>  (split at most 5 times, like the reference)
    std::vector<std::string> pieces;
    size_t start = 0;
    while (pieces.size() < 5) {
        size_t dot = line.find('.', start);
        if (dot == std::string::npos) break;
        pieces.push_back(line.substr(start, dot - start));
        start = dot + 1;
    }
    pieces.push_back(line.substr(start));
    if (pieces.size() != 6 || pieces[0] != "IMQ1" || !knownKind(pieces[1])) bad("not an IMQ1 frame");
    Frame f;
    f.kind = pieces[1];
    f.sid = pieces[2];
    f.index = parseCounter(pieces[3]);
    f.count = parseCounter(pieces[4]);
    if (!(1 <= f.index && f.index <= f.count && f.count <= 256)) bad("frame index out of range");
    f.data = b64uDecode(pieces[5]);
    return f;
}

std::pair<std::string, Bytes> framesDecode(const std::vector<std::string>& frames) {
    std::string kind, sid;
    int total = 0;
    std::map<int, Bytes> parts;
    bool any = false;
    for (const std::string& line : frames) {
        Frame f = parseFrame(line);
        if (!any) {
            any = true;
            kind = f.kind;
            sid = f.sid;
            total = f.count;
        } else if (f.kind != kind || f.sid != sid || f.count != total) {
            bad("frames from different messages");
        }
        parts[f.index] = f.data;
    }
    if (!any || int(parts.size()) != total) throw ProtocolError("INCOMPLETE");
    std::vector<Bytes> ordered;
    for (int i = 1; i <= total; i++) ordered.push_back(parts[i]);
    return {kind, joinAndCheck(ordered)};
}

std::vector<std::string> framesEncode(const std::string& kind, const Bytes& body, size_t chunk, const std::string& sid) {
    Bytes payload = body;
    uint32_t c = crc32(body.data(), body.size());
    for (int s = 24; s >= 0; s -= 8) payload.push_back(uint8_t(c >> s));
    size_t n = (payload.size() + chunk - 1) / chunk;
    std::vector<std::string> out;
    for (size_t i = 0; i < n; i++) {
        Bytes part(payload.begin() + i * chunk, payload.begin() + std::min(payload.size(), (i + 1) * chunk));
        out.push_back("IMQ1." + kind + "." + sid + "." + std::to_string(i + 1) + "." + std::to_string(n) + "." + b64uEncode(part));
    }
    return out;
}

void FrameAssembler::reset() {
    kind_.clear();
    sid_.clear();
    count_ = have_ = 0;
    parts_.clear();
    got_.clear();
    stored_ = 0;
}

FrameAssembler::Status FrameAssembler::add(const std::string& line) {
    Frame f = parseFrame(line);
    bool fresh = false;
    if (count_ == 0 || f.kind != kind_ || f.sid != sid_ || f.count != count_) {
        fresh = count_ != 0;  // a different message than the one in progress: start over
        reset();
        kind_ = f.kind;
        sid_ = f.sid;
        count_ = f.count;
        parts_.assign(size_t(count_), Bytes());
        got_.assign(size_t(count_), false);
    }
    size_t slot = size_t(f.index - 1);
    if (!got_[slot]) {
        if (stored_ + f.data.size() > MAX_MESSAGE_BYTES + 4) {
            reset();
            bad("message too large");
        }
        got_[slot] = true;
        parts_[slot] = f.data;
        stored_ += f.data.size();
        have_++;
    }
    if (complete()) return Status::Complete;
    return fresh ? Status::NewSession : Status::NeedMore;
}

Bytes FrameAssembler::body() const {
    if (!complete()) throw ProtocolError("INCOMPLETE");
    return joinAndCheck(parts_);
}

}  // namespace imzo
