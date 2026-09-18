/*
 * configs/easycheck.js  --  EasyCheck: μαζικός έλεγχος υποχρεώσεων (ObligationScan)
 * =============================================================================
 * FAITHFUL reproduction of Scheduler.Classes.ObligationScan (EasyCheck subsystem).
 * ΔΕΝ κάνει scraping — δουλεύει πάνω στη ΒΑΣΗ του TaxSystem (SQL Server): αντλεί από
 * τα DOCUMENTS/DOC_FIELDS/PARTY ποιες υποχρεώσεις (OBLIGATIONS) αφορούν κάθε υπόχρεο,
 * και διασταυρώνει με OBLIGATION_DATES (προθεσμίες) + CALENDAR_APPOINMENTS (υπάρχουσες
 * εργασίες) για να βρει τι ΛΕΙΠΕΙ.
 *
 * Οι ΑΚΡΙΒΕΙΣ 56 ερωτήσεις + docIdsYear + param-mapping εξάγονται από το decompiled
 * Scheduler (scripts/extract-easycheck.js) στο `easycheck-queries.json` — καμία
 * χειροκίνητη αντιγραφή, ίδιες SQL με το TaxSystem.
 *
 * ΤΡΕΞΙΜΟ (standalone, χρειάζεται SQL Server σύνδεση + `npm i mssql`):
 *   node configs/easycheck.js --year 2024 [--dbYear 2024] [--json out.json] [--apply]
 * Σύνδεση από env: EASYCHECK_DB_SERVER, _DATABASE, _USER, _PASSWORD  (ή EASYCHECK_DB_CONNSTRING).
 * Default = DRY-RUN (μόνο αναφορά, καμία εγγραφή). Το --apply είναι σκόπιμα ΑΝΕΝΕΡΓΟ:
 * η δημιουργία εργασιών γίνεται μέσω του wizard του TaxSystem (fMassCreationErgasies…),
 * εδώ παράγουμε την αναφορά «τι λείπει» (το «check» του EasyCheck).
 *
 * Ως runner-config export: run(http, inp, lib) απλώς εξηγεί πώς τρέχει (χρειάζεται DB).
 */
'use strict';
const fs = require('fs');
const path = require('path');

const SPEC_PATH = path.join(__dirname, 'easycheck-queries.json');

// Resolve a C# string.Format arg expr against docIdsYear[year] and the years.
function resolveArg(expr, docRow, scanYear, dmYear) {
  const m = expr.match(/^docIdsYear\[(\d+),\s*(\d+)\]$/);
  if (m) return docRow ? docRow[+m[1]][+m[2]] : 0;
  if (expr === 'Dm.Year') return dmYear;
  if (expr === 'year') return scanYear;
  const n = Number(expr); return Number.isFinite(n) ? n : expr;
}
// C# string.Format {0},{1},... substitution
function fmt(tpl, args) { return tpl.replace(/\{(\d+)\}/g, (_, i) => (args[+i] !== undefined ? String(args[+i]) : '{' + i + '}')); }

// Build the exact SQL list ObligationScan.MatchObligations would run for a year.
function buildObligationQueries(spec, scanYear, dmYear) {
  const docRow = spec.docIdsYear[String(scanYear)] || null;
  const out = [];
  for (const q of spec.queryOrder) {
    const tpl = spec.queries[q]; const argExprs = spec.format[q] || [];
    const args = argExprs.map(e => resolveArg(e, docRow, scanYear, dmYear));
    out.push({ name: q, sql: fmt(tpl, args), args, argExprs });
  }
  return { docRow, queries: out };
}

async function runEasyCheck({ scanYear, dmYear, jsonOut, logger }) {
  const log = logger || console.log;
  let mssql;
  try { mssql = require('mssql'); }
  catch (e) { throw new Error('Χρειάζεται το πακέτο mssql:  npm i mssql   (EasyCheck δουλεύει πάνω στη SQL βάση του TaxSystem)'); }

  const spec = JSON.parse(fs.readFileSync(SPEC_PATH, 'utf8'));
  if (!spec.docIdsYear[String(scanYear)]) log('[easycheck] ⚠ Το FillDocIds του Scheduler ορίζει docIds μόνο για 2012/2013· για ' + scanYear + ' οι doc-based ερωτήσεις δεν επιστρέφουν (== TaxSystem).');

  const cfg = process.env.EASYCHECK_DB_CONNSTRING
    ? process.env.EASYCHECK_DB_CONNSTRING
    : {
        server: process.env.EASYCHECK_DB_SERVER || 'localhost',
        database: process.env.EASYCHECK_DB_DATABASE,
        user: process.env.EASYCHECK_DB_USER, password: process.env.EASYCHECK_DB_PASSWORD,
        options: { trustServerCertificate: true, encrypt: false }, pool: { max: 4 },
      };
  const pool = await mssql.connect(cfg);
  const q = async (sql) => (await pool.request().query(sql)).recordset;

  try {
    // 1) party universe (== FillDataTables)
    const parties = await q(fmt(spec.partySql, [scanYear]));
    log('[easycheck] parties: ' + parties.length + ' (έτος ' + scanYear + ')');

    // 2) MatchObligations — 56 heuristics -> (PARTY_ID, OBL_ID)
    const { queries } = buildObligationQueries(spec, scanYear, dmYear);
    const oblMatch = new Map();   // partyId -> Set(oblId)
    let matchRows = 0;
    for (const item of queries) {
      let rs;
      try { rs = await q(item.sql); }
      catch (e) { log('[easycheck] ' + item.name + ' SQL error: ' + e.message.split('\n')[0]); continue; }
      for (const r of rs) {
        const pid = r.PARTY_ID, oid = r.OBL_ID;
        if (pid == null || oid == null) continue;
        if (!oblMatch.has(pid)) oblMatch.set(pid, new Set());
        oblMatch.get(pid).add(oid); matchRows++;
      }
    }
    log('[easycheck] υποχρεώσεις-ανά-υπόχρεο: ' + matchRows + ' ζεύγη, ' + oblMatch.size + ' υπόχρεοι');

    // 3) obligation deadlines (OBLIGATION_DATES) for the fiscal year
    const oblDates = await q(spec.oblDatesSql + " Where OBLIGATION_DATES.FISCALYEAR = " + scanYear);
    const datesByObl = new Map();
    for (const d of oblDates) { const k = d.OBLIGATIONS_ID; if (!datesByObl.has(k)) datesByObl.set(k, []); datesByObl.get(k).push(d); }

    // 4) existing calendar tasks per party+obligation (== CALENDAR_APPOINMENTS + AppointmentDetails)
    //    (read-only «check»: ποια ζεύγη έχουν ήδη εργασία)
    let existing = new Set();
    try {
      const appts = await q(
        "SELECT ca.PartyId AS PARTY_ID, ad.OBLIGATION_ID AS OBL_ID\n" +
        "FROM CALENDAR_APPOINMENTS ca WITH (NOLOCK)\n" +
        "LEFT JOIN AppointmentDetails ad WITH (NOLOCK) ON ad.AppointmentId = ca.AppointmentId\n" +
        "WHERE ISNULL(ca.statusId,0) <> 4 AND ad.OBLIGATION_ID IS NOT NULL");
      for (const a of appts) existing.add(a.PARTY_ID + '#' + a.OBL_ID);
    } catch (e) { log('[easycheck] ⚠ CALENDAR_APPOINMENTS/AppointmentDetails cross-check παραλείφθηκε: ' + e.message.split('\n')[0]); }

    // 5) report: per party, obligations that apply and LACK a task
    const partyMeta = new Map(parties.map(p => [p.PARTY_ID, p]));
    const report = [];
    for (const [pid, obls] of oblMatch) {
      const missing = [];
      for (const oid of obls) {
        if (existing.has(pid + '#' + oid)) continue;
        missing.push({ obligationId: oid, dueDates: (datesByObl.get(oid) || []).map(d => ({ date: d.DATE, period: d.PERIOD, periodTypeId: d.PERIOD_TYPE_ID })) });
      }
      if (missing.length) report.push({ partyId: pid, afm: (partyMeta.get(pid) || {}).F_AFM, obligationsApplied: [...obls], missingTasks: missing });
    }
    log('[easycheck] ✅ υπόχρεοι με υποχρεώσεις χωρίς εργασία: ' + report.length);

    const result = { scanYear, dmYear, generatedAt: new Date().toISOString(), parties: parties.length, oblMatchPairs: matchRows, partiesWithMissing: report.length, report };
    if (jsonOut) { fs.writeFileSync(jsonOut, JSON.stringify(result, null, 2)); log('[easycheck] γράφτηκε -> ' + jsonOut); }
    return result;
  } finally { await pool.close(); }
}

// ---- CLI ----
if (require.main === module) {
  const argv = process.argv.slice(2);
  const get = (k, d) => { const i = argv.indexOf('--' + k); return i >= 0 ? (argv[i + 1] && !argv[i + 1].startsWith('--') ? argv[i + 1] : true) : d; };
  const scanYear = parseInt(get('year', new Date().getFullYear() - 1), 10);
  const dmYear = parseInt(get('dbYear', scanYear), 10);
  const jsonOut = get('json', path.join(__dirname, '..', 'downloads', 'easycheck', 'EASYCHECK_' + scanYear + '.json'));
  if (get('apply', false)) { console.error('[easycheck] --apply είναι ανενεργό: η μαζική δημιουργία εργασιών γίνεται από τον wizard του TaxSystem. Τρέχω dry-run.'); }
  try { fs.mkdirSync(path.dirname(jsonOut), { recursive: true }); } catch (e) {}
  runEasyCheck({ scanYear, dmYear, jsonOut }).then(r => { console.log('DONE: ' + r.partiesWithMissing + ' υπόχρεοι με ελλείψεις (report -> ' + jsonOut + ')'); process.exit(0); })
    .catch(e => { console.error('EasyCheck error: ' + e.message); process.exit(1); });
}

// ---- runner-config export (documents that EasyCheck needs a DB, not HTTP) ----
module.exports = {
  id: 'easycheck',
  title: 'EasyCheck — Μαζικός έλεγχος υποχρεώσεων (ObligationScan, DB-driven)',
  portal: 'TaxSystem SQL Server (όχι HTTP)',
  subsystem: 'Scheduler',
  actions: ['scan'],
  inputs: [
    { key: 'year', label: 'Έτος ελέγχου (fiscal year)', env: 'EASYCHECK_YEAR' },
  ],
  runEasyCheck, buildObligationQueries,   // exported for programmatic use / tests
  async run(http, inp, lib) {
    const y = parseInt((inp.year || '').trim() || (new Date().getFullYear() - 1), 10);
    http.log('[easycheck] Το EasyCheck δουλεύει πάνω στη SQL βάση του TaxSystem (όχι HTTP scraping).');
    http.log('[easycheck] Τρέξε: node configs/easycheck.js --year ' + y + '   (env: EASYCHECK_DB_SERVER/_DATABASE/_USER/_PASSWORD, npm i mssql)');
    try {
      const r = await module.exports.runEasyCheck({ scanYear: y, dmYear: y, jsonOut: path.join(http.dlDir, 'EASYCHECK_' + y + '.json'), logger: http.log.bind(http) });
      return { ok: true, files: ['EASYCHECK_' + y + '.json'], summary: { partiesWithMissing: r.partiesWithMissing } };
    } catch (e) {
      http.log('[easycheck] ' + e.message);
      return { ok: false, reason: e.message };
    }
  },
};
