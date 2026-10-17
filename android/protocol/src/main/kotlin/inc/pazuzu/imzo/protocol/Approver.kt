package inc.pazuzu.imzo.protocol

import java.security.MessageDigest

/** The approver's private key. On Android this is the Keystore key behind a biometric prompt. */
interface Signer {
    /** 65-byte uncompressed P-256 point: 0x04 || X || Y. */
    val publicKey: ByteArray

    /** ECDSA P-256 / SHA-256 over `message`, returned as raw 64-byte `r || s`. May throw (e.g. cancelled biometric). */
    suspend fun sign(message: ByteArray): ByteArray
}

/** Monotonic response counter (replay protection). */
interface CounterStore {
    /** Increments and **persists before returning**; throws if it cannot persist. */
    fun next(): Long
}

/** What the device shows. Every field is derived from the signed request bytes, never from the claimed total. */
data class Review(
    val rid: ByteArray,
    val h: ByteArray,
    val fingerprint: String,
    val op: String,
    val ref: String,
    val total: Long,
    val count: Int,
    val items: List<Item>,
    val approval: Pair<Int, Int>,
) {
    override fun equals(other: Any?): Boolean =
        this === other || (other is Review &&
            rid.contentEquals(other.rid) && h.contentEquals(other.h) && fingerprint == other.fingerprint &&
            op == other.op && ref == other.ref && total == other.total && count == other.count &&
            items == other.items && approval == other.approval)

    override fun hashCode(): Int {
        var r = rid.contentHashCode()
        r = 31 * r + h.contentHashCode()
        r = 31 * r + fingerprint.hashCode()
        r = 31 * r + op.hashCode()
        r = 31 * r + ref.hashCode()
        r = 31 * r + total.hashCode()
        r = 31 * r + count
        r = 31 * r + items.hashCode()
        r = 31 * r + approval.hashCode()
        return r
    }
}

private const val MAX_COUNTER = 1L shl 53
private val DOMAIN_APPROVE = "IMZO/1/approve\u0000".toByteArray(Charsets.ISO_8859_1)
private val DOMAIN_ENROL = "IMZO/1/enrol\u0000".toByteArray(Charsets.ISO_8859_1)

/** Port of the reference `Device`: review a request, then sign an approve/reject answer. */
class Approver(private val signer: Signer, private val counters: CounterStore) {
    /**
     * `deviceId(signer.publicKey)`. Lazy so an [Approver] can be built before the device key is
     * readable (the key can be lost or need a screen lock); the failure then surfaces on first use.
     */
    val id: ByteArray by lazy { deviceId(signer.publicKey) }

    /** Parses (strictly) and summarises a request; `WRONG DEVICE` if it is addressed to another key. */
    fun review(requestBytes: ByteArray): Review {
        val req = parseRequest(requestBytes)
        if (!req.dev.contentEquals(id)) throw ProtocolError("WRONG DEVICE")
        return Review(
            rid = req.rid,
            h = MessageDigest.getInstance("SHA-256").digest(requestBytes),
            fingerprint = fingerprint(requestBytes),
            op = req.op,
            ref = req.ref,
            total = req.items.sumOf { it.amount }, // recomputed, never trusted
            count = req.items.size,
            items = req.items,
            approval = req.ap,
        )
    }

    /**
     * Signed answer, canonical CBOR with keys `v, rid, dev, dec, ctr, h, sig`; the signature covers
     * `"IMZO/1/approve\0" || cbor(body without sig)`. The counter is taken (and persisted by the
     * store) BEFORE signing; if `next()` throws nothing is signed. If `sign` throws, the exception
     * propagates and the counter stays consumed on purpose (persist before show).
     */
    suspend fun respond(review: Review, approve: Boolean): ByteArray {
        val ctr = counters.next()
        if (ctr !in 1..MAX_COUNTER) throw ProtocolError("COUNTER", "counter out of range") // the server would refuse it
        val body = linkedMapOf<String, Any>(
            "v" to 1L, "rid" to review.rid, "dev" to id, "dec" to (if (approve) 1L else 0L),
            "ctr" to ctr, "h" to review.h,
        )
        val sig = signer.sign(DOMAIN_APPROVE + Cbor.dumps(body))
        if (sig.size != 64) throw ProtocolError("BAD FORMAT", "signature must be 64 bytes")
        return Cbor.dumps(body + ("sig" to sig))
    }

    /**
     * Body of the `er` message: canonical CBOR `{"pub": public key, "sig": raw ECDSA}` where the
     * signature covers `"IMZO/1/enrol\0" || nonce || org.utf8` (proof of possession, mirrors
     * `Device.enrolment_answer`). Uses no counter. May throw whatever the signer throws.
     */
    suspend fun enrolmentAnswer(nonce: ByteArray, org: String): ByteArray {
        if (nonce.size != 16) throw ProtocolError("BAD FORMAT", "nonce must be 16 bytes")
        val sig = signer.sign(DOMAIN_ENROL + nonce + org.toByteArray(Charsets.UTF_8))
        if (sig.size != 64) throw ProtocolError("BAD FORMAT", "signature must be 64 bytes")
        return Cbor.dumps(linkedMapOf<String, Any>("pub" to signer.publicKey, "sig" to sig))
    }
}
