/*
 * configs/efka-ame.js  --  Ανάκτηση Αριθμού Μητρώου Εργοδότη (ΑΜΕ) από e-EFKA  [BROWSER]
 * =============================================================================
 * Αντιστοιχεί στο Hyper.Server.Tax (post-update 2026-10-14): WebRequestHelper.GetEfkaAme +
 * LoginEfkaOldWayTaxinet. Το app που λιστάρει τους ΑΜΕ = «Πιστοποίηση Εργοδοτών» (ePistErg).
 *
 * ΥΛΟΠΟΙΗΣΗ ΜΕ BROWSER (Playwright): το e-ΕΦΚΑ portal έχει JS redirects, eAccess inter-app SSO (si),
 * ΥΠΟΧΡΕΩΤΙΚΟ prompt ενημέρωσης στοιχείων επικοινωνίας («Παράλειψη»/«Συνέχεια») και dynamic JSF ids —
 * καθαρό-HTTP είναι εύθραυστο. LIVE-VERIFIED end-to-end.
 *
 * ΡΟΗ: eAccess login.xhtml → «Συνέχεια στο TAXISNET» → GSIS (j_username/j_password, #btn-login-submit,
 *   approval #btn-submit) → tab «Επιχείρηση/Πολίτης» + «Είσοδος» (afm) → ePistErg → («Παράλειψη» αν
 *   εμφανιστεί το eProfile prompt) → «Κοινών Επιχειρήσεων» → AMEs.xhtml → #AMEsForm:AMEsResults_data
 *   (1η γραμμή data-ri>=0, 1ο <td> = ΑΜΕ). Για οικοδομοτεχνικά: docKind=oikodomotexnikon.
 *
 * INPUTS: TAXISnet user/pass + ΑΦΜ υπόχρεου (εργοδότη). OUTPUT: EFKA_AME_<ΑΦΜ>.json.
 * Βλ. out/methodologies/EFKA_AME_retrieval.md.
 */
'use strict';
const path = require('path');
const fs = require('fs');
const { withBrowser } = require('../lib/browser-step');

const EFKA = 'https://apps.e-efka.gov.gr';

module.exports = {
  id: 'efka-ame',
  title: 'Ανάκτηση Αρ. Μητρώου Εργοδότη (ΑΜΕ) — e-EFKA [browser]',
  portal: 'e-EFKA Πιστοποίηση Εργοδοτών (apps.e-efka.gov.gr/ePistErg, GSIS)',
  subsystem: 'Hyper.Server.Tax (GetEfkaAme + LoginEfkaOldWayTaxinet, post-update)',
  actions: ['retrieve'],
  inputs: [
    { key: 'user', label: 'TAXISnet username', env: 'EFKA_USER' },
    { key: 'pass', label: 'TAXISnet password', env: 'EFKA_PASS', hidden: true },
    { key: 'afm', label: 'ΑΦΜ υπόχρεου (εργοδότη)', env: 'EFKA_AFM' },
    { key: 'docKind', label: 'Είδος: koinon (Κοινών Επιχειρήσεων· default) ή oikodomotexnikon', env: 'EFKA_AME_KIND', optional: true },
  ],

  async run(http, inp) {
    const afm = String(inp.afm || '').trim();
    if (!inp.user || !inp.pass || !afm) { http.log('[efka-ame] Χρειάζονται user + pass + ΑΦΜ υπόχρεου'); return { ok: false, reason: 'InvalidCredentials' }; }
    const linkText = /oikodom/i.test(inp.docKind || '') ? 'Οικοδομοτεχνικών Έργων' : 'Κοινών Επιχειρήσεων';
    const dump = (p, n, html) => { try { fs.writeFileSync(path.join(http.dlDir, n), html); } catch (e) {} };

    const res = await withBrowser({ headed: false }, async ({ page }) => {
      // 1) eAccess login page → «Συνέχεια στο TAXISNET»
      http.log('[efka-ame] eAccess login');
      await page.goto(EFKA + '/eAccess/login.xhtml'); await page.sleep(1500);
      try { await page.raw.getByText('Συνέχεια στο TAXISNET', { exact: false }).first().click(); } catch (e) {}
      await page.sleep(2500);
      // 2) GSIS oauth2 login (#btn-login-submit) + approval (#btn-submit)
      if (await page.count('input[name="j_username"]')) {
        http.log('[efka-ame] GSIS login');
        await page.fill('input[name="j_username"]', inp.user);
        await page.fill('input[name="j_password"]', inp.pass);
        await page.raw.locator('#btn-login-submit').first().click(); await page.sleep(3500);
      }
      if (await page.count('input[name="user_oauth_approval"]')) { await page.raw.locator('#btn-submit').first().click(); await page.sleep(3000); }
      dump(page, '01_after_gsis.html', await page.content());
      if (await page.count('input[name="j_password"]')) return { ok: false, reason: 'InvalidCredentials' };

      // 3) eAccess: tab «Επιχείρηση/Πολίτης» (ΑΦΜ, όχι ΑΜΚΑ) → επαλήθευση afm1 → «Είσοδος»
      try { await page.raw.getByText('Επιχείρηση/Πολίτης', { exact: false }).first().click(); await page.sleep(800); } catch (e) {}
      const afm1 = await page.evaluate(() => { const el = document.getElementById('gsisTabView:afm1'); return el ? (el.value || '') : ''; });
      if (afm1 && afm1 !== afm) { http.log('[efka-ame] ΑΦΜ κωδικών (' + afm1 + ') ≠ ΑΦΜ υπόχρεου (' + afm + ')'); return { ok: false, reason: 'AfmMismatch' }; }
      try { await page.raw.getByRole('button', { name: 'Είσοδος' }).first().click(); }
      catch (e) { try { await page.raw.getByText('Είσοδος', { exact: true }).first().click(); } catch (e2) {} }
      await page.sleep(4000);
      http.log('[efka-ame] eAccess landing: ' + page.url());

      // 4) «Πιστοποίηση Εργοδοτών» (ePistErg) — με «Παράλειψη» αν εμφανιστεί το forced eProfile prompt
      await page.goto(EFKA + '/ePistErg').catch(() => {}); await page.sleep(3500);
      for (let k = 0; k < 2 && /eProfile/i.test(page.url()); k++) {
        try { await page.raw.getByRole('button', { name: 'Παράλειψη' }).first().click(); http.log('[efka-ame] «Παράλειψη» (eProfile prompt)'); await page.sleep(4000); }
        catch (e) { break; }
      }
      dump(page, '02_episterg.html', await page.content());
      if (!/ePistErg/i.test(page.url())) { http.log('[efka-ame] δεν άνοιξε το ePistErg (' + page.url() + ')'); return { ok: false, reason: 'NoPistErg' }; }

      // 5) «Κοινών Επιχειρήσεων» → AMEs.xhtml
      try { await page.raw.getByText(linkText, { exact: false }).first().click(); } catch (e) { http.log('[efka-ame] δεν βρέθηκε «' + linkText + '»'); return { ok: false, reason: 'NoKoinonLink' }; }
      await page.sleep(4000);
      dump(page, '03_ames.html', await page.content());

      // 6) #AMEsForm:AMEsResults_data → γραμμές data-ri>=0 (1ο td = ΑΜΕ)
      const rows = await page.evaluate(() => {
        const t = document.getElementById('AMEsForm:AMEsResults_data'); if (!t) return null;
        const out = [];
        for (const tr of t.querySelectorAll('tr')) { const ri = tr.getAttribute('data-ri'); if (ri != null && +ri >= 0) out.push([...tr.querySelectorAll('td')].map(td => td.innerText.trim())); }
        return out;
      });
      if (!rows) return { ok: false, reason: 'NoAmeTable' };
      const list = rows.filter(r => r[0] && /^\d+$/.test(r[0]))
        .map(r => ({ ame: r[0], name: r[1] || '', apdCode: r[3] || '', username: r[4] || '', email: r[5] || '', cells: r }));
      if (!list.length) return { ok: false, reason: 'AmeNotFound' };
      return { ok: true, ames: list, url: page.url() };
    });

    if (!res || !res.ok) { http.log('[efka-ame] FAILED: ' + (res && res.reason)); return { ok: false, reason: (res && res.reason) || 'BrowserError' }; }
    const out = { portal: this.portal, afm, kind: linkText, retrievedAt: new Date().toISOString(), ame: res.ames[0].ame, allAmes: res.ames };
    const jf = path.join(http.dlDir, 'EFKA_AME_' + afm + '.json');
    fs.writeFileSync(jf, JSON.stringify(out, null, 2));
    http.log('[efka-ame] ✅ ΑΜΕ = ' + res.ames[0].ame + ' (' + (res.ames[0].name || '') + ')' + (res.ames.length > 1 ? ' +' + (res.ames.length - 1) : ''));
    return { ok: true, files: [path.basename(jf)], summary: { ame: res.ames[0].ame, name: res.ames[0].name, count: res.ames.length } };
  },
};
