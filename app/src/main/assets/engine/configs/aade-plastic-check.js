/*
 * configs/aade-plastic-check.js  --  Περιβαλλοντική Εισφορά για τα Πλαστικά Προϊόντα (ν.4736/2020 αρ.4)
 * =============================================================================
 * Έλεγχος ΥΠΟΧΡΕΩΣΗΣ / ΕΚΚΡΕΜΟΤΗΤΑΣ ΥΠΟΒΟΛΗΣ ανά τρίμηνο + λήψη PDF (δήλωση & ειδοποίηση πληρωμής) +
 * Ταυτότητα Οφειλής / ποσό / λήξη + κατάσταση πληρωμής από την ΑΑΔΕ. ΚΑΘΑΡΟ HTTP.
 *
 * TaxSystem: EpsilonSubmit `Plastic_V1` («Περιβαλλοντική Εισφορά για τα Πλαστικά Προϊόντα (Α.Α.Δ.Ε. - Εφαρμογές
 * Εντύπων)») — browser-υποβολή: login TAXISnet (GSIS OAM, όπως όλες οι εφαρμογές www1.aade.gr), click στο tile
 * `.btn2.btn.custom-panel.panel.panel-blue`, μετά angular scope: formdata.etos/etosChanged(), formdata.trimino/
 * periodChanged(), eidos1..4 = CUP_QTY/COVER_QTY/CONTAINER_QTY/COVERFOOD_QTY, ypologismos().
 * Εφαρμογή: https://www1.aade.gr/saadeapps3/Plastic (AngularJS· endpoints από js/app.js + views/*.js, live 23/10/2026).
 *
 * ΠΡΩΤΟΚΟΛΛΟ webresources/plastic/*:
 *   GET  getToken                 -> {result: base64url(JSON "token")}  -> header Authorization σε κάθε POST
 *   POST <ep>  body = base64(JSON {p1,p2,...}), Content-Type application/json -> {result: base64url(JSON)}
 *   ⚠ Πρέπει πρώτα getDownTime + getuserdata (όπως το init() της εφαρμογής) — αλλιώς ΟΛΑ δίνουν 500.
 *   getuserdata                   -> {afm, onomatepwnymo, isAccountant, ...}
 *   getDataAFM {p1:afmΦορολ, p2:afmΧρήστη}           -> στοιχεία υπόχρεου (epitid)
 *   getEtos    {p1, p2}           -> [{etos, t1..t4}] έτη/τρίμηνα ΑΝΟΙΧΤΑ για υποβολή (t>0 = προσωρινή δήλωση)
 *   getSearchApplication {p1:afmΦορολ, p2:afmΧρήστη} -> [{etos, trimhno, synolo, transId, ypoxAfm, onoma}] υποβληθείσες
 *   getPrintPDF {p1:afmΦορολ, p2:transId, p3:1|2, p4:afmΧρήστη} -> PDF (1 = Δήλωση, 2 = Ειδοποίηση Πληρωμής)
 *   GET getIsActiveEpitid/{χρήστης}/{φορολ}/{έτος}/{τρίμηνο}  -> {isActive, dateFrom, dateTo}  (υπόχρεος την περίοδο;)
 *   GET getMarginalDateData/{χρήστης}/{φορολ}/{έτος}/{τρίμηνο} -> {targetDate (προθεσμία), fineAmount, booksType}
 *
 * ΠΕΡΙΟΔΙΚΟΤΗΤΑ: η εισφορά είναι ΠΑΝΤΑ ΤΡΙΜΗΝΙΑΙΑ (TAXPLA_PERIOD 1..4 στην εφαρμογή). Το `periodicity` έρχεται από άλλο
 * module (M=μηνιαίος, Q=τριμηνιαίος, …)· για μηνιαίο υπόχρεο ο μήνας αντιστοιχίζεται στο τρίμηνο που τον περιέχει.
 *
 * ΚΑΤΑΣΤΑΣΗ ανά τρίμηνο:
 *   SUBMITTED_DEBT / SUBMITTED_PAID  υπάρχει δήλωση (+ πληρωμή κατά ΑΑΔΕ)
 *   PENDING_SUBMISSION               υπόχρεος (isActive), η περίοδος έληξε, ΔΕΝ υπάρχει δήλωση (+ overdue αν πέρασε η προθεσμία)
 *   NOT_OBLIGATED                    isActive=false (η πύλη δεν επιτρέπει/απαιτεί δήλωση για την περίοδο)
 *   NOT_DUE                          η περίοδος δεν έχει λήξει
 *
 * INPUTS: user/pass TAXISnet · period (2026 | Q3-2026 | 2026-09 | κενό = τρέχον έτος) · periodicity (Q|M, default Q)
 *         · afm (προαιρετικό: ΑΦΜ πελάτη όταν συνδέεται λογιστής) · pdf (κενό/1 = λήψη, 0 = όχι) · payments (κενό/1 | 0)
 * OUTPUT: PLASTIC_<ΑΦΜ>_<αίτημα>.json + PLASTIC_<ΑΦΜ>_<έτος>-Q<n>_<transId>_{DILOSI|EIDOPOIISI}.pdf
 */
'use strict';
const path = require('path');
const fs = require('fs');
const PAY = require('../lib/aade-payments.js');
const T = require('../lib/pdf-text.js');

const b64u = (s) => Buffer.from(String(s || '').replace(/-/g, '+').replace(/_/g, '/'), 'base64').toString('utf8');
// {result: base64url(JSON)}· κάποια endpoints διπλο-κωδικοποιούν (JSON string που περιέχει JSON) — το token είναι απλό string
const parseResult = (txt) => {
  const r = b64u(JSON.parse(txt).result);
  let v; try { v = JSON.parse(r); } catch (e) { return r; }
  if (typeof v === 'string') { try { return JSON.parse(v); } catch (e) { return v; } }
  return v;
};
const dot2slash = (d) => (d || '').replace(/\./g, '/');
const ymd = (d) => d.getFullYear() + '-' + String(d.getMonth() + 1).padStart(2, '0') + '-' + String(d.getDate()).padStart(2, '0');
// targetDate «2025-04-29T21:00:00Z[UTC]» = μεσάνυχτα ώρας Ελλάδας της 30/04 -> ημερομηνία Europe/Athens
const athensDate = (iso) => { const d = new Date(String(iso || '').replace(/\[UTC\]$/, '')); return isNaN(d) ? null : d.toLocaleDateString('en-CA', { timeZone: 'Europe/Athens' }); };
const isoToDmy = (iso) => iso ? iso.split('-').reverse().join('/') : null;

// period -> {year, quarters:[1..4]}  (μήνας -> το τρίμηνο του)
function parseRequest(raw, periodicity, now = new Date()) {
  const s = (raw || '').trim().toUpperCase();
  const notes = [];
  let m;
  if (!s) return { year: now.getFullYear(), quarters: [1, 2, 3, 4], label: String(now.getFullYear()), notes };
  if ((m = s.match(/^(\d{4})$/))) return { year: +m[1], quarters: [1, 2, 3, 4], label: m[1], notes };
  if ((m = s.match(/^Q([1-4])[-/ ]?(\d{4})$/)) || (m = s.match(/^([1-4])[-/ ]?Q[-/ ]?(\d{4})$/))) return { year: +m[2], quarters: [+m[1]], label: m[2] + '-Q' + m[1], notes };
  if ((m = s.match(/^(\d{4})[-/ ]?Q([1-4])$/))) return { year: +m[1], quarters: [+m[2]], label: m[1] + '-Q' + m[2], notes };
  if ((m = s.match(/^(\d{4})[-/.](\d{1,2})$/)) || (m = s.match(/^(\d{1,2})[-/.](\d{4})$/))) {
    const [y, mo] = m[1].length === 4 ? [+m[1], +m[2]] : [+m[2], +m[1]];
    const q = Math.ceil(mo / 3);
    notes.push('Η εισφορά είναι τριμηνιαία: ο μήνας ' + mo + '/' + y + ' ελέγχεται στο ' + q + 'ο τρίμηνο' + (/^M/i.test(periodicity || '') ? ' (μηνιαίος υπόχρεος)' : ''));
    return { year: y, quarters: [q], label: y + '-Q' + q, notes };
  }
  return null;
}

async function parseNotice(buf) {
  const L = await T.pdfLines(buf);
  const to = (L.map(l => l.text).find(t => /^\d{9} \d{9} \d{12}$/.test(t)) || null);
  return {
    tautotitaOfeilis: to,
    amount: PAY.num(T.valueBelow(L, 'ΠΟΣΟ ΔΗΛΩΣΗΣ', /€/)),
    payableBy: dot2slash(T.valueBelow(L, 'ΠΛΗΡΩΤΕΟ ΜΕΧΡΙ', /\d\d\.\d\d\.\d{4}/)) || null,
    vevaiosiDate: dot2slash(T.valueRight(L, 'ΗΜΕΡΟΜΗΝΙΑ ΒΕΒΑΙΩΣΗΣ', /\d\d\.\d\d\.\d{4}/)) || null,
    declarationNo: T.valueRight(L, 'ΑΡΙΘΜΟΣ ΔΗΛΩΣΗΣ'),
    paymentType: T.valueRight(L, 'ΤΥΠΟΣ ΠΛΗΡΩΜΗΣ'),
    doy: T.valueRight(L, 'ΔΟΥ'),
  };
}
async function parseDeclaration(buf) {
  const L = await T.pdfLines(buf);
  const items = [];
  for (const name of ['ΚΥΠΕΛΛΑΚΙ ΓΙΑ ΠΟΤΑ', 'ΚΑΛΥΜΜΑ/ΚΑΠΑΚΙ ΚΥΠΕΛΛΟΥ', 'ΠΕΡΙΕΚΤΗΣ ΤΡΟΦΙΜΩΝ', 'ΚΑΛΥΜΜΑ/ΚΑΠΑΚΙ ΠΕΡΙΕΚΤΗ ΤΡΟΦΙΜΩΝ']) {
    const r = T.findLabel(L, name); if (!r) continue;
    const row = L.filter(l => l.page === r.page && Math.abs(l.y - r.y) <= 4 && l.x > r.x + r.w);
    const qty = row.find(l => /^\d[\d.]*$/.test(l.text));
    const euros = row.filter(l => /€/.test(l.text)).sort((a, b) => a.x - b.x);
    items.push({ item: name, quantity: qty ? Number(qty.text.replace(/\./g, '')) : 0, unit: euros[0] ? PAY.num(euros[0].text) : null, total: euros.length > 1 ? PAY.num(euros[euros.length - 1].text) : 0 });
  }
  return {
    declarationNo: T.valueRight(L, 'ΑΡΙΘ. ΔΗΛ'),
    submittedOn: T.valueRight(L, 'ΗΜ/ΝΙΑ ΥΠΟΒ.ΔΗΛ.', /\d\d\/\d\d\/\d{4}/),
    total: PAY.num(T.valueRight(L, 'ΓΕΝΙΚΟ ΣΥΝΟΛΟ', /€/)),
    items,
  };
}

module.exports = {
  id: 'aade-plastic-check',
  title: 'Περιβαλλοντική Εισφορά Πλαστικών Προϊόντων: υποχρέωση/εκκρεμότητα ανά τρίμηνο + PDF + οφειλή/ΤΟ',
  portal: 'AADE saadeapps3/Plastic (GSIS OAM)',
  subsystem: 'EpsilonSubmit Plastic_V1 (login) + REST εφαρμογής',
  actions: ['check', 'retrieve'],
  inputs: [
    { key: 'user', label: 'TAXISnet username', env: 'AADE_USER' },
    { key: 'pass', label: 'TAXISnet password', env: 'AADE_PASS', hidden: true },
    { key: 'period', label: 'Περίοδος (2026 | Q3-2026 | 2026-09 · κενό = τρέχον έτος)', env: 'PLASTIC_PERIOD', optional: true },
    { key: 'periodicity', label: 'Περιοδικότητα υπόχρεου από άλλο module (Q=τριμηνιαίος, M=μηνιαίος· default Q)', env: 'PLASTIC_PERIODICITY', optional: true },
    { key: 'afm', label: 'ΑΦΜ φορολογουμένου (μόνο αν συνδέεται λογιστής· κενό = ο ίδιος)', env: 'PLASTIC_AFM', optional: true },
    { key: 'pdf', label: 'Λήψη PDF; (κενό/1 = ναι, 0 = όχι)', env: 'PLASTIC_PDF', optional: true },
    { key: 'payments', label: 'Κατάσταση πληρωμής από ΑΑΔΕ; (κενό/1 = ναι, 0 = όχι)', env: 'PLASTIC_PAYMENTS', optional: true },
  ],
  parseRequest, parseNotice, parseDeclaration,

  async run(http, inp, lib) {
    const off = (v) => /^(0|όχι|no?)$/i.test((v || '').trim());
    const req = parseRequest(inp.period, inp.periodicity);
    if (!req) { http.log('Άγνωστη μορφή περιόδου: ' + inp.period); return { ok: false, reason: 'BadPeriod' }; }
    for (const n of req.notes) http.log('[plastic] ' + n);
    const L = await lib.aadeLogin(http, inp);
    if (!L.ok) { http.log('AADE LOGIN FAILED: ' + L.reason); return { ok: false, reason: L.reason }; }
    const B = new URL('/saadeapps3/Plastic/', L.AADE).toString();
    const WS = B + 'webresources/plastic/';
    await http.follow('GET', B);
    const token = async () => { const r = await http.api('GET', WS + 'getToken?' + Math.random()); if (r.status !== 200) throw new Error('getToken ' + r.status); return parseResult(r.text); };
    const body = (d) => Buffer.from(JSON.stringify(d || {}), 'utf8').toString('base64');
    const post = async (ep, d) => {
      const r = await http.api('POST', WS + ep + '?' + Date.now(), body(d), { 'Content-Type': 'application/json;charset=utf-8;', Authorization: await token() });
      if (r.status === 204 || (r.status === 200 && !r.text)) return null;   // getDownTime απαντά συχνά 204
      if (r.status !== 200) throw new Error(ep + ' HTTP ' + r.status);
      return parseResult(r.text);
    };
    const postPdf = async (d) => {
      const r = await http.apiForm('POST', WS + 'getPrintPDF?' + Date.now(), body(d), { 'Content-Type': 'application/json;charset=utf-8;', Authorization: await token() });
      return r.buffer || null;                       // απαντά PDF bytes με Content-Type text/plain
    };
    const getJson = async (ep) => { const r = await http.api('GET', WS + ep + '?' + Date.now()); if (r.status !== 200) throw new Error(ep + ' HTTP ' + r.status); return JSON.parse(r.text); };

    // init όπως η εφαρμογή (χωρίς αυτά όλα τα επόμενα -> 500)
    await post('getDownTime', {});
    const user = await post('getuserdata', {});
    const me = user.afm;
    const afm = (inp.afm || '').trim() || me;
    const tp = await post('getDataAFM', { p1: afm, p2: me });
    http.log('[plastic] χρήστης ' + me + (afm !== me ? ' για ' + afm : '') + ' · ' + (tp.onomatepwnymo || user.onomatepwnymo || ''));

    const open = await post('getEtos', { p1: afm, p2: me });                     // ανοιχτά για υποβολή
    const subs = await post('getSearchApplication', { p1: afm, p2: me });        // υποβληθείσες
    const wantPdf = !off(inp.pdf);
    const files = [];
    const now = new Date();
    const report = { portal: this.portal, afm, name: tp.onomatepwnymo || '', user: me, request: req.label, periodicity: (inp.periodicity || 'Q').toUpperCase(),
      notes: req.notes, checkedAt: now.toISOString(), openForSubmission: open, quarters: [] };

    for (const q of req.quarters) {
      const Y = req.year;
      const out = { year: Y, quarter: q, period: q + 'ο Τρίμηνο ' + Y };
      const end = new Date(Y, q * 3, 0, 23, 59, 59);
      try { const a = await getJson('getIsActiveEpitid/' + me + '/' + afm + '/' + Y + '/' + q); out.isActive = a.isActive === true || a.isActive === 'true'; out.from = a.dateFrom; out.to = a.dateTo; }
      catch (e) { out.isActiveError = e.message; }
      try { const m = await getJson('getMarginalDateData/' + me + '/' + afm + '/' + Y + '/' + q); out.deadline = athensDate(m.targetDate); out.lateFine = m.fineAmount; out.booksType = m.booksType || null; }
      catch (e) { out.deadlineError = e.message; }
      const ow = (Array.isArray(open) ? open : []).find(x => +x.etos === Y);
      if (ow) out.openForSubmission = !!ow['t' + q], out.provisional = Number(ow['t' + q]) > 0;

      out.submissions = [];
      for (const s of (Array.isArray(subs) ? subs : []).filter(x => +x.etos === Y && +x.trimhno === q)) {
        const sub = { transId: s.transId, total: s.synolo };
        if (wantPdf) {
          const tag = 'PLASTIC_' + afm + '_' + Y + '-Q' + q + '_' + s.transId;
          const d = await postPdf({ p1: afm, p2: s.transId, p3: 1, p4: me });
          if (d) { const f = tag + '_DILOSI.pdf'; fs.writeFileSync(path.join(http.dlDir, f), d); files.push(f); sub.pdfDeclaration = f; try { sub.declaration = await parseDeclaration(d); } catch (e) { sub.parseError = e.message; } }
          if (Number(s.synolo) > 0) {
            const n = await postPdf({ p1: afm, p2: s.transId, p3: 2, p4: me });
            if (n) { const f = tag + '_EIDOPOIISI.pdf'; fs.writeFileSync(path.join(http.dlDir, f), n); files.push(f); sub.pdfNotice = f; try { Object.assign(sub, await parseNotice(n)); } catch (e) { sub.parseError = e.message; } }
            // κάποιες ειδοποιήσεις έχουν κενά «ΠΛΗΡΩΤΕΟ ΜΕΧΡΙ»/«ΗΜ. ΒΕΒΑΙΩΣΗΣ» -> προθεσμία από getMarginalDateData
            if (!sub.payableBy && out.deadline) { sub.payableBy = isoToDmy(out.deadline); sub.payableBySource = 'getMarginalDateData'; }
            if (!sub.amount && Number(s.synolo) > 0) sub.amount = Number(s.synolo);
          }
        }
        out.submissions.push(sub);
        http.log('[plastic] ' + out.period + ': δήλωση ' + s.transId + ' · ' + Number(s.synolo).toFixed(2) + ' €' + (sub.tautotitaOfeilis ? ' · ΤΟ ' + sub.tautotitaOfeilis + ' · πληρωτέο έως ' + sub.payableBy : ''));
      }
      if (out.submissions.length) out.status = 'SUBMITTED';
      else if (now <= end) out.status = 'NOT_DUE';
      else if (out.isActive === false) out.status = 'NOT_OBLIGATED';
      else { out.status = 'PENDING_SUBMISSION'; if (out.deadline && ymd(now) > out.deadline) out.overdue = true; }
      report.quarters.push(out);
    }

    if (!off(inp.payments)) {
      try { report.payments = await this.verifyPayments(http, L, report); }
      catch (e) { report.payments = { error: String(e && e.message || e) }; http.log('[plastic] πληρωμές: ' + report.payments.error); }
    }
    for (const q of report.quarters) if (q.status === 'SUBMITTED') {
      const paid = q.submissions.every(s => !(s.total > 0) || (s.payment && /^(PAID|PAID_LATE|CLOSED_UNMATCHED)$/.test(s.payment.status)));
      q.status = q.submissions.some(s => s.total > 0) ? (report.payments && !report.payments.error ? (paid ? 'SUBMITTED_PAID' : 'SUBMITTED_DEBT') : 'SUBMITTED_DEBT') : 'SUBMITTED_ZERO';
    }

    const jf = 'PLASTIC_' + afm + '_' + req.label + '.json';
    fs.writeFileSync(path.join(http.dlDir, jf), JSON.stringify(report, null, 2));
    http.log('[aade-plastic-check] ✅ saved -> ' + jf + (files.length ? ' (+' + files.length + ' PDF)' : ''));
    for (const q of report.quarters) http.log('  ' + q.period + ': ' + q.status + (q.overdue ? ' (ΕΚΠΡΟΘΕΣΜΗ, προθεσμία ' + q.deadline + ')' : q.deadline ? ' · προθεσμία ' + q.deadline : '')
      + q.submissions.map(s => ' | ' + Number(s.total).toFixed(2) + ' €' + (s.tautotitaOfeilis ? ' ΤΟ ' + s.tautotitaOfeilis : '') + (s.payment ? ' ' + s.payment.status + (s.payment.payments.length ? ' ' + s.payment.payments.map(p => p.date + ' ' + p.amount.toFixed(2) + '€').join(',') : '') : '')).join(''));
    return { ok: true, files: [jf, ...files], summary: report.quarters.map(q => ({ period: q.period, status: q.status })) };
  },

  // ίδια συνεδρία ΑΑΔΕ -> «Στοιχεία Πληρωμών» + ανεξόφλητες οφειλές (lib/aade-payments.js)
  async verifyPayments(http, L, report) {
    const items = report.quarters.flatMap(q => q.submissions.filter(s => s.tautotitaOfeilis && s.amount > 0).map(s => ({ q, s })));
    if (!items.length) return { checked: 0 };
    const ctx = { AADE: L.AADE, getText: async (u) => (await http.follow('GET', u)).text, postText: async (u, f) => (await http.follow('POST', u, f)).text, dump: http.dump.bind(http) };
    // από την αρχή της πρώτης περιόδου (η ημ. βεβαίωσης λείπει σε κάποιες ειδοποιήσεις)
    const first = items.map(x => x.q).sort((a, b) => a.year - b.year || a.quarter - b.quarter)[0];
    const from = '01/' + String((first.quarter - 1) * 3 + 1).padStart(2, '0') + '/' + first.year;
    const pays = await PAY.fetchPayments(ctx, from);
    const debts = await PAY.fetchOpenDebts(ctx);
    items.sort((x, y) => (pays.has(PAY.toKey(y.s.tautotitaOfeilis)) ? 1 : 0) - (pays.has(PAY.toKey(x.s.tautotitaOfeilis)) ? 1 : 0));
    const totals = { amount: 0, paid: 0, unpaid: 0 };
    for (const { s } of items) {
      s.payment = PAY.classify({ to: s.tautotitaOfeilis, amount: s.amount, issuedOn: s.vevaiosiDate, dueDate: s.payableBy, kindRe: /ΠΛΑΣΤΙΚ/ }, pays, debts);
      totals.amount += s.amount; totals.paid += s.payment.paid; totals.unpaid += s.payment.unpaid || 0;
    }
    for (const k of Object.keys(totals)) totals[k] = +totals[k].toFixed(2);
    return { checked: items.length, paymentsFound: pays.size, openDebts: debts.length, totals };
  },
};
