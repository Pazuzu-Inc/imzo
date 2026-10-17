package inc.pazuzu.imzo

import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPublicKeySpec
import java.security.KeyFactory
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class PublicKeyEncodingTest {
    private fun newKey(): ECPublicKey =
        KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }
            .generateKeyPair().public as ECPublicKey

    @Test
    fun alwaysSixtyFiveBytesWithPrefix() {
        repeat(200) {
            val p = uncompressedPoint(newKey())
            assertEquals(65, p.size)
            assertEquals(0x04.toByte(), p[0])
        }
    }

    @Test
    fun smallCoordinatesArePaddedOnTheLeft() {
        val template = newKey()
        val key = KeyFactory.getInstance("EC")
            .generatePublic(ECPublicKeySpec(ECPoint(BigInteger.valueOf(5), BigInteger.valueOf(0x1ff)), template.params)) as ECPublicKey
        val p = uncompressedPoint(key)
        assertEquals(65, p.size)
        assertArrayEquals(ByteArray(31) + byteArrayOf(5), p.copyOfRange(1, 33))
        assertArrayEquals(ByteArray(30) + byteArrayOf(1, 0xff.toByte()), p.copyOfRange(33, 65))
    }
}
