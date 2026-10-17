package inc.pazuzu.imzo.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** The signed answer as an animated QR for the laptop to scan; screen on, brightness up while shown. */
@Composable
fun AnswerScreen(state: UiState.Answer, onDone: () -> Unit) {
    KeepScreenOn()
    MaxBrightness()
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            if (state.approved) "APPROVED" else "REJECTED",
            fontSize = 44.sp,
            fontWeight = FontWeight.Black,
            color = if (state.approved) ApproveGreen else RejectRed,
        )
        Text("Show this to the laptop camera", fontSize = 18.sp)
        QrPlayer(state.frames, Modifier.fillMaxWidth(), fps = 3)
        Button(onClick = onDone, modifier = Modifier.fillMaxWidth().height(64.dp)) { Text("Done", fontSize = 22.sp) }
    }
}
