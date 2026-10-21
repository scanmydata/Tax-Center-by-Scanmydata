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
 *   ναι             |  ανοιχτή             |  ΑΤΟΜΙΚΗ ΕΠΙΧΕΙΡΗΣΗ
 *   ναι             |  διακομμένη          |  ΙΔΙΩΤΗΣ
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

/**
 * Το **κινητό** από το ίδιο `getLdapInfo` που δίνει και το email.
 *
 * Επαληθεύτηκε ζωντανά: το XML περιέχει `mobilenumber`, `mobilenumberemep` και
 * `phonenumber`. Το κινητό είναι ο μόνος αριθμός που έχει νόημα για Viber — το
 * `phonenumber` είναι σταθερό (`21…`) και δεν αντιστοιχεί σε λογαριασμό Viber.
 *
 * Κρατάμε μόνο ελληνικά κινητά (`69` + 8 ψηφία). Ένα λάθος εδώ δεν είναι
 * αθώο: θα άνοιγε συνομιλία με **άγνωστο τρίτο** και το πρώτο πράγμα που θα
 * του έφτανε θα ήταν φορολογικά έντυπα ξένου ανθρώπου.
 */
function pickMobile(xml) {
  for (const name of ['mobilenumber', 'mobilenumberemep']) {
    const digits = tag(xml, name).replace(/\D/g, '').replace(/^0030|^30(?=69)/, '');
    if (/^69\d{8}$/.test(digits)) return { mobile: digits, source: name };
  }
  return { mobile: '', source: '' };
}

/*
 * Καθεστώς ΦΠΑ από το μητρώο επιχείρησης — ίδια ερμηνεία με το ScanmyData
 * (_ar_detect_vat_profile_for_afm) και το taxis_rest_api (vatProfileFromRegistry):
 *  - απαλλαγή μικρών επιχειρήσεων, απαλλασσόμενο ή ειδικό αγροτών: ΜΗ υπόχρεος,
 *    ακόμη κι αν η «υπαγωγή» γράφει ΝΑΙ·
 *  - συχνότητα από το κείμενο της κατηγορίας βιβλίων («…ΜΕ ΜΗΝΙΑΙΑ ΠΕΡΙΟΔΟ ΦΠΑ»
 *    έχει φανεί σε Β κατηγορίας) — ΤΡΙΜΗΝ ελέγχεται πριν από ΜΗΝΙΑ, γιατί το
 *    «ΤΡΙΜΗΝΙΑΙΑ» περιέχει το «ΜΗΝΙΑ»·
 *  - αλλιώς Γ (διπλογραφικά) = μηνιαία, Β (απλογραφικά) = τριμηνιαία.
 */
// Το μητρώο γράφει συχνά με **λατινικά** γράμματα που μοιάζουν ελληνικά: ζωντανά
// (Οκτ. 2026) `<ypagwghfpa>NAI</ypagwghfpa>` — N, A, I λατινικά. Χωρίς αυτόν τον
// πίνακα το «ΝΑΙ» δεν ταιριάζει ποτέ και η υπαγωγή βγαίνει άγνωστη.
const LATIN_LOOKALIKE = { A: 'Α', B: 'Β', E: 'Ε', H: 'Η', I: 'Ι', K: 'Κ', M: 'Μ', N: 'Ν', O: 'Ο', P: 'Ρ', T: 'Τ', X: 'Χ', Y: 'Υ', Z: 'Ζ' };
const up = (v) => String(v == null ? '' : v).trim().toUpperCase().normalize('NFD').replace(/[̀-ͯ]/g, '')
  .replace(/[A-Z]/g, (c) => LATIN_LOOKALIKE[c] || c);

function booksLetter(raw) {
  const b = up(raw);
  if (b.startsWith('Γ') || b.startsWith('G') || b.includes('ΔΙΠΛΟΓΡΑΦ')) return 'Γ';
  if (b.startsWith('Β') || b.startsWith('B') || b.includes('ΑΠΛΟΓΡΑΦ')) return 'Β';
  return '';
}

function vatProfile(epix) {
  const flag = up(tag(epix, 'ypagwghfpa'));
  const regime = tag(epix, 'kathestwsfpa');
  const entryMode = tag(epix, 'troposentaxhsfpa');
  const books = tag(epix, 'kathgoriabibliwn');
  const R = up(regime);
  let subject = flag === 'ΝΑΙ' ? true : (flag === 'ΟΧΙ' ? false : null);
  const smallBusiness = R.includes('ΜΙΚΡΩΝ ΕΠΙΧΕΙΡΗΣΕΩΝ') || (R.includes('ΑΠΑΛΛΑΣΣΟΜΕΝ') && up(entryMode).includes('ΠΡΟΑΙΡΕΤΙΚ'));
  const exempt = R.includes('ΑΠΑΛΛΑΣΣΟΜΕΝ') || R.includes('ΜΙΚΡΩΝ ΕΠΙΧΕΙΡΗΣΕΩΝ');
  const farmers = R.includes('ΑΓΡΟΤ');
  if (exempt || farmers) subject = false;
  const B = up(books);
  const letter = booksLetter(books);
  let period = '';
  if (subject !== false) {
    if (B.includes('ΤΡΙΜΗΝ')) period = 'ΤΡΙΜΗΝΙΑΙΑ';
    else if (B.includes('ΜΗΝΙΑ')) period = 'ΜΗΝΙΑΙΑ';
    else if (letter === 'Γ') period = 'ΜΗΝΙΑΙΑ';
    else if (letter === 'Β') period = 'ΤΡΙΜΗΝΙΑΙΑ';
  }
  // Μία γραμμή για την καρτέλα: «Υπόχρεος ΦΠΑ — μηνιαία δήλωση».
  let label = '';
  if (subject === true) label = 'Υπόχρεος ΦΠΑ' + (period ? ' — ' + (period === 'ΜΗΝΙΑΙΑ' ? 'μηνιαία' : 'τριμηνιαία') + ' δήλωση' : '');
  else if (subject === false) label = 'Μη υπόχρεος ΦΠΑ' + (smallBusiness ? ' (απαλλαγή μικρών επιχειρήσεων)' : farmers ? ' (ειδικό καθεστώς αγροτών)' : exempt ? ' (απαλλασσόμενες δραστηριότητες)' : '');
  return { subject, period, label, regime, entryMode, books, booksLetter: letter, smallBusiness, farmers };
}

/*
 * ΠΛΗΡΗ ΣΤΟΙΧΕΙΑ ΜΗΤΡΩΟΥ — ό,τι δείχνει το «Μητρώο & Επικοινωνία» της ΑΑΔΕ.
 *
 * Τα πεδία επαληθεύτηκαν ζωντανά (Οκτ. 2026) σε ιδιώτη με διακομμένη ατομική
 * και σε ΟΕ. Πηγαίνουν στην καρτέλα ως **ενότητες με ετικέτες**, ώστε η
 * εφαρμογή να τα δείχνει χωρίς να ξέρει ένα-ένα τα ονόματα της ΑΑΔΕ: ένα νέο
 * πεδίο εδώ φαίνεται στο κινητό χωρίς νέα έκδοση της οθόνης.
 *
 * Τι ΔΕΝ μπαίνει, σκόπιμα (ελαχιστοποίηση, άρθρο 5 παρ. 1 στοιχ. γ): IBAN,
 * «συσχετιζόμενοι ΑΦΜ» (τρίτοι που δεν είναι πελάτες), ιστορικά μεταβολών.
 * Μέλη και σχέσεις μπαίνουν μόνο **ενεργά**.
 */
const FYSIKO_FIELDS = [
  ['Ονοματεπώνυμο', 'epwnymoa'],
  ['Πατρώνυμο', 'epwnymob'],
  ['Μητρώνυμο', 'epwnymoc'],
  ['Ημερομηνία γέννησης', 'hmgennhshs'],
  ['Τόπος / χώρα γέννησης', 'xwragennhshs'],
  ['Υπηκοότητα', 'yphkoothta'],
  ['Είδος ταυτότητας', 'eidostaytothtas'],
  ['Αριθμός ταυτότητας', 'artaytothtas'],
  ['Εκδούσα αρχή', 'ekdoysaarxh'],
  ['Ημερομηνία έκδοσης', 'hmekdoshs'],
  ['Διεύθυνση κατοικίας', 'dieykatoikias'],
  ['Αρμόδια ΔΟΥ', 'armodiadoy'],
  ['Κατάσταση φορολογούμενου', 'katastashforologoumenoy'],
  ['Οικογενειακή κατάσταση', 'oikogkatastash'],
];
const EPIX_FIELDS = [
  ['Νομική μορφή', 'nomikhmorfh'],
  ['Κατάσταση', 'katastashepixeirhshs'],
  ['Έναρξη εργασιών', 'hmenarxhs'],
  ['Διακοπή εργασιών', 'hmdiakophs'],
  ['Αιτία διακοπής', 'aitiadiakophs'],
  ['Διεύθυνση έδρας', 'dieyaskhshsdrasthrio'],
  ['ΔΟΥ', 'doydescription'],
  ['Αρ. ΓΕΜΗ', 'arithmoseggrafhsgemh'],
  ['Εγγραφή στο ΓΕΜΗ', 'hmeggrafhsgemh'],
  ['Επιμελητήριο', 'epimelhthriosyllogos'],
  ['Εκπρόσωπος', 'ekprosoposnp'],
  ['Κεφάλαιο', 'kefalaio'],
  ['Αρ. καταστατικού', 'arithmoskatastatikoy'],
  ['Αρχή επικύρωσης', 'arxhepikyrwshs'],
  ['Ημερομηνία επικύρωσης', 'hmepikyrwshs'],
  ['Λήξη διάρκειας', 'lhxhdiarkeias'],
  ['Κατηγορία μη φυσικού προσώπου', 'kathgoriamhfp'],
  ['Κατηγορία βιβλίων', 'kathgoriabibliwn'],
  ['Καθεστώς ΦΠΑ', 'kathestwsfpa'],
  ['Υπαγωγή σε ΦΠΑ', 'ypagwghfpa'],
  ['Τρόπος ένταξης σε ΦΠΑ', 'troposentaxhsfpa'],
  ['Ενδοκοινοτικές συναλλαγές', 'endokoinotikessynallages'],
  ['Τρόπος άσκησης δραστηριότητας', 'troposaskhshsdrasthriothtas'],
  ['Τρόπος ένταξης', 'troposentaxhs'],
  ['Προέλευση', 'firmorigin'],
  ['Λήξη διαχειριστικής περιόδου', 'lhxhdiaxeiristikhsperiodoy'],
  ['Λήξη πρώτης διαχειριστικής περιόδου', 'lhxhprwthsdiax'],
];

function fieldRows(xml, fields) {
  return fields.map(([label, name]) => [label, tag(xml, name)]).filter(([, v]) => v);
}

/** Μια λίστα JSON της ΑΑΔΕ. Ό,τι δεν διαβάζεται είναι άδεια λίστα — ποτέ σφάλμα. */
function rowsOf(raw) {
  try {
    const v = JSON.parse(raw || '[]');
    return Array.isArray(v) ? v : [];
  } catch (e) {
    return [];
  }
}

const s = (v) => (v == null ? '' : String(v).trim());
const live = (r) => !s(r && r.hmdiakophs);
const join = (...parts) => parts.map(s).filter(Boolean).join(' · ');
const share = (p) => (s(p) ? s(p).replace('.', ',') + '%' : '');

/** Οι ενότητες του «Περισσότερα στοιχεία» της καρτέλας. */
async function registryDetails(G, W, afm, ctx) {
  const sections = [];
  const add = (title, rows) => { if (rows.length) sections.push({ title, rows }); };
  const list = async (name) => {
    try { return rowsOf(await G(W + '/' + name + '/' + encodeURIComponent(afm))); } catch (e) { return []; }
  };

  if (ctx.hasFysiko) add('Φυσικό πρόσωπο', fieldRows(ctx.fysiko, FYSIKO_FIELDS));
  if (ctx.hasEpix) {
    const rows = fieldRows(ctx.epix, EPIX_FIELDS);
    if (ctx.longName) rows.unshift(['Επωνυμία', ctx.longName]);
    add(ctx.hasFysiko ? 'Ατομική επιχείρηση' : 'Επιχείρηση', rows);

    // ΚΑΔ: κύρια πρώτη, και μόνο όσες ασκούνται (χωρίς ημερομηνία διακοπής).
    const kad = (await list('getMhtrwoDrastiriothtesEpixeir')).filter(live)
      .sort((a, b) => (/ΚΥΡΙΑ/i.test(s(b.eidos)) ? 1 : 0) - (/ΚΥΡΙΑ/i.test(s(a.eidos)) ? 1 : 0));
    add('Δραστηριότητες (ΚΑΔ)', kad.map((r) => [s(r.kwdikos), join(r.drasthriothta, r.eidos, s(r.hmenarxhs) && 'από ' + s(r.hmenarxhs))]));

    if (!ctx.hasFysiko) {
      const members = (await list('getMelh')).filter(live);
      add('Μέλη', members.map((r) => [s(r.epwnymia), join('ΑΦΜ ' + s(r.afmmelous), r.eidossymmetoxhs, share(r.percentage), s(r.hmenarxhs) && 'από ' + s(r.hmenarxhs))]));
    }
    const roles = (await list('getSxeseisEpixeirhshs')).filter(live);
    add('Εκπρόσωποι και σχέσεις επιχείρησης', roles.map((r) => [s(r.epwnymia), join('ΑΦΜ ' + s(r.afmsxeshs), r.eidossxeshs, s(r.hmenarxhs) && 'από ' + s(r.hmenarxhs))]));
  }
  if (ctx.hasFysiko) {
    const holdings = (await list('getSymmetoxesNomikaProswpa')).filter(live);
    add('Συμμετοχές σε νομικά πρόσωπα', holdings.map((r) => [s(r.epwnymiaepixeirhshs), join('ΑΦΜ ' + s(r.afmepixeirhshs), r.eidossymmetoxhs, share(r.percentage), s(r.hmenarxhs) && 'από ' + s(r.hmenarxhs))]));
  }
  return sections;
}

module.exports = {
  vatProfile, // για τα τεστ
  registryDetails, // για τα τεστ
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

    /*
     * Η διακοπή εργασιών ΔΕΝ φαίνεται στην «κατάσταση επιχείρησης».
     *
     * Επαληθεύτηκε ζωντανά σε ΑΦΜ με παύση εργασιών από το 2018: το μητρώο
     * επιστρέφει `katastashepixeirhshs = ΕΝΕΡΓΗ` **και ταυτόχρονα**
     * `hmdiakophs = 11/12/2018`. Δεν είναι αντίφαση — η ίδια η ΑΑΔΕ το γράφει
     * δίπλα στο πεδίο: «εμφανίζεται η τελευταία κατάσταση πριν τη διακοπή
     * εργασιών». Άρα η κατάσταση δεν λέει αν η επιχείρηση ζει· το λέει μόνο η
     * ημερομηνία διακοπής.
     */
    const businessEnd = tag(epix, 'hmdiakophs');
    const businessOpen = hasEpix && !businessEnd;

    // **Κλειστή ατομική επιχείρηση σημαίνει ιδιώτης.** Ο άνθρωπος υπάρχει και
    // εξακολουθεί να έχει Ε1· η επιχείρηση όχι. Αν έμενε «ΑΤΟΜΙΚΗ ΕΠΙΧΕΙΡΗΣΗ»,
    // η εφαρμογή θα ζητούσε κάθε χρόνο Ε3, ΦΠΑ και καρτέλα εργοδότη για κάποιον
    // που έκλεισε πριν από χρόνια — μία βέβαιη αποτυχία ανά έντυπο, και ο
    // λογιστής να ψάχνει τι χάλασε.
    //
    // Το νομικό πρόσωπο μένει νομικό πρόσωπο ακόμη και διακομμένο: δεν υπάρχει
    // φυσικό πρόσωπο να ξαναγίνει.
    const kind = hasFysiko
      ? (businessOpen ? 'ΑΤΟΜΙΚΗ ΕΠΙΧΕΙΡΗΣΗ' : 'ΙΔΙΩΤΗΣ')
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

    // Η ΔΟΥ της επιχείρησης ισχύει όσο η επιχείρηση ζει. Μετά τη διακοπή
    // αρμόδια είναι η ΔΟΥ του φυσικού προσώπου — και οι δύο έρχονται στην ίδια
    // απάντηση, οπότε δεν κοστίζει τίποτα να διαλέξουμε τη σωστή.
    const doy = (businessOpen ? tag(epix, 'doydescription') : '') ||
      tag(fysiko, 'armodiadoy') || tag(epix, 'doydescription');

    const ldap = await G(W + '/getLdapInfo/' + encodeURIComponent(afm) + '?' + Date.now());
    http.dump('profile_ldap.xml', ldap);
    const mail = pickEmail(ldap);
    const cell = pickMobile(ldap);

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
      // Το κινητό του Μητρώου Επικοινωνίας. Χρησιμεύει στην αποστολή με Viber,
      // που αναγνωρίζει τον παραλήπτη **από τον αριθμό** και όχι από διεύθυνση.
      mobile: cell.mobile,
      mobileSource: cell.source,
      // Ο ΑΜΚΑ αντλείται χωριστά (MyAMKA, άλλη πύλη). Εδώ λέμε μόνο αν υπάρχει.
      hasAmka: kind !== 'ΝΟΜΙΚΟ ΠΡΟΣΩΠΟ',
      // Ενεργός **πελάτης**, όχι ενεργή επιχείρηση. Όποιος έκλεισε την ατομική
      // του εξακολουθεί να υποβάλλει Ε1, άρα κρίνεται από το μητρώο φυσικού.
      // Μόνο εκεί που δεν υπάρχει άνθρωπος από πίσω κρίνει η επιχείρηση.
      active: hasFysiko
        ? /ΚΑΝΟΝΙΚΗ/i.test(tag(fysiko, 'katastashforologoumenoy'))
        : businessOpen && !/ΔΙΑΚΟΠ|ΑΝΕΝΕΡΓ/i.test(tag(epix, 'katastashepixeirhshs')),
      businessStart: tag(epix, 'hmenarxhs'),
      // Πότε και γιατί έκλεισε. Δεν είναι διακοσμητικά: η καρτέλα τα δείχνει
      // για να εξηγήσει γιατί το είδος βγήκε «ΙΔΙΩΤΗΣ» σε κάποιον που ο
      // λογιστής θυμάται ως επιτηδευματία.
      businessEnd,
      businessEndReason: tag(epix, 'aitiadiakophs'),
      formerBusiness: hasEpix && !businessOpen,
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
      // Καθεστώς ΦΠΑ — μόνο για επιχείρηση που ζει. Η κλειστή δεν δηλώνει ΦΠΑ,
      // και ο ιδιώτης δεν έχει καθόλου μητρώο επιχείρησης.
      vat: businessOpen ? vatProfile(epix) : null,
      // Τα πλήρη στοιχεία μητρώου, σε ενότητες — βλ. registryDetails.
      details: await registryDetails(G, W, afm, {
        hasFysiko, hasEpix, fysiko, epix,
        longName: sameAccount && kind === 'ΝΟΜΙΚΟ ΠΡΟΣΩΠΟ' ? tag(userdata, 'longepwnymia') : '',
      }).catch((e) => { http.log('[profile] στοιχεία: ' + (e && e.message ? e.message : e)); return []; }),
      retrievedAt: new Date().toISOString(),
    };

    const file = 'AADE_profile_' + afm + '.json';
    fs.writeFileSync(path.join(http.dlDir, file), JSON.stringify(out, null, 2));
    http.log('[profile] ✅ ' + afm + ' · ' + kind + ' · ΔΟΥ ' + (doy || '—') +
      (out.formerBusiness ? ' · επιχείρηση σε διακοπή από ' + businessEnd : '') +
      (out.vat && out.vat.label ? ' · ' + out.vat.label : ''));
    return { ok: true, files: [file], out };
  },
};
