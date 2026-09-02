/*
 * configs/aade-declarations.js  --  Λήψη εντύπων ΑΑΔΕ (Φ2/Φ4/Φ5 + λοιπά "type-1" έντυπα)
 * Source: Hyper.Server.Tax.dll  Easy_Aade.GetGeneralType1Year (decompiled ~48560).  GSIS OAM login.
 *
 * FAITHFUL flow (per έτος, per είδος):
 *  1) GET {AADE}/taxisnet/{category}/protected/displayLiabilitiesForYear.htm?declarationType={filter}&year={Y}{extra}
 *     -> td.contenttd rows tr.tblRow1|tr.tblRow2. Row status at statusCol must contain
 *        "Έχει Υποβληθεί Δήλωση" / "Έχουν Υποβληθεί Δηλώσεις" / "Υπάρχει δήλωση σε εκκρεμότητα".
 *        Button cell (buttonCol) div.navbtn onclick doDisplayDeclarationsList(form, <7 args>) =
 *        [declarationType, year, periodType, periodStart, periodEnd, effectivePeriodStart, effectivePeriodEnd]
 *  2) POST {AADE}/taxisnet/{category}/protected/displayDeclarationsList.htm  {those 7}
 *     -> rows tr.tblRow1|tr.tblRow2: td[5]=="Οριστική", td[2]=declarationDatabaseId, td[4]=τύπος/σχόλιο.
 *        Take the LAST οριστική (== C# GetOnlyLastRow).
 *  3) POST {AADE}/taxisnet/{category}/protected/viewPdf.htm  {declarationDatabaseId, declarationType:filter} -> PDF
 *
 * NOTE: E1/E2/E3/E3_myDATA, Εκκαθαριστικά, ΦΕΝΠ (έντυπο Ν) and Τέλη Κυκλοφορίας are DIFFERENT AADE
 * sub-apps (income / webtax) — separate configs, not this GetGeneralType1Year flow.
 * INPUTS: TAXISnet user/pass + Έτος + (ποια έντυπα, default Φ2,Φ4,Φ5).
 */
'use strict';
const path = require('path');
const fs = require('fs');

// kind -> filter/category/columns (== the C# switch @48717). statusCol/buttonCol are C# 0-based td indexes.
const KINDS = {
  F2: { filter: 'vatF2', category: 'vat', label: 'Φ2', statusCol: 2, buttonCol: 3 },
  F4: { filter: 'vatF4', category: 'vat', label: 'Φ4', statusCol: 2, buttonCol: 3 },
  F5: { filter: 'vatF5', category: 'vat', label: 'Φ5', statusCol: 2, buttonCol: 3 },
};
const SUBMITTED = /Έχει Υποβληθεί Δήλωση|Έχουν Υποβληθεί Δηλώσεις|Υπάρχει δήλωση σε εκκρεμότητα/;
const rows = (html) => [...html.matchAll(/<tr\b[^>]*class="tblRow[12]"[^>]*>([\s\S]*?)<\/tr>/gi)]
  .map(r => [...r[1].matchAll(/<td\b[^>]*>([\s\S]*?)<\/td>/gi)].map(c => c[1]));

module.exports = {
  id: 'aade-declarations',
  title: 'Λήψη εντύπων ΑΑΔΕ (Φ2/Φ4/Φ5)',
  portal: 'AADE taxisnet (GSIS OAM)',
  subsystem: 'Hyper.Server',
  actions: ['retrieve', 'download'],
  inputs: [
    { key: 'user', label: 'TAXISnet username', env: 'AADE_USER' },
    { key: 'pass', label: 'TAXISnet password', env: 'AADE_PASS', hidden: true },
    { key: 'year', label: 'Έτος', env: 'AADE_YEAR' },
    { key: 'forms', label: 'Έντυπα (π.χ. F2,F4,F5 — κενό=όλα)', env: 'AADE_FORMS' },
  ],

  async run(http, inp, lib) {
    const L = await lib.aadeLogin(http, inp);
    if (!L.ok) { http.log('AADE LOGIN FAILED: ' + L.reason); return { ok: false, reason: L.reason }; }
    const strip = lib.stripTags;
    const want = (inp.forms || '').trim() ? inp.forms.toUpperCase().split(/[,\s]+/).filter(k => KINDS[k]) : Object.keys(KINDS);
    const year = (inp.year || '').trim();
    const result = { portal: this.portal, afm: (strip(L.page.text || '').match(/Α\.?Φ\.?Μ\.?\s*[:\-]?\s*(\d{9})/) || [])[1] || inp.user, year, retrievedAt: new Date().toISOString(), forms: {} };
    const pdfs = [];

    for (const k of want) {
      const cfg = KINDS[k];
      const p = (rel) => new URL('/taxisnet/' + cfg.category + '/protected/' + rel, L.AADE).toString();
      try {
        // 1) liabilities for the year
        const liab = await http.follow('GET', p('displayLiabilitiesForYear.htm?declarationType=' + cfg.filter + '&year=' + year + '&category=' + cfg.category));
        http.dump('liab_' + k + '_' + year + '.html', liab.text);
        const saved = [];
        for (const tds of rows(liab.text)) {
          const status = strip(tds[cfg.statusCol] || '');
          if (!SUBMITTED.test(status)) continue;
          const onclick = (tds[cfg.buttonCol] || '').match(/doDisplayDeclarationsList\(document\.displayDeclarationsListForm\s*,([\s\S]*?)\)/i);
          if (!onclick) continue;
          const a = [...onclick[1].matchAll(/"([^"]*)"/g)].map(x => x[1]);
          if (a.length < 7) continue;
          const nv = { declarationType: a[0], year: a[1], periodType: a[2], periodStart: a[3], periodEnd: a[4], effectivePeriodStart: a[5], effectivePeriodEnd: a[6] };
          // 2) declarations list for that period -> last οριστική databaseId
          const listResp = await http.follow('POST', p('displayDeclarationsList.htm'), nv);
          http.dump('list_' + k + '_' + (a[3] || '').slice(0, 10) + '.html', listResp.text);
          let dbId = '', comment = '';
          for (const l of rows(listResp.text)) {
            if (strip(l[5] || '') !== 'Οριστική') continue;
            dbId = strip(l[2] || ''); comment = strip(l[4] || '');   // last οριστική wins
          }
          if (!dbId) continue;
          // 3) PDF
          const pdf = await http.postForPdf(p('viewPdf.htm'), { declarationDatabaseId: dbId, declarationType: cfg.filter });
          if (!pdf) { http.log('[' + k + '] no PDF for period ' + (a[3] || '') + ' (id ' + dbId + ')'); continue; }
          const per = (a[3] || '').replace(/[^0-9]/g, '').slice(0, 8) || dbId;
          const f = cfg.label + '_' + result.afm + '_' + year + '_' + per + '.pdf';
          fs.writeFileSync(path.join(http.dlDir, f), pdf); pdfs.push(f);
          saved.push({ period: strip(tds[0] || ''), declarationDatabaseId: dbId, comment, pdf: f });
          http.log('[' + k + '] ✅ PDF -> ' + f + ' (' + pdf.length + ' b)');
        }
        result.forms[k] = { label: cfg.label, filter: cfg.filter, count: saved.length, items: saved };
        http.log('[' + k + '] ' + saved.length + ' PDF(s)');
      } catch (e) {
        http.log('[' + k + '] error ' + (e && e.message ? e.message : e));
        result.forms[k] = { label: cfg.label, error: String(e && e.message || e) };
      }
    }

    const jf = path.join(http.dlDir, 'AADE_declarations_' + result.afm + '_' + year + '.json');
    fs.writeFileSync(jf, JSON.stringify(result, null, 2));
    http.log('[aade-declarations] ✅ saved -> ' + path.basename(jf) + ' (' + pdfs.length + ' PDF)');
    return { ok: true, files: [path.basename(jf), ...pdfs] };
  },
};
