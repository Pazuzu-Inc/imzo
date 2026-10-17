package inc.pazuzu.imzo.ui

import inc.pazuzu.imzo.PromptText
import inc.pazuzu.imzo.protocol.Item
import inc.pazuzu.imzo.protocol.Review

/**
 * Pure text for the screens, so what the approver reads is unit-tested without Compose. Nothing here
 * shortens anything: names, accounts and amounts are passed through in full.
 */

/** Tiyin as `#,##0.00 UZS` using only text and integer operations (no floating point, no overflow). */
fun formatUzs(tiyin: Long): String {
    val negative = tiyin < 0
    val digits = tiyin.toString().removePrefix("-").padStart(3, '0')
    val whole = digits.dropLast(2)
    val fraction = digits.takeLast(2)
    val grouped = whole.reversed().chunked(3).joinToString(",").reversed()
    return (if (negative) "-" else "") + "$grouped.$fraction UZS"
}

/** The first 8 bytes of the device id as hex in groups of four digits, e.g. `1011 1213 1415 1617`. */
fun formatDeviceId(id: ByteArray): String =
    id.take(8).joinToString("") { "%02x".format(it) }.chunked(4).joinToString(" ")

/** The fingerprint's six words as two lines of three, to be set in a monospace face. */
fun fingerprintLines(fingerprint: String): List<String> =
    fingerprint.split(' ').filter { it.isNotEmpty() }.chunked(3).map { it.joinToString(" ") }

data class ItemLine(val number: String, val name: String, val account: String, val amount: String)

data class ReviewDisplay(
    val op: String,
    val ref: String,
    val approval: String,
    val count: String,
    val countLabel: String,
    val total: String,
    val fingerprint: List<String>,
    val items: List<ItemLine>,
)

object ReviewPresenter {
    fun present(review: Review): ReviewDisplay = ReviewDisplay(
        op = review.op,
        ref = review.ref,
        approval = "Approval ${review.approval.first} of ${review.approval.second}",
        count = review.count.toString(),
        countLabel = if (review.count == 1) "payment" else "payments",
        total = formatUzs(review.total),
        fingerprint = fingerprintLines(review.fingerprint),
        items = review.items.mapIndexed { i, item -> line(i + 1, item) },
    )

    private fun line(number: Int, item: Item) =
        ItemLine(number.toString(), item.name, item.account, formatUzs(item.amount))
}

/** System prompt text for approving or rejecting: what is being signed, from the review itself. */
fun approvePrompt(review: Review, approve: Boolean): PromptText {
    val items = if (review.count == 1) "1 item" else "${review.count} items"
    return PromptText(if (approve) "Approve this order" else "Reject this order", "$items, total ${formatUzs(review.total)}")
}

fun enrolPrompt(code: String) = PromptText("Enrol this device", "Code $code")
