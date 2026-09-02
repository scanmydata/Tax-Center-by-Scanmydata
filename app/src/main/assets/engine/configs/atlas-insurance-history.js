/*
 * configs/atlas-insurance-history.js  --  Process: Ασφαλιστικό/Εργασιακό Ιστορικό (ΑΤΛΑΣ)
 * Source: Hyper.Server.Tax.dll  Easy_EFKA_GGPS  (Login + GetAtlasAm, decompiled ~58525 / ~59290).
 *
 * FAITHFUL translation:
 *   Login()      -> lib.efkaGgpsLogin  (apps.e-efka.gov.gr/eAccess -> GSIS OAuth2 -> afm+amka)
 *   GetAtlasAm() -> GET https://www.atlas.gov.gr/apps/InsuranceHistory/  (GSIS SSO shared)
 *                  -> scrape 2 DevExpress grids: data-grid-misth (μισθωτοί),
 *                     data-grid-mi-misth (μη μισθωτοί) -> { "0": [...], "1": [...] } JSON.
 * (In the C# this is RetrievalKindPdfs.AtlasReg_Data: the "PdfData" it stores is JSON, not a PDF.)
 *
 * NOTE: there is NO "look up AMKA from AFM" flow in TaxSystem — AMKA (SSNum) is read from
 * party.F_AMKA and supplied as an input to login. This process returns the insurance history.
 */
'use strict';
const path = require('path');
const fs = require('fs');

module.exports = {
  id: 'atlas-insurance-history',
  title: 'Ασφαλιστικό/Εργασιακό Ιστορικό (ΑΤΛΑΣ)',
  portal: 'ATLAS via e-EFKA GGPS (apps.e-efka.gov.gr + atlas.gov.gr)',
  subsystem: 'Hyper.Server',
  actions: ['retrieve'],
  inputs: [
    { key: 'user', label: 'TAXISnet username', env: 'EFKA_USER' },
    { key: 'pass', label: 'TAXISnet password', env: 'EFKA_PASS', hidden: true },
    { key: 'afm', label: 'ΑΦΜ', env: 'EFKA_AFM' },
    { key: 'amka', label: 'ΑΜΚΑ', env: 'EFKA_AMKA' },
  ],

  async run(http, inp, lib) {
    // 1) login (e-EFKA GGPS variant — apps.e-efka.gov.gr/eAccess + GSIS OAuth2)
    const L = await lib.efkaGgpsLogin(http, inp);
    if (!L.ok) { http.log('LOGIN FAILED: ' + L.reason); return { ok: false, reason: L.reason }; }
    http.log('[atlas] login OK');

    // 2) GET the ATLAS InsuranceHistory page (shares the GSIS SSO session)
    const r = await http.follow('GET', 'https://www.atlas.gov.gr/apps/InsuranceHistory/');
    http.dump('05_atlas_insurancehistory.html', r.text);

    // 3) scrape the two DevExpress grids (== C# AtlasGetGridData)
    const misth = lib.atlasGrid(r.text, 'data-grid-misth');       // μισθωτοί
    const miMisth = lib.atlasGrid(r.text, 'data-grid-mi-misth');  // μη μισθωτοί
    const data = {};
    if (misth) data['0'] = misth;
    if (miMisth) data['1'] = miMisth;

    if (!Object.keys(data).length) {
      http.log('[atlas] no grids found on page (== C# DownLoadStatus.NotFound)');
      return { ok: false, reason: 'NotFound' };
    }

    const dest = path.join(http.dlDir, 'ATLAS_' + (inp.afm || 'unknown') + '.json');
    fs.writeFileSync(dest, JSON.stringify(data, null, 2));
    http.log('[atlas] ✅ SAVED -> ' + dest
      + ' (μισθωτοί=' + (misth ? misth.length : 0) + ' rows, μη-μισθωτοί=' + (miMisth ? miMisth.length : 0) + ' rows)');
    return { ok: true, files: [path.basename(dest)] };
  },
};
