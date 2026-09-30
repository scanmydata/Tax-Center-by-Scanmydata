/*
 * lib/aade-vat-http.js  --  ΑΑΔΕ ΦΠΑ (Φ2 περιοδική) — κατάσταση υποβολής + ΠΟΣΑ ανά περίοδο
 * =============================================================================
 * Zero-dependency (Node ≥ 18, built-in fetch) standalone module για ΕΝΣΩΜΑΤΩΣΗ ΣΕ ΑΛΛΗ
 * ΕΦΑΡΜΟΓΗ (scanmydata). Ένα αρχείο — κάνει μόνο του login GSIS OAM και τραβά, ΑΝΑ ΠΕΡΙΟΔΟ
 * (τρίμηνο Ή μήνα, ανάλογα με την κατηγορία βιβλίων του πελάτη — auto-detected):
 *   • κατάσταση υποβολής (SUBMITTED/PENDING/NOT_SUBMITTED)
 *   • ΠΟΣΑ: αποτέλεσμα (Χρεωστική/Πιστωτική/Μηδενική), συνολικό ποσό οφειλής, ποσό δόσης,
 *     εφάπαξ/1η δόση, Ταυτότητα Οφειλής (RF), ΔΟΥ, είδος φόρου, ημ/νία υποβολής/πληρωμής.
 *
 * ΠΗΓΗ (faithful reproduction — ΑΑΔΕ www1.aade.gr /taxisnet/vat/protected):
 *   1) displayLiabilitiesForYear.htm?declarationType=vatF2&year=YYYY   → περίοδοι + κατάσταση
 *      (== Hyper.Server Easy_Aade.GetGeneralType1Year· 4 στήλες, ΧΩΡΙΣ ποσά)
 *   2) doDisplayDeclarationsList(...) → displayDeclarationsList.htm?...  → δηλώσεις της περιόδου
 *      (αρ. καταχ., ημ/νία, Αρχική/Τροποποιητική, Οριστική)
 *   3) doDisplayDeclarationState(...) → displayDeclarationState.htm?declarationDatabaseId=<id>
 *      → «Εκκαθάριση Δήλωσης»: αποτέλεσμα + εφάπαξ/1η δόση
 *   4) doDisplayDebtCode(...) → displayDebtCode.htm?declarationDatabaseId=<id>
 *      → «Ταυτότητα Οφειλής»: συνολικό ποσό οφειλής, ποσό δόσης, RF, ΔΟΥ, είδος φόρου (μόνο χρεωστικές)
 *
 * ΚΩΔΙΚΟΙ: TAXISnet (username/password) — GSIS OAM (login.gsis.gr). Δεν αποθηκεύονται/λογάρονται.
 * ΛΟΓΙΣΤΗΣ ΓΙΑ ΠΕΛΑΤΗ: αν το login ανήκει σε λογιστικό γραφείο, γίνεται αυτόματα «για τον εαυτό μου»
 *   (AccountantSetAsMySelf)· για υποβολή/έλεγχο ΤΡΙΤΟΥ ΑΦΜ χρειάζεται ο πελάτης να έχει εξουσιοδοτήσει
 *   το γραφείο (η επιλογή πελάτη γίνεται από το ίδιο το TAXISnet session — εδώ ελέγχεται ο συνδεδεμένος ΑΦΜ).
 */
'use strict';

const AADE = 'https://www1.aade.gr';
const VATBASE = AADE + '/taxisnet/vat/protected/';
const UA = 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120 Safari/537.36';

// ---------------------------------------------------------------------------
// minimal cookie jar + fetch (manual redirects + JSF partial-response redirect)
// ---------------------------------------------------------------------------
class Jar {
  constructor() { this.jar = {}; }
  set(host, name, value) { (this.jar[host] = this.jar[host] || {})[name] = value; }
  _host(u) { return new URL(u).host; }
  store(url, res) {
    const h = this._host(url); this.jar[h] = this.jar[h] || {};
    for (const c of (res.headers.getSetCookie && res.headers.getSetCookie()) || []) {
      const kv = c.split(';')[0]; const i = kv.indexOf('='); if (i > 0) this.jar[h][kv.slice(0, i).trim()] = kv.slice(i + 1).trim();
    }
  }
  cookie(url) {
    const host = this._host(url); const parts = [];
    for (const h of Object.keys(this.jar))
      if (host === h || host.endsWith(h) || h.endsWith('gsis.gr'))
        for (const [k, v] of Object.entries(this.jar[h])) parts.push(k + '=' + v);
    return parts.join('; ');
  }
}

async function once(jar, method, url, form, extra) {
  const h = { 'User-Agent': UA, 'Accept': 'text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8', 'Accept-Language': 'el-GR,el;q=0.9,en;q=0.8' };
  const ck = jar.cookie(url); if (ck) h['Cookie'] = ck;
  let body; if (form) { h['Content-Type'] = 'application/x-www-form-urlencoded; charset=UTF-8'; body = new URLSearchParams(form).toString(); }
  if (extra) Object.assign(h, extra);
  const res = await fetch(url, { method, headers: h, body, redirect: 'manual' });
  jar.store(url, res);
  return res;
}
// follow HTTP 3xx AND JSF <partial-response><redirect> ; return {url,status,text}
async function follow(jar, method, url, form, extra) {
  let res = await once(jar, method, url, form, extra);
  let loc = res.headers.get('location'); let cur = url; let hops = 0;
  while (loc && res.status >= 300 && res.status < 400 && hops < 25) {
    cur = new URL(loc, cur).toString(); res = await once(jar, 'GET', cur); loc = res.headers.get('location'); hops++;
  }
  let text = await res.text(); let guard = 0;
  while (text.indexOf('<partial-response><redirect url=') !== -1 && guard < 12) {
    const m = text.match(/redirect url="([^"]*)"/); if (!m) break;
    cur = new URL(m[1].replace(/&amp;/g, '&'), cur).toString();
    res = await once(jar, 'GET', cur); loc = res.headers.get('location');
    while (loc && res.status >= 300 && res.status < 400 && hops < 25) { cur = new URL(loc, cur).toString(); res = await once(jar, 'GET', cur); loc = res.headers.get('location'); hops++; }
    text = await res.text(); guard++;
  }
  return { url: cur, status: res.status, text };
}

// ---------------------------------------------------------------------------
// html helpers
// ---------------------------------------------------------------------------
function decodeHtml(s) {
  return (s || '').replace(/&nbsp;/g, ' ').replace(/&euro;/g, '€').replace(/&#8364;/g, '€').replace(/&amp;/g, '&')
    .replace(/&quot;/g, '"').replace(/&#0*39;/g, "'").replace(/&#0*47;/g, '/').replace(/&lt;/g, '<').replace(/&gt;/g, '>');
}
function stripTags(s) { return decodeHtml((s || '').replace(/<[^>]+>/g, ' ')).replace(/\s+/g, ' ').trim(); }
function between(s, a, b) { const out = []; let i = 0; while (true) { const p = s.indexOf(a, i); if (p < 0) break; const q = s.indexOf(b, p + a.length); if (q < 0) break; out.push(s.slice(p + a.length, q)); i = q + b.length; } return out; }
function liabilityRows(html) {
  return [...html.matchAll(/<tr\b[^>]*class="tblRow[12]"[^>]*>([\s\S]*?)<\/tr>/gi)]
    .map(r => ({ html: r[1], cells: [...r[1].matchAll(/<td\b[^>]*>([\s\S]*?)<\/td>/gi)].map(c => c[1]) }));
}
// Ελληνικό ποσό «2.217,42» -> number 2217.42 (null αν λείπει)
function toNum(s) {
  const m = (s || '').match(/\d{1,3}(?:\.\d{3})*(?:,\d+)?|\d+(?:,\d+)?/);
  if (!m) return null;
  const n = parseFloat(m[0].replace(/\./g, '').replace(',', '.'));
  return Number.isFinite(n) ? n : null;
}
// Χάρτης label→value από πίνακες με 2 κελιά (label | value). Κρατά την ΠΡΩΤΗ εμφάνιση.
function labelMap(html) {
  const map = {};
  for (const tr of html.matchAll(/<tr\b[^>]*>([\s\S]*?)<\/tr>/gi)) {
    const cells = [...tr[1].matchAll(/<t[dh]\b[^>]*>([\s\S]*?)<\/t[dh]>/gi)].map(c => stripTags(c[1])).filter(c => c !== '');
    if (cells.length === 2) { const k = cells[0].replace(/[:：]\s*$/, '').trim(); if (k && !(k in map)) map[k] = cells[1]; }
  }
  return map;
}
function findVal(map, re) { for (const k of Object.keys(map)) if (re.test(k)) return map[k]; return ''; }

// doDisplayDeclarationsList(document.displayDeclarationsListForm,"declType","year","periodType","pStart","pEnd","effStart","effEnd")
function declListQuery(buttonCellHtml) {
  const onclick = decodeHtml(buttonCellHtml || '');
  const inner = between(onclick, 'doDisplayDeclarationsList(document.displayDeclarationsListForm,', ')')[0];
  if (!inner) return null;
  const a = inner.split(',').map(x => x.replace(/"/g, '').trim());
  if (a.length < 7) return null;
  const keys = ['declarationType', 'year', 'periodType', 'periodStart', 'periodEnd', 'effectivePeriodStart', 'effectivePeriodEnd'];
  const qs = new URLSearchParams(); keys.forEach((k, i) => qs.set(k, a[i]));
  return { query: '?' + qs.toString(), args: Object.fromEntries(keys.map((k, i) => [k, a[i]])) };
}
function parseDMY(s) { const m = (s || '').match(/(\d{1,2})\/(\d{1,2})\/(\d{4})/); return m ? { d: +m[1], mo: +m[2], y: +m[3] } : null; }
function periodLabel(args) {
  if (!args) return '';
  if (args.periodType === 'oneMonth') { const d = parseDMY(args.periodStart); return d ? (d.mo + 'ος Μήνας ' + d.y) : (args.periodStart || ''); }
  if (args.periodType === 'threeMonths') { const d = parseDMY(args.effectivePeriodStart || args.periodStart); if (!d) return args.periodStart || ''; const q = d.mo <= 3 ? 1 : d.mo <= 6 ? 2 : d.mo <= 9 ? 3 : 4; return q + 'ο Τρίμηνο ' + d.y; }
  return args.periodStart || '';
}
const STATUS_RE = /Έχει Υποβληθεί Δήλωση|Έχουν Υποβληθεί Δηλώσεις|Υπάρχει δήλωση σε εκκρεμότητα|σε εκκρεμότητα|Δεν έχει υποβληθεί δήλωση|Δεν έχουν υποβληθεί/;
function classify(t) {
  if (/Έχει Υποβληθεί Δήλωση|Έχουν Υποβληθεί Δηλώσεις/.test(t)) return 'SUBMITTED';
  if (/Υπάρχει δήλωση σε εκκρεμότητα|σε εκκρεμότητα/.test(t)) return 'PENDING';
  return 'NOT_SUBMITTED';
}

// ---------------------------------------------------------------------------
// login (GSIS OAM) — αναπαραγωγή aadeLogin· επιστρέφει {ok, afm, taxpayer} ή {ok:false,reason}
// ---------------------------------------------------------------------------
async function aadeLogin(jar, user, pass, log) {
  log('[aade-vat] login GSIS OAM');
  const home = await follow(jar, 'GET', AADE + '/taxisnet/info/protected/home.htm');
  const reqId = (home.text.match(/name="request_id"[^>]*value="([^"]*)"/i) || home.text.match(/value="([^"]*)"[^>]*name="request_id"/i) || [])[1];
  if (!reqId) return { ok: false, reason: 'NoRequestId' };
  const auth = await follow(jar, 'POST', 'https://login.gsis.gr/oam/server/auth_cred_submit',
    { username: user, password: pass, request_id: reqId, btn_login: '' });
  if (/An incorrect Username or Password|Καθορίστηκε λανθασμένο όνομα χρήστη ή κωδικός|κλειδωμένος ή απενεργοποιημένος|auth_fail_exception/i.test(auth.text)
      || (/name="username"/i.test(auth.text) && /name="password"/i.test(auth.text)))
    return { ok: false, reason: 'InvalidCredentials' };
  jar.set('www1.aade.gr', 'gr.taxisnet.infrastructure.common.web.ActorRoleCookieResolver.ACTOR_ROLE', 'SELF_SERVICE');
  jar.set('www1.aade.gr', 'OAMAuthnHintCookie', '1');
  await follow(jar, 'GET', AADE + '/webtax/incomefp/');
  await follow(jar, 'GET', AADE + '/webtax/incomefp/login.done');
  const check = await follow(jar, 'GET', AADE + '/taxisnet/info/protected/home.htm');
  if (/name="request_id"/i.test(check.text) && /name="password"/i.test(check.text)) return { ok: false, reason: 'NotLoggedIn' };
  const afm = (stripTags(check.text).match(/Α\.?Φ\.?Μ\.?\s*[:\-]?\s*(\d{9})/) || [])[1] || user;
  log('[aade-vat] login OK (ΑΦΜ ' + afm + ')');
  return { ok: true, afm };
}
// Λογιστικό γραφείο -> «για τον εαυτό μου» (== AccountantSetAsMySelf)
async function accountantSelf(jar, resp) {
  if (!/searchTaxpayer|Επιλογή Λογιστικού Γραφείου/.test(resp)) return false;
  const t = await follow(jar, 'POST', AADE + '/taxisnet/vat/protected/displayDeclarationTypes.htm',
    { actorRole: 'SELF_SERVICE', _eventId_chooseIndividual: 'για τον εαυτό μου' });
  return /Καλωσήρθατε στην Υπηρεσία ηλεκτρονικής υποβολής δηλώσεων/.test(t.text);
}

// ---------------------------------------------------------------------------
// ποσά μιας δήλωσης: declarationState + debtCode
// ---------------------------------------------------------------------------
async function declarationAmounts(jar, dbId, retView, log) {
  const out = { registrationNo: dbId, result: null, amounts: {}, debtId: null, doy: null, taxKind: null, payBy: null };
  const rv = encodeURIComponent(retView);
  // -- Κατάσταση Δήλωσης (εκκαθάριση) --
  try {
    const rs = await follow(jar, 'GET', VATBASE + 'displayDeclarationState.htm?declarationType=vatF2&declarationDatabaseId=' + dbId + '&returnView=' + rv);
    if (rs.status === 200) {
      const m = labelMap(rs.text);
      out.registrationNo = findVal(m, /Αριθμ\.?\s*Καταχ/) || dbId;
      out.submissionType = findVal(m, /^Τύπος$/) || findVal(m, /Τύπος/) || null;
      out.currentState = findVal(m, /Τρέχουσα Κατάσταση/) || null;
      out.submissionMethod = findVal(m, /Τρόπος\s*Y?Υ?ποβολής/) || null;
      const res = findVal(m, /Αποτέλεσμα/);
      out.result = /Χρεωστικ/.test(res) ? 'Χρεωστική' : /Πιστωτικ/.test(res) ? 'Πιστωτική' : /Μηδενικ/.test(res) ? 'Μηδενική' : (res || null);
      // Χρεωστική: «Δυνατότητες Πληρωμής» -> Εφάπαξ ποσό + Ποσό 1ης Δόσης
      const pay = findVal(m, /Δυνατότητες Πληρωμής/);
      const lump = (pay.match(/Εφάπαξ ποσό:\s*([\d.,]+)/) || [])[1];
      const first = (pay.match(/Ποσό 1ης Δόσης:\s*([\d.,]+)/) || [])[1];
      if (lump) { out.amounts.lumpSum = lump; out.amounts.lumpSumNum = toNum(lump); }
      if (first) { out.amounts.firstInstallment = first; out.amounts.firstInstallmentNum = toNum(first); }
      // Πιστωτική: «Ποσό για Έκπτωση» (μεταφορά/συμψηφισμός) + «Ποσό για Επιστροφή»
      const ded = findVal(m, /Ποσό για Έκπτωση/);
      const ref = findVal(m, /Ποσό για Επιστροφή/);
      if (ded && toNum(ded) != null) { out.amounts.amountForDeduction = (ded.match(/[\d.,]+/) || [])[0]; out.amounts.amountForDeductionNum = toNum(ded); }
      if (ref && toNum(ref) != null) { out.amounts.amountForRefund = (ref.match(/[\d.,]+/) || [])[0]; out.amounts.amountForRefundNum = toNum(ref); }
    }
  } catch (e) { log('[aade-vat] declState error id=' + dbId + ': ' + e.message); }
  // -- Ταυτότητα Οφειλής (μόνο χρεωστικές) --
  try {
    const rd = await follow(jar, 'GET', VATBASE + 'displayDebtCode.htm?declarationType=vatF2&declarationDatabaseId=' + dbId + '&returnView=' + rv);
    if (rd.status === 200 && /Ταυτότητα Οφειλής|Συνολικό ποσό οφειλής/.test(rd.text)) {
      const m = labelMap(rd.text);
      const total = findVal(m, /Συνολικό ποσό οφειλής/);
      if (total) { out.amounts.totalDue = (total.match(/[\d.,]+/) || [])[0]; out.amounts.totalDueNum = toNum(total); }
      const dose = Object.keys(m).find(k => /Ποσό δόσης δήλωσης/.test(k));
      if (dose) { out.amounts.installment = (m[dose].match(/[\d.,]+/) || [])[0]; out.amounts.installmentNum = toNum(m[dose]); out.payBy = (dose.match(/(\d{2}\/\d{2}\/\d{4})/) || [])[1] || out.payBy; }
      out.debtId = (findVal(m, /Ταυτότητα Οφειλής/).match(/[\d ]{10,}/) || [])[0]?.trim() || null;
      out.doy = findVal(m, /^ΔΟΥ$|Δ\.?Ο\.?Υ/) || null;
      out.taxKind = findVal(m, /Είδος Φόρου/) || null;
      out.issuedAt = findVal(m, /Ημ\/νία Έκδοσης/) || null;
      out.payerName = findVal(m, /Ονοματεπώνυμο|Επωνυμία/) || null;
    }
  } catch (e) { log('[aade-vat] debtCode error id=' + dbId + ': ' + e.message); }
  if (!out.amounts.currency && (out.amounts.totalDue || out.amounts.firstInstallment || out.amounts.amountForDeduction || out.amounts.amountForRefund)) out.amounts.currency = 'EUR';
  return out;
}

// ---------------------------------------------------------------------------
// MAIN — fetchVatDeclarations({user,pass,year, includeAmounts?, log?})
// ---------------------------------------------------------------------------
async function fetchVatDeclarations({ user, pass, year, includeAmounts = true, log } = {}) {
  log = log || (() => {});
  if (typeof fetch !== 'function') return { ok: false, reason: 'NodeFetchMissing (Node ≥ 18)' };
  const Y = String(year || '').trim();
  if (!/^\d{4}$/.test(Y)) return { ok: false, reason: 'BadYear' };
  const jar = new Jar();
  const L = await aadeLogin(jar, user, pass, log);
  if (!L.ok) return { ok: false, reason: L.reason };

  const report = {
    ok: true, portal: 'AADE taxisnet (GSIS OAM)', afm: L.afm, year: Y, declarationType: 'Φ2',
    checkedAt: new Date().toISOString(), periodType: null, status: null, counts: null, periods: [],
  };

  // 1) περίοδοι + κατάσταση
  let liab = await follow(jar, 'GET', VATBASE + 'displayLiabilitiesForYear.htm?declarationType=vatF2&year=' + Y);
  if (await accountantSelf(jar, liab.text)) liab = await follow(jar, 'GET', VATBASE + 'displayLiabilitiesForYear.htm?declarationType=vatF2&year=' + Y);
  if (/Δεν έχετε υποχρεώσεις υποβολής για το συγκεκριμένο έντυπο/.test(liab.text)) { report.status = 'NOT_OBLIGATED'; report.counts = { periods: 0, submitted: 0, pending: 0, notSubmitted: 0 }; return report; }
  const rows = liabilityRows(liab.text);
  if (!rows.length) { report.status = 'NO_DATA'; report.counts = { periods: 0, submitted: 0, pending: 0, notSubmitted: 0 }; return report; }

  let submitted = 0, pending = 0, missing = 0; const ptypes = new Set();
  for (const row of rows) {
    const texts = row.cells.map(c => stripTags(c));
    const statusText = texts.find(t => STATUS_RE.test(t)) || texts[2] || '';
    const status = classify(statusText);
    const dl = declListQuery(row.cells[3] || '');
    if (dl) ptypes.add(dl.args.periodType);
    const per = {
      period: (dl && periodLabel(dl.args)) || texts[0] || '',
      periodRange: texts[1] || (dl ? (dl.args.periodStart + ' - ' + dl.args.periodEnd) : ''),
      periodType: dl ? dl.args.periodType : '',
      status, statusText, declarations: [],
    };
    if (status === 'SUBMITTED') submitted++; else if (status === 'PENDING') pending++; else missing++;

    // 2) δηλώσεις της περιόδου (μόνο αν υποβληθείσα)
    if (status !== 'NOT_SUBMITTED' && dl) {
      const list = await follow(jar, 'GET', VATBASE + 'displayDeclarationsList.htm' + dl.query);
      const retView = 'displayDeclarationsList.htm' + dl.query;
      const drows = liabilityRows(list.text);
      for (let i = 0; i < drows.length; i++) {
        const c = drows[i].cells.map(x => stripTags(x));
        // databaseId: από το onclick doDisplayDeclarationState(...,"vatF2","<id>",...) ή το κελί 2
        const dbId = (between(decodeHtml(drows[i].html), 'doDisplayDeclarationState(document.displayDeclarationStateForm,"vatF2","', '"')[0] || c[2] || '').trim();
        const decl = {
          channel: c[0] || null, periodRange: c[1] || null, registrationNo: c[2] || dbId,
          submittedAt: c[3] || null, type: c[4] || null, finalized: c[5] || null,
        };
        if (includeAmounts && dbId) Object.assign(decl, await declarationAmounts(jar, dbId, retView, log));
        per.declarations.push(decl);
      }
      // effective = τελευταία Οριστική (τροποποιητική υπερισχύει της αρχικής)
      const finals = per.declarations.filter(d => /Οριστικ/.test(d.finalized || ''));
      if (finals.length) finals[finals.length - 1].effective = true;
    }
    report.periods.push(per);
  }

  report.periodType = ptypes.size === 1 ? [...ptypes][0] : (ptypes.size > 1 ? 'mixed' : null);
  report.status = missing > 0 ? 'INCOMPLETE' : (pending > 0 ? 'PENDING' : 'COMPLETE');
  report.counts = { periods: rows.length, submitted, pending, notSubmitted: missing };
  log('[aade-vat] ' + report.status + ' — περίοδοι=' + rows.length + ' (✓' + submitted + ' ~' + pending + ' ✗' + missing + '), periodType=' + report.periodType);
  return report;
}

module.exports = { fetchVatDeclarations };

// ---------------------------------------------------------------------------
// CLI:  node lib/aade-vat-http.js --user U --pass P --year 2024 [--out file.json] [--no-amounts]
//       ή env AADE_USER / AADE_PASS / AADE_YEAR
// ---------------------------------------------------------------------------
if (require.main === module) {
  (async () => {
    const a = process.argv.slice(2); const opt = {};
    for (let i = 0; i < a.length; i++) { const m = a[i].match(/^--([^=]+)(?:=(.*))?$/); if (m) opt[m[1]] = m[2] !== undefined ? m[2] : (a[i + 1] && !a[i + 1].startsWith('--') ? a[++i] : true); }
    const user = opt.user || process.env.AADE_USER;
    const pass = opt.pass || process.env.AADE_PASS;
    const year = opt.year || process.env.AADE_YEAR;
    if (!user || !pass || !year) { console.error('Usage: node aade-vat-http.js --user U --pass P --year YYYY [--out f.json] [--no-amounts]'); process.exit(1); }
    const r = await fetchVatDeclarations({ user, pass, year, includeAmounts: !opt['no-amounts'], log: (...x) => console.error(...x) });
    const json = JSON.stringify(r, null, 2);
    if (opt.out && typeof opt.out === 'string') { require('fs').writeFileSync(opt.out, json); console.error('saved -> ' + opt.out); }
    else console.log(json);
    process.exit(r.ok ? 0 : 2);
  })();
}
