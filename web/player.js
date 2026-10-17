// Plays a list of frame strings as an endless loop of QR codes on a canvas.
// Needs the global `qrcode` from vendor/qrcode.js in the browser, but importing this file in Node is safe.

/** Next frame index in a loop of n frames. */
export function nextIndex(i, n) {
  return n > 0 ? (i + 1) % n : 0;
}

const QUIET_MODULES = 4; // quiet zone on every side, in QR modules

export class FramePlayer {
  /**
   * @param {HTMLCanvasElement} canvas
   * @param {{fps?: number, ecc?: string, scale?: number}} options scale = canvas pixels per QR module
   */
  constructor(canvas, { fps = 3, ecc = "M", scale = 8 } = {}) {
    this.canvas = canvas;
    this.fps = fps;
    this.ecc = ecc;
    this.scale = scale;
    this.frames = [];
    this.index = 0;
    this.timer = null;
    /** Called as onShow(i, n) every time a frame is drawn. */
    this.onShow = null;
  }

  play(frames) {
    this.stop();
    this.frames = [...frames];
    this.index = 0;
    if (this.frames.length === 0) return;
    this._draw();
    if (this.frames.length > 1) {
      this.timer = setInterval(() => {
        this.index = nextIndex(this.index, this.frames.length);
        this._draw();
      }, 1000 / this.fps);
    }
  }

  stop() {
    if (this.timer !== null) clearInterval(this.timer);
    this.timer = null;
    this.frames = [];
    const ctx = this.canvas.getContext("2d");
    ctx.fillStyle = "#fff";
    ctx.fillRect(0, 0, this.canvas.width, this.canvas.height);
  }

  setFps(fps) {
    this.fps = fps;
    if (this.frames.length > 0) this.play(this.frames);
  }

  _draw() {
    const text = this.frames[this.index];
    const qr = globalThis.qrcode(0, this.ecc); // type 0 = smallest version that fits
    qr.addData(text, "Byte");
    qr.make();
    const modules = qr.getModuleCount();
    const side = (modules + 2 * QUIET_MODULES) * this.scale;
    const canvas = this.canvas;
    if (canvas.width !== side) {
      canvas.width = side;
      canvas.height = side;
    }
    const ctx = canvas.getContext("2d");
    ctx.fillStyle = "#fff";
    ctx.fillRect(0, 0, side, side);
    ctx.fillStyle = "#000";
    for (let r = 0; r < modules; r++) {
      for (let c = 0; c < modules; c++) {
        if (qr.isDark(r, c)) ctx.fillRect((c + QUIET_MODULES) * this.scale, (r + QUIET_MODULES) * this.scale, this.scale, this.scale);
      }
    }
    if (this.onShow) this.onShow(this.index, this.frames.length);
  }
}
