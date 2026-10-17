package inc.pazuzu.imzo

import java.security.ProviderException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class KeyCreationTest {
    @Test
    fun strongBoxIsTriedFirstAndUsedWhenItWorks() {
        val tried = mutableListOf<Boolean>()
        val key = generateWithFallback({ tried += it; "sb-key" }, { error("not asked") })
        assertEquals("sb-key", key)
        assertEquals(listOf(true), tried)
    }

    @Test
    fun anyStrongBoxFailureFallsBackToTheTeeOnce() {
        val tried = mutableListOf<Boolean>()
        val key = generateWithFallback(
            { strongBox ->
                tried += strongBox
                if (strongBox) throw ProviderException("StrongBox keymaster is broken") else "tee-key"
            },
            { error("not asked") },
        )
        assertEquals("tee-key", key)
        assertEquals(listOf(true, false), tried)
    }

    @Test
    fun bothFailingWithAScreenLockIsAKeystoreFailureWithTheRealCause() {
        val sb = ProviderException("strongbox says no")
        val tee = java.security.InvalidAlgorithmParameterException("tee says no")
        val ex = assertThrows(KeystoreFailure::class.java) {
            generateWithFallback<String>({ if (it) throw sb else throw tee }, { true })
        }
        assertSame(tee, ex.cause)
        assertTrue(ex.message, ex.message!!.contains("tee says no"))
        assertTrue(tee.suppressed.contains(sb))
    }

    @Test
    fun bothFailingWithoutAScreenLockIsScreenLockRequired() {
        val ex = assertThrows(ScreenLockRequired::class.java) {
            generateWithFallback<String>({ throw ProviderException("no lock") }, { false })
        }
        assertEquals("no lock", ex.cause?.message)
    }

    @Test
    fun theSecureCheckIsOnlyMadeWhenBothAttemptsFailed() {
        var asked = 0
        generateWithFallback({ it.toString() }, { asked++; false })
        generateWithFallback({ if (it) throw ProviderException("x") else "ok" }, { asked++; false })
        assertEquals(0, asked)
    }

    @Test
    fun keyProblemsAreNotConfusedWithEachOther() {
        assertFalse(ScreenLockRequired("a") is KeystoreFailure)
        assertFalse(KeystoreFailure("a") is ScreenLockRequired)
        assertFalse(ScreenLockRequired("a") is DeviceKeyLost)
    }
}
