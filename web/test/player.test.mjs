import { test } from "node:test";
import assert from "node:assert/strict";

import { nextIndex } from "../player.js";
import { applyTamper, tamperOff, TAMPER_ROW, FrameCollector, parseFrame, totalTiyin, formatUzs } from "../logic.js";

const PAYROLL = Array.from({ length: 12 }, (_, i) => [
  `Person ${i + 1}`,
  `8600${String(i + 1).padStart(16, "0")}`,
  1000 + i,
]);

test("nextIndex wraps around", () => {
  assert.equal(nextIndex(0, 3), 1);
  assert.equal(nextIndex(1, 3), 2);
  assert.equal(nextIndex(2, 3), 0);
  assert.equal(nextIndex(0, 1), 0);
  assert.equal(nextIndex(5, 0), 0);
});

test("applyTamper changes exactly one account's last digit, returns a new array", () => {
  const copy = JSON.parse(JSON.stringify(PAYROLL));
  const out = applyTamper(PAYROLL);
  assert.notEqual(out, PAYROLL);
  assert.deepEqual(PAYROLL, copy, "input not mutated");
  assert.equal(out.length, PAYROLL.length);
  const changed = out.map((row, i) => i).filter((i) => out[i][1] !== PAYROLL[i][1]);
  assert.deepEqual(changed, [TAMPER_ROW]);
  assert.equal(TAMPER_ROW, 6, "7th row");
  const [a, b] = [PAYROLL[6][1], out[6][1]];
  assert.equal(b.slice(0, -1), a.slice(0, -1));
  assert.equal(Number(b.at(-1)), (Number(a.at(-1)) + 1) % 10);
  out.forEach((row, i) => {
    assert.equal(row[0], PAYROLL[i][0]);
    assert.equal(row[2], PAYROLL[i][2]);
  });
});

test("applyTamper wraps 9 to 0", () => {
  const items = PAYROLL.map((r) => [...r]);
  items[6][1] = "86000000000000000009";
  assert.equal(applyTamper(items)[6][1], "86000000000000000000");
});

test("tamperOff returns the original values", () => {
  const out = tamperOff(PAYROLL);
  assert.deepEqual(out, PAYROLL);
  assert.notEqual(out, PAYROLL);
  out[0][0] = "x";
  assert.notEqual(PAYROLL[0][0], "x", "deep copy");
});

test("totals and UZS formatting", () => {
  assert.equal(totalTiyin([["a", "1", 150], ["b", "2", 250]]), 400);
  assert.equal(formatUzs(380050000), "3,800,500.00 UZS");
  assert.equal(formatUzs(5), "0.05 UZS");
});

test("parseFrame accepts IMQ1 frames and rejects junk", () => {
  assert.deepEqual(parseFrame("IMQ1.rs.abcd1234.2.3.AAAA"), { kind: "rs", sid: "abcd1234", i: 2, n: 3 });
  assert.equal(parseFrame("IMQ1.xx.abcd1234.1.1.AA"), null);
  assert.equal(parseFrame("IMQ1.rs.abcd1234.0.3.AA"), null);
  assert.equal(parseFrame("IMQ1.rs.abcd1234.4.3.AA"), null);
  assert.equal(parseFrame("IMQ1.rs.abcd1234.1.257.AA"), null);
  assert.equal(parseFrame("IMQ1.rs.abcd1234.1.x.AA"), null);
  assert.equal(parseFrame("hello"), null);
});

test("FrameCollector completes only when all n distinct indices of one session are present", () => {
  const c = new FrameCollector();
  const f = (sid, i, n) => `IMQ1.rs.${sid}.${i}.${n}.QQ`;
  let s = c.add(f("aaaa0001", 2, 3));
  assert.deepEqual([s.have, s.n, s.complete], [1, 3, false]);
  s = c.add(f("aaaa0001", 2, 3));
  assert.equal(s.have, 1, "repeats do not count twice");
  s = c.add(f("aaaa0001", 1, 3));
  assert.equal(s.have, 2);
  s = c.add(f("aaaa0001", 3, 3));
  assert.equal(s.complete, true);
  assert.deepEqual(s.frames.map((x) => x.split(".")[3]), ["1", "2", "3"], "ordered by index");
  assert.equal(s.kind, "rs");
});

test("FrameCollector restarts when a different session id shows up, ignores junk", () => {
  const c = new FrameCollector();
  c.add("IMQ1.rs.aaaa0001.1.2.QQ");
  let s = c.add("not a frame");
  assert.equal(s.have, 1);
  s = c.add("IMQ1.rs.bbbb0002.1.2.QQ");
  assert.deepEqual([s.sid, s.have, s.n, s.complete], ["bbbb0002", 1, 2, false]);
  s = c.add("IMQ1.rs.bbbb0002.2.2.QQ");
  assert.equal(s.complete, true);
  c.reset();
  assert.equal(c.state().have, 0);
});

test("FrameCollector with a single-frame message completes at once", () => {
  const c = new FrameCollector();
  assert.equal(c.add("IMQ1.er.cccc0003.1.1.QQ").complete, true);
});

test("parseFrame requires exactly six dot-separated pieces (extra dots are invalid)", () => {
  assert.notEqual(parseFrame("IMQ1.rs.abcd1234.1.2.AAAA"), null);
  assert.equal(parseFrame("IMQ1.rs.abcd1234.1.2.AAAA.extra"), null);
  assert.equal(parseFrame("IMQ1.rs.abcd1234.1.2.AAAA."), null);
  assert.equal(parseFrame("IMQ1.rs.abcd1234.1.2"), null);
});

test("applyTamper refuses an account whose last character is not a digit", () => {
  for (const bad of ["8600000000000000000x", "", "8600 000 "]) {
    const items = PAYROLL.map((r) => [...r]);
    items[6][1] = bad;
    assert.throws(() => applyTamper(items), /digit/i, JSON.stringify(bad));
  }
  const items = PAYROLL.map((r) => [...r]);
  items[6][1] = 12345;
  assert.throws(() => applyTamper(items), /digit/i);
});
