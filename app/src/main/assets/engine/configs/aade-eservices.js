/*
 * configs/aade-eservices.js  --  Λοταρία (Φορολοταρία) & Βεβαίωση Επιστρεπτέας Προκαταβολής
 * Source: Hyper.Server.Tax.dll  Easy_Aade.GetLottery (<GetLottery>d__28) &
 *         Easy_Aade.GetEpistreptea (<GetEpistreptea>d__32). GSIS OAM login (aadeLogin).
 *
 * ── ΛΟΤΑΡΙΑ (RetrievalKindPdfs.Lottery) ────────────────────────────────────────
 *   GET  {AADE}/webtax/incomefp/perprintform2010.do
 *        -> HtmlNode //select[@name='EKTYP1']/option[0] @value  ->  "{afm};{report}" (split ';')
 *   POST {AADE}/reports/rwservlet?   (Oracle Reports servlet, form-urlencoded)
 *        cmdkey=INC00S, p_afm={afm}, p_year=, report={report}, desname={report}, desformat=pdf, destype=cache
 *        -> PDF
 *
 * ── ΕΠΙΣΤΡΕΠΤΕΑ ΠΡΟΚΑΤΑΒΟΛΗ (RetrievalKindPdfs.Epistrept_Prokat) ────────────────
 *   (myBusinessSupport Angular SPA REST — ίδιο μοτίβο με aade-property/myPROPERTY)
 *   GET {AADE}/saadeapps3/myBusinessSupport/views/menuview/menuview.html        (app session)
 *   GET {AADE}/saadeapps3/myBusinessSupport/webresources/capitalcommon/getuserdata/username?  -> <afm>
 *   GET {AADE}/saadeapps3/myBusinessSupport/webresources/capitalcommon/getDownTime/username?
 *   GET {AADE}/saadeapps3/myBusinessSupport/webresources/capitalcommon/getPrintPDF/1/{afm}/999?  -> PDF
 *
 * INPUTS: TAXISnet user/pass + which (LOTARIA/EPISTREPTEA, κενό = και τα δύο) + προαιρετικά ΑΦΜ.
 * OUTPUT: LOTARIA_<afm>.pdf, EPISTREPTEA_<afm>.pdf + AADE_eservices_<afm>.json
 */
'use strict';
const path = require('path');
const fs = require('fs');

const MBS = '/saadeapps3/myBusinessSupport';

module.exports = {
  id: 'aade-eservices',
  title: 'Λοταρία (Φορολοταρία) & Βεβαίωση Επιστρεπτέας Προκαταβολής',
  portal: 'AADE (GSIS OAM)',
  subsystem: 'Hyper.Server',
  actions: ['retrieve', 'download'],
  inputs: [
    { key: 'user', label: 'TAXISnet username', env: 'AADE_USER' },
    { key: 'pass', label: 'TAXISnet password', env: 'AADE_PASS', hidden: true },
    { key: 'which', label: 'Ποιο (LOTARIA / EPISTREPTEA — κενό = και τα δύο)', env: 'AADE_ESVC', optional: true },
    { key: 'vat', label: 'ΑΦΜ (κενό = ο ΑΦΜ του λογαριασμού)', env: 'AADE_VAT', optional: true },
  ],

  async run(http, inp, lib) {
    const L = await lib.aadeLogin(http, inp);
    if (!L.ok) { http.log('AADE LOGIN FAILED: ' + L.reason); return { ok: false, reason: L.reason }; }
    const AADE = L.AADE;
    const acctAfm = (lib.stripTags(L.page && L.page.text || '').match(/Α\.?Φ\.?Μ\.?\s*[:\-]?\s*(\d{9})/) || [])[1]
      || (inp.vat || '').trim() || inp.user;
    const want = (inp.which || '').trim().toUpperCase();
    const doLot = !want || /LOT|ΛΟΤΑΡ/.test(want);
    const doEpi = !want || /EPIST|ΕΠΙΣΤΡΕΠ|PROKAT/.test(want);

    const result = { portal: this.portal, retrievedAt: new Date().toISOString() };
    const files = [];

    // ── ΛΟΤΑΡΙΑ ──
    if (doLot) {
      try {
        http.log('[lottery] GET perprintform2010.do');
        const page = (await http.follow('GET', AADE + '/webtax/incomefp/perprintform2010.do')).text;
        http.dump('lottery_form.html', page);
        // //select[@name='EKTYP1']/option[0] @value  ->  "{token};{report}"
        // (NOTE: το select του Oracle app ΔΕΝ κλείνει με </select>, οπότε παίρνουμε
        //  απλώς το πρώτο <option> μετά το name="EKTYP1".)
        const idx = page.search(/name=["']EKTYP1["']/i);
        const after = idx >= 0 ? page.slice(idx) : '';
        const optVal = (after.match(/<option\b[^>]*value=["']([^"']+)["']/i) || [])[1] || '';
        const val = lib.decodeHtml(optVal).trim();
        const [afmL, report] = val.split(';');
        if (!afmL || !report) {
          http.log('[lottery] ⚠ δεν βρέθηκε option EKTYP1 (πιθανόν χωρίς κλήρωση/κλειστή υπηρεσία)');
          result.lottery = 'NotFound';
        } else {
          http.log('[lottery] afm=' + afmL + ' report=' + report);
          const pdf = await http.postForPdf(AADE + '/reports/rwservlet?', {
            cmdkey: 'INC00S', p_afm: afmL, p_year: '', report, desname: report, desformat: 'pdf', destype: 'cache',
          });
          if (pdf) {
            const f = 'LOTARIA_' + acctAfm + '.pdf';
            fs.writeFileSync(path.join(http.dlDir, f), pdf); files.push(f);
            result.lottery = { pdf: f, bytes: pdf.length };
            http.log('[lottery] ✅ -> ' + f + ' (' + pdf.length + ' b)');
          } else { result.lottery = 'NoPDF'; http.log('[lottery] δεν επέστρεψε PDF'); }
        }
      } catch (e) { result.lottery = 'ERR:' + e.message; http.log('[lottery] ERROR ' + e.message); }
    }

    // ── ΕΠΙΣΤΡΕΠΤΕΑ ΠΡΟΚΑΤΑΒΟΛΗ ──
    if (doEpi) {
      try {
        http.log('[epistreptea] GET myBusinessSupport menuview');
        await http.follow('GET', AADE + MBS + '/views/menuview/menuview.html');
        const ud = (await http.follow('GET', AADE + MBS + '/webresources/capitalcommon/getuserdata/username?')).text;
        http.dump('epistreptea_userdata.txt', ud);
        let afmE = (inp.vat || '').trim();
        if (!afmE) afmE = (ud.match(/<afm>\s*(\d{9})\s*<\/afm>/i) || ud.match(/"afm"\s*:\s*"?(\d{9})/i) || [])[1] || '';
        if (!afmE) afmE = (L.page && (lib.stripTags(L.page.text).match(/Α\.?Φ\.?Μ\.?\s*[:\-]?\s*(\d{9})/) || [])[1]) || '';
        if (!afmE) {
          http.log('[epistreptea] ⚠ δεν βρέθηκε ΑΦΜ (δες epistreptea_userdata.txt)');
          result.epistreptea = 'NoAfm';
        } else {
          await http.follow('GET', AADE + MBS + '/webresources/capitalcommon/getDownTime/username?');
          http.log('[epistreptea] getPrintPDF afm=' + afmE);
          const doc = await http.getDoc(AADE + MBS + '/webresources/capitalcommon/getPrintPDF/1/' + afmE + '/999?');
          if (doc.buffer) {
            const f = 'EPISTREPTEA_' + afmE + '.pdf';
            fs.writeFileSync(path.join(http.dlDir, f), doc.buffer); files.push(f);
            result.epistreptea = { pdf: f, bytes: doc.buffer.length };
            http.log('[epistreptea] ✅ -> ' + f + ' (' + doc.buffer.length + ' b)');
          } else {
            result.epistreptea = 'NoPDF';
            http.log('[epistreptea] δεν επέστρεψε PDF (ct=' + doc.ct + ') — πιθανόν χωρίς επιστρεπτέα ή κλειστή υπηρεσία');
          }
        }
      } catch (e) { result.epistreptea = 'ERR:' + e.message; http.log('[epistreptea] ERROR ' + e.message); }
    }

    const jf = path.join(http.dlDir, 'AADE_eservices_' + acctAfm + '.json');
    fs.writeFileSync(jf, JSON.stringify(result, null, 2));
    http.log('[aade-eservices] ✅ saved -> ' + path.basename(jf) + ' (' + files.length + ' PDF)');
    return { ok: true, files: [path.basename(jf), ...files] };
  },
};
