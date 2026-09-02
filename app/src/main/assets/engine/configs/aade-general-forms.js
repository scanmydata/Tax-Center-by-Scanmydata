/*
 * configs/aade-general-forms.js  --  Γενικά έντυπα ΑΑΔΕ (declarationType-based)
 * Source: Hyper.Server.Tax.dll  Easy_Aade.GetGeneralType1Year / GetGeneralPageType1 (decompiled ~48700-49480).
 * GSIS OAM login. Καλύπτει σε ΜΙΑ διαδικασία τα έντυπα που ακολουθούν το ίδιο μοτίβο:
 *   GET  taxisnet/{category}/protected/displayLiabilitiesForYear.htm?declarationType={filter}&year={Y}{extra}
 *   row tblRow1/2: status «Έχει/Έχουν Υποβληθεί Δήλωση(εις)» -> button doDisplayDeclarationsList(7 args)
 *   GET  taxisnet/{category}/protected/displayDeclarationsList.htm?{params}
 *   row: col[5]=='Οριστική' (+ optional SearchType στο col[4]) -> declarationDatabaseId = col[2]
 *   POST taxisnet/{category}/protected/viewPdf.htm {declarationType, declarationDatabaseId} -> PDF
 *
 * FORMS (key -> {category, filter, extra, searchType, statusIdx, buttonIdx}):
 *   Φ2/Φ4/Φ5 (ΦΠΑ), ΦΜΥ/ΕΠΙΧ/ΜΕΡΙΣΜΑΤΑ/ΤΟΚΟΙ/ΔΙΚΑΙΩΜΑΤΑ (Παρακρατ. Φόροι), ΕΡΓΟΛΑΒΩΝ (Φ01-019),
 *   ΑΝΘΕΚΤΙΚΟΤΗΤΑΣ (Τέλος Ανθεκτ./Διαμονής), ΠΕΡΙΒΑΛΛΟΝ (Περιβαλλοντικό Τέλος), ΣΥΜΦΩΝΗΤΙΚΑ (Κατ. Συμφωνητικών).
 *
 * INPUTS: TAXISnet user/pass + Έντυπο (key) + Έτος. Δέχεται και πολλά έντυπα με κόμμα.
 */
'use strict';
const path = require('path');
const fs = require('fs');

// default col indices: status=2, button=3 (overridden per form)
const FORMS = {
  'Φ2': { category: 'vat', filter: 'vatF2', statusIdx: 2, buttonIdx: 3 },
  'Φ4': { category: 'vat', filter: 'vatF4', statusIdx: 2, buttonIdx: 3 },
  'Φ5': { category: 'vat', filter: 'vatF5', statusIdx: 2, buttonIdx: 3 },
  'ΦΜΥ': { category: 'deduction', filter: 'deductFMYTemporary', extra: '&periodType=oneMonth&typeBtn=Μήνας', searchType: '( ΦΜΥ )', statusIdx: 1, buttonIdx: 2 },
  'ΕΠΙΧ': { category: 'deduction', filter: 'deductFMYTemporary', extra: '&periodType=oneMonth&typeBtn=Μήνας', searchType: 'σε αμοιβές από Επιχειρηματική Δραστηριότητα )', statusIdx: 1, buttonIdx: 2 },
  'ΜΕΡΙΣΜΑΤΑ': { category: 'deduction', filter: 'deductFMYTemporary', extra: '&periodType=oneMonth&typeBtn=Μήνας', searchType: '( Μερίσματα )', statusIdx: 1, buttonIdx: 2 },
  'ΤΟΚΟΙ': { category: 'deduction', filter: 'deductFMYTemporary', extra: '&periodType=oneMonth&typeBtn=Μήνας', searchType: '( Τόκοι )', statusIdx: 1, buttonIdx: 2 },
  'ΔΙΚΑΙΩΜΑΤΑ': { category: 'deduction', filter: 'deductFMYTemporary', extra: '&periodType=oneMonth&typeBtn=Μήνας', searchType: '( Δικαιώματα )', statusIdx: 1, buttonIdx: 2 },
  'ΕΡΓΟΛΑΒΩΝ': { category: 'deduction', filter: 'deductContractor', statusIdx: 1, buttonIdx: 2 },
  'ΑΝΘΕΚΤΙΚΟΤΗΤΑΣ': { category: 'other', filter: 'otherAppH6', statusIdx: 2, buttonIdx: 3 },
  'ΠΕΡΙΒΑΛΛΟΝ': { category: 'other', filter: 'otherAppH7', statusIdx: 2, buttonIdx: 3 },
  'ΣΥΜΦΩΝΗΤΙΚΑ': { category: 'deduction', filter: 'deductAgreement', statusIdx: 1, buttonIdx: 2 },
};

const rowsOf = (html) => [...html.matchAll(/<tr\b[^>]*class="tblRow[12]"[^>]*>([\s\S]*?)<\/tr>/gi)]
  .map(r => [...r[1].matchAll(/<td\b[^>]*>([\s\S]*?)<\/td>/gi)].map(c => c[1]));
const STATUS_SUBMITTED = /Έχει Υποβληθεί Δήλωση|Έχουν Υποβληθεί Δηλώσεις|Υπάρχει δήλωση σε εκκρεμότητα/;

module.exports = {
  id: 'aade-general-forms',
  title: 'Γενικά Έντυπα ΑΑΔΕ (ΦΠΑ / Παρακρατούμενοι / Εργολάβων / Ανθεκτικότητας / Συμφωνητικά)',
  portal: 'AADE taxisnet (GSIS OAM)',
  subsystem: 'Hyper.Server',
  actions: ['retrieve', 'download'],
  inputs: [
    { key: 'user', label: 'TAXISnet username', env: 'AADE_USER' },
    { key: 'pass', label: 'TAXISnet password', env: 'AADE_PASS', hidden: true },
    { key: 'form', label: 'Έντυπο (' + Object.keys(FORMS).join('/') + ', κόμμα για πολλά)', env: 'AADE_FORM' },
    { key: 'year', label: 'Έτος', env: 'AADE_YEAR' },
  ],

  async run(http, inp, lib) {
    const L = await lib.aadeLogin(http, inp);
    if (!L.ok) { http.log('AADE LOGIN FAILED: ' + L.reason); return { ok: false, reason: L.reason }; }
    const strip = lib.stripTags;
    const Y = (inp.year || '').trim();
    if (!Y) { http.log('Χρειάζεται Έτος'); return { ok: false, reason: 'NoYear' }; }
    const afm = (strip(L.page.text || '').match(/Α\.?Φ\.?Μ\.?\s*[:\-]?\s*(\d{9})/) || [])[1] || inp.user;
    const want = (inp.form || '').split(',').map(s => s.trim().toUpperCase()).filter(k => FORMS[k]);
    if (!want.length) { http.log('Άγνωστο έντυπο. Διαθέσιμα: ' + Object.keys(FORMS).join(', ')); return { ok: false, reason: 'BadForm' }; }

    const files = [];
    const result = { portal: this.portal, afm, year: Y, retrievedAt: new Date().toISOString(), forms: {} };
    for (const key of want) {
      try { const r = await this.getForm(http, lib, L, afm, Y, key, files); result.forms[key] = r; }
      catch (e) { result.forms[key] = { error: String(e && e.message || e) }; http.log('[' + key + '] error ' + (e && e.message ? e.message : e)); }
    }
    const jf = 'AADE_forms_' + afm + '_' + Y + '.json';
    fs.writeFileSync(path.join(http.dlDir, jf), JSON.stringify(result, null, 2));
    http.log('[aade-general-forms] ✅ saved -> ' + jf + ' (' + files.length + ' PDF)');
    return { ok: files.length > 0, files: [jf, ...files] };
  },

  async getForm(http, lib, L, afm, Y, key, files) {
    const strip = lib.stripTags;
    const F = FORMS[key];
    const P = (rel) => new URL('/taxisnet/' + F.category + '/protected/' + rel, L.AADE).toString();
    const liabUrl = P('displayLiabilitiesForYear.htm') + '?declarationType=' + F.filter + '&year=' + Y + (F.extra || '');
    http.log('[' + key + '] GET liabilities (' + F.category + '/' + F.filter + ' ' + Y + ')');
    const liab = await http.follow('GET', liabUrl);
    http.dump('forms_' + key + '_liab.html', liab.text);
    if (liab.text.includes('Δεν έχετε υποχρεώσεις υποβολής για το συγκεκριμένο έντυπο')) {
      http.log('[' + key + '] καμία υποχρέωση/δήλωση για ' + Y);
      return { status: 'NoObligations' };
    }

    // collect declaration-list param sets from submitted-status rows
    const paramSets = [];
    for (const tds of rowsOf(liab.text)) {
      const status = strip(tds[F.statusIdx] || '');
      if (!STATUS_SUBMITTED.test(status)) continue;
      const btn = tds[F.buttonIdx] || '';
      const m = btn.match(/doDisplayDeclarationsList\(document\.displayDeclarationsListForm,([^;]*?)\)\s*;/i);
      if (!m) continue;
      const a = m[1].split(',').map(s => s.replace(/["']/g, ' ').trim()).filter(s => s.length);
      if (a.length < 7) continue;
      paramSets.push({ declarationType: a[0], year: a[1], periodType: a[2], periodStart: a[3], periodEnd: a[4], effectivePeriodStart: a[5], effectivePeriodEnd: a[6] });
    }
    if (!paramSets.length) { http.log('[' + key + '] χωρίς γραμμές «Υποβληθείσα Δήλωση»'); return { status: 'NoSubmitted' }; }

    const out = { status: 'ok', declarations: [], pdfs: [] };
    let idx = 0;
    for (const ps of paramSets) {
      const listUrl = P('displayDeclarationsList.htm') + '?' + new URLSearchParams(ps).toString();
      const list = await http.follow('GET', listUrl);
      for (const tds of rowsOf(list.text)) {
        const comment = strip(tds[4] || '');
        const finalStatus = strip(tds[5] || '');
        if (finalStatus !== 'Οριστική') continue;
        if (F.searchType && !comment.includes(F.searchType)) continue;
        const declId = strip(tds[2] || '');
        if (!declId) continue;
        // period label from the liabilities row params (col[0] of the list is the source, not the period)
        const period = (ps.periodStart || ps.periodType || '').replace(/\s+/g, '');
        out.declarations.push({ declarationDatabaseId: declId, period, periodStart: ps.periodStart, periodEnd: ps.periodEnd, comment });
        const pdf = await http.postForPdf(P('viewPdf.htm'), { declarationType: F.filter, declarationDatabaseId: declId });
        if (!pdf) { http.log('[' + key + '] declId ' + declId + ': no PDF'); continue; }
        idx++;
        const safeP = (period || String(idx)).replace(/[^0-9A-Za-zΑ-Ωα-ω]/g, '-');
        const f = key + '_' + afm + '_' + Y + '_' + safeP + '_' + declId + '.pdf';
        fs.writeFileSync(path.join(http.dlDir, f), pdf); files.push(f); out.pdfs.push(f);
        http.log('[' + key + '] ✅ ' + f + ' (' + pdf.length + ' b)');
      }
    }
    if (!out.pdfs.length) http.log('[' + key + '] καμία οριστική δήλωση' + (F.searchType ? ' για ' + F.searchType : ''));
    return out;
  },
};
