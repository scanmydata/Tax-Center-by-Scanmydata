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
 * Ε3 / Ε3 myDATA ΝΟΜΙΚΟΥ ΠΡΟΣΩΠΟΥ (Easy_Aade.GetN_E1_E2_E3_Nomiko -> Download_E3_Nomiko / Download_E3Mydata_Nomiko):
 *   ίδια λίστα δηλώσεων Ν· γραμμή με td[2] (XPath) = ημερολογιακή περίοδος χρήσης Y-1, στον εμφωλευμένο πίνακα
 *   td[8] κουμπί «Προβολή Ε3» / «Δεδομένα myDATA» με onclick window.open('<URL>') (αν πολλές -> η πιο πρόσφατη td[4])
 *   Y ≥ 2018: GET <URL> (ανοίγει τη συνεδρία webtax/incomefp για το Ε3 του Ν.Π.) -> GET webtax/incomefp/login.done -> PDF
 *   Y < 2018: η σελίδα login.done έχει PRINT_CODE_E3/FORMA_E3 + JS -> POST rwservlet
 *             {cmdkey, p_afm=PRINT_CODE_E3, report=desname='E3FormN<yy>.rdf', desformat=pdf, destype=cache}
 *   RegNo = 'E3-'+td[3] (myDATA: 'E3-'+td[3]+'myDATA').
 *
 * INPUTS: TAXISnet user/pass (νομικού προσώπου) + Έτος αναφοράς (έτος υποβολής· αφορά χρήση Y-1)
 *         + docs (N,E3,E3MYDATA· κενό = όλα) + versions (κενό = πιο πρόσφατη υποβολή, όπως το TaxSystem· all = όλες).
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
    { key: 'docs', label: 'Έντυπα (N,E3,E3MYDATA· κενό = όλα)', env: 'AADE_FENP_DOCS', optional: true },
    { key: 'versions', label: 'Εκδόσεις (κενό = μόνο η πιο πρόσφατη, all = αρχική + τροποποιητικές)', env: 'AADE_FENP_VERSIONS', optional: true },
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

    // Δηλώσεις της χρήσης Y-1 (αρχική + τροποποιητικές). == SetDownloadPDF_FENPQueryParams: γραμμές tblRow,
    // td[2] = περίοδος, td[3] = αρ. καταχ., td[4] = ημ/νία υποβολής, td[5] = είδος, κουμπιά στον ΕΜΦΩΛΕΥΜΕΝΟ πίνακα td[8].
    // Default (όπως το TaxSystem): η ΠΙΟ ΠΡΟΣΦΑΤΗ υποβολή· versions=all -> όλες.
    const versions = this.declarationVersions(lib, list.text, Y);
    if (!versions.length) { http.log('[fenp] καμία δήλωση για χρήση ' + (Y - 1) + ' (δες fenp_list.html)'); return { ok: false, reason: 'NoDeclarations', files: [] }; }
    const allVersions = /^(all|όλες|ολες|1)$/i.test((inp.versions || '').trim());
    const chosen = allVersions ? versions : [versions[versions.length - 1]];
    const latest = versions[versions.length - 1];
    const regNo = 'N-' + latest.regNo;

    // Table strings for JSON (header fields of the declaration on the list page)
    const g1 = (re) => { const m = list.text.match(re); return m ? strip(m[1]).replace(/\s+/g, ' ').trim() : null; };
    const fiscalPeriod = latest.period || g1(/Φορολογικό Έτος:<\/b>\s*<\/td>\s*<td[^>]*>([\s\S]*?)<\/td>/i);
    const fiscalYear = (String(fiscalPeriod || '').match(/(\d{4})/) || [])[1] || String(Y - 1);
    const table = { typosDilosis: g1(/Τύπος Δήλωσης:<\/b>\s*<\/td>\s*<td[^>]*>([\s\S]*?)<\/td>/i), fiscalPeriod, fiscalYear, arithmosKatax: latest.regNo,
      versions: versions.map(v => ({ regNo: v.regNo, submittedOn: v.submittedOn, kind: v.kind, buttons: Object.keys(v.buttons) })) };
    http.log('[fenp] χρήση ' + fiscalYear + ' · ' + versions.length + ' δήλωση(εις): ' + versions.map(v => v.regNo + ' ' + v.kind + ' ' + v.submittedOn).join(' | '));

    const want = new Set(((inp.docs || '').toUpperCase().replace(/Ε/g, 'E').match(/E3MYDATA|MYDATA|E3|N/g) || ['N', 'E3', 'E3MYDATA']).map(x => x === 'MYDATA' ? 'E3MYDATA' : x));
    const result = { portal: this.portal, afm, year: Y, fiscalYear, regNo, table, retrievedAt: new Date().toISOString(), docs: {} };
    const files = [];
    const jf = 'AADE_fenp_' + afm + '_' + Y + '.json';
    for (const v of chosen) {
      const sfx = allVersions ? '_' + v.regNo : '';
      const put = (k, val) => { if (allVersions) { (result.docs[k] = result.docs[k] || []).push(val); } else result.docs[k] = val; };
      if (want.has('N')) put('N', await this.getN(http, lib, P, v, afm, Y, sfx, files));
      for (const [key, button, suffix] of [['E3', 'Προβολή Ε3', ''], ['E3MYDATA', 'Δεδομένα myDATA', 'myDATA']]) {
        if (!want.has(key)) continue;
        try { put(key, await this.getE3(http, lib, L, v, Y, button, suffix, afm, sfx, files)); }
        catch (e) { put(key, { status: 'Error', regNo: v.regNo, error: String(e && e.message || e) }); http.log('[fenp] ' + key + ': ' + String(e && e.message || e)); }
      }
    }
    if (result.docs.N && result.docs.N.pdf) { result.pdf = result.docs.N.pdf; result.bytes = result.docs.N.bytes; }
    fs.writeFileSync(path.join(http.dlDir, jf), JSON.stringify(result, null, 2));
    const st = (v) => Array.isArray(v) ? v.map(x => x.status).join(',') : v.status;
    return { ok: files.length > 0, files: [jf, ...files], summary: Object.fromEntries(Object.entries(result.docs).map(([k, v]) => [k, st(v)])) };
  },

  // γραμμές δηλώσεων της χρήσης Y-1, ταξινομημένες κατά ημ/νία υποβολής (παλαιότερη -> νεότερη)
  declarationVersions(lib, listHtml, Y) {
    const strip = lib.stripTags;
    const starts = [...listHtml.matchAll(/<tr\b[^>]*class="tblRow[12]"[^>]*>/gi)].map(m => m.index);
    const out = [];
    starts.forEach((st, k) => {
      const seg = listHtml.slice(st, k + 1 < starts.length ? starts[k + 1] : listHtml.length);
      const tds = [...seg.matchAll(/<td\b[^>]*>([\s\S]*?)<\/td>/gi)].slice(0, 5).map(m => strip(m[1]));
      const per = (tds[1] || '').split('-');
      if (per.length !== 2 || (per[0].match(/(\d{4})/) || [])[1] !== String(Y - 1)) return;
      const buttons = {};
      for (const m of seg.matchAll(/<div\b([^>]*)>([\s\S]*?)<\/div>/gi)) {
        const name = strip(m[2]); const oc = lib.decodeHtml((m[1].match(/onclick\s*=\s*(["'])([\s\S]*?)\1/i) || [])[2] || '');
        if (name && oc && !buttons[name]) buttons[name] = oc;
      }
      const d = (tds[3] || '').match(/(\d{2})\/(\d{2})\/(\d{4})/);
      out.push({ period: tds[1], regNo: tds[2], submittedOn: tds[3], kind: tds[4], when: d ? d[3] + d[2] + d[1] : '', buttons });
    });
    return out.sort((a, b) => a.when.localeCompare(b.when));
  },

  // Download_FENP: «Προβολή» -> doViewPdfTaxisnet(frm, id, type) | doViewPdfTaxis(frm, 12 args incomeN) -> POST viewPdf.htm
  async getN(http, lib, P, v, afm, Y, sfx, files) {
    const oc = v.buttons['Προβολή'] || '';
    const args = (oc.match(/^\s*doViewPdfTaxis(?:net)?\s*\(([\s\S]*?)\)\s*;?\s*$/) || [])[1];
    const a = args ? args.split(',').slice(1).map(x => x.replace(/["']/g, ' ').trim()) : [];
    let pdfParams = null, via = '';
    if (/^\s*doViewPdfTaxisnet/.test(oc) && a.length >= 2) { pdfParams = { declarationDatabaseId: a[0], declarationType: a[1] }; via = 'taxisnet'; }
    else if (a.length >= 12 && /income/i.test(a[0])) {
      pdfParams = { 'taxisPK.num': a[3], 'taxisPK.doy': a[4], 'taxisPK.year': a[5], 'taxisPK.taxArea': a[6], 'taxisPK.docType': a[7], 'effectivePeriod.start': a[8], 'effectivePeriod.end': a[9], submissionDate: a[10], submissionType: a[1], declarationType: a[0], periodType: a[2], referenceYear: a[11] };
      via = 'taxis';
    }
    if (!pdfParams) { http.log('[fenp] Ν ' + v.regNo + ': χωρίς «Προβολή»'); return { status: 'NotFound', regNo: v.regNo }; }
    const pdf = await http.postForPdf(P('viewPdf.htm'), pdfParams);
    if (!pdf) { http.log('[fenp] Ν ' + v.regNo + ': δεν επέστρεψε PDF'); return { status: 'NoPDF', regNo: v.regNo }; }
    const f = 'FENP_N_' + afm + '_' + Y + sfx + '.pdf';
    fs.writeFileSync(path.join(http.dlDir, f), pdf); files.push(f);
    http.log('[fenp] ✅ Ν -> ' + f + ' (' + pdf.length + ' b) · N-' + v.regNo + ' ' + v.kind + ' · υποβ. ' + v.submittedOn);
    return { status: 'ok', regNo: 'N-' + v.regNo, kind: v.kind, submittedOn: v.submittedOn, via, pdf: f, bytes: pdf.length };
  },

  // Download_E3_Nomiko / Download_E3Mydata_Nomiko: κουμπί «Προβολή Ε3» / «Δεδομένα myDATA» -> window.open('<URL>')
  async getE3(http, lib, L, v, Y, button, suffix, afm, sfx, files) {
    const oc = v.buttons[button] || '';
    const url = oc.startsWith('window.open') ? (oc.match(/'([^']+)'/) || [])[1] : null;
    if (!url) { http.log('[fenp] ' + v.regNo + ': δεν υπάρχει κουμπί «' + button + '»'); return { status: 'NotFound', regNo: v.regNo }; }
    const w = { url, regNo: v.regNo, submittedOn: v.submittedOn, comment: v.kind };
    const regNo = 'E3-' + w.regNo + suffix;
    const tag = suffix ? 'E3MYDATA' : 'E3';
    const openUrl = new URL(w.url, new URL('/taxisnet/income/protected/', L.AADE)).toString();
    if (suffix && !/afmlegal=[^')&]+/.test(w.url)) return { status: 'NotFound', regNo, reason: 'no afmlegal' };
    const opened = await http.follow('GET', openUrl);                       // ανοίγει τη συνεδρία webtax/incomefp
    http.dump('fenp_' + tag + '_open.html', opened.text);
    if (!opened.text || !opened.text.length) return { status: 'Error', regNo, reason: 'EmptyOpen' };
    const LOGIN_DONE = new URL('/webtax/incomefp/login.done', L.AADE).toString();
    let pdf = null;
    if (suffix || Y >= 2018) {
      const d = await http.getDoc(LOGIN_DONE);
      pdf = d.buffer || null;
      if (!pdf) http.dump('fenp_' + tag + '_logindone.html', d.text || '');
    } else {
      // < 2018: PRINT_CODE_E3 + FORMA_E3 + JS (report E3FormN<yy>.rdf) -> POST rwservlet
      const page = (await http.follow('GET', LOGIN_DONE)).text;
      http.dump('fenp_E3_logindone.html', page);
      const val = (n) => (page.match(new RegExp('name="' + n + '"[^>]*value="([^"]*)"', 'i')) || page.match(new RegExp('value="([^"]*)"[^>]*name="' + n + '"', 'i')) || [])[1] || '';
      const code = val('PRINT_CODE_E3'), forma = val('FORMA_E3');
      const js = page.match(/e3_year=document\.forms\[0\]\["FORMA_E3"\]\.value\.substring\(2,4\)[\s\S]*?document\.forms\[1\]\.action='([^']+)'[\s\S]*?document\.forms\[1\]\["cmdkey"\]\.value='([^']+)'/);
      if (code && forma.length > 2 && js) {
        const rep = 'E3FormN' + forma.substring(2) + '.rdf';
        pdf = await http.postForPdf(new URL(js[1], LOGIN_DONE).toString(), { cmdkey: js[2], p_afm: code, report: rep, desname: rep, desformat: 'pdf', destype: 'cache' });
      }
    }
    if (!pdf) { http.log('[fenp] ' + tag + ': δεν επέστρεψε PDF'); return { status: 'NoPDF', regNo, submittedOn: w.submittedOn }; }
    const f = 'FENP_' + tag + '_' + afm + '_' + Y + sfx + '.pdf';
    fs.writeFileSync(path.join(http.dlDir, f), pdf); files.push(f);
    http.log('[fenp] ✅ ' + tag + ' -> ' + f + ' (' + pdf.length + ' b) · ' + regNo + ' · υποβ. ' + w.submittedOn);
    return { status: 'ok', regNo, submittedOn: w.submittedOn, comment: w.comment, pdf: f, bytes: pdf.length };
  },
};
