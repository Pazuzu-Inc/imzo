package inc.pazuzu.imzo.protocol

import java.util.Base64
import java.util.zip.CRC32
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class FramesTest {
    private val v get() = Cases.vectors

    private fun codeOf(block: () -> Any?): String? = try {
        block(); null
    } catch (e: ProtocolError) {
        e.code
    }

    private fun framesOf(c: org.json.JSONObject): List<String> =
        c.getJSONArray("frames").let { a -> (0 until a.length()).map { a.getString(it) } }

    // ------------------------------------------------------------------ cases.json (batch decode)

    @Test
    fun frameCasesMatchReference() {
        val arr = Cases.root.getJSONArray("frames")
        assertEquals(39, arr.length())
        val failures = mutableListOf<String>()
        for (i in 0 until arr.length()) {
            val c = arr.getJSONObject(i)
            val name = c.getString("name")
            val result = runCatching { Frames.decode(framesOf(c)) }
            if (c.has("error")) {
                val e = result.exceptionOrNull()
                if (e !is ProtocolError) failures += "$name: expected ${c.getString("error")}, got $result"
                else if (e.code != c.getString("error")) failures += "$name: expected ${c.getString("error")}, got ${e.code} (${e.detail})"
            } else {
                val decoded = result.getOrNull()
                if (decoded == null) {
                    failures += "$name: unexpected ${result.exceptionOrNull()}"
                } else {
                    if (decoded.first != c.getString("kind")) failures += "$name: kind ${decoded.first}"
                    if (decoded.second.toHex() != c.getString("body_hex")) failures += "$name: body differs"
                }
            }
        }
        if (failures.isNotEmpty()) fail(failures.joinToString("\n"))
    }

    @Test
    fun vectorFramesDecodeToTheRequestAndEncodeIdentically() {
        val arr = v.getJSONArray("frames_chunk_200")
        val frames = (0 until arr.length()).map { arr.getString(it) }
        val request = v.getString("request_hex").hexToBytes()
        val (kind, body) = Frames.decode(frames)
        assertEquals("rq", kind)
        assertArrayEquals(request, body)
        assertEquals(frames, Frames.encode("rq", request, 200, sid = "00000001"))
        assertTrue(frames.all { it.length < 400 })
    }

    @Test
    fun encodeDecodeRoundTripAndShape() {
        for (size in listOf(0, 1, 3, 4, 195, 196, 197, 200, 396, 1000)) {
            val body = ByteArray(size) { (it * 7 + 3).toByte() }
            for (chunk in listOf(1, 50, 200)) {
                if ((size + 4 + chunk - 1) / chunk > 256) continue // the 256-frame cap is tested separately
                val frames = Frames.encode("rs", body, chunk, sid = "abc")
                val (kind, out) = Frames.decode(frames.reversed())
                assertEquals("rs", kind)
                assertArrayEquals("size $size chunk $chunk", body, out)
                assertEquals((size + 4 + chunk - 1) / chunk, frames.size)
            }
        }
        val f = Frames.encode("en", byteArrayOf(1, 2, 3))
        assertTrue(f[0].matches(Regex("IMQ1\\.en\\.[0-9a-f]{8}\\.1\\.1\\.[A-Za-z0-9_-]+")))
        assertTrue(Frames.encode("en", byteArrayOf(1))[0] != Frames.encode("en", byteArrayOf(1))[0]) // random sid
    }

    @Test
    fun encodeRejectsBadArguments() {
        for (block in listOf<() -> Any>(
            { Frames.encode("xx", byteArrayOf()) },
            { Frames.encode("rq", byteArrayOf(), chunk = 0) },
            { Frames.encode("rq", ByteArray(300), chunk = 1) }, // would need 304 frames > 256
        )) {
            try { block(); fail("expected IllegalArgumentException") } catch (_: IllegalArgumentException) {}
        }
    }

    // ------------------------------------------------------------------ FrameAssembler

    private val body = ByteArray(700) { (it * 31).toByte() }
    private val session = Frames.encode("rq", body, 200, sid = "aaaa0001") // 4 frames

    @Test
    fun assemblerOutOfOrderWithRepeats() {
        assertEquals(4, session.size)
        val a = FrameAssembler()
        assertNull(a.result())
        val order = listOf(2, 0, 2, 2, 0, 3)
        val expectedHave = listOf(1, 2, 2, 2, 2, 3)
        order.forEachIndexed { k, idx ->
            assertEquals(Progress(expectedHave[k], 4, "aaaa0001"), a.add(session[idx]))
            assertNull(a.result())
        }
        assertEquals(Progress(4, 4, "aaaa0001"), a.add(session[1]))
        val (kind, out) = a.result()!!
        assertEquals("rq", kind)
        assertArrayEquals(body, out)
        // repeating a frame after completion is harmless
        assertEquals(Progress(4, 4, "aaaa0001"), a.add(session[0]))
        assertNotNull(a.result())
    }

    @Test
    fun secondSessionResetsAndReportsHaveOne() {
        val other = Frames.encode("rq", ByteArray(450) { it.toByte() }, 200, sid = "bbbb0002") // 3 frames
        val a = FrameAssembler()
        a.add(session[0]); a.add(session[1]); a.add(session[2])
        assertEquals(Progress(1, 3, "bbbb0002"), a.add(other[1]))
        assertNull(a.result())
        a.add(other[0]); a.add(other[2])
        assertArrayEquals(ByteArray(450) { it.toByte() }, a.result()!!.second)
        // going back to the first session starts over; nothing from before is mixed in
        assertEquals(Progress(1, 4, "aaaa0001"), a.add(session[3]))
        assertNull(a.result())
        // same sid but a different declared count or kind is also a new session
        assertEquals(Progress(1, 3, "aaaa0001"), a.add("IMQ1.rq.aaaa0001.1.3.AAAA"))
        assertEquals(Progress(1, 3, "aaaa0001"), a.add("IMQ1.rs.aaaa0001.1.3.AAAA"))
    }

    @Test
    fun malformedFrameThrowsAndLeavesStateUnchanged() {
        val a = FrameAssembler()
        a.add(session[0])
        a.add(session[1])
        val bad = listOf(
            "", "garbage", "IMQ1.rq.aaaa0001.1.4", "IMQ2.rq.aaaa0001.1.4.AAAA", "IMQ1.xx.aaaa0001.1.4.AAAA",
            "IMQ1.rq.aaaa0001.a.4.AAAA", "IMQ1.rq.aaaa0001.0.4.AAAA", "IMQ1.rq.aaaa0001.5.4.AAAA",
            "IMQ1.rq.bbbb0002.1.257.AAAA", "IMQ1.rq.bbbb0002.257.257.AAAA", "IMQ1.rq.bbbb0002.1.99999999999999999999.AAAA",
            "IMQ1.rq.bbbb0002.١.١.AAAA", "IMQ1.rq..1.1.AAAA", "IMQ1.rq.bbbb0002.1.1.AAAA.AAAA",
            "IMQ1.rq.bbbb0002.1.1.A", // length 1 mod 4
        )
        for (f in bad) {
            val e = runCatching { a.add(f) }.exceptionOrNull()
            assertTrue("expected ProtocolError for <$f>, got $e", e is ProtocolError)
            assertEquals(f, "BAD FORMAT", (e as ProtocolError).code)
        }
        // still in session aaaa0001 with exactly two parts
        assertEquals(Progress(2, 4, "aaaa0001"), a.add(session[0]))
        a.add(session[2]); a.add(session[3])
        assertArrayEquals(body, a.result()!!.second)
    }

    @Test
    fun hostileSizesAreRejectedBeforeDecoding() {
        val a = FrameAssembler()
        // 257 declared frames: rejected on the header, nothing allocated for the session
        assertEquals("BAD FORMAT", codeOf { a.add("IMQ1.rq.00000001.1.257.AAAA") })
        // a 100 KB frame string: rejected on length alone
        val huge = "IMQ1.rq.00000001.1.1." + "A".repeat(100_000)
        val e = runCatching { a.add(huge) }.exceptionOrNull() as ProtocolError
        assertEquals("BAD FORMAT", e.code)
        assertEquals("frame too long", e.detail)
        assertEquals("BAD FORMAT", codeOf { Frames.decode(listOf(huge)) })
        // the bound is on the whole frame string: 2048 passes the length check, 2049 does not
        val pad = "IMQ1.rq.00000001.1.1."
        val ok2048 = pad + "A".repeat(2048 - pad.length)
        val big2049 = pad + "A".repeat(2049 - pad.length)
        assertEquals("frame too long", (runCatching { a.add(big2049) }.exceptionOrNull() as ProtocolError).detail)
        assertTrue(runCatching { a.add(ok2048) }.exceptionOrNull().let { it == null || (it as ProtocolError).detail != "frame too long" })
    }

    @Test
    fun strictBase64InAssembler() {
        val a = FrameAssembler()
        for (data in listOf("YWJjNSRB+g", "YWJjNSRB/g", "YWJjNSRBwg==", "YWJjNSRBwg=", "YWJjNSRBwh", "YWJj NSRBwg")) {
            val e = runCatching { a.add("IMQ1.rq.00000012.1.1.$data") }.exceptionOrNull()
            assertEquals(data, "BAD FORMAT", (e as ProtocolError).code)
        }
        // and the canonical form of the same data is accepted
        assertEquals(Progress(1, 1, "00000012"), a.add("IMQ1.rq.00000012.1.1.YWJjNSRBwg"))
        assertArrayEquals("abc".toByteArray(), a.result()!!.second)
    }

    @Test
    fun emptySidIsRejectedByTheAssembler() {
        assertEquals("BAD FORMAT", codeOf { FrameAssembler().add("IMQ1.rq..1.1.AAAAAA") })
    }

    @Test
    fun crcFailureThrowsAndResets() {
        val a = FrameAssembler()
        // flip the first data character of part 1 to another valid character
        val first = session[0]
        val idx = first.lastIndexOf('.') + 1
        val flipped = first.substring(0, idx) + (if (first[idx] == 'A') 'B' else 'A') + first.substring(idx + 1)
        a.add(flipped); a.add(session[1]); a.add(session[2]); a.add(session[3])
        assertEquals("CRC", codeOf { a.result() })
        assertNull(a.result()) // reset: nothing left, the UI can rescan
        for (f in session) a.add(f)
        assertArrayEquals(body, a.result()!!.second)
    }

    @Test
    fun completeButTooShortPayloadIsBadFormatAndResets() {
        val a = FrameAssembler()
        a.add("IMQ1.rq.00000001.1.1.AAA") // 2 bytes only
        assertEquals("BAD FORMAT", codeOf { a.result() })
        assertNull(a.result())
    }

    @Test
    fun explicitResetClearsProgress() {
        val a = FrameAssembler()
        a.add(session[0])
        a.reset()
        assertEquals(Progress(1, 4, "aaaa0001"), a.add(session[1]))
    }

    @Test
    fun trailerIsBigEndianCrc32() {
        val frame = Frames.encode("rq", "abc".toByteArray(), sid = "s")[0]
        val raw = Base64.getUrlDecoder().decode(frame.substringAfterLast('.'))
        val crc = CRC32().apply { update("abc".toByteArray()) }.value
        val trailer = raw.copyOfRange(3, 7)
        assertEquals(crc, trailer.fold(0L) { acc, b -> (acc shl 8) or (b.toLong() and 0xFF) })
    }
}
