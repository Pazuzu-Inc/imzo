package inc.pazuzu.imzo.protocol

import java.math.BigInteger
import java.security.AlgorithmParameters
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.PublicKey
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPrivateKeySpec
import java.security.spec.ECPublicKeySpec

/** Test-only software [Signer]: JCA ECDSA P-256, DER converted to raw r||s like the Android one will. */
class SoftSigner(private val privateKey: PrivateKey, override val publicKey: ByteArray) : Signer {
    var signCalls = 0
        private set

    override suspend fun sign(message: ByteArray): ByteArray {
        signCalls++
        val sig = Signature.getInstance("SHA256withECDSA")
        sig.initSign(privateKey)
        sig.update(message)
        return rawFromDer(sig.sign())
    }

    companion object {
        val params: ECParameterSpec by lazy {
            AlgorithmParameters.getInstance("EC").apply { init(ECGenParameterSpec("secp256r1")) }
                .getParameterSpec(ECParameterSpec::class.java)
        }

        fun generate(): SoftSigner {
            val pair = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
            return SoftSigner(pair.private, uncompressed(pair.public as ECPublicKey))
        }

        /** Build from a P-256 scalar; the public point is computed here (JCA cannot derive it). */
        fun fromScalar(d: BigInteger): SoftSigner {
            val priv = KeyFactory.getInstance("EC").generatePrivate(ECPrivateKeySpec(d, params))
            val q = multiply(params.generator, d)
            return SoftSigner(priv, uncompressed(q))
        }

        /** The vector key: same derivation as `make_vectors` in the reference. */
        fun fromLabel(label: String): SoftSigner {
            val digest = java.security.MessageDigest.getInstance("SHA-256").digest(label.toByteArray())
            return fromScalar(BigInteger(1, digest).mod(params.order - BigInteger.ONE) + BigInteger.ONE)
        }

        private fun uncompressed(key: ECPublicKey) = uncompressed(key.w)

        private fun uncompressed(w: ECPoint): ByteArray {
            fun be32(v: BigInteger): ByteArray {
                val b = v.toByteArray().dropWhile { it.toInt() == 0 }.toByteArray()
                return ByteArray(32 - b.size) + b
            }
            return byteArrayOf(4) + be32(w.affineX) + be32(w.affineY)
        }

        // --- minimal affine double-and-add on secp256r1 (test helper only, not constant time)
        private fun multiply(g: ECPoint, k: BigInteger): ECPoint {
            val p = (params.curve.field as java.security.spec.ECFieldFp).p
            val a = params.curve.a
            fun add(x: ECPoint, y: ECPoint): ECPoint {
                if (x == ECPoint.POINT_INFINITY) return y
                if (y == ECPoint.POINT_INFINITY) return x
                val lambda = if (x.affineX == y.affineX) {
                    if ((x.affineY + y.affineY).mod(p).signum() == 0) return ECPoint.POINT_INFINITY
                    (x.affineX.pow(2) * BigInteger.valueOf(3) + a) * (x.affineY * BigInteger.TWO).modInverse(p)
                } else {
                    (y.affineY - x.affineY) * (y.affineX - x.affineX).modInverse(p)
                }.mod(p)
                val rx = (lambda.pow(2) - x.affineX - y.affineX).mod(p)
                val ry = (lambda * (x.affineX - rx) - x.affineY).mod(p)
                return ECPoint(rx, ry)
            }
            var result = ECPoint.POINT_INFINITY
            var addend = g
            var n = k
            while (n.signum() > 0) {
                if (n.testBit(0)) result = add(result, addend)
                addend = add(addend, addend)
                n = n.shiftRight(1)
            }
            return result
        }
    }
}

/** Test-only: JCA public key from a 65-byte uncompressed point. */
fun ecPublicKey(point: ByteArray): PublicKey {
    require(point.size == 65 && point[0] == 4.toByte())
    val x = BigInteger(1, point.copyOfRange(1, 33))
    val y = BigInteger(1, point.copyOfRange(33, 65))
    return KeyFactory.getInstance("EC").generatePublic(ECPublicKeySpec(ECPoint(x, y), SoftSigner.params))
}

/** Test-only: verify a raw r||s signature the way the server does (ECDSA P-256, SHA-256). */
fun verifyRaw(publicKey: ByteArray, message: ByteArray, raw: ByteArray): Boolean {
    val sig = Signature.getInstance("SHA256withECDSA")
    sig.initVerify(ecPublicKey(publicKey))
    sig.update(message)
    return sig.verify(derFromRaw(raw))
}

/** Test-only counter: persists nothing, hands out start+1, start+2, ... */
class MemoryCounter(start: Long = 0) : CounterStore {
    var value = start
        private set

    override fun next(): Long = ++value
}
