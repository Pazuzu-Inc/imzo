package inc.pazuzu.imzo

import android.content.Context
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test

class PrefsCounterStoreTest {
    // A dedicated file: the tests must never reset the app's real replay counter.
    private val file = "imzo_counter_test"
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Before
    fun clean() {
        context.deleteSharedPreferences(file)
    }

    @Test
    fun persistsBeforeReturning() {
        assertEquals(1L, PrefsCounterStore(context, file).next())
        val first = PrefsCounterStore(context, file) // new instance, same storage
        assertEquals(2L, first.next())
        assertEquals(3L, PrefsCounterStore(context, file).next())
    }

    @Test
    fun valueSurvivesAFreshPreferencesObject() {
        val store = PrefsCounterStore(context, file)
        store.next()
        store.next()
        // read the file back through a separate SharedPreferences handle
        assertEquals(2L, context.getSharedPreferences(file, Context.MODE_PRIVATE).getLong(DurableCounter.KEY, -1))
    }

    @Test
    fun failedCommitThrows() {
        val store = PrefsCounterStore(ReadOnlyPrefs())
        assertThrows(IllegalStateException::class.java) { store.next() }
    }

    /** SharedPreferences whose editor can never commit. */
    private class ReadOnlyPrefs : SharedPreferences {
        override fun getAll(): MutableMap<String, *> = mutableMapOf<String, Any>()
        override fun getString(key: String?, defValue: String?) = defValue
        override fun getStringSet(key: String?, defValues: MutableSet<String>?) = defValues
        override fun getInt(key: String?, defValue: Int) = defValue
        override fun getLong(key: String?, defValue: Long) = defValue
        override fun getFloat(key: String?, defValue: Float) = defValue
        override fun getBoolean(key: String?, defValue: Boolean) = defValue
        override fun contains(key: String?) = false
        override fun registerOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener?) {}
        override fun unregisterOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener?) {}
        override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor {
            override fun putString(key: String?, value: String?) = this
            override fun putStringSet(key: String?, values: MutableSet<String>?) = this
            override fun putInt(key: String?, value: Int) = this
            override fun putLong(key: String?, value: Long) = this
            override fun putFloat(key: String?, value: Float) = this
            override fun putBoolean(key: String?, value: Boolean) = this
            override fun remove(key: String?) = this
            override fun clear() = this
            override fun commit() = false
            override fun apply() {}
        }
    }
}
