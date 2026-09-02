/*
 * configs/aade-lease.js  --  Μισθωτήρια (Πληροφοριακά Στοιχεία Μισθώσεων Ακίνητης Περιουσίας / PLCS)
 * Source: Hyper.Server.Tax.dll  Easy_Aade.GetLeaseContracts + LeaseContractHomePage + GetLeaseDataFromPage
 * (decompiled ~49935 / ~54531 / ~54575).  GSIS OAM login — lib.aadeLogin.
 *
 * REAL flow (app "plcs" = Πληροφοριακά Στοιχεία Μισθώσεων):
 *   GET  {AADE}/sgsisapps/plcs/protected/displayConsole.htm                    (console; role check)
 *   GET  {AADE}/sgsisapps/plcs/protected/displaySubmissionDetails.htm?yearShow=ALL&submissionId=plcs
 *        -> up to 3 tables: 0=Μισθωτής, 1=Εκμισθωτής, 2=Μ_Ο (each header td.tdleft "Αριθμός Καταχώρησης")
 *        rows tr[style="font-size: 10pt; border-style: solid; border-color: blue; border-width: thin"]
 *        cells td.displaySubmissionDetails: [0]=Αριθμός Καταχώρησης, [1]=Ημ/νία, Status=[table0?3:2],
 *        Kind col=[table0?4:3] (ΑΡΧΙΚΗ/ΤΡΟΠΟΠΟΙΗΤΙΚΗ/ΛΥΣΗ/ΔΗΛΩΣΗ COVID), Acceptance(table!=0)=[4] ΝΑΙ/ΟΧΙ
 *   per "Οριστικό" row -> input[name=button2 title="Προβολή"] siblings submissionId/versionId/transId ->
 *   POST {AADE}/sgsisapps/plcs/protected/gsis-flow.htm?_flowId=submissionByForm-flow -> detail HTML
 *        table#contractDetails: leaseTypeAux(είδος), Ημ/νία Έναρξης/Λύσης, AgreementDate/DateFrom/DateTo,
 *        TotalRent(ποσό), extensionContract(συνέχιση), Realty(διεύθυνση) — parsed to JSON.
 *
 * Analysis in JSON (per user): ενεργό/ανενεργό-ΛΥΣΗ, εκπρόθεσμα, είδος μίσθωσης, ποσό, διάρκεια.
 * PDF naming target: ΔΙΕΥΘΥΝΣΗ-ΠΟΛΗ-ΤΚ _ ΠΟΣΟ _ ΔΙΑΡΚΕΙΑ  (address + PDF endpoint pinned from first detail dump).
 * INPUTS: TAXISnet user/pass.
 */
'use strict';
const path = require('path');
const fs = require('fs');

const PLCS = '/sgsisapps/plcs/protected/';
const ROW_RE = /<tr\b[^>]*style="[^"]*border-color:\s*blue[^"]*"[^>]*>([\s\S]*?)<\/tr>/gi;
const clean = (s, strip) => strip(String(s || '')).replace(/<!--\s*covid[^>]*-->/gi, '').trim();

// Realty label -> field (== C# GetLeaseDataFromPage propertyTable mapping)
const REALTY_MAP = {
  'Είδος Ακινήτου': 'type', 'Τοπωνύμιο/Θέση': 'placeName', 'ΑΤΑΚ': 'atak', 'Οδός': 'address',
  'Αριθμός': 'addressNo', 'Τ.Κ.': 'zip', 'ΚΑΕΚ': 'kaek', 'Περιγραφή': 'description',
  'Δήμος/Περιοχή': 'municipality', 'Νομός': 'prefecture', 'Όροφος': 'floor',
  'Επιφάνεια κυρίωνχώρων': 'surfaceMain', 'Επιφάνεια βοηθ.χώρων': 'surfaceRest', 'Α/Α': 'aa',
};
// property tables: label row (class textbluelec3) then a value row aligned by column
function parseRealty(html, strip) {
  const out = [];
  for (const t of html.matchAll(/<table[^>]*id="[^"]*propertyTable[^"]*"[^>]*>[\s\S]*?<\/table>/gi)) {
    const rows = [...t[0].matchAll(/<tr\b[^>]*>([\s\S]*?)<\/tr>/gi)]
      .map(r => [...r[1].matchAll(/<td\b[^>]*>([\s\S]*?)<\/td>/gi)].map(c => strip(c[1])));
    const realty = {};
    for (let i = 0; i < rows.length - 1; i++) {
      if (!rows[i].some(c => REALTY_MAP[c])) continue;
      rows[i].forEach((lab, j) => { const k = REALTY_MAP[lab]; const v = rows[i + 1][j]; if (k && v) realty[k] = v; });
    }
    if (Object.keys(realty).length) out.push(realty);
  }
  return out;
}
function parseHolders(html, strip) {
  const out = [];
  for (const t of html.matchAll(/<table[^>]*id="leaseholderTable\d+"[^>]*>[\s\S]*?<\/table>/gi)) {
    for (const r of t[0].matchAll(/<tr\b[^>]*class="[^"]*displaysubmissiondetails1[^"]*"[^>]*>([\s\S]*?)<\/tr>/gi)) {
      const c = [...r[1].matchAll(/<td\b[^>]*>([\s\S]*?)<\/td>/gi)].map(x => strip(x[1]));
      if (c[2]) out.push({ aa: c[0], tin: c[2], description: c[3] });
    }
  }
  return out;
}

module.exports = {
  id: 'aade-lease',
  title: 'Μισθωτήρια (Πληροφοριακά Στοιχεία Μισθώσεων)',
  portal: 'AADE PLCS sgsisapps/plcs (www1.aade.gr)',
  subsystem: 'Hyper.Server',
  actions: ['retrieve', 'download'],
  inputs: [
    { key: 'user', label: 'TAXISnet username', env: 'AADE_USER' },
    { key: 'pass', label: 'TAXISnet password', env: 'AADE_PASS', hidden: true },
  ],

  async run(http, inp, lib) {
    const L = await lib.aadeLogin(http, inp);
    if (!L.ok) { http.log('AADE LOGIN FAILED: ' + L.reason); return { ok: false, reason: L.reason }; }
    const strip = lib.stripTags;

    // 1) console (activates the plcs app / role)
    const console1 = await http.follow('GET', new URL(PLCS + 'displayConsole.htm', L.AADE).toString());
    http.dump('lease_console.html', console1.text);
    if (!console1.text.includes('Πληροφοριακά Στοιχεία Μισθώσεων') && /Επιλογή Ρόλου|επιλέξτε τον πελάτη/i.test(console1.text)) {
      http.log('[lease] role-selection page returned (multi-client). Need the client role — send lease_console.html.');
    }

    // 2) submissions list (all years)
    const list = await http.follow('GET', new URL(PLCS + 'displaySubmissionDetails.htm?yearShow=ALL&submissionId=plcs', L.AADE).toString());
    http.dump('lease_list.html', list.text);
    if (!list.text.includes('Αριθμός Καταχώρησης')) { http.log('[lease] list has no "Αριθμός Καταχώρησης" — dump saved'); return { ok: false, reason: 'NoList', files: [] }; }

    // The list has up to 4 tables, each preceded by a <td class="tdleft">…Αριθμός Καταχώρησης header and a
    // <*.textbluelec2> title. Tables differ in columns: "Υποβληθείσες" rows have a counterparty-name col
    // (Status=[3],Kind=[4]); "ως Μισθωτής/Εκμισθωτής" rows don't (Status=[2],Kind=[3],Acceptance=[4]).
    // -> assign each row to the nearest header above it, and detect the status column per row.
    const headers = [...list.text.matchAll(/<td\b[^>]*class="[^"]*tdleft[^"]*"[^>]*>([\s\S]*?)<\/td>/gi)]
      .filter(m => /Αριθμός Καταχώρησης/.test(m[1])).map(m => m.index);
    const titles = [...list.text.matchAll(/class="textbluelec2"[^>]*>([\s\S]*?)<\/[a-z]+>/gi)].map(m => ({ pos: m.index, text: strip(m[1]) }));
    const labelFor = (pos) => {
      const t = titles.filter(x => x.pos < pos).pop();
      const s = t ? t.text : '';
      const role = /ως\s*Μισθωτ/i.test(s) ? 'Μισθωτής' : /ως\s*Εκμισθωτ/i.test(s) ? 'Εκμισθωτής' : /Υποβληθείσες/i.test(s) ? 'Υποβληθείσες' : 'Άλλο';
      return { label: s, role };
    };
    const STATUS_RE = /Οριστικό|Προσωρινά|Ακυρωμ|Απορρ|Εκκρεμ/;

    // each row holds its own <form>s (button2=Προβολή/detail, receiptButton=Απόδειξη PDF); each form's
    // hidden fields carry submissionId=plcs, versionId=plcsVer2013, transId=<regNo> (last value wins).
    const formsIn = (rowHtml) => [...rowHtml.matchAll(/<form\b[^>]*action="([^"]*)"[^>]*>([\s\S]*?)<\/form>/gi)].map(f => ({
      action: lib.decodeHtml(f[1]),
      fields: Object.fromEntries([...f[2].matchAll(/name="([^"]+)"[^>]*value="([^"]*)"/gi)].map(m => [m[1], m[2]])),
      view: /name="button2"/.test(f[2]), pdf: /name="receiptButton"/.test(f[2]),
    }));
    const leases = [];
    for (const rm of list.text.matchAll(ROW_RE)) {
      const tds = [...rm[1].matchAll(/<td\b[^>]*class="[^"]*displaySubmissionDetails[^"]*"[^>]*>([\s\S]*?)<\/td>/gi)].map(c => strip(c[1]));
      if (tds.length < 4) continue;
      const hdrPos = headers.filter(p => p < rm.index).pop();
      const { label, role } = labelFor(hdrPos != null ? hdrPos : rm.index);
      const statusIdx = STATUS_RE.test(tds[3] || '') ? 3 : 2;     // name-col table => 3, else 2
      const regNoFull = clean(tds[0], strip);
      const regNo = regNoFull.includes('(') ? regNoFull.split('(')[0].trim() : regNoFull;
      const forms = formsIn(rm[1]);
      const viewForm = forms.find(f => f.view), pdfForm = forms.find(f => f.pdf);
      const lz = {
        table: label, role, regNo, regNoFull,
        counterparty: statusIdx === 3 ? tds[2] : '',
        status: tds[statusIdx] || '', kindCell: tds[statusIdx + 1] || '',
        acceptance: statusIdx === 2 ? (tds[4] || '') : '', date: tds[1] || '',
        _view: viewForm ? { action: viewForm.action, fields: viewForm.fields } : null,
        _pdf: pdfForm ? { action: pdfForm.action, fields: pdfForm.fields } : null,
      };
      if (!regNo || !lz._view) continue;
      leases.push(lz);
    }
    const byRole = leases.reduce((a, l) => { a[l.role] = (a[l.role] || 0) + 1; return a; }, {});
    http.log('[lease] list parsed: ' + leases.length + ' εγγραφές — ' + JSON.stringify(byRole));

    // 3) per lease -> detail (Προβολή/button2 form) -> parse fields; -> Απόδειξη (receiptButton form) PDF
    const san = (s) => strip(String(s || '')).replace(/[\s\/]+/g, '_').replace(/[^0-9A-Za-zΑ-Ωα-ωάέήίόύώϊϋΐΰ.,_\-]/g, '').replace(/_+/g, '_').replace(/^_|_$/g, '').slice(0, 60);
    const base = list.url; // form actions are relative to the list page path (.../sgsisapps/plcs/protected/)
    const pdfs = [];
    let di = 0;
    for (const lz of leases) {
      if (!/Οριστικό/.test(lz.status)) { lz.skipped = 'not Οριστικό (' + lz.status + ')'; continue; }
      const detail = await http.follow('POST', new URL(lz._view.action, base).toString(), lz._view.fields);
      if (di < 4) http.dump('lease_detail_' + lz.role + '_' + di + '.html', detail.text);
      const d = detail.text, ds = strip(d);
      const selBlock = (d.match(/<select[^>]*name="leaseTypeAux"[\s\S]*?<\/select>/i) || [''])[0];
      lz.leaseType = strip((selBlock.match(/<option[^>]*selected[^>]*>([\s\S]*?)<\/option>/i) || [])[1] || '');
      lz.dateStart = (ds.match(/Ημερομηνία Έναρξης Μίσθωσης:\s*([0-9\/\.\-]{6,10})/) || [])[1] || '';
      lz.dateEnd = (ds.match(/Ημερομηνία Λύσης:\s*([0-9\/\.\-]{6,10})/) || [])[1] || '';
      lz.totalRent = (ds.match(/(?:Μηνιαίο|Συνολικό)?\s*Μίσθωμα[^0-9]{0,20}([\d.]+,\d{2})/i) || [])[1] || '';
      lz.leaseContinuation = /name="extensionContract"[^>]*value="true"/i.test(d);
      // full analysis: ακίνητα (διεύθυνση/πόλη/ΤΚ/είδος/όροφος/επιφάνεια) + εκμισθωτές/μισθωτές
      lz.realty = parseRealty(d, strip);
      lz.holders = parseHolders(d, strip);
      const rt = lz.realty[0] || {};
      lz.address = [rt.address, rt.addressNo].filter(Boolean).join(' '); // Οδός + Αριθμός
      lz.city = rt.municipality || rt.placeName || '';                    // Δήμος/Περιοχή
      lz.tk = rt.zip || '';                                               // Τ.Κ.
      lz.kind = /Λύση/.test(lz.kindCell) ? 'ΛΥΣΗ' : /Covid/i.test(lz.kindCell) ? 'ΔΗΛΩΣΗ COVID'
        : /Τροποποιητικ/.test(lz.kindCell) ? 'ΤΡΟΠΟΠΟΙΗΤΙΚΗ' : /Αρχικ/.test(lz.kindCell) ? 'ΑΡΧΙΚΗ' : lz.kindCell;
      lz.active = lz.kind !== 'ΛΥΣΗ' && !lz.dateEnd;   // ενεργό vs ανενεργό/λύση (εκπρόθεσμα φαίνονται στο status)

      // Απόδειξη (PDF) via the receiptButton form (POST reportController.htm {timeStamp, transId})
      if (lz._pdf) {
        const pdf = await http.postForPdf(new URL(lz._pdf.action, base).toString(), lz._pdf.fields);
        if (pdf) {
          const dur = [lz.dateStart, lz.dateEnd].filter(Boolean).join('-') || lz.dateStart || '';
          const name = [san(lz.address || lz.city || lz.role), san(lz.tk), san(lz.totalRent), san(dur)].filter(Boolean).join('_') || lz.regNo;
          const f = 'MISTH_' + lz.role + '_' + lz.regNo + '_' + name + '.pdf';
          fs.writeFileSync(path.join(http.dlDir, f), pdf); lz.pdf = f; pdfs.push(f);
          http.log('[lease] ✅ PDF -> ' + f + ' (' + pdf.length + ' b)');
        } else { http.log('[lease] PDF not returned for ' + lz.regNo + ' (reportController.htm)'); }
      }
      delete lz._view; delete lz._pdf;
      di++;
    }

    const jf = path.join(http.dlDir, 'AADE_leases_' + (inp.user || 'user') + '.json');
    fs.writeFileSync(jf, JSON.stringify({ portal: this.portal, retrievedAt: new Date().toISOString(), count: leases.length, byRole, active: leases.filter(l => l.active).length, leases }, null, 2));
    http.log('[lease] ✅ saved -> ' + path.basename(jf) + ' (' + di + ' details, ' + pdfs.length + ' PDF). Send me a lease_detail_*.html to finalize address extraction.');
    return { ok: true, files: [path.basename(jf), ...pdfs] };
  },
};
