/*
 * configs/efka-employer-card-ssp.js — Οικονομική καρτέλα εργοδότη ΕΦΚΑ
 * («Κινήσεις τρεχουσών εισφορών») από τη ΝΕΑ υπηρεσία e-ΕΦΚΑ, καθαρό HTTP.
 * =============================================================================
 * App-owned (from taxis_rest_api/engine/server-configs, same contract).
 * Replaces the EFKA branch of efka-employer-card.js, whose URL
 * (apps.e-efka.gov.gr/eEmployerTransactions/secure/transactionsReport.xhtml)
 * answers 404 since the service moved to
 *   https://services.e-efka.gov.gr/ssp.efka.emp-transactions-ext/secure/transactions.xhtml
 * Flow (verified live Oct 2026; same steps as ScanmyData kartela_ergodoti.py,
 * which does them in Playwright):
 *   1) lib.efkaServicesLogin(isLegal) — «Είσοδος Εργοδότη» → TAXISnet of the
 *      business → role external-employer with ΑΦΜ + ΑΜΕ
 *   2) GET transactions.xhtml (Keycloak SSO, same realm)
 *   3) AJAX «Εκτύπωση Κινήσεων» on transactionsForm with dateFrom/dateTo
 *   4) load the dynamic PrintTransactionsDialog (…_contentLoad=true)
 *   5) POST PrintTransactionsForm with its «Εκτύπωση» button → application/pdf
 * Component ids (j_idtNNN) are generated, so buttons are found by title.
 *
 * INPUTS: TAXISnet user/pass of the employer, ΑΦΜ, ΑΜΕ (10 digits), year.
 * OUTPUT: KARTELA_ERGODOTI_EFKA_<afm>_<year>.pdf
 */
'use strict';
const path = require('path');
const fs = require('fs');

const TX = 'https://services.e-efka.gov.gr/ssp.efka.emp-transactions-ext/secure/transactions.xhtml';
const AJAX = { 'Faces-Request': 'partial/ajax', 'X-Requested-With': 'XMLHttpRequest' };

function attr(tag, name) {
  const m = tag.match(new RegExp('\\b' + name + '="([^"]*)"', 'i'));
  return m ? m[1] : '';
}

// Fields (inputs + selected options) and buttons of the form with id `formId`.
function formOf(html, formId, baseDecode) {
  // Titles may come as numeric entities (&#917;…) as well as plain UTF-8.
  const decode = (s) => baseDecode(String(s || '')).replace(/&#(\d+);/g, (_, n) => String.fromCharCode(Number(n)))
    .replace(/&#x([0-9a-f]+);/gi, (_, n) => String.fromCharCode(parseInt(n, 16)));
  const at = html.indexOf('id="' + formId + '"');
  if (at < 0) return null;
  const start = html.lastIndexOf('<form', at);
  const end = html.indexOf('</form>', at);
  const block = html.slice(start, end < 0 ? undefined : end);
  const fields = {};
  for (const m of block.matchAll(/<input\b[^>]*>/gi)) {
    const name = attr(m[0], 'name');
    if (!name) continue;
    const type = (attr(m[0], 'type') || 'text').toLowerCase();
    if ((type === 'checkbox' || type === 'radio') && !/\bchecked\b/i.test(m[0])) continue;
    fields[name] = decode(attr(m[0], 'value'));
  }
  for (const m of block.matchAll(/<select\b[^>]*name="([^"]*)"[^>]*>([\s\S]*?)<\/select>/gi)) {
    const opt = m[2].match(/<option\b[^>]*\bselected\b[^>]*>/i);
    fields[m[1]] = opt ? decode(attr(opt[0], 'value')) : '';
  }
  const buttons = [...block.matchAll(/<button\b[^>]*>/gi)].map((b) => ({ name: attr(b[0], 'name'), title: decode(attr(b[0], 'title')) }));
  return { action: decode(attr(block.match(/<form\b[^>]*>/i)[0], 'action')), fields, buttons };
}

const ddmmyyyy = (d) => [d.getDate(), d.getMonth() + 1, d.getFullYear()].map((n, i) => (i < 2 ? String(n).padStart(2, '0') : String(n))).join('/');

module.exports = {
  id: 'efka-employer-card-ssp',
  title: 'Οικονομική καρτέλα εργοδότη ΕΦΚΑ (νέα υπηρεσία, κινήσεις τρεχουσών εισφορών)',
  portal: 'e-EFKA services.e-efka.gov.gr ssp.efka.emp-transactions-ext',
  subsystem: 'server',
  actions: ['download'],
  inputs: [
    { key: 'user', label: 'TAXISnet username (εργοδότη)', env: 'AADE_USER' },
    { key: 'pass', label: 'TAXISnet password', env: 'AADE_PASS', hidden: true },
    { key: 'afm', label: 'ΑΦΜ εργοδότη', env: 'EFKA_AFM' },
    { key: 'ame', label: 'Αρ. Μητρώου Εργοδότη (10 ψηφία)', env: 'EFKA_AME' },
    { key: 'year', label: 'Έτος', env: 'EFKA_YEAR' },
  ],
  formOf, // exported for tests

  async run(http, inp, lib) {
    const Y = parseInt(String(inp.year || '').trim(), 10);
    if (!Y) return { ok: false, reason: 'NoYear' };
    if (!/^\d{10}$/.test(String(inp.ame || '').trim())) return { ok: false, reason: 'NoAme' };
    const L = await lib.efkaServicesLogin(http, { user: inp.user, pass: inp.pass, afm: inp.afm, ame: String(inp.ame).trim(), isLegal: true });
    if (!L.ok) { http.log('[efka-card] LOGIN FAILED: ' + L.reason); return { ok: false, reason: L.reason }; }

    const page = await http.follow('GET', TX);
    http.dump('efka_card_tx.html', page.text);
    const tx = formOf(page.text, 'transactionsForm', lib.decodeHtml);
    const launcher = tx && tx.buttons.find((b) => b.title === 'Εκτύπωση Κινήσεων');
    const dialogId = (page.text.match(/PrimeFaces\.cw\("Dialog","PrintTransactionsDialog",\{id:"([^"]+)"/) || [])[1];
    if (!tx || !launcher || !dialogId) { http.log('[efka-card] transactions page changed'); return { ok: false, reason: 'NoPrintForm' }; }
    const action = new URL(tx.action || TX, TX).toString();
    const today = new Date();
    const to = Y === today.getFullYear() ? ddmmyyyy(today) : '31/12/' + Y;

    // 3) «Εκτύπωση Κινήσεων»: the server keeps the period for the dialog.
    await http.follow('POST', action, {
      ...tx.fields, dateFrom_input: '01/01/' + Y, dateTo_input: to,
      'jakarta.faces.partial.ajax': 'true', 'jakarta.faces.source': launcher.name,
      'jakarta.faces.partial.execute': '@all', [launcher.name]: launcher.name,
    }, AJAX);

    // 4) dynamic dialog content
    const vs = tx.fields['jakarta.faces.ViewState'];
    const dlg = await http.follow('POST', action, {
      'jakarta.faces.partial.ajax': 'true', 'jakarta.faces.source': dialogId, 'jakarta.faces.partial.execute': dialogId,
      'jakarta.faces.partial.render': dialogId, [dialogId]: dialogId, [dialogId + '_contentLoad']: 'true', 'jakarta.faces.ViewState': vs,
    }, AJAX);
    const content = lib.extractUpdate(dlg.text, dialogId);
    const printForm = formOf(content, 'PrintTransactionsForm', lib.decodeHtml);
    const button = printForm && printForm.buttons.find((b) => b.title === 'Εκτύπωση');
    if (!printForm || !button) { http.dump('efka_card_dialog.html', content); return { ok: false, reason: 'NoPrintForm' }; }
    const newVs = (dlg.text.match(/<update id="[^"]*ViewState[^"]*"><!\[CDATA\[([^\]]*)\]\]>/) || [])[1] || vs;

    // 5) the PDF
    const pdf = await http.postForPdf(new URL(printForm.action || action, TX).toString(), { ...printForm.fields, 'jakarta.faces.ViewState': newVs, [button.name]: button.name });
    if (!pdf) return { ok: false, reason: 'NoPDF' };
    const f = 'KARTELA_ERGODOTI_EFKA_' + String(inp.afm || '').replace(/\D/g, '') + '_' + Y + '.pdf';
    fs.writeFileSync(path.join(http.dlDir, f), pdf);
    http.log('[efka-card] ✅ ' + f + ' (' + pdf.length + ' b)');
    return { ok: true, files: [f] };
  },
};
