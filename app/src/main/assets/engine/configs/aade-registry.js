/*
 * configs/aade-registry.js  --  Στοιχεία Επιχείρησης & Στοιχεία Φυσικών/Νομικών Προσώπων (Μητρώο ΑΑΔΕ)
 * Source: Hyper.Server.Tax.dll  Easy_Aade.RegistryInfo / BusinessInfo / PersonalInfo (decompiled ~54147 / 41932 / 53445).
 * App: myAADE  saadeapps3/comregistry  (GSIS OAM login, then app session).
 *
 * FAITHFUL flow (after aadeLogin OAM):
 *   GET {host}/saadeapps3/comregistry/                                             (establish app JSESSIONID)
 *   GET .../webresources/infomytaxisnet/getuserdata/username                       -> <afm> του χρήστη
 *   GET .../webresources/infomytaxisnet/getDownTime/username
 *   ── ΣΤΟΙΧΕΙΑ ΕΠΙΧΕΙΡΗΣΗΣ (BusinessInfo / LegalInfo) ──
 *   GET .../getMhtrwoEpixeirhshs/{afm}     -> αν περιέχει "<hmenarxhs>" υπάρχει επιχείρηση
 *   GET .../getPrintPDFepixSection/{afm}/1/1/1/1/{L}/1/1/1/1/0/0/0/0/0/0/4         (L=0 φυσικό/ατομική, 1 νομικό)  -> PDF
 *   ── ΣΤΟΙΧΕΙΑ ΦΥΣΙΚΟΥ ΠΡΟΣΩΠΟΥ (PersonalInfo, μόνο αν όχι νομικό) ──
 *   GET .../getMhtrwoFusikou/{afm}         -> αν περιέχει "<afm>{afm}</afm>" είναι φυσικό
 *   GET .../getPrintPDFepixSection/{afm}/0/0/0/1/0/1/1/0/0/0/0/1/0/0/0/3           -> PDF
 *
 * INPUTS: TAXISnet user/pass (+ προαιρετικά ΑΦΜ-στόχος, default = ο ΑΦΜ του λογαριασμού).
 * OUTPUT: STOIXEIA_*.pdf + AADE_registry_<afm>.json (τα strings των μητρώων φυσικού/επιχείρησης).
 */
'use strict';
const path = require('path');
const fs = require('fs');

// Flatten XML leaf tags -> { tag: value | [values] } (keeps the raw text strings for JSON).
function xmlToObj(xml) {
  const out = {};
  const re = /<([A-Za-z_][\w.:-]*)>([^<]*)<\/\1>/g;
  let m;
  while ((m = re.exec(xml))) {
    const key = m[1], val = m[2].trim();
    if (val === '') continue;
    if (out[key] === undefined) out[key] = val;
    else if (Array.isArray(out[key])) out[key].push(val);
    else out[key] = [out[key], val];
  }
  return out;
}

module.exports = {
  id: 'aade-registry',
  title: 'Στοιχεία Επιχείρησης & Φυσικών/Νομικών Προσώπων (Μητρώο)',
  portal: 'AADE saadeapps3/comregistry (GSIS OAM)',
  subsystem: 'Hyper.Server',
  actions: ['retrieve', 'download'],
  inputs: [
    { key: 'user', label: 'TAXISnet username', env: 'AADE_USER' },
    { key: 'pass', label: 'TAXISnet password', env: 'AADE_PASS', hidden: true },
    { key: 'type', label: 'Είδος: ΦΥΣΙΚΟ / ΝΟΜΙΚΟ (κενό = αυτόματα ό,τι υπάρχει)', env: 'AADE_REG_TYPE', optional: true },
    { key: 'vat', label: 'ΑΦΜ-στόχος (κενό = ο ΑΦΜ του λογαριασμού)', env: 'AADE_VAT', optional: true },
  ],

  async run(http, inp, lib) {
    const L = await lib.aadeLogin(http, inp);
    if (!L.ok) { http.log('AADE LOGIN FAILED: ' + L.reason); return { ok: false, reason: L.reason }; }
    const REG = new URL('/saadeapps3/comregistry', L.AADE).toString();
    const W = REG + '/webresources/infomytaxisnet';
    const G = async (rel) => (await http.follow('GET', rel[0] === '/' ? new URL(rel, L.AADE).toString() : rel)).text;

    // establish app session (comregistry root)
    await http.follow('GET', REG + '/');
    const userdata = await G(W + '/getuserdata/username');
    http.dump('registry_userdata.xml', userdata);
    let afm = (inp.vat || '').trim();
    if (!afm) afm = (userdata.match(/<afm>\s*(\d{9})\s*<\/afm>/i) || [])[1] || '';
    if (!afm) { http.log('[registry] δεν βρέθηκε ΑΦΜ (δες registry_userdata.xml)'); return { ok: false, reason: 'NoAfm' }; }
    await G(W + '/getDownTime/username');
    http.log('[registry] ΑΦΜ ' + afm);

    // Επιλογή χρήστη: ΦΥΣΙΚΟ / ΝΟΜΙΚΟ / (κενό = αυτόματα ό,τι υπάρχει)
    const typeRaw = (inp.type || '').trim().toUpperCase();
    const forcePhysical = /ΦΥΣΙΚ|FYS|PHYS/.test(typeRaw);
    const forceLegal = /ΝΟΜΙΚ|NOMIK|LEGAL/.test(typeRaw);
    http.log('[registry] είδος: ' + (forcePhysical ? 'ΦΥΣΙΚΟ' : forceLegal ? 'ΝΟΜΙΚΟ' : 'ΑΥΤΟΜΑΤΑ'));

    const result = { portal: this.portal, afm, requestedType: forcePhysical ? 'ΦΥΣΙΚΟ' : forceLegal ? 'ΝΟΜΙΚΟ' : 'ΑΥΤΟΜΑΤΑ', retrievedAt: new Date().toISOString() };
    const files = [];

    // ── ΣΤΟΙΧΕΙΑ ΦΥΣΙΚΟΥ ΠΡΟΣΩΠΟΥ (PersonalInfo) — αν ζητηθεί ΦΥΣΙΚΟ ή ΑΥΤΟΜΑΤΑ ──
    let isPhysical = false;
    if (!forceLegal) {
      const fusiko = await G(W + '/getMhtrwoFusikou/' + afm);
      http.dump('registry_fusiko.xml', fusiko);
      isPhysical = fusiko.includes('<afm>' + afm + '</afm>');
      if (isPhysical) {
        result.fysiko = xmlToObj(fusiko);
        const url = W + '/getPrintPDFepixSection/' + afm + '/0/0/0/1/0/1/1/0/0/0/0/1/0/0/0/3';
        const doc = await http.getDoc(url);
        if (doc.buffer) {
          const f = 'STOIXEIA_FYSIKOU_' + afm + '.pdf';
          fs.writeFileSync(path.join(http.dlDir, f), doc.buffer); files.push(f);
          result.fysikoPdf = { pdf: f, bytes: doc.buffer.length };
          http.log('[registry] ✅ ΦΥΣΙΚΟΥ -> ' + f + ' (' + doc.buffer.length + ' b)');
        } else { result.fysikoPdf = 'NoPDF'; http.log('[registry] ΦΥΣΙΚΟΥ: δεν επέστρεψε PDF (ct=' + doc.ct + ')'); }
      } else if (forcePhysical) {
        http.log('[registry] ⚠ ζητήθηκε ΦΥΣΙΚΟ αλλά δεν βρέθηκε μητρώο φυσικού για ' + afm);
      } else {
        http.log('[registry] δεν είναι φυσικό πρόσωπο (νομικό) — παράλειψη ΦΥΣΙΚΟΥ');
      }
    }

    // ── ΣΤΟΙΧΕΙΑ ΕΠΙΧΕΙΡΗΣΗΣ (BusinessInfo=φυσικό/ατομική, LegalInfo=νομικό) — αν όχι μόνο ΦΥΣΙΚΟ ──
    if (!forcePhysical) {
      const epix = await G(W + '/getMhtrwoEpixeirhshs/' + afm);
      http.dump('registry_epix.xml', epix);
      if (epix.includes('<hmenarxhs>')) {
      const legal = forceLegal ? true : !isPhysical;     // νομικό αν το ζήτησε ο χρήστης ή δεν υπάρχει μητρώο φυσικού
      result.epixeirisi = xmlToObj(epix);
      result.epixeirisiType = legal ? 'ΝΟΜΙΚΟ' : 'ΦΥΣΙΚΟ/ΑΤΟΜΙΚΗ';
      const L5 = legal ? '1' : '0';
      const url = W + '/getPrintPDFepixSection/' + afm + '/1/1/1/1/' + L5 + '/1/1/1/1/0/0/0/0/0/0/4';
      const doc = await http.getDoc(url);
      if (doc.buffer) {
        const f = 'STOIXEIA_EPIXEIRISIS_' + (legal ? 'NOMIKO_' : '') + afm + '.pdf';
        fs.writeFileSync(path.join(http.dlDir, f), doc.buffer); files.push(f);
        result.epixeirisiPdf = { pdf: f, bytes: doc.buffer.length };
        http.log('[registry] ✅ ΕΠΙΧΕΙΡΗΣΗΣ' + (legal ? ' (ΝΟΜΙΚΟ)' : '') + ' -> ' + f + ' (' + doc.buffer.length + ' b)');
      } else { result.epixeirisiPdf = 'NoPDF'; http.log('[registry] ΕΠΙΧΕΙΡΗΣΗΣ: δεν επέστρεψε PDF (ct=' + doc.ct + ')'); }
      } else if (forceLegal) {
        http.log('[registry] ⚠ ζητήθηκε ΝΟΜΙΚΟ αλλά δεν βρέθηκε μητρώο επιχείρησης για ' + afm);
      } else {
        http.log('[registry] χωρίς ενεργή επιχείρηση (κανένα <hmenarxhs>) — παράλειψη ΕΠΙΧΕΙΡΗΣΗΣ');
      }
    }

    const jf = path.join(http.dlDir, 'AADE_registry_' + afm + '.json');
    fs.writeFileSync(jf, JSON.stringify(result, null, 2));
    http.log('[aade-registry] ✅ saved -> ' + path.basename(jf) + ' (' + files.length + ' PDF)');
    return { ok: true, files: [path.basename(jf), ...files] };
  },
};
