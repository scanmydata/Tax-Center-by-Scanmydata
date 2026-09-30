/*
 * configs/aade-tekmarto-eke.js  --  ΑΑΔΕ: ανάλυση ελάχιστου τεκμαρτού εισοδήματος 28Α (fetch + OCR + calc)
 * =============================================================================
 * Runner wrapper πάνω από `lib/aade-tekmarto-fetch.js`: οδηγεί το E1 online app (browser),
 * κατεβάζει το PDF «Εσωτερικοί Πίνακες Ε1», κάνει ελληνικό OCR, παρσάρει τον πίνακα 28Α, και
 * σταυρο-επαληθεύει με το `lib/tekmarto-min-income` (calc). Πηγές inputs: E1 «FieldsEKE» +
 * Ε3 (F581/F023/F022). Βλ. out/methodologies/Tekmarto_min_income_28A.md.
 *
 * ΕΞΑΡΤΗΣΕΙΣ: playwright, mupdf, tesseract.js (ήδη στο package.json).
 * INPUTS: TAXISnet user/pass + Έτος εισοδημάτων.
 * OUTPUT: esoterikoi_pinakes_<docYear>.pdf (+ .ocr.txt) + JSON ανάλυσης.
 */
'use strict';
const path = require('path');
const fs = require('fs');
const { fetchTekmarto } = require('../lib/aade-tekmarto-fetch');

module.exports = {
  id: 'aade-tekmarto-eke',
  title: 'ΑΑΔΕ: ανάλυση ελάχιστου τεκμαρτού 28Α (E1 Εσωτ. Πίνακες + OCR + calc)',
  portal: 'AADE E1 webtax/incomefp (GSIS OAM, browser + OCR)',
  subsystem: 'OnLineTaxClearance / D-DLL fE1Tab6 (28Α)',
  actions: ['fetch', 'ocr', 'calc'],
  inputs: [
    { key: 'user', label: 'TAXISnet username', env: 'AADE_USER' },
    { key: 'pass', label: 'TAXISnet password', env: 'AADE_PASS', hidden: true },
    { key: 'year', label: 'Έτος εισοδημάτων (π.χ. 2025)', env: 'AADE_YEAR' },
  ],

  async run(http, inp) {
    const r = await fetchTekmarto({ user: inp.user, pass: inp.pass, year: inp.year, outDir: http.dlDir, log: http.log.bind(http) });
    if (!r.ok) { http.log('[tekmarto-eke] FAILED: ' + r.reason); return { ok: false, reason: r.reason }; }
    const files = [];
    if (r.pdfPath) files.push(path.basename(r.pdfPath));
    if (r.pdfPath && fs.existsSync(r.pdfPath.replace(/\.pdf$/, '.ocr.txt'))) files.push(path.basename(r.pdfPath).replace(/\.pdf$/, '.ocr.txt'));
    if (!r.has28A) { http.log('[tekmarto-eke] ✅ PDF+OCR OK, αλλά χωρίς πίνακα 28Α (δεν υπόκειται/δεν συμπληρώθηκε)'); return { ok: true, files, summary: { has28A: false, year: r.year } }; }
    const jf = path.join(http.dlDir, 'tekmarto_28A_' + r.year + '.json');
    fs.writeFileSync(jf, JSON.stringify({ parsed: r.parsed, calc: r.calc, match: r.match }, null, 2)); files.push(path.basename(jf));
    http.log('[tekmarto-eke] ✅ 28Α: official=' + r.parsed.officialMinIncome + ' calc=' + r.calc.taxTaxable + (r.match ? ' (match)' : ' (διαφορά!)'));
    return { ok: true, files, summary: { has28A: true, year: r.year, officialMinIncome: r.parsed.officialMinIncome, calcTaxable: r.calc.taxTaxable, match: r.match } };
  },
};
