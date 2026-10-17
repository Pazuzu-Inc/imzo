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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** The enrolment code to compare with the laptop, and the `er` answer for the laptop to scan. */
@Composable
fun EnrolScreen(state: UiState.EnrolShowing, onDone: () -> Unit) {
    KeepScreenOn()
    MaxBrightness()
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Check it matches the laptop", fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
        Text(state.code, fontFamily = FontFamily.Monospace, fontSize = 72.sp, fontWeight = FontWeight.Black, softWrap = false)
        Text("Then show this to the laptop camera", fontSize = 18.sp)
        QrPlayer(state.frames, Modifier.fillMaxWidth(), fps = 3)
        Button(onClick = onDone, modifier = Modifier.fillMaxWidth().height(64.dp)) { Text("Done", fontSize = 22.sp) }
    }
}
