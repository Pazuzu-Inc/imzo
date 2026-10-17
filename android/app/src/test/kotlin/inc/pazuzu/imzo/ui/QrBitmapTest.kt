package inc.pazuzu.imzo.ui

import com.google.zxing.BinaryBitmap
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.BitMatrix
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class QrBitmapTest {
    private fun frame(totalChars: Int): String {
        val prefix = "IMQ1.rq.00000001.1.5."
        val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"
        return prefix + (0 until totalChars - prefix.length).map { alphabet[(it * 7 + it / 3) % alphabet.length] }.joinToString("")
    }

    /** Renders the matrix to a luminance image (black module = 0, white = 255) and decodes it with ZXing. */
    private fun decode(m: BitMatrix): String {
        val scale = 4 // give the binarizer a realistic, non-1px image
        val w = m.width * scale
        val h = m.height * scale
        val pixels = IntArray(w * h) { i ->
            val x = (i % w) / scale
            val y = (i / w) / scale
            if (m.get(x, y)) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
        }
        val bitmap = BinaryBitmap(HybridBinarizer(RGBLuminanceSource(w, h, pixels)))
        return QRCodeReader().decode(bitmap).text
    }

    @Test
    fun `a 270 character frame round-trips at every ECC level`() {
        val text = frame(270)
        assertEquals(270, text.length)
        for (ecc in listOf(ErrorCorrectionLevel.L, ErrorCorrectionLevel.M, ErrorCorrectionLevel.Q)) {
            assertEquals("ecc $ecc", text, decode(qrMatrix(text, ecc)))
        }
    }

    @Test
    fun `the matrix has a quiet zone of 4 modules on every side`() {
        val m = qrMatrix(frame(100))
        for (k in 0 until 4) {
            for (j in 0 until m.width) {
                assertFalse("top row $k col $j", m.get(j, k))
                assertFalse("bottom row $k col $j", m.get(j, m.height - 1 - k))
                assertFalse("left col $k row $j", m.get(k, j))
                assertFalse("right col $k row $j", m.get(m.width - 1 - k, j))
            }
        }
        // ...and the code itself starts right after it: the finder pattern's corner module is dark.
        assertTrue(m.get(4, 4))
        assertEquals(m.width, m.height)
    }

    @Test
    fun `a typical 290 character frame stays within QR version 13`() {
        val m = qrMatrix(frame(290))
        assertTrue("width ${m.width}", m.width <= 77) // version 13 = 69 modules + 2*4 quiet zone
    }

    @Test
    fun `module scale is a floor of at least 1`() {
        assertEquals(3, moduleScale(sizePx = 300, matrixWidth = 77)) // 3.89 floors
        assertEquals(4, moduleScale(sizePx = 308, matrixWidth = 77))
        assertEquals(1, moduleScale(sizePx = 10, matrixWidth = 77))
        assertEquals(1, moduleScale(sizePx = 77, matrixWidth = 77))
    }
}
