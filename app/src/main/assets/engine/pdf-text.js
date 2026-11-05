/*
 * lib/pdf-text.js  --  Κείμενο PDF με θέσεις (mupdf structured text), για ανάγνωση πεδίων «ετικέτα -> τιμή».
 * =============================================================================
 * Τα PDF της ΑΑΔΕ (Jasper/Oracle reports) έχουν τις τιμές σε ξεχωριστά text blocks, σε σειρά που ΔΕΝ ακολουθεί
 * τη σελίδα. Γι' αυτό διαβάζουμε γραμμές με bbox και βρίσκουμε την τιμή ΔΕΞΙΑ (ίδια γραμμή) ή ΚΑΤΩ από την ετικέτα.
 * Εξάρτηση: mupdf (ήδη στο package.json, lazy import όπως στο aade-tekmarto-fetch).
 */
'use strict';
const path = require('path');
const { pathToFileURL } = require('url');

let _mupdf;
async function loadMupdf() {
  if (_mupdf) return _mupdf;
  const p = path.join(__dirname, '..', 'node_modules', 'mupdf', 'dist', 'mupdf.js');
  try { _mupdf = await import(pathToFileURL(p).href); } catch (e) { throw new Error('mupdf missing (npm i mupdf)'); }
  return _mupdf;
}

// -> [{page, x, y, w, h, text}] για όλες τις σελίδες
async function pdfLines(buf) {
  const mupdf = await loadMupdf();
  const doc = mupdf.Document.openDocument(new Uint8Array(buf), 'application/pdf');
  const out = [];
  for (let i = 0; i < doc.countPages(); i++) {
    const j = JSON.parse(doc.loadPage(i).toStructuredText().asJSON());
    for (const bl of j.blocks || []) for (const l of bl.lines || [])
      if ((l.text || '').trim()) out.push({ page: i, x: l.bbox.x, y: l.bbox.y, w: l.bbox.w, h: l.bbox.h, text: l.text.replace(/\s+/g, ' ').trim() });
  }
  return out;
}

const findLabel = (lines, label) => lines.find(l => (label instanceof RegExp ? label.test(l.text) : l.text === label));
// τιμή στην ΙΔΙΑ γραμμή (|Δy| ≤ tol), δεξιά της ετικέτας· προαιρετικό φίλτρο re
function valueRight(lines, label, re, tol = 4) {
  const L = findLabel(lines, label); if (!L) return null;
  const c = lines.filter(l => l !== L && l.page === L.page && Math.abs(l.y - L.y) <= tol && l.x > L.x + L.w - 2 && (!re || re.test(l.text)))
    .sort((a, b) => a.x - b.x)[0];
  return c ? c.text : null;
}
// τιμή ΚΑΤΩ από την ετικέτα (μέσα σε maxDy), με οριζόντια επικάλυψη
function valueBelow(lines, label, re, maxDy = 40) {
  const L = findLabel(lines, label); if (!L) return null;
  const c = lines.filter(l => l.page === L.page && l.y > L.y + 1 && l.y - L.y <= maxDy && l.x < L.x + L.w + 20 && l.x + l.w > L.x - 20 && (!re || re.test(l.text)))
    .sort((a, b) => a.y - b.y)[0];
  return c ? c.text : null;
}

module.exports = { pdfLines, valueRight, valueBelow, findLabel };
