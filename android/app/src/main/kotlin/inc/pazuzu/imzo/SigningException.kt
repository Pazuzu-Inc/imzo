package inc.pazuzu.imzo

/** Base type for every way [KeystoreSigner.sign] can end without a signature. */
sealed class SigningException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** The user dismissed the prompt. Not an error to show in red. */
class SigningCancelled(message: String) : SigningException(message)

/** Anything else: key missing or invalidated, hardware error, malformed signature. */
class SigningFailed(message: String, cause: Throwable? = null) : SigningException(message, cause)

/** Biometrics are locked out after too many wrong attempts: the user must wait or use the screen lock. Shown as a notice. */
class SigningLockedOut(message: String) : SigningException(message)

/** Title and subtitle of the system authentication prompt for the signature about to be made. */
data class PromptText(val title: String, val subtitle: String)
