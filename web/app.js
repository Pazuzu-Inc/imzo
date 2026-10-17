// The initiator page: shows the order, plays request QR frames, scans the answer frames back.
// It never parses CBOR and never decides anything: the phone and the server do.

import { FramePlayer } from "./player.js";
import { FrameScanner } from "./scanner.js";
import { FrameCollector, applyTamper, tamperOff, totalTiyin, formatUzs } from "./logic.js";

const $ = (id) => document.getElementById(id);

const player = new FramePlayer($("qr"), { fps: 3, ecc: "M", scale: 8 });
const collector = new FrameCollector();
const scanner = new FrameScanner($("cam"), onScanned);

/** What the stage is doing: null | {mode: "order", orderId} | {mode: "enrol"}. */
let session = null;
let finishing = false;
let poll = null;
/** Answers (frames joined) already sent in this order session; never POSTed twice, so a stale answer QR in view cannot lock the device. */
const sent = new Set();
let payroll = [];

// --------------------------------------------------------------------------- helpers

async function api(path, body) {
  const res = await fetch(path, body === undefined ? {} : { method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify(body) });
  let data = null;
  try {
    data = await res.json();
  } catch {
    /* not JSON */
  }
  if (!res.ok) throw new Error((data && data.error) || `HTTP ${res.status}`);
  return data;
}

function setStatus(text, kind, hint = "") {
  const el = $("status");
  el.textContent = text;
  el.className = kind;
  $("hint").textContent = hint;
}

function statusKind(s) {
  return s === "EXECUTED" ? "ok" : s === "PENDING" ? "pending" : "bad";
}

function setProgress(have, n) {
  $("progress").textContent = `answer frames: ${have}/${n}`;
}

function frameLines(text) {
  return text.split(/\s+/).filter(Boolean);
}

function stopStage() {
  player.stop();
  scanner.stop();
  if (poll !== null) clearInterval(poll);
  poll = null;
  $("playinfo").textContent = "";
}

function ensureOptions() {
  const fps = Math.min(15, Math.max(1, Number($("fps").value) || 3));
  player.ecc = $("ecc").value;
  player.fps = fps;
}

async function startCamera() {
  try {
    await scanner.start();
  } catch (err) {
    $("hint").textContent = `Camera unavailable (${err.name || err.message}). Use the dev paste box.`;
  }
}

function startPlaying(frames) {
  ensureOptions();
  player.onShow = (i, n) => {
    $("playinfo").textContent = `showing frame ${i + 1}/${n}`;
  };
  player.play(frames);
}

function beginSession(next, expectedKind) {
  stopStage();
  collector.reset();
  sent.clear();
  finishing = false;
  session = { ...next, expectedKind };
  setProgress(0, 0);
}

// --------------------------------------------------------------------------- scanning

function onScanned(text) {
  if (!session || finishing) return;
  const parts = text.split(".", 3);
  if (parts[1] !== session.expectedKind) return; // ignore our own kind and anything unexpected
  const state = collector.add(text);
  setProgress(state.have, state.n);
  if (state.complete) {
    finishing = true;
    finishFrames(state.frames);
  }
}

async function finishFrames(frames) {
  const current = session;
  const key = frames.join("\n");
  try {
    if (current.mode === "order") {
      if (sent.has(key)) {
        keepCollecting("This answer was already sent. On the phone press Done, then Send again if needed.");
        return;
      }
      sent.add(key);
      const { status } = await api("/api/answers", { frames });
      showOrderStatus(status);
    } else {
      const { code } = await api("/api/enrol/finish", { frames });
      showEnrolled(code);
    }
  } catch (err) {
    if (current.mode === "order" && session === current) {
      // The order is still open and its request QR must stay up: show the refusal as a hint and listen on.
      if (err instanceof TypeError) sent.delete(key); // never reached the server: the same answer may be tried again
      keepCollecting(`${err.message}. Show the phone's answer again, or Send again.`);
    } else {
      stopStage();
      setStatus(err.message, "bad", "Server refused the answer. Send again to retry.");
    }
  } finally {
    finishing = false;
  }
}

/** Start collecting answer frames afresh without touching the request QR, the poll or the status. */
function keepCollecting(hint) {
  collector.reset();
  scanner.resetDedupe();
  setProgress(0, 0);
  $("hint").textContent = hint;
}

function showOrderStatus(status) {
  setStatus(status, statusKind(status), status === "PENDING" ? "Waiting for more approvals." : "");
  if (status === "PENDING") {
    // the answer is spent: start collecting afresh so the next frame cannot complete and re-send the old one.
    // (The `sent` set stops a still-visible answer QR from being POSTed twice in this order session.)
    collector.reset();
    scanner.resetDedupe();
    setProgress(0, 0);
  } else {
    stopStage();
    session = null;
  }
  refreshAudit();
}

function showEnrolled(code) {
  stopStage();
  session = null;
  $("code").textContent = code;
  setStatus("ENROLLED", "ok", "Check that the phone shows the same code.");
  refreshAudit();
}

// --------------------------------------------------------------------------- panels

async function beginEnrol() {
  $("code").textContent = "";
  try {
    const { frames } = await api("/api/enrol/begin", { user: $("enrolUser").value.trim() });
    beginSession({ mode: "enrol" }, "er");
    setStatus("PENDING", "pending", "Phone: scan this QR, then show its reply to the webcam.");
    startPlaying(frames);
    await startCamera();
  } catch (err) {
    stopStage();
    setStatus(err.message, "bad");
  }
}

async function sendOrder() {
  const tamper = $("tamper").checked;
  const sendButton = $("send");
  sendButton.disabled = true; // no second order while this request is in flight
  showFingerprint(""); // never leave the previous order's words next to a new or failed order
  try {
    let created;
    try {
      // inside the try so that a throwing applyTamper also re-enables the button
      const items = tamper ? applyTamper(payroll) : tamperOff(payroll); // the table always shows `payroll`
      created = await api("/api/orders", {
        maker: $("maker").value.trim(),
        approver: $("approver").value.trim(),
        ref: $("ref").value,
        items,
      });
    } finally {
      sendButton.disabled = false;
    }
    const { order_id, frames, fingerprint } = created;
    beginSession({ mode: "order", orderId: order_id }, "rs");
    showFingerprint(fingerprint);
    setStatus("PENDING", "pending", "Phone: scan this QR, check the order, then show the reply to the webcam.");
    startPlaying(frames);
    poll = setInterval(pollOrder, 1500);
    await startCamera();
  } catch (err) {
    stopStage();
    session = null;
    setStatus(err.message, "bad");
  }
}

/** The words the approver shows for the order that was actually submitted (tampered or not). */
function showFingerprint(words) {
  $("fingerprint").textContent = words || "";
  $("fpWrap").hidden = !words;
}

async function pollOrder() {
  if (!session || session.mode !== "order" || finishing) return;
  try {
    const { status } = await api(`/api/orders/${encodeURIComponent(session.orderId)}`);
    if (status !== "PENDING") showOrderStatus(status);
  } catch {
    /* transient, try again */
  }
}

async function refreshAudit() {
  try {
    const { ok, rows } = await api("/api/audit");
    const chain = $("chain");
    chain.textContent = ok ? `chain ok (${rows.length} events)` : "CHAIN BROKEN";
    chain.className = ok ? "ok" : "bad";
    const body = rows
      .map((row, i) => {
        const { kind, t, hash_hex, ...rest } = row;
        const detail = Object.entries(rest).map(([k, v]) => `${k}=${typeof v === "string" ? v : JSON.stringify(v)}`).join(" ");
        return `<tr><td>${i + 1}</td><td>${esc(kind)}</td><td>${esc(detail)}</td><td>${esc(hash_hex.slice(0, 12))}</td></tr>`;
      })
      .join("");
    $("auditRows").innerHTML = `<table><thead><tr><th>#</th><th>event</th><th>details</th><th>hash</th></tr></thead><tbody>${body}</tbody></table>`;
  } catch (err) {
    $("chain").textContent = err.message;
    $("chain").className = "bad";
  }
}

function esc(s) {
  return String(s).replace(/[&<>"]/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;" })[c]);
}

/** The operator-only tamper switch: always starts and resets unticked, and its label stands out while ticked. */
function setTamper(on) {
  $("tamper").checked = on;
  syncTamperLabel();
}

function syncTamperLabel() {
  $("tamperLabel").className = $("tamper").checked ? "armed" : "";
}

async function resetServer() {
  stopStage();
  session = null;
  setTamper(false);
  collector.reset();
  $("code").textContent = "";
  showFingerprint("");
  setProgress(0, 0);
  try {
    await api("/api/reset", {});
    setStatus("IDLE", "idle", "Server reset.");
    refreshAudit();
  } catch (err) {
    setStatus(err.message, "bad");
  }
}

async function devSubmit(path, kind) {
  const frames = frameLines($("devFrames").value);
  try {
    const result = await api(path, { frames });
    if (kind === "order") {
      if (session && session.mode === "order") session = null;
      stopStage();
      setStatus(result.status, statusKind(result.status), "dev paste");
    } else {
      showEnrolled(result.code);
      return;
    }
    refreshAudit();
  } catch (err) {
    setStatus(err.message, "bad", "dev paste");
  }
}

// --------------------------------------------------------------------------- start up

function renderPayroll() {
  $("rows").innerHTML = payroll
    .map(([name, account, tiyin], i) => `<tr><td>${i + 1}</td><td>${esc(name)}</td><td class="acct">${esc(account)}</td><td class="num">${esc(formatUzs(tiyin).replace(" UZS", ""))}</td></tr>`)
    .join("");
  $("total").textContent = formatUzs(totalTiyin(payroll));
}

async function init() {
  setTamper(false); // never trust restored form state: a ticked box would silently alter the order
  $("tamper").addEventListener("change", syncTamperLabel);
  $("enrolBegin").addEventListener("click", beginEnrol);
  $("send").addEventListener("click", sendOrder);
  $("auditFetch").addEventListener("click", refreshAudit);
  $("reset").addEventListener("click", resetServer);
  $("devAnswers").addEventListener("click", () => devSubmit("/api/answers", "order"));
  $("devEnrol").addEventListener("click", () => devSubmit("/api/enrol/finish", "enrol"));
  $("fps").addEventListener("change", () => {
    ensureOptions();
    if (player.frames.length) player.play(player.frames);
  });
  $("ecc").addEventListener("change", () => {
    ensureOptions();
    if (player.frames.length) player.play(player.frames);
  });
  try {
    payroll = await (await fetch("payroll.json")).json();
    renderPayroll();
  } catch (err) {
    setStatus("payroll.json: " + err.message, "bad");
  }
  player.stop(); // paint the canvas white
}

init();
