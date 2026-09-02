/*
 * configs/efka-notices.js  --  Process: "Ειδοποιητήρια Εισφορών ΕΦΚΑ/ΤΕΚΑ" (μη μισθωτοί)
 * Source: Hyper.Server.Tax.dll  Easy_EFKA_SelfEmployed (Login + GetNotice).
 * A process config = { id, title, portal, inputs, run(http, inputs, lib) }.
 */
'use strict';
const path = require('path');
const fs = require('fs');

const FORM_ID = 'form-id:accordion-insurance-obligations-citizen';
const DT = {
  EFKA: FORM_ID + ':notifications-accordion-panel:e-efka-last-notification-citizen-datatable',
  TEKA: FORM_ID + ':notifications-accordion-panel:teka-last-notification-citizen-datatable',
};

module.exports = {
  id: 'efka-notices',
  title: 'Ειδοποιητήρια Εισφορών ΕΦΚΑ/ΤΕΚΑ (μη μισθωτοί)',
  portal: 'e-EFKA (services.e-efka.gov.gr, non-employee)',
  subsystem: 'Hyper.Server',
  actions: ['retrieve', 'download'],
  inputs: [
    { key: 'user', label: 'TAXISnet username', env: 'EFKA_USER' },
    { key: 'pass', label: 'TAXISnet password', env: 'EFKA_PASS', hidden: true },
    { key: 'afm', label: 'ΑΦΜ', env: 'EFKA_AFM' },
    { key: 'amka', label: 'ΑΜΚΑ', env: 'EFKA_AMKA' },
  ],

  async run(http, inp, lib) {
    // 1) login (reusable e-EFKA non-employee KeyCloak flow)
    const L = await lib.efkaNonEmployeeLogin(http, inp);
    if (!L.ok) { http.log('LOGIN FAILED: ' + L.reason); return { ok: false, reason: L.reason }; }
    const ypoUrl = L.links['Υποχρεώσεις Ασφάλισης'];
    if (!ypoUrl) { http.log('No "Υποχρεώσεις Ασφάλισης" link'); return { ok: false, reason: 'NoObligations' }; }

    // 2) confirm obligations page
    const r = await http.follow('GET', ypoUrl); http.dump('04_obligations.html', r.text);
    if (!lib.hasId(r.text, FORM_ID)) { http.log('obligations page missing accordion'); return { ok: false, reason: 'PageError' }; }
    http.log('[obligations] OK');

    // 3) per kind: tabChange -> datatable -> print button -> PDF (+ collect table strings)
    const saved = [];
    const tables = { portal: this.portal, afm: inp.afm, retrievedAt: new Date().toISOString(), sections: {} };
    for (const kind of ['EFKA', 'TEKA']) {
      const res = await this.getNotice(http, lib, ypoUrl, kind, inp.afm, tables);
      if (res) saved.push(res);
    }
    // every runner also emits a JSON of the targeted table strings (for future use)
    const jf = path.join(http.dlDir, 'EFKA_notices_' + inp.afm + '_table.json');
    fs.writeFileSync(jf, JSON.stringify(tables, null, 2));
    saved.push(path.basename(jf));
    http.log(saved.length ? ('DONE — ' + saved.join(', ')) : 'DONE — no current notices.');
    return { ok: true, files: saved };
  },

  async getNotice(http, lib, ypoUrl, kind, afm, tables) {
    http.log('[notice:' + kind + '] GET obligations (fresh ViewState)');
    const page = (await http.follow('GET', ypoUrl)).text;
    const vs = lib.viewState(page);
    const tab = lib.findTabByText(page, 'Τρέχουσες Οφειλές');
    if (!vs || !tab) { http.log('[notice:' + kind + '] no ViewState/tab'); return null; }
    const newTab = tab.href.replace(/^#/, '');
    http.log('[notice:' + kind + '] tabChange (dataIndex=' + tab.dataIndex + ')');
    const tabResp = await http.follow('POST', ypoUrl, {
      'jakarta.faces.partial.ajax': 'true', 'jakarta.faces.source': FORM_ID,
      'jakarta.faces.partial.execute': FORM_ID, 'jakarta.faces.partial.render': FORM_ID,
      'jakarta.faces.behavior.event': 'tabChange', 'jakarta.faces.partial.event': 'tabChange',
      [FORM_ID + '_contentLoad']: 'true', [FORM_ID + '_newTab']: newTab,
      [FORM_ID + '_tabindex']: tab.dataIndex, 'form-id': 'form-id',
      [FORM_ID + '_activeIndex']: tab.dataIndex, 'jakarta.faces.ViewState': vs,
    });
    http.dump('06_notifications_' + kind + '.html', tabResp.text);
    const upd = lib.extractUpdate(tabResp.text, FORM_ID);
    const rows = lib.dataTableRows(upd, DT[kind]);
    // record the table strings (all rows, cells as text) into the JSON collector
    if (tables) {
      const dtIdx = upd.indexOf('id="' + DT[kind] + '"');
      const headers = dtIdx >= 0 ? [...upd.slice(dtIdx).matchAll(/<th\b[^>]*>([\s\S]*?)<\/th>/gi)].map(t => lib.stripTags(t[1])).filter(Boolean).slice(0, 20) : [];
      tables.sections[kind] = { headers, rows: (rows || []).map(r => r.map(c => lib.stripTags(c))) };
    }
    if (!rows) { http.log('[notice:' + kind + '] no ' + kind + ' notice (datatable absent)'); return null; }
    if (rows.length === 0) { http.log('[notice:' + kind + '] datatable empty'); return null; }
    const tds = rows[0];
    const td4 = tds[4] || '';
    const bm = td4.match(/<button\b([^>]*)>([\s\S]*?)<\/button>/i);
    if (!bm) { http.log('[notice:' + kind + '] no print button; cells: ' + tds.map(lib.stripTags).join(' | ')); return null; }
    const btnId = (bm[1].match(/id="([^"]*)"/i) || [])[1] || '';
    http.log('[notice:' + kind + '] print button id=' + btnId + ' text="' + lib.stripTags(bm[2]) + '"');
    if (!btnId) return null;
    const pdf = await http.postForPdf(ypoUrl, {
      'form-id': 'form-id', [btnId]: '', [FORM_ID + '_activeIndex']: tab.dataIndex, 'jakarta.faces.ViewState': vs,
    });
    if (!pdf) { http.log('[notice:' + kind + '] no PDF returned'); return null; }
    // naming == C# MetaData: AFM # cell1 cell2
    const meta = (afm + '_' + lib.stripTags(tds[1] || '') + '_' + lib.stripTags(tds[2] || '')).replace(/[^A-Za-z0-9_\-]/g, '_').slice(0, 60);
    const dest = path.join(http.dlDir, 'EFKA_' + kind + '_' + (meta || Date.now()) + '.pdf');
    fs.writeFileSync(dest, pdf);
    http.log('[notice:' + kind + '] ✅ PDF SAVED -> ' + dest + ' (' + pdf.length + ' bytes)');
    return path.basename(dest);
  },
};
