package inc.pazuzu.imzo.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/** Differential test: every `cbor` case recorded from the Python reference must behave the same here. */
@RunWith(Parameterized::class)
class CborCasesTest(private val case: CborCase) {
    companion object {
        /**
         * The reference accepts unsigned ints up to 2^64-1; this port decodes them to Long and
         * therefore rejects >= 2^63 (no Imzo field needs it). Cases that hit that documented
         * difference are pinned to the Kotlin outcome instead of the reference outcome.
         */
        private val LONG_RANGE_DIVERGENCE = setOf("uint 2**64-1")

        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun cases(): List<CborCase> = Cases.cbor
    }

    @Test
    fun matchesReference() {
        val data = case.bytes
        if (case.name in LONG_RANGE_DIVERGENCE) {
            val e = runCatching { Cbor.loads(data) }.exceptionOrNull()
            assertTrue("case '${case.name}': expected ProtocolError, got $e", e is ProtocolError)
            assertEquals("case '${case.name}'", "BAD FORMAT", (e as ProtocolError).code)
            return
        }
        if (case.ok) {
            val value = try {
                Cbor.loads(data)
            } catch (e: ProtocolError) {
                fail("case '${case.name}': reference accepts, Kotlin rejected with ${e.message}"); return
            }
            assertEquals("case '${case.name}' round trip", case.hex, Cbor.dumps(value).toHex())
        } else {
            try {
                Cbor.loads(data)
                fail("case '${case.name}': reference rejects with ${case.error}, Kotlin accepted")
            } catch (e: ProtocolError) {
                assertEquals("case '${case.name}'", case.error, e.code)
            }
        }
    }
}

class CborTest {
    @Test
    fun `maps sort by encoded key bytes`() {
        // encoded keys: "a" = 61 61, "b" = 61 62, "aa" = 62 61 61 -> order a, b, aa (length first, then bytes)
        val map = linkedMapOf<String, Any>("b" to 2L, "aa" to 3L, "a" to 1L)
        assertEquals("a361610161620262616103", Cbor.dumps(map).toHex())
    }

    @Test
    fun `round trips every supported type`() {
        val value = linkedMapOf<String, Any>(
            "n" to 300L, "b" to byteArrayOf(1, 2, 3), "s" to "café", "l" to listOf(0L, "x"),
        )
        val decoded = Cbor.loads(Cbor.dumps(value)) as Map<*, *>
        assertEquals(300L, decoded["n"])
        assertTrue((decoded["b"] as ByteArray).contentEquals(byteArrayOf(1, 2, 3)))
        assertEquals("café", decoded["s"])
        assertEquals(listOf(0L, "x"), decoded["l"])
        assertTrue(decoded is LinkedHashMap<*, *>)
    }

    @Test
    fun `dumps rejects unsupported types`() {
        val bad = listOf<Any>(true, -1L, 1.5, listOf(null as Any?), mapOf(1L to 1L), charArrayOf('a'))
        for (v in bad) {
            try {
                Cbor.dumps(v)
                fail("dumps accepted $v")
            } catch (_: IllegalArgumentException) {
            }
        }
    }

    @Test
    fun `values at or above 2^63 are rejected`() {
        val e = runCatching { Cbor.loads("1b8000000000000000".hexToBytes()) }.exceptionOrNull()
        assertTrue(e is ProtocolError && e.code == "BAD FORMAT")
    }
}
