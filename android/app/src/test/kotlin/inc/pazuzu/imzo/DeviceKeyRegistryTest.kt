package inc.pazuzu.imzo

import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.spec.ECGenParameterSpec
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class DeviceKeyRegistryTest {
    private val ecKey = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair().private

    private fun pub(fill: Int) = ByteArray(65) { if (it == 0) 0x04 else fill.toByte() }

    private class FakeBackend(var pub: ByteArray?, val priv: PrivateKey?, val created: ByteArray) : DeviceKeyBackend {
        var creates = 0
        override fun existingPublicKey() = pub
        override fun privateKey() = priv
        override fun create(): ByteArray {
            creates++
            pub = created
            return created
        }
    }

    private class FakeMarker(var value: String? = null, var failCommit: Boolean = false) : KeyMarkerStore {
        override fun get() = value
        override fun commit(value: String): Boolean {
            if (failCommit) return false
            this.value = value
            return true
        }
    }

    @Test
    fun firstRunCreatesKeyAndWritesMarker() {
        val b = FakeBackend(null, ecKey, pub(1))
        val m = FakeMarker()
        assertArrayEquals(pub(1), DeviceKeyRegistry(b, m).publicKey())
        assertEquals(1, b.creates)
        assertNotNull(m.value)
        // later runs find the same key and do not create again
        assertArrayEquals(pub(1), DeviceKeyRegistry(b, m).publicKey())
        assertEquals(1, b.creates)
    }

    @Test
    fun missingKeyWithMarkerThrowsAndDoesNotCreate() {
        val m = FakeMarker(DeviceKeyRegistry.markerFor(pub(1)))
        val b = FakeBackend(null, null, pub(2))
        assertThrows(DeviceKeyLost::class.java) { DeviceKeyRegistry(b, m).publicKey() }
        assertEquals(0, b.creates)
        assertNull(b.pub)
    }

    @Test
    fun presentKeyWithMarkerMismatchThrows() {
        val m = FakeMarker(DeviceKeyRegistry.markerFor(pub(1)))
        val b = FakeBackend(pub(2), ecKey, pub(3))
        assertThrows(DeviceKeyLost::class.java) { DeviceKeyRegistry(b, m).publicKey() }
        assertEquals(0, b.creates)
    }

    @Test
    fun presentKeyWithoutMarkerIsAdopted() { // crashed between creating the key and writing the marker
        val b = FakeBackend(pub(1), ecKey, pub(9))
        val m = FakeMarker()
        assertArrayEquals(pub(1), DeviceKeyRegistry(b, m).publicKey())
        assertEquals(DeviceKeyRegistry.markerFor(pub(1)), m.value)
        assertEquals(0, b.creates)
    }

    @Test
    fun markerCommitFailureThrows() {
        val b = FakeBackend(null, ecKey, pub(1))
        assertThrows(IllegalStateException::class.java) { DeviceKeyRegistry(b, FakeMarker(failCommit = true)).publicKey() }
    }

    @Test
    fun signWithMissingKeyThrowsDeviceKeyLostAndNeverCreates() {
        val b = FakeBackend(null, null, pub(2))
        val ex = assertThrows(DeviceKeyLost::class.java) { DeviceKeyRegistry(b, FakeMarker()).keyForSigning() }
        assertEquals("device key missing; enrol this device again", ex.message)
        assertEquals(0, b.creates)
        // also with a marker present
        val ex2 = assertThrows(DeviceKeyLost::class.java) {
            DeviceKeyRegistry(b, FakeMarker(DeviceKeyRegistry.markerFor(pub(1)))).keyForSigning()
        }
        assertEquals("device key missing; enrol this device again", ex2.message)
        assertEquals(0, b.creates)
    }

    @Test
    fun signWithMissingPrivateKeyHandleThrowsDeviceKeyLost() {
        val b = FakeBackend(pub(1), null, pub(2))
        assertThrows(DeviceKeyLost::class.java) {
            DeviceKeyRegistry(b, FakeMarker(DeviceKeyRegistry.markerFor(pub(1)))).keyForSigning()
        }
    }

    @Test
    fun signWithReplacedKeyThrowsDeviceKeyLost() {
        val b = FakeBackend(pub(2), ecKey, pub(3))
        assertThrows(DeviceKeyLost::class.java) {
            DeviceKeyRegistry(b, FakeMarker(DeviceKeyRegistry.markerFor(pub(1)))).keyForSigning()
        }
    }

    @Test
    fun signWithMatchingKeyReturnsIt() {
        val b = FakeBackend(pub(1), ecKey, pub(3))
        assertEquals(ecKey, DeviceKeyRegistry(b, FakeMarker(DeviceKeyRegistry.markerFor(pub(1)))).keyForSigning())
    }
}
