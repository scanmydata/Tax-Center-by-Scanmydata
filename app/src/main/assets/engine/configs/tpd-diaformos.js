/*
 * configs/tpd-diaformos.js  --  Process: Πλρhρmiτe Tlwn Difrmshsh (TPD e-Services)
 * Portal: https://e-services.tpd.gr  (POST-LOGIN JSON API requests-service/v2)
 * Spec: out/processes/TPD-eServices/PlhrTelwnDiaf.md (verified live).
 *
 * Flow (all cookies from the shared curl jar; see lib/tpd-http.js for the login):
 *   GET  {E}/requests-service/v2/requests/   -> request TYPES [{id,displayName,messagingPanel}]
 *   GET  {E}/requests-service/v2/history/    -> completed [{requestId,requestTypeId,requestName,
 *                                                        status,lastUpdated,uiStatus,actions:[{id,name}]}]
 *   POST {E}/requests-service/v2/proceed     {requestTypeId, actionId, requestId}
 *        -> {requestId,responseType,content,responseMessage,fields:[{fieldName,fieldValue,...}]}
 *   PdfFile field = base64 of the notice PDF (client makes a data:application/pdf;base64 link)
 *
 * actionId 5 = "Πrοbola Istorical" (view the completed notice) — READ-ONLY, safe.
 * initiate / payment/* are NEVER called (state-changing, money).
 *
 * INPUTS (env or prompt): AADE_USER, AADE_PASS — same env names as the ΑΑΔΕ processes (one
 * TAXISnet account). Optional: TPD_PICK ('all' | index | list | range), TPD_TYPE (requestTypeId
 * filter). Default = every history entry.
 * OUTPUT: per entry notice_<id>.pdf + item_<id>.json, plus HISTORY.json / TYPES.json,
 * and a run SUMMARY json the caller program can consume (returned in res.data too).
 */
'use strict';
const path = require('path');
const fs = require('fs');

module.exports = {
  id: 'tpd-diaformos',
  title: 'TPD e-Services — notices (Πλρhρmiτe Tlwn Difrmshsh)',
  portal: 'TPD e-Services (e-services.tpd.gr)',
  subsystem: 'requests-service/v2 JSON (POST-LOGIN)',
  transport: 'curl', // host needs TLS1.2 + HTTP/1.1 (Node fetch TLS fails) — see lib/tpd-http.js
  actions: ['retrieve', 'download'],
  inputs: [
    { key: 'user', label: 'TAXISnet username', env: 'AADE_USER' },
    { key: 'pass', label: 'TAXISnet password', env: 'AADE_PASS', hidden: true },
    { key: 'pick', label: "Pick ('all' | history index) — blank = all", env: 'TPD_PICK', optional: true },
    { key: 'type', label: 'requestTypeId filter (blank = any)', env: 'TPD_TYPE', optional: true },
  ],

  async run(http, inp, lib) {
    const E = process.env.TPD_HOST || 'https://e-services.tpd.gr';
    const files = [];
    const summary = { portal: this.portal, retrievedAt: new Date().toISOString(), requestTypes: [], history: [], items: [] };

    // 1) login (OAuth2 GSIS -> consent -> code redirect -> e-services SESSION)
    const L = await lib.tpdEservicesLogin(http, inp, E);
    if (!L.ok) { http.log('TPD LOGIN FAILED: ' + L.reason + (L.probe ? ' (probe status ' + L.probe.status + ')' : '')); return { ok: false, reason: L.reason }; }
    const types = L.types || [];
    http.dump('TYPES.json', JSON.stringify(types, null, 2));
    summary.requestTypes = types;
    files.push('TYPES.json');

    // 2) history (the dynamic state we are asked about)
    const h = await http.api('GET', E + '/requests-service/v2/history/');
    if (h.status !== 200) { http.log('history -> ' + h.status); return { ok: false, reason: 'HistoryStatus ' + h.status }; }
    let hist = [];
    try { hist = JSON.parse(h.text); } catch (e) { http.dump('history.raw.txt', h.text); return { ok: false, reason: 'HistoryNotJson' }; }
    hist.sort((a, b) => (b.lastUpdated || '').localeCompare(a.lastUpdated || ''));
    http.dump('HISTORY.json', JSON.stringify(hist, null, 2)); files.push('HISTORY.json');
    summary.history = hist;
    http.log('=== HISTORY (' + hist.length + ') ===');
    hist.forEach((x, i) => http.log('  [' + i + '] ' + x.lastUpdated + ' type=' + x.requestTypeId + ' ' + x.requestName +
      ' :: ' + x.uiStatus + ' :: requestId=' + x.requestId + ' :: actions=' + (x.actions || []).map((a) => a.id + ':' + a.name).join(', ')));

    // 3) selection
    const fType = (inp.type || '').trim();
    const fPick = (inp.pick || '').trim().toLowerCase();
    let wanted;
    if (/^\d[\d,\s-]*$/.test(fPick)) {
      const expand = (s) => {
        if (!/^\d+-\d+$/.test(s)) return [+s];
        const a = +s.split('-')[0], b = +s.split('-')[1];
        const out = []; for (let i = a; i <= b; i++) out.push(i); return out;
      };
      const idxs = fPick.split(/[\s,]+/).filter((s) => s !== '').map(expand).flat();
      wanted = idxs.map((i) => hist[i]).filter(Boolean);
    }
    else if (fType) wanted = hist.filter((x) => String(x.requestTypeId) === fType);
    else wanted = hist.slice();
    if (!wanted.length) { http.log('nothing selected'); return { ok: false, reason: 'NoSelection' }; }

    // 4) proceed (READ actionId only: prefer the "view/Πrοbola Istorical" one) + save PDF + JSON
    for (const x of wanted) {
      const acts = x.actions || [];
      const VIEW = /istor|οτory|view|履歴|Σyteme|Πrοbola/i; // the read-only "view past notice" action (observed id 5)
      const isUnsafe = (a) => /pay|initiate|submit|delete|cancel/i.test(String(a.name || '') + ' ' + String(a.id));
      const act = acts.find((a) => !isUnsafe(a) && VIEW.test(String(a.name || '')))
        || acts.find((a) => !isUnsafe(a) && +a.id === 5)
        || acts.filter((a) => !isUnsafe(a)).sort((a, b) => (+b.id || 0) - (+a.id || 0))[0]
        || acts[0];
      if (!act) { http.log('  no action for ' + x.requestId); continue; }
      http.log('[proceed] ' + x.requestName + ' type=' + x.requestTypeId + ' action=' + act.id + ' (' + (act.name || '') + ') requestId=' + x.requestId);
      const pr = await http.api('POST', E + '/requests-service/v2/proceed', { requestTypeId: x.requestTypeId, actionId: act.id, requestId: x.requestId });
      const tag = String(x.requestId).slice(0, 8);
      const item = { requestId: x.requestId, requestTypeId: x.requestTypeId, requestName: x.requestName, status: x.status, uiStatus: x.uiStatus, lastUpdated: x.lastUpdated, actionId: act.id, actionName: act.name, fields: {}, pdf: null };
      let j = null;
      try { j = JSON.parse(pr.text); } catch (e) { http.dump('proceed_raw_' + tag + '.txt', pr.text); http.log('  proceed -> status ' + pr.status + ' (not JSON)'); summary.items.push({ requestId: x.requestId, error: 'status ' + pr.status }); continue; }
      item.responseType = j.responseType; item.responseMessage = j.responseMessage; item.content = j.content;
      for (const f of j.fields || []) {
        const isPdf = /pdf/i.test(String(f.fieldName || ''));
        if (isPdf && f.fieldValue) {
          const b = Buffer.from(f.fieldValue, 'base64');
          if (b.slice(0, 4).toString('latin1') === '%PDF') {
            const fn = 'notice_' + tag + '.pdf';
            fs.writeFileSync(path.join(http.dlDir, fn), b); files.push(fn);
            item.pdf = { file: fn, bytes: b.length };
            item.fields[f.fieldName] = { value: '<PDF ' + b.length + ' bytes -> ' + fn + '>', file: fn, bytes: b.length };
            http.log('  PDF ok (' + b.length + ' b) -> ' + fn);
          } else {
            item.fields[f.fieldName] = '<not %PDF, ' + Math.ceil((f.fieldValue || '').length / 1024) + ' KB base64>';
            http.log('  PdfFile decoded but NOT %PDF');
          }
          continue;
        }
        item.fields[f.fieldName] = { value: f.fieldValue, displayName: f.displayName, inputType: f.fieldInputType, readOnly: f.readOnly, mandatory: f.mandatory };
      }
      const jf = 'item_' + tag + '.json';
      fs.writeFileSync(path.join(http.dlDir, jf), JSON.stringify(item, null, 2)); files.push(jf);
      summary.items.push(item);
      http.log('  item -> ' + jf + ' (' + Object.keys(item.fields).length + ' fields)');
    }

    const sf = 'SUMMARY.json';
    fs.writeFileSync(path.join(http.dlDir, sf), JSON.stringify(summary, null, 2)); files.push(sf);
    const pdfs = summary.items.filter((i) => i.pdf).length;
    http.log('DONE: ' + summary.items.length + ' item(s), ' + pdfs + ' PDF -> ' + sf);
    return { ok: pdfs > 0, files, data: summary, reason: pdfs ? null : 'NoPdf' };
  },
};
