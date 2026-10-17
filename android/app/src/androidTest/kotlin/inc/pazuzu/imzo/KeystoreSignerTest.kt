package inc.pazuzu.imzo

import android.os.Build
import android.security.keystore.KeyInfo
import android.security.keystore.KeyProperties
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import java.security.KeyFactory
import java.security.KeyStore
import java.security.PrivateKey
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Only reads the public key / key metadata, so no biometric prompt and no Activity are needed. */
class KeystoreSignerTest {
    private fun signer() = KeystoreSigner(ApplicationProvider.getApplicationContext()) { error("no UI in this test") }

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

    /**
     * What the demo promises: every signature needs a fresh biometric / screen-lock authentication
     * (no validity window), and the key lives in secure hardware. Logs the security level so the
     * device check can record StrongBox vs TEE.
     */
    @Test
    fun keyRequiresAuthenticationForEveryUseInSecureHardware() {
        signer().publicKey // make sure the key exists
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val key = ks.getKey(KeystoreSigner.ALIAS, null) as PrivateKey
        val info = KeyFactory.getInstance(key.algorithm, "AndroidKeyStore").getKeySpec(key, KeyInfo::class.java)

        assertTrue("user authentication required", info.isUserAuthenticationRequired)
        assertEquals("authentication needed for every use", 0, info.userAuthenticationValidityDurationSeconds)
        assertEquals(
            KeyProperties.AUTH_BIOMETRIC_STRONG or KeyProperties.AUTH_DEVICE_CREDENTIAL,
            info.userAuthenticationType,
        )
        assertTrue("invalidated by biometric enrolment", info.isInvalidatedByBiometricEnrollment)
        if (Build.VERSION.SDK_INT >= 31) {
            assertNotEquals("key must not be software-backed", KeyProperties.SECURITY_LEVEL_SOFTWARE, info.securityLevel)
            Log.i("KeystoreSignerTest", "KeyInfo securityLevel=${info.securityLevel} (1=TEE, 2=StrongBox, 0=software)")
        } else {
            assertTrue("key must be inside secure hardware", info.isInsideSecureHardware)
        }
    }
}
