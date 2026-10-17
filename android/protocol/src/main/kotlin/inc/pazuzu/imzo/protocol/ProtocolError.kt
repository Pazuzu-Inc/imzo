package inc.pazuzu.imzo.protocol

/** A short, device-screen-friendly failure code such as `BAD FORMAT`; same codes as `imzo_ref.py`. */
class ProtocolError(val code: String, val detail: String = "") :
    Exception(if (detail.isEmpty()) code else "$code: $detail")
