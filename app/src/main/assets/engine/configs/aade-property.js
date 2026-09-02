/*
 * configs/aade-property.js  --  Περιουσιακή Κατάσταση (+ ΕΝΦΙΑ σημείωση)
 * Portal: www1.aade.gr/saadeapps3/myPROPERTY  (νέο Angular SPA, GSIS OAM login).
 *
 * ΣΗΜΕΙΩΣΗ RE: ο decompiled (Easy_Aade.GetAdde_E9Page ~44900-45650) χρησιμοποιούσε το ΠΑΛΙΟ Oracle ADF
 * /webtax3/etak για ENFIA_Ekk/Doseis/EidPliromis/Periousiaki. ΟΛΟ αυτό ΕΧΕΙ ΑΠΟΣΥΡΘΕΙ (404). Το myPROPERTY
 * είναι πλέον Angular SPA με REST endpoints `webresources/capitalcommon/*`. Η **Περιουσιακή Κατάσταση**
 * βγαίνει από `getPrintPeriousiaki/{afm}/{year}/{afm}` (απλό GET, session cookie, χωρίς token) — ΕΠΙΒΕΒΑΙΩΜΕΝΟ.
 * Το ετήσιο ΕΝΦΙΑ (εκκαθαριστικό/δόσεις/ειδοποιητήριο) δεν εντοπίστηκε ως ζωντανό endpoint σε αυτά τα accounts
 * (χωριστή, μετακομισμένη εφαρμογή· χρειάζεται επιπλέον probe) — γι' αυτό εδώ καλύπτεται μόνο η Περιουσιακή.
 *
 * FAITHFUL flow (νέο SPA):
 *   GET  /saadeapps3/myPROPERTY?                                        (app session)
 *   GET  .../webresources/capitalcommon/getuserdata/username  -> {afm,...}
 *   GET  .../webresources/capitalcommon/getCurrentYear/       -> {year}
 *   ανά έτος: GET .../webresources/capitalcommon/getPrintPeriousiaki/{afm}/{year}/{afm} -> PDF
 *
 * INPUTS: TAXISnet user/pass + (προαιρετικά) Έτος (κενό = όλα τα διαθέσιμα, τρέχον..τρέχον-4).
 * OUTPUT: PERIOUSIAKI_<ΑΦΜ>_<έτος>.pdf + AADE_property_<ΑΦΜ>.json.
 */
'use strict';
const path = require('path');
const fs = require('fs');

module.exports = {
  id: 'aade-property',
  title: 'Περιουσιακή Κατάσταση (myPROPERTY)',
  portal: 'AADE saadeapps3/myPROPERTY (GSIS OAM)',
  subsystem: 'Hyper.Server',
  actions: ['retrieve', 'download'],
  inputs: [
    { key: 'user', label: 'TAXISnet username', env: 'AADE_USER' },
    { key: 'pass', label: 'TAXISnet password', env: 'AADE_PASS', hidden: true },
    { key: 'year', label: 'Έτος (κενό = όλα τα διαθέσιμα)', env: 'AADE_YEAR', optional: true },
  ],

  async run(http, inp, lib) {
    const L = await lib.aadeLogin(http, inp);
    if (!L.ok) { http.log('AADE LOGIN FAILED: ' + L.reason); return { ok: false, reason: L.reason }; }
    const APP = new URL('/saadeapps3/myPROPERTY', L.AADE).toString();
    const W = APP + '/webresources/capitalcommon';
    await http.follow('GET', APP + '?');                                  // establish SPA session

    const ud = await http.api('GET', W + '/getuserdata/username', undefined, {});
    let afm = '';
    try { afm = JSON.parse(ud.text).afm; } catch (e) {}
    if (!afm) { http.dump('property_userdata.txt', ud.text); http.log('[property] δεν βρέθηκε ΑΦΜ'); return { ok: false, reason: 'NoAfm' }; }

    let curYear = 0;
    const cy = await http.api('GET', W + '/getCurrentYear/', undefined, {});
    try { const o = JSON.parse(cy.text); curYear = parseInt(o.year || o, 10); } catch (e) { curYear = parseInt((cy.text.match(/\d{4}/) || [])[0], 10); }
    if (!curYear) curYear = new Date().getFullYear();

    const wantYear = (inp.year || '').trim();
    const years = wantYear ? [wantYear] : Array.from({ length: 5 }, (_, i) => String(curYear - i));
    http.log('[property] ΑΦΜ ' + afm + ' | τρέχον έτος ' + curYear + ' | έτη: ' + years.join(', '));

    const result = { portal: this.portal, afm, currentYear: curYear, retrievedAt: new Date().toISOString(), years: {} };
    const files = [];
    for (const y of years) {
      try {
        const doc = await http.getDoc(W + '/getPrintPeriousiaki/' + afm + '/' + y + '/' + afm);
        if (!doc.buffer) { result.years[y] = 'NoPDF'; http.log('[property ' + y + '] χωρίς PDF (ct=' + doc.ct + ')'); continue; }
        const f = 'PERIOUSIAKI_' + afm + '_' + y + '.pdf';
        fs.writeFileSync(path.join(http.dlDir, f), doc.buffer); files.push(f);
        result.years[y] = { pdf: f, bytes: doc.buffer.length };
        http.log('[property ' + y + '] ✅ ' + f + ' (' + doc.buffer.length + ' b)');
      } catch (e) { result.years[y] = { error: String(e && e.message || e) }; http.log('[property ' + y + '] error ' + (e && e.message ? e.message : e)); }
    }
    const jf = 'AADE_property_' + afm + '.json';
    fs.writeFileSync(path.join(http.dlDir, jf), JSON.stringify(result, null, 2));
    http.log('[aade-property] ✅ saved -> ' + jf + ' (' + files.length + ' PDF)');
    return { ok: files.length > 0, files: [jf, ...files] };
  },
};
