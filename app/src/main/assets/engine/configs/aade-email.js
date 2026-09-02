/*
 * configs/aade-email.js  --  Email πελάτη από το Μητρώο Επικοινωνίας ΑΑΔΕ
 * =============================================================================
 * Το export «Κωδικοί Υπόχρεων» των λογιστικών προγραμμάτων ΔΕΝ έχει στήλη με το
 * email του πελάτη — μόνο «EMAIL INTRASTAT», που είναι άλλο πράγμα. Η μόνη
 * αξιόπιστη πηγή είναι το Μητρώο Επικοινωνίας της ΑΑΔΕ.
 *
 * Ίδια εφαρμογή και ίδιο login με το `aade-registry` (saadeapps3/comregistry,
 * GSIS OAM), οπότε δεν χρειάζεται τίποτα καινούργιο πέρα από ένα endpoint:
 *
 *   GET {host}/saadeapps3/comregistry/                              (app session)
 *   GET .../webresources/infomytaxisnet/getuserdata/username        -> <afm>
 *   GET .../webresources/infomytaxisnet/getLdapInfo/{afm}?<ts>      -> XML
 *
 * Το XML δίνει έως τρεις διευθύνσεις. Η σειρά προτεραιότητας είναι αυτή που
 * χρησιμοποιεί και η ίδια η ΑΑΔΕ στις ειδοποιήσεις της:
 *
 *   mail2     η διεύθυνση επικοινωνίας που δήλωσε ο υπόχρεος
 *   mailemep  η διεύθυνση του εκπροσώπου/λογιστή
 *   mail      η παλιά διεύθυνση του TAXISnet
 *
 * ΣΗΜΕΙΩΣΗ: αυτό το config είναι δικό της εφαρμογής (δεν υπάρχει στον runner).
 * Απαιτεί μόνο fs/path, ώστε να τρέχει και στους δύο κόσμους αν χρειαστεί.
 *
 * INPUTS: TAXISnet user/pass (+ προαιρετικά ΑΦΜ-στόχος).
 * OUTPUT: AADE_email_<ΑΦΜ>.json
 */
'use strict';
const path = require('path');
const fs = require('fs');

function pickEmail(xml) {
  const get = (tag) => {
    const m = xml.match(new RegExp('<' + tag + '>([^<]*)</' + tag + '>', 'i'));
    return m ? m[1].trim() : '';
  };
  const mail2 = get('mail2');
  const mailemep = get('mailemep');
  const mail = get('mail');

  // Ίδιος έλεγχος με το FILTER_VALIDATE_EMAIL: κάτι@κάτι.κάτι, χωρίς κενά.
  const valid = (e) => /^[^\s@]+@[^\s@]+\.[^\s@]{2,}$/.test(e);
  let chosen = '';
  let source = '';
  for (const [name, candidate] of [['mail2', mail2], ['mailemep', mailemep], ['mail', mail]]) {
    if (candidate && valid(candidate)) { chosen = candidate.toLowerCase(); source = name; break; }
  }
  return { email: chosen, source, mail2, mailemep, mail };
}

module.exports = {
  id: 'aade-email',
  title: 'Email πελάτη από το Μητρώο Επικοινωνίας ΑΑΔΕ',
  portal: 'AADE myAADE comregistry (GSIS OAM)',
  subsystem: 'ScanMyData Tax Center',
  actions: ['retrieve'],
  inputs: [
    { key: 'user', label: 'TAXISnet username', env: 'AADE_USER' },
    { key: 'pass', label: 'TAXISnet password', env: 'AADE_PASS', hidden: true },
    { key: 'vat', label: 'ΑΦΜ-στόχος (κενό = ο ΑΦΜ του λογαριασμού)', env: 'AADE_VAT', optional: true },
  ],

  async run(http, inp, lib) {
    const L = await lib.aadeLogin(http, inp);
    if (!L.ok) { http.log('AADE LOGIN FAILED: ' + L.reason); return { ok: false, reason: L.reason }; }

    const REG = new URL('/saadeapps3/comregistry', L.AADE).toString();
    const W = REG + '/webresources/infomytaxisnet';
    const G = async (url) => (await http.follow('GET', url)).text;

    // establish app session (comregistry root)
    await http.follow('GET', REG + '/');

    let afm = (inp.vat || '').trim();
    if (!afm) {
      const userdata = await G(W + '/getuserdata/username');
      http.dump('email_userdata.xml', userdata);
      afm = (userdata.match(/<afm>\s*(\d{9})\s*<\/afm>/i) || [])[1] || '';
    }
    if (!afm) { http.log('[email] δεν βρέθηκε ΑΦΜ (δες email_userdata.xml)'); return { ok: false, reason: 'NoAfm' }; }

    // Το timestamp είναι cache-buster — το ίδιο κάνει και η σελίδα της ΑΑΔΕ.
    const xml = await G(W + '/getLdapInfo/' + encodeURIComponent(afm) + '?' + Date.now());
    http.dump('email_ldap.xml', xml);

    const parsed = pickEmail(xml);
    const out = {
      portal: this.portal,
      afm,
      email: parsed.email,
      source: parsed.source,
      mail2: parsed.mail2,
      mailemep: parsed.mailemep,
      mail: parsed.mail,
      retrievedAt: new Date().toISOString(),
    };
    const name = 'AADE_email_' + afm + '.json';
    fs.writeFileSync(path.join(http.dlDir, name), JSON.stringify(out, null, 2));

    if (!parsed.email) {
      http.log('[email] δεν βρέθηκε έγκυρη διεύθυνση για τον ΑΦΜ ' + afm);
      return { ok: false, reason: 'NoEmail', files: [name], out };
    }

    http.log('[email] ✅ ' + afm + ' -> ' + parsed.email + ' (' + parsed.source + ')');
    return { ok: true, files: [name], out };
  },
};
