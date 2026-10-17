package inc.pazuzu.imzo.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import inc.pazuzu.imzo.protocol.Progress

/**
 * Full-screen camera with a progress strip. Used for the order (`rq`) and for the enrolment request
 * (`en`); the caller decides what a scanned text means. The screen stays on while this is shown.
 */
@Composable
fun ScanRequestScreen(
    title: String,
    progress: Progress?,
    notice: String?,
    onText: (String) -> Unit,
    onCancel: () -> Unit,
) {
    KeepScreenOn()
    Box(modifier = Modifier.fillMaxSize()) {
        CameraScanner(modifier = Modifier.fillMaxSize(), onText = onText)
        Column(
            modifier = Modifier.align(Alignment.TopCenter).fillMaxWidth().background(Color(0xE6000000)).padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(title, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            if (progress != null) {
                Text("Received ${progress.have} of ${progress.total} parts", fontSize = 18.sp)
                LinearProgressIndicator(
                    progress = { progress.have.toFloat() / progress.total },
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                )
            } else {
                Text("Point the camera at the animated QR code", fontSize = 16.sp)
            }
            if (notice != null) Text(notice, color = MaterialTheme.colorScheme.error, fontSize = 16.sp)
        }
        Button(onClick = onCancel, modifier = Modifier.align(Alignment.BottomCenter).padding(24.dp)) { Text("Cancel") }
    }
}
