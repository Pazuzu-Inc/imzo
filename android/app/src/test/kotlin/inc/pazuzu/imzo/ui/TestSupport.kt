package inc.pazuzu.imzo.ui

import inc.pazuzu.imzo.protocol.Approver
import inc.pazuzu.imzo.protocol.Cbor
import inc.pazuzu.imzo.protocol.CounterStore
import inc.pazuzu.imzo.protocol.Item
import inc.pazuzu.imzo.protocol.Review
import inc.pazuzu.imzo.protocol.Signer
import inc.pazuzu.imzo.protocol.derFromRaw
import inc.pazuzu.imzo.protocol.rawFromDer
import java.math.BigInteger
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPublicKeySpec

/**
 * Test-only [Signer]: a software P-256 key. [beforeSign] runs first on every sign (throw to simulate a
 * cancelled biometric, or suspend on a gate to keep a request in flight); [publicKeyError] makes
 * `publicKey` throw like a Keystore in an odd state.
 */
class TestSigner : Signer {
    private val privateKey: PrivateKey
    private val point: ByteArray
    var beforeSign: suspend () -> Unit = {}
    var publicKeyError: Throwable? = null
    var signCalls = 0
        private set

    init {
        val pair = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
        privateKey = pair.private
        val w = (pair.public as ECPublicKey).w
        fun be32(v: BigInteger): ByteArray {
            val b = v.toByteArray().dropWhile { it.toInt() == 0 }.toByteArray()
            return ByteArray(32 - b.size) + b
        }
        point = byteArrayOf(4) + be32(w.affineX) + be32(w.affineY)
    }

    override val publicKey: ByteArray get() = publicKeyError?.let { throw it } ?: point

    val key: ByteArray get() = point

    override suspend fun sign(message: ByteArray): ByteArray {
        beforeSign()
        signCalls++
        val sig = Signature.getInstance("SHA256withECDSA")
        sig.initSign(privateKey)
        sig.update(message)
        return rawFromDer(sig.sign())
    }
}

/** In-memory [CounterStore]. */
class TestCounter : CounterStore {
    var value = 0L
        private set

    override fun next(): Long = ++value
}

/** Verifies a raw r||s signature against an uncompressed P-256 point. */
fun verifyRaw(publicKey: ByteArray, message: ByteArray, raw: ByteArray): Boolean {
    val params = (KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }
        .generateKeyPair().public as ECPublicKey).params
    val x = BigInteger(1, publicKey.copyOfRange(1, 33))
    val y = BigInteger(1, publicKey.copyOfRange(33, 65))
    val key = KeyFactory.getInstance("EC").generatePublic(ECPublicKeySpec(ECPoint(x, y), params))
    return Signature.getInstance("SHA256withECDSA").run {
        initVerify(key)
        update(message)
        verify(derFromRaw(raw))
    }
}

/** Same field layout as `imzo_ref.build_request`; `total` is computed from the items unless overridden. */
fun buildRequest(
    dev: ByteArray,
    items: List<Item>,
    op: String = "payroll",
    ref: String = "Payroll October 2026",
    ap: Pair<Int, Int> = 1 to 1,
    rid: ByteArray = ByteArray(16) { it.toByte() },
    nonce: ByteArray = ByteArray(16) { (32 + it).toByte() },
    total: Long = items.sumOf { it.amount },
): ByteArray = Cbor.dumps(
    linkedMapOf<String, Any>(
        "v" to 1L, "rid" to rid, "dev" to dev, "nonce" to nonce, "op" to op, "cur" to "UZS",
        "ref" to ref, "items" to items.map { listOf(it.name, it.account, it.amount) },
        "total" to total, "ap" to listOf(ap.first.toLong(), ap.second.toLong()),
    ),
)

/** A genuine [Review] (the constructor is internal to :protocol): the request is built and parsed by the real [Approver]. */
fun reviewOf(items: List<Item>, ap: Pair<Int, Int> = 1 to 1, ref: String = "Payroll October 2026"): Review {
    val approver = Approver(TestSigner(), TestCounter())
    return approver.review(buildRequest(dev = approver.id, items = items, ap = ap, ref = ref))
}

val sampleItems = listOf(
    Item("Aziza Karimova", "86000000000000000001", 450_000_000L),
    Item("Bobur Tursunov", "8600123412341234", 900_050_000L),
    Item("Dilnoza Yusupova", "86000000000000000003", 123_456_789L),
)
