package inc.pazuzu.imzo

import java.math.BigInteger
import java.security.interfaces.ECPublicKey

/** 65-byte uncompressed P-256 point: 0x04 || X || Y, each coordinate left-padded to 32 bytes. */
internal fun uncompressedPoint(key: ECPublicKey): ByteArray {
    fun fixed(v: BigInteger): ByteArray {
        val raw = v.toByteArray().let { if (it.size > 1 && it[0].toInt() == 0) it.copyOfRange(1, it.size) else it }
        check(raw.size <= 32) { "coordinate too long" }
        return ByteArray(32 - raw.size) + raw
    }
    return byteArrayOf(0x04) + fixed(key.w.affineX) + fixed(key.w.affineY)
}
