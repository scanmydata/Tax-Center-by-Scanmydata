/*
 * configs/dae-parepid-check.js  --  Τέλος Διαμονής Παρεπιδημούντων & Τέλος επί Ακαθαρίστων Εσόδων (dae.govapp.gr)
 * =============================================================================
 * Έλεγχος ΕΚΚΡΕΜΟΤΗΤΑΣ ΥΠΟΒΟΛΗΣ + ΟΦΕΙΛΗΣ ανά περίοδο στη νέα πλατφόρμα (από 01/04/2025) της ΑΑΔΕ/ΚΕΔΕ
 * https://dae.govapp.gr — ΚΑΘΑΡΟ HTTP (χωρίς browser).
 *
 * Πηγή: ΟΧΙ decompiled κώδικας (το TaxSystem κάνει μόνο login + click, DilwsiAkathEsodParepidimountwn).
 * Τα endpoints βγήκαν από το Angular bundle της πύλης (/angular/main.js) + την καταγραφή Playwright του χρήστη,
 * και επιβεβαιώθηκαν LIVE (23/10/2026, εταιρία, Σεπτέμβριος 2026).
 *
 * ΡΟΗ:
 *   1. GET  /authorization/ExternalLogin?provider=Gsis -> oauth2.gsis.gr login.jsp -> j_spring_security_check
 *      -> oauth/authorize (approval) -> /signin-gsis -> /Authorization/ExternalLoginCallback -> /ekkremotites
 *      (== lib.gsisSubmitAndApprove· cookie session του ASP.NET Core)
 *   2. GET  /authorization/UserInfo                         -> {tin, legalName, ...}
 *   3. GET  /api/Helper/Periodoi                            -> [{id, perigrafi, periodikiDilosiApo/Eos, typosPeriodouYpovolis 1=μήνας 2=τρίμηνο}]
 *   4. GET  /api/StatementsEpixirisi/GetPendingPeriodikes   -> «Εκκρεμότητες Περιοδικών Δηλώσεων (Φ2)» (προς υποβολή)
 *   5. POST /api/Periodoi/GetCompletedPeriods               -> «Περίοδοι με Υποβολές» (DataTables body)
 *   6. POST /api/StatementsYpovoli/GetYpovolesPeriodou      -> «Ιστορικό Υποβολών» της περιόδου
 *        body {start,length,searchColumns:[{columnName:'periodId',value,regex:false}],orders:[]}
 *        -> {id, tautotitaOfeilis, posoVevaiosis, posoYpoloipo, posoPliromonKefalaiou/Prosafxisis, posoDiagrafwn,
 *            teleutaiaImeraPrinProstimo, paymentState, seiraYpovolis, isDiorthotiki, hasAsymfonia, ...}
 *   7. GET  /api/StatementsYpovoli/GetYpovoliDetail/{id}    -> μόνο periodikiDilosi αν λείπει από τη λίστα
 *      (GET /api/StatementsYpovoli/RefreshTautotitaOfeilisForYpovoli/{id} όταν shouldHaveTO && !tautotitaOfeilis)
 *   8. GET  /ViewReports/ReportStatementYpovoli/{id}/{periodId} -> PDF «Δήλωση Απόδοσης» (μόνο όταν υπάρχει χρεωστικό ποσό)
 *
 * ⚠ Τα ποσά πληρωμής/paymentState διαβάζονται από τη ΛΙΣΤΑ (βήμα 6, ενημερωμένα). Το GetYpovoliDetail (βήμα 7) δίνει
 * τα ποσά της στιγμής υποβολής (πάντα «Απλήρωτη») — ΔΕΝ χρησιμοποιείται για πληρωμές. Επιπλέον διασταύρωση με την
 * ΑΑΔΕ (aade=1, default· η πύλη παραπέμπει στο myAADE για το ακριβές ποσό), με ΔΕΥΤΕΡΟ login (GSIS OAM, ξεχωριστό jar):
 *   9. POST taxisnet/info/protected/displayActualPaymentInfo.htm {docKind:'09123478DRSAP'=όλα, payDateFrom, payDateTo, payTo:''}
 *      «Στοιχεία Πληρωμών» για όλο το διάστημα -> γραμμές [ΔΟΥ, ημ/νία, ποσό, «Τ.Ο.», ΤΟ] -> map ΤΟ -> πληρωμές
 *  10. GET  taxisnet/info/protected/displayDebtInfoAndPay.htm -> ανεξόφλητες «ΤΕΛΟΣ ΠΑΡΕΠΙΔΗΜΟΥΝΤΩΝ» (βεβαίωση/υπόλοιπο)
 *   -> aade.status: PAID | PARTIAL | UNPAID | NOT_FOUND
 *
 * INPUTS: TAXISnet user/pass (της επιχείρησης) + period:
 *   «2026-09» | «09/2026» | «Σεπτέμβριος 2026» | «Q3-2026» / «Γ Τρίμηνο 2026» | «2026» (όλο το έτος) | κενό = προηγούμενος μήνας
 * OUTPUT: DAE_<ΑΦΜ>_<περίοδος>.json + PAREPID_<ΑΦΜ>_<YYYY-MM|YYYY-Qn>_<idΥποβολής>_<ποσό>.pdf
 */
'use strict';
const path = require('path');
const fs = require('fs');

const B = 'https://dae.govapp.gr';
const XHR = { 'X-Requested-With': 'XMLHttpRequest' };
const MONTHS = ['Ιανουάριος', 'Φεβρουάριος', 'Μάρτιος', 'Απρίλιος', 'Μάιος', 'Ιούνιος', 'Ιούλιος', 'Αύγουστος', 'Σεπτέμβριος', 'Οκτώβριος', 'Νοέμβριος', 'Δεκέμβριος'];
const QUARTERS = ['Α', 'Β', 'Γ', 'Δ'];
const norm = (s) => (s || '').normalize('NFD').replace(/[̀-ͯ]/g, '').toUpperCase().replace(/[΄'’]/g, '').replace(/\s+/g, ' ').trim();

// period input -> {kind:'month'|'quarter'|'year', year, n}
function parsePeriod(raw, now = new Date()) {
  const s = norm(raw);
  if (!s) { const d = new Date(now.getFullYear(), now.getMonth() - 1, 1); return { kind: 'month', year: d.getFullYear(), n: d.getMonth() + 1 }; }
  let m;
  if ((m = s.match(/^(\d{4})$/))) return { kind: 'year', year: +m[1] };
  if ((m = s.match(/^(\d{4})[-/.](\d{1,2})$/))) return { kind: 'month', year: +m[1], n: +m[2] };
  if ((m = s.match(/^(\d{1,2})[-/.](\d{4})$/))) return { kind: 'month', year: +m[2], n: +m[1] };
  if ((m = s.match(/^Q([1-4])[-/ ]?(\d{4})$/)) || (m = s.match(/^(\d{4})[-/ ]?Q([1-4])$/)))
    return /^\d{4}/.test(m[1]) ? { kind: 'quarter', year: +m[1], n: +m[2] } : { kind: 'quarter', year: +m[2], n: +m[1] };
  if ((m = s.match(/^([ΑΒΓΔ])\s*ΤΡΙΜΗΝΟ\s+(\d{4})$/))) return { kind: 'quarter', year: +m[2], n: QUARTERS.indexOf(m[1]) + 1 };
  const mi = MONTHS.findIndex(x => s.startsWith(norm(x).slice(0, 4)));
  if (mi >= 0 && (m = s.match(/(\d{4})/))) return { kind: 'month', year: +m[1], n: mi + 1 };
  return null;
}
const ymOf = (iso) => { const d = (iso || '').slice(0, 10).split('-'); return { y: +d[0], m: +d[1] }; };
// matches a portal period {perigrafi, periodikiDilosiApo, typosPeriodouYpovolis} against the request
function periodMatches(p, req) {
  const a = ymOf(p.periodikiDilosiApo || p.periodApo);
  if (a.y !== req.year) return false;
  if (req.kind === 'year') return true;
  const isQ = (p.typosPeriodouYpovolis === 2) || /Τρίμηνο/.test(p.perigrafi || p.periodPerigrafi || '');
  if (req.kind === 'month') return !isQ && a.m === req.n;
  return isQ && Math.ceil(a.m / 3) === req.n;
}
function periodTag(p) {
  const a = ymOf(p.periodikiDilosiApo);
  const isQ = p.typosPeriodouYpovolis === 2 || /Τρίμηνο/.test(p.perigrafi || '');
  return isQ ? a.y + '-Q' + Math.ceil(a.m / 3) : a.y + '-' + String(a.m).padStart(2, '0');
}
const PAYMENT_STATE = { 1: 'Απλήρωτη', 2: 'Μερική Πληρωμή', 3: 'Πληρωμένη' };
const money = (v) => Number(v || 0).toFixed(2);

async function apiJson(http, method, url, body) {
  const r = await http.api(method, url, body, XHR);
  if (r.status === 401 || r.status === 403) throw new Error('Unauthorized ' + r.status + ' ' + url);
  if (r.status !== 200) throw new Error('HTTP ' + r.status + ' ' + url);
  try { return JSON.parse(r.text); } catch (e) { throw new Error('Not JSON from ' + url + ': ' + r.text.slice(0, 200)); }
}
const paged = (cols) => ({ start: 0, length: 100, searchColumns: cols, orders: [] });

module.exports = {
  id: 'dae-parepid-check',
  title: 'Τέλος Παρεπιδημούντων & Ακαθαρίστων Εσόδων (dae.govapp.gr): εκκρεμότητα υποβολής + οφειλή + PDF',
  portal: 'dae.govapp.gr (ΑΑΔΕ/ΚΕΔΕ, GSIS OAuth2)',
  subsystem: 'standalone HTTP (Angular API)',
  actions: ['check', 'retrieve'],
  inputs: [
    { key: 'user', label: 'TAXISnet username (επιχείρησης)', env: 'AADE_USER' },
    { key: 'pass', label: 'TAXISnet password', env: 'AADE_PASS', hidden: true },
    { key: 'period', label: 'Περίοδος (2026-09 | Σεπτέμβριος 2026 | Q3-2026 | 2026 · κενό = προηγ. μήνας)', env: 'DAE_PERIOD', optional: true },
    { key: 'aade', label: 'Διασταύρωση πληρωμών με ΑΑΔΕ; (κενό/1 = ναι, 0 = όχι)', env: 'DAE_AADE', optional: true },
  ],
  parsePeriod, periodMatches, periodTag,

  async login(http, inp) {
    const r = await http.follow('GET', B + '/authorization/ExternalLogin?provider=Gsis');
    if (!/j_password/.test(r.text)) return { ok: false, reason: 'NoGsisLoginPage' };
    const g = await http.lib.gsisSubmitAndApprove(http, inp.user, inp.pass);
    if (!g.ok) return g;
    if (!/dae\.govapp\.gr/.test(g.page.url)) { http.dump('dae_login_end.html', g.page.text); return { ok: false, reason: 'LoginDidNotReturnToDae' }; }
    return { ok: true };
  },

  async run(http, inp, lib) {
    http.lib = lib;
    const req = parsePeriod(inp.period);
    if (!req) { http.log('Άγνωστη μορφή περιόδου: ' + inp.period); return { ok: false, reason: 'BadPeriod' }; }
    const L = await this.login(http, inp);
    if (!L.ok) { http.log('DAE LOGIN FAILED: ' + L.reason); return { ok: false, reason: L.reason }; }

    const user = await apiJson(http, 'GET', B + '/authorization/UserInfo');
    const afm = user.tin || inp.user;
    http.log('[dae] ' + afm + ' ' + (user.legalName || user.lastName || ''));

    const all = await apiJson(http, 'GET', B + '/api/Helper/Periodoi');
    const pending = await apiJson(http, 'GET', B + '/api/StatementsEpixirisi/GetPendingPeriodikes');
    const completed = (await apiJson(http, 'POST', B + '/api/Periodoi/GetCompletedPeriods', paged([]))).data || [];

    // περίοδοι του αιτήματος: όσες του portal ταιριάζουν και αφορούν την επιχείρηση (εκκρεμεί ή έχει υποβολή)
    const pendIds = new Set(pending.map(p => p.periodId));
    const doneIds = new Set(completed.map(p => p.id));
    let targets = all.filter(p => periodMatches(p, req));
    let relevant = targets.filter(p => pendIds.has(p.id) || doneIds.has(p.id));
    // περιοδικότητα επιχείρησης ≠ αιτήματος: μήνας σε τριμηνιαία -> το τρίμηνο που τον περιέχει· τρίμηνο σε μηνιαία -> οι 3 μήνες
    if (!relevant.length && req.kind !== 'year') {
      const alt = req.kind === 'month' ? { kind: 'quarter', year: req.year, n: Math.ceil(req.n / 3) } : null;
      const cand = all.filter(p => alt ? periodMatches(p, alt)
        : (periodMatches(p, { kind: 'year', year: req.year }) && p.typosPeriodouYpovolis !== 2 && Math.ceil(ymOf(p.periodikiDilosiApo).m / 3) === req.n));
      const rel2 = cand.filter(p => pendIds.has(p.id) || doneIds.has(p.id));
      if (rel2.length) { http.log('[dae] η επιχείρηση έχει ' + (alt ? 'τριμηνιαία' : 'μηνιαία') + ' περιοδικότητα -> ' + rel2.map(p => p.perigrafi).join(', ')); relevant = rel2; }
    }
    if (req.kind === 'year' || relevant.length) targets = relevant;
    const label = req.kind === 'year' ? String(req.year) : req.kind === 'month' ? req.year + '-' + String(req.n).padStart(2, '0') : req.year + '-Q' + req.n;
    http.log('[dae] αίτημα ' + label + ' -> ' + (targets.map(p => p.perigrafi + '#' + p.id).join(', ') || 'καμία περίοδος του portal'));

    const files = [];
    const report = { portal: this.portal, afm, name: user.legalName || '', request: label, checkedAt: new Date().toISOString(), periods: [] };
    for (const p of targets) report.periods.push(await this.checkPeriod(http, afm, p, pending, doneIds, files));
    if (!targets.length) report.periods.push({ period: label, status: 'NO_PERIOD', note: 'Η περίοδος δεν υπάρχει στο portal (ή δεν έχει ακόμα ανοίξει)' });
    if (!/^0|^όχι|^no?$/i.test((inp.aade || '').trim())) {
      try { report.aade = await this.aadeVerify(http, lib, inp, report); }
      catch (e) { report.aade = { error: String(e && e.message || e) }; http.log('[dae] ΑΑΔΕ διασταύρωση: ' + report.aade.error); }
    }

    const jf = 'DAE_' + afm + '_' + label + '.json';
    fs.writeFileSync(path.join(http.dlDir, jf), JSON.stringify(report, null, 2));
    http.log('[dae-parepid-check] ✅ saved -> ' + jf + (files.length ? ' (+' + files.length + ' PDF)' : ''));
    for (const r of report.periods) {
      const ch = (r.submissions || []).filter(x => Number(x.posoVevaiosis) > 0);
      http.log('  ' + r.period + ': ' + r.status + ch.map(x => ' | ΤΟ ' + x.tautotitaOfeilis + ' βεβ. ' + money(x.posoVevaiosis) + ' € πληρ. ' + money(x.paid)
        + ' € υπόλ. ' + money(x.posoYpoloipo) + ' € (' + x.paymentStateText + ', λήξη ' + x.deadline + ')'
        + (x.aade ? ' ΑΑΔΕ: ' + x.aade.status + (x.aade.payments.length ? ' ' + x.aade.payments.map(y => y.date).join(',') : '') : '')).join(''));
    }
    if (report.aade && report.aade.totals) http.log('  ΣΥΝΟΛΟ ' + label + ': βεβαιώθηκαν ' + money(report.aade.totals.vevaiosi) + ' € · πληρώθηκαν ' + money(report.aade.totals.paid) + ' € · ανεξόφλητα ' + money(report.aade.totals.unpaid) + ' €');
    return { ok: true, files: [jf, ...files], summary: report.periods.map(r => ({ period: r.period, status: r.status })) };
  },

  // ΑΑΔΕ: πραγματική κατάσταση πληρωμής κάθε ΤΟ (πληρωμές + ανεξόφλητες οφειλές). Ξεχωριστό HyperHttp/jar.
  async aadeVerify(http, lib, inp, report) {
    const PAY = require('../lib/aade-payments.js');
    const subs = report.periods.flatMap(p => (p.submissions || []).filter(s => Number(s.posoVevaiosis) > 0 && s.tautotitaOfeilis).map(s => ({ p, s })));
    if (!subs.length) return { checked: 0 };
    const ah = new lib.HyperHttp(path.join(http.dlDir, 'aade'));
    let L = await lib.aadeLogin(ah, inp);
    if (!L.ok && /OAMError/.test(L.reason)) { http.log('[aade] ' + L.reason + ' — νέα προσπάθεια σε 30″'); await new Promise(r => setTimeout(r, 30000)); L = await lib.aadeLogin(new lib.HyperHttp(path.join(http.dlDir, 'aade')), inp); }
    if (!L.ok) throw new Error('AADE login: ' + L.reason);
    const ctx = { AADE: L.AADE, getText: async (u) => (await ah.follow('GET', u)).text, postText: async (u, f) => (await ah.follow('POST', u, f)).text, dump: ah.dump.bind(ah) };
    const dmy = (iso) => { const [y, m, d] = iso.split('-'); return d + '/' + m + '/' + y; };
    const from = dmy(subs.map(x => x.s.submittedOn).filter(Boolean).sort()[0]);
    const pays = await PAY.fetchPayments(ctx, from);
    const debts = await PAY.fetchOpenDebts(ctx);
    // πρώτα όσες έχουν πληρωμή με την ΙΔΙΑ ΤΟ, μετά τα fallback κατά ποσό
    subs.sort((x, y) => (pays.has(PAY.toKey(y.s.tautotitaOfeilis)) ? 1 : 0) - (pays.has(PAY.toKey(x.s.tautotitaOfeilis)) ? 1 : 0));
    const totals = { vevaiosi: 0, paid: 0, unpaid: 0 };
    for (const { p, s } of subs) {
      const st = PAY.classify({ to: s.tautotitaOfeilis, amount: s.posoVevaiosis, issuedOn: dmy(s.submittedOn), dueDate: s.deadline ? dmy(s.deadline) : null, kindRe: /ΠΑΡΕΠΙΔΗΜ/ }, pays, debts);
      s.aade = { ...st, agreesWithDae: /^PAID/.test(st.status) === (Number(s.posoYpoloipo) <= 0.005) || st.status === 'CLOSED_UNMATCHED' };
      totals.vevaiosi += Number(s.posoVevaiosis); totals.paid += st.paid; totals.unpaid += st.unpaid || 0;
      if (p.debt) { p.debt.aadeStatus = st.status; p.debt.aadeUnpaid = st.unpaid; }
      if (/^PAID/.test(st.status) && p.status === 'SUBMITTED_DEBT') p.status = 'SUBMITTED_PAID';
      if (!s.aade.agreesWithDae) http.log('[aade] ⚠ ' + p.period + ': ΑΑΔΕ=' + st.status + ' ενώ dae=' + s.paymentStateText + ' (υπόλοιπο ' + money(s.posoYpoloipo) + ')');
    }
    for (const k of Object.keys(totals)) totals[k] = +totals[k].toFixed(2);
    return { checked: subs.length, paymentsWindow: [from, PAY.dmy(new Date())], paymentsFound: pays.size, openDebts: debts.length, totals };
  },

  async checkPeriod(http, afm, p, pending, doneIds, files) {
    const out = { period: p.perigrafi, periodId: p.id, from: (p.periodikiDilosiApo || '').slice(0, 10), to: (p.periodikiDilosiEos || '').slice(0, 10) };
    // (α) εκκρεμότητα υποβολής: Φ2 της περιόδου που δεν έχει ακόμα δήλωση στο portal
    const pend = pending.filter(x => x.periodId === p.id);
    if (pend.length) out.pendingSubmission = pend.map(x => ({
      f2SubmittedOn: (x.imerominiaYpovolis || '').slice(0, 10), f2Amount: x.poso, remainingWithoutStatement: x.posoXorisStatements,
      deadline: (x.teleutaiaImeraPrinProstimo || '').slice(0, 10), kind: x.periodikiDilosiEidos, reason: x.aitiaProvolis, smallBusiness: !!x.isSmallBusiness,
    }));
    // (β) υποβολές της περιόδου
    let subs = [];
    if (doneIds.has(p.id)) {
      const body = paged([{ columnName: 'periodId', value: String(p.id), regex: false }]);
      for (let i = 0; i < 3; i++) {             // το endpoint επιστρέφει περιστασιακά data:[] με recordsFiltered>0
        const r = await apiJson(http, 'POST', B + '/api/StatementsYpovoli/GetYpovolesPeriodou', body);
        subs = r.data || [];
        if (subs.length || !r.recordsFiltered) break;
      }
    }
    out.submissions = [];
    for (const s of subs) {
      // ΠΗΓΗ ΑΛΗΘΕΙΑΣ = η γραμμή της λίστας (GetYpovolesPeriodou): έχει τα ΕΝΗΜΕΡΩΜΕΝΑ ποσά πληρωμής/paymentState
      // (== το «Ιστορικό Υποβολών» της πύλης). Το GetYpovoliDetail επιστρέφει τα ποσά της ΣΤΙΓΜΗΣ ΥΠΟΒΟΛΗΣ (live:
      // Ιούνιος 2025 detail=Απλήρωτη 333,44 / λίστα=Πληρωμένη 333,44) -> χρησιμοποιείται μόνο για periodikiDilosi αν λείπει.
      let d = s;
      if (!d.periodikiDilosi) {
        try { const det = await apiJson(http, 'GET', B + '/api/StatementsYpovoli/GetYpovoliDetail/' + s.id); d = { ...s, periodikiDilosi: det.periodikiDilosi }; }
        catch (e) { http.log('[dae] detail ' + s.id + ': ' + e.message); }
      }
      if (!d.tautotitaOfeilis && d.shouldHaveTO) {
        try { await apiJson(http, 'GET', B + '/api/StatementsYpovoli/RefreshTautotitaOfeilisForYpovoli/' + s.id); const det = await apiJson(http, 'GET', B + '/api/StatementsYpovoli/GetYpovoliDetail/' + s.id); d = { ...d, tautotitaOfeilis: det.tautotitaOfeilis }; }
        catch (e) { http.log('[dae] refresh ΤΟ ' + s.id + ': ' + e.message); }
      }
      const paid = Number(d.posoPliromonKefalaiou || 0) + Number(d.posoPliromonProsafxisis || 0);
      const sub = {
        id: d.id, seira: d.seiraYpovolis, diorthotiki: !!d.isDiorthotiki, hasAsymfonia: !!d.hasAsymfonia,
        tautotitaOfeilis: d.tautotitaOfeilis || null, posoVevaiosis: d.posoVevaiosis, posoDiagrafwn: d.posoDiagrafwn,
        posoPliromonKefalaiou: d.posoPliromonKefalaiou, posoPliromonProsafxisis: d.posoPliromonProsafxisis, posoYpoloipo: d.posoYpoloipo,
        paymentState: d.paymentState, paymentStateText: PAYMENT_STATE[d.paymentState] || String(d.paymentState),
        submittedOn: (d.createdOn || '').slice(0, 10), deadline: (d.teleutaiaImeraPrinProstimo || '').slice(0, 10),
        lastUpdate: (d.lastModifiedOn || '').slice(0, 10), monthsLate: d.minesKathisterisis || 0, diloseisPouAfora: d.diloseisPouAfora,
        f2: d.periodikiDilosi ? { submittedOn: (d.periodikiDilosi.periodikiDilosiImerominiaYpovolis || '').slice(0, 10), akatharisto: d.periodikiDilosi.akatharistoPosoPeriodikisDilosis, diorthotiki: !!d.periodikiDilosi.diorthotiki } : null,
        paid,
      };
      // (γ) PDF μόνο όταν βεβαιώθηκε χρεωστικό ποσό
      if (Number(d.posoVevaiosis) > 0) {
        const doc = await http.getDoc(B + '/ViewReports/ReportStatementYpovoli/' + d.id + '/' + p.id);
        if (doc.buffer) {
          const f = 'PAREPID_' + afm + '_' + periodTag(p) + '_' + d.id + '_' + money(d.posoVevaiosis).replace('.', ',') + '.pdf';
          fs.writeFileSync(path.join(http.dlDir, f), doc.buffer); files.push(f); sub.pdf = f;
          http.log('[dae] ✅ ' + f + ' (' + doc.buffer.length + ' b)');
        } else http.log('[dae] PDF ' + d.id + ': ' + doc.status + ' ' + doc.ct);
      }
      out.submissions.push(sub);
    }
    // (δ) κατάσταση περιόδου (η τελευταία υποβολή = μεγαλύτερη σειρά)
    const last = out.submissions.slice().sort((a, b) => (b.seira || 0) - (a.seira || 0))[0];
    const debtSubs = out.submissions.filter(s => Number(s.posoYpoloipo) > 0);
    if (debtSubs.length) out.debt = {
      ypoloipo: debtSubs.reduce((a, s) => a + Number(s.posoYpoloipo), 0), vevaiosi: debtSubs.reduce((a, s) => a + Number(s.posoVevaiosis || 0), 0),
      tautotitaOfeilis: debtSubs.map(s => s.tautotitaOfeilis).filter(Boolean).join(', ') || null, deadline: debtSubs[0].deadline,
    };
    if (out.pendingSubmission) out.status = 'PENDING_SUBMISSION';
    else if (!last) out.status = 'NO_SUBMISSION';
    else if (out.debt) out.status = 'SUBMITTED_DEBT';
    else if (out.submissions.some(x => Number(x.posoVevaiosis) > 0)) out.status = 'SUBMITTED_PAID';   // αρχική χρεωστική + διορθωτική 0 € -> πληρωμένη
    else out.status = 'SUBMITTED_ZERO';
    http.log('[dae] ' + p.perigrafi + ': ' + out.status);
    return out;
  },
};
