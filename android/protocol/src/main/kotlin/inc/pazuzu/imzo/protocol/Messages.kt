package inc.pazuzu.imzo.protocol

import java.text.Normalizer

/** One payment line of an order: beneficiary name, account number, amount in tiyin. */
data class Item(val name: String, val account: String, val amount: Long)

/**
 * A validated approval request. Contains byte arrays, so equality is by content (overridden
 * below) rather than by reference.
 */
data class Request(
    val rid: ByteArray,
    val dev: ByteArray,
    val nonce: ByteArray,
    val op: String,
    val cur: String,
    val ref: String,
    val items: List<Item>,
    val total: Long,
    val ap: Pair<Int, Int>,
) {
    override fun equals(other: Any?): Boolean =
        this === other || (other is Request &&
            rid.contentEquals(other.rid) && dev.contentEquals(other.dev) && nonce.contentEquals(other.nonce) &&
            op == other.op && cur == other.cur && ref == other.ref && items == other.items &&
            total == other.total && ap == other.ap)

    override fun hashCode(): Int {
        var h = rid.contentHashCode()
        h = 31 * h + dev.contentHashCode()
        h = 31 * h + nonce.contentHashCode()
        h = 31 * h + op.hashCode()
        h = 31 * h + cur.hashCode()
        h = 31 * h + ref.hashCode()
        h = 31 * h + items.hashCode()
        h = 31 * h + total.hashCode()
        h = 31 * h + ap.hashCode()
        return h
    }
}

private const val BAD = "BAD FORMAT"
private const val MAX_ITEMS = 50
private const val MAX_NAME_BYTES = 64
private const val MAX_REF_BYTES = 64
private const val MAX_APPROVERS = 5
private const val MAX_AMOUNT = 1L shl 53
private val OPS = setOf("payroll", "transfer", "beneficiary", "limit")
private val CURRENCIES = setOf("UZS")
private val REQUEST_KEYS = setOf("v", "rid", "dev", "nonce", "op", "cur", "ref", "items", "total", "ap")

private val FORBIDDEN_TYPES = setOf(
    Character.CONTROL, Character.FORMAT, Character.SURROGATE, Character.PRIVATE_USE, Character.UNASSIGNED,
).map { it.toInt() }.toSet()

private fun isDigits(s: String) = s.isNotEmpty() && s.all { it in '0'..'9' }

/**
 * Same checks, same order as `_check_text` in the reference. The Python "C*" categories are
 * Cc, Cf, Cs, Co, Cn; unassigned (Cn) is rejected on purpose so a difference between the JVM's and
 * Python's Unicode tables can only make this stricter, never looser.
 */
private fun checkText(value: Any?, maxBytes: Int, what: String) {
    if (value !is String || value.isEmpty()) throw ProtocolError(BAD, "$what must be non-empty text")
    if (value.toByteArray(Charsets.UTF_8).size > maxBytes) throw ProtocolError(BAD, "$what too long")
    if (Normalizer.normalize(value, Normalizer.Form.NFC) != value) throw ProtocolError(BAD, "$what not NFC")
    var i = 0
    while (i < value.length) {
        val cp = value.codePointAt(i)
        if (Character.getType(cp) in FORBIDDEN_TYPES) throw ProtocolError(BAD, "$what has control characters")
        i += Character.charCount(cp)
    }
}

private fun isBytes16(v: Any?) = v is ByteArray && v.size == 16

/** Port of `validate_request`: structural and semantic checks on a decoded request map. */
private fun validateRequest(obj: Any?): Request {
    if (obj !is Map<*, *> || obj.keys != REQUEST_KEYS) throw ProtocolError(BAD, "wrong or missing fields")
    if (obj["v"] != 1L) throw ProtocolError(BAD, "unsupported version")
    for (key in listOf("rid", "dev", "nonce")) {
        if (!isBytes16(obj[key])) throw ProtocolError(BAD, "$key must be 16 bytes")
    }
    val op = obj["op"]
    if (op !is String || op !in OPS) throw ProtocolError(BAD, "unknown op")
    val cur = obj["cur"]
    if (cur !is String || cur !in CURRENCIES) throw ProtocolError(BAD, "unsupported currency")
    checkText(obj["ref"], MAX_REF_BYTES, "ref")
    val rawItems = obj["items"]
    if (rawItems !is List<*> || rawItems.size !in 1..MAX_ITEMS) throw ProtocolError(BAD, "item count out of range")
    var total = 0L
    val items = ArrayList<Item>(rawItems.size)
    for (it in rawItems) {
        if (it !is List<*> || it.size != 3) throw ProtocolError(BAD, "item must be [name, account, amount]")
        val (name, account, amt) = it
        checkText(name, MAX_NAME_BYTES, "name")
        if (account !is String || !isDigits(account) || account.length !in listOf(16, 20)) {
            throw ProtocolError(BAD, "account must be 16 or 20 digits")
        }
        if (amt !is Long || amt !in 1..MAX_AMOUNT) throw ProtocolError(BAD, "amount out of range")
        total += amt // at most 50 * 2^53, cannot overflow
        items += Item(name as String, account, amt)
    }
    val claimed = obj["total"]
    if (claimed !is Long || claimed != total) throw ProtocolError("TOTAL MISMATCH", "claimed $claimed, computed $total")
    val ap = obj["ap"]
    if (ap !is List<*> || ap.size != 2 || !ap.all { it is Long }) throw ProtocolError(BAD, "ap must be [k, n]")
    val k = ap[0] as Long
    val n = ap[1] as Long
    if (k !in 1..n || n > MAX_APPROVERS) throw ProtocolError(BAD, "ap must be [k, n]")
    return Request(
        rid = obj["rid"] as ByteArray,
        dev = obj["dev"] as ByteArray,
        nonce = obj["nonce"] as ByteArray,
        op = op,
        cur = cur,
        ref = obj["ref"] as String,
        items = items,
        total = total,
        ap = k.toInt() to n.toInt(),
    )
}

/** Strict decode (canonical CBOR only) followed by the reference's validation. */
fun parseRequest(data: ByteArray): Request = validateRequest(Cbor.loads(data))

/** The server's enrolment challenge: a single-use nonce and the organisation the device joins. */
class EnrolmentRequest(val nonce: ByteArray, val org: String)

private const val MAX_ORG_BYTES = 128

/** Strict decode of the `en` message body `{"v":1,"nonce":16 bytes,"org":text}` (see `Server.begin_enrolment`). */
fun parseEnrolmentRequest(data: ByteArray): EnrolmentRequest {
    val obj = Cbor.loads(data)
    if (obj !is Map<*, *> || obj.keys != setOf("v", "nonce", "org")) throw ProtocolError(BAD, "wrong or missing fields")
    if (obj["v"] != 1L) throw ProtocolError(BAD, "unsupported version")
    val nonce = obj["nonce"]
    if (nonce !is ByteArray || nonce.size != 16) throw ProtocolError(BAD, "nonce must be 16 bytes")
    val org = obj["org"]
    if (org !is String || org.isEmpty() || org.toByteArray(Charsets.UTF_8).size > MAX_ORG_BYTES) {
        throw ProtocolError(BAD, "org must be short non-empty text")
    }
    return EnrolmentRequest(nonce, org)
}
