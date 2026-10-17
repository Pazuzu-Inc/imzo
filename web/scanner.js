// Reads QR codes from the webcam. Needs the global `jsQR` from vendor/jsQR.js.

const SCAN_INTERVAL_MS = 100; // ~10 scans per second
const MAX_WIDTH = 1280;
const PREFIX = "IMQ1.";

export class FrameScanner {
  /**
   * @param {HTMLVideoElement} video preview element
   * @param {(text: string) => void} onFrame called once per new IMQ1 frame string
   */
  constructor(video, onFrame) {
    this.video = video;
    this.onFrame = onFrame;
    this.stream = null;
    this.timer = null;
    this.last = null;
    this.generation = 0; // bumped by every start() and stop(); a stale start() releases its own stream
    this.canvas = document.createElement("canvas"); // offscreen, never attached
    this.ctx = this.canvas.getContext("2d", { willReadFrequently: true });
  }

  async start() {
    if (this.stream) return;
    const generation = ++this.generation;
    this.last = null;
    const stream = await navigator.mediaDevices.getUserMedia({ video: { facingMode: "user" } });
    if (generation !== this.generation) {
      // stop() or a newer start() happened while the camera was opening: nobody owns this stream
      stream.getTracks().forEach((t) => t.stop());
      return;
    }
    this.stream = stream;
    this.video.srcObject = stream;
    this.video.muted = true;
    this.video.playsInline = true;
    this.timer = setInterval(() => this._scan(), SCAN_INTERVAL_MS); // _scan waits for the first video frame
    try {
      await this.video.play();
    } catch (err) {
      if (generation === this.generation) throw err; // an interrupted play() after stop() is not an error
    }
  }

  stop() {
    this.generation++;
    if (this.timer !== null) clearInterval(this.timer);
    this.timer = null;
    if (this.stream) this.stream.getTracks().forEach((t) => t.stop());
    this.stream = null;
    this.video.srcObject = null;
  }

  /** Forget the last string so an identical one is reported again. */
  resetDedupe() {
    this.last = null;
  }

  _scan() {
    const v = this.video;
    if (!v.videoWidth || !v.videoHeight || v.readyState < 2) return;
    const w = Math.min(v.videoWidth, MAX_WIDTH);
    const h = Math.round((v.videoHeight * w) / v.videoWidth);
    if (this.canvas.width !== w || this.canvas.height !== h) {
      this.canvas.width = w;
      this.canvas.height = h;
    }
    this.ctx.drawImage(v, 0, 0, w, h);
    const image = this.ctx.getImageData(0, 0, w, h);
    const code = globalThis.jsQR(image.data, w, h, { inversionAttempts: "dontInvert" });
    if (!code || typeof code.data !== "string" || !code.data.startsWith(PREFIX)) return;
    if (code.data === this.last) return;
    this.last = code.data;
    this.onFrame(code.data);
  }
}
