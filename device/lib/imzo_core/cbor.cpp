// Deterministic CBOR encoder and strict decoder (port of the reference's dumps/loads).
#include <algorithm>

#include "imzo_core.h"

namespace imzo {

Value Value::uint(uint64_t n) { Value v; v.type = Type::Uint; v.u = n; return v; }
Value Value::bytes(Bytes x) { Value v; v.type = Type::Bytes; v.b = std::move(x); return v; }
Value Value::text(std::string x) { Value v; v.type = Type::Text; v.s = std::move(x); return v; }
Value Value::array() { Value v; v.type = Type::Array; return v; }
Value Value::map() { Value v; v.type = Type::Map; return v; }

const Value* Value::get(const std::string& key) const {
    for (const auto& kv : m) {
        if (kv.first == key) return &kv.second;
    }
    return nullptr;
}
Value& Value::set(std::string key, Value v) { m.emplace_back(std::move(key), std::move(v)); return *this; }
Value& Value::push(Value v) { a.push_back(std::move(v)); return *this; }

namespace {

void head(Bytes& out, int major, uint64_t n) {
    uint8_t m = uint8_t(major << 5);
    if (n < 24) {
        out.push_back(m | uint8_t(n));
    } else if (n < 0x100) {
        out.push_back(m | 24);
        out.push_back(uint8_t(n));
    } else if (n < 0x10000) {
        out.push_back(m | 25);
        for (int s = 8; s >= 0; s -= 8) out.push_back(uint8_t(n >> s));
    } else if (n < 0x100000000ULL) {
        out.push_back(m | 26);
        for (int s = 24; s >= 0; s -= 8) out.push_back(uint8_t(n >> s));
    } else {
        out.push_back(m | 27);
        for (int s = 56; s >= 0; s -= 8) out.push_back(uint8_t(n >> s));
    }
}

void encode(Bytes& out, const Value& v) {
    switch (v.type) {
        case Value::Type::Uint: head(out, 0, v.u); break;
        case Value::Type::Bytes:
            head(out, 2, v.b.size());
            out.insert(out.end(), v.b.begin(), v.b.end());
            break;
        case Value::Type::Text:
            head(out, 3, v.s.size());
            out.insert(out.end(), v.s.begin(), v.s.end());
            break;
        case Value::Type::Array:
            head(out, 4, v.a.size());
            for (const auto& x : v.a) encode(out, x);
            break;
        case Value::Type::Map: {
            std::vector<std::pair<Bytes, Bytes>> pairs;
            for (const auto& kv : v.m) {
                Bytes k, val;
                head(k, 3, kv.first.size());
                k.insert(k.end(), kv.first.begin(), kv.first.end());
                encode(val, kv.second);
                pairs.emplace_back(std::move(k), std::move(val));
            }
            std::sort(pairs.begin(), pairs.end(), [](const auto& x, const auto& y) { return x.first < y.first; });
            head(out, 5, pairs.size());
            for (const auto& p : pairs) {
                out.insert(out.end(), p.first.begin(), p.first.end());
                out.insert(out.end(), p.second.begin(), p.second.end());
            }
            break;
        }
    }
}

[[noreturn]] void bad(const char* why) { throw ProtocolError("BAD FORMAT", why); }

void readHead(const Bytes& d, size_t& i, int& major, uint64_t& n) {
    if (i >= d.size()) bad("truncated");
    uint8_t b = d[i++];
    major = b >> 5;
    int ai = b & 31;
    if (ai < 24) { n = uint64_t(ai); return; }
    if (ai < 28) {
        size_t size = size_t(1) << (ai - 24);
        if (size > d.size() - i) bad("truncated");
        n = 0;
        for (size_t k = 0; k < size; k++) n = (n << 8) | d[i + k];
        i += size;
        return;
    }
    bad("indefinite or reserved length");
}

/** Strict UTF-8: no overlong forms, no surrogates, nothing above U+10FFFF. */
bool validUtf8(const uint8_t* p, size_t n) {
    size_t i = 0;
    while (i < n) {
        uint8_t c = p[i];
        if (c < 0x80) { i++; continue; }
        size_t len;
        uint32_t cp;
        if ((c & 0xE0) == 0xC0) { len = 2; cp = c & 0x1F; }
        else if ((c & 0xF0) == 0xE0) { len = 3; cp = c & 0x0F; }
        else if ((c & 0xF8) == 0xF0) { len = 4; cp = c & 0x07; }
        else return false;
        if (len > n - i) return false;
        for (size_t k = 1; k < len; k++) {
            if ((p[i + k] & 0xC0) != 0x80) return false;
            cp = (cp << 6) | (p[i + k] & 0x3F);
        }
        if ((len == 2 && cp < 0x80) || (len == 3 && cp < 0x800) || (len == 4 && cp < 0x10000)) return false;
        if (cp > 0x10FFFF || (cp >= 0xD800 && cp <= 0xDFFF)) return false;
        i += len;
    }
    return true;
}

Value decode(const Bytes& d, size_t& i, int depth) {
    if (depth > MAX_DEPTH) bad("nesting too deep");
    int major;
    uint64_t n;
    readHead(d, i, major, n);
    switch (major) {
        case 0: return Value::uint(n);
        case 2:
        case 3: {
            if (n > d.size() - i) bad("truncated");
            size_t len = size_t(n);
            if (major == 2) {
                Value v = Value::bytes(Bytes(d.begin() + i, d.begin() + i + len));
                i += len;
                return v;
            }
            if (!validUtf8(d.data() + i, len)) bad("invalid utf-8");
            Value v = Value::text(std::string(reinterpret_cast<const char*>(d.data() + i), len));
            i += len;
            return v;
        }
        case 4: {
            if (n > d.size() - i) bad("array longer than message");
            Value v = Value::array();
            for (uint64_t k = 0; k < n; k++) v.push(decode(d, i, depth + 1));
            return v;
        }
        case 5: {
            if (n > d.size() - i) bad("map longer than message");
            Value v = Value::map();
            for (uint64_t k = 0; k < n; k++) {
                Value key = decode(d, i, depth + 1);
                if (!key.isText()) bad("map keys must be text");
                if (v.get(key.s)) bad("duplicate key");
                v.set(key.s, decode(d, i, depth + 1));
            }
            return v;
        }
        default: bad("unsupported major type");
    }
}

}  // namespace

Bytes dumps(const Value& v) {
    Bytes out;
    encode(out, v);
    return out;
}

Value loads(const Bytes& data) {
    if (data.size() > MAX_MESSAGE_BYTES) bad("message too large");
    size_t i = 0;
    Value v = decode(data, i, 0);
    if (i != data.size()) bad("trailing bytes");
    if (dumps(v) != data) bad("not canonical");
    return v;
}

}  // namespace imzo
