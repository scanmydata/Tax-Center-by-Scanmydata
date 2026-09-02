/*
 * configs/efka-teka-certificate.js  --  Process: Φορολογικές Βεβαιώσεις ΕΦΚΑ / ΤΕΚΑ (φορολογικής χρήσης)
 * Source: Hyper.Server.Tax.dll  Easy_EFKA_SelfEmployed.GetDikaiomataAsfalisis (decompiled ~61250).
 * Kinds: Efka_Vev ("Φορολογικές Βεβαιώσεις") + Efka_Teka_Vev ("Φορολογικές Βεβαιώσεις TEKA").
 *
 * FAITHFUL flow (same proven login as efka-notices — services.e-efka.gov.gr non-employee):
 *   login -> landing link "Δικαιώματα Ασφάλισης" (_dikaiomataAsfalisisURl)
 *   GET it -> FormNode #form-id:accordion-insurance-royalties-citizen + jakarta.faces.ViewState
 *   tab <li class="ui-tabs-header ..."> text "Φορολογικές Βεβαιώσεις"[/" TEKA"] -> href, data-index
 *   JSF tabChange POST (adds application-type-selector_input=registration, reg-apps-reg_activeIndex=1)
 *     -> partial-response <update id=FormNode> -> rows tr.ui-datatable-even/odd
 *        td[0]=έτος, td[8]/div/button = print button (id)
 *   per έτος: POST {form-id, application-type-selector_input=registration, <buttonId>='',
 *             reg-apps-reg_activeIndex=1, FormNode_activeIndex=data-index, ViewState} -> PDF (PostDataStream)
 *   MetaData filename = ΑΦΜ # έτος.
 *
 * INPUTS: TAXISnet user/pass + ΑΦΜ + ΑΜΚΑ. Optional έτος (κενό = όλα τα διαθέσιμα έτη του πίνακα).
 */
'use strict';
const path = require('path');
const fs = require('fs');

const FORM_ID = 'form-id:accordion-insurance-royalties-citizen';
const TAB_LABEL = { EFKA: 'Φορολογικές Βεβαιώσεις', TEKA: 'Φορολογικές Βεβαιώσεις TEKA' };

module.exports = {
  id: 'efka-teka-certificate',
  title: 'Φορολογικές Βεβαιώσεις ΕΦΚΑ/ΤΕΚΑ (φορολογικής χρήσης)',
  portal: 'e-EFKA (services.e-efka.gov.gr, non-employee)',
  subsystem: 'Hyper.Server',
  actions: ['retrieve', 'download'],
  inputs: [
    { key: 'user', label: 'TAXISnet username', env: 'EFKA_USER' },
    { key: 'pass', label: 'TAXISnet password', env: 'EFKA_PASS', hidden: true },
    { key: 'afm', label: 'ΑΦΜ', env: 'EFKA_AFM' },
    { key: 'amka', label: 'ΑΜΚΑ', env: 'EFKA_AMKA' },
    { key: 'year', label: 'Έτος (κενό = όλα)', env: 'EFKA_YEAR' },
  ],

  async run(http, inp, lib) {
    const L = await lib.efkaNonEmployeeLogin(http, inp);
    if (!L.ok) { http.log('LOGIN FAILED: ' + L.reason); return { ok: false, reason: L.reason }; }
    // "Δικαιώματα Ασφάλισης" == insuranceRoyalties.xhtml (accordion-insurance-royalties-citizen).
    // The landing menu uses nested spans, so resolve robustly: menu text -> href in landing -> known path.
    const ROY_PATH = '/ssp.efka.non.employees/views/insuranceRoyalties.xhtml';
    let url = L.links['Δικαιώματα Ασφάλισης'];
    if (!url) {
      const m = (L.landing || '').match(/href="([^"]*insuranceRoyalties\.xhtml[^"]*)"/i);
      url = m ? new URL(lib.decodeHtml(m[1]), L.SVC).toString() : new URL(ROY_PATH, L.SVC).toString();
    }
    http.log('[cert] royalties URL = ' + url);

    const saved = [];
    for (const kind of ['EFKA', 'TEKA']) {
      try { const s = await this.getCertificates(http, lib, url, kind, inp); saved.push(...s); }
      catch (e) { http.log('[cert:' + kind + '] error ' + (e && e.message ? e.message : e)); }
    }
    http.log(saved.length ? ('DONE — saved ' + saved.length + ' PDF(s): ' + saved.join(', ')) : 'DONE — no certificates saved.');
    return { ok: saved.length > 0, files: saved };
  },

  async getCertificates(http, lib, url, kind, inp) {
    http.log('[cert:' + kind + '] GET Δικαιώματα Ασφάλισης');
    const page = (await http.follow('GET', url)).text;
    if (!lib.hasId(page, FORM_ID)) { http.log('[cert:' + kind + '] page missing accordion'); return []; }
    const vs = lib.viewState(page);
    const tab = lib.findTabByText(page, TAB_LABEL[kind]);
    if (!vs || !tab || !tab.href) { http.log('[cert:' + kind + '] no ViewState/tab "' + TAB_LABEL[kind] + '"'); return []; }
    const newTab = tab.href.replace(/^#/, '');
    if (!newTab.startsWith(FORM_ID + ':')) { http.log('[cert:' + kind + '] unexpected tab href'); return []; }

    http.log('[cert:' + kind + '] tabChange (dataIndex=' + tab.dataIndex + ')');
    const tabResp = await http.follow('POST', url, {
      'jakarta.faces.partial.ajax': 'true', 'jakarta.faces.source': FORM_ID,
      'jakarta.faces.partial.execute': FORM_ID, 'jakarta.faces.partial.render': FORM_ID,
      'jakarta.faces.behavior.event': 'tabChange', 'jakarta.faces.partial.event': 'tabChange',
      [FORM_ID + '_contentLoad']: 'true', [FORM_ID + '_newTab']: newTab, [FORM_ID + '_tabindex']: tab.dataIndex,
      'form-id': 'form-id',
      [FORM_ID + ':application-type-selector_input']: 'registration',
      [FORM_ID + ':reg-apps-reg_activeIndex']: '1',
      [FORM_ID + '_activeIndex']: tab.dataIndex,
      'jakarta.faces.ViewState': vs,
    });
    http.dump('06_certificates_' + kind + '.html', tabResp.text);
    const upd = lib.extractUpdate(tabResp.text, FORM_ID);

    // column headers (th) + rows tr.ui-datatable-even/odd
    const headers = [...upd.matchAll(/<th\b[^>]*>([\s\S]*?)<\/th>/gi)].map(t => lib.stripTags(t[1])).filter(Boolean);
    const rawRows = [...upd.matchAll(/<tr\b[^>]*class="[^"]*ui-datatable-(?:even|odd)[^"]*"[^>]*>([\s\S]*?)<\/tr>/gi)]
      .map(r => [...r[1].matchAll(/<td\b[^>]*>([\s\S]*?)<\/td>/gi)].map(t => t[1]));
    if (!rawRows.length) { http.log('[cert:' + kind + '] no rows (no certificates)'); return []; }

    // Build the table (the per-year totals that are ALSO inside each PDF) -> JSON.
    // C#: year = td[0], print button = ./td[8]/div/button  (XPath 1-indexed => 0-based tds[7]).
    const cellText = (c) => (/<button|PrimeFaces\.cw|<script/i.test(c)) ? '' : lib.stripTags(c); // action column -> ''
    const table = rawRows.map(tds => {
      const cells = tds.map(cellText);
      const row = { year: cells[0] || '', cells };
      if (headers.length === cells.length) headers.forEach((h, i) => { if (h) row[h] = cells[i] || ''; }); // labelled only when aligned
      row._btnId = (tds[7] || '').match(/<button\b[^>]*id="([^"]*)"/i)?.[1]
                || tds.map(c => (c.match(/<button\b[^>]*id="([^"]*)"/i) || [])[1]).find(Boolean) || '';
      return row;
    });

    const out = [];
    // save the whole table as JSON (all years, strings preserved in UTF-8)
    const tblDest = path.join(http.dlDir, 'VEV_' + kind + '_' + inp.afm + '_table.json');
    fs.writeFileSync(tblDest, JSON.stringify({
      portal: this.portal, kind, afm: inp.afm, amka: inp.amka, retrievedAt: new Date().toISOString(),
      headers, rows: table.map(({ _btnId, ...r }) => r),
    }, null, 2));
    http.log('[cert:' + kind + '] 🗎 table saved (' + table.length + ' έτη) -> ' + path.basename(tblDest));
    out.push(path.basename(tblDest));

    // download the PDF(s) for the requested year (blank = all years)
    const wantYear = (inp.year || '').trim();
    for (const row of table) {
      if (wantYear && row.year !== wantYear) continue;
      if (!row._btnId) { http.log('[cert:' + kind + ' ' + row.year + '] no print button'); continue; }
      http.log('[cert:' + kind + ' ' + row.year + '] print button id=' + row._btnId);
      const pdf = await http.postForPdf(url, {
        'form-id': 'form-id',
        [FORM_ID + ':application-type-selector_input']: 'registration',
        [row._btnId]: '',
        [FORM_ID + ':reg-apps-reg_activeIndex']: '1',
        [FORM_ID + '_activeIndex']: tab.dataIndex,
        'jakarta.faces.ViewState': vs,
      });
      if (!pdf) { http.log('[cert:' + kind + ' ' + row.year + '] no PDF'); continue; }
      const dest = path.join(http.dlDir, 'VEV_' + kind + '_' + inp.afm + '_' + row.year + '.pdf'); // MetaData == ΑΦΜ#έτος
      fs.writeFileSync(dest, pdf);
      http.log('[cert:' + kind + ' ' + row.year + '] ✅ PDF SAVED -> ' + path.basename(dest) + ' (' + pdf.length + ' bytes)');
      out.push(path.basename(dest));
    }
    if (wantYear && !out.some(f => f.endsWith(wantYear + '.pdf'))) http.log('[cert:' + kind + '] έτος ' + wantYear + ' — διαθέσιμα: ' + table.map(r => r.year).join(', '));
    return out;
  },
};
