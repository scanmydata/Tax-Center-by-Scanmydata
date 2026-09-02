/*
 * configs/aade-login-check.js  --  Probe: AADE (www1.aade.gr) login via GSIS OAM
 * Source: Hyper.Server.Tax.dll  Easy_Aade login (decompiled ~56844).
 *
 * PURPOSE: validate the AADE OAM login end-to-end BEFORE building the AADE document
 * scrapes (μισθωτήρια, οφειλές ΑΑΔΕ, E1/E2/E3/E9/ΕΝΦΙΑ/ΦΠΑ/βεβ. αποδοχών…), which all
 * share this same login. It logs in and confirms the protected home loads (no OAM form),
 * then lists the available menu links so we can wire the next processes to real URLs.
 *
 * INPUTS: TAXISnet user/pass.
 */
'use strict';
const path = require('path');
const fs = require('fs');

module.exports = {
  id: 'aade-login-check',
  title: 'AADE login probe (OAM) — foundation for all AADE processes',
  portal: 'AADE (www1.aade.gr, GSIS OAM login.gsis.gr)',
  subsystem: 'Hyper.Server',
  actions: ['login-check'],
  inputs: [
    { key: 'user', label: 'TAXISnet username', env: 'AADE_USER' },
    { key: 'pass', label: 'TAXISnet password', env: 'AADE_PASS', hidden: true },
  ],

  async run(http, inp, lib) {
    const L = await lib.aadeLogin(http, inp);
    if (!L.ok) { http.log('AADE LOGIN FAILED: ' + L.reason + ' (see dumps 01_aade_oam.html / 02_aade_authresp.html)'); return { ok: false, reason: L.reason }; }

    // collect menu links from the taxisnet home (helps wire the next processes)
    const links = {};
    for (const a of (L.page.text || '').matchAll(/<a\b[^>]*href="([^"]*)"[^>]*>([\s\S]*?)<\/a>/gi)) {
      const t = lib.stripTags(a[2]); const href = lib.decodeHtml(a[1]);
      if (t && /\.htm|protected|webtax|saadeapps/i.test(href)) links[t] = new URL(href, L.AADE).toString();
    }
    fs.writeFileSync(path.join(http.dlDir, 'aade_menu_links.json'), JSON.stringify(links, null, 2));
    http.log('[aade] ✅ LOGIN OK — menu links saved (' + Object.keys(links).length + '). See aade_menu_links.json + 04_aade_home.html');
    return { ok: true, files: ['aade_menu_links.json'] };
  },
};
