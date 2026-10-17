package inc.pazuzu.imzo.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun HomeScreen(deviceId: String, onScanOrder: () -> Unit, onEnrol: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Text("Imzo", fontSize = 40.sp, fontWeight = FontWeight.Bold)
        // Always visible: the approver has no network permission at all.
        Text(
            "No internet permission: talks only through QR codes",
            fontSize = 18.sp,
            fontWeight = FontWeight.SemiBold,
            color = ApproveGreen,
        )
        Column {
            Text("Device ID", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.secondary)
            Text(deviceId, fontFamily = FontFamily.Monospace, fontSize = 24.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(12.dp))
        Button(onClick = onScanOrder, modifier = Modifier.fillMaxWidth().height(96.dp)) {
            Text("Scan order", fontSize = 28.sp)
        }
        OutlinedButton(onClick = onEnrol, modifier = Modifier.fillMaxWidth().height(96.dp)) {
            Text("Enrol this device", fontSize = 28.sp)
        }
    }
}
