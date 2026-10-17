package inc.pazuzu.imzo.ui

import inc.pazuzu.imzo.DeviceKeyLost
import inc.pazuzu.imzo.SigningCancelled
import inc.pazuzu.imzo.SigningFailed
import inc.pazuzu.imzo.protocol.Approver
import inc.pazuzu.imzo.protocol.Cbor
import inc.pazuzu.imzo.protocol.Frames
import inc.pazuzu.imzo.protocol.enrolmentCode
import inc.pazuzu.imzo.protocol.deviceId
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ApproverViewModelTest {
    private lateinit var signer: TestSigner
    private lateinit var counter: TestCounter
    private lateinit var vm: ApproverViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        newViewModel()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun newViewModel() {
        signer = TestSigner()
        counter = TestCounter()
        vm = ApproverViewModel(Approver(signer, counter)) { signer.publicKey }
    }

    private val state get() = vm.state.value

    private fun myRequest(rid: ByteArray = ByteArray(16) { it.toByte() }) =
        buildRequest(deviceId(signer.key), sampleItems, rid = rid)

    private fun frames(kind: String, body: ByteArray, sid: String = "s1d0", chunk: Int = 60) = Frames.encode(kind, body, chunk, sid)

    private fun feed(frames: List<String>) = frames.forEach { vm.onFrame(it) }

    private fun reviewMyRequest(): UiState.Reviewing {
        vm.startScan()
        val fs = frames("rq", myRequest())
        assertTrue("need several frames", fs.size > 1)
        feed(fs)
        return state as UiState.Reviewing
    }

    private fun answerBody(a: UiState): Map<*, *> {
        val (kind, body) = Frames.decode((a as UiState.Answer).frames)
        assertEquals("rs", kind)
        return Cbor.loads(body) as Map<*, *>
    }

    // ---------------------------------------------------------------- start

    @Test
    fun startsAtHomeWithTheDeviceId() {
        val home = state as UiState.Home
        assertEquals(formatDeviceId(deviceId(signer.key)), home.deviceId)
    }

    // ---------------------------------------------------------------- order scan -> review

    @Test
    fun scanningRequestFramesLeadsToReview() {
        vm.startScan()
        assertEquals(UiState.Scanning(null), state)
        val fs = frames("rq", myRequest())
        vm.onFrame(fs[0])
        assertEquals(1, (state as UiState.Scanning).progress!!.have)
        assertEquals(fs.size, (state as UiState.Scanning).progress!!.total)
        feed(fs.drop(1).reversed()) // any order
        val r = (state as UiState.Reviewing).review
        assertEquals(sampleItems.size, r.count)
        assertEquals(sampleItems.sumOf { it.amount }, r.total)
        assertEquals(sampleItems, r.items)
    }

    @Test
    fun garbageAndMalformedFramesAreIgnored() {
        vm.startScan()
        for (t in listOf("", "hello", "https://example.com", "IMQ1.", "IMQ1.rq.s.1.2.!!!", "IMQ1.zz.s.1.1.AAAA", "IMQ1.rq..1.1.AAAA")) vm.onFrame(t)
        assertEquals(UiState.Scanning(null), state)
        feed(frames("rq", myRequest())) // still works afterwards
        assertTrue(state is UiState.Reviewing)
    }

    @Test
    fun crcErrorResetsAndKeepsScanning() {
        vm.startScan()
        val body = myRequest()
        val good = frames("rq", body, sid = "aaaa")
        val other = body.copyOf().also { it[5] = (it[5] + 1).toByte() }
        val bad = frames("rq", other, sid = "aaaa")
        assertEquals(good.size, bad.size)
        // the first part comes from a different body: all parts present, CRC fails
        feed(listOf(bad.first()) + good.drop(1))
        assertEquals(UiState.Scanning(null), state)
        feed(frames("rq", body, sid = "bbbb"))
        assertTrue(state is UiState.Reviewing)
    }

    @Test
    fun requestForAnotherDeviceIsRejectedAndApproveIsANoOp() {
        vm.startScan()
        feed(frames("rq", buildRequest(ByteArray(16) { 0x77 }, sampleItems)))
        val s = state as UiState.Rejected
        assertEquals("WRONG DEVICE", s.reason)
        vm.approve()
        vm.reject()
        assertEquals(s, state)
        assertEquals(0L, counter.value)
        assertEquals(0, signer.signCalls)
    }

    @Test
    fun malformedRequestShowsItsCode() {
        vm.startScan()
        feed(frames("rq", buildRequest(deviceId(signer.key), sampleItems, total = 1)))
        assertEquals("TOTAL MISMATCH", (state as UiState.Rejected).reason)

        vm.reset()
        vm.startScan()
        feed(frames("rq", byteArrayOf(0xff.toByte())))
        assertEquals("BAD FORMAT", (state as UiState.Rejected).reason)
    }

    @Test
    fun anotherMessageKindWhileScanningForAnOrderIsWrongKind() {
        vm.startScan()
        feed(frames("er", byteArrayOf(1, 2, 3)))
        assertEquals("WRONG KIND", (state as UiState.Rejected).reason)
    }

    @Test
    fun framesOutsideScanningAreIgnored() {
        feed(frames("rq", myRequest()))
        assertTrue(state is UiState.Home)
        reviewMyRequest()
        feed(frames("rq", myRequest(), sid = "zzzz")) // already reviewing
        assertTrue(state is UiState.Reviewing)
    }

    // ---------------------------------------------------------------- approve / reject

    @Test
    fun approveYieldsASignedResponseWithDecOne() {
        val review = reviewMyRequest().review
        vm.approve()
        val a = state as UiState.Answer
        assertTrue(a.approved)
        val m = answerBody(a)
        assertEquals(1L, m["dec"])
        assertEquals(1L, m["ctr"])
        assertArrayEquals(review.rid, m["rid"] as ByteArray)
        assertArrayEquals(review.h, m["h"] as ByteArray)
        assertEquals(1L, counter.value)
    }

    @Test
    fun rejectYieldsDecZero() {
        reviewMyRequest()
        vm.reject()
        val a = state as UiState.Answer
        assertEquals(false, a.approved)
        assertEquals(0L, answerBody(a)["dec"])
    }

    @Test
    fun cancelledBiometricReturnsToTheSameReviewAndATrySucceeds() {
        val reviewing = reviewMyRequest()
        signer.beforeSign = { throw SigningCancelled("user cancelled") }
        vm.approve()
        val back = state as UiState.Reviewing
        assertSame(reviewing.review, back.review)
        assertNull(back.notice)
        assertEquals(false, back.busy)
        assertEquals(1L, counter.value) // consumed, deliberately not refunded

        signer.beforeSign = {}
        vm.approve() // not stuck in flight
        assertEquals(2L, answerBody(state)["ctr"])
        assertEquals(2L, counter.value)
    }

    @Test
    fun failedSigningShowsTheMessageAndReturnsToReview() {
        val reviewing = reviewMyRequest()
        signer.beforeSign = { throw SigningFailed("hardware error") }
        vm.reject()
        val back = state as UiState.Reviewing
        assertSame(reviewing.review, back.review)
        assertEquals("hardware error", back.notice)
        signer.beforeSign = {}
        vm.reject()
        assertEquals(0L, answerBody(state)["dec"])
    }

    @Test
    fun onlyOneResponseWhileOneIsInFlight() {
        reviewMyRequest()
        val gate = CompletableDeferred<Unit>()
        signer.beforeSign = { gate.await() }
        vm.approve()
        assertEquals(true, (state as UiState.Reviewing).busy)
        vm.approve()
        vm.reject()
        vm.approve()
        gate.complete(Unit)
        val a = state as UiState.Answer
        assertTrue(a.approved)
        assertEquals(1L, counter.value)
        assertEquals(1, signer.signCalls)
        vm.approve() // and none after the answer either
        vm.reject()
        assertEquals(1L, counter.value)
        assertTrue(state is UiState.Answer)
    }

    @Test
    fun resetWhileSigningDropsTheResult() {
        reviewMyRequest()
        val gate = CompletableDeferred<Unit>()
        signer.beforeSign = { gate.await() }
        vm.approve()
        vm.reset()
        assertTrue(state is UiState.Home)
        gate.complete(Unit)
        assertTrue(state is UiState.Home)
    }

    @Test
    fun resetReturnsHomeFromAnywhere() {
        reviewMyRequest()
        vm.reset()
        assertTrue(state is UiState.Home)
        reviewMyRequest()
        vm.approve()
        assertTrue(state is UiState.Answer)
        vm.reset()
        assertTrue(state is UiState.Home)
        vm.startEnrol()
        vm.reset()
        assertTrue(state is UiState.Home)
        // a half-scanned session does not leak into the next scan
        vm.startScan()
        val fs = frames("rq", myRequest())
        vm.onFrame(fs[0])
        vm.reset()
        vm.startScan()
        vm.onFrame(fs[1])
        assertEquals(1, (state as UiState.Scanning).progress!!.have)
    }

    // ---------------------------------------------------------------- key problems

    @Test
    fun deviceKeyLostAtStartIsADedicatedState() {
        signer = TestSigner().apply { publicKeyError = DeviceKeyLost("gone") }
        vm = ApproverViewModel(Approver(signer, TestCounter())) { signer.publicKey }
        assertEquals(UiState.DeviceKeyLost, state)
        vm.startScan()
        vm.startEnrol()
        assertEquals(UiState.DeviceKeyLost, state)
    }

    @Test
    fun deviceKeyLostWhileReviewingIsADedicatedState() {
        vm.startScan()
        signer.publicKeyError = DeviceKeyLost("gone")
        feed(frames("rq", myRequest()))
        assertEquals(UiState.DeviceKeyLost, state)
    }

    @Test
    fun noScreenLockIsADedicatedState() {
        signer = TestSigner().apply { publicKeyError = IllegalStateException("Cannot create the device key") }
        vm = ApproverViewModel(Approver(signer, TestCounter())) { signer.publicKey }
        assertEquals(UiState.ScreenLockRequired, state)
        signer.publicKeyError = null
        vm.reset() // "Try again" after setting a lock
        assertTrue(state is UiState.Home)
    }

    // ---------------------------------------------------------------- enrolment

    private val nonce = ByteArray(16) { (0x70 + it).toByte() }
    private fun enrolRequest(org: String = "demo") =
        Cbor.dumps(linkedMapOf("v" to 1L, "nonce" to nonce, "org" to org))

    @Test
    fun enrolmentShowsTheCodeAndTheSignedAnswer() {
        vm.startEnrol()
        assertEquals(UiState.EnrolScanning(null), state)
        val fs = frames("en", enrolRequest(), chunk = 20)
        assertTrue(fs.size > 1)
        vm.onEnrolRequestFrame(fs[0])
        assertEquals(1, (state as UiState.EnrolScanning).progress!!.have)
        fs.drop(1).forEach { vm.onEnrolRequestFrame(it) }

        val shown = state as UiState.EnrolShowing
        assertEquals(enrolmentCode(signer.key), shown.code)
        val (kind, body) = Frames.decode(shown.frames)
        assertEquals("er", kind)
        val m = Cbor.loads(body) as Map<*, *>
        assertArrayEquals(signer.key, m["pub"] as ByteArray)
        val msg = "IMZO/1/enrol\u0000".toByteArray(Charsets.ISO_8859_1) + nonce + "demo".toByteArray()
        assertTrue(verifyRaw(signer.key, msg, m["sig"] as ByteArray))
        assertEquals(0L, counter.value) // enrolment does not use the response counter
    }

    @Test
    fun orderFramesDuringEnrolmentAreWrongKind() {
        vm.startEnrol()
        frames("rq", myRequest()).forEach(vm::onEnrolRequestFrame)
        assertEquals("WRONG KIND", (state as UiState.Rejected).reason)
    }

    @Test
    fun badEnrolmentRequestIsRejected() {
        vm.startEnrol()
        frames("en", Cbor.dumps(linkedMapOf("v" to 2L, "nonce" to nonce, "org" to "demo"))).forEach(vm::onEnrolRequestFrame)
        assertEquals("BAD FORMAT", (state as UiState.Rejected).reason)
    }

    @Test
    fun cancelledEnrolmentBiometricGoesBackToScanning() {
        vm.startEnrol()
        signer.beforeSign = { throw SigningCancelled("x") }
        frames("en", enrolRequest()).forEach(vm::onEnrolRequestFrame)
        assertEquals(UiState.EnrolScanning(null), state)
        signer.beforeSign = {}
        frames("en", enrolRequest()).forEach(vm::onEnrolRequestFrame)
        assertNotNull((state as UiState.EnrolShowing).frames)
    }

    @Test
    fun enrolFramesAreIgnoredWhenNotEnrolling() {
        frames("en", enrolRequest()).forEach(vm::onEnrolRequestFrame)
        assertTrue(state is UiState.Home)
        vm.startScan()
        frames("en", enrolRequest()).forEach(vm::onEnrolRequestFrame)
        assertEquals(UiState.Scanning(null), state)
    }
}
