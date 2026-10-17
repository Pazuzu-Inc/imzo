package inc.pazuzu.imzo.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class QrFrameDecoderTest {
    private val text = "IMQ1.rq.00000001.1.5." + "AbC-_123".repeat(30)
    private val scale = 4
    private val matrix = qrMatrix(text)
    private val qrPx = matrix.width * scale

    // Image bigger than the code: QR at (offX, offY), white elsewhere; rowStride > width (camera padding).
    private val offX = 40
    private val offY = 24
    private val width = qrPx + 2 * offX
    private val height = qrPx + 2 * offY
    private val rowStride = width + 16

    private fun luma(): ByteArray {
        val data = ByteArray(rowStride * height) { 0xFF.toByte() }
        for (y in 0 until qrPx) for (x in 0 until qrPx) {
            if (matrix.get(x / scale, y / scale)) data[(offY + y) * rowStride + offX + x] = 0
        }
        return data
    }

    @Test
    fun `decodes a padded plane with a non-zero crop`() {
        val d = QrFrameDecoder()
        val got = d.decodeLuma(luma(), rowStride, width, height, offX - 8, offY - 8, qrPx + 16, qrPx + 16)
        assertEquals(text, got)
    }

    @Test
    fun `decodes with the full image as crop and reuses the reader`() {
        val d = QrFrameDecoder()
        assertEquals(text, d.decodeLuma(luma(), rowStride, width, height, 0, 0, width, height))
        assertEquals(text, d.decodeLuma(luma(), rowStride, width, height, 0, 0, width, height))
    }

    @Test
    fun `a crop that cuts off the code finds nothing`() {
        val d = QrFrameDecoder()
        assertNull(d.decodeLuma(luma(), rowStride, width, height, 0, 0, offX + 20, height))
    }

    @Test
    fun `odd dimensions return null instead of throwing`() {
        val d = QrFrameDecoder()
        val data = luma()
        assertNull(d.decodeLuma(data, rowStride, width, height, 0, 0, 0, 0)) // zero crop
        assertNull(d.decodeLuma(data, rowStride, width, height, width + 50, height + 50, 100, 100)) // outside
        assertNull(d.decodeLuma(data, rowStride, width, height, -30, -30, -5, 10)) // negative size
        assertNull(d.decodeLuma(data, rowStride, 0, height, 0, 0, 10, 10)) // zero width
        assertNull(d.decodeLuma(data, 0, width, height, 0, 0, 10, 10)) // zero stride
        assertNull(d.decodeLuma(data, width - 1, width, height, 0, 0, 10, 10)) // stride smaller than width
        assertNull(d.decodeLuma(ByteArray(0), rowStride, width, height, 0, 0, width, height)) // empty buffer
        assertNull(d.decodeLuma(data.copyOf(100), rowStride, width, height, 0, 0, width, height)) // short buffer
        assertNull(d.decodeLuma(ByteArray(5000) { (it * 31).toByte() }, 100, 100, 50, 0, 0, 100, 50)) // noise
    }

    @Test
    fun `the last row may be short`() {
        val d = QrFrameDecoder()
        // Real camera buffers often omit the stride padding of the final row.
        val data = luma().copyOf(rowStride * (height - 1) + width)
        assertEquals(text, d.decodeLuma(data, rowStride, width, height, 0, 0, width, height))
    }
}
