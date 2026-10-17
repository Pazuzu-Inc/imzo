package inc.pazuzu.imzo.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DebouncerTest {
    private var now = 1_000L
    private val debouncer = Debouncer(windowMs = 300) { now }

    @Test
    fun `first sighting passes`() {
        assertTrue(debouncer.shouldEmit("a"))
    }

    @Test
    fun `same string within the window is suppressed`() {
        assertTrue(debouncer.shouldEmit("a"))
        now += 299
        assertFalse(debouncer.shouldEmit("a"))
    }

    @Test
    fun `a different string passes immediately`() {
        assertTrue(debouncer.shouldEmit("a"))
        now += 10
        assertTrue(debouncer.shouldEmit("b"))
    }

    @Test
    fun `same string after the window passes again`() {
        assertTrue(debouncer.shouldEmit("a"))
        now += 300
        assertTrue(debouncer.shouldEmit("a"))
    }

    @Test
    fun `each string has its own window so alternating frames are still throttled`() {
        assertTrue(debouncer.shouldEmit("a"))
        now += 50
        assertTrue(debouncer.shouldEmit("b"))
        now += 50
        assertFalse(debouncer.shouldEmit("a"))
        assertFalse(debouncer.shouldEmit("b"))
    }

    @Test
    fun `a suppressed sighting does not extend the window`() {
        assertTrue(debouncer.shouldEmit("a"))
        now += 200
        assertFalse(debouncer.shouldEmit("a"))
        now += 100 // 300 ms after the emitted one
        assertTrue(debouncer.shouldEmit("a"))
    }
}
