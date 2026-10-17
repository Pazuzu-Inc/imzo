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
 * writes `build/cross/response.json`; `protocol/test_kotlin_cross.py` then feeds that to the
 * reference `Server` (the reference verifies the Kotlin signature, counter and hash).
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
}
