import { test } from "node:test";
import assert from "node:assert/strict";

// scanner.js touches `document` only to make an offscreen canvas.
globalThis.document = {
  createElement: () => ({ width: 0, height: 0, getContext: () => ({ drawImage() {}, getImageData: () => ({ data: [] }) }) }),
};
const { FrameScanner } = await import("../scanner.js");

/** A camera whose getUserMedia promises are settled by hand, so we can interleave start() and stop(). */
function fakeCamera() {
  const pending = [];
  const streams = [];
  Object.defineProperty(globalThis, "navigator", { configurable: true, value: {
    mediaDevices: {
      getUserMedia: () =>
        new Promise((resolve) => {
          const stream = { stopped: 0, getTracks: () => [{ stop: () => stream.stopped++ }] };
          streams.push(stream);
          pending.push(() => resolve(stream));
        }),
    },
  } });
  return { pending, streams };
}

const video = () => ({ srcObject: null, play: async () => {}, videoWidth: 0, videoHeight: 0, readyState: 0 });

test("stop() during getUserMedia stops the stream that arrives afterwards", async () => {
  const cam = fakeCamera();
  const s = new FrameScanner(video(), () => {});
  const started = s.start();
  s.stop();
  cam.pending[0]();
  await started;
  assert.equal(cam.streams[0].stopped, 1, "late stream is released");
  assert.equal(s.stream, null);
  assert.equal(s.timer, null);
  assert.equal(s.video.srcObject, null);
});

test("overlapping start() calls keep exactly one live stream", async () => {
  const cam = fakeCamera();
  const s = new FrameScanner(video(), () => {});
  const a = s.start();
  const b = s.start();
  cam.pending[1]();
  await b;
  cam.pending[0]();
  await a;
  assert.equal(cam.streams[0].stopped, 1, "superseded stream is released");
  assert.equal(cam.streams[1].stopped, 0);
  assert.equal(s.stream, cam.streams[1]);
  s.stop();
  assert.equal(cam.streams[1].stopped, 1);
});

test("start() after a completed start() is a no-op, and stop() then start() works again", async () => {
  const cam = fakeCamera();
  const s = new FrameScanner(video(), () => {});
  const a = s.start();
  cam.pending[0]();
  await a;
  await s.start();
  assert.equal(cam.streams.length, 1);
  s.stop();
  const b = s.start();
  cam.pending[1]();
  await b;
  assert.equal(s.stream, cam.streams[1]);
  s.stop();
  assert.deepEqual(cam.streams.map((x) => x.stopped), [1, 1]);
});
