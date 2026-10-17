package inc.pazuzu.imzo.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class QrPlayerTest {
    @Test
    fun `next index wraps around`() {
        assertEquals(1, nextFrameIndex(0, 3))
        assertEquals(2, nextFrameIndex(1, 3))
        assertEquals(0, nextFrameIndex(2, 3))
    }

    @Test
    fun `a single frame stays on itself`() {
        assertEquals(0, nextFrameIndex(0, 1))
    }

    @Test
    fun `empty list yields zero instead of dividing by zero`() {
        assertEquals(0, nextFrameIndex(0, 0))
    }

    @Test
    fun `an out of range index is brought back into range`() {
        assertEquals(0, nextFrameIndex(7, 3)) // list shrank under the player
    }

    @Test
    fun `frame delay follows fps and never reaches zero`() {
        assertEquals(333L, frameDelayMs(3))
        assertEquals(1000L, frameDelayMs(1))
        assertEquals(1000L, frameDelayMs(0))
    }
}
