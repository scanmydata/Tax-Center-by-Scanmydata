/*
 * lib/aade-payments.js  --  Κατάσταση πληρωμής Ταυτοτήτων Οφειλής από την ΑΑΔΕ (Προσωποποιημένη Πληροφόρηση)
 * =============================================================================
 * Κοινό για aade-fees-check (H6/H7) και dae-parepid-check (παρεπιδημούντες). Απαιτεί ήδη συνδεδεμένη συνεδρία
 * ΑΑΔΕ (lib.aadeLogin) — ctx = { AADE, getText(url), postText(url, form), dump?(name, txt) }.
 *
 *   POST taxisnet/info/protected/displayActualPaymentInfo.htm {docKind:'09123478DRSAP' (= «Όλα»), payDateFrom, payDateTo, payTo:''}
 *        (docKind 0 = μόνο Τ.Ο.· οι δηλώσεις τελών H6/H7 πληρώνονται με Τ.Ο.Τ. και χρειάζονται όλα τα είδη)
 *        «Στοιχεία Πληρωμών» (η σελίδα δείχνει εξ ορισμού ~3 μήνες· με εύρος ημερομηνιών όλο το διάστημα)
 *        γραμμές [ΔΟΥ, ημ/νία, ποσό, «Τ.Ο.», ΤΟ, …] -> Map(ΤΟ χωρίς κενά -> [{date, amount, doy}])
 *   GET  taxisnet/info/protected/displayDebtInfoAndPay.htm  -> ανεξόφλητες οφειλές
 *        [ΔΟΥ, Είδος φόρου, Πηγή, Οικ. έτος, Ημ/νία Βεβαίωσης, Αρχικό βεβαιωθέν, Μη ληξιπρ., Ληξιπρ., Προσαυξ., Σύνολο, …]
 *
 * classify(): PAID (πληρωμές ≥ ποσό) | PARTIAL | UNPAID (ανοιχτή οφειλή ίδιου ποσού/ημ. βεβαίωσης) | NOT_FOUND
 *   1ος κανόνας: πληρωμή με την ΙΔΙΑ ΤΟ (matchedBy 'to').
 *   2ος κανόνας (fallback): η οφειλή δηλώσεων (Τ.Ο.Τ.) συχνά εξοφλείται με ΑΛΛΗ ΤΟ βεβαιωμένης οφειλής (Τ.Ο., π.χ. από
 *   «Ο Λογαριασμός μου») -> πληρωμή ΙΔΙΟΥ ΠΟΣΟΥ, από την ημ/νία έκδοσης και μετά, που δεν έχει αντιστοιχιστεί αλλού
 *   (matchedBy 'amount'). Live: H6 07–09/2025 (488/488/408 €) πληρώθηκαν έτσι.
 *   3ος κανόνας: εκπρόθεσμη πληρωμή ΜΕ ΠΡΟΣΑΥΞΗΣΗ -> ποσό ∈ (ποσό, ποσό×1,12+0,05], ΜΕΤΑ τη λήξη (matchedBy
 *   'amount+surcharge', status PAID_LATE). Live: H7 Α΄2026 2,80 € (λήξη 30/04) -> 2,82 € στις 26/05/2026.
 *   Χωρίς ταυτοποίηση: αν ΔΕΝ υπάρχει ανοιχτή οφειλή -> CLOSED_UNMATCHED (δεν οφείλεται πλέον, π.χ. εξοφλήθηκε μέσα
 *   σε συνδυαστική πληρωμή/συμψηφισμό)· NOT_FOUND μένει μόνο όταν δεν έχει διαβαστεί λίστα οφειλών.
 *   Καλέστε classify με τη σειρά των περιόδων· οι πληρωμές που χρησιμοποιούνται σημαδεύονται (used).
 */
'use strict';

const strip = (s) => (s || '').replace(/<[^>]+>/g, ' ').replace(/&nbsp;/g, ' ').replace(/&euro;/g, '€').replace(/&amp;/g, '&').replace(/\s+/g, ' ').trim();
const cellsOf = (html) => [...(html || '').matchAll(/<tr\b[^>]*>([\s\S]*?)<\/tr>/gi)].map(m => [...m[1].matchAll(/<td\b[^>]*>([\s\S]*?)<\/td>/gi)].map(c => strip(c[1])));
const num = (t) => Number(String(t || '').replace(/[^\d,.-]/g, '').replace(/\./g, '').replace(',', '.')) || 0;
const toKey = (to) => String(to || '').replace(/\s/g, '');
const pad = (n) => String(n).padStart(2, '0');
const dmy = (d) => pad(d.getDate()) + '/' + pad(d.getMonth() + 1) + '/' + d.getFullYear();

async function fetchPayments(ctx, fromDmy, toDmy) {
  const U = new URL('/taxisnet/info/protected/displayActualPaymentInfo.htm', ctx.AADE).toString();
  await ctx.getText(U);
  const html = await ctx.postText(U, { docKind: '09123478DRSAP', payDateFrom: fromDmy, payDateTo: toDmy || dmy(new Date()), payTo: '' });
  if (ctx.dump) ctx.dump('aade_payments.html', html);
  if (!/name="payInfo"/.test(html)) throw new Error('Η σελίδα «Στοιχεία Πληρωμών» δεν φορτώθηκε (μη συνδεδεμένη συνεδρία ΑΑΔΕ;)');
  const pays = new Map(); const all = [];
  for (const c of cellsOf(html)) {
    if (c.length < 5 || !/^\d\d\/\d\d\/\d{4}$/.test(c[1])) continue;
    const k = toKey(c[4]); if (!k) continue;
    const e = { date: c[1], amount: num(c[2]), doy: c[0], kind: c[3], to: k, used: false };
    (pays.get(k) || pays.set(k, []).get(k)).push(e);
    all.push(e);
  }
  pays.all = all;
  return pays;
}

async function fetchOpenDebts(ctx) {
  const html = await ctx.getText(new URL('/taxisnet/info/protected/displayDebtInfoAndPay.htm', ctx.AADE).toString());
  if (ctx.dump) ctx.dump('aade_debts.html', html);
  if (/name="password"/i.test(html) || !/tblRow|Δεν υπάρχουν|οφειλ/i.test(html)) throw new Error('Η σελίδα οφειλών δεν φορτώθηκε (μη συνδεδεμένη συνεδρία ΑΑΔΕ;)');
  return cellsOf(html).filter(c => c.length >= 10 && /^\d\d\/\d\d\/\d{4}$/.test(c[4] || ''))
    .map(c => ({ kind: c[1], year: c[3], vevaiosiDate: c[4], vevaiosi: num(c[5]), unpaid: num(c[9]), used: false }));
}

const ymdOf = (d) => (d || '').split('/').reverse().join('-');
// one ΤΟ -> {status, matchedBy, payments, paid, unpaid}
function classify({ to, amount, issuedOn, dueDate, kindRe }, pays, debts) {
  let pl = (pays.get(toKey(to)) || []).filter(x => !x.used);
  let matchedBy = pl.length ? 'to' : null;
  const d = debts.find(x => !x.used && Math.abs(x.vevaiosi - Number(amount)) < 0.005 && (!issuedOn || x.vevaiosiDate === issuedOn) && (!kindRe || kindRe.test(x.kind)));
  if (d) d.used = true;
  if (!pl.length && !(d && d.unpaid > 0) && pays.all) {
    const since = ymdOf(issuedOn);
    const cand = pays.all.filter(x => !x.used && Math.abs(x.amount - Number(amount)) < 0.005 && (!since || ymdOf(x.date) >= since))
      .sort((a, b) => ymdOf(a.date).localeCompare(ymdOf(b.date)));
    if (cand.length) { pl = [cand[0]]; matchedBy = 'amount'; }
    else if (dueDate) {
      const due = ymdOf(dueDate), A = Number(amount);
      const late = pays.all.filter(x => !x.used && x.amount > A + 0.005 && x.amount <= A * 1.12 + 0.05 && ymdOf(x.date) > due)
        .sort((a, b) => ymdOf(a.date).localeCompare(ymdOf(b.date)));
      if (late.length) { pl = [late[0]]; matchedBy = 'amount+surcharge'; }
    }
  }
  pl.forEach(x => { x.used = true; });
  const paid = +pl.reduce((a, x) => a + x.amount, 0).toFixed(2);
  const status = d && d.unpaid > 0 ? (paid > 0 ? 'PARTIAL' : 'UNPAID')
    : paid >= Number(amount) - 0.005 ? (matchedBy === 'amount+surcharge' ? 'PAID_LATE' : 'PAID')
    : paid > 0 ? 'PARTIAL' : (Array.isArray(debts) ? 'CLOSED_UNMATCHED' : 'NOT_FOUND');
  return { status, matchedBy, payments: pl.map(({ used, ...x }) => x), paid, unpaid: d ? d.unpaid : (/^(PAID|PAID_LATE|CLOSED_UNMATCHED)$/.test(status) ? 0 : null) };
}

module.exports = { fetchPayments, fetchOpenDebts, classify, toKey, num, dmy, strip };
