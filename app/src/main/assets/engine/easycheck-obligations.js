/*
 * lib/easycheck-obligations.js  --  ΚΟΙΝΗ ΜΗΧΑΝΗ EasyCheck (ΑΑΔΕ online completion)
 * =============================================================================
 * Μία πηγή αλήθειας για τον έλεγχο ΟΛΟΚΛΗΡΩΣΗΣ υποχρεώσεων ΑΠΕΥΘΕΙΑΣ ΑΠΟ ΤΗΝ ΑΑΔΕ
 * (ΧΩΡΙΣ βάση/DB). Καθαρές συναρτήσεις + core μηχανή παραμετρική με `ctx` ώστε να την
 * χρησιμοποιούν ΚΑΙ το standalone `easycheck-aade-http.js` ΚΑΙ το runner-config
 * `configs/aade-obligations-check.js`.
 *
 * Πηγή: Hyper.Server.Tax.dll Easy_Aade.GetGeneralType1Year (+ οι declarationType filters
 * που εμφανίζονται σε Common/EpsilonSubmit για το ίδιο displayLiabilitiesForYear.htm).
 *
 * ctx = {
 *   AADE,                       // https://www1.aade.gr
 *   getText(url) -> string,     // follow GET (cookies, redirects)
 *   postText(url, form) -> str, // follow POST (για AccountantSetAsMySelf)
 *   log(...), dump(name,txt),   // προαιρετικά
 *   vat                         // ΑΦΜ (για AccountantSetAsMySelf)
 * }
 */
'use strict';

// ---------------------------------------------------------------------------
// Υποχρεώσεις που ελέγχονται μέσω displayLiabilitiesForYear.
// Οι 12 του switch (Easy_Aade.GetGeneralType1Year) + επιπλέον filters ίδιου μηχανισμού
// (income/kvs/vatF1/otherAppH2B) που βρέθηκαν σε Common/EpsilonSubmit για την ίδια σελίδα.
// periodCol -1 = χωρίς στήλη περιόδου (η περίοδος βγαίνει από το onclick doDisplayDeclarationsList).
// searchType != '' = υποκατηγορία που επιβεβαιώνεται με drill-down displayDeclarationsList.
// ---------------------------------------------------------------------------
const OBLIGATIONS = {
  // --- ΦΠΑ ---
  'Φ2':          { label: 'ΦΠΑ Φ2 (περιοδική)',            category: 'vat',       filter: 'vatF2',              periodCol: 0,  statusCol: 2, buttonCol: 3, extra: '',                                     searchType: '' },
  'Φ4':          { label: 'ΦΠΑ Φ4',                        category: 'vat',       filter: 'vatF4',              periodCol: 0,  statusCol: 2, buttonCol: 3, extra: '',                                     searchType: '' },
  'Φ5':          { label: 'ΦΠΑ Φ5',                        category: 'vat',       filter: 'vatF5',              periodCol: 0,  statusCol: 2, buttonCol: 3, extra: '',                                     searchType: '' },
  'Φ1':          { label: 'ΦΠΑ Φ1 (εκκαθαριστική, παλαιά)', category: 'vat',      filter: 'vatF1',              periodCol: 0,  statusCol: 2, buttonCol: 3, extra: '',                                     searchType: '' },
  // --- Εισόδημα Ν.Π. (FENP, μέσω liabilities) / ΚΒΣ ---
  'ΕΙΣΟΔΗΜΑ_ΝΠ': { label: 'Δήλωση Φορ. Εισοδήματος Ν.Π. (FENP)', category: 'income', filter: 'incomeN',        periodCol: 0,  statusCol: 2, buttonCol: 3, extra: '',                                     searchType: '' },
  'ΜΥΦ':         { label: 'Συγκεντρωτικές ΚΒΣ/ΜΥΦ',         category: 'kvs',       filter: 'kvsD13',             periodCol: 0,  statusCol: 2, buttonCol: 3, extra: '',                                     searchType: '' },
  // --- Εισόδημα Φυσικών (E1/E3) — ΔΙΑΦΟΡΕΤΙΚΟΣ μηχανισμός: webtax/incomefp menu (kind:'income') ---
  // Υποβλήθηκε ⇔ ο πίνακας του menu έχει κωδικό «E1-<id> / <ημερομηνία>» (== SearchE1E2E3ID/CheckEisodhmaFysiko).
  'E1':          { label: 'Δήλωση Εισοδήματος Ε1 (φυσικά)', kind: 'income', codePrefix: 'E1-', requiresDate: true },
  'E3':          { label: 'Ε3 (Επιχ. Δραστηριότητα)',      kind: 'income', codePrefix: 'E3-', requiresDate: false },
  // --- «Other app» τέλη ---
  'ΠΕΡΙΒΑΛΛΟΝ':  { label: 'Περιβαλλοντικό Τέλος',           category: 'other',     filter: 'otherAppH7',         periodCol: 0,  statusCol: 2, buttonCol: 3, extra: '',                                     searchType: '' },
  'ΔΙΑΜΟΝΗ':     { label: 'Φόρος/Τέλος Διαμονής',           category: 'other',     filter: 'otherAppH6',         periodCol: 0,  statusCol: 2, buttonCol: 3, extra: '',                                     searchType: '' },
  'ΤΕΛΟΣ_H2B':   { label: 'Τέλος (otherAppH2B)',            category: 'other',     filter: 'otherAppH2B',        periodCol: 0,  statusCol: 2, buttonCol: 3, extra: '',                                     searchType: '' },
  // --- Παρακρατούμενοι (deduction) ---
  'ΣΥΜΦΩΝΗΤΙΚΑ':  { label: 'Κατάσταση Συμφωνητικών',         category: 'deduction', filter: 'deductAgreement',    periodCol: -1, statusCol: 1, buttonCol: 2, extra: '',                                     searchType: '' },
  'ΕΡΓΟΛΑΒΩΝ':   { label: 'Φόρος Εργολάβων',                category: 'deduction', filter: 'deductContractor',   periodCol: -1, statusCol: 1, buttonCol: 2, extra: '',                                     searchType: '' },
  'ΦΜΥ':         { label: 'Παρακρ. ΦΜΥ (μισθωτών)',         category: 'deduction', filter: 'deductFMYTemporary', periodCol: -1, statusCol: 1, buttonCol: 2, extra: '&periodType=oneMonth&typeBtn=Μήνας', searchType: '( ΦΜΥ )' },
  'ΦΜΥ_ΕΠΙΧ':    { label: 'ΦΜΥ αμοιβών Επιχ. Δραστ.',        category: 'deduction', filter: 'deductFMYTemporary', periodCol: -1, statusCol: 1, buttonCol: 2, extra: '&periodType=oneMonth&typeBtn=Μήνας', searchType: 'σε αμοιβές από Επιχειρηματική Δραστηριότητα )' },
  'ΦΜΥ_ΔΙΚΑΙΩΜΑΤΑ': { label: 'Παρακρ. Δικαιωμάτων',          category: 'deduction', filter: 'deductFMYTemporary', periodCol: -1, statusCol: 1, buttonCol: 2, extra: '&periodType=oneMonth&typeBtn=Μήνας', searchType: '( Δικαιώματα )' },
  'ΦΜΥ_ΜΕΡΙΣΜΑΤΑ':  { label: 'Παρακρ. Μερισμάτων',           category: 'deduction', filter: 'deductFMYTemporary', periodCol: -1, statusCol: 1, buttonCol: 2, extra: '&periodType=oneMonth&typeBtn=Μήνας', searchType: '( Μερίσματα )' },
  'ΦΜΥ_ΤΟΚΟΙ':   { label: 'Παρακρ. Τόκων',                  category: 'deduction', filter: 'deductFMYTemporary', periodCol: -1, statusCol: 1, buttonCol: 2, extra: '&periodType=oneMonth&typeBtn=Μήνας', searchType: '( Τόκοι )' },
};

const GROUPS = {
  ALL:    Object.keys(OBLIGATIONS),
  VAT:    ['Φ2', 'Φ4', 'Φ5', 'Φ1'],
  FMY:    ['ΦΜΥ', 'ΦΜΥ_ΕΠΙΧ', 'ΦΜΥ_ΔΙΚΑΙΩΜΑΤΑ', 'ΦΜΥ_ΜΕΡΙΣΜΑΤΑ', 'ΦΜΥ_ΤΟΚΟΙ'],
  DEDUCT: ['ΣΥΜΦΩΝΗΤΙΚΑ', 'ΕΡΓΟΛΑΒΩΝ', 'ΦΜΥ', 'ΦΜΥ_ΕΠΙΧ', 'ΦΜΥ_ΔΙΚΑΙΩΜΑΤΑ', 'ΦΜΥ_ΜΕΡΙΣΜΑΤΑ', 'ΦΜΥ_ΤΟΚΟΙ'],
  OTHER:  ['ΠΕΡΙΒΑΛΛΟΝ', 'ΔΙΑΜΟΝΗ', 'ΤΕΛΟΣ_H2B'],
  INCOME: ['E1', 'E3', 'ΕΙΣΟΔΗΜΑ_ΝΠ', 'ΜΥΦ'],
  FYSIKA: ['E1', 'E3'],
};

// ---------------------------------------------------------------------------
// pure HTML helpers
// ---------------------------------------------------------------------------
function decodeHtml(s) {
  return (s || '').replace(/&nbsp;/g, ' ').replace(/&amp;/g, '&').replace(/&quot;/g, '"')
    .replace(/&#0*39;/g, "'").replace(/&#0*47;/g, '/').replace(/&lt;/g, '<').replace(/&gt;/g, '>');
}
function stripTags(s) { return decodeHtml((s || '').replace(/<[^>]+>/g, ' ')).replace(/\s+/g, ' ').trim(); }
function between(s, a, b) { const out = []; let i = 0; while (true) { const p = s.indexOf(a, i); if (p < 0) break; const q = s.indexOf(b, p + a.length); if (q < 0) break; out.push(s.slice(p + a.length, q)); i = q + b.length; } return out; }
function liabilityRows(html) {
  return [...html.matchAll(/<tr\b[^>]*class="tblRow[12]"[^>]*>([\s\S]*?)<\/tr>/gi)]
    .map(r => ({ html: r[1], cells: [...r[1].matchAll(/<td\b[^>]*>([\s\S]*?)<\/td>/gi)].map(c => c[1]) }));
}
// == hyperserver status text -> class
const STATUS_RE = /Έχει Υποβληθεί Δήλωση|Έχουν Υποβληθεί Δηλώσεις|Υπάρχει δήλωση σε εκκρεμότητα|σε εκκρεμότητα|Δεν έχει υποβληθεί δήλωση|Δεν έχουν υποβληθεί/;
function classify(statusText) {
  if (/Έχει Υποβληθεί Δήλωση|Έχουν Υποβληθεί Δηλώσεις/.test(statusText)) return 'SUBMITTED';
  if (/Υπάρχει δήλωση σε εκκρεμότητα|σε εκκρεμότητα/.test(statusText)) return 'PENDING';
  return 'NOT_SUBMITTED';
}
function parseDMY(s) { const m = (s || '').match(/(\d{1,2})\/(\d{1,2})\/(\d{4})/); return m ? { d: +m[1], mo: +m[2], y: +m[3] } : null; }
// doDisplayDeclarationsList(document.displayDeclarationsListForm,"declType","year","periodType","pStart","pEnd","effStart","effEnd")
function declListQuery(buttonCellHtml) {
  const onclick = decodeHtml(buttonCellHtml || '');
  const inner = between(onclick, 'doDisplayDeclarationsList(document.displayDeclarationsListForm,', ');')[0];
  if (!inner) return null;
  const a = inner.split(',').map(x => x.replace(/"/g, '').trim());
  if (a.length < 7) return null;
  const keys = ['declarationType', 'year', 'periodType', 'periodStart', 'periodEnd', 'effectivePeriodStart', 'effectivePeriodEnd'];
  const qs = new URLSearchParams();
  keys.forEach((k, i) => qs.set(k, a[i]));
  return { query: '?' + qs.toString(), args: Object.fromEntries(keys.map((k, i) => [k, a[i]])) };
}
function periodFromArgs(args) {
  if (!args) return '';
  if (args.periodType === 'oneMonth') { const d = parseDMY(args.periodStart); return d ? d.mo + 'ος Μήνας' : (args.periodStart || ''); }
  if (args.periodType === 'threeMonths') { const d = parseDMY(args.effectivePeriodStart); if (!d) return args.effectivePeriodStart || ''; const q = d.mo <= 3 ? 1 : d.mo <= 6 ? 2 : d.mo <= 9 ? 3 : 4; return q + 'ο Τρίμηνο'; }
  return args.periodStart || '';
}
const PERIOD_RE = /(Δεκαπενθήμερο|Μήνας|Τρίμηνο|Εξάμηνο|Έτος|Ετήσια|\b\d{1,2}ο[ςυ]?\b|\b\d{4}\b)/;
// ΑΝΘΕΚΤΙΚΟ interpret: πρώτα configured columns, μετά fallback σάρωση κελιών (για νέα filters).
function interpretRow(o, cells) {
  const texts = cells.map(c => stripTags(c || ''));
  // status
  let statusText = '';
  if (o.statusCol != null && texts[o.statusCol] && STATUS_RE.test(texts[o.statusCol])) statusText = texts[o.statusCol];
  if (!statusText) statusText = texts.find(t => STATUS_RE.test(t)) || (texts[o.statusCol] || '');
  const status = classify(statusText);
  // period
  let period = '';
  if (o.periodCol >= 0 && texts[o.periodCol]) period = texts[o.periodCol];
  if (!period) {
    if (o.periodCol < 0 || !period) { const dl = declListQuery(cells[o.buttonCol] || ''); period = periodFromArgs(dl && dl.args); }
  }
  if (!period) period = texts.find(t => PERIOD_RE.test(t) && !STATUS_RE.test(t)) || '';
  return { period, status, statusText };
}
function resolveWanted(raw) {
  const out = [];
  for (const tok of (raw || 'Φ2').split(',').map(s => s.trim().toUpperCase()).filter(Boolean)) {
    if (GROUPS[tok]) out.push(...GROUPS[tok]); else out.push(tok);
  }
  return [...new Set(out)];
}

// ---------------------------------------------------------------------------
// core engine (ctx-driven — παρέχεται getText/postText/log/dump από τον caller)
// ---------------------------------------------------------------------------
async function accountantSetAsMySelf(ctx, resp, category) {
  try {
    if (!ctx.postText) return false;
    if (!/searchTaxpayer|Επιλογή Λογιστικού Γραφείου/.test(resp)) return false;
    const t = await ctx.postText(`${ctx.AADE}/taxisnet/${category}/protected/displayDeclarationTypes.htm`,
      { actorRole: 'SELF_SERVICE', _eventId_chooseIndividual: 'για τον εαυτό μου' });
    return t.includes('Καλωσήρθατε στην Υπηρεσία ηλεκτρονικής υποβολής δηλώσεων');
  } catch (e) { return false; }
}
async function getLiabilities(ctx, o, year, cache) {
  const key = o.filter + '|' + o.extra + '|' + o.category;
  if (cache && cache.has(key)) return cache.get(key);
  const url = ctx.AADE + '/taxisnet/' + o.category + '/protected/displayLiabilitiesForYear.htm?declarationType=' + o.filter + '&year=' + year + o.extra;
  let resp = await ctx.getText(url);
  if (await accountantSetAsMySelf(ctx, resp, o.category)) resp = await ctx.getText(url);
  const val = { resp, url };
  if (cache) cache.set(key, val);
  return val;
}
async function subtypeSubmitted(ctx, o, declQuery) {
  try {
    const html = await ctx.getText(ctx.AADE + '/taxisnet/' + o.category + '/protected/displayDeclarationsList.htm' + declQuery);
    for (const row of liabilityRows(html)) {
      const t4 = stripTags(row.cells[4] || ''); const t5 = stripTags(row.cells[5] || '');
      if ((!o.searchType || t4.includes(o.searchType)) && t5 === 'Οριστική') return true;
    }
    return false;
  } catch (e) { return null; }
}
// -- income (E1/E3 φυσικών) completion: webtax/incomefp menu.
// Υποβλήθηκε ⇔ ο πίνακας δηλώσεων έχει κωδικό «E1-<id> / <ημ/νία>» (== Easy_Aade.SearchE1E2E3ID +
// CheckEisodhmaFysiko: κωδικός «En-<ψηφία>» ακολουθούμενος από «/ <date>» = οριστικά υποβληθείσα δήλωση).
// Επιστρέφει {submitted, submittedDate|null}.
function incomeSubmission(menuText, o, rawHtml) {
  const p = o.codePrefix.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
  const DATE = '(\\d{2}/\\d{2}/\\d{4}(?:\\s+\\d{2}:\\d{2})?)';
  if (o.requiresDate) {
    // E1 (νέα layout): κωδικός ακολουθούμενος από «/ <ημ/νία>» == CheckEisodhmaFysiko (contains '/')
    const m = menuText.match(new RegExp(p + '\\d+\\s*/\\s*' + DATE));
    if (m) return { submitted: true, submittedDate: m[1] };
  } else {
    // E3 (νέα layout): bare «E3-<ψηφία>» visible στον πίνακα δηλώσεων == CheckEisodhmaFysiko E3- branch
    const m = menuText.match(new RegExp(p + '\\d+'));
    if (m) { const d = menuText.slice(m.index, m.index + 80).match(new RegExp(DATE)); return { submitted: true, submittedDate: d ? d[1] : null }; }
  }
  // fallback παλαιάς layout (<2019, GetsCodeFromPageFysiko): JS `var sCode = 'E1-123'; ... var sDate = '...'`
  if (rawHtml) {
    const c = rawHtml.match(new RegExp("sCode\\s*=\\s*['\"]" + p + "\\d+['\"]"));
    if (c) { const d = rawHtml.slice(c.index, c.index + 140).match(new RegExp("sDate\\s*=\\s*['\"]" + DATE)); return { submitted: true, submittedDate: d ? d[1] : null }; }
  }
  return { submitted: false, submittedDate: null };
}
async function checkIncome(ctx, key, year) {
  const o = OBLIGATIONS[key];
  const entry = { obligation: key, label: o.label, kind: 'income', periods: [] };
  if (ctx.log) ctx.log('[check] ' + key + ' (' + o.label + ') έτος ' + year);
  let menu;
  try { menu = await ctx.getText(ctx.AADE + '/webtax/incomefp/year' + year + '-income-menu.do'); }
  catch (e) { entry.status = 'ERROR'; entry.error = e.message; return entry; }
  if (ctx.dump) ctx.dump('check_' + key + '_' + year + '.html', menu);
  if (!/income-menu|PRINT_CODE|PB[A-Z0-9_]*_PRINT|income-menuMod/i.test(menu)) { entry.status = 'NO_DATA'; return entry; }
  const text = stripTags(menu);
  const { submitted, submittedDate } = incomeSubmission(text, o, menu);
  const amend = /τροποποιητικ/i.test(text);
  entry.periods.push({ period: String(year), status: submitted ? 'SUBMITTED' : 'NOT_SUBMITTED',
    statusText: submitted ? ('Υποβλήθηκε' + (submittedDate ? ' ' + submittedDate : '') + (amend ? ' (τροποποιητική)' : '')) : 'Δεν έχει υποβληθεί δήλωση' });
  entry.status = submitted ? 'COMPLETE' : 'INCOMPLETE';
  entry.counts = { periods: 1, submitted: submitted ? 1 : 0, pending: 0, notSubmitted: submitted ? 0 : 1 };
  if (ctx.log) ctx.log('[check] ' + key + ': ' + entry.status + (submittedDate ? ' (' + submittedDate + ')' : ''));
  return entry;
}

async function checkObligation(ctx, key, year, cache) {
  const o = OBLIGATIONS[key];
  if (!o) return { obligation: key, error: 'UnknownObligation' };
  if (o.kind === 'income') return checkIncome(ctx, key, year);
  const entry = { obligation: key, label: o.label, declarationType: o.filter, category: o.category, periods: [] };
  if (ctx.log) ctx.log('[check] ' + key + ' (' + o.label + ') έτος ' + year);
  let resp;
  try { resp = (await getLiabilities(ctx, o, year, cache)).resp; }
  catch (e) { entry.status = 'ERROR'; entry.error = e.message; return entry; }
  if (ctx.dump) ctx.dump('check_' + key + '_' + year + '.html', resp);

  if (resp.includes('Δεν έχετε υποχρεώσεις υποβολής για το συγκεκριμένο έντυπο')) { entry.status = 'NOT_OBLIGATED'; if (ctx.log) ctx.log('[check] ' + key + ': ΔΕΝ υπόχρεος'); return entry; }
  const rows = liabilityRows(resp);
  if (!rows.length) { entry.status = 'NO_DATA'; if (ctx.log) ctx.log('[check] ' + key + ': καμία γραμμή'); return entry; }

  let submitted = 0, pending = 0, missing = 0;
  for (const row of rows) {
    let { period, status, statusText } = interpretRow(o, row.cells);
    if (o.searchType && status !== 'NOT_SUBMITTED') {
      const dl = declListQuery(row.cells[o.buttonCol] || '');
      if (dl) { const has = await subtypeSubmitted(ctx, o, dl.query); if (has === false) status = 'NOT_SUBMITTED'; }
    }
    if (status === 'SUBMITTED') submitted++; else if (status === 'PENDING') pending++; else missing++;
    entry.periods.push({ period, status, statusText });
  }
  entry.status = missing > 0 ? 'INCOMPLETE' : (pending > 0 ? 'PENDING' : 'COMPLETE');
  entry.counts = { periods: rows.length, submitted, pending, notSubmitted: missing };
  if (ctx.log) ctx.log('[check] ' + key + ': ' + entry.status + ' (✓' + submitted + ' ~' + pending + ' ✗' + missing + ' / ' + rows.length + ')');
  return entry;
}
// run all requested obligations -> report object
async function runAll(ctx, year, wanted) {
  const cache = new Map();
  const report = { portal: 'AADE taxisnet (GSIS OAM)', afm: ctx.vat, year: String(year), checkedAt: new Date().toISOString(), obligations: [] };
  for (const key of wanted) {
    if (!OBLIGATIONS[key]) { if (ctx.log) ctx.log('[check] άγνωστη: ' + key); report.obligations.push({ obligation: key, error: 'UnknownObligation' }); continue; }
    try { report.obligations.push(await checkObligation(ctx, key, year, cache)); }
    catch (e) { report.obligations.push({ obligation: key, error: e.message }); }
  }
  return report;
}

module.exports = {
  OBLIGATIONS, GROUPS, resolveWanted,
  decodeHtml, stripTags, between, liabilityRows, classify, declListQuery, periodFromArgs, interpretRow,
  accountantSetAsMySelf, getLiabilities, subtypeSubmitted, checkObligation, runAll,
  incomeSubmission, checkIncome,
};
