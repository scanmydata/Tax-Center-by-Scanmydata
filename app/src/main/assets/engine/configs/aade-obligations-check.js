/*
 * configs/aade-obligations-check.js  --  EasyCheck (ΑΑΔΕ online) ως runner-config
 * =============================================================================
 * ΤΟ «νέο EasyCheck»: ελέγχει ΑΠΕΥΘΕΙΑΣ ΑΠΟ ΤΗΝ ΑΑΔΕ αν οι υποχρεώσεις (δηλώσεις)
 * έχουν ΥΠΟΒΛΗΘΕΙ/ολοκληρωθεί — ΧΩΡΙΣ βάση/DB schema.
 *
 * Η λογική + η ΠΛΗΡΗΣ λίστα υποχρεώσεων ζουν στο κοινό engine
 * `configs/easycheck-obligations.js` (ίδια πηγή αλήθειας με το standalone
 * `easycheck-aade-http.js`). Πηγή: Hyper.Server.Tax Easy_Aade.GetGeneralType1Year.
 *
 * Καλύπτει ό,τι ελέγχει το EasyCheck μέσω displayLiabilitiesForYear:
 *   ΦΠΑ Φ2/Φ4/Φ5/Φ1 · Εισόδημα(incomeN) · ΜΥΦ/ΚΒΣ(kvsD13) · Περιβαλλοντικό/Διαμονής/H2B ·
 *   Συμφωνητικά · Εργολάβων · ΦΜΥ family (ΦΜΥ/Επιχ/Δικαιώματα/Μερίσματα/Τόκοι).
 *
 * INPUTS: TAXISnet user/pass + Έτος + obl (keys με κόμμα ή ομάδα ALL/VAT/FMY/DEDUCT/OTHER/INCOME· κενό=Φ2).
 * OUTPUT: AADE_obligations_<afm>_<έτος>.json
 */
'use strict';
const path = require('path');
const fs = require('fs');
const E = require('../lib/easycheck-obligations.js');

module.exports = {
  id: 'aade-obligations-check',
  title: 'EasyCheck (ΑΑΔΕ): Έλεγχος ολοκλήρωσης υποχρεώσεων',
  portal: 'AADE taxisnet (GSIS OAM)',
  subsystem: 'Hyper.Server',
  actions: ['check'],
  inputs: [
    { key: 'user', label: 'TAXISnet username', env: 'AADE_USER' },
    { key: 'pass', label: 'TAXISnet password', env: 'AADE_PASS', hidden: true },
    { key: 'year', label: 'Έτος', env: 'AADE_YEAR' },
    { key: 'obl', label: 'Υποχρεώσεις (' + Object.keys(E.OBLIGATIONS).join('/') + ' | ALL/VAT/FMY/DEDUCT/OTHER/INCOME, κόμμα· κενό=Φ2)', env: 'AADE_OBL', optional: true },
  ],
  // exported για προγραμματιστική χρήση / tests
  engine: E,

  async run(http, inp, lib) {
    const Y = (inp.year || '').trim();
    if (!/^\d{4}$/.test(Y)) { http.log('Χρειάζεται έγκυρο Έτος (YYYY)'); return { ok: false, reason: 'NoYear' }; }
    const L = await lib.aadeLogin(http, inp);
    if (!L.ok) { http.log('AADE LOGIN FAILED: ' + L.reason); return { ok: false, reason: L.reason }; }
    const AADE = L.AADE;
    const afm = (lib.stripTags(L.page && L.page.text || '').match(/Α\.?Φ\.?Μ\.?\s*[:\-]?\s*(\d{9})/) || [])[1] || inp.user;

    const ctx = {
      AADE, vat: afm, log: http.log.bind(http), dump: http.dump.bind(http),
      getText: async (url) => (await http.follow('GET', url)).text,
      postText: async (url, form) => (await http.follow('POST', url, form)).text,
    };
    const wanted = E.resolveWanted(inp.obl || 'Φ2');
    const report = await E.runAll(ctx, Y, wanted);

    const jf = path.join(http.dlDir, 'AADE_obligations_' + afm + '_' + Y + '.json');
    fs.writeFileSync(jf, JSON.stringify(report, null, 2));
    http.log('[aade-obligations-check] ✅ saved -> ' + path.basename(jf));
    for (const e of report.obligations) http.log('  ' + e.obligation + ': ' + (e.status || e.error || '') + (e.counts ? ' [✓' + e.counts.submitted + ' ~' + e.counts.pending + ' ✗' + e.counts.notSubmitted + ']' : ''));
    return { ok: true, files: [path.basename(jf)], summary: report.obligations.map(o => ({ obligation: o.obligation, status: o.status })) };
  },
};
