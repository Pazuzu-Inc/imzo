package inc.pazuzu.imzo.protocol

import java.io.File
import java.math.BigInteger
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Answers the Python-built `cross_request_hex` with a key derived from `cross_key_secret_hex` and
 * writes `build/cross/response.json` (approve) and `build/cross/reject.json` (reject, `dec = 0`);
 * `protocol/test_kotlin_cross.py` then feeds those to the reference `Server` (the reference verifies
 * the Kotlin signature, counter and hash).
 */
class CrossCheckTest {
    @Test
    fun answerPythonRequestAndWriteForTheReference() = runBlocking {
        val secret = BigInteger(1, Cases.root.getString("cross_key_secret_hex").hexToBytes())
        val requestHex = Cases.root.getString("cross_request_hex")
        val request = requestHex.hexToBytes()

        val signer = SoftSigner.fromScalar(secret)
        val approver = Approver(signer, MemoryCounter())
        val review = approver.review(request)
        assertEquals(3, review.count)
        assertEquals(1350050000L, review.total)

        val response = approver.respond(review, true)
        val m = Cbor.loads(response) as Map<*, *>
        assertTrue(verifyRaw(signer.publicKey, "IMZO/1/approve\u0000".toByteArray(Charsets.ISO_8859_1) +
            Cbor.dumps(linkedMapOf<String, Any>().also { b -> for (k in listOf("v", "rid", "dev", "dec", "ctr", "h")) b[k] = m[k]!! }),
            m["sig"] as ByteArray))

        val dir = File("build/cross").apply { mkdirs() }
        File(dir, "response.json").writeText(
            JSONObject()
                .put("public_key_hex", signer.publicKey.toHex())
                .put("request_hex", requestHex)
                .put("response_hex", response.toHex())
                .toString(2) + "\n",
        )
    }

    /** The same request answered with a REJECT (`dec = 0`), first answer of a fresh counter, for the reference. */
    @Test
    fun rejectPythonRequestAndWriteForTheReference() = runBlocking {
        val secret = BigInteger(1, Cases.root.getString("cross_key_secret_hex").hexToBytes())
        val requestHex = Cases.root.getString("cross_request_hex")

        val signer = SoftSigner.fromScalar(secret)
        val approver = Approver(signer, MemoryCounter())
        val response = approver.respond(approver.review(requestHex.hexToBytes()), false)
        val m = Cbor.loads(response) as Map<*, *>
        assertEquals(0L, m["dec"])
        assertEquals(1L, m["ctr"])
        assertTrue(verifyRaw(signer.publicKey, "IMZO/1/approve\u0000".toByteArray(Charsets.ISO_8859_1) +
            Cbor.dumps(linkedMapOf<String, Any>().also { b -> for (k in listOf("v", "rid", "dev", "dec", "ctr", "h")) b[k] = m[k]!! }),
            m["sig"] as ByteArray))

        val dir = File("build/cross").apply { mkdirs() }
        File(dir, "reject.json").writeText(
            JSONObject()
                .put("public_key_hex", signer.publicKey.toHex())
                .put("request_hex", requestHex)
                .put("response_hex", response.toHex())
                .toString(2) + "\n",
        )
    }

    /**
     * The Kotlin approver's enrolment answer for a fixed nonce/org, written to `build/cross/enrol.json`;
     * the Python test makes a pending enrolment with that nonce and runs the reference `finish_enrolment`.
     */
    @Test
    fun answerAnEnrolmentChallengeAndWriteForTheReference() = runBlocking {
        val secret = BigInteger(1, Cases.root.getString("cross_key_secret_hex").hexToBytes())
        val signer = SoftSigner.fromScalar(secret)
        val nonce = ByteArray(16) { (0x60 + it).toByte() }
        val org = "demo"

        val answer = Approver(signer, MemoryCounter()).enrolmentAnswer(nonce, org)
        val m = Cbor.loads(answer) as Map<*, *>
        assertTrue(verifyRaw(signer.publicKey, "IMZO/1/enrol\u0000".toByteArray(Charsets.ISO_8859_1) + nonce + org.toByteArray(), m["sig"] as ByteArray))

        val dir = File("build/cross").apply { mkdirs() }
        File(dir, "enrol.json").writeText(
            JSONObject()
                .put("public_key_hex", signer.publicKey.toHex())
                .put("nonce_hex", nonce.toHex())
                .put("org", org)
                .put("answer_hex", answer.toHex())
                .toString(2) + "\n",
        )
    }
}
