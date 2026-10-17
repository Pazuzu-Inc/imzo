package inc.pazuzu.imzo.protocol

import java.math.BigInteger
import java.security.AlgorithmParameters
import java.security.KeyFactory
import java.security.PublicKey
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPublicKeySpec
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** Differential tests against `cases.json` / `vectors.json` recorded from `imzo_ref.py`. */
class MessagesTest {

    // ------------------------------------------------------------------ cases.json

    @Test
    fun requestCasesMatchReference() {
        val arr = Cases.root.getJSONArray("requests")
        assertEquals(59, arr.length())
        val failures = mutableListOf<String>()
        for (i in 0 until arr.length()) {
            val c = arr.getJSONObject(i)
            val name = c.getString("name")
            val data = c.getString("hex").hexToBytes()
            val result = runCatching { parseRequest(data) }
            if (c.has("error")) {
                val e = result.exceptionOrNull()
                if (e !is ProtocolError) failures += "$name: expected ${c.getString("error")}, got $result"
                else if (e.code != c.getString("error")) failures += "$name: expected ${c.getString("error")}, got ${e.code} (${e.detail})"
            } else {
                val req = result.getOrNull()
                if (req == null) { failures += "$name: unexpected ${result.exceptionOrNull()}"; continue }
                if (req.total != c.getLong("total")) failures += "$name: total ${req.total}"
                if (req.items.size != c.getInt("count")) failures += "$name: count ${req.items.size}"
                if (req.items.sumOf { it.amount } != req.total) failures += "$name: items do not sum to total"
                if (fingerprint(data) != c.getString("fingerprint")) failures += "$name: fingerprint ${fingerprint(data)}"
            }
        }
        if (failures.isNotEmpty()) fail(failures.joinToString("\n"))
    }

    // ------------------------------------------------------------------ vectors.json

    private val v get() = Cases.vectors

    @Test
    fun vectorRequestParsesToExpectedFields() {
        val data = v.getString("request_hex").hexToBytes()
        val req = parseRequest(data)
        assertEquals(v.getLong("total_tiyin"), req.total)
        assertEquals(v.getInt("item_count"), req.items.size)
        assertEquals("payroll", req.op)
        assertEquals("UZS", req.cur)
        assertEquals("Payroll October 2026", req.ref)
        assertEquals(1 to 1, req.ap)
        assertArrayEquals(ByteArray(16) { it.toByte() }, req.rid)
        assertArrayEquals(ByteArray(16) { (16 + it).toByte() }, req.nonce)
        assertArrayEquals(v.getString("device_id_hex").hexToBytes(), req.dev)
        assertEquals(Item("Aziza Karimova", "86000000000000000001", 450_000_000L), req.items[0])
    }

    @Test
    fun vectorFingerprintEnrolmentCodeDeviceId() {
        assertEquals(v.getString("fingerprint"), fingerprint(v.getString("request_hex").hexToBytes()))
        val pub = v.getString("public_key_hex").hexToBytes()
        assertEquals(65, pub.size)
        assertEquals(v.getString("enrolment_code"), enrolmentCode(pub))
        assertEquals(v.getString("device_id_hex"), deviceId(pub).toHex())
    }

    @Test
    fun vectorSignatureVerifiesAfterDerFromRaw() {
        val raw = v.getString("signature_hex").hexToBytes()
        assertEquals(64, raw.size)
        val der = derFromRaw(raw)
        val sig = Signature.getInstance("SHA256withECDSA")
        sig.initVerify(publicKeyFromUncompressed(v.getString("public_key_hex").hexToBytes()))
        sig.update(v.getString("response_signing_input_hex").hexToBytes())
        assertTrue("vector signature must verify", sig.verify(der))
        // and the reverse direction recovers the raw form
        assertArrayEquals(raw, rawFromDer(der))
    }

    // ------------------------------------------------------------------ DER <-> raw

    @Test
    fun derRoundTripWithShortR() {
        val r = ByteArray(31) { (it + 1).toByte() } // 31 bytes, high bit clear
        val s = ByteArray(32) { (0x80 + it).toByte() } // high bit set -> needs 0x00 in DER
        val der = byteArrayOf(0x30, (2 + r.size + 2 + 1 + s.size).toByte(), 0x02, r.size.toByte()) + r +
            byteArrayOf(0x02, (1 + s.size).toByte(), 0x00) + s
        val raw = rawFromDer(der)
        assertEquals(64, raw.size)
        assertEquals(0, raw[0].toInt()) // r left-padded to 32 bytes
        assertArrayEquals(r, raw.copyOfRange(1, 32))
        assertArrayEquals(s, raw.copyOfRange(32, 64))
        assertArrayEquals(der, derFromRaw(raw))
    }

    @Test
    fun derFromRawStripsLeadingZerosAndRejectsBadLength() {
        val raw = ByteArray(64)
        raw[31] = 5
        raw[63] = 0x81.toByte()
        assertArrayEquals(
            byteArrayOf(0x30, 0x07, 0x02, 0x01, 0x05, 0x02, 0x02, 0x00, 0x81.toByte()),
            derFromRaw(raw),
        )
        for (n in listOf(0, 63, 65)) {
            val e = runCatching { derFromRaw(ByteArray(n)) }.exceptionOrNull()
            assertTrue(e is ProtocolError)
            assertEquals("BAD FORMAT", (e as ProtocolError).code)
            assertEquals("signature must be 64 bytes", e.detail)
        }
    }

    @Test
    fun rawFromDerRejectsMalformedDer() {
        val bad = listOf(
            byteArrayOf(),
            byteArrayOf(0x30, 0x00),
            byteArrayOf(0x30, 0x06, 0x02, 0x01, 0x05, 0x02, 0x01), // truncated
            byteArrayOf(0x30, 0x06, 0x02, 0x01, 0x05, 0x02, 0x01, 0x05, 0x00), // trailing byte
            byteArrayOf(0x30, 0x06, 0x02, 0x01, 0x85.toByte(), 0x02, 0x01, 0x05), // negative r
            byteArrayOf(0x30, 0x07, 0x02, 0x02, 0x00, 0x05, 0x02, 0x01, 0x05), // non-minimal r
        )
        for (d in bad) {
            val e = runCatching { rawFromDer(d) }.exceptionOrNull()
            assertTrue("expected ProtocolError for ${d.toHex()}", e is ProtocolError)
        }
    }

    // ------------------------------------------------------------------ text rules

    private fun requestWith(
        ref: String = "Payroll",
        name: String = "Aziza Karimova",
        overrides: Map<String, Any> = emptyMap(),
    ): ByteArray {
        val m = linkedMapOf<String, Any>(
            "v" to 1L,
            "rid" to ByteArray(16) { it.toByte() },
            "dev" to ByteArray(16) { 1 },
            "nonce" to ByteArray(16) { 2 },
            "op" to "payroll",
            "cur" to "UZS",
            "ref" to ref,
            "items" to listOf(listOf(name, "8600123412341234", 100L)),
            "total" to 100L,
            "ap" to listOf(1L, 1L),
        )
        m.putAll(overrides)
        return Cbor.dumps(m)
    }

    private fun code(data: ByteArray): String =
        (runCatching { parseRequest(data) }.exceptionOrNull() as? ProtocolError)?.let { "${it.code}: ${it.detail}" } ?: "OK"

    @Test
    fun textRules() {
        assertEquals("OK", code(requestWith()))
        assertEquals("OK", code(requestWith(name = "Дилноза Юсупова")))
        assertEquals("BAD FORMAT: name has control characters", code(requestWith(name = "Ab͸")))
        assertEquals("BAD FORMAT: name has control characters", code(requestWith(name = "Ab‮")))
        assertEquals("BAD FORMAT: ref has control characters", code(requestWith(ref = "x\u0000")))
        assertEquals("BAD FORMAT: ref has control characters", code(requestWith(ref = "x"))) // private use
        assertEquals("BAD FORMAT: ref has control characters", code(requestWith(ref = "x😀󠀁"))) // astral: emoji ok, U+E0001 is Cf
        assertEquals("BAD FORMAT: name not NFC", code(requestWith(name = "é")))
        assertEquals("BAD FORMAT: ref must be non-empty text", code(requestWith(ref = "")))
        assertEquals("BAD FORMAT: ref too long", code(requestWith(ref = "a".repeat(65))))
        assertEquals("OK", code(requestWith(ref = "a".repeat(64))))
        assertEquals("OK", code(requestWith(ref = "😀")))
        assertEquals("TOTAL MISMATCH: claimed 99, computed 100", code(requestWith(overrides = mapOf("total" to 99L))))
    }
}

/** Test-only: build a P-256 public key from a 65-byte uncompressed point. */
private fun publicKeyFromUncompressed(point: ByteArray): PublicKey {
    require(point.size == 65 && point[0] == 4.toByte())
    val params = AlgorithmParameters.getInstance("EC").apply { init(ECGenParameterSpec("secp256r1")) }
        .getParameterSpec(ECParameterSpec::class.java)
    val x = BigInteger(1, point.copyOfRange(1, 33))
    val y = BigInteger(1, point.copyOfRange(33, 65))
    return KeyFactory.getInstance("EC").generatePublic(ECPublicKeySpec(ECPoint(x, y), params))
}
