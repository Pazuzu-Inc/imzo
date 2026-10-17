package inc.pazuzu.imzo.protocol

import java.security.MessageDigest
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class ApproverTest {
    private val v get() = Cases.vectors
    private val request get() = v.getString("request_hex").hexToBytes()

    private fun sha256(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b)

    /** The reference's vector key: derived from the same label as `make_vectors`. */
    private fun vectorSigner() = SoftSigner.fromLabel("imzo-test-vector-key").also {
        assertEquals(v.getString("public_key_hex"), it.publicKey.toHex()) // sanity: key derivation matches Python
    }

    private fun parse(response: ByteArray): Map<*, *> = Cbor.loads(response) as Map<*, *>

    private fun signingInput(m: Map<*, *>): ByteArray {
        val body = linkedMapOf<String, Any>()
        for (k in listOf("v", "rid", "dev", "dec", "ctr", "h")) body[k] = m[k]!!
        return "IMZO/1/approve\u0000".toByteArray(Charsets.ISO_8859_1) + Cbor.dumps(body)
    }

    @Test
    fun reviewShowsFieldsFromTheVectorRequest() {
        val approver = Approver(vectorSigner(), MemoryCounter())
        assertEquals(v.getString("device_id_hex"), approver.id.toHex())
        val r = approver.review(request)
        assertEquals(v.getLong("total_tiyin"), r.total)
        assertEquals(v.getInt("item_count"), r.count)
        assertEquals(v.getString("fingerprint"), r.fingerprint)
        assertEquals("payroll", r.op)
        assertEquals("Payroll October 2026", r.ref)
        assertEquals(1 to 1, r.approval)
        assertEquals(3, r.items.size)
        assertEquals(Item("Aziza Karimova", "86000000000000000001", 450_000_000L), r.items[0])
        assertArrayEquals(ByteArray(16) { it.toByte() }, r.rid)
        assertArrayEquals(sha256(request), r.h)
    }

    @Test
    fun reviewForAnotherDeviceIsRejected() {
        val other = Approver(SoftSigner.generate(), MemoryCounter())
        val e = runCatching { other.review(request) }.exceptionOrNull()
        assertTrue(e is ProtocolError)
        assertEquals("WRONG DEVICE", (e as ProtocolError).code)
    }

    @Test
    fun reviewRejectsMalformedRequests() {
        val approver = Approver(vectorSigner(), MemoryCounter())
        val e = runCatching { approver.review(request + 0) }.exceptionOrNull()
        assertEquals("BAD FORMAT", (e as ProtocolError).code)
    }

    @Test
    fun approveAndRejectResponsesVerify() = runBlocking {
        val signer = vectorSigner()
        val counter = MemoryCounter()
        val approver = Approver(signer, counter)
        val review = approver.review(request)

        val approve = parse(approver.respond(review, true))
        val reject = parse(approver.respond(review, false))

        assertEquals(setOf("v", "rid", "dev", "dec", "ctr", "h", "sig"), approve.keys)
        assertEquals(1L, approve["v"])
        assertEquals(1L, approve["dec"])
        assertEquals(1L, approve["ctr"])
        assertEquals(0L, reject["dec"])
        assertEquals(2L, reject["ctr"])
        for (m in listOf(approve, reject)) {
            assertArrayEquals(sha256(request), m["h"] as ByteArray)
            assertArrayEquals(review.rid, m["rid"] as ByteArray)
            assertArrayEquals(approver.id, m["dev"] as ByteArray)
            val sig = m["sig"] as ByteArray
            assertEquals(64, sig.size)
            assertTrue(verifyRaw(signer.publicKey, signingInput(m), sig))
            // a different message must not verify under the same signature
            assertFalse(verifyRaw(signer.publicKey, signingInput(m) + 0, sig))
        }
        assertEquals(2L, counter.value)
    }

    @Test
    fun mutatingReturnedArraysCannotChangeWhatRespondSigns() = runBlocking {
        val signer = vectorSigner()
        val approver = Approver(signer, MemoryCounter())
        val review = approver.review(request)
        val rid = review.rid.copyOf()
        val id = approver.id.copyOf()
        // every access hands out a fresh copy ...
        assertNotSame(review.rid, review.rid)
        assertNotSame(review.h, review.h)
        assertNotSame(approver.id, approver.id)
        // ... so scribbling over what a caller got changes nothing
        review.rid.fill(0x7f)
        review.h.fill(0x7f)
        approver.id.fill(0x7f)
        val m = parse(approver.respond(review, true))
        assertArrayEquals(rid, m["rid"] as ByteArray)
        assertArrayEquals(sha256(request), m["h"] as ByteArray)
        assertArrayEquals(id, m["dev"] as ByteArray)
        assertEquals(v.getString("device_id_hex"), (m["dev"] as ByteArray).toHex())
        assertTrue(verifyRaw(signer.publicKey, signingInput(m), m["sig"] as ByteArray))
        assertArrayEquals(rid, review.rid)
    }

    @Test
    fun reviewDoesNotAliasTheRequestBytesTheCallerKeeps() = runBlocking {
        val approver = Approver(vectorSigner(), MemoryCounter())
        val bytes = request.copyOf()
        val review = approver.review(bytes)
        bytes.fill(0) // the caller reuses its buffer
        assertArrayEquals(ByteArray(16) { it.toByte() }, review.rid)
        assertArrayEquals(sha256(request), review.h)
        assertEquals(sha256(request).toHex(), (parse(approver.respond(review, true))["h"] as ByteArray).toHex())
    }

    @Test
    fun equalReviewsAreEqualByContent() {
        val approver = Approver(vectorSigner(), MemoryCounter())
        val a = approver.review(request)
        val b = approver.review(request)
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
    }

    @Test
    fun responseIsCanonicalCbor() = runBlocking {
        val approver = Approver(vectorSigner(), MemoryCounter())
        val bytes = approver.respond(approver.review(request), true)
        assertArrayEquals(bytes, Cbor.dumps(Cbor.loads(bytes)))
    }

    @Test
    fun failingCounterMeansNoSignatureAndNoResponse() = runBlocking {
        val signer = vectorSigner()
        val approver = Approver(signer, object : CounterStore {
            override fun next(): Long = throw IllegalStateException("disk full")
        })
        val review = approver.review(request)
        try {
            approver.respond(review, true)
            fail("expected the counter failure to propagate")
        } catch (e: IllegalStateException) {
            assertEquals("disk full", e.message)
        }
        assertEquals("signer must not be called when the counter fails", 0, signer.signCalls)
    }

    @Test
    fun failingSignerPropagatesAndTheCounterStaysConsumed() = runBlocking {
        val base = vectorSigner()
        val cancelling = object : Signer {
            override val publicKey get() = base.publicKey
            override suspend fun sign(message: ByteArray): ByteArray = throw IllegalStateException("biometric cancelled")
        }
        val counter = MemoryCounter()
        val approver = Approver(cancelling, counter)
        val review = approver.review(request)
        try {
            approver.respond(review, true)
            fail("expected the signer failure to propagate")
        } catch (e: IllegalStateException) {
            assertEquals("biometric cancelled", e.message)
        }
        assertEquals(1L, counter.value) // persisted before signing, deliberately not rolled back
    }

    @Test
    fun badCounterOrSignatureSizeIsRefused() = runBlocking {
        val base = vectorSigner()
        val review = Approver(base, MemoryCounter()).review(request)
        for (bad in listOf(0L, -1L, (1L shl 53) + 1)) {
            val a = Approver(base, object : CounterStore { override fun next() = bad })
            val e = runCatching { a.respond(review, true) }.exceptionOrNull()
            assertEquals("COUNTER", (e as ProtocolError).code)
        }
        val shortSig = object : Signer {
            override val publicKey get() = base.publicKey
            override suspend fun sign(message: ByteArray) = ByteArray(63)
        }
        val e = runCatching { Approver(shortSig, MemoryCounter()).respond(review, true) }.exceptionOrNull()
        assertEquals("BAD FORMAT", (e as ProtocolError).code)
    }
}
