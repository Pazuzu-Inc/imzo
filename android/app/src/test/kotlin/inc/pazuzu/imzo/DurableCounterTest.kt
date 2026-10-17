package inc.pazuzu.imzo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class DurableCounterTest {
    private class FakeKv(var failCommit: Boolean = false) : KeyValueStore {
        val durable = HashMap<String, Long>()
        override fun getLong(key: String, default: Long): Long = durable[key] ?: default
        override fun commitLong(key: String, value: Long): Boolean {
            if (failCommit) return false
            durable[key] = value
            return true
        }
    }

    @Test
    fun startsAtOneAndIncrements() {
        val c = DurableCounter(FakeKv())
        assertEquals(1L, c.next())
        assertEquals(2L, c.next())
    }

    @Test
    fun valueIsDurableBeforeReturn() {
        val kv = FakeKv()
        val c = DurableCounter(kv)
        val n = c.next()
        assertEquals(n, kv.durable[DurableCounter.KEY])
        // a fresh instance over the same storage continues, never repeats
        assertEquals(n + 1, DurableCounter(kv).next())
    }

    @Test
    fun failedCommitThrowsAndDoesNotAdvance() {
        val kv = FakeKv()
        val c = DurableCounter(kv)
        assertEquals(1L, c.next())
        kv.failCommit = true
        assertThrows(IllegalStateException::class.java) { c.next() }
        assertThrows(IllegalStateException::class.java) { c.next() }
        assertEquals(1L, kv.durable[DurableCounter.KEY])
        kv.failCommit = false
        // nothing was handed out for the failed attempts, so the next good value is 2
        assertEquals(2L, c.next())
    }

    @Test
    fun failedCommitOnFirstUseLeavesStorageEmpty() {
        val kv = FakeKv(failCommit = true)
        assertThrows(IllegalStateException::class.java) { DurableCounter(kv).next() }
        assertEquals(null, kv.durable[DurableCounter.KEY])
    }
}
