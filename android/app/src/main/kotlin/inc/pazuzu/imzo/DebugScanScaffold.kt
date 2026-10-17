package inc.pazuzu.imzo

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import inc.pazuzu.imzo.protocol.FrameAssembler
import inc.pazuzu.imzo.protocol.Frames
import inc.pazuzu.imzo.protocol.ProtocolError
import inc.pazuzu.imzo.ui.CameraScanner
import inc.pazuzu.imzo.ui.QrPlayer

private enum class Mode { HELLO, SCAN, PLAY }

/**
 * THROWAWAY SCAFFOLD for the Task 8 device check (debug builds only); the next task replaces it with the
 * real screens. SCAN shows the last IMQ1 frame text, the FrameAssembler progress and the seconds from the
 * first frame to completion; PLAY loops any text, split into frames, as a QR animation.
 */
@Composable
internal fun DebugScanScaffold() {
    var mode by remember { mutableStateOf(Mode.HELLO) }
    var lastText by remember { mutableStateOf("") }
    var progress by remember { mutableStateOf("have 0/0") }
    var frameCount by remember { mutableStateOf(0) }
    val assembler = remember { FrameAssembler() }
    var firstFrameAt by remember { mutableStateOf(0L) }
    var complete by remember { mutableStateOf(false) }
    var playText by remember { mutableStateOf("x".repeat(1500)) }
    var chunk by remember { mutableStateOf("200") }

    fun reset() {
        assembler.reset(); lastText = ""; progress = "have 0/0"; frameCount = 0; firstFrameAt = 0L; complete = false
    }

    Box(modifier = Modifier.fillMaxSize()) {
        when (mode) {
            Mode.HELLO -> CameraPreview()
            Mode.SCAN -> CameraScanner(Modifier.fillMaxSize()) { text ->
                if (complete) return@CameraScanner // done; press Reset to scan again
                lastText = text
                frameCount++
                if (firstFrameAt == 0L) firstFrameAt = System.nanoTime()
                progress = try {
                    val p = assembler.add(text)
                    val done = assembler.result()
                    if (done != null) {
                        complete = true
                        val secs = (System.nanoTime() - firstFrameAt) / 1e9
                        "COMPLETE ${p.total}/${p.total}: ${done.second.size} bytes in %.1f s (%d scans)".format(secs, frameCount)
                    } else {
                        "have ${p.have}/${p.total} (sid ${p.sid})"
                    }
                } catch (e: ProtocolError) { // bad frame, or the CRC of a complete set failed (assembler already reset)
                    "rejected: ${e.message}"
                }
            }
            Mode.PLAY -> {
                val size = chunk.toIntOrNull()?.takeIf { it in 20..1000 } ?: 200
                val frames = remember(playText, size) { Frames.encode("rq", playText.toByteArray(), size) }
                Column(
                    modifier = Modifier.fillMaxSize().background(Color.White).verticalScroll(rememberScrollState()).padding(top = 72.dp),
                ) {
                    QrPlayer(frames, Modifier.fillMaxWidth())
                    Text("${frames.size} frames, chunk $size, ${frames.first().length} chars/frame", color = Color.Black)
                    OutlinedTextField(chunk, { chunk = it }, label = { Text("chunk bytes") }, modifier = Modifier.padding(8.dp))
                    OutlinedTextField(playText, { playText = it }, label = { Text("text to play") }, modifier = Modifier.padding(8.dp).fillMaxWidth())
                }
            }
        }
        Column(
            modifier = Modifier.align(Alignment.TopCenter).fillMaxWidth().background(Color(0xCCFFFFFF)).padding(8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("DEBUG scan scaffold (throwaway)", style = MaterialTheme.typography.labelSmall, color = Color.Black)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { mode = if (mode == Mode.SCAN) Mode.HELLO else Mode.SCAN }) {
                    Text(if (mode == Mode.SCAN) "Stop scan" else "Scan")
                }
                Button(onClick = { mode = if (mode == Mode.PLAY) Mode.HELLO else Mode.PLAY }) {
                    Text(if (mode == Mode.PLAY) "Stop play" else "Play")
                }
                if (mode == Mode.SCAN) Button(onClick = { reset() }) { Text("Reset") }
            }
            if (mode == Mode.SCAN) {
                Text(progress, color = Color.Black)
                if (lastText.isNotEmpty()) Text(lastText.take(60) + "...", style = MaterialTheme.typography.labelSmall, color = Color.Black)
            }
        }
    }
}
