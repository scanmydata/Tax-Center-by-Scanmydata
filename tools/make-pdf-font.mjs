#!/usr/bin/env node
/*
 * tools/make-pdf-font.mjs — η γραμματοσειρά των PDF που φτιάχνει η εφαρμογή.
 * =============================================================================
 * Ένα PDF με ελληνικά **πρέπει** να κουβαλά τη γραμματοσειρά του. Η εναλλακτική
 * (οι 14 «τυπικές» γραμματοσειρές του PDF) δεν έχει ελληνικά γλυφά, και ό,τι
 * φαίνεται σωστό σε έναν viewer βγαίνει κενά τετράγωνα σε άλλον.
 *
 * Η DejaVu Sans είναι 757 KB· ολόκληρη μέσα σε κάθε έντυπο θα έκανε ένα email
 * με τρεις καρτέλες ΚΕΑΟ βαρύτερο από δύο megabyte. Αυτό το εργαλείο κρατά
 * **μόνο τα γλυφά που χρησιμοποιούμε** και παράγει, δίπλα στη γραμματοσειρά,
 * έναν πίνακα μετρικών ώστε η εφαρμογή να μη χρειάζεται να διαβάσει TrueType.
 *
 * ## Γιατί εδώ και όχι στο κινητό
 *
 * Το subsetting είναι χειρουργική σε δυαδικούς πίνακες: λάθος checksum ή λάθος
 * loca και το PDF ανοίγει κενό — σε **μερικούς** viewers. Εδώ το αποτέλεσμα
 * ελέγχεται με τα μάτια πριν μπει στο αποθετήριο· στη συσκευή θα ήταν κώδικας
 * που δεν τρέχει ποτέ σε δοκιμή.
 *
 * ## Τι κρατιέται
 *
 * Τα 273 γλυφά που χρειαζόμαστε **ξαναριθμούνται από το μηδέν**: αλλιώς τα
 * `loca` και `hmtx` θα κουβαλούσαν από 24 KB θέσεις για 6.253 γλυφά που δεν
 * υπάρχουν πια. Η ξαναρίθμηση σημαίνει ότι τα σύνθετα γλυφά (το «ά» είναι
 * alpha συν tonos, όχι δικό του σχήμα) πρέπει να δείξουν στους νέους αριθμούς
 * των εξαρτημάτων τους — γι' αυτό το `components()` επιστρέφει και θέσεις.
 *
 * Ο `cmap` δεν αντιγράφεται καθόλου: το PDF δίνει κατευθείαν αριθμούς γλυφών
 * (`/CIDToGIDMap /Identity`) και τη μετάφραση χαρακτήρα σε γλυφό την κάνει η
 * εφαρμογή από το `.json` που παράγεται εδώ δίπλα.
 *
 * Χρήση:
 *   node tools/make-pdf-font.mjs [--src <φάκελος με DejaVuSans*.ttf>]
 *
 * Πηγή: DejaVu Sans 2.37 (Bitstream Vera + DejaVu license — επιτρέπει ρητά την
 * ενσωμάτωση σε έγγραφα). Η άδεια αντιγράφεται δίπλα στα αρχεία.
 */
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const HERE = path.dirname(fileURLToPath(import.meta.url));
const OUT = path.resolve(HERE, '../app/src/main/assets/fonts');
const argSrc = process.argv.indexOf('--src');
const SRC = argSrc > 0
  ? process.argv[argSrc + 1]
  : 'C:/Users/antonis/Documents/mydata-etimologio-bridge/assets/vendor';

// --------------------------------------------------------------- ο χαρακτήρες
//
// Ό,τι μπορεί να γράψει το έντυπο: ελληνικά (μονοτονικά), λατινικά, ψηφία,
// σημεία στίξης, το ευρώ και οι παύλες/εισαγωγικά που βάζουν οι πύλες.
function charset() {
  const cps = new Set();
  const add = (from, to) => { for (let c = from; c <= (to || from); c++) cps.add(c); };
  add(0x20, 0x7e);            // ASCII
  add(0xa0);                  // αδιάσπαστο κενό — έρχεται από HTML της ΑΑΔΕ
  add(0xa9); add(0xab); add(0xae); add(0xb0); add(0xb1); add(0xb7); add(0xbb);
  add(0xc0, 0xff);            // λατινικά με τόνους
  add(0x0384, 0x038a); add(0x038c); add(0x038e, 0x03a1); add(0x03a3, 0x03ce);
  add(0x2010, 0x2015);        // παύλες
  add(0x2018, 0x201d);        // εισαγωγικά
  add(0x2020, 0x2022); add(0x2026); add(0x2030);
  add(0x20ac);                // €
  add(0x2192);                // →
  return [...cps].sort((a, b) => a - b);
}

// ------------------------------------------------------------- ανάγνωση TTF
class Reader {
  constructor(buf) { this.b = buf; }
  u8(o) { return this.b.readUInt8(o); }
  u16(o) { return this.b.readUInt16BE(o); }
  i16(o) { return this.b.readInt16BE(o); }
  u32(o) { return this.b.readUInt32BE(o); }
  tag(o) { return this.b.toString('latin1', o, o + 4); }
}

function tables(r) {
  const out = {};
  const n = r.u16(4);
  for (let i = 0; i < n; i++) {
    const p = 12 + i * 16;
    out[r.tag(p)] = { off: r.u32(p + 8), len: r.u32(p + 12) };
  }
  return out;
}

/** cmap μορφής 4 (BMP) -> χάρτης κωδικού σε αριθμό γλυφού. */
function cmap4(r, t) {
  // Η DejaVu έχει πέντε υποπίνακες, από τους οποίους οι δύο τελευταίοι είναι
  // μορφής 12 (πέρα από το BMP). Εμείς θέλουμε ρητά τον (3,1) μορφής 4 — ό,τι
  // γράφουμε ζει ολόκληρο κάτω από το 0xFFFF.
  const n = r.u16(t.off + 2);
  let sub = 0;
  for (let i = 0; i < n; i++) {
    const p = t.off + 4 + i * 8;
    const plat = r.u16(p), enc = r.u16(p + 2), off = r.u32(p + 4);
    if (r.u16(t.off + off) !== 4) continue;
    if (!sub || (plat === 3 && enc === 1)) sub = t.off + off;
  }
  if (!sub) throw new Error('δεν βρέθηκε cmap μορφής 4');
  const segX2 = r.u16(sub + 6);
  const ends = sub + 14, starts = ends + segX2 + 2, deltas = starts + segX2, ranges = deltas + segX2;
  return (cp) => {
    for (let s = 0; s < segX2; s += 2) {
      if (cp > r.u16(ends + s)) continue;
      const start = r.u16(starts + s);
      if (cp < start) return 0;
      const ro = r.u16(ranges + s);
      if (ro === 0) return (cp + r.i16(deltas + s)) & 0xffff;
      const gp = ranges + s + ro + (cp - start) * 2;
      const g = r.u16(gp);
      return g === 0 ? 0 : (g + r.i16(deltas + s)) & 0xffff;
    }
    return 0;
  };
}

function locaOf(r, t, numGlyphs, longFormat) {
  const out = new Array(numGlyphs + 1);
  for (let i = 0; i <= numGlyphs; i++) {
    out[i] = longFormat ? r.u32(t.loca.off + i * 4) : r.u16(t.loca.off + i * 2) * 2;
  }
  return out;
}

/**
 * Τα εξαρτήματα ενός σύνθετου γλυφού, με τη **θέση** τους μέσα στο γλυφό.
 *
 * Χωρίς αυτά το «ά» βγαίνει κενό: στη DejaVu είναι alpha συν tonos, όχι δικό
 * του σχήμα. Και επειδή ξαναριθμούμε τα γλυφά, χρειαζόμαστε και το πού
 * γράφεται ο αριθμός για να τον διορθώσουμε.
 */
function components(r, start, end) {
  if (end - start < 10 || r.i16(start) >= 0) return [];
  const out = [];
  let p = start + 10;
  for (;;) {
    const flags = r.u16(p);
    out.push({ gid: r.u16(p + 2), at: p + 2 - start });
    p += 4 + ((flags & 0x0001) ? 4 : 2);
    if (flags & 0x0008) p += 2;
    else if (flags & 0x0040) p += 4;
    else if (flags & 0x0080) p += 8;
    if (!(flags & 0x0020)) break;
    if (p >= end) break;
  }
  return out;
}

// ------------------------------------------------------------- γράψιμο TTF
// Ό,τι αντιγράφεται αυτούσιο. Το `cmap` **δεν** χρειάζεται: το PDF δηλώνει
// `/CIDToGIDMap /Identity` και δίνει απευθείας αριθμούς γλυφών — τη μετάφραση
// χαρακτήρα σε γλυφό την κάνει η εφαρμογή από τον πίνακα μετρικών.
const KEEP = ['cvt ', 'fpgm', 'prep'];

function checksum(buf) {
  let sum = 0;
  const padded = buf.length % 4 ? Buffer.concat([buf, Buffer.alloc(4 - (buf.length % 4))]) : buf;
  for (let i = 0; i < padded.length; i += 4) sum = (sum + padded.readUInt32BE(i)) >>> 0;
  return sum;
}

function buildFont(src) {
  const r = new Reader(src);
  const t = tables(r);
  for (const need of ['head', 'hhea', 'maxp', 'hmtx', 'loca', 'glyf', 'cmap']) {
    if (!t[need]) throw new Error('λείπει ο πίνακας ' + need);
  }
  const unitsPerEm = r.u16(t.head.off + 18);
  const longLoca = r.i16(t.head.off + 50) === 1;
  const numGlyphs = r.u16(t.maxp.off + 4);
  const numHMetrics = r.u16(t.hhea.off + 34);
  const loca = locaOf(r, t, numGlyphs, longLoca);
  const lookup = cmap4(r, t.cmap);

  // ποια γλυφά κρατάμε
  const keep = new Set([0]);
  const chars = [];
  for (const cp of charset()) {
    const gid = lookup(cp);
    if (!gid || gid >= numGlyphs) continue;
    keep.add(gid);
    chars.push([cp, gid]);
  }
  // κλείσιμο συνθέτων
  const queue = [...keep];
  while (queue.length) {
    const gid = queue.pop();
    for (const c of components(r, t.glyf.off + loca[gid], t.glyf.off + loca[gid + 1])) {
      if (!keep.has(c.gid) && c.gid < numGlyphs) { keep.add(c.gid); queue.push(c.gid); }
    }
  }

  // Ξαναρίθμηση: 6.253 γλυφά σημαίνουν `loca` και `hmtx` 24 KB το καθένα, για
  // 274 που κρατάμε. Με νέους αριθμούς η γραμματοσειρά πέφτει κάτω από 40 KB —
  // και μπαίνει ολόκληρη σε κάθε PDF που στέλνουμε.
  const order = [...keep].sort((a, b) => a - b);
  const map = new Map(order.map((gid, i) => [gid, i]));

  const pieces = [];
  const newLoca = Buffer.alloc((order.length + 1) * 4);
  let at = 0;
  order.forEach((gid, i) => {
    newLoca.writeUInt32BE(at, i * 4);
    const from = t.glyf.off + loca[gid], to = t.glyf.off + loca[gid + 1];
    if (to <= from) return;
    let piece = Buffer.from(src.subarray(from, to));
    // Τα σύνθετα δείχνουν σε αριθμούς γλυφών: πρέπει να δείξουν στους νέους.
    for (const c of components(r, from, to)) piece.writeUInt16BE(map.get(c.gid), c.at);
    if (piece.length % 4) piece = Buffer.concat([piece, Buffer.alloc(4 - (piece.length % 4))]);
    pieces.push(piece);
    at += piece.length;
  });
  newLoca.writeUInt32BE(at, order.length * 4);
  const glyf = Buffer.concat(pieces);

  const widthOf = (gid) => r.u16(t.hmtx.off + Math.min(gid, numHMetrics - 1) * 4);
  const lsbOf = (gid) => (gid < numHMetrics ? r.i16(t.hmtx.off + gid * 4 + 2) : 0);
  const hmtx = Buffer.alloc(order.length * 4);
  order.forEach((gid, i) => {
    hmtx.writeUInt16BE(widthOf(gid), i * 4);
    hmtx.writeInt16BE(lsbOf(gid), i * 4 + 2);
  });

  const head = Buffer.from(src.subarray(t.head.off, t.head.off + t.head.len));
  head.writeUInt32BE(0, 8);              // checkSumAdjustment: μηδέν πριν το τέλος
  head.writeInt16BE(1, 50);              // indexToLocFormat: μακρύ loca

  const hhea = Buffer.from(src.subarray(t.hhea.off, t.hhea.off + t.hhea.len));
  hhea.writeUInt16BE(order.length, 34);  // numberOfHMetrics

  const maxp = Buffer.from(src.subarray(t.maxp.off, t.maxp.off + t.maxp.len));
  maxp.writeUInt16BE(order.length, 4);   // numGlyphs

  const out = { glyf, loca: newLoca, head, hhea, hmtx, maxp };
  for (const tag of KEEP) {
    if (!t[tag]) continue;
    out[tag] = Buffer.from(src.subarray(t[tag].off, t[tag].off + t[tag].len));
  }

  // --- συναρμολόγηση
  const tags = Object.keys(out).sort();
  const numTables = tags.length;
  const dir = Buffer.alloc(12 + numTables * 16);
  dir.writeUInt32BE(0x00010000, 0);
  dir.writeUInt16BE(numTables, 4);
  const pow = Math.floor(Math.log2(numTables));
  dir.writeUInt16BE(16 * 2 ** pow, 6);
  dir.writeUInt16BE(pow, 8);
  dir.writeUInt16BE(numTables * 16 - 16 * 2 ** pow, 10);

  let offset = dir.length;
  const body = [];
  tags.forEach((tag, i) => {
    const data = out[tag];
    const p = 12 + i * 16;
    dir.write(tag, p, 4, 'latin1');
    dir.writeUInt32BE(checksum(data), p + 4);
    dir.writeUInt32BE(offset, p + 8);
    dir.writeUInt32BE(data.length, p + 12);
    const padded = data.length % 4 ? Buffer.concat([data, Buffer.alloc(4 - (data.length % 4))]) : data;
    body.push(padded);
    offset += padded.length;
  });
  const font = Buffer.concat([dir, ...body]);
  // checkSumAdjustment: το ίδιο το αρχείο πρέπει να αθροίζει στο μαγικό νούμερο
  const headIndex = tags.indexOf('head');
  const headOffset = dir.readUInt32BE(12 + headIndex * 16 + 8);
  font.writeUInt32BE((0xb1b0afba - checksum(font)) >>> 0, headOffset + 8);

  // --- μετρικές για την εφαρμογή
  const scale = (v) => Math.round((v * 1000) / unitsPerEm);
  const glyphs = [];
  for (const [cp, gid] of chars) glyphs.push(cp, map.get(gid), scale(widthOf(gid)));

  const os2 = t['OS/2'];
  const metrics = {
    source: 'DejaVu Sans 2.37 (subset)',
    unitsPerEm,
    numGlyphs: order.length,
    ascent: scale(r.i16(t.hhea.off + 4)),
    descent: scale(r.i16(t.hhea.off + 6)),
    capHeight: os2 && os2.len >= 90 ? scale(r.i16(os2.off + 88)) : 700,
    bbox: [
      scale(r.i16(t.head.off + 36)), scale(r.i16(t.head.off + 38)),
      scale(r.i16(t.head.off + 40)), scale(r.i16(t.head.off + 42)),
    ],
    glyphs,
  };
  return { font, metrics, kept: order.length, numGlyphs };
}

// ------------------------------------------------------------------- εκτέλεση
fs.mkdirSync(OUT, { recursive: true });
const jobs = [
  ['DejaVuSans.ttf', 'report-regular'],
  ['DejaVuSans-Bold.ttf', 'report-bold'],
];
for (const [file, name] of jobs) {
  const src = fs.readFileSync(path.join(SRC, file));
  const { font, metrics, kept, numGlyphs } = buildFont(src);
  fs.writeFileSync(path.join(OUT, name + '.ttf'), font);
  fs.writeFileSync(path.join(OUT, name + '.json'), JSON.stringify(metrics) + '\n');
  console.log(
    `  ${name}: ${(src.length / 1024).toFixed(0)} KB -> ${(font.length / 1024).toFixed(1)} KB` +
    `  (${kept} από ${numGlyphs} γλυφά, ${metrics.glyphs.length / 3} χαρακτήρες)`,
  );
}
console.log('\n  ✓ Έτοιμο: ' + path.relative(process.cwd(), OUT));
