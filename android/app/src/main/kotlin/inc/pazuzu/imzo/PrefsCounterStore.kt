package inc.pazuzu.imzo

import android.content.Context
import android.content.SharedPreferences
import inc.pazuzu.imzo.protocol.CounterStore

/** [KeyValueStore] over SharedPreferences using `commit()` (synchronous, reports failure), never `apply()`. */
internal class PrefsKeyValueStore(private val prefs: SharedPreferences) : KeyValueStore {
    override fun getLong(key: String, default: Long): Long = prefs.getLong(key, default)

    override fun commitLong(key: String, value: Long): Boolean = prefs.edit().putLong(key, value).commit()
}

/** Durable response counter in its own prefs file. Throws [IllegalStateException] if it cannot persist. */
class PrefsCounterStore(prefs: SharedPreferences) : CounterStore by DurableCounter(PrefsKeyValueStore(prefs)) {
    /** [file] is only overridden by tests, so they never touch the real counter. */
    constructor(context: Context, file: String = FILE) : this(
        context.applicationContext.getSharedPreferences(file, Context.MODE_PRIVATE),
    )

    companion object {
        const val FILE = "imzo_counter"
    }
}
