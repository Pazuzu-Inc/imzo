package inc.pazuzu.imzo.ui

/**
 * Sizing rules kept as pure functions so they are unit-tested (Compose rendering is only checked on a
 * device). The Compose code applies them; it never shortens text, it scrolls or shrinks instead.
 */

/**
 * Tallest the review header may be, in dp. Portrait: half the screen, so the item list and the buttons
 * always keep the other half. Landscape: everything above the buttons (`reservedBelowDp`). The header
 * scrolls inside this limit; never negative.
 */
fun headerMaxHeightDp(availableHeightDp: Float, landscape: Boolean, reservedBelowDp: Float): Float =
    if (landscape) (availableHeightDp - reservedBelowDp).coerceAtLeast(0f) else (availableHeightDp * 0.5f).coerceAtLeast(0f)

private const val ENROL_CODE_MAX_SP = 72f
private const val MONOSPACE_ADVANCE_EM = 0.65f // real monospace digits are about 0.6 em; 8% margin

/**
 * Font size (sp) for the enrolment code so `chars` monospace digits always fit in `availableWidthDp`
 * at the user's `fontScale` (sp are multiplied by it). At most 72 sp.
 */
fun enrolCodeFontSizeSp(availableWidthDp: Float, fontScale: Float, chars: Int = 6): Float {
    val scale = fontScale.coerceAtLeast(0.1f)
    val fit = availableWidthDp / (chars * MONOSPACE_ADVANCE_EM * scale)
    return minOf(fit, ENROL_CODE_MAX_SP).coerceAtLeast(1f) // no floor above 1: a digit is never clipped
}
