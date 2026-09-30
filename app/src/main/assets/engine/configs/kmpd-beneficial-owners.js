/*
 * configs/kmpd-beneficial-owners.js  --  Κεντρικό Μητρώο Πραγματικών Δικαιούχων (ΚΜΠΔ)
 * =============================================================================
 * Runner wrapper πάνω από το ΚΑΘΑΡΟ-HTTP module `lib/kmpd-http.js` (γρήγορο/ελαφρύ, ΧΩΡΙΣ browser).
 * Κατεβάζει «Εκτύπωση δικαιούχων» + «Εκτύπωση Βεβαίωσης οριστικοποίησης» με σωστή ελληνική ονομασία
 * «<Επωνυμία> - <ΑΦΜ> - <Είδος>.pdf». Πηγή process: EpsilonDocs.bMtrPragtknDikxn (webapps.gsis.gr/dsae/boregistry).
 * ΓΙΑ ΕΤΑΙΡΙΑ: user/pass = ΝΟΜΙΜΟΥ ΕΚΠΡΟΣΩΠΟΥ, afm = ΑΦΜ της εταιρίας.
 */
'use strict';
const { fetchBeneficialOwners } = require('../lib/kmpd-http');

module.exports = {
  id: 'kmpd-beneficial-owners',
  title: 'Κεντρικό Μητρώο Πραγματικών Δικαιούχων (ΚΜΠΔ) — δικαιούχοι + βεβαίωση (καθαρό HTTP)',
  portal: 'GSIS ΚΜΠΔ webapps/dsae/boregistry (καθαρό HTTP, χωρίς browser)',
  subsystem: 'EpsilonDocs (bMtrPragtknDikxn)',
  actions: ['retrieve', 'download'],
  inputs: [
    { key: 'user', label: 'TAXISnet username (νόμιμου εκπροσώπου για εταιρία)', env: 'KMPD_USER' },
    { key: 'pass', label: 'TAXISnet password', env: 'KMPD_PASS', hidden: true },
    { key: 'afm', label: 'ΑΦΜ οντότητας/εταιρίας', env: 'KMPD_AFM' },
    { key: 'docs', label: 'Έγγραφα (dikaiouxoi,bebaiosi· κενό=και τα δύο)', env: 'KMPD_DOCS', optional: true },
  ],

  async run(http, inp) {
    const docs = String(inp.docs || '').split(',').map(s => s.trim()).filter(Boolean);
    const r = await fetchBeneficialOwners({
      user: inp.user, pass: inp.pass, afm: String(inp.afm || '').trim(),
      outDir: http.dlDir, docs: docs.length ? docs : undefined, log: http.log.bind(http),
    });
    if (!r.ok) { http.log('[kmpd] FAILED: ' + r.reason); return { ok: false, reason: r.reason }; }
    http.log('[kmpd] ✅ ' + r.entity.name + ' (' + r.entity.afm + ') — ' + r.files.length + ' PDF');
    return { ok: true, files: r.files.map(f => f.name), summary: { entity: r.entity, files: r.files.map(f => ({ type: f.type, name: f.name, bytes: f.bytes })) } };
  },
};
