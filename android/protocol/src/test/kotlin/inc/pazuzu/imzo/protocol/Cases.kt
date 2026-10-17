package inc.pazuzu.imzo.protocol

import org.json.JSONObject

/** One entry of `cases.json` -> `cbor`: raw bytes (hex) plus the reference's recorded outcome. */
data class CborCase(val name: String, val hex: String, val ok: Boolean, val error: String?) {
    val bytes: ByteArray get() = hex.hexToBytes()
    override fun toString() = name
}

/** Loads the shared differential test data (copied onto the test classpath from `<repo>/protocol`). */
object Cases {
    private fun resource(name: String): String =
        (Cases::class.java.getResourceAsStream("/$name")
            ?: error("test resource $name missing; check processTestResources in protocol/build.gradle.kts"))
            .use { it.readBytes().toString(Charsets.UTF_8) }

    val root: JSONObject by lazy { JSONObject(resource("cases.json")) }
    val vectors: JSONObject by lazy { JSONObject(resource("vectors.json")) }

    val cbor: List<CborCase> by lazy {
        val arr = root.getJSONArray("cbor")
        (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            CborCase(o.getString("name"), o.getString("hex"), o.getBoolean("ok"), o.optString("error", "").ifEmpty { null })
        }
    }
}

fun String.hexToBytes(): ByteArray {
    require(length % 2 == 0) { "odd-length hex" }
    return ByteArray(length / 2) { ((this[2 * it].digitToInt(16) shl 4) or this[2 * it + 1].digitToInt(16)).toByte() }
}

fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
