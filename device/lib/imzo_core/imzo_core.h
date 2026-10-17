// Imzo protocol v1, portable C++17 core for the Imzo Device (ESP32-S3).
//
// No Arduino or ESP headers in here: the same sources are built with the host compiler and checked
// against protocol/cases.json (device/host/test_core.py). The Python reference (protocol/imzo_ref.py)
// is the source of truth; where this disagrees with it, this is wrong.
#pragma once

#include <cstddef>
#include <cstdint>
#include <stdexcept>
#include <string>
#include <utility>
#include <vector>

namespace imzo {

using Bytes = std::vector<uint8_t>;

// ---------------------------------------------------------------------------------- limits

constexpr size_t MAX_ITEMS = 50;
constexpr size_t MAX_NAME_BYTES = 64;
constexpr size_t MAX_REF_BYTES = 64;
constexpr size_t MAX_ORG_BYTES = 128;
constexpr size_t MAX_MESSAGE_BYTES = 16 * 1024;
constexpr int MAX_DEPTH = 4;
constexpr uint64_t MAX_AMOUNT = 1ULL << 53;

/** Short, screen-friendly code such as "BAD FORMAT" or "WRONG DEVICE"; detail is for logs only. */
struct ProtocolError : std::runtime_error {
    std::string code;
    std::string detail;
    explicit ProtocolError(std::string c, std::string d = "")
        : std::runtime_error(d.empty() ? c : c + ": " + d), code(std::move(c)), detail(std::move(d)) {}
};

// ---------------------------------------------------------------------------------- hashing

Bytes sha256(const uint8_t* data, size_t len);
inline Bytes sha256(const Bytes& b) { return sha256(b.data(), b.size()); }
uint32_t crc32(const uint8_t* data, size_t len);

// ---------------------------------------------------------------------------------- CBOR

/** The subset Imzo uses: unsigned ints, byte strings, text strings, arrays, maps with text keys. */
struct Value {
    enum class Type { Uint, Bytes, Text, Array, Map } type = Type::Uint;
    uint64_t u = 0;
    Bytes b;
    std::string s;
    std::vector<Value> a;
    std::vector<std::pair<std::string, Value>> m;

    static Value uint(uint64_t n);
    static Value bytes(Bytes v);
    static Value text(std::string v);
    static Value array();
    static Value map();

    bool isUint() const { return type == Type::Uint; }
    bool isBytes() const { return type == Type::Bytes; }
    bool isText() const { return type == Type::Text; }
    bool isArray() const { return type == Type::Array; }
    bool isMap() const { return type == Type::Map; }
    /** Map lookup; nullptr when absent. */
    const Value* get(const std::string& key) const;
    Value& set(std::string key, Value v);  // map append
    Value& push(Value v);                  // array append
};

/** Deterministic encoding (RFC 8949 core deterministic form), map keys sorted by encoded bytes. */
Bytes dumps(const Value& v);
/** Strict decode: bounded size, depth limit, no trailing bytes, canonical form only. */
Value loads(const Bytes& data);

// ---------------------------------------------------------------------------------- messages

struct Item {
    std::string name;
    std::string account;
    uint64_t amount = 0;  // tiyin
};

struct Request {
    Bytes rid, dev, nonce;
    std::string op, cur, ref;
    std::vector<Item> items;
    uint64_t total = 0;
    int apK = 1, apN = 1;
};

/** Strict parse plus every semantic check of the reference. Throws ProtocolError. */
Request parseRequest(const Bytes& data);

struct EnrolmentChallenge {
    Bytes nonce;
    std::string org;
};
EnrolmentChallenge parseEnrolmentChallenge(const Bytes& data);

/** True for code points this device can both prove NFC for and show; everything else is refused. */
bool isAllowedCodePoint(uint32_t cp);

std::string fingerprint(const Bytes& canonicalRequest);
std::string enrolmentCode(const Bytes& publicKey);
Bytes deviceId(const Bytes& publicKey);
/** 530250000 -> "5,302,500.00" */
std::string formatAmount(uint64_t tiyin);

// ---------------------------------------------------------------------------------- IMQ1 framing

struct Frame {
    std::string kind;  // rq, rs, en, er
    std::string sid;
    int index = 0;     // 1-based
    int count = 0;
    Bytes data;
};

Frame parseFrame(const std::string& line);
/** Batch reassembly, any order, repeats allowed; mirrors frames_decode. Returns {kind, body}. */
std::pair<std::string, Bytes> framesDecode(const std::vector<std::string>& frames);
/** Encode a body as frames (used for the answer QR). */
std::vector<std::string> framesEncode(const std::string& kind, const Bytes& body, size_t chunk, const std::string& sid);

/** Incremental assembler for scanned frames. A frame from another message starts over. */
class FrameAssembler {
public:
    enum class Status { NeedMore, Complete, NewSession };
    Status add(const std::string& line);  // throws ProtocolError on a malformed frame
    void reset();
    bool complete() const { return count_ != 0 && have_ == count_; }
    int have() const { return have_; }
    int count() const { return count_; }
    const std::string& kind() const { return kind_; }
    /** Joined body once complete; verifies the CRC. Throws ProtocolError. */
    Bytes body() const;

private:
    std::string kind_, sid_;
    int count_ = 0, have_ = 0;
    std::vector<Bytes> parts_;
    std::vector<bool> got_;
    size_t stored_ = 0;
};

// ---------------------------------------------------------------------------------- approver

/** The device key. On the ESP32 it is a P-256 key in flash, signing through mbedTLS. */
class Signer {
public:
    virtual ~Signer() = default;
    /** 65-byte uncompressed point 0x04 || X || Y. */
    virtual Bytes publicKey() const = 0;
    /** ECDSA P-256 over SHA-256(message), raw 64-byte r||s. Throws ProtocolError on failure. */
    virtual Bytes sign(const Bytes& message) = 0;
};

/** Monotonic answer counter. next() persists BEFORE returning and throws if it cannot. */
class CounterStore {
public:
    virtual ~CounterStore() = default;
    virtual uint64_t next() = 0;
};

/** What the device shows. Every field is derived from the signed bytes, the total is recomputed. */
struct Review {
    Bytes rid, h;
    std::string fingerprint, op, ref;
    uint64_t total = 0;
    size_t count = 0;
    std::vector<Item> items;
    int apK = 1, apN = 1;
};

Review review(const Bytes& requestBytes, const Bytes& ownDeviceId);
Bytes respond(const Review& r, bool approve, Signer& signer, CounterStore& counters);
Bytes enrolmentAnswer(const EnrolmentChallenge& c, Signer& signer);
/** What respond() signs, exposed so tests can compare it byte for byte with the reference. */
Bytes responseSigningInput(const Bytes& rid, const Bytes& dev, bool approve, uint64_t ctr, const Bytes& h);

}  // namespace imzo
