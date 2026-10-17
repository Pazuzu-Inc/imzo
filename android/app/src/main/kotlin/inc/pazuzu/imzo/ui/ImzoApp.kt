package inc.pazuzu.imzo.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier

/** The whole app: a plain `when (state)` over the view model's [UiState]. */
@Composable
fun ImzoApp(vm: ApproverViewModel) {
    val state by vm.state.collectAsState()
    // Back from anywhere but Home abandons the flow (and any pending signature).
    BackHandler(enabled = state !is UiState.Home) { vm.reset() }

    ImzoTheme {
        Surface(modifier = Modifier.fillMaxSize()) {
            Box(modifier = Modifier.fillMaxSize().safeDrawingPadding()) { Content(state, vm) }
        }
    }
}

@Composable
private fun Content(state: UiState, vm: ApproverViewModel) {
    when (val s = state) {
        is UiState.Home -> HomeScreen(s.deviceId, onScanOrder = vm::startScan, onEnrol = vm::startEnrol)
        is UiState.Scanning -> CameraGate(onBack = vm::reset) {
            ScanRequestScreen("Scan the order on the laptop", s.progress, s.notice, vm::onFrame, vm::reset)
        }
        is UiState.EnrolScanning -> CameraGate(onBack = vm::reset) {
            ScanRequestScreen("Scan the enrolment code on the laptop", s.progress, s.notice, vm::onEnrolRequestFrame, vm::reset)
        }
        is UiState.EnrolShowing -> EnrolScreen(s, onDone = vm::reset)
        is UiState.Reviewing -> ReviewScreen(s, onApprove = vm::approve, onReject = vm::reject)
        is UiState.Answer -> AnswerScreen(s, onDone = vm::reset)
        is UiState.Rejected -> ErrorScreen(s.reason, s.detail, "Back", vm::reset)
        UiState.DeviceKeyLost -> ErrorScreen(
            "DEVICE KEY LOST",
            "This device's key is gone or was replaced, so it can no longer approve anything. " +
                "It must be enrolled again: clear this app's data in Android settings, then enrol it.",
            "Back",
            vm::reset,
        )
        UiState.ScreenLockRequired -> ErrorScreen(
            "SET A SCREEN LOCK",
            "Set a screen lock to use Imzo. The device key is protected by it.",
            "Try again",
            vm::reset,
        )
    }
}
