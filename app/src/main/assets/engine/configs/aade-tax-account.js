/*
 * configs/aade-tax-account.js  --  Φορολογικός Λογαριασμός (Μηνιαία Ενημέρωση) — MonthTaxInfo
 * Source: Hyper.Server.Tax.dll  Easy_Aade.GetMonthTaxInfo + MonthTaxInfoGetToken (decompiled ~50844 / 53370).
 * App: www1.aade.gr/saadeapps3/MonthlyReport  (token-guarded REST, GSIS OAM login).
 *
 * FAITHFUL flow (after aadeLogin OAM):
 *   GET  /saadeapps3/MonthlyReport/?#!/arxiki                                 (establish app session)
 *   GET  .../webresources/monthlyreport/getToken  -> JSON{k:v}; token = base64urlDecode(v).replace('"','')
 *   POST .../getuserdata            (Authorization: token)          -> <result>base64url</result> -> {afm,...}
 *   POST .../getEtos  jsonText=b64u({p1:afm,p2:afm})                -> [{Etos, M1..M12}]  (διαθέσιμοι μήνες/έτος)
 *   POST .../getPrintPDF jsonText=b64u({p1:afm,p2:Etos,p3:mhnas})   -> PDF   (Authorization ανά κλήση)
 * κάθε POST θέλει ΦΡΕΣΚΟ token στο Authorization.
 *
 * INPUTS: TAXISnet user/pass + (προαιρετικά) Έτος + Μήνας (1-12). Κενά = το πιο πρόσφατο διαθέσιμο.
 * OUTPUT: FOR_LOGARIASMOS_<ΑΦΜ>_<έτος>_<μήνας>.pdf + AADE_tax_account_<ΑΦΜ>.json (διαθέσιμα έτη/μήνες).
 */
'use strict';
const path = require('path');
const fs = require('fs');

const b64u = (s) => Buffer.from(s, 'utf8').toString('base64url');
const unb64u = (s) => Buffer.from(s, 'base64url').toString('utf8');
const resultXml = (xml) => (xml.match(/<result>([\s\S]*?)<\/result>/i) || [])[1] || '';

module.exports = {
  id: 'aade-tax-account',
  title: 'Φορολογικός Λογαριασμός (Μηνιαία Ενημέρωση)',
  portal: 'AADE saadeapps3/MonthlyReport (GSIS OAM)',
  subsystem: 'Hyper.Server',
  actions: ['retrieve', 'download'],
  inputs: [
    { key: 'user', label: 'TAXISnet username', env: 'AADE_USER' },
    { key: 'pass', label: 'TAXISnet password', env: 'AADE_PASS', hidden: true },
    { key: 'year', label: 'Έτος (κενό = πιο πρόσφατο)', env: 'AADE_YEAR', optional: true },
    { key: 'month', label: 'Μήνας 1-12 (κενό = πιο πρόσφατος διαθέσιμος)', env: 'AADE_MONTH', optional: true },
  ],

  async run(http, inp, lib) {
    const L = await lib.aadeLogin(http, inp);
    if (!L.ok) { http.log('AADE LOGIN FAILED: ' + L.reason); return { ok: false, reason: L.reason }; }
    const APP = new URL('/saadeapps3/MonthlyReport', L.AADE).toString();
    const W = APP + '/webresources/monthlyreport';
    const token = async () => {
      const r = await http.api('GET', W + '/getToken', undefined, {});
      try { const o = JSON.parse(r.text); const v = Object.values(o)[0]; return unb64u(String(v)).replace(/"/g, ''); }
      catch (e) { http.log('[taxacc] getToken parse error'); http.dump('taxacc_token.txt', r.text); return ''; }
    };

    await http.follow('GET', APP + '/?#!/arxiki');            // establish app session

    // getuserdata -> afm
    let tok = await token(); if (!tok) return { ok: false, reason: 'NoToken' };
    const ud = await http.apiForm('POST', W + '/getuserdata', {}, { Authorization: tok });
    http.dump('taxacc_userdata.xml', ud.text);
    let userData = {};
    try { userData = JSON.parse(unb64u(resultXml(ud.text))); } catch (e) {}
    const afm = userData.afm || inp.user;
    if (!afm) { http.log('[taxacc] δεν βρέθηκε ΑΦΜ (δες taxacc_userdata.xml)'); return { ok: false, reason: 'NoAfm' }; }

    // getEtos -> διαθέσιμα έτη + μήνες (body = RAW base64url string, Content-Type application/json)
    tok = await token();
    const et = await http.apiForm('POST', W + '/getEtos', b64u(JSON.stringify({ p1: afm, p2: afm })), { Authorization: tok });
    http.dump('taxacc_etos.xml', et.text);
    let list = [];
    try { list = JSON.parse(unb64u(resultXml(et.text)).replace(/"\[/g, '[').replace(/\]"/g, ']').replace(/\\"/g, '"')); } catch (e) { http.log('[taxacc] getEtos parse error'); }
    // availability map: { year: [months...] }
    const avail = {};
    for (const o of list) {
      const y = String(o.etos != null ? o.etos : o.Etos);   // API keys are lowercase (etos, m1..m12)
      const months = [];
      for (let m = 1; m <= 12; m++) { const v = o['m' + m] != null ? o['m' + m] : o['M' + m]; if (String(v) === '1') months.push(m); }
      avail[y] = months;
    }
    const years = Object.keys(avail).sort();
    http.log('[taxacc] ΑΦΜ ' + afm + ' | διαθέσιμα: ' + years.map(y => y + ':[' + avail[y].join(',') + ']').join('  '));

    // choose year + month (default = latest year, its highest available month)
    let year = (inp.year || '').trim();
    if (!year || !avail[year]) year = years[years.length - 1];
    let month = parseInt((inp.month || '').trim(), 10);
    const avMonths = avail[year] || [];
    if (!month || !avMonths.includes(month)) {
      if (inp.month) http.log('[taxacc] μήνας ' + inp.month + ' μη διαθέσιμος για ' + year + ' — διαθέσιμοι: ' + avMonths.join(', '));
      month = avMonths[avMonths.length - 1];
    }
    const result = { portal: this.portal, afm, available: avail, retrievedAt: new Date().toISOString() };
    if (!year || !month || parseInt(year, 10) < 2022) {
      http.log('[taxacc] δεν υπάρχει διαθέσιμος μήνας (>=2022)');
      fs.writeFileSync(path.join(http.dlDir, 'AADE_tax_account_' + afm + '.json'), JSON.stringify(result, null, 2));
      return { ok: false, reason: 'NoMonth', files: ['AADE_tax_account_' + afm + '.json'] };
    }
    result.chosen = { year, month };
    http.log('[taxacc] λήψη για ' + year + '/' + month);

    // getPrintPDF (body = RAW base64url string, Content-Type text/plain == C# PostDataStream "text/plain")
    tok = await token();
    const pr = await http.apiForm('POST', W + '/getPrintPDF',
      b64u(JSON.stringify({ p1: afm, p2: String(year), p3: month })),
      { Authorization: tok, 'Content-Type': 'text/plain; charset=UTF-8', Accept: 'text/plain, */*' });
    const files = [];
    if (pr.buffer) {
      const f = 'FOR_LOGARIASMOS_' + afm + '_' + year + '_' + month + '.pdf';
      fs.writeFileSync(path.join(http.dlDir, f), pr.buffer); files.push(f);
      result.pdf = { pdf: f, bytes: pr.buffer.length };
      http.log('[taxacc] ✅ PDF -> ' + f + ' (' + pr.buffer.length + ' b)');
    } else {
      result.pdf = 'NoPDF'; http.dump('taxacc_printpdf.txt', pr.text);
      http.log('[taxacc] δεν επέστρεψε PDF (ct=' + pr.ct + ', δες taxacc_printpdf.txt)');
    }
    const jf = 'AADE_tax_account_' + afm + '.json';
    fs.writeFileSync(path.join(http.dlDir, jf), JSON.stringify(result, null, 2));
    return { ok: files.length > 0, files: [jf, ...files] };
  },
};
