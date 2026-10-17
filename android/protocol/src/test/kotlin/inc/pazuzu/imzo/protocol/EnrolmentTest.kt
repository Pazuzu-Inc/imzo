package inc.pazuzu.imzo.protocol

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EnrolmentTest {
    private val nonce = ByteArray(16) { (0x60 + it).toByte() }
    private val domain = "IMZO/1/enrol\u0000".toByteArray(Charsets.ISO_8859_1)

    @Test
    fun answerParsesToTheRightKeysAndTheSignatureVerifies() = runBlocking {
        val signer = SoftSigner.generate()
        val approver = Approver(signer, MemoryCounter())

        val answer = approver.enrolmentAnswer(nonce, "demo")

        assertArrayEquals(answer, Cbor.dumps(Cbor.loads(answer))) // canonical
        val m = Cbor.loads(answer) as Map<*, *>
        assertEquals(setOf("pub", "sig"), m.keys)
        assertArrayEquals(signer.publicKey, m["pub"] as ByteArray)
        val sig = m["sig"] as ByteArray
        assertEquals(64, sig.size)
        assertTrue(verifyRaw(signer.publicKey, domain + nonce + "demo".toByteArray(), sig))
        // bound to the nonce and the org
        assertFalse(verifyRaw(signer.publicKey, domain + nonce + "demo2".toByteArray(), sig))
        assertFalse(verifyRaw(signer.publicKey, domain + ByteArray(16) + "demo".toByteArray(), sig))
    }

    @Test
    fun orgIsSignedAsUtf8() = runBlocking {
        val signer = SoftSigner.generate()
        val answer = Approver(signer, MemoryCounter()).enrolmentAnswer(nonce, "Bank Özbek")
        val sig = (Cbor.loads(answer) as Map<*, *>)["sig"] as ByteArray
        assertTrue(verifyRaw(signer.publicKey, domain + nonce + "Bank Özbek".toByteArray(Charsets.UTF_8), sig))
    }

    @Test
    fun enrolmentDoesNotConsumeACounter() = runBlocking {
        val counter = MemoryCounter()
        Approver(SoftSigner.generate(), counter).enrolmentAnswer(nonce, "demo")
        assertEquals(0L, counter.value)
    }

    @Test
    fun badNonceSizeIsRefusedWithoutSigning() = runBlocking {
        val signer = SoftSigner.generate()
        val e = runCatching { Approver(signer, MemoryCounter()).enrolmentAnswer(ByteArray(15), "demo") }.exceptionOrNull()
        assertEquals("BAD FORMAT", (e as ProtocolError).code)
        assertEquals(0, signer.signCalls)
    }

    @Test
    fun approverCanBeBuiltBeforeTheKeyIsReadable() {
        val broken = object : Signer {
            override val publicKey: ByteArray get() = throw IllegalStateException("no key yet")
            override suspend fun sign(message: ByteArray): ByteArray = throw IllegalStateException("no key yet")
        }
        val approver = Approver(broken, MemoryCounter()) // must not throw
        assertTrue(runCatching { approver.id }.isFailure)
    }

    // ---- the `en` request body

    @Test
    fun parsesAnEnrolmentRequest() {
        val body = Cbor.dumps(linkedMapOf("v" to 1L, "nonce" to nonce, "org" to "demo"))
        val r = parseEnrolmentRequest(body)
        assertArrayEquals(nonce, r.nonce)
        assertEquals("demo", r.org)
    }

    @Test
    fun rejectsMalformedEnrolmentRequests() {
        fun code(obj: Any): String? = (runCatching { parseEnrolmentRequest(Cbor.dumps(obj)) }.exceptionOrNull() as? ProtocolError)?.code
        val ok = linkedMapOf<String, Any>("v" to 1L, "nonce" to nonce, "org" to "demo")
        assertEquals("BAD FORMAT", code(ok + ("extra" to 1L)))
        assertEquals("BAD FORMAT", code(ok - "org"))
        assertEquals("BAD FORMAT", code(ok + ("v" to 2L)))
        assertEquals("BAD FORMAT", code(ok + ("nonce" to ByteArray(8))))
        assertEquals("BAD FORMAT", code(ok + ("org" to "")))
        assertEquals("BAD FORMAT", code(ok + ("org" to "x".repeat(200))))
        assertEquals("BAD FORMAT", code(ok + ("org" to 5L)))
        assertEquals("BAD FORMAT", code(listOf(1L)))
        assertEquals("BAD FORMAT", (runCatching { parseEnrolmentRequest(byteArrayOf(0xff.toByte())) }.exceptionOrNull() as ProtocolError).code)
    }
}
