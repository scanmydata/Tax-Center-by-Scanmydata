/*
 * configs/easycheck-status.js  --  EasyCheck ΠΛΗΡΕΣ: υποχρεώσεις ΑΑΔΕ + κατάσταση ΕΦΚΑ/ΚΕΑΟ/ΓΕΜΗ
 * =============================================================================
 * Ενοποιεί σε ΜΙΑ εκτέλεση όλο τον έλεγχο ολοκλήρωσης/κατάστασης που κάνει το EasyCheck,
 * ΧΩΡΙΣ βάση, ξαναχρησιμοποιώντας τα ΗΔΗ δοκιμασμένα configs (κάθε πύλη με δικό της login/cookie jar):
 *
 *   • ΑΑΔΕ  — έλεγχος ΟΛΟΚΛΗΡΩΣΗΣ υποχρεώσεων (16 περιοδικές + E1/E3 εισόδημα) μέσω του κοινού
 *             engine `lib/easycheck-obligations.js` (aade-obligations-check).
 *   • ΚΕΑΟ  — κατάσταση οφειλών (φορείς/χρεώσεις/ρυθμίσεις) μέσω `keao-debts`.
 *   • ΕΦΚΑ  — ειδοποιητήρια/εισφορές μέσω `efka-notices`.
 *   • ΓΕΜΗ  — μηνύματα/κατάσταση μέσω `easynotify` (which=GEMH).
 *
 * Κάθε πύλη είναι ΠΡΟΑΙΡΕΤΙΚΗ: τρέχει μόνο αν δοθούν τα αντίστοιχα credentials. Ό,τι λείπει
 * (creds ή πρόσβαση) υποβαθμίζεται ομαλά — δεν ρίχνει τον υπόλοιπο έλεγχο.
 *
 * INPUTS:
 *   ΑΑΔΕ:  user/pass (TAXISnet), year, obl (κενό=Φ2· ALL/VAT/FMY/DEDUCT/OTHER/INCOME/FYSIKA ή keys)
 *   ΕΦΚΑ/ΚΕΑΟ (προαιρετικά): efkaUser/efkaPass + afm + amka (και ame για εργοδότη)
 *   ΓΕΜΗ (προαιρετικά): gemhUser/gemhPass
 * OUTPUT: EASYCHECK_STATUS_<afm>.json (συγκεντρωτικό) + τα επιμέρους αρχεία κάθε πύλης σε υποφακέλους.
 */
'use strict';
const path = require('path');
const fs = require('fs');
const E = require('../lib/easycheck-obligations.js');

const val = (v) => (v == null ? '' : String(v)).trim();

module.exports = {
  id: 'easycheck-status',
  title: 'EasyCheck ΠΛΗΡΕΣ — υποχρεώσεις ΑΑΔΕ + κατάσταση ΕΦΚΑ/ΚΕΑΟ/ΓΕΜΗ',
  portal: 'AADE + e-EFKA/KEAO + ΓΕΜΗ',
  subsystem: 'Hyper.Server + EasyNotify',
  actions: ['check', 'status'],
  inputs: [
    { key: 'user', label: 'TAXISnet username (ΑΑΔΕ)', env: 'AADE_USER' },
    { key: 'pass', label: 'TAXISnet password (ΑΑΔΕ)', env: 'AADE_PASS', hidden: true },
    { key: 'year', label: 'Έτος', env: 'AADE_YEAR' },
    { key: 'obl', label: 'Υποχρεώσεις ΑΑΔΕ (ALL/VAT/FMY/DEDUCT/OTHER/INCOME/FYSIKA ή keys· κενό=Φ2)', env: 'AADE_OBL', optional: true },
    { key: 'efkaUser', label: 'ΕΦΚΑ/ΚΕΑΟ TAXISnet username (προαιρετικό)', env: 'EFKA_USER', optional: true },
    { key: 'efkaPass', label: 'ΕΦΚΑ/ΚΕΑΟ password (προαιρετικό)', env: 'EFKA_PASS', hidden: true, optional: true },
    { key: 'afm', label: 'ΑΦΜ (ΕΦΚΑ/ΚΕΑΟ)', env: 'EFKA_AFM', optional: true },
    { key: 'amka', label: 'ΑΜΚΑ (ΕΦΚΑ/ΚΕΑΟ μη μισθωτού)', env: 'EFKA_AMKA', optional: true },
    { key: 'ame', label: 'ΑΜΕ (ΕΦΚΑ εργοδότη — προαιρετικό)', env: 'EFKA_AME', optional: true },
    { key: 'gemhUser', label: 'ΓΕΜΗ username (προαιρετικό)', env: 'GEMH_USER', optional: true },
    { key: 'gemhPass', label: 'ΓΕΜΗ password (προαιρετικό)', env: 'GEMH_PASS', hidden: true, optional: true },
  ],

  async run(http, inp, lib) {
    const Y = val(inp.year);
    if (!/^\d{4}$/.test(Y)) { http.log('Χρειάζεται έγκυρο Έτος (YYYY)'); return { ok: false, reason: 'NoYear' }; }

    const summary = { generatedAt: new Date().toISOString(), year: Y, aade: null, enfia: null, keao: null, efka: null, gemh: null };
    let afm = val(inp.afm);

    // ---- 1) ΑΑΔΕ: έλεγχος ολοκλήρωσης υποχρεώσεων (core, δοκιμασμένο) ----
    try {
      const L = await lib.aadeLogin(http, { user: val(inp.user), pass: val(inp.pass) });
      if (!L.ok) { http.log('[aade] LOGIN FAILED: ' + L.reason); summary.aade = { ok: false, reason: L.reason }; }
      else {
        const aadeAfm = (lib.stripTags(L.page && L.page.text || '').match(/Α\.?Φ\.?Μ\.?\s*[:\-]?\s*(\d{9})/) || [])[1] || val(inp.user);
        if (!afm) afm = aadeAfm;
        const ctx = { AADE: L.AADE, vat: aadeAfm, log: http.log.bind(http), dump: http.dump.bind(http),
          getText: async (u) => (await http.follow('GET', u)).text,
          postText: async (u, f) => (await http.follow('POST', u, f)).text };
        const report = await E.runAll(ctx, Y, E.resolveWanted(inp.obl || 'Φ2'));
        const jf = path.join(http.dlDir, 'AADE_obligations_' + aadeAfm + '_' + Y + '.json');
        fs.writeFileSync(jf, JSON.stringify(report, null, 2));
        summary.aade = { ok: true, afm: aadeAfm, file: path.basename(jf),
          obligations: report.obligations.map(o => ({ obligation: o.obligation, status: o.status || o.error, counts: o.counts })) };
        http.log('[aade] ✅ ' + summary.aade.obligations.length + ' υποχρεώσεις -> ' + path.basename(jf));
      }
    } catch (e) { http.log('[aade] error: ' + e.message); summary.aade = { ok: false, reason: e.message }; }

    // helper: τρέξε ένα υπάρχον config σε δικό του HyperHttp (ξεχωριστό cookie jar + υποφάκελος)
    const runSub = async (label, configFile, subInputs) => {
      try {
        const cfg = require('./' + configFile);
        const subHttp = new lib.HyperHttp(path.join(http.dlDir, label));
        const res = await cfg.run(subHttp, subInputs, lib);
        http.log('[' + label + '] ' + (res && res.ok ? '✅ ok' + (res.files ? ' (' + res.files.length + ' αρχεία)' : '') : 'απέτυχε: ' + (res && res.reason || 'unknown')));
        return { ok: !!(res && res.ok), reason: res && res.reason, files: (res && res.files || []).map(f => label + '/' + f), summary: res && res.summary };
      } catch (e) { http.log('[' + label + '] error: ' + e.message); return { ok: false, reason: e.message }; }
    };

    // ---- 2) ΕΝΦΙΑ/Ε9 (ΑΑΔΕ, καθαρό HTTP ADF/ETAK — ίδια TAXISnet creds) ----
    summary.enfia = await runSub('enfia', 'aade-enfia-http.js', { user: val(inp.user), pass: val(inp.pass), year: Y });

    const efkaCreds = val(inp.efkaUser) && val(inp.efkaPass) && val(inp.afm);
    // ---- 3) ΚΕΑΟ οφειλές ----
    if (efkaCreds) summary.keao = await runSub('keao', 'keao-debts.js',
      { user: val(inp.efkaUser), pass: val(inp.efkaPass), afm: val(inp.afm), amka: val(inp.amka), ame: val(inp.ame) });
    else { http.log('[keao] παραλείπεται (χωρίς ΕΦΚΑ credentials)'); summary.keao = { skipped: 'NoEfkaCreds' }; }

    // ---- 4) ΕΦΚΑ ειδοποιητήρια/εισφορές ----
    if (efkaCreds) summary.efka = await runSub('efka', 'efka-notices.js',
      { user: val(inp.efkaUser), pass: val(inp.efkaPass), afm: val(inp.afm), amka: val(inp.amka) });
    else { http.log('[efka] παραλείπεται (χωρίς ΕΦΚΑ credentials)'); summary.efka = { skipped: 'NoEfkaCreds' }; }

    // ---- 5) ΓΕΜΗ ----
    if (val(inp.gemhUser) && val(inp.gemhPass)) summary.gemh = await runSub('gemh', 'easynotify.js',
      { user: val(inp.user), pass: val(inp.pass), which: 'GEMH', gemhUser: val(inp.gemhUser), gemhPass: val(inp.gemhPass) });
    else { http.log('[gemh] παραλείπεται (χωρίς ΓΕΜΗ credentials)'); summary.gemh = { skipped: 'NoGemhCreds' }; }

    const jf = path.join(http.dlDir, 'EASYCHECK_STATUS_' + (afm || val(inp.user)) + '.json');
    fs.writeFileSync(jf, JSON.stringify(summary, null, 2));
    http.log('[easycheck-status] ✅ συγκεντρωτικό -> ' + path.basename(jf));
    return { ok: true, files: [path.basename(jf)], summary };
  },
};
