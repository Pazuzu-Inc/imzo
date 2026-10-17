package inc.pazuzu.imzo.protocol

import java.io.ByteArrayOutputStream
import java.security.SecureRandom
import java.util.Base64
import java.util.zip.CRC32

/** What the UI shows while scanning: parts present, parts declared, and the session id. */
data class Progress(val have: Int, val total: Int, val sid: String)

private const val BAD = "BAD FORMAT"
private const val MAX_PARTS = 256

/**
 * Hostile-input bound, a Kotlin-side addition (the reference has none): any frame string longer than
 * this is rejected before it is split or decoded. Legitimate frames at chunk 200 are about 290
 * characters; 2048 leaves room for larger chunks and long session ids.
 */
internal const val MAX_FRAME_CHARS = 2048

/**
 * Hostile-input bound on one reassembled message, another Kotlin-side addition: the reference caps the
 * CBOR message at 16 KiB, and the payload is that plus the 4-byte CRC32 trailer. [FrameAssembler]
 * refuses to accumulate more, however many frames of up to [MAX_FRAME_CHARS] characters arrive.
 */
internal const val MAX_PAYLOAD_BYTES = 16 * 1024 + 4

private val KINDS = setOf("rq", "rs", "en", "er")

private fun isAsciiDigits(s: String) = s.isNotEmpty() && s.all { it in '0'..'9' }

/** Value of an ASCII-digit string, or -1 if it is far beyond any valid counter (no overflow, no allocation). */
private fun counter(s: String): Int {
    val t = s.trimStart('0')
    return if (t.isEmpty()) 0 else if (t.length > 3) -1 else t.toInt()
}

/**
 * Strict unpadded base64url, like `_unb64u`: only `[A-Za-z0-9_-]`, no padding, and canonical (the
 * decoded bytes re-encode to the same text, i.e. trailing bits are zero) so each value has one encoding.
 */
private fun unb64u(text: String): ByteArray {
    if (!text.all { it in 'A'..'Z' || it in 'a'..'z' || it in '0'..'9' || it == '-' || it == '_' }) {
        throw ProtocolError(BAD, "bad base64")
    }
    if (text.length % 4 == 1) throw ProtocolError(BAD, "bad base64")
    val raw = Base64.getUrlDecoder().decode(text) // accepts unpadded input; leniency is closed by the re-encode check
    if (Base64.getUrlEncoder().withoutPadding().encodeToString(raw) != text) throw ProtocolError(BAD, "bad base64")
    return raw
}

internal class ParsedFrame(val kind: String, val sid: String, val index: Int, val total: Int, val data: ByteArray)

/**
 * `IMQ1.<kind>.<sid>.<i>.<n>.<b64url>`. Same checks as the reference (`frame.split(".", 5)`: a dot in
 * the data part fails the base64 alphabet check). The sid is opaque text; it cannot contain dots by
 * construction of the split. An empty sid is accepted here, exactly like the reference; the live
 * [FrameAssembler] additionally refuses it.
 */
internal fun parseFrame(frame: String): ParsedFrame {
    if (frame.length > MAX_FRAME_CHARS) throw ProtocolError(BAD, "frame too long")
    val pieces = frame.split(".", limit = 6)
    if (pieces.size != 6 || pieces[0] != "IMQ1" || pieces[1] !in KINDS) throw ProtocolError(BAD, "not an IMQ1 frame")
    val kind = pieces[1]
    val sid = pieces[2]
    val iText = pieces[3]
    val nText = pieces[4]
    val data = pieces[5]
    if (!isAsciiDigits(iText) || !isAsciiDigits(nText)) throw ProtocolError(BAD, "bad frame counters")
    val i = counter(iText)
    val n = counter(nText)
    if (i < 1 || i > n || n > MAX_PARTS) throw ProtocolError(BAD, "frame index out of range")
    return ParsedFrame(kind, sid, i, n, unb64u(data))
}

/** Joins the parts, checks the CRC32 trailer. Shared by [Frames.decode] and [FrameAssembler]. */
private fun assemble(parts: Array<ByteArray?>): ByteArray {
    val joined = ByteArrayOutputStream(parts.sumOf { it!!.size })
    for (p in parts) joined.write(p!!)
    val payload = joined.toByteArray()
    if (payload.size < 4) throw ProtocolError(BAD, "too short")
    val body = payload.copyOfRange(0, payload.size - 4)
    val crc = payload.copyOfRange(payload.size - 4, payload.size).fold(0L) { acc, b -> (acc shl 8) or (b.toLong() and 0xFF) }
    if (CRC32().apply { update(body) }.value != crc) throw ProtocolError("CRC")
    return body
}

/** IMQ1 animated-QR framing; mirrors `frames_encode` / `frames_decode` of the reference. */
object Frames {
    private val random = SecureRandom()

    /**
     * Splits `body` + CRC32 into frames `IMQ1.<kind>.<sid>.<i>.<n>.<chunk>`; show them in a loop.
     * A null or empty `sid` means a random one (like the reference's `sid or urandom`). Throws
     * [IllegalArgumentException] if a frame would exceed [MAX_FRAME_CHARS] (lower the chunk size).
     */
    fun encode(kind: String, body: ByteArray, chunk: Int = 200, sid: String? = null): List<String> {
        require(kind in KINDS) { "unknown kind" }
        require(chunk > 0) { "chunk must be positive" }
        val crc = CRC32().apply { update(body) }.value
        val payload = body + ByteArray(4) { (crc shr (24 - 8 * it)).toByte() }
        val parts = (payload.indices step chunk).map { payload.copyOfRange(it, minOf(it + chunk, payload.size)) }
        require(parts.size <= MAX_PARTS) { "too many frames; raise the chunk size" } // the decoder would refuse them
        val id = if (sid.isNullOrEmpty()) ByteArray(4).also { random.nextBytes(it) }.joinToString("") { "%02x".format(it) } else sid
        val enc = Base64.getUrlEncoder().withoutPadding()
        val frames = parts.mapIndexed { k, p -> "IMQ1.$kind.$id.${k + 1}.${parts.size}.${enc.encodeToString(p)}" }
        require(frames.all { it.length <= MAX_FRAME_CHARS }) { "frame longer than $MAX_FRAME_CHARS characters; lower the chunk size" } // the decoder would refuse it
        return frames
    }

    /**
     * Batch reassembly from frames in any order, with repeats; strictly equal to `frames_decode`:
     * frames of different sessions are a BAD FORMAT, a missing part is INCOMPLETE, a bad trailer CRC.
     * (If the same part appears twice with different data, the last one wins, as in the reference.)
     * For live camera scanning use [FrameAssembler], which resets on a new session instead.
     */
    fun decode(frames: Iterable<String>): Pair<String, ByteArray> {
        var kind: String? = null
        var sid = ""
        var total = 0
        var parts: Array<ByteArray?> = emptyArray()
        for (frame in frames) {
            val f = parseFrame(frame)
            if (kind == null) {
                kind = f.kind; sid = f.sid; total = f.total
                parts = arrayOfNulls(total) // total is already validated <= 256
            } else if (f.kind != kind || f.sid != sid || f.total != total) {
                throw ProtocolError(BAD, "frames from different messages")
            }
            parts[f.index - 1] = f.data
        }
        if (kind == null || parts.any { it == null }) throw ProtocolError("INCOMPLETE")
        return kind to assemble(parts)
    }
}

/**
 * Incremental reassembly for live camera scanning. The first frame fixes (kind, sid, n); a later
 * frame with a different triple starts a new session with that frame (progress `have = 1`), unlike
 * the batch [Frames.decode], which rejects mixed sessions. Not thread-safe; call from one thread.
 */
class FrameAssembler {
    private var kind: String? = null
    private var sid = ""
    private var total = 0
    private var parts: Array<ByteArray?> = emptyArray()
    private var have = 0
    private var bytes = 0 // total size of the parts held, bounded by MAX_PAYLOAD_BYTES

    /**
     * Takes one scanned frame. A malformed frame (including one longer than [MAX_FRAME_CHARS], an
     * empty sid, or a declared count above 256) throws `ProtocolError("BAD FORMAT")` and leaves the
     * state untouched; everything is validated before any session storage is allocated.
     * Repeats are fine (a repeated part replaces the earlier one, as in the reference). If the parts
     * held would exceed [MAX_PAYLOAD_BYTES] the assembler is reset and `BAD FORMAT` is thrown.
     */
    fun add(frame: String): Progress {
        val f = parseFrame(frame)
        if (f.sid.isEmpty()) throw ProtocolError(BAD, "empty session id")
        if (kind != f.kind || sid != f.sid || total != f.total) {
            kind = f.kind; sid = f.sid; total = f.total
            parts = arrayOfNulls(total)
            have = 0
            bytes = 0
        }
        val replaced = parts[f.index - 1]
        if (bytes - (replaced?.size ?: 0) + f.data.size > MAX_PAYLOAD_BYTES) {
            reset()
            throw ProtocolError(BAD, "message too large")
        }
        if (replaced == null) have++
        bytes += f.data.size - (replaced?.size ?: 0)
        parts[f.index - 1] = f.data
        return Progress(have, total, sid)
    }

    /**
     * `(kind, body)` once every part is present and the CRC32 trailer verifies; null while parts are
     * missing (INCOMPLETE is never thrown here). If the complete payload is rejected (`CRC`, or
     * `BAD FORMAT` for fewer than 4 bytes) the assembler is reset before throwing, so the UI can
     * simply rescan. A successful result stays available until [reset] or a new session starts.
     */
    fun result(): Pair<String, ByteArray>? {
        val k = kind ?: return null
        if (have < total) return null
        try {
            return k to assemble(parts)
        } catch (e: ProtocolError) {
            reset()
            throw e
        }
    }

    fun reset() {
        kind = null; sid = ""; total = 0; parts = emptyArray(); have = 0; bytes = 0
    }
}
