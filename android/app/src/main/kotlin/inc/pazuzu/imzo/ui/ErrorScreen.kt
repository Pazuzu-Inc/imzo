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
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** A refusal or a device problem. Nothing was signed. The short code is the headline. */
@Composable
fun ErrorScreen(headline: String, message: String, buttonLabel: String, onButton: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(headline, fontSize = 44.sp, fontWeight = FontWeight.Black, color = RejectRed, lineHeight = 48.sp, softWrap = true)
        if (message.isNotEmpty()) Text(message, fontSize = 20.sp, softWrap = true)
        Text("Nothing was signed.", fontSize = 16.sp, color = Color(0xFFB0BEC5))
        Button(onClick = onButton, modifier = Modifier.fillMaxWidth().height(64.dp)) { Text(buttonLabel, fontSize = 22.sp) }
    }
}
