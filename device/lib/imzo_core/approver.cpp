// Review, respond, enrolment answer (port of Device.review / respond / enrolment_answer).
#include "imzo_core.h"

namespace imzo {

namespace {
const char DOMAIN_APPROVE[] = "IMZO/1/approve";  // followed by a NUL byte
const char DOMAIN_ENROL[] = "IMZO/1/enrol";

Bytes withDomain(const char* domain) {
    Bytes b;
    for (const char* p = domain; *p; p++) b.push_back(uint8_t(*p));
    b.push_back(0);
    return b;
}

Value responseBody(const Bytes& rid, const Bytes& dev, bool approve, uint64_t ctr, const Bytes& h) {
    Value body = Value::map();
    body.set("v", Value::uint(1));
    body.set("rid", Value::bytes(rid));
    body.set("dev", Value::bytes(dev));
    body.set("dec", Value::uint(approve ? 1 : 0));
    body.set("ctr", Value::uint(ctr));
    body.set("h", Value::bytes(h));
    return body;
}
}  // namespace

Review review(const Bytes& requestBytes, const Bytes& ownDeviceId) {
    Request req = parseRequest(requestBytes);
    if (req.dev != ownDeviceId) throw ProtocolError("WRONG DEVICE");
    Review r;
    r.rid = req.rid;
    r.h = sha256(requestBytes);
    r.fingerprint = fingerprint(requestBytes);
    r.op = req.op;
    r.ref = req.ref;
    r.total = 0;
    for (const Item& it : req.items) r.total += it.amount;  // recomputed, never trusted
    r.count = req.items.size();
    r.items = req.items;
    r.apK = req.apK;
    r.apN = req.apN;
    return r;
}

Bytes responseSigningInput(const Bytes& rid, const Bytes& dev, bool approve, uint64_t ctr, const Bytes& h) {
    Bytes in = withDomain(DOMAIN_APPROVE);
    Bytes body = dumps(responseBody(rid, dev, approve, ctr, h));
    in.insert(in.end(), body.begin(), body.end());
    return in;
}

Bytes respond(const Review& r, bool approve, Signer& signer, CounterStore& counters) {
    Bytes dev = deviceId(signer.publicKey());
    uint64_t ctr = counters.next();  // persisted before anything is signed or shown
    if (ctr < 1 || ctr > MAX_AMOUNT) throw ProtocolError("COUNTER", "counter out of range");
    Bytes sig = signer.sign(responseSigningInput(r.rid, dev, approve, ctr, r.h));
    if (sig.size() != 64) throw ProtocolError("BAD FORMAT", "signature must be 64 bytes");
    Value body = responseBody(r.rid, dev, approve, ctr, r.h);
    body.set("sig", Value::bytes(sig));
    return dumps(body);
}

Bytes enrolmentAnswer(const EnrolmentChallenge& c, Signer& signer) {
    Bytes msg = withDomain(DOMAIN_ENROL);
    msg.insert(msg.end(), c.nonce.begin(), c.nonce.end());
    msg.insert(msg.end(), c.org.begin(), c.org.end());
    Bytes sig = signer.sign(msg);
    if (sig.size() != 64) throw ProtocolError("BAD FORMAT", "signature must be 64 bytes");
    Value body = Value::map();
    body.set("pub", Value::bytes(signer.publicKey()));
    body.set("sig", Value::bytes(sig));
    return dumps(body);
}

}  // namespace imzo
