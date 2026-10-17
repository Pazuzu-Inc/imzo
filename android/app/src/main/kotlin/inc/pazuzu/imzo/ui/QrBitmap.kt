package inc.pazuzu.imzo.ui

import android.graphics.Bitmap
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.common.BitMatrix
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

/** Quiet zone around the code, in modules (the QR spec asks for 4; webcams need it). */
private const val QUIET_ZONE = 4

/**
 * The QR code for `text` as a module matrix that already includes the quiet zone (`true` = dark).
 * No CHARACTER_SET hint: passing one makes ZXing prepend an ECI segment, which simpler decoders (the
 * laptop page's jsQR) can trip over; ISO-8859-1 is the default and frames are ASCII anyway.
 * Width and height 0 asks ZXing for the natural size: one matrix cell per module.
 */
internal fun qrMatrix(text: String, ecc: ErrorCorrectionLevel = ErrorCorrectionLevel.M): BitMatrix {
    val hints = mapOf(
        EncodeHintType.ERROR_CORRECTION to ecc,
        EncodeHintType.MARGIN to QUIET_ZONE,
    )
    return QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 0, 0, hints)
}

/** Whole pixels per module so edges stay crisp: floor(sizePx / matrixWidth), never below 1. */
internal fun moduleScale(sizePx: Int, matrixWidth: Int): Int = maxOf(1, sizePx / matrixWidth)

/**
 * Black on white QR bitmap of at most `sizePx` (unless the code needs more than one pixel per module).
 * The size is `matrixWidth * floor(sizePx / matrixWidth)`; the caller draws it scaled to fit with
 * nearest-neighbour filtering. RGB_565 halves the memory and is exact for pure black and white.
 */
fun qrBitmap(text: String, sizePx: Int, ecc: ErrorCorrectionLevel = ErrorCorrectionLevel.M): Bitmap {
    val m = qrMatrix(text, ecc)
    val scale = moduleScale(sizePx, m.width)
    val w = m.width * scale
    val h = m.height * scale
    val pixels = IntArray(w * h)
    for (y in 0 until h) {
        val my = y / scale
        for (x in 0 until w) {
            pixels[y * w + x] = if (m.get(x / scale, my)) BLACK else WHITE
        }
    }
    return Bitmap.createBitmap(pixels, w, h, Bitmap.Config.RGB_565)
}

private const val BLACK = 0xFF000000.toInt()
private const val WHITE = 0xFFFFFFFF.toInt()
