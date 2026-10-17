package inc.pazuzu.imzo.ui

import android.graphics.Rect
import android.util.Size
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors

/** Only IMQ1 frames are worth handing on; [inc.pazuzu.imzo.protocol.FrameAssembler] validates the rest. */
private const val FRAME_PREFIX = "IMQ1."

/**
 * Lets a string through at most once per `windowMs`. Each distinct string has its own window, so two
 * frames alternating in view are still throttled; a suppressed sighting does not extend its window.
 * Not thread-safe (the analyzer calls it from its single thread).
 */
internal class Debouncer(private val windowMs: Long, private val clock: () -> Long) {
    private val lastEmitted = HashMap<String, Long>()

    fun shouldEmit(text: String): Boolean {
        val now = clock()
        lastEmitted.values.removeAll { now - it >= windowMs } // keeps the map at a handful of entries
        if (lastEmitted.containsKey(text)) return false
        lastEmitted[text] = now
        return true
    }
}

/** Decodes QR codes from the luminance plane of camera frames; reuses one reader. */
internal class QrFrameDecoder {
    private val reader = MultiFormatReader().apply {
        setHints(
            mapOf(
                DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE),
                DecodeHintType.TRY_HARDER to true,
            ),
        )
    }

    /** The QR text in the image's visible area, or null when there is none (or it is unreadable). */
    fun decode(image: ImageProxy): String? {
        return try {
            val plane = image.planes[0]
            val buffer = plane.buffer
            buffer.rewind()
            val bytes = ByteArray(buffer.remaining()).also { buffer.get(it) }
            val crop: Rect = image.cropRect
            decodeLuma(bytes, plane.rowStride, image.width, image.height, crop.left, crop.top, crop.width(), crop.height())
        } catch (_: Exception) { // odd frame or buffer trouble: drop it, never crash the analysis thread
            null
        }
    }

    /**
     * Decodes the QR text in a luminance plane: `bytes` holds `height` rows of `rowStride` bytes (the
     * last row may be short or the buffer shorter than `rowStride * height`; it is zero-padded), of
     * which `width` bytes per row are image. The crop is clamped to the image. Returns null for
     * anything unreadable or malformed (including odd dimensions); never throws.
     */
    fun decodeLuma(
        bytes: ByteArray, rowStride: Int, width: Int, height: Int,
        cropLeft: Int, cropTop: Int, cropWidth: Int, cropHeight: Int,
    ): String? {
        return try {
            if (rowStride <= 0 || width <= 0 || height <= 0 || width > rowStride) return null
            val left = cropLeft.coerceIn(0, width - 1)
            val top = cropTop.coerceIn(0, height - 1)
            val w = cropWidth.coerceAtMost(width - left)
            val h = cropHeight.coerceAtMost(height - top)
            if (w <= 0 || h <= 0) return null
            val needed = rowStride * height
            val data = if (bytes.size == needed) bytes else bytes.copyOf(needed)
            val source = PlanarYUVLuminanceSource(data, rowStride, height, left, top, w, h, false)
            reader.decodeWithState(BinaryBitmap(HybridBinarizer(source))).text
        } catch (_: Exception) { // ReaderException (nothing readable) or an unchecked one from the detector
            null
        } finally {
            reader.reset()
        }
    }
}

/**
 * Back-camera preview that scans QR codes and calls `onText` (on the main thread) with each IMQ1 frame,
 * at most once per distinct string per 300 ms. Compose it only once the camera permission is granted;
 * it does not ask. Unbinds the camera and stops its analysis thread when it leaves the composition.
 */
@Composable
fun CameraScanner(modifier: Modifier = Modifier, onText: (String) -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val currentOnText by rememberUpdatedState(onText)
    var noCamera by remember { mutableStateOf(false) }
    val previewView = remember { PreviewView(context) }

    DisposableEffect(lifecycleOwner) {
        val mainExecutor = ContextCompat.getMainExecutor(context)
        val analysisExecutor = Executors.newSingleThreadExecutor()
        val decoder = QrFrameDecoder()
        var disposed = false // main thread only
        val debouncer = Debouncer(300) { System.nanoTime() / 1_000_000 }
        val preview = Preview.Builder().build().also { it.surfaceProvider = previewView.surfaceProvider }
        val analysis = ImageAnalysis.Builder()
            .setResolutionSelector(
                ResolutionSelector.Builder()
                    .setResolutionStrategy(
                        ResolutionStrategy(Size(1280, 720), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER),
                    )
                    .build(),
            )
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()
        analysis.setAnalyzer(analysisExecutor) { image ->
            try {
                val text = decoder.decode(image)
                if (text != null && text.startsWith(FRAME_PREFIX) && debouncer.shouldEmit(text)) {
                    mainExecutor.execute { if (!disposed) currentOnText(text) }
                }
            } catch (_: Exception) { // an exception escaping here would kill the app: drop the frame
            } finally {
                image.close()
            }
        }

        var provider: ProcessCameraProvider? = null
        val providerFuture = ProcessCameraProvider.getInstance(context)
        providerFuture.addListener({
            if (disposed) return@addListener
            try {
                val p = providerFuture.get()
                provider = p
                p.unbindAll()
                p.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
            } catch (_: IllegalArgumentException) { // no back camera
                noCamera = true
            } catch (_: IllegalStateException) { // camera in use elsewhere, or use cases cannot be combined here
                noCamera = true
            } catch (_: ExecutionException) { // camera provider failed to initialise
                noCamera = true
            }
        }, mainExecutor)

        onDispose {
            disposed = true
            analysis.clearAnalyzer()
            provider?.unbind(preview, analysis)
            analysisExecutor.shutdown()
        }
    }

    if (noCamera) {
        Box(modifier = modifier, contentAlignment = Alignment.Center) { Text("No camera available on this device.") }
    } else {
        AndroidView(modifier = modifier, factory = { previewView })
    }
}
