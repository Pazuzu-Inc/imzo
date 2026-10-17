package inc.pazuzu.imzo.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** Target size of the pre-rendered bitmaps; they are drawn scaled up, nearest-neighbour. */
private const val BITMAP_PX = 512

/** The index that follows `i` in a loop of `n` frames; 0 for an empty or single-frame loop or an index out of range. */
internal fun nextFrameIndex(i: Int, n: Int): Int {
    val next = i + 1
    return if (i < 0 || next >= n) 0 else next
}

/** Milliseconds each frame stays on screen at `fps` (at least 1 fps). */
internal fun frameDelayMs(fps: Int): Long = 1000L / fps.coerceAtLeast(1)

/**
 * Loops `frames` as QR codes at `fps`, full width and square on a white background, with a small
 * "frame i/n" caption. Nothing for an empty list; a single frame is shown without animating.
 * All bitmaps are rendered up front on `Dispatchers.Default`, so playback only swaps images.
 */
@Composable
fun QrPlayer(frames: List<String>, modifier: Modifier = Modifier, fps: Int = 3) {
    if (frames.isEmpty()) return
    val bitmaps by produceState(emptyList<ImageBitmap>(), frames) {
        value = emptyList()
        value = withContext(Dispatchers.Default) { frames.map { qrBitmap(it, BITMAP_PX).asImageBitmap() } }
    }
    var index by remember(frames) { mutableIntStateOf(0) }
    LaunchedEffect(frames, bitmaps.size, fps) {
        if (bitmaps.size > 1) {
            while (true) {
                delay(frameDelayMs(fps))
                index = nextFrameIndex(index, bitmaps.size)
            }
        }
    }
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        val shown = bitmaps.getOrNull(index)
        if (shown != null) {
            Image(
                bitmap = shown,
                contentDescription = "QR frame ${index + 1} of ${bitmaps.size}",
                modifier = Modifier.fillMaxWidth().aspectRatio(1f).background(Color.White),
                contentScale = ContentScale.Fit,
                filterQuality = FilterQuality.None,
            )
            Text("frame ${index + 1}/${bitmaps.size}")
        }
    }
}
