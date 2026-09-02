/*
 * configs/amka-retrieve.js  --  Process: Ανάκτηση ΑΜΚΑ (MyAMKA)
 * Portal: www.amka.gr/app  (myAMKA, GOV.GR) — replaces the old EpsilonSubmit browser doc.
 *
 * The old TaxSystem "myAMKA" doc drove a browser: login MyAMKA with TAXISnet, then
 * scrape the AMKA from the page. The portal is now an OAuth (BFF) SPA whose data comes
 * from a REST call. This is the FAITHFUL HTTP reproduction of what the SPA does:
 *   GET  /app/oauth/login                       -> GSIS OAuth2 (j_username/j_password + approval)
 *   GET  /app/oauth/token                        -> bearer token
 *   POST /app/api/AmkaCitizenAppService/CTZ_GetPersonAmka   (Authorization: ctaf2 <token>, body {})
 *        -> { amkaList: [...], unreadMessages: n }
 *
 * INPUTS: TAXISnet user/pass only (the AMKA is what we RETRIEVE).
 */
'use strict';
const path = require('path');
const fs = require('fs');

// pull AMKA-looking values (11 digits, DD<=31, MM<=12) out of an arbitrary JSON value
function collectAmka(node, acc) {
  if (node == null) return;
  if (typeof node === 'string' || typeof node === 'number') {
    const s = String(node);
    if (/^\d{11}$/.test(s)) { const dd = +s.slice(0, 2), mm = +s.slice(2, 4); if (dd >= 1 && dd <= 31 && mm >= 1 && mm <= 12) acc.add(s); }
    return;
  }
  if (Array.isArray(node)) { for (const x of node) collectAmka(x, acc); return; }
  if (typeof node === 'object') { for (const k of Object.keys(node)) collectAmka(node[k], acc); }
}

module.exports = {
  id: 'amka-retrieve',
  title: 'Ανάκτηση ΑΜΚΑ (MyAMKA)',
  portal: 'MyAMKA (www.amka.gr/app, GOV.GR)',
  subsystem: 'MyAMKA / BFF REST',
  actions: ['retrieve'],
  inputs: [
    { key: 'user', label: 'TAXISnet username', env: 'EFKA_USER' },
    { key: 'pass', label: 'TAXISnet password', env: 'EFKA_PASS', hidden: true },
  ],

  async run(http, inp, lib) {
    // 1) login MyAMKA (GSIS OAuth2 BFF)
    const L = await lib.myAmkaLogin(http, inp);
    if (!L.ok) { http.log('LOGIN FAILED: ' + L.reason); return { ok: false, reason: L.reason }; }

    // 2) CTZ_GetPersonAmka
    const res = await lib.myAmkaApi(http, L.token, 'CTZ_GetPersonAmka', {});
    if (!res.ok) { http.log('API FAILED: ' + res.reason); http.dump('amka_api_error.txt', res.raw || ''); return { ok: false, reason: res.reason }; }

    http.dump('amka_person.json', JSON.stringify(res.data, null, 2));
    const found = new Set();
    // primary: documented shape { amkaList: [...] }
    if (res.data && res.data.amkaList) collectAmka(res.data.amkaList, found);
    if (!found.size) collectAmka(res.data, found); // fallback: scan whole payload
    const amkas = [...found];

    if (!amkas.length) { http.log('[amka] no AMKA in payload (see amka_person.json)'); return { ok: false, reason: 'NotFound' }; }
    const dest = path.join(http.dlDir, 'AMKA_' + inp.user + '.json');
    fs.writeFileSync(dest, JSON.stringify({ amka: amkas[0], amkaList: amkas, source: res.data }, null, 2));
    http.log('[amka] ✅ AMKA = ' + amkas.join(', ') + '  -> ' + dest);
    return { ok: true, amka: amkas[0], files: [path.basename(dest)] };
  },
};
