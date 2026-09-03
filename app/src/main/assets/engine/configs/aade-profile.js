/*
 * configs/aade-profile.js  --  Στοιχεία ταυτότητας πελάτη από το Μητρώο ΑΑΔΕ
 * =============================================================================
 * Ό,τι κάνει το EpsilonSubmit όταν πατάς «άντληση από TAXIS» στη νέα καρτέλα:
 * με τους κωδικούς TAXISnet του πελάτη, γεμίζει **ονοματεπώνυμο, ΑΦΜ, ΔΟΥ** και
 * βρίσκει τι είδους υπόχρεος είναι.
 *
 * Ίδια εφαρμογή και ίδιο login με τα `aade-registry` / `aade-email`
 * (saadeapps3/comregistry, GSIS OAM). Η διαφορά από το `aade-registry` είναι ο
 * σκοπός: εκείνο κατεβάζει τα **PDF** των μητρώων για τον φάκελο του πελάτη,
 * αυτό επιστρέφει **πεδία** για να συμπληρωθεί η φόρμα. Δεν γράφει PDF.
 *
 *   GET {host}/saadeapps3/comregistry/                          (app session)
 *   GET .../webresources/infomytaxisnet/getuserdata/username    -> afm, ονοματεπώνυμο
 *   GET .../webresources/infomytaxisnet/getMhtrwoFusikou/{afm}  -> ΔΟΥ, στοιχεία φυσικού
 *   GET .../webresources/infomytaxisnet/getMhtrwoEpixeirhshs/{afm} -> ΔΟΥ, έναρξη
 *   GET .../webresources/infomytaxisnet/getLdapInfo/{afm}       -> email επικοινωνίας
 *
 * ΕΙΔΟΣ ΥΠΟΧΡΕΟΥ — από τον συνδυασμό των δύο μητρώων, όχι από μαντεψιά:
 *
 *   μητρώο φυσικού  |  μητρώο επιχείρησης  |  είδος
 *   ────────────────┼──────────────────────┼──────────────────────
 *   ναι             |  όχι                 |  ΙΔΙΩΤΗΣ
 *   ναι             |  ναι                 |  ΑΤΟΜΙΚΗ ΕΠΙΧΕΙΡΗΣΗ
 *   όχι             |  ναι                 |  ΝΟΜΙΚΟ ΠΡΟΣΩΠΟ
 *
 * Έχει σημασία πέρα από την ετικέτα: **ΑΜΚΑ έχουν μόνο οι δύο πρώτες**
 * περιπτώσεις, γιατί μόνο εκεί υπάρχει φυσικό πρόσωπο από πίσω. Σε νομικό
 * πρόσωπο το πεδίο ΑΜΚΑ δεν είναι κενό — δεν υφίσταται.
 *
 * ΣΗΜΕΙΩΣΗ: config της εφαρμογής (δεν υπάρχει στον runner). Απαιτεί μόνο
 * fs/path, ώστε να τρέχει και στους δύο κόσμους.
 *
 * INPUTS: TAXISnet user/pass (+ προαιρετικά ΑΦΜ-στόχος).
 * OUTPUT: AADE_profile_<ΑΦΜ>.json  ·  out = τα πεδία της φόρμας
 */
'use strict';
const path = require('path');
const fs = require('fs');

function tag(xml, name) {
  const m = xml.match(new RegExp('<' + name + '>([^<]*)</' + name + '>', 'i'));
  return m ? m[1].trim() : '';
}

/**
 * «ΠΑΠΑΔΟΠΟΥΛΟΣ  ΓΕΩΡΓΙΟΣ» -> { name: 'ΠΑΠΑΔΟΠΟΥΛΟΣ', firstName: 'ΓΕΩΡΓΙΟΣ' }
 *
 * Η ΑΑΔΕ χωρίζει επώνυμο και όνομα με **δύο** κενά. Όταν λείπει ο διπλός
 * διαχωριστής δεν μαντεύουμε: υπάρχουν σύνθετα επώνυμα («ΠΑΠΑ ΓΕΩΡΓΙΟΥ») και
 * ένα λάθος σπάσιμο θα έμπαινε στα email που θα φύγουν προς τον πελάτη. Τότε
 * μπαίνει όλο στο επώνυμο και το διορθώνει ο χρήστης.
 */
function splitName(full) {
  const value = (full || '').replace(/\s+$/, '');
  const parts = value.split(/\s{2,}/).filter(Boolean);
  if (parts.length >= 2) {
    return { name: parts[0].trim(), firstName: parts.slice(1).join(' ').trim() };
  }
  return { name: value.trim(), firstName: '' };
}

function pickEmail(xml) {
  const valid = (e) => /^[^\s@]+@[^\s@]+\.[^\s@]{2,}$/.test(e);
  for (const name of ['mail2', 'mailemep', 'mail']) {
    const candidate = tag(xml, name);
    if (candidate && valid(candidate)) return { email: candidate.toLowerCase(), source: name };
  }
  return { email: '', source: '' };
}

module.exports = {
  id: 'aade-profile',
  title: 'Άντληση στοιχείων πελάτη από το TAXIS (ονοματεπώνυμο, ΑΦΜ, ΔΟΥ)',
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

    await http.follow('GET', REG + '/');
    const userdata = await G(W + '/getuserdata/username');
    http.dump('profile_userdata.xml', userdata);

    const loginAfm = tag(userdata, 'afm');
    const afm = (inp.vat || '').trim() || loginAfm;
    if (!afm) { http.log('[profile] δεν βρέθηκε ΑΦΜ'); return { ok: false, reason: 'NoAfm' }; }

    const fysiko = await G(W + '/getMhtrwoFusikou/' + encodeURIComponent(afm));
    http.dump('profile_fysiko.xml', fysiko);
    const hasFysiko = fysiko.includes('<afm>' + afm + '</afm>');

    const epix = await G(W + '/getMhtrwoEpixeirhshs/' + encodeURIComponent(afm));
    http.dump('profile_epix.xml', epix);
    const hasEpix = epix.includes('<hmenarxhs>');

    const kind = hasFysiko
      ? (hasEpix ? 'ΑΤΟΜΙΚΗ ΕΠΙΧΕΙΡΗΣΗ' : 'ΙΔΙΩΤΗΣ')
      : (hasEpix ? 'ΝΟΜΙΚΟ ΠΡΟΣΩΠΟ' : '');

    if (!kind) {
      http.log('[profile] ο ΑΦΜ ' + afm + ' δεν έχει ούτε μητρώο φυσικού ούτε επιχείρησης');
      return { ok: false, reason: 'NoRegistry' };
    }

    // Το ονοματεπώνυμο του λογαριασμού ισχύει μόνο όταν ο ΑΦΜ-στόχος είναι ο
    // ίδιος. Όταν ο λογιστής ρωτά για τρίτον, το `getuserdata` επιστρέφει τα
    // **δικά του** στοιχεία — και θα γράφαμε το όνομά του στην καρτέλα πελάτη.
    const sameAccount = afm === loginAfm;
    let name = '';
    let firstName = '';
    if (kind === 'ΝΟΜΙΚΟ ΠΡΟΣΩΠΟ') {
      name = sameAccount ? (tag(userdata, 'longepwnymia') || tag(userdata, 'onomatepwnymo')) : '';
    } else if (sameAccount) {
      const split = splitName(tag(userdata, 'onomatepwnymo'));
      name = split.name;
      firstName = split.firstName;
    } else {
      // `epwnymoa` = ονοματεπώνυμο του ίδιου (τα b/c είναι πατρός και μητρός).
      // Εδώ δεν υπάρχει διπλό κενό, οπότε δεν σπάμε — το βλέπει ο χρήστης.
      name = tag(fysiko, 'epwnymoa');
    }

    const doy = tag(epix, 'doydescription') || tag(fysiko, 'armodiadoy');

    const ldap = await G(W + '/getLdapInfo/' + encodeURIComponent(afm) + '?' + Date.now());
    http.dump('profile_ldap.xml', ldap);
    const mail = pickEmail(ldap);

    // ── Σχέσεις φυσικού προσώπου ──────────────────────────────────────────
    //
    // Κρατάμε **μόνο** τον/τη σύζυγο, και μόνο ενεργή σχέση (`hmdiakophs` κενό).
    // Οι υπόλοιπες σχέσεις — κληρονόμοι, συσχετιζόμενοι — είναι τρίτα πρόσωπα
    // που δεν είναι πελάτες του γραφείου· δεν έχουμε λόγο να τα αντιγράψουμε
    // (ελαχιστοποίηση, άρθρο 5 παρ. 1 στοιχ. γ).
    const spouse = { afm: '', name: '', since: '' };
    if (hasFysiko) {
      try {
        const raw = await G(W + '/getSxeseisFysiko/' + encodeURIComponent(afm));
        http.dump('profile_sxeseis.json', raw);
        const rows = JSON.parse(raw || '[]');
        const found = (Array.isArray(rows) ? rows : []).find((r) =>
          /ΣΥΖΥΓ/i.test(String(r && r.eidossxeshs || '')) && !r.hmdiakophs);
        if (found) {
          spouse.afm = String(found.afmsxeshs || '').trim();
          spouse.name = String(found.epwnymia || '').trim();
          spouse.since = String(found.hmenarxhs || '').trim();
          http.log('[profile] βρέθηκε σύζυγος στις σχέσεις μητρώου');
        }
      } catch (e) {
        // Δεν χαλάει η άντληση: τα στοιχεία ταυτότητας έχουν ήδη βρεθεί.
        http.log('[profile] σχέσεις: ' + (e && e.message ? e.message : e));
      }
    }

    const out = {
      portal: this.portal,
      afm,
      name,
      firstName,
      kind,
      doy,
      email: mail.email,
      emailSource: mail.source,
      // Ο ΑΜΚΑ αντλείται χωριστά (MyAMKA, άλλη πύλη). Εδώ λέμε μόνο αν υπάρχει.
      hasAmka: kind !== 'ΝΟΜΙΚΟ ΠΡΟΣΩΠΟ',
      active: hasEpix
        ? !/ΔΙΑΚΟΠ|ΑΝΕΝΕΡΓ/i.test(tag(epix, 'katastashepixeirhshs'))
        : /ΚΑΝΟΝΙΚΗ/i.test(tag(fysiko, 'katastashforologoumenoy')),
      businessStart: tag(epix, 'hmenarxhs'),
      // Η οικογενειακή κατάσταση έρχεται ήδη μέσα στο μητρώο φυσικού
      // (`oikogkatastash`, π.χ. «ΕΓΓΑΜΟΣ-Η») και μέχρι τώρα πεταγόταν.
      //
      // **Δεν** δίνει τον ΑΦΜ του συζύγου — αυτόν τον δίνει μόνο το ETAK, και
      // μόνο όταν ο σύζυγος εμφανίζεται στο Ε9. Λέει όμως **αν** υπάρχει, που
      // αρκεί για να ζητηθεί το εκκαθαριστικό συζύγου (τυπώνεται με τους
      // κωδικούς του ίδιου του υπόχρεου) και για να προταθεί καρτέλα.
      maritalStatus: tag(fysiko, 'oikogkatastash'),
      spouseAfm: spouse.afm,
      spouseName: spouse.name,
      spouseSince: spouse.since,
      retrievedAt: new Date().toISOString(),
    };

    const file = 'AADE_profile_' + afm + '.json';
    fs.writeFileSync(path.join(http.dlDir, file), JSON.stringify(out, null, 2));
    http.log('[profile] ✅ ' + afm + ' · ' + kind + ' · ΔΟΥ ' + (doy || '—'));
    return { ok: true, files: [file], out };
  },
};
