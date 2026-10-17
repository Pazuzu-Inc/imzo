package inc.pazuzu.imzo

/** Base type for every way [KeystoreSigner.sign] can end without a signature. */
sealed class SigningException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** The user dismissed the prompt, or biometrics are locked out. Not an error to show in red. */
class SigningCancelled(message: String) : SigningException(message)

/** Anything else: key missing or invalidated, hardware error, malformed signature. */
class SigningFailed(message: String, cause: Throwable? = null) : SigningException(message, cause)
