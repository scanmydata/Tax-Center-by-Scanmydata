/*
 * configs/akeed-edoeap.js  --  Process: Еfarmob EDOEAP e ΑKEED (Αnalitical Κatashshr Eiiforash Diadikyou)
 * Portal: apps.e-efka.gov.gr/EDOEAPeACD/  (landing) -> apps.ika.gr/gsis-edoeap-consumer (JSF app,
 *         OAuth1 GSIS AAA provider www1.gsis.gr/gsisapps/soasgsisws2)
 * Spec: out/processes/EFKA-IKA/AkaAkeEdoeap.md + live capture runner/downloads/akel-ed/ (FIXED:
 *       the capture 500'd with javax.faces ViewExpiredException because the replay POSTed
 *       indexACD.xhtml with a STALE ViewState; correct = GET link gsis/serviceACD, see lib/akeed-http.js).
 *
 * Flow: akeedDoeapLogin (landing -> consumer login -> gsis/serviceACD -> AAA login.do -> back
 *       authenticated). POST-LOGIN the authenticated consumer page is CRAWLED (GET links only,
 *       JSF POST menu actions are NEVER fired): every response is classified —
 *       %PDF -> saved pdf_NN.<label>.pdf, application/json/JSON -> saved json_NN.json, else logged —
 *       and AKEED_manifest.json lists all of them (pdfs[]/jsons[]/html[] + menu items + print/pdf
 *       endpoints found in the page JS).
 *
 * INPUTS (env or prompt): AADE_USER, AADE_PASSWORD, AADE_AFM, AADE_CAPTCHA — TAXISnet credentials.
 * OUTPUT: downloads/akeed-edoeap/ : 00..06 flow dumps + pdf files + json files + AKEED_manifest.json.
 */
'use strict';
const path = require('path');
const fs = require('fs');
const akeed = require('../lib/akeed-http');

module.exports = {
  id: 'akeed-edoeap',
  title: 'AKEED — Εfarmob EDOEAP e AKEED (Εiiforash Diadikyou): login + list PDFs/JSONs',
  portal: 'apps.e-efka.gov.gr/EDOEAPeACD/ -> apps.ika.gr (GSIS OAuth1)',
  subsystem: 'JSF/PrimeFaces + AAA OAuth1 (soasgsisws2)',
  actions: ['retrieve', 'download'],
  inputs: [
  { key: 'user', label: 'TAXISnet username', env: 'AADE_USER' },
  { key: 'pass', label: 'TAXISnet password', env: 'AADE_PASSWORD', hidden: true },
  { key: 'afm', label: 'Α.Φ.Μ. (9-digit; blank = the 9-digit username)', env: 'AADE_AFM', optional: true },
  { key: 'captcha', label: 'Captcha code (blank = auto ddddocr, else prompt)', env: 'AADE_CAPTCHA', optional: true },
  { key: 'period', label: '-period τriοdοhsh (e.g. 2025; blank = all)', env: 'AADE_PERIOD', optional: true },
  ],

  async run(http, inp, lib) {
    const files = [];
    http.log('[akeed] LOGIN');
    const L = await akeed.akeedDoeapLogin(http, inp);
    if (!L.ok) { http.log('AKEED LOGIN FAILED: ' + L.reason + (L.captchaFile ? ' (captcha image saved: ' + L.captchaFile + ' — rerun with AADE_CAPTCHA=...)' : '')); return { ok: false, reason: L.reason, captchaFile: L.captchaFile }; }

    const authPage = L.page;
    const manifest = { portal: this.portal, retrievedAt: new Date().toISOString(), login: 'OK',
      authenticatedUrl: authPage.url, pdfs: [], jsons: [], html: [], menu: [], endpoints: [] };

    // ---- menu items (PrimeFaces) + print/pdf endpoint references in inline JS (READ-ONLY report) ----
    for (const li of authPage.text.matchAll(/<li\b[^>]*class="ui-menuitem[^"]*"[^>]*>([\s\S]*?)<\/li>/gi)) {
      const label = lib.stripTags(li[1]);
      const oc = (li[1].match(/onclick="([^"]*)"/i) || [])[1] || '';
      const href = lib.decodeHtml((li[1].match(/href="([^"]*)"/i) || [])[1] || '');
      if (label) manifest.menu.push({ label, href: href || undefined, onclick: oc.slice(0, 160) || undefined });
    }
    for (const m of authPage.text.matchAll(/([A-Za-z0-9_./-]*(?:[Pp]rint|\.aspx|\.pdf|pdf[A-Z]|PrintForm|getPdf)[A-Za-z0-9_./-]*)/g))
      if (!manifest.endpoints.includes(m[1]) && m[1].length > 4) manifest.endpoints.push(m[1]);
    manifest.endpoints = manifest.endpoints.slice(0, 60);

    // ---- crawl: GET hrefs of the authenticated page (same host, skip static JSF resources) ----
    const seen = new Set([authPage.url]);
    const queue = [];
    const push = (u) => { if (u && !seen.has(u) && !/javax\.faces\.resource|\.css|\.js(\?|$)|^data:|^#|^javascript:/i.test(u)) { seen.add(u); queue.push(u); } };
    for (const a of authPage.text.matchAll(/<a\b[^>]*href="([^"]*)"/gi)) push(lib.decodeHtml(a[1]).startsWith('http') ? lib.decodeHtml(a[1]) : new URL(lib.decodeHtml(a[1]), authPage.url).toString());
    // landing static docs (guides PDFs)
    const land = await http.follow('GET', 'https://apps.e-efka.gov.gr/EDOEAPeACD/');
    for (const a of land.text.matchAll(/href="(https?:\/\/[^"]*\.(?:pdf|xlsx?|docx?|json)[^"]*)"/gi)) push(lib.decodeHtml(a[1]));

    let n = 0;
    for (const url of queue.slice(0, 30)) {
      try {
        const doc = await http.getDoc(url);
        if (doc.buffer) {
          const fn = 'pdf_' + String(++n).padStart(2, '0') + '.pdf';
          fs.writeFileSync(path.join(http.dlDir, fn), doc.buffer); files.push(fn);
          manifest.pdfs.push({ url, file: fn, bytes: doc.buffer.length });
          http.log('[akeed] PDF  ' + url + ' -> ' + fn + ' (' + doc.buffer.length + ' b)');
        } else if ((doc.ct || '').includes('json') || /^\s*[[{]/.test(doc.text || '')) {
          let parsed = null;
          try { parsed = JSON.parse(doc.text); } catch (e) {}
          const fn = 'json_' + String(n + 100).padStart(3, '0') + '.json';
          fs.writeFileSync(path.join(http.dlDir, fn), parsed ? JSON.stringify(parsed, null, 2) : doc.text); files.push(fn);
          manifest.jsons.push({ url, file: fn, topKeys: parsed && !Array.isArray(parsed) ? Object.keys(parsed) : (Array.isArray(parsed) ? '[array ' + parsed.length + ']' : null) });
          http.log('[akeed] JSON ' + url + ' -> ' + fn);
        } else {
          manifest.html.push({ url, status: doc.status, title: lib.stripTags((doc.text || '').match(/<title[^>]*>([^<]*)/i) ? doc.text.match(/<title[^>]*>([^<]*)/i)[1] : '') });
          http.log('[akeed] html ' + url + ' (' + doc.status + ')');
        }
      } catch (e) { manifest.html.push({ url, error: e.message }); http.log('[akeed] ERR ' + url + ' ' + e.message); }
    }

    // ---- known authenticated PrimeFaces VIEWS via direct GET (capture-verified: the menu
    //      'Deλloshshsh' (Declarations) view is served at APP/EDOEAPeACD/ACDs.xhtml as a plain
    //      GET — no AJAX click/meta-fields needed; DataTable id ACDsForm:ACDsResults, rows 9) ----
    const baseView = authPage.url.replace(/index\.xhtml$/, '');
    const KNOWN_VIEWS = [
      { file: 'ACDs.xhtml', dtId: 'ACDsForm:ACDsResults', label: 'Declarations (Deλloshshsh)' },
    ];
    for (const kv of KNOWN_VIEWS) {
      const vUrl = baseView + kv.file;
      const r4 = await http.follow('GET', vUrl);
      http.dump('view_' + kv.file.replace(/\W/g, '_') + '.html', r4.text || '');
      const hasDt = (r4.text || '').includes('ui-datatable-data');
      http.log('[akeed] view GET ' + vUrl + ' -> ' + r4.status + (hasDt ? ' (DataTable ok)' : ' (no datatable)'));
      if (r4.status !== 200 || !hasDt) continue;
      const rowsHtml = lib.dataTableRows(r4.text, kv.dtId) || [];
      const headTb = (r4.text.match(/<tbody\b[^>]*class="[^"]*ui-datatable-head[^"]*"[^>]*>([\s\S]*?)<\/tbody>/i) || [])[1] || '';
      const dtArea = (r4.text.slice(r4.text.indexOf('id="' + kv.dtId + '"'), r4.text.indexOf('ui-datatable-data ui-widget-content')));
      const cols = [...((headTb || dtArea).matchAll(/<th\b[^>]*role="columnheader"[^>]*>([\s\S]*?)<\/th>/gi))].map((h) => lib.stripTags(h[1]));
      const recs = [];
      for (const cells of rowsHtml) {
        const rec = cells.map((c) => lib.stripTags(c));
        const acts = [...cells.join('').matchAll(/PrimeFaces\.ab\(\{s:&quot;([^&]*)&quot;,u:&quot;([A-Za-z]+)&quot;/g)].map((a) => ({ comp: a[2], cell: a[1] }));
        recs.push({ cells: rec, actions: acts });
      }
      const period = String(inp.period || '').trim();
      const matched = period ? recs.filter((r) => r.cells.some((c) => c === period || c.startsWith(period + '/'))) : recs;
      const outObj = { view: vUrl, label: kv.label, dataTable: kv.dtId, columns: cols, total: recs.length, period: period || null, matched: matched.length, rows: recs, rowsFiltered: matched };
      const fn = 'json_dt_' + kv.file.split('.')[0] + '.json';
      fs.writeFileSync(path.join(http.dlDir, fn), JSON.stringify(outObj, null, 2)); files.push(fn);
      manifest.jsons.push({ url: vUrl, file: fn, topKeys: '[DataTable ' + kv.dtId + ' rows ' + recs.length + '; filtered ' + matched.length + ']' });
      http.log('[akeed] DataTable ' + kv.dtId + ' ' + recs.length + ' rows (' + matched.length + ' for period ' + (period || 'all') + ') -> ' + fn);
    }

    // ---- menu clicks (JSF navigation POSTs — exactly what the CefSharp spec clicks; NEVER Logout) ----
    const tabNotes = [];
    const CLICK = /addSubmitParam\('([^']*)',\{ ?'([^']*)':'([^']*)'\}\)\.submit\('([^']*)'\)/;
    const normG = (s) => s.normalize('NFD').replace(/\p{M}/gu, '').toLowerCase();
    const isLogout = (s) => /\u03c0\u03bf\u03c3|pо\u03c3|log\s*out/i.test(normG(lib.stripTags(s)));
    const items = [];
    for (const li of authPage.text.matchAll(/<li\b[^>]*class="ui-menuitem[^"]*"[^>]*>([\s\S]*?)<\/li>/gi)) {
      const mm = CLICK.exec(li[1]); if (!mm) continue;
      const label = lib.stripTags(li[1]);
      if (label && !isLogout(label) && !items.some((x) => x.param === mm[2])) items.push({ label, param: mm[2], form: mm[4] });
    }
    const idxUrl = authPage.url;
    const classify = async (doc, url, tag) => {
      if (doc.buffer) {
        const fn = 'pdf_' + tag + '.pdf';
        fs.writeFileSync(path.join(http.dlDir, fn), doc.buffer); files.push(fn);
        manifest.pdfs.push({ url, file: fn, bytes: doc.buffer.length });
        http.log('[akeed] PDF ' + url + ' -> ' + fn + ' (' + doc.buffer.length + ' b)');
      } else if ((doc.ct || '').includes('json') || /^\s*[[{]/.test(doc.text || '')) {
        let parsed = null; try { parsed = JSON.parse(doc.text); } catch (e) {}
        const fn = 'json_' + tag + '.json';
        fs.writeFileSync(path.join(http.dlDir, fn), parsed ? JSON.stringify(parsed, null, 2) : doc.text); files.push(fn);
        manifest.jsons.push({ url, file: fn, topKeys: parsed && !Array.isArray(parsed) ? Object.keys(parsed) : (Array.isArray(parsed) ? '[array ' + parsed.length + ']' : null) });
        http.log('[akeed] JSON ' + url + ' -> ' + fn);
      } else {
        manifest.html.push({ url, status: doc.status, title: lib.stripTags((doc.text || '').match(/<title[^>]*>([^<]*)/i) ? doc.text.match(/<title[^>]*>([^<]*)/i)[1] : '') });
        http.log('[akeed] html ' + url + ' (' + doc.status + ')');
      }
    };
    let q2 = 0;
    const queue2 = [];
    for (const it of items) {
      http.log('[akeed] menu click: ' + it.label);
      const fresh = await http.follow('GET', idxUrl); // fresh ViewState right before POST (JSF pitfall)
      const act = (fresh.text.match(/<form[^>]*id="MenuForm"[^>]*action="([^"]*)"/i) || [])[1]
               || (fresh.text.match(/<form[^>]*action="([^"]*)"[^>]*id="MenuForm"/i) || [])[1]
               || (fresh.text.match(/<form[^>]*id="(form[^"]*)"[^>]*action="([^"]*)"/i) || [])[2];
      const vsm = fresh.text.match(/<form\b(?:(?!\s*<\/form>)[\s\S])*?javax\.faces\.ViewState[^>]*value="([^"]*)"/i)
               || fresh.text.match(/value="([^"]*)"[^>]*javax\.faces\.ViewState/i);
      let vs = vsm ? vsm[1] : ((fresh.text.match(/javax\.faces\.ViewState[^>]*value="([^"]*)"/i) || [])[1] || '');
      const formName = it.form || 'MenuForm';
      if (!act || !vs) { http.log('[akeed] ✗ no MenuForm action/ViewState for click'); break; }
      const r2 = await http.follow('POST', new URL(act, idxUrl).toString(), {
        'javax.faces.partial.ajax': 'true', 'javax.faces.source': formName,
        'javax.faces.partial.execute': formName, 'javax.faces.partial.render': formName,
        formName: formName, [it.param]: it.param, 'javax.faces.ViewState': vs,
      });
      // partial-response: extract component updates (the view content the click rendered)
      const ups = [...r2.text.matchAll(/<update id="([^"]*)"><!\[CDATA\[([\s\S]*?)\]\]><\/update>/g)].map((m) => ({ id: m[1], html: m[2] }));
      const tabHtml = ups.filter((u) => !/javax\.faces\.ViewState|MenuForm/.test(u.id)).map((u) => u.html).join('\n');
      const tagN = 'tab_' + String(tabNotes.length + 1).padStart(2, '0');
      http.dump(tagN + '_post.xml', r2.text);
      let tabText = tabHtml;
      if (!tabHtml) { const r3 = await http.follow('GET', idxUrl); tabText = r3.text; }
      http.dump(tagN + '.html', tabText);
      const note = { label: it.label, url: r2.url, updates: ups.map((u) => u.id) };
      // endpoints + tables on the tab
      for (const m of tabText.matchAll(/([A-Za-z0-9_./-]*(?:[Pp]rint|\.aspx|\.pdf|getPdf|PrintForm|\.json)[A-Za-z0-9_./-]*)/g))
        if (m[1].length > 4 && !manifest.endpoints.includes(m[1])) manifest.endpoints.push(m[1]);
      for (const dt of tabText.matchAll(/id="([^"]*datatable[^"]*)"/gi))
        note.dataTable = { id: dt[1], rows: lib.dataTableRows(tabText, dt[1]) || [] };
      if (note.dataTable && note.dataTable.rows.length) {
        const fn = 'json_dt_' + tagN + '.json';
        fs.writeFileSync(path.join(http.dlDir, fn), JSON.stringify(note.dataTable, null, 2)); files.push(fn);
        manifest.jsons.push({ url: r2.url, file: fn, topKeys: '[datatable rows ' + note.dataTable.rows.length + ']' });
        http.log('[akeed] DataTable ' + note.dataTable.id + ' -> ' + fn + ' (' + note.dataTable.rows.length + ' rows)');
      }
      note.submenu = [];
      for (const li of tabText.matchAll(/<li\b[^>]*class="ui-tab(?:s-header|s-footer|[^"]*)"[^>]*>([\s\S]*?)<\/li>/gi)) {
        const t = lib.stripTags(li[1]); if (t) note.submenu.push(t);
      }
      await classify({ status: r2.status, ct: 'text/html', buffer: null, text: tabText }, r2.url, tagN);
      const hrefs = [];
      for (const a of tabText.matchAll(/<a\b[^>]*href="([^"]*)"/gi)) {
        const u0 = lib.decodeHtml(a[1]);
        if (/^(data:|#|javascript:)/i.test(u0)) continue;
        const u = new URL(u0, r2.url).toString();
        if (/javax\.faces\.resource|\.css|\.js(\?|$)/i.test(u)) continue;
        hrefs.push(u);
      }
      http.log('[akeed] tab ' + it.label + ' -> links=' + hrefs.length + ' dt=' + (note.dataTable ? note.dataTable.rows.length + ' rows' : 'no') + ' submenu=' + note.submenu.length);
      for (const u of hrefs.slice(0, 15)) {
        try { const doc = await http.getDoc(u); await classify(doc, u, 't' + String(tabNotes.length + 1).padStart(2, '0') + String(++q2).padStart(2, '0')); } catch (e) { http.log('[akeed] ERR ' + u + ' ' + e.message); }
      }
      tabNotes.push(note);
    }
    manifest.tabs = tabNotes;

    const mf = 'AKEED_manifest.json';
    fs.writeFileSync(path.join(http.dlDir, mf), JSON.stringify(manifest, null, 2)); files.push(mf);
    http.log('[akeed] manifest: ' + manifest.pdfs.length + ' PDF, ' + manifest.jsons.length + ' JSON, ' + manifest.html.length + ' html, ' + manifest.menu.length + ' menu items -> ' + mf);
    return { ok: true, files, data: manifest };
  },
};
