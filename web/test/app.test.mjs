import { test, after } from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";

// A tiny stand-in for the browser, just enough to run app.js unmodified.

const handlers = {};
const resetHandlers = [];
const elements = new Map();
function element(id) {
  if (!elements.has(id)) {
    const ctx = { fillRect() {}, drawImage() {}, getImageData: () => ({ data: [] }) };
    elements.set(id, {
      id, value: "", checked: false, disabled: false, textContent: "", className: "", innerHTML: "", width: 560, height: 560,
      addEventListener: (type, fn) => {
        (handlers[id] ||= {})[type] = fn;
        if (id === "reset") resetHandlers.push(fn); // every page instance, so cleanup can stop them all
      },
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
let answerError = null; // when set, POST /api/answers fails with this error code (HTTP 400)
const FINGERPRINT = "bada fele kogi mumi puta rasi";
let ordersGate = null; // when set, POST /api/orders waits for this promise
globalThis.fetch = async (path, init = {}) => {
  const method = init.method || "GET";
  calls.push({ method, path, body: init.body ? JSON.parse(init.body) : undefined });
  const reply = (data, ok = true) => ({ ok, status: ok ? 200 : 400, json: async () => data });
  if (path === "payroll.json") return reply(PAYROLL);
  if (path === "/api/orders" && method === "POST") {
    if (ordersGate) await ordersGate;
    return reply({ order_id: "ord-1", frames: ["IMQ1.rq.aaaa0001.1.1.QQ"], fingerprint: FINGERPRINT });
  }
  if (path === "/api/answers") return answerError ? reply({ error: answerError }, false) : reply({ status: answerStatus });
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
  for (const reset of resetHandlers) await reset(); // stops camera timers and order polls so the process can exit
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

test("the page shows the fingerprint of the order it submitted, labelled to match the approver", async () => {
  const html = readFileSync(new URL("../index.html", import.meta.url), "utf8");
  assert.ok(html.includes("Order fingerprint (same words on the approver)"), "label in the order panel");
  assert.match(html, /id="fingerprint"/);
  await click("send");
  assert.equal(element("fingerprint").textContent, FINGERPRINT);
  assert.equal(element("fpWrap").hidden, false);
  await click("reset");
  assert.equal(element("fingerprint").textContent, "", "cleared with the server");
  assert.equal(element("fpWrap").hidden, true);
});

test("an /api/answers error while an order is shown keeps the request QR going and collecting", async () => {
  await click("send");
  const answers = () => calls.filter((c) => c.path === "/api/answers").length;
  const before = answers();
  answerError = "NOT PENDING";
  scanQueue.push("IMQ1.rs.cccc0003.1.2.QQ", "IMQ1.rs.cccc0003.2.2.QQ"); // a stale answer still on the phone
  await sleep(450);
  assert.equal(answers(), before + 1);
  assert.equal(element("status").textContent, "PENDING", "the order is still pending");
  assert.equal(element("status").className, "pending");
  assert.match(element("hint").textContent, /NOT PENDING/);
  assert.match(element("playinfo").textContent, /showing frame/, "request QR keeps playing");
  assert.equal(element("progress").textContent, "answer frames: 0/0", "collector reset");

  // the stale answer is still in view: it is not POSTed again (that would count against the device)
  scanQueue.push("IMQ1.rs.cccc0003.1.2.QQ", "IMQ1.rs.cccc0003.2.2.QQ");
  await sleep(450);
  assert.equal(answers(), before + 1, "no second POST of an answer the server already refused");

  // a fresh answer is still collected and sent
  answerError = null;
  scanQueue.push("IMQ1.rs.dddd0004.1.2.QQ", "IMQ1.rs.dddd0004.2.2.QQ");
  await sleep(450);
  assert.equal(answers(), before + 2);
  assert.equal(element("status").textContent, "PENDING");
});

test("an /api/enrol/finish error still stops the stage", async () => {
  const realFetch = globalThis.fetch;
  globalThis.fetch = async (path, init) => {
    if (path === "/api/enrol/begin") return { ok: true, status: 200, json: async () => ({ frames: ["IMQ1.en.eeee0005.1.1.QQ"] }) };
    if (path === "/api/enrol/finish") return { ok: false, status: 400, json: async () => ({ error: "USER ALREADY HAS A DEVICE" }) };
    return realFetch(path, init);
  };
  await click("enrolBegin");
  scanQueue.push("IMQ1.er.ffff0006.1.1.QQ");
  await sleep(450);
  globalThis.fetch = realFetch;
  assert.equal(element("status").textContent, "USER ALREADY HAS A DEVICE");
  assert.equal(element("status").className, "bad");
  assert.equal(element("playinfo").textContent, "", "stage stopped");
});

test("Send is re-enabled and the error shown when applyTamper throws (non-digit account)", async () => {
  const realFetch = globalThis.fetch;
  globalThis.fetch = async (path, init) => {
    if (path === "payroll.json") return { ok: true, status: 200, json: async () => PAYROLL.map((r, i) => (i === 6 ? [r[0], "8600000000000000000x", r[2]] : r)) };
    return realFetch(path, init);
  };
  // reload the payroll the page holds: the page only fetches it at start-up, so go through a fresh import
  const fresh = await import("../app.js?bad-payroll");
  await sleep(50);
  const ordersBefore = calls.filter((c) => c.path === "/api/orders" && c.method === "POST").length;
  element("tamper").checked = true;
  await handlers.send.click(); // handlers now belong to the fresh page instance (this test runs last)
  globalThis.fetch = realFetch;
  element("tamper").checked = false;
  assert.equal(element("send").disabled, false);
  assert.match(element("status").textContent, /digit/i);
  assert.equal(element("status").className, "bad");
  assert.equal(calls.filter((c) => c.path === "/api/orders" && c.method === "POST").length, ordersBefore, "no order was sent");
  void fresh;
});

test("the tamper box is declared autocomplete=off and its label is highlighted only while ticked", async () => {
  const html = readFileSync(new URL("../index.html", import.meta.url), "utf8");
  for (const id of ["tamper", "enrolUser", "maker", "approver", "ref", "fps", "ecc", "devFrames"]) {
    assert.match(html, new RegExp(`<(input|select|textarea)[^>]*id="${id}"[^>]*autocomplete="off"`), `${id} autocomplete=off`);
  }
  assert.match(html, /id="tamperLabel"/);
  element("tamper").checked = true;
  handlers.tamper.change();
  assert.match(element("tamperLabel").className, /armed/);
  element("tamper").checked = false;
  handlers.tamper.change();
  assert.doesNotMatch(element("tamperLabel").className, /armed/);
});

test("Reset server unticks the tamper box", async () => {
  element("tamper").checked = true;
  handlers.tamper.change();
  await click("reset");
  assert.equal(element("tamper").checked, false);
  assert.doesNotMatch(element("tamperLabel").className, /armed/);
});

test("a fresh page instance starts with the tamper box unticked even if the DOM was restored as ticked", async () => {
  element("tamper").checked = true; // browser form-state restoration
  await import("../app.js?fresh-page");
  await sleep(50);
  assert.equal(element("tamper").checked, false);
  assert.doesNotMatch(element("tamperLabel").className, /armed/);
});
