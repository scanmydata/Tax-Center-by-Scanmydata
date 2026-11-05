/*
 * configs/efka-employer-card.js  --  Οικονομική Καρτέλα Εργοδότη (e-ΕΦΚΑ, ΝΕΟ ΟΠΣ)
 * =============================================================================
 * Το ΠΑΛΙΟ app `apps.e-efka.gov.gr/eEmployerTransactions` (κωδικοί ΙΚΑ) ΑΠΟΣΥΡΘΗΚΕ (404).
 * Η καρτέλα μετακόμισε στο ΝΕΟ ΟΠΣ: `services.e-efka.gov.gr/ssp.efka.emp-transactions-ext`,
 * login με **TAXISnet + ΑΜΕ** (role external-employer), KeyCloak eEfkaExtAuth.
 * (σελίδα e-ΕΦΚΑ: /el/elektronikes-yperesies/oikonomike-kartole-ergodote)
 *
 * ΠΛΗΡΩΣ LIVE-VERIFIED end-to-end (καθαρό HTTP, χωρίς browser):
 *   1) OIDC: GET app → «Είσοδος Εργοδότη» (broker taxisnet-employer) → GSIS (j_spring + approval)
 *      → KeyCloak post-broker-login: role=external-employer + afm + ame → app landing.
 *   2) GET secure/transactions.xhtml → ViewState + transactionsForm (dateFrom/dateTo).
 *   3) «Εμφάνιση» (j_idt135) partial-ajax με date range → datatable `linesTable`
 *      (Ημ/νία · Είδος Κίνησης · Αρ.Παραστατικού · Περίοδος · Χρέωση · Πίστωση · Υπόλοιπο) + pagination.
 *   4) «Εκτύπωση Κινήσεων» (j_idt137) ajax → ανοίγει PrintTransactionsDialog.
 *   5) PDF: POST {PrintTransactionsForm, PrintTransactionsForm:j_idt209:'', ViewState} → application/pdf.
 *
 * INPUTS: TAXISnet user/pass + ΑΦΜ εργοδότη + ΑΜΕ + (προαιρ.) Από/Έως (dd/MM/yyyy).
 * OUTPUT: KARTELA_ERGODOTI_<ΑΦΜ>.pdf + EFKA_employer_card_<ΑΦΜ>.json (όλες οι κινήσεις).
 * Βλ. out/methodologies/EFKA_employer_card.md.
 */
'use strict';
const path = require('path');
const fs = require('fs');

const SVC = 'https://services.e-efka.gov.gr/';
const APP = SVC + 'ssp.efka.emp-transactions-ext/';
const PAGE = APP + 'secure/transactions.xhtml';
const AJAX = { 'Faces-Request': 'partial/ajax', 'X-Requested-With': 'XMLHttpRequest' };

// ViewState from a full page OR a partial-response
const vsOf = (s) => (s.match(/ViewState[^>]*><!\[CDATA\[([^\]]+)\]\]/i)
  || s.match(/name="jakarta\.faces\.ViewState"[^>]*value="([^"]*)"/i)
  || s.match(/value="([^"]*)"[^>]*name="jakarta\.faces\.ViewState"/i) || [])[1] || '';

// collect name->value of all plain inputs/selects inside a <form id=...>
function formFields(html, formId, decodeHtml) {
  const start = html.search(new RegExp('<form[^>]*id="' + formId.replace(/[$:.]/g, '\\$&') + '"', 'i'));
  if (start < 0) return {};
  const block = html.slice(start, (html.indexOf('</form>', start) + 7) || html.length);
  const f = {};
  for (const m of block.matchAll(/<input\b[^>]*>/gi)) {
    const name = (m[0].match(/name="([^"]*)"/i) || [])[1]; if (!name) continue;
    const type = ((m[0].match(/type="([^"]*)"/i) || [])[1] || 'text').toLowerCase();
    if (type === 'submit' || type === 'button') continue;
    if ((type === 'checkbox' || type === 'radio') && !/\bchecked\b/i.test(m[0])) continue;
    f[name] = decodeHtml((m[0].match(/value="([^"]*)"/i) || [])[1] || '');
  }
  for (const s of block.matchAll(/<select\b[^>]*name="([^"]*)"[^>]*>([\s\S]*?)<\/select>/gi)) {
    const sel = (s[2].match(/<option[^>]*value="([^"]*)"[^>]*selected/i) || [])[1];
    f[s[1]] = decodeHtml(sel != null ? sel : ((s[2].match(/<option[^>]*value="([^"]*)"/i) || [])[1] || ''));
  }
  return f;
}

// parse linesTable rows out of a transactionsForm partial-response (or full html)
function parseRows(html, lib) {
  const body = lib.extractUpdate(html, 'transactionsForm') || html;
  const out = [];
  for (const tr of (body.match(/<tr\b[^>]*data-ri="\d+"[\s\S]*?<\/tr>/gi) || [])) {
    const cells = [...tr.matchAll(/<td\b[^>]*>([\s\S]*?)<\/td>/gi)].map(c => lib.stripTags(c[1]));
    if (!cells.length) continue;
    out.push({
      date: cells[0] || '', kind: cells[1] || '', docNo: cells[2] || '', period: cells[3] || '',
      charge: cells[4] || '', credit: cells[5] || '', balance: cells[6] || '',
    });
  }
  return out;
}

module.exports = {
  id: 'efka-employer-card',
  title: 'Οικονομική Καρτέλα Εργοδότη (e-ΕΦΚΑ ΟΠΣ)',
  portal: 'services.e-efka.gov.gr/ssp.efka.emp-transactions-ext (TAXISnet + ΑΜΕ)',
  subsystem: 'Hyper.Server (νέο ΟΠΣ e-ΕΦΚΑ)',
  actions: ['retrieve', 'download'],
  inputs: [
    { key: 'user', label: 'TAXISnet username', env: 'EFKA_USER' },
    { key: 'pass', label: 'TAXISnet password', env: 'EFKA_PASS', hidden: true },
    { key: 'afm', label: 'ΑΦΜ εργοδότη', env: 'EFKA_AFM' },
    { key: 'ame', label: 'ΑΜΕ (Αρ. Μητρώου Εργοδότη)', env: 'EFKA_AME' },
    { key: 'from', label: 'Από (dd/MM/yyyy, κενό = 01/01/2022)', env: 'EFKA_FROM', optional: true },
    { key: 'to', label: 'Έως (dd/MM/yyyy, κενό = σήμερα)', env: 'EFKA_TO', optional: true },
  ],

  async run(http, inp, lib) {
    const { decodeHtml, stripTags, gsisSubmitAndApprove, ownFormAction } = lib;
    const afm = String(inp.afm || '').trim();
    const ame = String(inp.ame || '').trim();
    const FROM = (inp.from || '01/01/2022').trim();
    const TO = (inp.to || new Date().toLocaleDateString('el-GR').replace(/\./g, '/')).trim();
    if (!inp.user || !inp.pass || !afm || !ame) { http.log('[emp-card] Χρειάζονται user + pass + ΑΦΜ + ΑΜΕ'); return { ok: false, reason: 'InvalidInput' }; }

    // ---- 1) LOGIN (OIDC KeyCloak: employer broker -> GSIS -> role external-employer + afm + ame) ----
    http.log('[emp-card] login (TAXISnet εργοδότη + ΑΜΕ ' + ame + ')');
    let r = await http.follow('GET', APP);
    const bm = r.text.match(/id="social-taxisnet-employer"[^>]*href="([^"]*)"/i) || r.text.match(/href="([^"]*)"[^>]*id="social-taxisnet-employer"/i);
    if (!bm) return { ok: false, reason: 'NoEmployerBroker' };
    await http.follow('GET', new URL(decodeHtml(bm[1]), APP).toString());
    const g = await gsisSubmitAndApprove(http, inp.user, inp.pass);
    if (!g.ok) { http.log('[emp-card] GSIS login failed: ' + g.reason); return { ok: false, reason: g.reason }; }
    const roleAction = decodeHtml(ownFormAction(g.page.text, 'kc-form-select-role'));
    if (!roleAction) return { ok: false, reason: 'NoRoleForm' };
    r = await http.follow('POST', new URL(roleAction, g.page.url).toString(), {
      role: 'external-employer', afm, amka: '', pa: '', ame, amoe: '',
      'authorizing-afm': '', 'authorizing-contractor-afm': '', 'submit-role-attribute': 'Υποβολή',
    });
    http.dump('03_card_landing.html', r.text);
    if (!/Οικονομική Καρτέλα Εργοδότη/.test(r.text)) { http.log('[emp-card] login landing not the card: ' + stripTags(r.text).slice(0, 150)); return { ok: false, reason: 'LoginLanding' }; }
    http.log('[emp-card] ✅ logged in');

    // ---- 2) card page base state ----
    let vs = vsOf(r.text);
    const base = formFields(r.text, 'transactionsForm', decodeHtml);

    // ---- 3) SEARCH (Εμφάνιση j_idt135) with date range + paginate all rows ----
    http.log('[emp-card] Εμφάνιση ' + FROM + ' - ' + TO);
    const searchBody = Object.assign({}, base, {
      'jakarta.faces.partial.ajax': 'true', 'jakarta.faces.source': 'j_idt135',
      'jakarta.faces.partial.execute': 'transactionsForm', 'jakarta.faces.partial.render': 'transactionsForm',
      'j_idt135': 'j_idt135', 'dateFrom_input': FROM, 'dateTo_input': TO, 'jakarta.faces.ViewState': vs,
    });
    let res = await http.once('POST', PAGE, searchBody, AJAX);
    let xml = await res.text(); http.dump('10_search.xml', xml);
    vs = vsOf(xml) || vs;
    const rows = parseRows(xml, lib);
    const pages = +((xml.match(/Σελίδα\s*\d+\s*από\s*(\d+)/i) || [])[1] || 1);
    http.log('[emp-card] σελίδα 1/' + pages + ' (' + rows.length + ' γραμμές)');
    for (let p = 1; p < pages; p++) {
      const pageBody = Object.assign({}, base, {
        'jakarta.faces.partial.ajax': 'true', 'jakarta.faces.source': 'linesTable',
        'jakarta.faces.partial.execute': 'linesTable', 'jakarta.faces.partial.render': 'linesTable',
        'jakarta.faces.behavior.event': 'page', 'jakarta.faces.partial.event': 'page',
        'linesTable_pagination': 'true', 'linesTable_first': String(p * 10), 'linesTable_rows': '10',
        'linesTable_encodeFeature': 'true', 'transactionsForm': 'transactionsForm',
        'dateFrom_input': FROM, 'dateTo_input': TO, 'jakarta.faces.ViewState': vs,
      });
      const pr = await http.once('POST', PAGE, pageBody, AJAX);
      const px = await pr.text(); vs = vsOf(px) || vs;
      const prows = parseRows(px, lib);
      rows.push(...prows);
      http.log('[emp-card] σελίδα ' + (p + 1) + '/' + pages + ' (+' + prows.length + ')');
    }
    http.log('[emp-card] σύνολο κινήσεων: ' + rows.length);

    // ---- 4) PREP print (Εκτύπωση Κινήσεων j_idt137) ----
    const prep = await http.once('POST', PAGE, Object.assign({}, base, {
      'jakarta.faces.partial.ajax': 'true', 'jakarta.faces.source': 'j_idt137',
      'jakarta.faces.partial.execute': 'transactionsForm', 'j_idt137': 'j_idt137',
      'dateFrom_input': FROM, 'dateTo_input': TO, 'jakarta.faces.ViewState': vs,
    }), AJAX);
    vs = vsOf(await prep.text()) || vs;

    // ---- 5) PDF: POST PrintTransactionsForm (j_idt209 = empty) ----
    const pdfRes = await http.once('POST', PAGE, {
      'PrintTransactionsForm': 'PrintTransactionsForm', 'PrintTransactionsForm:j_idt209': '', 'jakarta.faces.ViewState': vs,
    });
    const pdf = Buffer.from(await pdfRes.arrayBuffer());
    const files = [];
    if (pdf.slice(0, 4).toString() === '%PDF') {
      const pf = 'KARTELA_ERGODOTI_' + afm + '.pdf';
      fs.writeFileSync(path.join(http.dlDir, pf), pdf); files.push(pf);
      http.log('[emp-card] ✅ PDF -> ' + pf + ' (' + pdf.length + ' b)');
    } else {
      http.log('[emp-card] ⚠ δεν επιστράφηκε PDF (ct=' + (pdfRes.headers.get('content-type') || '') + ')');
    }

    const out = {
      portal: this.portal, afm, ame, from: FROM, to: TO, retrievedAt: new Date().toISOString(),
      count: rows.length, transactions: rows,
    };
    const jf = 'EFKA_employer_card_' + afm + '.json';
    fs.writeFileSync(path.join(http.dlDir, jf), JSON.stringify(out, null, 2)); files.push(jf);
    http.log('[emp-card] ✅ ' + rows.length + ' κινήσεις -> ' + jf);
    return { ok: true, files, summary: { ame, count: rows.length, pdf: files.find(f => f.endsWith('.pdf')) || null } };
  },
};
