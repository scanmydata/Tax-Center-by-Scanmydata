/*
 * lib/kad-average.js  --  Μέσος όρος τζίρου ανά ΚΑΔ (πίνακας 28Α) — reference dataset
 * =============================================================================
 * Ο «δημοσιευμένος πίνακας μέσων όρων ΚΑΔ» που χρησιμοποιεί το 28Α (προσαύξηση τζίρου =
 * (τζίρος − μέσος ΚΑΔ) × 5%). ΠΗΓΗ: είναι **hardcoded μέσα στο κάθε D-DLL** — μέθοδος
 * `bKad_Click` → `KadDt` (στήλες KAD / DESCR / COUNT / AMOUNT· AMOUNT = μέσος τζίρος κλάδου).
 * Εξήχθη 1:1 από τα decompiled D24/D25/D26 στα `data/kad-averages-{2023,2024,2025}.json`.
 * Δεν κατεβαίνει runtime από ΑΑΔΕ — το Epsilon ενσωματώνει τον επίσημο πίνακα ανά έτος.
 *
 * Ο πίνακας είναι σε επίπεδο ΚΑΔ-κλάσης «NN.NN» (π.χ. 01.61). ΑΦΜ με πιο αναλυτικό ΚΑΔ
 * (π.χ. 01.61.10.01) ανάγεται αυτόματα στην κλάση NN.NN.
 */
'use strict';
const fs = require('fs');
const path = require('path');

const DATA_DIR = path.join(__dirname, '..', 'data');
const cache = {};
function load(year) {
  const y = String(year);
  if (cache[y]) return cache[y];
  const f = path.join(DATA_DIR, 'kad-averages-' + y + '.json');
  if (!fs.existsSync(f)) return null;
  cache[y] = JSON.parse(fs.readFileSync(f, 'utf8'));
  return cache[y];
}
function availableYears() {
  try { return fs.readdirSync(DATA_DIR).map(f => (f.match(/^kad-averages-(\d{4})\.json$/) || [])[1]).filter(Boolean).map(Number).sort(); }
  catch (e) { return []; }
}
// «01.61.10.01» / «0161» / «01,61» → «01.61» (κλάση NN.NN)
function normalizeKad(kad) {
  const digits = String(kad || '').replace(/[^0-9]/g, '');
  if (digits.length < 4) return null;
  return digits.slice(0, 2) + '.' + digits.slice(2, 4);
}

// getKadAverage(kad, year) → {kad, descr, count, amount, year} | null
function getKadAverage(kad, year) {
  const ds = load(year); if (!ds) return null;
  const key = normalizeKad(kad); if (!key) return null;
  const row = ds.kad[key]; if (!row) return null;
  return { kad: key, descr: row.descr, count: row.count, amount: row.amount, year: ds.year };
}
// αναζήτηση με κείμενο περιγραφής ή prefix ΚΑΔ
function searchKad(query, year) {
  const ds = load(year); if (!ds) return [];
  const q = String(query || '').trim().toUpperCase();
  const out = [];
  for (const [k, v] of Object.entries(ds.kad)) {
    if (k.startsWith(q) || v.descr.toUpperCase().includes(q)) out.push({ kad: k, descr: v.descr, count: v.count, amount: v.amount });
  }
  return out;
}
function listKad(year) {
  const ds = load(year); if (!ds) return [];
  return Object.entries(ds.kad).map(([k, v]) => ({ kad: k, descr: v.descr, count: v.count, amount: v.amount }));
}

module.exports = { getKadAverage, searchKad, listKad, availableYears, normalizeKad };

// CLI:  node lib/kad-average.js 01.61 2025    |    node lib/kad-average.js --search ΑΡΤΟΠΟΙΙΑ 2025
if (require.main === module) {
  const a = process.argv.slice(2);
  if (!a.length) { console.log('Έτη: ' + availableYears().join(', ') + '\nΧρήση: node lib/kad-average.js <ΚΑΔ> <έτος> | --search <κείμενο> <έτος>'); process.exit(0); }
  if (a[0] === '--search') {
    const year = a[2] || availableYears().slice(-1)[0];
    const res = searchKad(a[1], year);
    console.log(res.length + ' αποτελέσματα (' + year + '):');
    for (const r of res.slice(0, 40)) console.log('  ' + r.kad + '  μέσος ' + r.amount.toFixed(2) + '€  (n=' + r.count + ')  ' + r.descr);
  } else {
    const year = a[1] || availableYears().slice(-1)[0];
    const r = getKadAverage(a[0], year);
    console.log(r ? JSON.stringify(r, null, 2) : 'Δεν βρέθηκε ΚΑΔ ' + a[0] + ' για ' + year);
  }
}
