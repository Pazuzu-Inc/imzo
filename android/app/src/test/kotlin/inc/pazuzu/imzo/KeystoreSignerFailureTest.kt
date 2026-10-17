package inc.pazuzu.imzo

import android.security.keystore.KeyPermanentlyInvalidatedException
import java.security.InvalidKeyException
import org.junit.Assert.assertTrue
import org.junit.Test

class KeystoreSignerFailureTest {
    @Test
    fun keyInvalidatedByABiometricChangeIsDeviceKeyLost() {
        val e = initSignFailure(KeyPermanentlyInvalidatedException())
        assertTrue(e.toString(), e is DeviceKeyLost)
    }

    @Test
    fun otherKeyProblemsStayARetryableSigningFailure() {
        assertTrue(initSignFailure(InvalidKeyException("boom")) is SigningFailed)
    }
}
