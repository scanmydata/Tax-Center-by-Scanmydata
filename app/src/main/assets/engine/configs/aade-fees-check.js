/*
 * configs/aade-fees-check.js  --  Έλεγχος ΥΠΟΒΟΛΗΣ: Τέλος Πλαστικής Σακούλας + Τέλος Διαμονής
 * =============================================================================
 * Αφιερωμένη διαδικασία ελέγχου (EasyCheck-style, ΑΠΕΥΘΕΙΑΣ ΑΠΟ ΤΗΝ ΑΑΔΕ, χωρίς DB) για τις
 * δύο δηλώσεις τελών του taxisnet «other»:
 *
 *   ΠΕΡΙΒΑΛΛΟΝ = otherAppH7  «Περιβαλλοντικό Τέλος» (πλαστικής σακούλας μεταφοράς)  — ΤΡΙΜΗΝΟ
 *   ΔΙΑΜΟΝΗ    = otherAppH6  «Τέλος Ανθεκτικότητας στην κλιματική κρίση» (πρώην Φόρος Διαμονής) — ΜΗΝΑΣ
 *
 * Πηγή: Hyper.Server.Tax.dll  Easy_Aade.GetGeneralPageType1 / GetGeneralType1Year
 *   RetrievalKindPdfs.Perival_Telos  -> Filter "otherAppH7", MetaDataKey "Περ.Τέλος",  PeriodType.Quarter
 *   RetrievalKindPdfs.Foros_Diamonis -> Filter "otherAppH6", MetaDataKey "Φ.Διαμονής", PeriodType.Month
 *   και τα δύο: TaxisCategory "other", SearchForPaymentID = true, cols period=0/status=2/button=3.
 *
 * FAITHFUL ροή (ίδια με το TaxSystem):
 *   1. GET  taxisnet/other/protected/displayLiabilitiesForYear.htm?declarationType={H6|H7}&year={Y}
 *      (+ AccountantSetAsMySelf αν ο λογαριασμός είναι λογιστή)
 *      «Δεν έχετε υποχρεώσεις υποβολής…» => ΔΕΝ ΥΠΟΧΡΕΟΣ για το έτος.
 *   2. ανά γραμμή tblRow1/2: col[0]=περίοδος («Νος Μήνας» / «Νο Τρίμηνο»), col[2]=κατάσταση
 *      («Έχει/Έχουν Υποβληθεί Δήλωση(εις)» | «Υπάρχει δήλωση σε εκκρεμότητα» | «Δεν έχει υποβληθεί»),
 *      col[3]=κουμπί doDisplayDeclarationsList(7 args).
 *   3. για υποβληθείσες: GET displayDeclarationsList.htm?{7 args} -> γραμμές με col[5]=="Οριστική":
 *      col[2]=declarationDatabaseId, col[4]=σχόλιο (Αρχική/Τροποποιητική), col[7]=κουμπί «Ταυτότητα Οφειλής».
 *   4. Ταυτότητα Οφειλής: GET displayDebtCode.htm?declarationType=..&declarationDatabaseId=..&returnView=..
 *      -> td.tblFormPrompt=="Ταυτότητα Οφειλής" -> td[1].
 *   5. (προαιρετικά pdf=1) POST viewPdf.htm {declarationType, declarationDatabaseId} -> PDF της δήλωσης.
 *
 * Πάνω από τη ροή του TaxSystem προσθέτει ΠΛΕΓΜΑ ΑΝΑΜΕΝΟΜΕΝΩΝ ΠΕΡΙΟΔΩΝ (12 μήνες / 4 τρίμηνα):
 *   SUBMITTED | PENDING | NOT_SUBMITTED (+overdue) | NOT_DUE (η περίοδος δεν έληξε) | NO_ROW (καμία γραμμή ΑΑΔΕ)
 *   Η προθεσμία είναι ΕΝΔΕΙΚΤΙΚΗ (τέλος του επόμενου μήνα από τη λήξη της περιόδου)· παρατάσεις
 *   δεν λαμβάνονται υπόψη — η επίσημη ημερομηνία είναι στο OBLIGATION_DATES του TaxSystem.
 *
 * INPUTS: TAXISnet user/pass + Έτος + fees (ΠΕΡΙΒΑΛΛΟΝ,ΔΙΑΜΟΝΗ· κενό=και τα δύο) + pdf (1=λήψη PDF).
 * OUTPUT: AADE_fees_<ΑΦΜ>_<έτος>.json (+ <ΑΦΜ>_<ΤΕΛΟΣ>_<έτος>_<περίοδος>[_σχόλιο].pdf όπως το TaxSystem).
 */
'use strict';
const path = require('path');
const fs = require('fs');
const E = require('../lib/easycheck-obligations.js');
const PAY = require('../lib/aade-payments.js');

// key -> engine OBLIGATIONS key + ό,τι ορίζει το GetGeneralType1Year/GetFriendlyName για το Kind
const FEES = {
  'ΠΕΡΙΒΑΛΛΟΝ': { obl: 'ΠΕΡΙΒΑΛΛΟΝ', kind: 'Perival_Telos', friendly: 'ΠΕΡΙΒΑΛΛΟΝΤΙΚΟ ΤΕΛΟΣ', metaKey: 'Περ.Τέλος', periodType: 'Quarter',
    label: 'Περιβαλλοντικό Τέλος Πλαστικής Σακούλας (otherAppH7)' },
  'ΔΙΑΜΟΝΗ':    { obl: 'ΔΙΑΜΟΝΗ',    kind: 'Foros_Diamonis', friendly: 'ΤΕΛΟΣ ΑΝΘΕΚΤ', metaKey: 'Φ.Διαμονής', periodType: 'Month',
    label: 'Τέλος Ανθεκτικότητας / Φόρος Διαμονής (otherAppH6)' },
};
const ALIASES = { 'ΣΑΚΟΥΛΑ': 'ΠΕΡΙΒΑΛΛΟΝ', 'H7': 'ΠΕΡΙΒΑΛΛΟΝ', 'OTHERAPPH7': 'ΠΕΡΙΒΑΛΛΟΝ', 'ΑΝΘΕΚΤΙΚΟΤΗΤΑΣ': 'ΔΙΑΜΟΝΗ', 'H6': 'ΔΙΑΜΟΝΗ', 'OTHERAPPH6': 'ΔΙΑΜΟΝΗ' };

// == C# GetGeneralType1Year: col[0] «1ος Μήνας»/«12ος Μήνας»/«3ο Τρίμηνο» -> 1..12 ή 31..34
function periodKey(text) {
  const t = (text || '').trim();
  let n = parseInt(t.slice(0, 1), 10);
  if (!(n > 0)) return 0;
  if (n === 1 && /^\d$/.test(t.slice(1, 2))) n = 10 + parseInt(t.slice(1, 2), 10);
  return t.includes('Τρίμηνο') ? 30 + n : n;
}
// fallback από τα args του doDisplayDeclarationsList (oneMonth / threeMonths) — όπως το branch periodCol<0
function periodKeyFromArgs(args) {
  if (!args) return 0;
  const d = (s) => { const m = (s || '').match(/(\d{1,2})\/(\d{1,2})\/(\d{4})/); return m ? +m[2] : 0; };
  if (args.periodType === 'oneMonth') return d(args.periodStart);
  if (args.periodType === 'threeMonths') { const mo = d(args.effectivePeriodStart); return mo ? 30 + Math.ceil(mo / 3) : 0; }
  return 0;
}
function periodLabel(key) { return key > 30 ? (key - 30) + 'ο Τρίμηνο' : key + 'ος Μήνας'; }

// ενδεικτική προθεσμία: τελευταία ημέρα του μήνα που ακολουθεί τη λήξη της περιόδου
function periodBounds(year, key) {
  const lastMonth = key > 30 ? (key - 30) * 3 : key;           // 1-based
  const end = new Date(year, lastMonth, 0, 23, 59, 59);         // τελευταία ημέρα της περιόδου
  const due = new Date(year, lastMonth + 1, 0, 23, 59, 59);     // τελευταία ημέρα του επόμενου μήνα
  return { end, due };
}
const ymd = (d) => d.getFullYear() + '-' + String(d.getMonth() + 1).padStart(2, '0') + '-' + String(d.getDate()).padStart(2, '0');

// displayDeclarationsList: οριστικές δηλώσεις της περιόδου (col[5]=="Οριστική")
// Οι γραμμές έχουν ΕΜΦΩΛΕΥΜΕΝΟ πίνακα στη στήλη «Ενέργειες» -> ένα απλό <tr>…</tr> regex κόβεται στο πρώτο
// εσωτερικό </tr>. Χωρίζουμε σε τμήματα από κάθε <tr class="tblRowN"> ως το επόμενο (== HtmlAgilityPack DOM).
function declarationRowSegments(html) {
  const starts = [...html.matchAll(/<tr\b[^>]*class="tblRow[12]"[^>]*>/gi)].map(m => m.index);
  return starts.map((st, i) => html.slice(st, i + 1 < starts.length ? starts[i + 1] : html.length));
}
// displayDeclarationsList: οριστικές δηλώσεις της περιόδου (col[5]=="Οριστική")
// στήλες: 0 Πηγή · 1 Ημερολ. Περίοδος · 2 Αριθμός Καταχ. · 3 Ημ/νία Υποβολής · 4 Είδος · 5 Κατάσταση ·
//         6 Χρεωσ./Πιστωτ./Μηδεν. (Χ/Π/Μ) · 7 Ενέργειες (Προβολή / Κατάσταση / Ταυτότητα Οφειλής)
const BALANCE = { 'Χ': 'Χρεωστική', 'Π': 'Πιστωτική', 'Μ': 'Μηδενική' };
function finalDeclarations(html) {
  const out = [];
  for (const seg of declarationRowSegments(html)) {
    const c = [...seg.matchAll(/<td\b[^>]*>([\s\S]*?)<\/td>/gi)].slice(0, 7).map(m => E.stripTags(m[1]));
    if (c[5] !== 'Οριστική') continue;
    const id = c[2];
    if (!id) continue;
    // «Ταυτότητα Οφειλής»: doDisplayDebtCode(form,"declType","declId","displayDeclarationsList.htm?…")
    let debtOnclick = '';
    for (const m of seg.matchAll(/<div\b([^>]*)>([\s\S]*?)<\/div>/gi)) {
      if (E.stripTags(m[2]) !== 'Ταυτότητα Οφειλής') continue;
      const oc = m[1].match(/onclick\s*=\s*(["'])([\s\S]*?)\1/i);
      debtOnclick = oc ? E.decodeHtml(oc[2]) : '';
      break;
    }
    out.push({ declarationDatabaseId: id, comment: c[4], submittedOn: c[3], calendarPeriod: c[1], source: c[0],
      balance: BALANCE[c[6]] || c[6] || '', cells: c, debtOnclick });
  }
  return out;
}
// == C#: PaymentPrintCodes.TaxExtEverythingBetween("displayDeclarationsList.htm?", "\");")[0].Split(",")
function debtReturnView(onclick) {
  const inner = E.between(onclick || '', 'displayDeclarationsList.htm?', '");')[0];
  if (inner == null) return null;
  const parts = inner.split(',').map(x => x.replace(/"/g, '').trim());
  if (parts.length !== 1) return null;                          // PaymentID_TypeInUrl κενό για H6/H7
  return 'displayDeclarationsList.htm?' + parts[0];
}
// displayDebtCode.htm: όλα τα ζεύγη tblFormPrompt -> τιμή (Ταυτότητα Οφειλής, ποσό, κ.λπ.)
function debtInfo(html) {
  const info = {};
  for (const tr of (html || '').matchAll(/<tr\b[^>]*>([\s\S]*?)<\/tr>/gi)) {
    const tds = [...tr[1].matchAll(/<td\b([^>]*)>([\s\S]*?)<\/td>/gi)];
    if (tds.length < 2 || !/class\s*=\s*["']tblFormPrompt["']/i.test(tds[0][1])) continue;
    const k = PAY.strip(tds[0][2]); const v = PAY.strip(tds[1][2]);
    if (k) info[k] = v;
  }
  return info;
}

module.exports = {
  id: 'aade-fees-check',
  title: 'Έλεγχος υποβολής: Τέλος Πλαστικής Σακούλας (H7) + Τέλος Διαμονής/Ανθεκτικότητας (H6)',
  portal: 'AADE taxisnet/other (GSIS OAM)',
  subsystem: 'Hyper.Server',
  actions: ['check', 'retrieve'],
  inputs: [
    { key: 'user', label: 'TAXISnet username', env: 'AADE_USER' },
    { key: 'pass', label: 'TAXISnet password', env: 'AADE_PASS', hidden: true },
    { key: 'year', label: 'Έτος', env: 'AADE_YEAR' },
    { key: 'fees', label: 'Τέλη (ΠΕΡΙΒΑΛΛΟΝ,ΔΙΑΜΟΝΗ· κενό=και τα δύο)', env: 'AADE_FEES', optional: true },
    { key: 'pdf', label: 'Λήψη PDF δηλώσεων; (1=ναι, κενό=όχι)', env: 'AADE_FEES_PDF', optional: true },
    { key: 'payments', label: 'Κατάσταση πληρωμής ΤΟ από ΑΑΔΕ; (κενό/1=ναι, 0=όχι)', env: 'AADE_FEES_PAYMENTS', optional: true },
  ],
  FEES, periodKey, periodKeyFromArgs, periodBounds, finalDeclarations, debtReturnView, debtInfo,

  resolveFees(raw) {
    const toks = (raw || '').split(',').map(s => s.trim().toUpperCase()).filter(Boolean);
    if (!toks.length) return Object.keys(FEES);
    return [...new Set(toks.map(t => ALIASES[t] || t))];
  },

  async run(http, inp, lib) {
    const Y = (inp.year || '').trim();
    if (!/^\d{4}$/.test(Y)) { http.log('Χρειάζεται έγκυρο Έτος (YYYY)'); return { ok: false, reason: 'NoYear' }; }
    const wanted = this.resolveFees(inp.fees);
    const bad = wanted.filter(k => !FEES[k]);
    if (bad.length) { http.log('Άγνωστο τέλος: ' + bad.join(', ') + ' — διαθέσιμα: ' + Object.keys(FEES).join(', ')); return { ok: false, reason: 'BadFee' }; }
    const L = await lib.aadeLogin(http, inp);
    if (!L.ok) { http.log('AADE LOGIN FAILED: ' + L.reason); return { ok: false, reason: L.reason }; }
    const afm = (lib.stripTags(L.page && L.page.text || '').match(/Α\.?Φ\.?Μ\.?\s*[:\-]?\s*(\d{9})/) || [])[1] || inp.user;
    const ctx = {
      AADE: L.AADE, vat: afm, log: http.log.bind(http), dump: http.dump.bind(http),
      getText: async (url) => (await http.follow('GET', url)).text,
      postText: async (url, form) => (await http.follow('POST', url, form)).text,
    };
    const wantPdf = /^(1|y|yes|ναι|ν)$/i.test((inp.pdf || '').trim());
    const files = [];
    const report = { portal: this.portal, afm, year: Y, checkedAt: new Date().toISOString(), fees: [] };
    for (const key of wanted) {
      try { report.fees.push(await this.checkFee(http, ctx, afm, +Y, key, wantPdf, files)); }
      catch (e) { report.fees.push({ fee: key, error: String(e && e.message || e) }); http.log('[' + key + '] error ' + (e && e.message ? e.message : e)); }
    }
    if (!/^(0|όχι|no?)$/i.test((inp.payments || '').trim())) {
      try { report.payments = await this.verifyPayments(http, ctx, report); }
      catch (e) { report.payments = { error: String(e && e.message || e) }; http.log('[pay] ' + report.payments.error); }
    }
    const jf = 'AADE_fees_' + afm + '_' + Y + '.json';
    fs.writeFileSync(path.join(http.dlDir, jf), JSON.stringify(report, null, 2));
    http.log('[aade-fees-check] ✅ saved -> ' + jf + (files.length ? ' (+' + files.length + ' PDF)' : ''));
    for (const f of report.fees) {
      if (f.error) { http.log('  ' + f.fee + ': ERROR ' + f.error); continue; }
      http.log('  ' + f.fee + ': ' + f.status + (f.counts ? ' [✓' + f.counts.submitted + ' ~' + f.counts.pending + ' ✗' + f.counts.notSubmitted + (f.counts.overdue ? ' (εκπρόθεσμα ' + f.counts.overdue + ')' : '') + ' / ' + f.counts.due + ' ληγμένες]' : ''));
      for (const p of f.periods || []) for (const d of p.declarations || []) if (d.paymentId || d.balance)
        http.log('    ' + p.period + ' · υποβ. ' + (d.submittedOn || '') + ' ' + (d.comment || '') + ' ' + (d.balance || '') + (d.amount != null ? ' · ' + d.amount.toFixed(2) + ' €' : '')
          + (d.paymentId ? ' · ΤΟ ' + d.paymentId : '') + (d.dueDate ? ' · λήξη ' + d.dueDate : '') + (d.payment ? ' · ' + d.payment.status + (d.payment.matchedBy === 'amount' ? ' (κατά ποσό, άλλη ΤΟ)' : d.payment.matchedBy === 'amount+surcharge' ? ' (εκπρόθεσμα με προσαύξηση, άλλη ΤΟ)' : d.payment.status === 'CLOSED_UNMATCHED' ? ' (καμία ανοιχτή οφειλή· η πληρωμή δεν ταυτοποιήθηκε)' : '') + (d.payment.payments.length ? ' ' + d.payment.payments.map(x => x.date + ' ' + x.amount.toFixed(2) + '€').join(', ') : '') : ''));
      if (f.totals) http.log('    ΣΥΝΟΛΟ: βεβαιώθηκαν ' + f.totals.amount.toFixed(2) + ' € · πληρώθηκαν ' + f.totals.paid.toFixed(2) + ' € · ανεξόφλητα ' + f.totals.unpaid.toFixed(2) + ' €');
    }
    return { ok: true, files: [jf, ...files], summary: report.fees.map(f => ({ fee: f.fee, status: f.status || 'ERROR' })) };
  },

  // κατάσταση πληρωμής κάθε ΤΟ από «Στοιχεία Πληρωμών» + ανεξόφλητες οφειλές (ίδια συνεδρία ΑΑΔΕ)
  async verifyPayments(http, ctx, report) {
    const items = [];
    for (const f of report.fees) for (const p of f.periods || []) for (const d of p.declarations || [])
      if (d.paymentId && d.amount > 0) items.push({ f, p, d });
    if (!items.length) return { checked: 0 };
    const from = items.map(x => (x.d.submittedOn || '').split('/').reverse().join('-')).filter(Boolean).sort()[0].split('-').reverse().join('/');
    const pays = await PAY.fetchPayments(ctx, from);
    const debts = await PAY.fetchOpenDebts(ctx);
    // πρώτα όσες ταιριάζουν με την ΙΔΙΑ ΤΟ (ώστε οι πληρωμές τους να μη «δανειστούν» από το fallback κατά ποσό)
    items.sort((x, y) => (pays.has(PAY.toKey(y.d.paymentId)) ? 1 : 0) - (pays.has(PAY.toKey(x.d.paymentId)) ? 1 : 0));
    for (const { f, d } of items) {
      d.payment = PAY.classify({ to: d.paymentId, amount: d.amount, issuedOn: d.issuedOn, dueDate: d.dueDate }, pays, debts);
      f.totals = f.totals || { amount: 0, paid: 0, unpaid: 0 };
      f.totals.amount += d.amount; f.totals.paid += d.payment.paid; f.totals.unpaid += d.payment.unpaid || 0;
    }
    return { checked: items.length, from, paymentsFound: pays.size, openDebts: debts.length };
  },

  async checkFee(http, ctx, afm, Y, key, wantPdf, files) {
    const F = FEES[key];
    const o = E.OBLIGATIONS[F.obl];
    const P = (rel) => ctx.AADE + '/taxisnet/' + o.category + '/protected/' + rel;
    const entry = { fee: key, label: F.label, declarationType: o.filter, periodType: F.periodType, periods: [] };
    http.log('[' + key + '] ' + F.label + ' — έτος ' + Y);

    const { resp } = await E.getLiabilities(ctx, o, Y);
    http.dump('fees_' + key + '_' + Y + '_liab.html', resp);
    if (resp.includes('Δεν έχετε υποχρεώσεις υποβολής για το συγκεκριμένο έντυπο')) {
      entry.status = 'NOT_OBLIGATED'; http.log('[' + key + '] ΔΕΝ υπόχρεος για ' + Y); return entry;
    }

    // 1) γραμμές ΑΑΔΕ -> map periodKey -> {status, statusText, declQuery}
    const rows = new Map();
    for (const row of E.liabilityRows(resp)) {
      const { period, status, statusText } = E.interpretRow(o, row.cells);
      const dl = E.declListQuery(row.cells[o.buttonCol] || '');
      const k = periodKey(period) || periodKeyFromArgs(dl && dl.args);
      if (!k) continue;
      rows.set(k, { aadePeriod: period, status, statusText, declQuery: dl && dl.query });
    }

    // 2) πλέγμα αναμενόμενων περιόδων
    const keys = F.periodType === 'Quarter' ? [31, 32, 33, 34] : [1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12];
    const now = new Date();
    const c = { due: 0, submitted: 0, pending: 0, notSubmitted: 0, overdue: 0, noRow: 0 };
    for (const k of keys) {
      const { end, due } = periodBounds(Y, k);
      const r = rows.get(k);
      const p = { period: periodLabel(k), periodEnd: ymd(end), indicativeDeadline: ymd(due) };
      if (now <= end) { p.status = r ? r.status : 'NOT_DUE'; if (!r) { entry.periods.push(p); continue; } }
      else c.due++;
      if (!r) { p.status = 'NO_ROW'; c.noRow++; entry.periods.push(p); continue; }
      p.status = r.status; p.statusText = r.statusText;
      if (now > end) {
        if (r.status === 'SUBMITTED') c.submitted++;
        else if (r.status === 'PENDING') c.pending++;
        else { c.notSubmitted++; if (now > due) { p.overdue = true; c.overdue++; } }
      }
      // 3) drill-down για υποβληθείσες: οριστικές δηλώσεις + Ταυτότητα Οφειλής (+PDF)
      if (r.status !== 'NOT_SUBMITTED' && r.declQuery) {
        try { p.declarations = await this.drillDown(http, ctx, P, o, F, afm, Y, k, r.declQuery, wantPdf, files); }
        catch (e) { p.drillDownError = String(e && e.message || e); }
      }
      entry.periods.push(p);
    }
    entry.counts = c;
    entry.status = c.notSubmitted > 0 ? 'INCOMPLETE' : c.pending > 0 ? 'PENDING' : c.noRow > 0 ? 'PARTIAL_DATA' : 'COMPLETE';
    http.log('[' + key + '] ' + entry.status + ' (✓' + c.submitted + ' ~' + c.pending + ' ✗' + c.notSubmitted + ' / ' + c.due + ' ληγμένες περίοδοι)');
    return entry;
  },

  async drillDown(http, ctx, P, o, F, afm, Y, k, declQuery, wantPdf, files) {
    const list = await ctx.getText(P('displayDeclarationsList.htm') + declQuery);
    http.dump('fees_' + F.obl + '_' + Y + '_' + k + '_list.html', list);
    const decls = finalDeclarations(list);
    for (const d of decls) {
      d.metaData = F.metaKey + '-' + d.declarationDatabaseId;
      const rv = debtReturnView(d.debtOnclick);
      if (rv) {
        try {
          // == displayDebtCodeForm (method=get): τα 3 πεδία URL-encoded
          const html = await ctx.getText(P('displayDebtCode.htm') + '?' + new URLSearchParams({ declarationType: o.filter, declarationDatabaseId: d.declarationDatabaseId, returnView: rv }).toString());
          http.dump('fees_' + F.obl + '_' + Y + '_' + k + '_debt_' + d.declarationDatabaseId + '.html', html);
          const info = debtInfo(html);
          if (info['Ταυτότητα Οφειλής']) d.paymentId = info['Ταυτότητα Οφειλής'];
          d.debtInfo = info;
          // «Συνολικό ποσό οφειλής δήλωσης» + «Ποσό δόσης δήλωσης της dd/mm/yyyy» (= ημ/νία λήξης) + «Ημ/νία Έκδοσης»
          if (info['Συνολικό ποσό οφειλής δήλωσης']) d.amount = PAY.num(info['Συνολικό ποσό οφειλής δήλωσης']);
          const dk = Object.keys(info).find(x => /^Ποσό δόσης δήλωσης της/.test(x));
          if (dk) d.dueDate = (dk.match(/(\d\d\/\d\d\/\d{4})/) || [])[1] || null;
          if (info['Ημ/νία Έκδοσης']) d.issuedOn = info['Ημ/νία Έκδοσης'];
          if (info['Είδος Φόρου']) d.taxKind = info['Είδος Φόρου'];
        } catch (e) { d.debtError = String(e && e.message || e); }
      }
      delete d.debtOnclick;
      if (!wantPdf) continue;
      const pdf = await http.postForPdf(P('viewPdf.htm'), { declarationType: o.filter, declarationDatabaseId: d.declarationDatabaseId });
      if (!pdf) { http.log('[' + F.obl + '] declId ' + d.declarationDatabaseId + ': χωρίς PDF'); continue; }
      // == GetFriendlyNames: {Vat}_{friendly}_{RefYear}_{Νο|Νος}[_{Comments}]
      const n = k > 30 ? (k - 30) + 'ο' : k + 'ος';
      const f = (afm + '_' + F.friendly + '_' + Y + '_' + n + (d.comment ? '_' + d.comment : '') + '_' + d.declarationDatabaseId)
        .replace(/[\\/:*?"<>|]/g, '-') + '.pdf';
      fs.writeFileSync(path.join(http.dlDir, f), pdf); files.push(f); d.pdf = f;
      http.log('[' + F.obl + '] ✅ ' + f + ' (' + pdf.length + ' b)');
    }
    return decls;
  },
};
