package inc.pazuzu.imzo.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * What the approver signs, in full. The header (recomputed total, fingerprint, count, op, ref) is bounded and
 * scrolls inside its limit; the Approve / Reject buttons are always on screen; the item list scrolls. No text here is ever shortened:
 * no `maxLines`, no ellipsis.
 */
@Composable
fun ReviewScreen(state: UiState.Reviewing, onApprove: () -> Unit, onReject: () -> Unit) {
    val display = ReviewPresenter.present(state.review)
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val totalWidth = maxWidth
        val landscape = maxWidth > maxHeight
        if (landscape) {
            // Landscape: header and buttons on the left, the list gets the full height on the right.
            // The buttons are anchored at the bottom; the header scrolls in the space above them.
            Row(modifier = Modifier.fillMaxSize()) {
                Column(modifier = Modifier.width(totalWidth * 0.45f).fillMaxHeight().padding(12.dp)) {
                    Header(display, modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()))
                    Buttons(state, onApprove, onReject)
                }
                ItemList(display, modifier = Modifier.weight(1f).fillMaxHeight())
            }
        } else {
            // Portrait: the header is capped at half the height and scrolls inside that; the buttons are
            // measured before the weighted list, so they can never be squeezed out.
            val headerMax = headerMaxHeightDp(maxHeight.value, landscape = false, reservedBelowDp = BUTTONS_RESERVED_DP).dp
            Column(modifier = Modifier.fillMaxSize()) {
                Header(
                    display,
                    modifier = Modifier.heightIn(max = headerMax).verticalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                )
                HorizontalDivider(thickness = 2.dp)
                ItemList(display, modifier = Modifier.weight(1f).fillMaxWidth())
                HorizontalDivider(thickness = 2.dp)
                Buttons(state, onApprove, onReject, modifier = Modifier.padding(12.dp))
            }
        }
    }
}

private const val BUTTONS_RESERVED_DP = 96f

/** Most important first: total and fingerprint, then the count, then op and reference. */
@Composable
private fun Header(d: ReviewDisplay, modifier: Modifier = Modifier) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("TOTAL (recomputed on this device)", fontSize = 13.sp, color = MaterialTheme.colorScheme.secondary, softWrap = true)
        Text(d.total, fontSize = 32.sp, fontWeight = FontWeight.Black, lineHeight = 36.sp, softWrap = true)
        Text("Compare with the laptop", fontSize = 13.sp, color = MaterialTheme.colorScheme.secondary, modifier = Modifier.padding(top = 4.dp))
        for (line in d.fingerprint) {
            Text(line, fontFamily = FontFamily.Monospace, fontSize = 22.sp, fontWeight = FontWeight.Bold, softWrap = true)
        }
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(d.count, fontSize = 56.sp, fontWeight = FontWeight.Black, lineHeight = 60.sp)
            Text(d.countLabel, fontSize = 22.sp, modifier = Modifier.padding(bottom = 8.dp))
        }
        Text("${d.op.uppercase()}  ${d.approval}", fontSize = 16.sp, color = MaterialTheme.colorScheme.secondary, softWrap = true)
        Text(d.ref, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, softWrap = true)
    }
}

@Composable
private fun ItemList(d: ReviewDisplay, modifier: Modifier = Modifier) {
    LazyColumn(modifier = modifier, contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp, vertical = 8.dp)) {
        items(d.items) { item ->
            Column(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("${item.number}. ${item.name}", fontSize = 20.sp, fontWeight = FontWeight.SemiBold, softWrap = true)
                Text(item.account, fontFamily = FontFamily.Monospace, fontSize = 18.sp, softWrap = true)
                Text(item.amount, fontSize = 22.sp, fontWeight = FontWeight.Bold, softWrap = true)
            }
            HorizontalDivider()
        }
    }
}

@Composable
private fun Buttons(state: UiState.Reviewing, onApprove: () -> Unit, onReject: () -> Unit, modifier: Modifier = Modifier) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (state.busy) Text("Waiting for fingerprint or screen lock ...", fontSize = 16.sp, softWrap = true)
        state.notice?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 16.sp, softWrap = true) }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
            Button(
                onClick = onReject,
                enabled = !state.busy,
                modifier = Modifier.weight(1f).height(72.dp),
                colors = ButtonDefaults.buttonColors(containerColor = RejectRed, contentColor = Color.Black),
            ) { Text("Reject", fontSize = 24.sp, fontWeight = FontWeight.Bold) }
            Button(
                onClick = onApprove,
                enabled = !state.busy,
                modifier = Modifier.weight(1f).height(72.dp),
                colors = ButtonDefaults.buttonColors(containerColor = ApproveGreen, contentColor = Color.Black),
            ) { Text("Approve", fontSize = 24.sp, fontWeight = FontWeight.Bold) }
        }
    }
}
