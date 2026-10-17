#include "device_key.h"

#include <Preferences.h>
#include <mbedtls/ctr_drbg.h>
#include <mbedtls/ecdsa.h>
#include <mbedtls/ecp.h>
#include <mbedtls/entropy.h>

using imzo::Bytes;
using imzo::ProtocolError;

namespace {

const char* NS = "imzo";

// One RNG for the process, seeded from the ESP32 hardware entropy source.
mbedtls_entropy_context g_entropy;
mbedtls_ctr_drbg_context g_drbg;
bool g_rngReady = false;

void initRng() {
    if (g_rngReady) return;
    mbedtls_entropy_init(&g_entropy);
    mbedtls_ctr_drbg_init(&g_drbg);
    const char* pers = "imzo-device";
    if (mbedtls_ctr_drbg_seed(&g_drbg, mbedtls_entropy_func, &g_entropy, reinterpret_cast<const uint8_t*>(pers), 11) != 0) {
        throw ProtocolError("KEY ERROR", "rng seed");
    }
    g_rngReady = true;
}

/** RAII holder for the curve group so every early exit frees it. */
struct Group {
    mbedtls_ecp_group g;
    Group() {
        mbedtls_ecp_group_init(&g);
        if (mbedtls_ecp_group_load(&g, MBEDTLS_ECP_DP_SECP256R1) != 0) throw ProtocolError("KEY ERROR", "curve");
    }
    ~Group() { mbedtls_ecp_group_free(&g); }
};

}  // namespace

void DeviceKey::begin() {
    initRng();
    Preferences prefs;
    if (!prefs.begin(NS, false)) throw ProtocolError("KEY ERROR", "nvs");
    size_t have = prefs.getBytesLength("d");
    if (have == 32) {
        prefs.getBytes("d", d_, 32);
        prefs.end();
        derivePublic();
        return;
    }
    // First boot: generate and store the key, then read it back before trusting it.
    Group grp;
    mbedtls_mpi d;
    mbedtls_ecp_point q;
    mbedtls_mpi_init(&d);
    mbedtls_ecp_point_init(&q);
    int rc = mbedtls_ecp_gen_keypair(&grp.g, &d, &q, mbedtls_ctr_drbg_random, &g_drbg);
    uint8_t raw[32];
    if (rc == 0) rc = mbedtls_mpi_write_binary(&d, raw, 32);
    mbedtls_mpi_free(&d);
    mbedtls_ecp_point_free(&q);
    if (rc != 0) {
        prefs.end();
        throw ProtocolError("KEY ERROR", "keygen");
    }
    size_t wrote = prefs.putBytes("d", raw, 32);
    prefs.putULong64("ctr", 0);
    uint8_t back[32] = {0};
    prefs.getBytes("d", back, 32);
    prefs.end();
    bool same = wrote == 32;
    for (int i = 0; i < 32; i++) same = same && back[i] == raw[i];
    if (!same) throw ProtocolError("KEY ERROR", "flash write");
    for (int i = 0; i < 32; i++) d_[i] = raw[i];
    derivePublic();
}

void DeviceKey::derivePublic() {
    Group grp;
    mbedtls_mpi d;
    mbedtls_ecp_point q;
    mbedtls_mpi_init(&d);
    mbedtls_ecp_point_init(&q);
    uint8_t out[65];
    size_t olen = 0;
    int rc = mbedtls_mpi_read_binary(&d, d_, 32);
    if (rc == 0) rc = mbedtls_ecp_mul(&grp.g, &q, &d, &grp.g.G, mbedtls_ctr_drbg_random, &g_drbg);
    if (rc == 0) rc = mbedtls_ecp_point_write_binary(&grp.g, &q, MBEDTLS_ECP_PF_UNCOMPRESSED, &olen, out, sizeof out);
    mbedtls_mpi_free(&d);
    mbedtls_ecp_point_free(&q);
    if (rc != 0 || olen != 65) throw ProtocolError("KEY ERROR", "public key");
    pub_.assign(out, out + 65);
}

Bytes DeviceKey::sign(const Bytes& message) {
    Bytes hash = imzo::sha256(message);
    Group grp;
    mbedtls_mpi d, r, s;
    mbedtls_mpi_init(&d);
    mbedtls_mpi_init(&r);
    mbedtls_mpi_init(&s);
    Bytes sig(64);
    int rc = mbedtls_mpi_read_binary(&d, d_, 32);
    if (rc == 0) rc = mbedtls_ecdsa_sign(&grp.g, &r, &s, &d, hash.data(), hash.size(), mbedtls_ctr_drbg_random, &g_drbg);
    if (rc == 0) rc = mbedtls_mpi_write_binary(&r, sig.data(), 32);
    if (rc == 0) rc = mbedtls_mpi_write_binary(&s, sig.data() + 32, 32);
    mbedtls_mpi_free(&d);
    mbedtls_mpi_free(&r);
    mbedtls_mpi_free(&s);
    if (rc != 0) throw ProtocolError("SIGN ERROR");
    return sig;
}

uint64_t DeviceKey::counter() const {
    Preferences prefs;
    prefs.begin(NS, true);
    uint64_t c = prefs.getULong64("ctr", 0);
    prefs.end();
    return c;
}

uint64_t DeviceKey::next() {
    Preferences prefs;
    if (!prefs.begin(NS, false)) throw ProtocolError("COUNTER", "nvs");
    uint64_t n = prefs.getULong64("ctr", 0) + 1;
    size_t wrote = prefs.putULong64("ctr", n);
    uint64_t back = prefs.getULong64("ctr", 0);
    prefs.end();
    if (wrote != 8 || back != n) throw ProtocolError("COUNTER", "flash write");
    return n;
}

void DeviceKey::wipe() {
    Preferences prefs;
    prefs.begin(NS, false);
    prefs.clear();
    prefs.end();
    for (int i = 0; i < 32; i++) d_[i] = 0;
    pub_.clear();
}
