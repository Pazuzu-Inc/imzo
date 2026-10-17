// Pure helpers shared by the page and the tests. No DOM access, no imports.

/** Index (0-based) of the payroll row the "infected laptop" tampers with: the 7th. */
export const TAMPER_ROW = 6;

const KINDS = ["rq", "rs", "en", "er"];

function copyItems(items) {
  return items.map((row) => [...row]);
}

/** Deep copy of the order exactly as shown on screen. */
export function tamperOff(items) {
  return copyItems(items);
}

/** New list in which the 7th row's account has its last digit changed (digit + 1 mod 10). */
export function applyTamper(items) {
  const out = copyItems(items);
  const row = out[TAMPER_ROW];
  if (!row) return out;
  const account = row[1];
  const last = (Number(account.at(-1)) + 1) % 10;
  row[1] = account.slice(0, -1) + String(last);
  return out;
}

export function totalTiyin(items) {
  return items.reduce((sum, row) => sum + row[2], 0);
}

/** tiyin -> "3,800,500.00 UZS" (fixed en-US grouping so the output does not depend on the locale). */
export function formatUzs(tiyin) {
  const whole = Math.floor(tiyin / 100);
  const frac = String(tiyin % 100).padStart(2, "0");
  return `${String(whole).replace(/\B(?=(\d{3})+(?!\d))/g, ",")}.${frac} UZS`;
}

/** Header fields of an IMQ1 frame, or null. Never looks at the payload: the page does not parse CBOR. */
export function parseFrame(text) {
  if (typeof text !== "string") return null;
  const p = text.split(".", 6);
  if (p.length !== 6 || p[0] !== "IMQ1" || !KINDS.includes(p[1])) return null;
  if (!/^[0-9]+$/.test(p[3]) || !/^[0-9]+$/.test(p[4])) return null;
  const i = Number(p[3]);
  const n = Number(p[4]);
  if (!(i >= 1 && i <= n && n <= 256)) return null;
  return { kind: p[1], sid: p[2], i, n };
}

/**
 * Collects scanned frames of one message (same kind and session id) until every index 1..n is present.
 * A frame of a different session replaces the one being collected (stale QR on screen, new message).
 */
export class FrameCollector {
  constructor() {
    this.reset();
  }

  reset() {
    this.kind = null;
    this.sid = null;
    this.n = 0;
    this.parts = new Map();
  }

  add(text) {
    const f = parseFrame(text);
    if (f) {
      if (this.sid !== null && (f.kind !== this.kind || f.sid !== this.sid || f.n !== this.n)) this.reset();
      this.kind = f.kind;
      this.sid = f.sid;
      this.n = f.n;
      this.parts.set(f.i, text);
    }
    return this.state();
  }

  state() {
    const have = this.parts.size;
    const complete = this.n > 0 && have === this.n;
    const frames = complete ? [...this.parts.keys()].sort((a, b) => a - b).map((i) => this.parts.get(i)) : [];
    return { kind: this.kind, sid: this.sid, n: this.n, have, complete, frames };
  }
}
