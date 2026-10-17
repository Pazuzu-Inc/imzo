import { test, after } from "node:test";
import assert from "node:assert/strict";

// A tiny stand-in for the browser, just enough to run app.js unmodified.

const handlers = {};
const elements = new Map();
function element(id) {
  if (!elements.has(id)) {
    const ctx = { fillRect() {}, drawImage() {}, getImageData: () => ({ data: [] }) };
    elements.set(id, {
      id, value: "", checked: false, disabled: false, textContent: "", className: "", innerHTML: "", width: 560, height: 560,
      addEventListener: (type, fn) => { (handlers[id] ||= {})[type] = fn; },
      getContext: () => ctx,
      // <video> bits used by the scanner
      videoWidth: 640, videoHeight: 480, readyState: 4, srcObject: null, play: async () => {},
    });
  }
  return elements.get(id);
}
globalThis.document = { getElementById: element, createElement: () => element("offscreen") };
const click = (id) => handlers[id].click();

const PAYROLL = Array.from({ length: 12 }, (_, i) => [`Person ${i + 1}`, `8600${String(i + 1).padStart(16, "0")}`, 1000 + i]);
const calls = [];
let answerStatus = "PENDING";
let ordersGate = null; // when set, POST /api/orders waits for this promise
globalThis.fetch = async (path, init = {}) => {
  const method = init.method || "GET";
  calls.push({ method, path, body: init.body ? JSON.parse(init.body) : undefined });
  const reply = (data, ok = true) => ({ ok, status: ok ? 200 : 400, json: async () => data });
  if (path === "payroll.json") return reply(PAYROLL);
  if (path === "/api/orders" && method === "POST") {
    if (ordersGate) await ordersGate;
    return reply({ order_id: "ord-1", frames: ["IMQ1.rq.aaaa0001.1.1.QQ"], fingerprint: "x" });
  }
  if (path === "/api/answers") return reply({ status: answerStatus });
  if (path === "/api/audit") return reply({ ok: true, rows: [] });
  if (path.startsWith("/api/orders/")) return reply({ status: "PENDING" });
  if (path === "/api/reset") return reply({ ok: true });
  return reply({ error: "NOT FOUND" }, false);
};
const scanQueue = [];
globalThis.jsQR = () => {
  const data = scanQueue.shift();
  return data === undefined ? null : { data };
};
globalThis.qrcode = () => ({ addData() {}, make() {}, getModuleCount: () => 1, isDark: () => false });
Object.defineProperty(globalThis, "navigator", {
  configurable: true,
  value: { mediaDevices: { getUserMedia: async () => ({ getTracks: () => [{ stop() {} }] }) } },
});

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
await import("../app.js");
await sleep(50); // init(): payroll.json
after(async () => {
  await click("reset"); // stops the camera timer and the order poll so the process can exit
});

test("Send is disabled while the order request is in flight and enabled again afterwards", async () => {
  let release;
  ordersGate = new Promise((r) => (release = r));
  const first = click("send");
  await sleep(10);
  const disabledWhileInFlight = element("send").disabled;
  release();
  await first;
  ordersGate = null;
  assert.equal(disabledWhileInFlight, true);
  assert.equal(element("send").disabled, false);
  assert.equal(calls.filter((c) => c.path === "/api/orders" && c.method === "POST").length, 1);
});

test("a failed order request also re-enables Send", async () => {
  const realFetch = globalThis.fetch;
  globalThis.fetch = async (path, init) => {
    if (path === "/api/orders" && init && init.method === "POST") throw new Error("offline");
    return realFetch(path, init);
  };
  await click("send");
  globalThis.fetch = realFetch;
  assert.equal(element("send").disabled, false);
  assert.equal(element("status").textContent, "offline");
});

test("after a PENDING answer the collector restarts, so the next frame does not re-POST the same frames", async () => {
  await click("send");
  const answers = () => calls.filter((c) => c.path === "/api/answers").length;
  const before = answers();
  // frames 1 and 2 complete the message -> POST -> PENDING; then frame 1 shows up again (distinct from the last one seen)
  scanQueue.push("IMQ1.rs.bbbb0002.1.2.QQ", "IMQ1.rs.bbbb0002.2.2.QQ");
  await sleep(450);
  assert.equal(answers(), before + 1);
  assert.equal(element("status").textContent, "PENDING");
  scanQueue.push("IMQ1.rs.bbbb0002.1.2.QQ");
  await sleep(450);
  assert.equal(answers(), before + 1, "no second POST of the same frames");
  assert.equal(element("progress").textContent, "answer frames: 1/2", "collection starts over");
});
