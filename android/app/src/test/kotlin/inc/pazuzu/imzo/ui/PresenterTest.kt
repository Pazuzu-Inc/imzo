package inc.pazuzu.imzo.ui

import inc.pazuzu.imzo.protocol.Item
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class PresenterTest {
    @Test
    fun formatsTiyinAsUzsWithIntegerArithmetic() {
        assertEquals("0.00 UZS", formatUzs(0))
        assertEquals("0.05 UZS", formatUzs(5))
        assertEquals("0.99 UZS", formatUzs(99))
        assertEquals("1.00 UZS", formatUzs(100))
        assertEquals("10.01 UZS", formatUzs(1001))
        assertEquals("1,234,567.89 UZS", formatUzs(123_456_789))
        assertEquals("1,000.00 UZS", formatUzs(100_000))
        // 2^53 tiyin: beyond what a Double could keep exact after dividing
        assertEquals("90,071,992,547,409.92 UZS", formatUzs(1L shl 53))
        assertEquals("92,233,720,368,547,758.07 UZS", formatUzs(Long.MAX_VALUE))
    }

    @Test
    fun deviceIdIsTheFirstEightBytesInGroups() {
        val id = ByteArray(16) { (0x10 + it).toByte() }
        assertEquals("1011 1213 1415 1617", formatDeviceId(id))
    }

    @Test
    fun fingerprintIsShownInGroupsOfThreeWords() {
        assertEquals(listOf("bada fele kogi", "mumi puta rasi"), fingerprintLines("bada fele kogi mumi puta rasi"))
    }

    private fun review(items: List<Item>, ap: Pair<Int, Int> = 2 to 3, ref: String = "Payroll October 2026") =
        reviewOf(items, ap, ref)

    @Test
    fun reviewDisplayShowsEveryItemInFull() {
        val longName = "N".repeat(64)
        val items = sampleItems + Item(longName, "86000000000000000009", 1)
        val review = review(items)
        val d = ReviewPresenter.present(review)

        assertEquals("payroll", d.op)
        assertEquals("Payroll October 2026", d.ref)
        assertEquals("Approval 2 of 3", d.approval)
        assertEquals("4", d.count)
        assertEquals(formatUzs(items.sumOf { it.amount }), d.total)
        assertEquals(fingerprintLines(review.fingerprint), d.fingerprint)
        assertEquals(2, d.fingerprint.size)
        assertEquals(4, d.items.size)
        for ((line, item) in d.items.zip(items)) {
            assertEquals(item.name, line.name) // never shortened
            assertEquals(item.account, line.account)
            assertEquals(formatUzs(item.amount), line.amount)
            assertFalse(line.name.contains("…") || line.name.endsWith("..."))
        }
        assertEquals(longName, d.items.last().name)
        assertEquals(listOf("1", "2", "3", "4"), d.items.map { it.number })
    }

    @Test
    fun totalIsTheRecomputedSumOfTheItems() {
        val d = ReviewPresenter.present(review(sampleItems))
        assertEquals("14,735,067.89 UZS", d.total) // 1,473,506,789 tiyin
    }

    @Test
    fun countLabelIsSingularForOne() {
        assertEquals("payment", ReviewPresenter.present(review(sampleItems.take(1))).countLabel)
        assertEquals("payments", ReviewPresenter.present(review(sampleItems)).countLabel)
    }
}
