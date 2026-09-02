/*
 * configs/aade-debts.js  --  Οφειλές ΑΑΔΕ (ληξιπρόθεσμες/εμπρόθεσμες) + Στοιχεία Πληρωμών + Ταυτότητες Οφειλής (PDF)
 * Portal: www1.aade.gr "Προσωποποιημένη Πληροφόρηση" (GSIS OAM login — lib.aadeLogin).
 *
 * Confirmed live from the real pages:
 *  - Debt rows carry BOTH a "Μη ληξιπρόθεσμο Υπόλοιπο" (εμπρόθεσμο) and a "Ληξιπρόθεσμο Υπόλοιπο" column,
 *    plus summary totals "Μη Ληξιπρόθεσμο/Ληξιπρόθεσμο Υπόλοιπο Οφειλών" -> we split per debt + report totals.
 *  - "Ταυτότητα Οφειλής" (ΤΟ) print: button showTObut_N -> doDisplayPaymentCode(form,"displayDebtCode.htm",
 *    returnView, mchDoy,mchDept,mchMctCode,mchSctCode,mchYear,mchSourceCode,mchMcNo,mcLineNo,index) -> GET
 *    displayDebtCode.htm?<those params>. getDoc() saves it as PDF if the server returns application/pdf.
 *
 * EVERY page's table strings are also saved to JSON (AADE_debts_<user>.json) for future use.
 * INPUTS: TAXISnet user/pass.
 */
'use strict';
const path = require('path');
const fs = require('fs');
const { renderMany, mergePdf, tableDoc } = require('../lib/render-pdf');

// installment (δόσεις) tables are already in the page HTML (hidden, revealed client-side):
// <table id="installmentInfo_N"> Ανάλυση Δόσεων (Α/Α δόσης, ημ/νία λήξης, υπόλοιπο, τόκοι, σύνολο, αναστολή)
function parseInstallments(html, strip) {
  const out = {};
  for (const m of html.matchAll(/<table[^>]*id="installmentInfo_(\d+)"[\s\S]*?<\/table>/gi)) {
    const rows = [...m[0].matchAll(/<tr\b[^>]*>([\s\S]*?)<\/tr>/gi)]
      .map(r => [...r[1].matchAll(/<t[dh]\b[^>]*>([\s\S]*?)<\/t[dh]>/gi)].map(c => strip(c[1]))).filter(c => c.some(x => x));
    if (rows.length > 1) out[m[1]] = { headers: rows[0], rows: rows.slice(1) };
  }
  return out;
}

const BASE = '/taxisnet/info/protected/';
const PAGES = [
  { key: 'debts_unregulated', title: 'Οφειλές εκτός Ρύθμισης και Πληρωμή', url: BASE + 'displayDebtInfoAndPay.htm', debts: true },
  { key: 'debts_arrangement', title: 'Οφειλές σε Ρύθμιση και Πληρωμή', url: BASE + 'displayArrangementInfoAndPay.htm' },
  { key: 'debts_coresponsible', title: 'Οφειλές από Συνυπευθυνότητα', url: BASE + 'displayCoResponsibleDebtInfo.htm' },
  { key: 'payments', title: 'Στοιχεία Πληρωμών', url: BASE + 'displayActualPaymentInfo.htm' },
  { key: 'returns', title: 'Επιστροφές', url: BASE + 'displayReturnInfo.htm' },
];
const TO_ARGS = ['action', 'returnView', 'mchDoy', 'mchDept', 'mchMctCode', 'mchSctCode', 'mchYear', 'mchSourceCode', 'mchMcNo', 'mcLineNo', 'index'];

const rowsOf = (html, strip) => [...html.matchAll(/<tr\b[^>]*>([\s\S]*?)<\/tr>/gi)]
  .map(r => [...r[1].matchAll(/<t[dh]\b[^>]*>([\s\S]*?)<\/t[dh]>/gi)].map(c => strip(c[1])))
  .filter(cells => cells.some(c => c));
const amt = (s) => { const m = String(s || '').match(/-?[\d.]+,\d{2}/); return m ? parseFloat(m[0].replace(/\./g, '').replace(',', '.')) : 0; };

module.exports = {
  id: 'aade-debts',
  title: 'Οφειλές ΑΑΔΕ (ληξιπρόθεσμες/εμπρόθεσμες) + Πληρωμές + Ταυτότητες Οφειλής',
  portal: 'AADE Προσωποποιημένη Πληροφόρηση (www1.aade.gr)',
  subsystem: 'Hyper.Server',
  actions: ['retrieve', 'download'],
  inputs: [
    { key: 'user', label: 'TAXISnet username', env: 'AADE_USER' },
    { key: 'pass', label: 'TAXISnet password', env: 'AADE_PASS', hidden: true },
    { key: 'doseis', label: 'Να συμπεριληφθούν οι δόσεις; (ναι/όχι)', env: 'AADE_DOSEIS' },
  ],

  async run(http, inp, lib) {
    const L = await lib.aadeLogin(http, inp);
    if (!L.ok) { http.log('AADE LOGIN FAILED: ' + L.reason); return { ok: false, reason: L.reason }; }
    const strip = lib.stripTags;
    const wantDoseis = /^(y|yes|ν|ναι|1|true)$/i.test((inp.doseis || '').trim()); // δόσεις optional
    const afm = (strip(L.page.text || '').match(/Α\.?Φ\.?Μ\.?\s*[:\-]?\s*(\d{9})/) || [])[1] || inp.user; // ΑΦΜ υπόχρεου
    const san = (s) => strip(String(s || '')).replace(/[€\s]+/g, '_').replace(/[^0-9A-Za-zΑ-Ωα-ωάέήίόύώϊϋΐΰ.,_\-]/g, '').replace(/_+/g, '_').replace(/^_|_$/g, '').slice(0, 70);
    const result = { portal: this.portal, afm, retrievedAt: new Date().toISOString(), sections: {} };
    const pdfs = [];
    const pending = []; // ΤΟ PDFs that get a δόσεις 2nd page merged in: { path, toBuf, doseisHtml }

    for (const p of PAGES) {
      try {
        const r = await http.follow('GET', new URL(p.url, L.AADE).toString());
        http.dump('page_' + p.key + '.html', r.text);
        const allRows = rowsOf(r.text, strip);
        const section = { title: p.title, rows: allRows };

        if (p.debts) {
          // summary totals (the ληξιπρόθεσμο / εμπρόθεσμο split at account level)
          section.totals = {
            emprothesmo_nonOverdue: (r.text.match(/Μη Ληξιπρόθεσμο Υπόλοιπο Οφειλών[\s\S]{0,120}?(-?[\d.]+,\d{2})/) || [])[1] || null,
            lixiprothesmo_overdue: (r.text.match(/(?<!Μη )Ληξιπρόθεσμο Υπόλοιπο Οφειλών[\s\S]{0,120}?(-?[\d.]+,\d{2})/) || [])[1] || null,
          };
          // column map from the header row (do NOT drop empty cells — alignment matters)
          const headerCells = (r.text.match(/<tr\b[^>]*>[\s\S]*?<\/tr>/gi) || [])
            .map(tr => [...tr.matchAll(/<t[dh]\b[^>]*>([\s\S]*?)<\/t[dh]>/gi)].map(c => strip(c[1])))
            .find(cs => cs.some(c => /Ημ\/νία Βεβαίωσης/.test(c)) && cs.some(c => /Ληξιπρόθεσμο Υπόλοιπο/.test(c))) || [];
          const colHdr = headerCells.map(h => h);
          const idxOverdue = headerCells.findIndex(c => /^Ληξιπρόθεσμο Υπόλοιπο/.test(c));
          const idxNon = headerCells.findIndex(c => /Μη ληξιπρόθεσμο Υπόλοιπο/.test(c));
          const idxTotal = headerCells.findIndex(c => /Συνολικό Ποσό/.test(c));
          section.columns = colHdr;

          // per-debt rows carry a "Ταυτότητα Οφειλής" print button (showTObut) -> parse its args
          const debts = [];
          for (const tr of r.text.match(/<tr\b[^>]*>[\s\S]*?<\/tr>/gi) || []) {
            const call = tr.match(/doDisplayPaymentCode\(document\.displayPaymentCodeForm\s*,([\s\S]*?)\)/i);
            if (!call) continue;
            const args = [...call[1].matchAll(/"([^"]*)"/g)].map(a => a[1]);
            const to = {}; TO_ARGS.forEach((k, i) => { to[k] = args[i]; });
            const cells = [...tr.matchAll(/<t[dh]\b[^>]*>([\s\S]*?)<\/t[dh]>/gi)].map(c => strip(c[1])); // keep alignment
            const fields = {}; colHdr.forEach((h, i) => { if (h) fields[h] = cells[i] != null ? cells[i] : ''; });
            const overdueAmt = idxOverdue >= 0 ? amt(cells[idxOverdue]) : 0;
            const overdue = overdueAmt > 0;                        // ληξιπρόθεσμη αν το «Ληξιπρόθεσμο Υπόλοιπο» > 0
            const instIdx = (tr.match(/showInstallmentInfoRadio_(\d+)/) || tr.match(/installmentInfo_(\d+)/) || [])[1];
            debts.push({ overdue, fields, cells, to, instIdx, nonOverdue: idxNon >= 0 ? cells[idxNon] : null, overdueBalance: idxOverdue >= 0 ? cells[idxOverdue] : null, total: idxTotal >= 0 ? cells[idxTotal] : null });
          }
          section.debts = debts;
          section.lixiprothesmes = debts.filter(d => d.overdue);   // overdue debts
          section.emprothesmes = debts.filter(d => !d.overdue);    // not-yet-due debts
          section.counts = { total: debts.length, lixiprothesmes: section.lixiprothesmes.length, emprothesmes: section.emprothesmes.length };

          // δόσεις (installments) — OPTIONAL (μόνο αν το ζητήσει ο χρήστης). Parse installmentInfo_N,
          // attach στο JSON + κράτα το HTML ώστε να μπει ως 2η σελίδα ΜΕΣΑ στο PDF της Ταυτότητας Οφειλής.
          if (wantDoseis) {
            const instMap = parseInstallments(r.text, strip);
            debts.forEach((d, i) => {
              const inst = instMap[d.instIdx != null ? d.instIdx : String(i)] || instMap[String(i)];
              if (!inst) return;
              d.installments = inst;                               // consolidated into the debts JSON
              const katigoria = d.fields['Είδος φόρου'] || d.fields['Είδος'] || p.key;
              const poso = d.total || d.overdueBalance || d.nonOverdue || '';
              d._doseisHtml = tableDoc('Ανάλυση Δόσεων Οφειλής', 'ΑΦΜ ' + afm + ' · ' + strip(katigoria) + ' · Σύνολο ' + strip(poso),
                [{ heading: 'Δόσεις', headers: inst.headers, rows: inst.rows }]);
            });
            section.withInstallments = debts.filter(d => d.installments).length;
          }

          // Ταυτότητα Οφειλής (ΤΟ) PDF per debt: GET displayDebtCode.htm (εμφάνιση ΤΟ),
          // then POST debtInfoPdf.htm (== κουμπί «Εκτύπωση» -> doViewPdf(form viewPdf ...)).
          const mch = (d) => ({
            mchDoy: d.to.mchDoy || '', mchDept: d.to.mchDept || '', mchMctCode: d.to.mchMctCode || '',
            mchSctCode: d.to.mchSctCode || '', mchYear: d.to.mchYear || '', mchSourceCode: d.to.mchSourceCode || '',
            mchMcNo: d.to.mchMcNo || '', mcLineNo: d.to.mcLineNo || '',
          });
          let idx = 0;
          for (const d of debts) {
            if (!d.to.mcLineNo) { idx++; continue; }
            // 1) show the ΤΟ (establishes state + carries the RF code)
            const q = new URLSearchParams({ returnView: d.to.returnView || '', ...mch(d), rtr: '' }).toString();
            const code = await http.getDoc(new URL(BASE + (d.to.action || 'displayDebtCode.htm') + '?' + q, L.AADE).toString());
            if (code.text) {
              if (idx === 0) http.dump('to_' + p.key + '_sample.html', code.text);
              d.toCode = (code.text.match(/RF\d{2}[A-Z0-9]{4,}/) || code.text.match(/Ταυτότητα[\s\S]{0,160}?(\d[\d\s]{12,})/) || [])[0] || null;
            }
            // 2) press «Εκτύπωση» -> POST debtInfoPdf.htm (withoutAmounts=false) -> PDF
            const pdf = await http.postForPdf(new URL(BASE + 'debtInfoPdf.htm', L.AADE).toString(), { ...mch(d), withoutAmounts: 'false' });
            if (pdf) {
              // naming: ΑΦΜ υπόχρεου _ κατηγορία οφειλής _ ποσό
              const katigoria = d.fields['Είδος φόρου'] || d.fields['Είδος'] || p.key;
              const poso = d.total || d.overdueBalance || d.nonOverdue || '';
              const f = 'OFEILI_' + afm + '_' + san(katigoria) + '_' + san(poso) + '.pdf';
              d.toPdf = f;
              if (wantDoseis && d._doseisHtml) { pending.push({ path: path.join(http.dlDir, f), toBuf: pdf, doseisHtml: d._doseisHtml }); } // δόσεις -> 2η σελίδα
              else { fs.writeFileSync(path.join(http.dlDir, f), pdf); pdfs.push(f); }
              http.log('[' + p.key + '] ✅ ΤΟ PDF -> ' + f + ' (' + pdf.length + ' b)' + (d.toCode ? ' code=' + d.toCode : '') + (wantDoseis && d._doseisHtml ? ' [+δόσεις σελ.2]' : ''));
            } else {
              http.log('[' + p.key + '] ΤΟ PDF failed (debtInfoPdf.htm)' + (d.toCode ? ' code=' + d.toCode : ''));
            }
            idx++;
          }
        }
        result.sections[p.key] = section;
        http.log('[' + p.key + '] rows=' + allRows.length + (p.debts ? (' debts=' + section.debts.length + ' (ληξιπρόθεσμες=' + section.counts.lixiprothesmes + ', εμπρόθεσμες=' + section.counts.emprothesmes + ')') : ''));
      } catch (e) {
        http.log('[' + p.key + '] error ' + (e && e.message ? e.message : e));
        result.sections[p.key] = { title: p.title, error: String(e && e.message || e) };
      }
    }

    // merge the δόσεις as a 2nd page INTO each Ταυτότητα Οφειλής PDF (Playwright renders the δόσεις page)
    if (pending.length) {
      const rr = await renderMany(pending.map(p => p.doseisHtml));
      for (let i = 0; i < pending.length; i++) {
        const it = pending[i];
        let out = it.toBuf;
        if (rr.ok && rr.buffers[i]) { try { out = await mergePdf([it.toBuf, rr.buffers[i]]); } catch (e) { out = it.toBuf; } }
        fs.writeFileSync(it.path, out); pdfs.push(path.basename(it.path));
      }
      http.log(rr.ok ? ('[aade-debts] ✅ δόσεις ως 2η σελίδα σε ' + pending.length + ' PDF')
        : ('[aade-debts] δόσεις 2η σελίδα skipped: ' + rr.reason + ' (ΤΟ PDF χωρίς δόσεις· δεδομένα στο JSON)'));
    }

    for (const s of Object.values(result.sections)) for (const d of (s.debts || [])) { delete d._doseisHtml; delete d.instIdx; } // internal fields out of JSON
    const dest = path.join(http.dlDir, 'AADE_debts_' + (inp.user || 'user') + '.json');
    fs.writeFileSync(dest, JSON.stringify(result, null, 2));
    http.log('[aade-debts] ✅ saved -> ' + path.basename(dest) + (pdfs.length ? (' + ' + pdfs.length + ' PDF' + (wantDoseis ? ' (με δόσεις)' : '')) : ''));
    return { ok: true, files: [path.basename(dest), ...pdfs] };
  },
};
