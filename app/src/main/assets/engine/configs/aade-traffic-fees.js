/*
 * configs/aade-traffic-fees.js  --  Τέλη Κυκλοφορίας (app myCAR)
 * Source: Hyper.Server.Tax.dll  Easy_Aade.GetTrafficFees (decompiled ~51862).  GSIS OAM login.
 *
 * FAITHFUL flow (app www1.aade.gr/saadeapps3/myCAR, REST):
 *   GET myCAR/?#!/roadtax
 *   GET myCAR/webresources/carcommon/getuserdata/username   -> runningafm from <afm>…</afm>
 *   GET myCAR/webresources/carcommon/getDownTime/username
 *   GET myCAR/webresources/tkcommon/get5Years/              -> has "validfiscalyear":"<year>"
 *   per έτος (validfiscalyear present):
 *     GET tkcommon/getAllVehicles/{afm}                     -> must contain "Επιλογή όλων"
 *     GET tkcommon/getChkAfmArkyklPeriod/{afm}/Επιλογή όλων/{year}/12  -> must contain <fiscalyear>{year}</fiscalyear>
 *     GET tkcommon/getPrintPDF3/{afm}/Επιλογή όλων/{year}/12 -> PDF
 *
 * INPUTS: TAXISnet user/pass + Έτος (κενό = όλα τα διαθέσιμα από get5Years).
 */
'use strict';
const path = require('path');
const fs = require('fs');

const ALL = encodeURIComponent('Επιλογή όλων');

module.exports = {
  id: 'aade-traffic-fees',
  title: 'Τέλη Κυκλοφορίας (myCAR)',
  portal: 'AADE saadeapps3/myCAR (GSIS OAM)',
  subsystem: 'Hyper.Server',
  actions: ['retrieve', 'download'],
  inputs: [
    { key: 'user', label: 'TAXISnet username', env: 'AADE_USER' },
    { key: 'pass', label: 'TAXISnet password', env: 'AADE_PASS', hidden: true },
    { key: 'year', label: 'Έτος (κενό = όλα τα διαθέσιμα)', env: 'AADE_YEAR' },
  ],

  async run(http, inp, lib) {
    const L = await lib.aadeLogin(http, inp);
    if (!L.ok) { http.log('AADE LOGIN FAILED: ' + L.reason); return { ok: false, reason: L.reason }; }
    const strip = lib.stripTags;
    const CAR = new URL('/saadeapps3/myCAR', L.AADE).toString();
    const G = async (rel) => (await http.follow('GET', CAR + rel)).text;

    await http.follow('GET', CAR + '/?#!/roadtax');
    const userdata = await G('/webresources/carcommon/getuserdata/username');
    const afm = (userdata.match(/<afm>\s*(\d{9})\s*<\/afm>/i) || [])[1] || (strip(userdata).match(/(\d{9})/) || [])[1];
    if (!afm) { http.dump('mycar_userdata.txt', userdata); http.log('[traffic] runningafm not found (δες mycar_userdata.txt)'); return { ok: false, reason: 'NoAfm' }; }
    await G('/webresources/carcommon/getDownTime/username');
    const years5 = await G('/webresources/tkcommon/get5Years/');
    http.dump('mycar_5years.json', years5);

    const wantYear = (inp.year || '').trim();
    const years = wantYear ? [wantYear]
      : [...new Set([...years5.matchAll(/"validfiscalyear"\s*:\s*"(\d{4})"/g)].map(m => m[1]))];
    http.log('[traffic] ΑΦΜ ' + afm + ' | έτη: ' + (years.join(', ') || '—'));

    const result = { portal: this.portal, afm, retrievedAt: new Date().toISOString(), years: {} };
    const pdfs = [];
    for (const y of years) {
      try {
        if (!years5.includes('"validfiscalyear":"' + y + '"') && !new RegExp('"validfiscalyear"\\s*:\\s*"' + y + '"').test(years5)) {
          result.years[y] = 'NotAvailable'; http.log('[traffic ' + y + '] μη διαθέσιμο'); continue;
        }
        const vehicles = await G('/webresources/tkcommon/getAllVehicles/' + afm);
        if (!vehicles.includes('Επιλογή όλων')) { result.years[y] = 'NoVehicles'; http.log('[traffic ' + y + '] χωρίς οχήματα'); continue; }
        const chk = await G('/webresources/tkcommon/getChkAfmArkyklPeriod/' + afm + '/' + ALL + '/' + y + '/12');
        if (!chk.includes('<fiscalyear>' + y + '</fiscalyear>')) { result.years[y] = 'NotFound'; http.log('[traffic ' + y + '] δεν βρέθηκε'); continue; }
        const doc = await http.getDoc(CAR + '/webresources/tkcommon/getPrintPDF3/' + afm + '/' + ALL + '/' + y + '/12');
        if (!doc.buffer) { result.years[y] = 'NoPDF'; http.log('[traffic ' + y + '] δεν επέστρεψε PDF (ct=' + doc.ct + ')'); continue; }
        const f = 'TELH_KYKLOFORIAS_' + afm + '_' + y + '.pdf';
        fs.writeFileSync(path.join(http.dlDir, f), doc.buffer); pdfs.push(f);
        result.years[y] = { pdf: f, bytes: doc.buffer.length };
        http.log('[traffic ' + y + '] ✅ PDF -> ' + f + ' (' + doc.buffer.length + ' b)');
      } catch (e) { result.years[y] = { error: String(e && e.message || e) }; http.log('[traffic ' + y + '] error ' + (e && e.message ? e.message : e)); }
    }

    const jf = path.join(http.dlDir, 'AADE_traffic_fees_' + afm + '.json');
    fs.writeFileSync(jf, JSON.stringify(result, null, 2));
    http.log('[aade-traffic-fees] ✅ saved -> ' + path.basename(jf) + ' (' + pdfs.length + ' PDF)');
    return { ok: true, files: [path.basename(jf), ...pdfs] };
  },
};
