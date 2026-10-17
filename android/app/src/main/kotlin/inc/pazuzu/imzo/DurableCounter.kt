package inc.pazuzu.imzo

import inc.pazuzu.imzo.protocol.CounterStore

/** Minimal storage the counter needs; `commitLong` must return true only when the value is durable. */
interface KeyValueStore {
    fun getLong(key: String, default: Long): Long

    /** Synchronously persists `value`; false if it could not be written. */
    fun commitLong(key: String, value: Long): Boolean
}

/**
 * Replay counter that never hands out a value it has not durably stored: it computes `current + 1`,
 * commits it, and only then returns it. If the commit fails nothing is returned and the stored
 * value is unchanged. The first value is 1.
 */
class DurableCounter(private val store: KeyValueStore) : CounterStore {
    override fun next(): Long = synchronized(LOCK) { // process-wide: two instances over one file must not race
        val n = store.getLong(KEY, 0L) + 1
        check(store.commitLong(KEY, n)) { "could not persist the response counter" }
        n
    }

    companion object {
        const val KEY = "ctr"
        private val LOCK = Any()
    }
}
