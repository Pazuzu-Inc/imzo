package inc.pazuzu.imzo

import java.security.KeyStore
import java.security.PrivateKey
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Only reads the public key / key metadata, so no biometric prompt and no Activity are needed. */
class KeystoreSignerTest {
    private fun signer() = KeystoreSigner { error("no UI in this test") }

    @Test
    fun publicKeyIsStableAndUncompressed() {
        val first = signer().publicKey
        assertEquals(65, first.size)
        assertEquals(0x04.toByte(), first[0])
        assertArrayEquals(first, signer().publicKey) // a new signer finds the same key
    }

    @Test
    fun keyIsNotExportable() {
        signer().publicKey // make sure the key exists
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val key = ks.getKey(KeystoreSigner.ALIAS, null) as PrivateKey
        assertNull(key.encoded)
    }
}
