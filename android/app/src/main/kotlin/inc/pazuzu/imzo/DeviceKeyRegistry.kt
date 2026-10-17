package inc.pazuzu.imzo

import java.security.MessageDigest
import java.security.PrivateKey

/** What the registry needs from the platform key store. */
interface DeviceKeyBackend {
    /** 65-byte uncompressed public key of the device key, or null if no key exists under the alias. */
    fun existingPublicKey(): ByteArray?

    /** The private key handle, or null if missing. */
    fun privateKey(): PrivateKey?

    /** Generates the key and returns its public key. Only [DeviceKeyRegistry.publicKey] may call this. */
    fun create(): ByteArray
}

/** Durable record that a device key was created (SHA-256 of its public key, hex). */
interface KeyMarkerStore {
    fun get(): String?

    /** Synchronous; false if it could not be persisted. */
    fun commit(value: String): Boolean
}

/** The device key is gone or was replaced: the device identity would change, so this device must be enrolled again. */
class DeviceKeyLost(message: String) : IllegalStateException(message)

/** No key could be created and the device has no secure screen lock: the user must set one. */
class ScreenLockRequired(message: String, cause: Throwable? = null) : IllegalStateException(message, cause)

/** No key could be created although a screen lock is set: a real device error, with the platform's message as cause. */
class KeystoreFailure(message: String, cause: Throwable? = null) : IllegalStateException(message, cause)

/**
 * Key creation policy, free of Android types so the JVM can test it. [generate] makes the key with
 * `strongBox` true or false (always user-authentication-required: a failure never relaxes that).
 * StrongBox is tried first; ANY failure of that attempt retries once on the TEE with the same spec.
 * If both fail, a missing screen lock ([isDeviceSecure] false) is [ScreenLockRequired], anything
 * else is [KeystoreFailure] carrying the TEE error as cause and the StrongBox error as suppressed.
 */
internal fun <T> generateWithFallback(generate: (strongBox: Boolean) -> T, isDeviceSecure: () -> Boolean): T {
    val strongBoxError = try {
        return generate(true)
    } catch (e: Exception) {
        e
    }
    val teeError = try {
        return generate(false)
    } catch (e: Exception) {
        e.addSuppressed(strongBoxError)
        e
    }
    if (!isDeviceSecure()) throw ScreenLockRequired("Set a screen lock to use Imzo", teeError)
    throw KeystoreFailure("Cannot create the device key: ${teeError.message ?: teeError.javaClass.simpleName}", teeError)
}

/**
 * Guards the device identity. A key is created only on the very first run (no key, no marker). A
 * missing key with a marker (e.g. the keystore dropped it when the screen lock was removed) or a
 * key that does not match the marker is an error, never a silent new identity.
 */
class DeviceKeyRegistry(private val backend: DeviceKeyBackend, private val marker: KeyMarkerStore) {
    /** Enrolment path: the public key, creating the key only on first run. */
    fun publicKey(): ByteArray {
        val existing = backend.existingPublicKey()
        val recorded = marker.get()
        if (existing == null) {
            if (recorded != null) throw DeviceKeyLost(LOST_MESSAGE)
            val created = backend.create()
            record(created)
            return created
        }
        if (recorded == null) {
            record(existing) // key exists but the marker write was lost: adopt it
        } else if (recorded != markerFor(existing)) {
            throw DeviceKeyLost("the device key was replaced; enrol this device again")
        }
        return existing
    }

    /** Signing path: never creates a key. A missing or replaced key is [DeviceKeyLost]: retrying cannot help. */
    fun keyForSigning(): PrivateKey {
        val existing = backend.existingPublicKey() ?: throw DeviceKeyLost(LOST_MESSAGE)
        val recorded = marker.get()
        if (recorded != null && recorded != markerFor(existing)) {
            throw DeviceKeyLost("the device key was replaced; enrol this device again")
        }
        return backend.privateKey() ?: throw DeviceKeyLost(LOST_MESSAGE)
    }

    private fun record(pub: ByteArray) {
        check(marker.commit(markerFor(pub))) { "could not persist the device key marker" }
    }

    companion object {
        const val LOST_MESSAGE = "device key missing; enrol this device again"

        fun markerFor(publicKey: ByteArray): String =
            MessageDigest.getInstance("SHA-256").digest(publicKey).joinToString("") { "%02x".format(it) }
    }
}
