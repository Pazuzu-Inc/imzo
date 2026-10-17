package inc.pazuzu.imzo.protocol

import java.math.BigInteger
import java.security.MessageDigest

/**
 * Derived identifiers and ECDSA signature encoding. Mirrors `imzo_ref.py` (`_syllable`,
 * `fingerprint`, `enrolment_code`, `device_id`, `raw_from_der`, `der_from_raw`).
 */

private const val CONSONANTS = "bdfgklmnprstvzjh" // 16
private const val VOWELS = "aeiu" // 4

private fun sha256(data: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(data)

private fun syllable(x: Int): String = "${CONSONANTS[x shr 2]}${VOWELS[x and 3]}"

/**
 * Six pronounceable two-syllable words (72 bits) from SHA-256 of the request. Lets the approver
 * compare two screens; needs no word list.
 */
fun fingerprint(canonicalRequest: ByteArray): String {
    val n = BigInteger(1, sha256(canonicalRequest).copyOfRange(0, 9))
    val mask = BigInteger.valueOf(0xFFF)
    return (0 until 6).joinToString(" ") { i ->
        val chunk = n.shiftRight(72 - 12 * (i + 1)).and(mask).toInt()
        syllable(chunk shr 6) + syllable(chunk and 63)
    }
}

/** Six digits derived from the public key; both screens must show the same. */
fun enrolmentCode(publicKey: ByteArray): String {
    val n = BigInteger(1, sha256(publicKey).copyOfRange(0, 4)).toLong()
    return (n % 1_000_000).toString().padStart(6, '0')
}

/** First 16 bytes of SHA-256 of the uncompressed public key. */
fun deviceId(publicKey: ByteArray): ByteArray = sha256(publicKey).copyOfRange(0, 16)

private const val BAD_DER = "bad DER signature"

/** DER `SEQUENCE { INTEGER r, INTEGER s }` -> 64-byte `r || s` (each left-padded to 32 bytes). */
fun rawFromDer(der: ByteArray): ByteArray {
    var pos = 0

    fun byteAt(): Int {
        if (pos >= der.size) throw ProtocolError("BAD FORMAT", BAD_DER)
        return der[pos++].toInt() and 0xFF
    }

    fun length(): Int {
        val first = byteAt()
        if (first < 0x80) return first
        if (first != 0x81) throw ProtocolError("BAD FORMAT", BAD_DER) // signatures never need more
        val n = byteAt()
        if (n < 0x80) throw ProtocolError("BAD FORMAT", BAD_DER) // non-minimal length
        return n
    }

    fun integer(): ByteArray {
        if (byteAt() != 0x02) throw ProtocolError("BAD FORMAT", BAD_DER)
        val len = length()
        if (len == 0 || pos + len > der.size) throw ProtocolError("BAD FORMAT", BAD_DER)
        val body = der.copyOfRange(pos, pos + len)
        pos += len
        if (body[0].toInt() and 0x80 != 0) throw ProtocolError("BAD FORMAT", BAD_DER) // negative
        if (body.size > 1 && body[0].toInt() == 0 && body[1].toInt() and 0x80 == 0) {
            throw ProtocolError("BAD FORMAT", BAD_DER) // non-minimal
        }
        val value = if (body[0].toInt() == 0 && body.size > 1) body.copyOfRange(1, body.size) else body
        if (value.size > 32) throw ProtocolError("BAD FORMAT", BAD_DER) // does not fit 32 bytes
        return ByteArray(32 - value.size) + value
    }

    if (byteAt() != 0x30) throw ProtocolError("BAD FORMAT", BAD_DER)
    val seqLen = length()
    if (pos + seqLen != der.size) throw ProtocolError("BAD FORMAT", BAD_DER)
    val r = integer()
    val s = integer()
    if (pos != der.size) throw ProtocolError("BAD FORMAT", BAD_DER)
    return r + s
}

/** 64-byte `r || s` -> minimal DER `SEQUENCE { INTEGER r, INTEGER s }`. */
fun derFromRaw(raw: ByteArray): ByteArray {
    if (raw.size != 64) throw ProtocolError("BAD FORMAT", "signature must be 64 bytes")

    fun integer(part: ByteArray): ByteArray {
        var start = 0
        while (start < part.size - 1 && part[start].toInt() == 0) start++
        var body = part.copyOfRange(start, part.size)
        if (body[0].toInt() and 0x80 != 0) body = byteArrayOf(0) + body
        return byteArrayOf(0x02, body.size.toByte()) + body
    }

    val content = integer(raw.copyOfRange(0, 32)) + integer(raw.copyOfRange(32, 64))
    // content is at most 2 * (2 + 33) = 70 bytes, so the length always fits one byte.
    return byteArrayOf(0x30, content.size.toByte()) + content
}
