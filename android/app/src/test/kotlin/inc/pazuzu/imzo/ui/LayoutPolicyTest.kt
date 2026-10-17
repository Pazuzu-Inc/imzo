package inc.pazuzu.imzo.ui

import inc.pazuzu.imzo.PromptText
import inc.pazuzu.imzo.protocol.Item
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LayoutPolicyTest {
    @Test
    fun portraitHeaderIsCappedAtHalfTheHeight() {
        assertEquals(400f, headerMaxHeightDp(800f, landscape = false, reservedBelowDp = 96f), 0.001f)
        assertEquals(0f, headerMaxHeightDp(0f, landscape = false, reservedBelowDp = 96f), 0.001f)
    }

    @Test
    fun landscapeHeaderGetsEverythingExceptTheButtons() {
        assertEquals(304f, headerMaxHeightDp(400f, landscape = true, reservedBelowDp = 96f), 0.001f)
        assertEquals(0f, headerMaxHeightDp(80f, landscape = true, reservedBelowDp = 96f), 0.001f) // never negative
    }

    @Test
    fun buttonsAlwaysKeepRoomInPortrait() {
        for (h in listOf(360f, 480f, 640f, 800f, 1200f)) {
            val header = headerMaxHeightDp(h, landscape = false, reservedBelowDp = 96f)
            assertTrue(h - header >= 96f)
        }
    }

    @Test
    fun enrolCodeNeverExceedsTheWidth() {
        for (width in listOf(200f, 280f, 328f, 600f)) {
            for (scale in listOf(0.85f, 1f, 1.3f, 1.5f, 2f)) {
                val size = enrolCodeFontSizeSp(width, scale)
                assertTrue(size <= 72f)
                // 6 monospace digits advance at most 0.6 em; sp are scaled by fontScale into dp
                assertTrue("w=$width s=$scale size=$size", 6 * 0.6f * size * scale <= width)
            }
        }
    }

    @Test
    fun enrolCodeIsFullSizeWhenThereIsRoom() {
        assertEquals(72f, enrolCodeFontSizeSp(328f, 1f), 0.001f)
        assertTrue(enrolCodeFontSizeSp(328f, 2f) < 72f)
    }

    @Test
    fun promptTextForApproveRejectAndEnrol() {
        val items = listOf(Item("A", "86000000000000000001", 150_000), Item("B", "86000000000000000002", 250_050))
        val review = reviewOf(items)
        assertEquals(PromptText("Approve this order", "2 items, total 4,000.50 UZS"), approvePrompt(review, true))
        assertEquals(PromptText("Reject this order", "2 items, total 4,000.50 UZS"), approvePrompt(review, false))
        assertEquals(PromptText("Enrol this device", "Code 012345"), enrolPrompt("012345"))
        assertEquals("1 item, total 0.01 UZS", approvePrompt(reviewOf(listOf(Item("A", "86000000000000000001", 1))), true).subtitle)
    }
}
