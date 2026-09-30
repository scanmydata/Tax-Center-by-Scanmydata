/*
 * configs/aade-vat-f2-amounts.js  --  ΦΠΑ Φ2: κατάσταση υποβολής + ΠΟΣΑ ανά περίοδο
 * =============================================================================
 * Runner wrapper πάνω από το standalone module `lib/aade-vat-http.js` (καθαρό HTTP, ΧΩΡΙΣ browser).
 * Επεκτείνει τον απλό έλεγχο Φ2 (aade-obligations-check) ώστε να επιστρέφει ΚΑΙ τα ποσά ανά
 * τρίμηνο/μήνα (αυτόματα): αποτέλεσμα (Χρεωστική/Πιστωτική/Μηδενική), συνολικό ποσό οφειλής,
 * ποσό δόσης, Ταυτότητα Οφειλής (RF), ΔΟΥ, ημ/νίες. Φτιαγμένο για ενσωμάτωση στο scanmydata.
 *
 * INPUTS: TAXISnet user/pass + Έτος.
 * OUTPUT: AADE_F2_amounts_<afm>_<έτος>.json (πλήρες JSON του module).
 */
'use strict';
const path = require('path');
const fs = require('fs');
const { fetchVatDeclarations } = require('../lib/aade-vat-http');

module.exports = {
  id: 'aade-vat-f2-amounts',
  title: 'ΦΠΑ Φ2: κατάσταση υποβολής + ποσά ανά τρίμηνο/μήνα',
  portal: 'AADE taxisnet (GSIS OAM)',
  subsystem: 'Hyper.Server (Easy_Aade + declarationState/debtCode)',
  actions: ['check', 'amounts'],
  inputs: [
    { key: 'user', label: 'TAXISnet username', env: 'AADE_USER' },
    { key: 'pass', label: 'TAXISnet password', env: 'AADE_PASS', hidden: true },
    { key: 'year', label: 'Έτος', env: 'AADE_YEAR' },
  ],

  async run(http, inp) {
    const Y = String(inp.year || '').trim();
    if (!/^\d{4}$/.test(Y)) { http.log('Χρειάζεται έγκυρο Έτος (YYYY)'); return { ok: false, reason: 'NoYear' }; }
    const r = await fetchVatDeclarations({ user: inp.user, pass: inp.pass, year: Y, includeAmounts: true, log: http.log.bind(http) });
    if (!r.ok) { http.log('[aade-vat-f2] FAILED: ' + r.reason); return { ok: false, reason: r.reason }; }
    const jf = path.join(http.dlDir, 'AADE_F2_amounts_' + r.afm + '_' + Y + '.json');
    fs.writeFileSync(jf, JSON.stringify(r, null, 2));
    http.log('[aade-vat-f2] ✅ ' + r.status + ' — ' + r.counts.periods + ' περίοδοι (' + (r.periodType || '?') + ') -> ' + path.basename(jf));
    for (const p of r.periods) {
      const d = p.declarations.find(x => x.effective) || p.declarations[0] || {};
      const a = d.amounts || {};
      const amt = a.totalDue ? ('οφειλή ' + a.totalDue) : a.amountForDeduction ? ('έκπτωση ' + a.amountForDeduction) : a.amountForRefund ? ('επιστροφή ' + a.amountForRefund) : '-';
      http.log('  ' + p.period + ': ' + p.status + (d.result ? ' [' + d.result + '] ' + amt : ''));
    }
    return { ok: true, files: [path.basename(jf)], summary: { afm: r.afm, status: r.status, periodType: r.periodType, counts: r.counts } };
  },
};
