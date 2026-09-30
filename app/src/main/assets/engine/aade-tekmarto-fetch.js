/*
 * lib/aade-tekmarto-fetch.js  --  Λήψη ανάλυσης 28Α από ΑΑΔΕ (E1 «Εσωτερικοί Πίνακες») + OCR + calc
 * =============================================================================
 * Οδηγεί το E1 online app (www1.aade.gr/webtax/incomefp) με browser, κατεβάζει το PDF
 * «Εσωτερικοί Πίνακες Ε1» (POST year{Y}-income-menuPrint.do, printSubTables), το κάνει OCR
 * (ελληνικά, mupdf render + tesseract.js), και ΠΑΡΣΑΡΕΙ τον πίνακα «Προσδιορισμός ελάχιστου
 * καθαρού εισοδήματος από επιχειρηματική δραστηριότητα» (28Α) → τροφοδοτεί `calcTekmarto`
 * (lib/tekmarto-min-income) για σταυρο-επαλήθευση με την επίσημη τιμή της ΑΑΔΕ.
 *
 * ΕΞΑΡΤΗΣΕΙΣ (lazy): playwright (browser), mupdf (render PDF→PNG), tesseract.js (Greek OCR).
 * Το OCR pipeline είναι LIVE-VERIFIED (καθαρή εξαγωγή ελληνικών πινάκων). Ο 28Α-parser είναι
 * label-based, βασισμένος στα ΑΚΡΙΒΗ labels του TaxSystem (fE1Tab6)· χρειάζεται live επαλήθευση
 * σε λογαριασμό που ΕΧΕΙ συμπληρωμένο πίνακα 28Α (οι δοκιμαστικοί δεν είχαν).
 *
 * Πηγή inputs (επιβεβαιωμένο): years/maxSalary/averageKad ← E1 «FieldsEKE» prefill· payrollCost/
 * turnover/ΚΑΔ ← Ε3 (F581/F023/F022). Βλ. out/methodologies/Tekmarto_min_income_28A.md.
 */
'use strict';
const fs = require('fs');
const path = require('path');
const { pathToFileURL } = require('url');
const { calcTekmarto } = require('./tekmarto-min-income');

const INCOMEFP = 'https://www1.aade.gr/webtax/incomefp/';

// ---------------------------------------------------------------------------
// 1) BROWSER: login E1 app + download «Εσωτερικοί Πίνακες Ε1» PDF
// ---------------------------------------------------------------------------
async function downloadEsoterikoiPinakes({ user, pass, docYear, outDir, log }) {
  log = log || (() => {});
  let withBrowser;
  try { ({ withBrowser } = require('./browser-step')); } catch (e) { return { ok: false, reason: 'browser-step missing' }; }
  fs.mkdirSync(outDir, { recursive: true });
  const pdfPath = path.join(outDir, 'esoterikoi_pinakes_' + docYear + '.pdf');

  const res = await withBrowser({ headed: false }, async ({ page, context }) => {
    await page.goto(INCOMEFP); await page.sleep(1500);
    // GSIS OAM login
    if (await page.count('input[name="username"]')) {
      log('[tekm-fetch] GSIS login');
      await page.fill('input[name="username"]', user);
      await page.fill('input[name="password"]', pass);
      if (await page.count('button[name="btn_login"]')) await page.clickNav('button[name="btn_login"]');
      else await page.clickNav('input[type="submit"]');
      await page.sleep(2500);
    }
    if (/name="username"/i.test(await page.content())) return { ok: false, reason: 'InvalidCredentials' };
    // enter the declarations portal
    if (await page.count('a[href*="login.done"]')) { await page.clickNav('a[href*="login.done"]'); await page.sleep(2500); }
    // (ο διαθέσιμος χρόνος στο app είναι πάντα το τρέχον· το docYear ελέγχεται μόνο για ονομασία)
    if (!(await page.count('button[name="PB_PRINT_SUBTABLES"]'))) {
      return { ok: false, reason: 'NoSubTablesButton (μήπως δεν υπάρχει υποβληθείσα δήλωση;)' };
    }
    log('[tekm-fetch] download «Εσωτερικοί Πίνακες Ε1»');
    const d = await page.expectDownload(async () => { await page.raw.locator('button[name="PB_PRINT_SUBTABLES"]').click(); }, pdfPath, 90000);
    return { ok: true, pdfPath: d.path };
  });
  if (!res || !res.ok) { log('[tekm-fetch] FAILED: ' + (res && res.reason)); return res || { ok: false, reason: 'BrowserError' }; }
  const size = fs.statSync(res.pdfPath).size;
  log('[tekm-fetch] PDF -> ' + path.basename(res.pdfPath) + ' (' + size + 'b)');
  return { ok: true, pdfPath: res.pdfPath, bytes: size };
}

// ---------------------------------------------------------------------------
// 2) OCR: render PDF→PNG (mupdf) + Greek OCR (tesseract.js). Returns [{page, text}]
// ---------------------------------------------------------------------------
async function ocrPdf(pdfPath, { zoom = 3, lang = 'ell', log } = {}) {
  log = log || (() => {});
  const NM = path.join(__dirname, '..', 'node_modules');
  let mupdf, createWorker;
  try { mupdf = await import(pathToFileURL(path.join(NM, 'mupdf/dist/mupdf.js')).href); }
  catch (e) { return { ok: false, reason: 'mupdf missing (npm i mupdf)' }; }
  try { ({ createWorker } = require(path.join(NM, 'tesseract.js'))); }
  catch (e) { return { ok: false, reason: 'tesseract.js missing (npm i tesseract.js)' }; }

  const buf = fs.readFileSync(pdfPath);
  const doc = mupdf.Document.openDocument(new Uint8Array(buf), 'application/pdf');
  const n = doc.countPages();
  const worker = await createWorker(lang);
  const mat = mupdf.Matrix.scale(zoom, zoom);
  const pages = [];
  const tmp = pdfPath + '.page.png';
  try {
    for (let i = 0; i < n; i++) {
      const pix = doc.loadPage(i).toPixmap(mat, mupdf.ColorSpace.DeviceRGB, false, true);
      fs.writeFileSync(tmp, Buffer.from(pix.asPNG()));
      const { data: { text } } = await worker.recognize(tmp);
      pages.push({ page: i, text });
    }
  } finally { await worker.terminate(); try { fs.unlinkSync(tmp); } catch (e) {} }
  log('[tekm-fetch] OCR ' + n + ' σελ.');
  return { ok: true, pages };
}

// ---------------------------------------------------------------------------
// 3) PARSE 28Α table (label-based, από τα labels του TaxSystem fE1Tab6)
//    Ελληνικός αριθμός «12.320,00» → number. Ψάχνει labels & τη γραμμή/επόμενο ποσό.
// ---------------------------------------------------------------------------
function grNum(s) {
  const m = (s || '').match(/-?\d{1,3}(?:\.\d{3})*(?:,\d+)?|-?\d+(?:,\d+)?/);
  if (!m) return null; const n = parseFloat(m[0].replace(/\./g, '').replace(',', '.'));
  return Number.isFinite(n) ? n : null;
}
// βρες την τιμή που ακολουθεί ένα label (ίδια ή επόμενες γραμμές)
function valAfterLabel(text, labelRe) {
  const lines = text.split(/\r?\n/);
  for (let i = 0; i < lines.length; i++) {
    if (labelRe.test(lines[i])) {
      // ποσό στην ίδια γραμμή μετά το label;
      const after = lines[i].replace(labelRe, '');
      let v = grNum(after);
      if (v != null) return v;
      // αλλιώς στις επόμενες 2 γραμμές
      for (let j = 1; j <= 2 && i + j < lines.length; j++) { v = grNum(lines[i + j]); if (v != null) return v; }
    }
  }
  return null;
}
// Επιστρέφει τα inputs 28Α αν εντοπιστεί ο πίνακας, αλλιώς {found:false}
function parse28A(ocrText) {
  const T = ocrText.replace(/[«»]/g, '');
  const hasTable = /Προσδιορισμ[όο]ς ελ[άα]χιστου καθαρο[ύυ] εισοδ[ήη]ματος|ελ[άα]χιστου ποσο[ύυ] καθαρο[ύυ] εισοδ[ήη]ματος|Τεκμαρτ[ήη] Φορολ[όο]γηση/i.test(T)
    || (/Χρ[όο]νια ασκ/i.test(T) && /κατ[ώω]τατ/i.test(T));
  if (!hasTable) return { found: false };
  const out = {
    found: true,
    years: valAfterLabel(T, /Χρ[όο]νια ασκ[^\n]*επιχ/i),
    minWage: valAfterLabel(T, /Ετ[ήη]σιος κατ[ώω]τατος μικτ[όο]ς μισθ/i),
    maxSalary: valAfterLabel(T, /Μικτ[έε]ς αποδοχ[έε]ς υπαλλ[ήη]λου[^\n]*μεγαλ[ύυ]τερο/i),
    payrollCost: valAfterLabel(T, /Συνολικ[όο] κ[όο]στος μισθοδοσ/i),
    turnover: valAfterLabel(T, /Τζ[ίι]ρος ατομικ/i),
    averageKad: valAfterLabel(T, /Μ[έε]σος [όο]ρος ΚΑΔ/i),
    incomeEmployee: valAfterLabel(T, /Εισ[όο]δημα απ[όο] Μισθωτ/i),
    incomeFarm: valAfterLabel(T, /Εισ[όο]δημα απ[όο] αγροτικ/i),
    officialMinIncome: valAfterLabel(T, /Ελ[άα]χιστο Εισ[όο]δημα|Φορολογητ[έε]ο/i),
    officialTax: valAfterLabel(T, /Φ[όο]ρος Εισοδ[ήη]ματος/i),
  };
  return out;
}

// ---------------------------------------------------------------------------
// MAIN: fetch + OCR + parse + calc (cross-check)
// ---------------------------------------------------------------------------
async function fetchTekmarto({ user, pass, year, outDir, log } = {}) {
  log = log || (() => {});
  const incomeYear = Number(year) || (new Date().getFullYear() - 1);
  const docYear = incomeYear + 1;
  const dl = await downloadEsoterikoiPinakes({ user, pass, docYear, outDir, log });
  if (!dl.ok) return dl;
  const ocr = await ocrPdf(dl.pdfPath, { log });
  if (!ocr.ok) return { ok: false, reason: ocr.reason, pdfPath: dl.pdfPath };
  const fullText = ocr.pages.map(p => p.text).join('\n');
  fs.writeFileSync(dl.pdfPath.replace(/\.pdf$/, '.ocr.txt'), fullText);
  const parsed = parse28A(fullText);
  if (!parsed.found) {
    log('[tekm-fetch] Δεν βρέθηκε πίνακας 28Α στους Εσωτερικούς Πίνακες (ίσως δεν υπόκειται / δεν συμπληρώθηκε).');
    return { ok: true, pdfPath: dl.pdfPath, has28A: false, year: incomeYear };
  }
  // τοπικός επανυπολογισμός για σταυρο-επαλήθευση
  const calc = calcTekmarto({
    year: incomeYear, years: parsed.years, maxSalary: parsed.maxSalary, payrollCost: parsed.payrollCost,
    turnover: parsed.turnover, averageKad: parsed.averageKad, incomeEmployee: parsed.incomeEmployee,
    incomeFarm: parsed.incomeFarm, reduceRight: 1,
  });
  const match = parsed.officialMinIncome != null && Math.abs(calc.taxTaxable - parsed.officialMinIncome) < 1;
  log('[tekm-fetch] 28Α: official=' + parsed.officialMinIncome + ' calc=' + calc.taxTaxable + (match ? ' ✅ match' : ' ⚠ διαφορά'));
  return { ok: true, pdfPath: dl.pdfPath, has28A: true, year: incomeYear, parsed, calc, match };
}

module.exports = { fetchTekmarto, downloadEsoterikoiPinakes, ocrPdf, parse28A, grNum };

// CLI:  node lib/aade-tekmarto-fetch.js --user U --pass P --year 2025 --out ./out
if (require.main === module) {
  (async () => {
    const a = process.argv.slice(2); const opt = {};
    for (let i = 0; i < a.length; i++) { const m = a[i].match(/^--(.+)$/); if (m) opt[m[1]] = (a[i + 1] && !a[i + 1].startsWith('--')) ? a[++i] : true; }
    const r = await fetchTekmarto({ user: opt.user || process.env.AADE_USER, pass: opt.pass || process.env.AADE_PASS,
      year: opt.year || process.env.AADE_YEAR, outDir: opt.out || './downloads/tekmarto', log: (...x) => console.error(...x) });
    console.log(JSON.stringify(r && r.parsed ? { ok: r.ok, has28A: r.has28A, parsed: r.parsed, calc: r.calc, match: r.match } : r, null, 2));
    process.exit(r && r.ok ? 0 : 2);
  })();
}
