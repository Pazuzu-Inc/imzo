// Host driver for the portable core: one command per line on stdin, one answer per line on stdout.
// Used only by device/host/test_core.py. Binary arguments are lowercase hex, "-" means empty.
#include <iostream>
#include <sstream>

#include "imzo_core.h"

using namespace imzo;

namespace {

std::string toHex(const Bytes& b) {
    static const char* d = "0123456789abcdef";
    std::string s;
    for (uint8_t x : b) { s.push_back(d[x >> 4]); s.push_back(d[x & 15]); }
    return s.empty() ? "-" : s;
}

Bytes fromHex(const std::string& s) {
    Bytes out;
    if (s == "-") return out;
    auto v = [](char c) { return c <= '9' ? c - '0' : c - 'a' + 10; };
    for (size_t i = 0; i + 1 < s.size(); i += 2) out.push_back(uint8_t(v(s[i]) * 16 + v(s[i + 1])));
    return out;
}

struct FakeSigner : Signer {
    Bytes pub;
    Bytes publicKey() const override { return pub; }
    // Stand-in signature: SHA-256(message) twice, so the test sees exactly which bytes get signed.
    // Real ECDSA is checked on the device against the backend.
    Bytes sign(const Bytes& m) override { Bytes d = sha256(m); d.insert(d.end(), d.begin(), d.end()); return d; }
};

struct FixedCounter : CounterStore {
    uint64_t n = 0;
    uint64_t next() override { return n; }
};

}  // namespace

int main() {
    std::string line;
    while (std::getline(std::cin, line)) {
        std::istringstream in(line);
        std::string cmd;
        in >> cmd;
        try {
            if (cmd == "cbor") {
                std::string h; in >> h;
                std::string r = toHex(dumps(loads(fromHex(h))));
                std::cout << "ok " << r << "\n";
            } else if (cmd == "request") {
                std::string h; in >> h;
                Bytes req = fromHex(h);
                Request r = parseRequest(req);
                std::string fp = fingerprint(req);
                std::cout << "ok " << r.total << " " << r.items.size() << " " << fp << "\n";
            } else if (cmd == "frames" || cmd == "assemble") {
                std::vector<std::string> frames;
                for (std::string f; in >> f;) {  // each frame arrives hex-encoded: frames may contain spaces or newlines
                    Bytes raw = fromHex(f);
                    frames.emplace_back(raw.begin(), raw.end());
                }
                if (cmd == "frames") {
                    auto r = framesDecode(frames);
                    std::string body = toHex(r.second);
                    std::cout << "ok " << r.first << " " << body << "\n";
                } else {  // incremental assembler
                    FrameAssembler a;
                    for (const auto& f : frames) a.add(f);
                    if (!a.complete()) throw ProtocolError("INCOMPLETE");
                    std::string body = toHex(a.body());
                    std::cout << "ok " << a.kind() << " " << body << "\n";
                }
            } else if (cmd == "respond") {
                std::string reqHex, pubHex, ctr, dec;
                in >> reqHex >> pubHex >> ctr >> dec;
                FakeSigner s; s.pub = fromHex(pubHex);
                FixedCounter c; c.n = std::stoull(ctr);
                Review r = review(fromHex(reqHex), deviceId(s.pub));
                Bytes resp = respond(r, dec == "1", s, c);
                Bytes input = responseSigningInput(r.rid, deviceId(s.pub), dec == "1", c.n, r.h);
                std::cout << "ok " << toHex(resp) << " " << toHex(input) << " " << r.fingerprint << " " << r.total << "\n";
            } else if (cmd == "enrol") {
                std::string nonceHex, org, pubHex;
                in >> nonceHex >> org >> pubHex;
                FakeSigner s; s.pub = fromHex(pubHex);
                Bytes body = enrolmentAnswer(EnrolmentChallenge{fromHex(nonceHex), org}, s);
                std::cout << "ok " << toHex(body) << " " << enrolmentCode(s.pub) << " " << toHex(deviceId(s.pub)) << "\n";
            } else if (cmd == "challenge") {
                std::string h; in >> h;
                EnrolmentChallenge c = parseEnrolmentChallenge(fromHex(h));
                std::cout << "ok " << toHex(c.nonce) << " " << c.org << "\n";
            } else if (cmd == "amount") {
                uint64_t n; in >> n;
                std::cout << "ok " << formatAmount(n) << "\n";
            } else if (cmd == "encode") {
                std::string kind, sid, h; size_t chunk;
                in >> kind >> sid >> chunk >> h;
                auto frames = framesEncode(kind, fromHex(h), chunk, sid);
                for (const auto& f : frames) std::cout << f << " ";
                std::cout << "\n";
            } else {
                std::cout << "err UNKNOWN COMMAND\n";
            }
        } catch (const ProtocolError& e) {
            std::cout << "err " << e.code << "\n";
        }
    }
    return 0;
}
