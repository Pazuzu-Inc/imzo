package inc.pazuzu.imzo

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import android.security.keystore.StrongBoxUnavailableException
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import inc.pazuzu.imzo.protocol.Signer
import inc.pazuzu.imzo.protocol.rawFromDer
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

/** Base type for every way [KeystoreSigner.sign] can end without a signature. */
sealed class SigningException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** The user dismissed the prompt, or biometrics are locked out. Not an error to show in red. */
class SigningCancelled(message: String) : SigningException(message)

/** Anything else: key invalidated, hardware error, malformed signature. */
class SigningFailed(message: String, cause: Throwable? = null) : SigningException(message, cause)

/**
 * The approver's P-256 key, generated inside the Android Keystore (StrongBox when available, else
 * the TEE). The key is non-exportable and needs a fresh biometric / device-credential
 * authentication for every signature: [sign] shows the system prompt and signs through the
 * authenticated `CryptoObject`.
 *
 * [activityProvider] is only invoked from [sign]; reading [publicKey] needs no UI or authentication.
 */
class KeystoreSigner(private val activityProvider: () -> FragmentActivity) : Signer {
    constructor(activity: FragmentActivity) : this({ activity })

    override val publicKey: ByteArray by lazy { uncompressedPoint(loadOrCreatePublicKey()) }

    override suspend fun sign(message: ByteArray): ByteArray {
        val signature = Signature.getInstance(ALGORITHM)
        try {
            signature.initSign(privateKey())
        } catch (e: KeyPermanentlyInvalidatedException) {
            throw SigningFailed("The device key was invalidated (biometrics changed); this device must be enrolled again", e)
        } catch (e: java.security.GeneralSecurityException) {
            throw SigningFailed("The device key is not usable: ${e.message}", e)
        }
        val authenticated = withContext(Dispatchers.Main) { authenticate(activityProvider(), signature) }
        try {
            authenticated.update(message)
            return rawFromDer(authenticated.sign())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw SigningFailed("Signing failed: ${e.message}", e)
        }
    }

    private fun privateKey(): PrivateKey {
        val key = keyStore().getKey(ALIAS, null) as? PrivateKey
        if (key != null) return key
        loadOrCreatePublicKey() // key was missing: create it
        return keyStore().getKey(ALIAS, null) as? PrivateKey
            ?: throw SigningFailed("The device key is missing")
    }

    private fun keyStore(): KeyStore = KeyStore.getInstance(PROVIDER).apply { load(null) }

    private fun loadOrCreatePublicKey(): ECPublicKey {
        (keyStore().getCertificate(ALIAS)?.publicKey as? ECPublicKey)?.let { return it }
        return try {
            generateKeyPair(strongBox = true)
        } catch (_: StrongBoxUnavailableException) {
            generateKeyPair(strongBox = false)
        }
    }

    private fun generateKeyPair(strongBox: Boolean): ECPublicKey {
        val spec = KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_SIGN)
            .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
            .setDigests(KeyProperties.DIGEST_SHA256)
            .setUserAuthenticationRequired(true)
            // timeout 0 = authentication is required for every single use, bound to the CryptoObject
            .setUserAuthenticationParameters(0, KeyProperties.AUTH_BIOMETRIC_STRONG or KeyProperties.AUTH_DEVICE_CREDENTIAL)
            .setInvalidatedByBiometricEnrollment(true)
            .setIsStrongBoxBacked(strongBox)
            .build()
        try {
            val generator = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, PROVIDER)
            generator.initialize(spec)
            return generator.generateKeyPair().public as ECPublicKey
        } catch (e: StrongBoxUnavailableException) {
            throw e
        } catch (e: java.security.GeneralSecurityException) {
            throw IllegalStateException("Cannot create the device key (is a screen lock set?): ${e.message}", e)
        } catch (e: java.security.ProviderException) {
            throw IllegalStateException("Cannot create the device key (is a screen lock set?): ${e.message}", e)
        }
    }

    private suspend fun authenticate(activity: FragmentActivity, signature: Signature): Signature =
        suspendCancellableCoroutine { cont ->
            val callback = object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    val authed = result.cryptoObject?.signature
                    if (authed == null) {
                        cont.resumeWithException(SigningFailed("No authenticated signature returned"))
                    } else {
                        cont.resume(authed)
                    }
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    val text = errString.toString()
                    cont.resumeWithException(
                        when (errorCode) {
                            BiometricPrompt.ERROR_USER_CANCELED,
                            BiometricPrompt.ERROR_CANCELED,
                            BiometricPrompt.ERROR_NEGATIVE_BUTTON,
                            BiometricPrompt.ERROR_LOCKOUT,
                            BiometricPrompt.ERROR_LOCKOUT_PERMANENT,
                            -> SigningCancelled(text)
                            else -> SigningFailed(text)
                        },
                    )
                }
                // onAuthenticationFailed (a bad fingerprint) is not terminal: the prompt stays up.
            }
            val prompt = BiometricPrompt(activity, ContextCompat.getMainExecutor(activity), callback)
            val info = BiometricPrompt.PromptInfo.Builder()
                .setTitle("Approve this order")
                .setAllowedAuthenticators(BIOMETRIC_STRONG or DEVICE_CREDENTIAL)
                // no negative button: the API forbids one when DEVICE_CREDENTIAL is allowed
                .build()
            cont.invokeOnCancellation { activity.runOnUiThread { prompt.cancelAuthentication() } }
            prompt.authenticate(info, BiometricPrompt.CryptoObject(signature))
        }

    companion object {
        const val ALIAS = "imzo-device-key"
        private const val PROVIDER = "AndroidKeyStore"
        private const val ALGORITHM = "SHA256withECDSA"
    }
}
