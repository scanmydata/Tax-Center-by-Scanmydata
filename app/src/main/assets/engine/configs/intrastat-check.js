/*
 * configs/intrastat-check.js  --  Έλεγχος υποβολής δήλωσης INTRASTAT (ανά περίοδο/ροή)
 * =============================================================================
 * Πύλη ΕΛ.ΣΤΑΤ: https://eurostat.statistics.gr  (ASP custom «post()» app, login io1/io2).
 * Αντιστοιχεί στο TaxSystem EpsilonSubmit `Intrastat_login`/`Intrastat_V1` (CefSharp/Selenium
 * DoSubmitIntrastat) + Hyper.Server.Acc `ObligationRepository.CheckIntrastatExists`.
 *
 * ΡΟΗ (browser, LIVE-VERIFIED):
 *   1) login.aspx: io1=email, io2=password → post(0) («Είσοδος»).
 *   2) history.aspx («Αρχείο Δηλώσεων») → πίνακας ΑΦΜ (ο λογιστής βλέπει όλες τις επιχειρήσεις
 *      που διαχειρίζεται) → κλικ στο link της ζητούμενης ΑΦΜ (javascript:post(0,0,<id>)).
 *   3) year select name="io2" (onchange post(0)) = ΕΤΟΣ της περιόδου → ο πίνακας αρχείου:
 *      στήλες «Πρωτόκολλο | * | Ροή | Γραμμές | Τιμ.Αξία | Στατ.Αξία | Δημιουργία | Μεταβολή».
 *      Πρωτόκολλο = `YYYYMM/serial` (η περίοδος είναι ΜΕΣΑ στο πρωτόκολλο).
 *   4) ΕΛΕΓΧΟΣ: για period=YYYYMM + ροή(ές), υπάρχει γραμμή με πρωτόκολλο `YYYYMM/...` & ίδια Ροή;
 *      → SUBMITTED (με πρωτόκολλο/τύπο/γραμμές/αξίες/ημ.) ή NOT_SUBMITTED.
 *
 * INPUTS: user(email)/pass + ΑΦΜ επιχείρησης + period (YYYYMM | MM/YYYY | YYYY-MM) + flow(προαιρ.).
 * OUTPUT: INTRASTAT_CHECK_<ΑΦΜ>_<period>.json.
 */
'use strict';
const path = require('path');
const fs = require('fs');
const { withBrowser } = require('../lib/browser-step');

const BASE = 'https://eurostat.statistics.gr/';

// period → {year:'YYYY', month:'MM', key:'YYYYMM'}
function parsePeriod(p) {
  const s = String(p || '').trim();
  let y, m;
  let mt;
  if ((mt = s.match(/^(\d{4})(\d{2})$/))) { y = mt[1]; m = mt[2]; }            // 202508
  else if ((mt = s.match(/^(\d{4})[-/](\d{1,2})$/))) { y = mt[1]; m = mt[2]; } // 2025-08
  else if ((mt = s.match(/^(\d{1,2})[-/](\d{4})$/))) { y = mt[2]; m = mt[1]; } // 08/2025
  else return null;
  m = String(+m).padStart(2, '0');
  return { year: y, month: m, key: y + m };
}

// which flows to check from input (default both)
function wantFlows(flow) {
  const f = (flow || '').toLowerCase();
  if (/αφιξ|arriv|import|εισαγ/.test(f)) return ['Άφιξη'];
  if (/αποστ|dispatch|export|εξαγ/.test(f)) return ['Αποστολή'];
  return ['Άφιξη', 'Αποστολή'];
}

module.exports = {
  id: 'intrastat-check',
  title: 'Έλεγχος υποβολής INTRASTAT (ανά περίοδο/ροή)',
  portal: 'ΕΛ.ΣΤΑΤ eurostat.statistics.gr (Αρχείο Δηλώσεων)',
  subsystem: 'EpsilonSubmit Intrastat_V1 (DoSubmitIntrastat) / ObligationRepository.CheckIntrastatExists',
  actions: ['check'],
  inputs: [
    { key: 'user', label: 'Email χρήστη Intrastat', env: 'INTRA_USER' },
    { key: 'pass', label: 'Password Intrastat', env: 'INTRA_PASS', hidden: true },
    { key: 'afm', label: 'ΑΦΜ επιχείρησης', env: 'INTRA_AFM' },
    { key: 'period', label: 'Περίοδος (YYYYMM | MM/YYYY | YYYY-MM)', env: 'INTRA_PERIOD' },
    { key: 'flow', label: 'Ροή: arrival/Άφιξη, dispatch/Αποστολή, κενό = και οι δύο', env: 'INTRA_FLOW', optional: true },
    { key: 'pdf', label: 'Λήψη PDF βεβαίωσης υποβολής; (ναι/όχι, default ναι)', env: 'INTRA_PDF', optional: true },
  ],

  async run(http, inp) {
    const afm = String(inp.afm || '').trim();
    const per = parsePeriod(inp.period);
    if (!inp.user || !inp.pass || !afm) { http.log('[intrastat] Χρειάζονται user + pass + ΑΦΜ'); return { ok: false, reason: 'InvalidInput' }; }
    if (!per) { http.log('[intrastat] Μη έγκυρη περίοδος (δώσε YYYYMM ή MM/YYYY)'); return { ok: false, reason: 'BadPeriod' }; }
    const flows = wantFlows(inp.flow);
    const wantPdf = !/^(no|όχι|οχι|false|0)$/i.test(String(inp.pdf || 'yes').trim());
    http.log('[intrastat] έλεγχος ΑΦΜ ' + afm + ' περίοδος ' + per.key + ' ροές: ' + flows.join('/'));

    const res = await withBrowser({ headed: !!process.env.INTRA_HEADED }, async ({ page, context }) => {
      const dump = async (n) => { try { fs.writeFileSync(path.join(http.dlDir, n), await page.content()); } catch (e) {} };
      // 1) login
      await page.goto(BASE + 'login.aspx'); await page.sleep(1500);
      if (!(await page.count('input[name="io1"]'))) return { ok: false, reason: 'NoLoginForm' };
      await page.fill('input[name="io1"]', inp.user);
      await page.fill('input[name="io2"]', inp.pass);
      await page.evaluate(() => { typeof post === 'function' ? post(0) : document.forms[0].submit(); });
      await page.sleep(3500);
      if (/login/i.test(page.url()) || await page.count('input[name="io2"][type="password"]')) { await dump('intrastat_login_fail.html'); return { ok: false, reason: 'LoginFailed' }; }

      // open the archive for afm+year (history.aspx → select ΑΦΜ → select έτος). Returns reason|null.
      const openArchive = async () => {
        await page.goto(BASE + 'history.aspx'); await page.sleep(2200);
        const sel = await page.evaluate((a) => { for (const x of document.querySelectorAll('a')) { if ((x.getAttribute('href') || '').startsWith('javascript:post') && (x.innerText || '').trim() === a) return x.getAttribute('href'); } return null; }, afm);
        if (!sel) return 'AfmNotManaged';
        const args = sel.match(/post\(([^)]*)\)/)[1].split(',').map(s => s.trim());
        await page.evaluate(a => post(+a[0], +a[1], a[2]), args); await page.sleep(2800);
        const okYear = await page.evaluate((y) => { const s = document.querySelector('select[name="io2"]'); if (!s) return 'NoYearSelect'; if (![...s.options].some(o => o.value === y)) return 'YearNotAvailable'; s.value = y; (typeof post === 'function') ? post(0) : s.form.submit(); return true; }, per.year);
        if (okYear !== true) return okYear;
        await page.sleep(2800);
        return null;
      };
      // 2+3) αρχείο afm+year
      const oa = await openArchive();
      if (oa) { await dump('intrastat_openarchive_fail.html'); return { ok: false, reason: oa }; }
      await dump('intrastat_archive_' + afm + '_' + per.year + '.html');

      // 4) parse the archive table (header «Πρωτόκολλο … Ροή …»)
      const data = await page.evaluate(() => {
        const clean = s => (s || '').replace(/\s+/g, ' ').trim();
        if (/Δεν βρέθηκαν Δηλώσεις/i.test(document.body.innerText)) return { noDecl: true, rows: [] };
        // Data rows have a fixed 8-col layout: Πρωτόκολλο | * | Ροή | Γραμμές | Τιμ.Αξία | Στατ.Αξία | Δημιουργία | Μεταβολή.
        // Parse positionally: any <tr> whose first cell is a YYYYMM/serial protocol.
        const rows = [];
        for (const tr of document.querySelectorAll('tr')) {
          const cells = [...tr.cells].map(x => clean(x.innerText));
          if (cells.length < 3) continue;
          if (!/^\d{6}\//.test((cells[0] || '').replace(/\s/g, ''))) continue;
          const a = tr.querySelector('a[href^="javascript:post(1"]');
          const id = a ? (a.getAttribute('href').match(/post\(\s*1\s*,\s*0\s*,\s*([^)]+)\)/) || [])[1] : null;
          rows.push({ protocol: cells[0], type: cells[1] || '', flow: cells[2] || '', lines: cells[3] || '', timeValue: cells[4] || '', statValue: cells[5] || '', created: cells[6] || '', modified: cells[7] || '', detailId: id ? id.trim() : (cells[0].replace('/', '')) });
        }
        return { noDecl: false, rows };
      });

      // match period (protocol starts with YYYYMM/) per flow
      const allRows = data.rows || [];
      const periodRows = allRows.filter(r => (r.protocol || '').replace(/\s/g, '').startsWith(per.key + '/'));
      const perFlow = {};
      for (const fl of flows) {
        const hit = periodRows.filter(r => (r.flow || '').includes(fl) || (r.flow || '').includes(fl.replace('ή', 'η')));
        perFlow[fl] = { status: hit.length ? 'SUBMITTED' : 'NOT_SUBMITTED', declarations: hit };
      }

      // PDF βεβαίωσης ανά υποβληθείσα δήλωση της περιόδου
      const pdfFiles = [];
      if (wantPdf) {
        const flat = []; for (const fl of flows) for (const d of perFlow[fl].declarations) flat.push({ fl, d });
        for (let k = 0; k < flat.length; k++) {
          const { fl, d } = flat[k];
          try {
            if (k > 0) { const re = await openArchive(); if (re) { http.log('[intrastat] re-open archive failed: ' + re); break; } } // fresh archive per declaration (session resets)
            // open details: post(1,0,<detailId>)
            await page.evaluate(id => post(1, 0, isNaN(+id) ? id : +id), d.detailId); await page.sleep(2500);
            const flowShort = /Άφιξ|αφιξ/i.test(fl) ? 'AFIKSI' : 'APOSTOLI';
            const serial = (d.protocol.split('/')[1] || String(d.detailId)).replace(/\D/g, '');
            const fnameBase = 'INTRASTAT_' + afm + '_' + per.key + '_' + flowShort + '_' + serial;
            let saved = false;
            // (a) full declaration view (ΠΡΟΒΟΛΗ ΔΗΛΩΣΗΣ, line items) → PDF
            try { await page.raw.pdf({ path: path.join(http.dlDir, fnameBase + '_ΔΗΛΩΣΗ.pdf'), format: 'A4', printBackground: true }); pdfFiles.push(fnameBase + '_ΔΗΛΩΣΗ.pdf'); saved = true; } catch (e) { http.log('[intrastat] δήλωση pdf err: ' + e.message); }
            // (b) «Παραλαβή Βεβαίωσης» → popup /certification (ΒΕΒΑΙΩΣΗ ΠΑΡΑΛΑΒΗΣ) → PDF
            const bev = await page.evaluate(() => { for (const a of document.querySelectorAll('a')) if ((a.innerText || '').replace(/\s+/g, ' ').trim() === 'Παραλαβή Βεβαίωσης') return a.getAttribute('href'); return null; });
            if (bev) {
              const a = bev.match(/post\(([^)]*)\)/)[1].split(',').map(s => s.trim());
              const [popup] = await Promise.all([context.waitForEvent('page', { timeout: 15000 }).catch(() => null), page.evaluate(x => post(+x[0], +x[1], isNaN(+x[2]) ? x[2] : +x[2]), a)]);
              if (popup) {
                await popup.waitForLoadState('domcontentloaded').catch(() => {});
                try { await popup.emulateMedia({ media: 'screen' }); await popup.pdf({ path: path.join(http.dlDir, fnameBase + '_ΒΕΒΑΙΩΣΗ.pdf'), format: 'A4', printBackground: true }); pdfFiles.push(fnameBase + '_ΒΕΒΑΙΩΣΗ.pdf'); saved = true; } catch (e) { http.log('[intrastat] βεβαίωση pdf err: ' + e.message); }
                await popup.close().catch(() => {});
              }
            }
            d.pdf = saved ? fnameBase : null;
          } catch (e) { http.log('[intrastat] pdf fetch err ' + d.protocol + ': ' + e.message); }
        }
      }
      return { ok: true, allRows, periodRows, perFlow, pdfFiles };
    });

    if (!res || !res.ok) { http.log('[intrastat] FAILED: ' + (res && res.reason)); return { ok: false, reason: (res && res.reason) || 'BrowserError' }; }

    const perFlow = res.perFlow;
    const allSubmitted = flows.every(fl => perFlow[fl].status === 'SUBMITTED');
    const anySubmitted = flows.some(fl => perFlow[fl].status === 'SUBMITTED');
    const overall = allSubmitted ? 'SUBMITTED' : (anySubmitted ? 'PARTIAL' : 'NOT_SUBMITTED');

    const out = {
      portal: this.portal, afm, period: per.key, year: per.year, month: per.month,
      flowsChecked: flows, overall, perFlow,
      yearArchiveCount: res.allRows.length, periodRowCount: res.periodRows.length,
      pdfFiles: res.pdfFiles, retrievedAt: new Date().toISOString(),
    };
    const jf = 'INTRASTAT_CHECK_' + afm + '_' + per.key + '.json';
    fs.writeFileSync(path.join(http.dlDir, jf), JSON.stringify(out, null, 2));
    http.log('[intrastat] ' + per.key + ' → ' + overall + ' (' + flows.map(f => f + ':' + perFlow[f].status).join(', ') + ')' + (res.pdfFiles.length ? ' | ' + res.pdfFiles.length + ' PDF' : ''));
    return { ok: true, files: [jf, ...res.pdfFiles], summary: { afm, period: per.key, overall, pdf: res.pdfFiles } };
  },
};
