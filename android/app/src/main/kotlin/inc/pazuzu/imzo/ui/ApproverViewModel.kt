package inc.pazuzu.imzo.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import inc.pazuzu.imzo.DeviceKeyLost as KeyLost
import inc.pazuzu.imzo.SigningCancelled
import inc.pazuzu.imzo.SigningFailed
import inc.pazuzu.imzo.protocol.Approver
import inc.pazuzu.imzo.protocol.FrameAssembler
import inc.pazuzu.imzo.protocol.Frames
import inc.pazuzu.imzo.protocol.Progress
import inc.pazuzu.imzo.protocol.ProtocolError
import inc.pazuzu.imzo.protocol.Review
import inc.pazuzu.imzo.protocol.deviceId
import inc.pazuzu.imzo.protocol.enrolmentCode
import inc.pazuzu.imzo.protocol.parseEnrolmentRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

sealed interface UiState {
    /** `deviceId` is the grouped device id for display. */
    data class Home(val deviceId: String) : UiState

    /** Waiting for / assembling the server's `en` challenge. `notice` explains a failed attempt. */
    data class EnrolScanning(val progress: Progress?, val notice: String? = null) : UiState

    /** The enrolment code and the `er` answer frames to play. */
    data class EnrolShowing(val code: String, val frames: List<String>) : UiState

    data class Scanning(val progress: Progress?) : UiState

    /** `busy` while the biometric prompt / signing is in flight; `notice` is a message from a failed attempt. */
    data class Reviewing(val review: Review, val notice: String? = null, val busy: Boolean = false) : UiState

    /** A short protocol code (WRONG DEVICE, BAD FORMAT, TOTAL MISMATCH, WRONG KIND ...) shown prominently. */
    data class Rejected(val reason: String, val detail: String = "") : UiState

    data class Answer(val frames: List<String>, val approved: Boolean) : UiState

    /** The Keystore key is gone or replaced: the device must be enrolled again. */
    data object DeviceKeyLost : UiState

    /** The Keystore refused to make or use the key: the device has no screen lock. */
    data object ScreenLockRequired : UiState
}

/**
 * The approver's whole flow. Scanning is driven by the UI through [onFrame] / [onEnrolRequestFrame].
 * One answer per review: while [approve] / [reject] is in flight, or after it succeeded, further calls
 * do nothing until [reset]. The counter used by a cancelled or failed attempt is not refunded.
 *
 * [publicKey] is read lazily for display and enrolment; failures surface as [UiState.DeviceKeyLost] or
 * [UiState.ScreenLockRequired].
 */
class ApproverViewModel(
    private val approver: Approver,
    private val publicKey: () -> ByteArray,
) : ViewModel() {
    private val assembler = FrameAssembler()
    private var inFlight = false
    private var job: Job? = null
    private var epoch = 0 // bumped by reset(); a result from an older epoch is dropped

    private val _state = MutableStateFlow<UiState>(homeState())
    val state: StateFlow<UiState> = _state

    // ------------------------------------------------------------------ navigation

    fun startScan() {
        resetInternal()
        _state.value = if (isKeyProblem(_state.value)) _state.value else UiState.Scanning(null)
    }

    fun startEnrol() {
        resetInternal()
        _state.value = if (isKeyProblem(_state.value)) _state.value else UiState.EnrolScanning(null)
    }

    /** Back to Home from anywhere; abandons a pending signature. */
    fun reset() {
        resetInternal()
    }

    private fun resetInternal() {
        epoch++
        job?.cancel()
        job = null
        inFlight = false
        assembler.reset()
        _state.value = homeState()
    }

    private fun isKeyProblem(s: UiState) = s is UiState.DeviceKeyLost || s is UiState.ScreenLockRequired || s is UiState.Rejected

    private fun homeState(): UiState = try {
        UiState.Home(formatDeviceId(deviceId(publicKey())))
    } catch (e: Exception) {
        keyProblem(e)
    }

    private fun keyProblem(e: Exception): UiState = when (e) {
        is KeyLost -> UiState.DeviceKeyLost
        is IllegalStateException -> UiState.ScreenLockRequired
        else -> UiState.Rejected("DEVICE ERROR", e.message.orEmpty())
    }

    // ------------------------------------------------------------------ scanning

    /** A scanned QR text while [UiState.Scanning]. Anything else is ignored. */
    fun onFrame(text: String) {
        if (_state.value !is UiState.Scanning) return
        feed(text) { kind, body -> onOrderMessage(kind, body) }
    }

    /** A scanned QR text while [UiState.EnrolScanning]. Anything else is ignored. */
    fun onEnrolRequestFrame(text: String) {
        if (_state.value !is UiState.EnrolScanning) return
        feed(text) { kind, body -> onEnrolMessage(kind, body) }
    }

    private fun feed(text: String, whenComplete: (String, ByteArray) -> Unit) {
        if (inFlight || !text.startsWith("IMQ1.")) return // not ours; the scanner already filters, this is belt and braces
        val progress = try {
            assembler.add(text)
        } catch (_: ProtocolError) {
            return // malformed frame: keep scanning
        }
        val done = try {
            assembler.result()
        } catch (_: ProtocolError) {
            setProgress(null) // CRC failure: the assembler has reset itself, scan again
            return
        }
        if (done == null) {
            setProgress(progress)
            return
        }
        assembler.reset()
        whenComplete(done.first, done.second)
    }

    private fun setProgress(progress: Progress?) {
        _state.value = when (val s = _state.value) {
            is UiState.Scanning -> UiState.Scanning(progress)
            is UiState.EnrolScanning -> UiState.EnrolScanning(progress)
            else -> s
        }
    }

    private fun onOrderMessage(kind: String, body: ByteArray) {
        if (kind != "rq") {
            _state.value = UiState.Rejected("WRONG KIND", "expected an order, scanned \"$kind\"")
            return
        }
        _state.value = try {
            UiState.Reviewing(approver.review(body))
        } catch (e: ProtocolError) {
            UiState.Rejected(e.code, e.detail)
        } catch (e: Exception) {
            keyProblem(e)
        }
    }

    // ------------------------------------------------------------------ enrolment

    private fun onEnrolMessage(kind: String, body: ByteArray) {
        if (kind != "en") {
            _state.value = UiState.Rejected("WRONG KIND", "expected an enrolment request, scanned \"$kind\"")
            return
        }
        val request = try {
            parseEnrolmentRequest(body)
        } catch (e: ProtocolError) {
            _state.value = UiState.Rejected(e.code, e.detail)
            return
        }
        val code = try {
            enrolmentCode(publicKey())
        } catch (e: Exception) {
            _state.value = keyProblem(e)
            return
        }
        inFlight = true
        val my = epoch
        job = viewModelScope.launch {
            val next: UiState = try {
                val answer = approver.enrolmentAnswer(request.nonce, request.org)
                UiState.EnrolShowing(code, Frames.encode("er", answer))
            } catch (e: CancellationException) {
                throw e
            } catch (_: SigningCancelled) {
                UiState.EnrolScanning(null)
            } catch (e: SigningFailed) {
                UiState.EnrolScanning(null, e.message)
            } catch (e: ProtocolError) {
                UiState.Rejected(e.code, e.detail)
            } catch (e: Exception) {
                keyProblem(e)
            }
            if (epoch == my) {
                inFlight = false
                _state.value = next
            }
        }
    }

    // ------------------------------------------------------------------ answering

    fun approve() = answer(true)

    fun reject() = answer(false)

    private fun answer(approve: Boolean) {
        val reviewing = _state.value as? UiState.Reviewing ?: return
        if (inFlight) return
        inFlight = true
        val my = epoch
        _state.value = reviewing.copy(notice = null, busy = true)
        job = viewModelScope.launch {
            val next: UiState = try {
                val response = approver.respond(reviewing.review, approve)
                UiState.Answer(Frames.encode("rs", response), approve)
            } catch (e: CancellationException) {
                throw e
            } catch (_: SigningCancelled) {
                reviewing.copy(notice = null, busy = false)
            } catch (e: SigningFailed) {
                reviewing.copy(notice = e.message ?: "Signing failed", busy = false)
            } catch (e: KeyLost) {
                UiState.DeviceKeyLost
            } catch (e: ProtocolError) {
                UiState.Rejected(e.code, e.detail)
            } catch (e: Exception) {
                reviewing.copy(notice = "Could not sign: ${e.message}", busy = false)
            }
            if (epoch == my) {
                inFlight = false
                _state.value = next
            }
        }
    }
}
