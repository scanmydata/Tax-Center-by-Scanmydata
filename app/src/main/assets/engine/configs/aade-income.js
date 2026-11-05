/*
 * configs/aade-income.js  --  Λήψη E1 / E2 / E3 / E3_myDATA + Εκκαθαριστικό (E0) — ΟΛΑ τα έτη
 * Source: Hyper.Server.Tax.dll  Easy_Aade.GetE1E2E3Fysiko (decompiled ~45954).  GSIS OAM login.
 *
 * FAITHFUL flow, per έτος:
 *  1) GET  {AADE}/webtax/incomefp/year{Y}-income-menu.do  -> hidden print codes:
 *          PRINT_CODE(s1), PRINT_CODE_SYZ(s1s), PRINT_CODE_E2(s2), PRINT_CODE_E2_SYZYGOY(s2s), PRINT_CODE_E3(s3)
 *     If s1&&s2&&s3 empty -> POST year{Y}-income-menuMod.do {PRINT_CODE:'',…,YEAR, PBModGoToModMenu{Y}} (τροποποιητική)
 *  2) PDF per έντυπο:
 *     • έτος >= 2023 (E1/E2/E3/E3myData) & >=2024 (Εκκαθαριστικό E0):
 *         POST year{Y}-income-menuPrint.do  with PostValuesOver2023 + the έντυπο flag:
 *           E1=e1_print:'e1_print', E1_Synopsi=e1_print_mini, E2=print_e2_ypo, E2συζ=print_e2_syz,
 *           E3=e3_print:'e3_print', E3_myDATA=e3_print:'e3md_print', Εκκαθ.=print_e0_ypo
 *         PostValuesOver2023: 2023 -> {PRINT_CODE:s1,PRINT_CODE_SYZ:s1s,PRINT_CODE_E2:s2,PRINT_CODE_E2_SYZYGOY:s2s,
 *           YEAR, PBMod2023:'', e1_print:'',e3_print:'',print_e2_ypo:'',print_e2_syz:''};  >=2024 -> {YEAR, same '' flags}
 *     • έτος < 2023 (E0 < 2024):  POST {AADE}/reports/rwservlet?  with
 *           {cmdkey:'INC00S', p_afm:<print code>, report:'<X>Form{suffix}.rdf', desname, desformat:'pdf', destype:'cache'}
 *           X: E1/E2/E3/E0. suffix = last 2 digits of year. E1 report is read from the menu page (…w.rdf variant).
 *
 * Επιπλέον (live 11/2026): EKK_SYN / EKK_SYZ_SYN = Σύνοψη εκκαθαριστικού (print_e0m_ypo|syz, μόνο ≥2024),
 *   PROEK / PROEK_SYZ = Προεκκαθάριση «Ενημέρωση Εισοδημάτων-Δαπανών» (GetE1Proekatharis, bebApodoxPrintYpo|Syz).
 * INPUTS: TAXISnet user/pass + Έτος + (ποια έντυπα, default E1,E2,E3,EKK).
 */
'use strict';
const path = require('path');
const fs = require('fs');

// έντυπο -> { code(print-code input for old years), flagKey/flagVal (menuPrint), button (existence for new years),
//            menuFrom(year boundary: >= => income-menuPrint.do, < => reports/rwservlet), reportBase, label }
const FORMS = {
  E1:      { code: 's1',  flagKey: 'e1_print',      flagVal: 'e1_print',      button: 'PBE1_PRINT_PDF',         menuFrom: 2023, reportBase: 'E1Form', label: 'E1' },
  E1_SYN:  { code: 's1',  flagKey: 'e1_print_mini', flagVal: 'e1_print_mini', button: 'PBE1_PRINT_PDF',         menuFrom: 2023, reportBase: 'E1Form', label: 'E1_Synopsi' },
  E2:      { code: 's2',  flagKey: 'print_e2_ypo',  flagVal: 'print_e2_ypo',  button: 'PBE2_PRINT_PDF',         menuFrom: 2023, reportBase: 'E2Form', label: 'E2' },
  E2_SYZ:  { code: 's2s', flagKey: 'print_e2_syz',  flagVal: 'print_e2_syz',  button: 'PBE2_SYZYGOY_PRINT_PDF', menuFrom: 2023, reportBase: 'E2Form', label: 'E2_Spouse' },
  E3:      { code: 's3',  flagKey: 'e3_print',      flagVal: 'e3_print',      button: 'PBE3_PRINT_PDF',         menuFrom: 2023, reportBase: 'E3Form', label: 'E3' },
  E3MYDATA:{ code: 's3',  flagKey: 'e3_print',      flagVal: 'e3md_print',    button: 'E3MY_PRINT_PDF',         menuFrom: 2024, reportBase: 'E3Form', label: 'E3_myDATA' },
  EKK:     { code: 's1',  flagKey: 'print_e0_ypo',  flagVal: 'print_e0_ypo',  button: 'PB_EKKATH_PDF',          menuFrom: 2024, reportBase: 'E0Form', label: 'Εκκαθαριστικό' },
  EKK_SYZ: { code: 's1s', flagKey: 'print_e0_syz',  flagVal: 'print_e0_syz',  button: 'PB_EKKATH_PDF_SYZ',      menuFrom: 2024, reportBase: 'E0Form', label: 'Εκκαθαριστικό_συζύγου' },
  // Σύνοψη εκκαθαριστικού (E1_Ekk_Synopsi / E1_Ekk_SyzSynopsi): ΜΟΝΟ menuPrint.do (δεν υπάρχει .rdf για παλιά έτη)
  EKK_SYN:     { code: 's1',  flagKey: 'print_e0m_ypo', flagVal: 'print_e0m_ypo', button: 'PB_EKKATH_PDF',     menuFrom: 2024, onlyNew: true, label: 'Σύνοψη_Εκκαθαριστικού' },
  EKK_SYZ_SYN: { code: 's1s', flagKey: 'print_e0m_syz', flagVal: 'print_e0m_syz', button: 'PB_EKKATH_PDF_SYZ', menuFrom: 2024, onlyNew: true, label: 'Σύνοψη_Εκκαθαριστικού_συζύγου' },
  // Προεκκαθάριση (GetE1Proekatharis): κουμπί PBE_EKTYPWSH_APODOXWN «Ενημέρωση Εισοδημάτων-Δαπανών-προεκκαθάρισης
  //   φόρου Υπόχρεου | Συζύγου/ΜΣΣ» (μη disabled) -> POST με bebApodoxPrintYpo|bebApodoxPrintSyz. Μόνο φυσικά πρόσωπα.
  //   Η σελίδα είναι ISO-8859-7 και το value φτάνει αλλοιωμένο -> αναγνώριση από το onclick bebApodoxYpo()/bebApodoxSyz().
  PROEK:     { proek: 'bebApodoxPrintYpo', onclick: 'bebApodoxYpo', label: 'Προεκκαθάριση' },
  PROEK_SYZ: { proek: 'bebApodoxPrintSyz', onclick: 'bebApodoxSyz', label: 'Προεκκαθάριση_συζύγου' },
};
// προεκκαθάριση διαθέσιμη; input name=PBE_EKTYPWSH_APODOXWN με onclick=<fn>() και ΧΩΡΙΣ disabled
function proekAvailable(menu, fn) {
  return [...menu.matchAll(/<input\b[^>]*name="PBE_EKTYPWSH_APODOXWN"[^>]*>/gi)].some(m =>
    new RegExp('onclick="' + fn + '\\(').test(m[0]) && !/disabled/i.test(m[0]));
}
const codeInput = (html, name) => (html.match(new RegExp('name="' + name + '"[^>]*value="([^"]*)"', 'i')) || [])[1] || '';
// έντυπο διαθέσιμο; νεότερα έτη -> το κουμπί εκτύπωσης υπάρχει & ΔΕΝ είναι disabled· παλιά -> υπάρχει print code
function available(menu, F, code) {
  const btn = menu.match(new RegExp('<(?:button|input)[^>]*name="' + F.button + '"[^>]*>', 'i'));
  if (btn) return !/disabled/i.test(btn[0]);
  return !!code;
}

module.exports = {
  id: 'aade-income',
  title: 'Λήψη E1/E2/E3/E3myDATA + Εκκαθαριστικό (όλα τα έτη)',
  portal: 'AADE webtax/incomefp (GSIS OAM)',
  subsystem: 'Hyper.Server',
  actions: ['retrieve', 'download'],
  inputs: [
    { key: 'user', label: 'TAXISnet username', env: 'AADE_USER' },
    { key: 'pass', label: 'TAXISnet password', env: 'AADE_PASS', hidden: true },
    { key: 'year', label: 'Έτος (φορολ. έτος)', env: 'AADE_YEAR' },
    { key: 'forms', label: 'Έντυπα (E1,E1_SYN,E2,E2_SYZ,E3,E3MYDATA,EKK,EKK_SYZ,EKK_SYN,EKK_SYZ_SYN,PROEK,PROEK_SYZ — κενό=E1,E2,E3,EKK)', env: 'AADE_FORMS' },
  ],

  async run(http, inp, lib) {
    const L = await lib.aadeLogin(http, inp);
    if (!L.ok) { http.log('AADE LOGIN FAILED: ' + L.reason); return { ok: false, reason: L.reason }; }
    const strip = lib.stripTags;
    const Y = parseInt((inp.year || '').trim(), 10);
    if (!Y) { http.log('Χρειάζεται Έτος'); return { ok: false, reason: 'NoYear' }; }
    const afm = (strip(L.page.text || '').match(/Α\.?Φ\.?Μ\.?\s*[:\-]?\s*(\d{9})/) || [])[1] || inp.user;
    const suffix = String(Y).slice(-2);
    const H = (rel) => new URL('/webtax/incomefp/' + rel, L.AADE).toString();
    let want = (inp.forms || '').toUpperCase().replace(/[.\-]/g, '').split(/[,\s]+/).map(s => s.trim()).filter(k => FORMS[k]);
    if (!want.length) want = ['E1', 'E2', 'E3', 'EKK']; // κενό ή μη αναγνωρίσιμο -> default (ποτέ άδειο)

    // 1) income menu (ΜΟΝΟ ανάγνωση — ΠΟΤΕ δεν πατάμε «Υποβολή/Τροποποιητική» κουμπιά, κανένα menuMod)
    const menu = (await http.follow('GET', H('year' + Y + '-income-menu.do'))).text;
    http.dump('income_menu_' + Y + '.html', menu);
    const comment = '';
    const c = { s1: codeInput(menu, 'PRINT_CODE'), s1s: codeInput(menu, 'PRINT_CODE_SYZ'), s2: codeInput(menu, 'PRINT_CODE_E2'), s2s: codeInput(menu, 'PRINT_CODE_E2_SYZYGOY'), s3: codeInput(menu, 'PRINT_CODE_E3') };

    // PostValuesOver2023 base (for year >= 2023 menuPrint requests)
    const postBase = () => (Y === 2023
      ? { PRINT_CODE: c.s1, PRINT_CODE_SYZ: c.s1s, PRINT_CODE_E2: c.s2, PRINT_CODE_E2_SYZYGOY: c.s2s, YEAR: String(Y), ['PBMod' + Y]: '', e1_print: '', e3_print: '', print_e2_ypo: '', print_e2_syz: '' }
      : { YEAR: String(Y), e1_print: '', e3_print: '', print_e2_ypo: '', print_e2_syz: '' });

    http.log('[income] έτος ' + Y + ' | έντυπα: ' + want.join(', '));
    const result = { portal: this.portal, afm, year: Y, comment, retrievedAt: new Date().toISOString(), forms: {} };
    const pdfs = [];
    for (const k of want) {
      const F = FORMS[k];
      if (F.proek) { await this.getProek(http, H, menu, Y, afm, k, F, result, pdfs, postBase); continue; }
      const code = c[F.code];
      if (F.onlyNew && Y < F.menuFrom) { result.forms[k] = { label: F.label, status: 'NotApplicable (μόνο έτος ≥ ' + F.menuFrom + ')' }; http.log('[' + k + '] μόνο για έτος ≥ ' + F.menuFrom + ' — skip'); continue; }
      if (!available(menu, F, code)) { result.forms[k] = { label: F.label, status: 'NotAvailable (κουμπί disabled / χωρίς δήλωση)' }; http.log('[' + k + '] μη διαθέσιμο (κουμπί disabled) — skip'); continue; }
      try {
        let pdf = null;
        if (Y >= F.menuFrom) {
          // year >= boundary -> income-menuPrint.do with PostValuesOver2023 + this form's flag
          pdf = await http.postForPdf(H('year' + Y + '-income-menuPrint.do'), { ...postBase(), [F.flagKey]: F.flagVal });
        } else {
          // older years -> reports/rwservlet with the .rdf report
          let report = F.reportBase + suffix + '.rdf';
          if (k === 'E1') { // E1 report name is declared in the menu page (…w.rdf variant)
            const rep = (menu.match(/\["report"\]\.value='(E1Form[^']*?\.rdf)'/i) || [])[1];
            if (rep) report = rep;
          }
          pdf = await http.postForPdf(new URL('/reports/rwservlet', L.AADE).toString(), { cmdkey: 'INC00S', p_afm: code, report, desname: report, desformat: 'pdf', destype: 'cache' });
        }
        if (!pdf) { result.forms[k] = { label: F.label, status: 'NoPDF' }; http.log('[' + k + '] δεν επέστρεψε PDF'); continue; }
        // Εύρος Ά(U+0386)–ώ(U+03CE): περιλαμβάνει τους τονισμένους και το
        // τελικό σίγμα. Το παλιό «Α-Ω, α-ω» τα έκοβε — «Εκκαθαριστικ».
        const f = F.label.replace(/[^0-9A-Za-zΆ-ώ_]/g, '') + '_' + afm + '_' + Y + '.pdf';
        fs.writeFileSync(path.join(http.dlDir, f), pdf); pdfs.push(f);
        result.forms[k] = { label: F.label, pdf: f, bytes: pdf.length, comment };
        http.log('[' + k + '] ✅ PDF -> ' + f + ' (' + pdf.length + ' b)');
      } catch (e) { result.forms[k] = { label: F.label, error: String(e && e.message || e) }; http.log('[' + k + '] error ' + (e && e.message ? e.message : e)); }
    }

    const jf = path.join(http.dlDir, 'AADE_income_' + afm + '_' + Y + '.json');
    fs.writeFileSync(jf, JSON.stringify(result, null, 2));
    http.log('[aade-income] ✅ saved -> ' + path.basename(jf) + ' (' + pdfs.length + ' PDF)');
    return { ok: true, files: [path.basename(jf), ...pdfs] };
  },

  // GetE1Proekatharis. Ο browser υποβάλλει τη φόρμα του μενού (action year{Y}-income-menuPrint.do) με το flag·
  // το TaxSystem κάνει POST στο year{Y}-income-menu.do με YEAR='yearPrm' (literal, bug). Δοκιμάζουμε πρώτα το
  // menuPrint.do (όπως ο browser) και μετά το menu.do με το σωστό έτος.
  async getProek(http, H, menu, Y, afm, k, F, result, pdfs, postBase) {
    if (!proekAvailable(menu, F.onclick)) { result.forms[k] = { label: F.label, status: 'NotFound (δεν υπάρχει ενεργό κουμπί προεκκαθάρισης)' }; http.log('[' + k + '] δεν υπάρχει ενεργό κουμπί προεκκαθάρισης (' + F.onclick + ') — skip'); return; }
    try {
      const flags = { bebApodoxPrintYpo: '', bebApodoxPrintSyz: '', [F.proek]: F.proek };
      const pdf = await http.postForPdf(H('year' + Y + '-income-menuPrint.do'), { ...postBase(), ...flags })
        || await http.postForPdf(H('year' + Y + '-income-menu.do'), { YEAR: String(Y), ...flags });
      if (!pdf) { result.forms[k] = { label: F.label, status: 'NoPDF' }; http.log('[' + k + '] δεν επέστρεψε PDF'); return; }
      const f = F.label.replace(/[^0-9A-Za-zΆ-ώ_]/g, '') + '_' + afm + '_' + Y + '.pdf';
      fs.writeFileSync(path.join(http.dlDir, f), pdf); pdfs.push(f);
      result.forms[k] = { label: F.label, pdf: f, bytes: pdf.length };
      http.log('[' + k + '] ✅ PDF -> ' + f + ' (' + pdf.length + ' b)');
    } catch (e) { result.forms[k] = { label: F.label, error: String(e && e.message || e) }; http.log('[' + k + '] error ' + (e && e.message ? e.message : e)); }
  },
};
