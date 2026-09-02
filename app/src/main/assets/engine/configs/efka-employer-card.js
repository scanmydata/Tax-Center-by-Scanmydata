/*
 * configs/efka-employer-card.js  --  Οικονομική Καρτέλα Εργοδότη ΕΦΚΑ + ΤΕΚΑ
 * Login: Easy_EFKA_Erg (apps.e-efka.gov.gr eAccess/j_security_check, κωδικοί ΙΚΑ εργοδότη). ✅ login live-confirmed.
 * Apps (from the eAccess menu + the reference Playwright extractor):
 *   ΕΦΚΑ:  https://apps.e-efka.gov.gr/eEmployerTransactions/secure/transactionsReport.xhtml
 *   ΤΕΚΑ:  https://apps.e-efka.gov.gr/eTekaEmployerTransactions/secure/transactionsReport.xhtml?mode=default
 * Each report page is a PrimeFaces form with a year SelectOneMenu (name endsWith 'year_input') and an
 * «Εκτύπωση» submit button. Submitting the whole form (all fields + year + button name) returns the PDF
 * (application/pdf or octet-stream/%PDF). We POST it directly over the logged-in session (no browser).
 *
 * INPUTS: κωδικοί ΙΚΑ εργοδότη (user/pass) + Έτος (προαιρετικό) + ποιες (EFKA,TEKA — default και τα δύο).
 */
'use strict';
const path = require('path');
const fs = require('fs');

const APPS = {
  EFKA: { label: 'ΕΦΚΑ', url: 'https://apps.e-efka.gov.gr/eEmployerTransactions/secure/transactionsReport.xhtml' },
  TEKA: { label: 'ΤΕΚΑ', url: 'https://apps.e-efka.gov.gr/eTekaEmployerTransactions/secure/transactionsReport.xhtml?mode=default' },
};

// extract the <form> that holds the «Εκτύπωση» button, with all its fields (inputs + selects + button)
function printForm(html) {
  for (const f of html.matchAll(/<form\b[^>]*>[\s\S]*?<\/form>/gi)) {
    const block = f[0];
    if (!/title="Εκτύπωση"/i.test(block)) continue;
    const action = (block.match(/<form\b[^>]*action="([^"]*)"/i) || [])[1] || '';
    const fields = {};
    for (const m of block.matchAll(/<input\b[^>]*>/gi)) {
      const name = (m[0].match(/name="([^"]*)"/i) || [])[1]; if (!name) continue;
      const type = ((m[0].match(/type="([^"]*)"/i) || [])[1] || 'text').toLowerCase();
      if ((type === 'checkbox' || type === 'radio') && !/\bchecked\b/i.test(m[0])) continue;
      if (type === 'submit' || type === 'button') continue; // buttons handled below
      fields[name] = (m[0].match(/value="([^"]*)"/i) || [])[1] || '';
    }
    for (const s of block.matchAll(/<select\b[^>]*name="([^"]*)"[^>]*>([\s\S]*?)<\/select>/gi)) {
      const opts = [...s[2].matchAll(/<option[^>]*value="([^"]*)"[^>]*>/gi)];
      const selected = (s[2].match(/<option[^>]*value="([^"]*)"[^>]*selected/i) || [])[1];
      fields[s[1]] = selected != null ? selected : (opts[0] ? opts[0][1] : '');
    }
    const btn = block.match(/<(?:button|input)\b[^>]*title="Εκτύπωση"[^>]*>/i);
    const btnName = btn ? (btn[0].match(/name="([^"]*)"/i) || [])[1] : '';
    if (btnName) fields[btnName] = (btn[0].match(/value="([^"]*)"/i) || [])[1] || '';
    const yearKey = Object.keys(fields).find(k => /year_input$/i.test(k));
    return { action, fields, yearKey };
  }
  return null;
}

module.exports = {
  id: 'efka-employer-card',
  title: 'Οικονομική Καρτέλα Εργοδότη ΕΦΚΑ + ΤΕΚΑ',
  portal: 'e-EFKA εργοδότη (apps.e-efka.gov.gr, κωδικοί ΙΚΑ)',
  subsystem: 'Hyper.Server',
  actions: ['retrieve', 'download'],
  inputs: [
    { key: 'user', label: 'Κωδικός ΙΚΑ εργοδότη (username)', env: 'IKA_USER' },
    { key: 'pass', label: 'Κωδικός ΙΚΑ εργοδότη (password)', env: 'IKA_PASS', hidden: true },
    { key: 'year', label: 'Έτος (κενό = προεπιλογή)', env: 'IKA_YEAR' },
    { key: 'which', label: 'Ποιες (EFKA,TEKA — κενό = και οι δύο)', env: 'IKA_WHICH' },
  ],

  async run(http, inp, lib) {
    const L = await lib.efkaErgodLogin(http, inp);
    if (!L.ok) { http.log('EFKA-ERG LOGIN FAILED: ' + L.reason); return { ok: false, reason: L.reason }; }

    const which = (inp.which || '').toUpperCase().split(/[,\s]+/).filter(k => APPS[k]);
    const want = which.length ? which : ['EFKA', 'TEKA'];
    const year = (inp.year || '').trim();
    const result = { portal: this.portal, user: inp.user, year, retrievedAt: new Date().toISOString(), cards: {} };
    const pdfs = [];

    for (const k of want) {
      const A = APPS[k];
      try {
        const rep = await http.follow('GET', A.url);
        http.dump('report_' + k + '.html', rep.text);
        const pf = printForm(rep.text);
        if (!pf) { result.cards[k] = { label: A.label, status: 'NoPrintForm (δες report_' + k + '.html)' }; http.log('[' + k + '] δεν βρέθηκε φόρμα «Εκτύπωση»'); continue; }
        if (year && pf.yearKey) pf.fields[pf.yearKey] = year; // set requested έτος
        const action = new URL(pf.action || A.url, rep.url).toString();
        http.log('[' + k + '] POST print form (' + Object.keys(pf.fields).length + ' fields' + (pf.yearKey ? ', έτος=' + pf.fields[pf.yearKey] : '') + ')');
        const pdf = await http.postForPdf(action, pf.fields);
        if (!pdf) { result.cards[k] = { label: A.label, status: 'NoPDF' }; http.log('[' + k + '] δεν επέστρεψε PDF'); continue; }
        const f = 'KARTELA_ERGODOTI_' + k + '_' + inp.user + (year ? '_' + year : '') + '.pdf';
        fs.writeFileSync(path.join(http.dlDir, f), pdf); pdfs.push(f);
        result.cards[k] = { label: A.label, pdf: f, bytes: pdf.length };
        http.log('[' + k + '] ✅ PDF -> ' + f + ' (' + pdf.length + ' b)');
      } catch (e) { result.cards[k] = { label: A.label, error: String(e && e.message || e) }; http.log('[' + k + '] error ' + (e && e.message ? e.message : e)); }
    }

    const jf = path.join(http.dlDir, 'EFKA_employer_card_' + inp.user + '.json');
    fs.writeFileSync(jf, JSON.stringify(result, null, 2));
    http.log('[efka-employer-card] ✅ saved -> ' + path.basename(jf) + ' (' + pdfs.length + ' PDF)');
    return { ok: true, files: [path.basename(jf), ...pdfs] };
  },
};
