package inc.pazuzu.imzo.protocol

import java.io.ByteArrayOutputStream

/**
 * Deterministic CBOR (RFC 8949 core deterministic form) restricted to what Imzo uses: unsigned
 * ints, byte strings, text strings, arrays and maps with text keys. Mirrors `imzo_ref.py`
 * (`_head`, `dumps`, `_read_head`, `_decode`, `loads`), including the order of checks.
 *
 * Value types: `Long` (unsigned only), `ByteArray`, `String`, `List<Any>`, `Map<String, Any>`
 * (decoded maps are `LinkedHashMap`, in wire order).
 *
 * Deliberate difference from the reference: unsigned ints are held in a `Long`, so values in
 * [2^63, 2^64) are rejected as BAD FORMAT (the reference accepts up to 2^64-1). No Imzo field
 * needs them (amounts and counters are capped at 2^53).
 */
object Cbor {
    const val MAX_MESSAGE_BYTES = 16 * 1024
    const val MAX_DEPTH = 4

    private const val BAD = "BAD FORMAT"

    // ------------------------------------------------------------------ encode

    fun dumps(value: Any): ByteArray {
        val out = ByteArrayOutputStream()
        write(out, value)
        return out.toByteArray()
    }

    private fun write(out: ByteArrayOutputStream, value: Any?) {
        when (value) {
            is Long -> {
                require(value >= 0) { "negative integers are not used in Imzo messages" }
                head(out, 0, value)
            }
            is Int -> write(out, value.toLong())
            is ByteArray -> {
                head(out, 2, value.size.toLong())
                out.write(value)
            }
            is String -> {
                val raw = value.toByteArray(Charsets.UTF_8)
                head(out, 3, raw.size.toLong())
                out.write(raw)
            }
            is List<*> -> {
                head(out, 4, value.size.toLong())
                for (x in value) write(out, x)
            }
            is Map<*, *> -> {
                val pairs = value.entries.map { (k, v) ->
                    require(k is String) { "map keys must be text" }
                    dumps(k) to dumps(v ?: throw IllegalArgumentException("null is not used in Imzo messages"))
                }.sortedWith { a, b -> compareUnsigned(a.first, b.first) }
                head(out, 5, pairs.size.toLong())
                for ((k, v) in pairs) {
                    out.write(k)
                    out.write(v)
                }
            }
            else -> throw IllegalArgumentException("unsupported type ${value?.javaClass?.simpleName ?: "null"}")
        }
    }

    /** Shortest-form head; `n` is a non-negative Long. */
    private fun head(out: ByteArrayOutputStream, major: Int, n: Long) {
        val m = major shl 5
        when {
            n < 24 -> out.write(m or n.toInt())
            n < 0x100 -> { out.write(m or 24); out.write(n.toInt()) }
            n < 0x10000 -> { out.write(m or 25); writeBe(out, n, 2) }
            n < 0x100000000L -> { out.write(m or 26); writeBe(out, n, 4) }
            else -> { out.write(m or 27); writeBe(out, n, 8) }
        }
    }

    private fun writeBe(out: ByteArrayOutputStream, n: Long, size: Int) {
        for (shift in (size - 1) downTo 0) out.write(((n ushr (8 * shift)) and 0xFF).toInt())
    }

    /** Unsigned lexicographic order, shorter first when one is a prefix of the other (Python bytes order). */
    private fun compareUnsigned(a: ByteArray, b: ByteArray): Int {
        val n = minOf(a.size, b.size)
        for (i in 0 until n) {
            val d = (a[i].toInt() and 0xFF) - (b[i].toInt() and 0xFF)
            if (d != 0) return d
        }
        return a.size - b.size
    }

    // ------------------------------------------------------------------ decode

    /** Strict decode: bounded size, no trailing bytes, canonical form only. */
    fun loads(data: ByteArray): Any {
        if (data.size > MAX_MESSAGE_BYTES) throw ProtocolError(BAD, "message too large")
        val r = Reader(data)
        val value = decode(r, 0)
        if (r.pos != data.size) throw ProtocolError(BAD, "trailing bytes")
        if (!dumps(value).contentEquals(data)) throw ProtocolError(BAD, "not canonical")
        return value
    }

    private class Reader(val data: ByteArray, var pos: Int = 0)

    /**
     * Reads a head and returns (major, argument). The argument is an unsigned 64-bit value held
     * in a Long, so it is negative when >= 2^63; callers must use [exceeds] / sign checks.
     */
    private fun readHead(r: Reader): Pair<Int, Long> {
        if (r.pos >= r.data.size) throw ProtocolError(BAD, "truncated")
        val b = r.data[r.pos].toInt() and 0xFF
        val major = b shr 5
        val ai = b and 31
        r.pos++
        if (ai < 24) return major to ai.toLong()
        if (ai in 24..27) {
            val size = 1 shl (ai - 24)
            if (r.pos + size > r.data.size) throw ProtocolError(BAD, "truncated")
            var n = 0L
            for (k in 0 until size) n = (n shl 8) or (r.data[r.pos + k].toLong() and 0xFF)
            r.pos += size
            return major to n
        }
        throw ProtocolError(BAD, "indefinite or reserved length")
    }

    /** True when the unsigned value `n` is larger than `limit` (n is negative if >= 2^63). */
    private fun exceeds(n: Long, limit: Int): Boolean = n < 0 || n > limit

    private fun decode(r: Reader, depth: Int): Any {
        if (depth > MAX_DEPTH) throw ProtocolError(BAD, "nesting too deep")
        val (major, n) = readHead(r)
        when (major) {
            0 -> {
                if (n < 0) throw ProtocolError(BAD, "integer above 2^63-1 not supported")
                return n
            }
            2, 3 -> {
                if (exceeds(n, r.data.size - r.pos)) throw ProtocolError(BAD, "truncated")
                val end = r.pos + n.toInt()
                val raw = r.data.copyOfRange(r.pos, end)
                r.pos = end
                if (major == 2) return raw
                return decodeUtf8(raw)
            }
            4 -> {
                if (exceeds(n, r.data.size - r.pos)) throw ProtocolError(BAD, "array longer than message")
                val out = ArrayList<Any>(n.toInt())
                repeat(n.toInt()) { out.add(decode(r, depth + 1)) }
                return out
            }
            5 -> {
                if (exceeds(n, r.data.size - r.pos)) throw ProtocolError(BAD, "map longer than message")
                val out = LinkedHashMap<String, Any>()
                repeat(n.toInt()) {
                    val k = decode(r, depth + 1)
                    if (k !is String) throw ProtocolError(BAD, "map keys must be text")
                    if (out.containsKey(k)) throw ProtocolError(BAD, "duplicate key")
                    out[k] = decode(r, depth + 1)
                }
                return out
            }
            else -> throw ProtocolError(BAD, "unsupported major type $major")
        }
    }

    private fun decodeUtf8(raw: ByteArray): String =
        try {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
                .decode(java.nio.ByteBuffer.wrap(raw))
                .toString()
        } catch (e: java.nio.charset.CharacterCodingException) {
            throw ProtocolError(BAD, "invalid utf-8")
        }
}
