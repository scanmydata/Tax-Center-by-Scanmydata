/*
 * configs/aade-fenp.js  --  ΦΕΝΠ / Έντυπο Ν (Δήλωση Φορολογίας Εισοδήματος Νομικών Προσώπων)
 * Source: Hyper.Server.Tax.dll  Easy_Aade.LoginFENP + GetDisplayDeclarationsLegalEntity + Download_FENP
 * (decompiled ~56433/56640/43674).  GSIS OAM login. Νομικά πρόσωπα only.
 *
 * FAITHFUL chain (per reference year Y; οι δηλώσεις αφορούν χρήση Y-1):
 *   GET  taxisnet/income/protected/displayActorRoles.htm
 *   POST taxisnet/income/protected/displayDeclarationTypes.htm {actorRole:'SELF_SERVICE'}
 *   ClickLoginFENP: <form name='gotoincomeN' action=A> + inputs declarationType(=incomeN)/year
 *        -> GET income/protected/{A}?declarationType=..&year=..                       (FENP landing)
 *   listParams (doDisplayDeclarationsList 8-args, row td[0]=περίοδος year==Y-1, td[2]='ΕπεξεργασίαΔηλώσεων')
 *        -> GET income/protected/displayDeclarationsList.htm?{listParams}             (declarations list)
 *   pdfParams (doViewPdfTaxis onclick: 3-args {declarationDatabaseId,declarationType} OR 13-args incomeN)
 *        -> POST income/protected/viewPdf.htm {pdfParams} -> PDF.  RegNo = 'N-'+td[3].
 *
 * INPUTS: TAXISnet user/pass (νομικού προσώπου) + Έτος αναφοράς.
 */
'use strict';
const path = require('path');
const fs = require('fs');

const rows = (html) => [...html.matchAll(/<tr\b[^>]*class="tblRow[12]"[^>]*>([\s\S]*?)<\/tr>/gi)]
  .map(r => [...r[1].matchAll(/<td\b[^>]*>([\s\S]*?)<\/td>/gi)].map(c => c[1]));
const onclickArgs = (fnName, html) => { // strip "fn(" ... ");" -> comma-split args (quotes trimmed)
  const i = html.indexOf(fnName + '(');
  if (i < 0) return null;
  const j = html.indexOf(');', i);
  if (j < 0) return null;
  return html.slice(i + fnName.length + 1, j).split(',').map(s => s.replace(/["']/g, ' ').trim());
};
// Grab the REAL button onclick "fn(document.<form>, args...);" (not the JS function definition "fn(frm,...)").
// Returns args WITHOUT the leading frm reference.
const callArgs = (html, fn) => {
  const re = new RegExp(fn.replace(/[.*+?^${}()|[\]\\]/g, '\\$&') + '\\s*\\(\\s*document\\.[A-Za-z0-9_]+([^;]*?)\\)\\s*;');
  const m = html.match(re);
  if (!m) return null;
  return m[1].replace(/^\s*,/, '').split(',').map(s => s.replace(/["']/g, ' ').trim()).filter(s => s.length);
};

module.exports = {
  id: 'aade-fenp',
  title: 'ΦΕΝΠ / Έντυπο Ν (Νομικά Πρόσωπα)',
  portal: 'AADE taxisnet/income (GSIS OAM)',
  subsystem: 'Hyper.Server',
  actions: ['retrieve', 'download'],
  inputs: [
    { key: 'user', label: 'TAXISnet username (νομικού προσώπου)', env: 'AADE_USER' },
    { key: 'pass', label: 'TAXISnet password', env: 'AADE_PASS', hidden: true },
    { key: 'year', label: 'Έτος αναφοράς', env: 'AADE_YEAR' },
  ],

  async run(http, inp, lib) {
    const L = await lib.aadeLogin(http, inp);
    if (!L.ok) { http.log('AADE LOGIN FAILED: ' + L.reason); return { ok: false, reason: L.reason }; }
    const strip = lib.stripTags;
    const Y = parseInt((inp.year || '').trim(), 10);
    if (!Y) { http.log('Χρειάζεται Έτος'); return { ok: false, reason: 'NoYear' }; }
    const afm = (strip(L.page.text || '').match(/Α\.?Φ\.?Μ\.?\s*[:\-]?\s*(\d{9})/) || [])[1] || inp.user;
    const P = (rel) => new URL('/taxisnet/income/protected/' + rel, L.AADE).toString();
    const yr = (cell) => { const p = strip(cell).split('-'); if (p.length !== 2) return null; const m = (p[0].match(/(\d{4})/) || [])[1]; return m ? parseInt(m, 10) : null; };

    // LoginFENP
    await http.follow('GET', P('displayActorRoles.htm'));
    const types = await http.follow('POST', P('displayDeclarationTypes.htm'), { actorRole: 'SELF_SERVICE' });
    http.dump('fenp_types.html', types.text);
    // gotoincomeN form
    const gAction = (types.text.match(/<form[^>]*name="gotoincomeN"[^>]*action="([^"]*)"/i) || types.text.match(/<form[^>]*action="([^"]*)"[^>]*name="gotoincomeN"/i) || [])[1];
    if (!gAction) { http.log('[fenp] δεν βρέθηκε form gotoincomeN (δες fenp_types.html)'); return { ok: false, reason: 'NoGotoIncomeN' }; }
    const decType = (types.text.match(/name="declarationType"[^>]*value="([^"]*)"/i) || [])[1] || 'incomeN';
    const gYear = (types.text.match(/name="year"[^>]*value="([^"]*)"/i) || [])[1] || String(Y);
    const landing = await http.follow('GET', new URL(lib.decodeHtml(gAction) + '?declarationType=' + encodeURIComponent(decType) + '&year=' + encodeURIComponent(gYear), P('')).toString());
    http.dump('fenp_landing.html', landing.text);

    // GetDisplayDeclarationsLegalEntity: row td[0]=περίοδος (year==Y-1), td[2]='ΕπεξεργασίαΔηλώσεων' -> doDisplayDeclarationsList 8 args
    let listParams = null;
    for (const tds of rows(landing.text)) {
      if (yr(tds[0] || '') !== Y - 1) continue;
      if (strip(tds[2] || '').replace(/\s+/g, '') !== 'ΕπεξεργασίαΔηλώσεων') continue;
      const a = onclickArgs('doDisplayDeclarationsList', tds[2] || '');
      if (a && a.length >= 8) { listParams = { declarationType: a[1], year: a[2], periodType: a[3], periodStart: a[4], periodEnd: a[5], effectivePeriodStart: a[6], effectivePeriodEnd: a[7] }; break; }
    }
    if (!listParams) { http.log('[fenp] δεν βρέθηκε γραμμή «Επεξεργασία Δηλώσεων» για χρήση ' + (Y - 1) + ' (δες fenp_landing.html)'); return { ok: false, reason: 'NoDeclarationsRow', files: [] }; }
    const list = await http.follow('GET', P('displayDeclarationsList.htm') + '?' + new URLSearchParams(listParams).toString());
    http.dump('fenp_list.html', list.text);

    // Download_FENP: the «Προβολή» button carries the PDF onclick.  The Ενέργειες cell holds a
    // NESTED table, so cell-index parsing is unreliable -- search the button onclick directly.
    //  doViewPdfTaxisnet(frm, declarationId, declType)              -> {declarationDatabaseId, declarationType}
    //  doViewPdfTaxis(frm, declType, subType, periodType, pkNum, pkDoy, pkYear, pkTaxArea, pkDocType,
    //                 effStart, effEnd, subDate, referenceYear)     -> taxisPK.* form
    let pdfParams = null, regNo = '', via = '';
    const aNet = callArgs(list.text, 'doViewPdfTaxisnet');            // [declarationId, declType]
    const aTax = callArgs(list.text, 'doViewPdfTaxis');              // [declType, subType, periodType, pkNum...]
    if (aNet && aNet.length >= 2) {
      pdfParams = { declarationDatabaseId: aNet[0], declarationType: aNet[1] };
      regNo = 'N-' + aNet[0]; via = 'taxisnet';
    } else if (aTax && aTax.length >= 12 && /income/i.test(aTax[0])) {
      pdfParams = { 'taxisPK.num': aTax[3], 'taxisPK.doy': aTax[4], 'taxisPK.year': aTax[5], 'taxisPK.taxArea': aTax[6], 'taxisPK.docType': aTax[7], 'effectivePeriod.start': aTax[8], 'effectivePeriod.end': aTax[9], submissionDate: aTax[10], submissionType: aTax[1], declarationType: aTax[0], periodType: aTax[2], referenceYear: aTax[11] };
      regNo = 'N-' + aTax[3]; via = 'taxis';
    }
    if (!pdfParams) { http.log('[fenp] δεν βρέθηκε «Προβολή» (doViewPdfTaxisnet/doViewPdfTaxis) (δες fenp_list.html)'); return { ok: false, reason: 'NoViewPdf', files: [] }; }

    // Table strings for JSON (header fields of the declaration on the list page)
    const g1 = (re) => { const m = list.text.match(re); return m ? strip(m[1]).replace(/\s+/g, ' ').trim() : null; };
    const fiscalPeriod = g1(/Φορολογικό Έτος:<\/b>\s*<\/td>\s*<td[^>]*>([\s\S]*?)<\/td>/i)
      || (list.text.match(/(\d{2}\/\d{2}\/\d{4}\s*-\s*\d{2}\/\d{2}\/\d{4})/) || [])[1] || null;
    const fiscalYear = (String(fiscalPeriod || '').match(/(\d{4})/) || [])[1] || String(Y - 1);
    const table = {
      typosDilosis: g1(/Τύπος Δήλωσης:<\/b>\s*<\/td>\s*<td[^>]*>([\s\S]*?)<\/td>/i),
      fiscalPeriod, fiscalYear,
      arithmosKatax: regNo.replace(/^N-/, ''),
      via,
    };
    http.log('[fenp] Προβολή via ' + via + ' | αρ.καταχ ' + table.arithmosKatax + ' | χρήση ' + fiscalYear);

    const pdf = await http.postForPdf(P('viewPdf.htm'), pdfParams);
    const result = { portal: this.portal, afm, year: Y, fiscalYear, regNo, table, retrievedAt: new Date().toISOString() };
    if (!pdf) { http.log('[fenp] δεν επέστρεψε PDF'); result.status = 'NoPDF'; fs.writeFileSync(path.join(http.dlDir, 'AADE_fenp_' + afm + '_' + Y + '.json'), JSON.stringify(result, null, 2)); return { ok: false, reason: 'NoPDF' }; }
    const f = 'FENP_N_' + afm + '_' + Y + '.pdf';
    fs.writeFileSync(path.join(http.dlDir, f), pdf); result.pdf = f; result.bytes = pdf.length;
    fs.writeFileSync(path.join(http.dlDir, 'AADE_fenp_' + afm + '_' + Y + '.json'), JSON.stringify(result, null, 2));
    http.log('[fenp] ✅ PDF -> ' + f + ' (' + pdf.length + ' b)');
    return { ok: true, files: ['AADE_fenp_' + afm + '_' + Y + '.json', f] };
  },
};
