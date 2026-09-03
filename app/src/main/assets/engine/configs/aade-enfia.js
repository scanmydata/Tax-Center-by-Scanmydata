/*
 * configs/aade-enfia.js  --  ΕΝΦΙΑ Εκκαθαριστικό (+ προαιρετικά Ε9 / Περιουσιακή Κατάσταση)
 * App: ETAK  www1.aade.gr/etak/faces/main.jspx  (Oracle ADF + F5 ASM — απαιτεί ΠΡΑΓΜΑΤΙΚΟ browser).
 *
 * Γιατί browser-step κι όχι HTTP: το ETAK κάνει ADF loopback + F5 client-verification (bot-detection)
 * που δεν αναπαράγονται με σκέτο HTTP χωρίς πλαστογράφηση cookies (εκτός ορίων). Ο desktop runner το
 * τρέχει με Playwright· η μελλοντική Android εφαρμογή υλοποιεί το ΙΔΙΟ BrowserPage contract με native
 * WebView (τρέχει το ίδιο page-JS on-device, χωρίς πλαστογράφηση). Ροή == ιδιοκτήτη e9.py.
 *
 * ΣΗΜΕΙΩΣΗ: ΕΝΦΙΑ Δόσεις & Ειδοποιητήριο έχουν ΚΛΕΙΣΕΙ — δεν υλοποιούνται.
 *
 * FLOW:
 *   goto www.aade.gr/dilosi-e9-enfia -> link «Είσοδος στην εφαρμογή» (a[href=".../etak/"]) -> popup
 *   popup GSIS OAM: username/password/btn_login -> (cbEnter «Είσοδος») -> ETAK main.jspx
 *   select[name="pt1:yearSelect"] -> επιλογή έτους (default: επιλεγμένο/μέγιστο)
 *   a[id="pt1:clPrintEkk{year}"] «Εκτύπωση Εκκαθαριστικού» -> download PDF
 *   (προαιρετικά e9=ναι) tab «Περιουσιακή Κατάσταση» -> div[role=grid] -> JSON (Ε9 δεδομένα)
 *
 * INPUTS: TAXISnet user/pass + Έτος (κενό = πιο πρόσφατο) + e9 (ναι/όχι: και τα grids Ε9).
 * OUTPUT: ENFIA_EKK_<user>_<year>.pdf + AADE_enfia_<user>.json (year options + Ε9 grids αν ζητηθούν).
 */
'use strict';
const path = require('path');
const fs = require('fs');
const { withBrowser } = require('../lib/browser-step');

const ENFIA_LANDING = 'https://www.aade.gr/dilosi-e9-enfia';
const ETAK_MAIN = 'https://www1.aade.gr/etak/faces/main.jspx';

module.exports = {
  id: 'aade-enfia',
  title: 'ΕΝΦΙΑ Εκκαθαριστικό (+ Ε9/Περιουσιακή) — ETAK',
  portal: 'AADE ETAK (Oracle ADF, browser-step)',
  subsystem: 'Hyper.Server',
  actions: ['retrieve', 'download'],
  needsBrowser: true,
  inputs: [
    { key: 'user', label: 'TAXISnet username', env: 'AADE_USER' },
    { key: 'pass', label: 'TAXISnet password', env: 'AADE_PASS', hidden: true },
    { key: 'year', label: 'Έτος (κενό = πιο πρόσφατο)', env: 'AADE_YEAR', optional: true },
    { key: 'years', label: 'Έτη χωρισμένα με κόμμα (υπερισχύει του «Έτος»)', env: 'AADE_YEARS', optional: true },
    { key: 'e9', label: 'Και δεδομένα Ε9/Περιουσιακής; (ναι/όχι)', env: 'AADE_E9', optional: true },
  ],

  async run(http, inp, lib) {
    const wantE9 = /^(ναι|nai|yes|y|1)$/i.test((inp.e9 || '').trim());
    const headed = /^(1|true|ναι)$/i.test(process.env.AADE_HEADED || '');
    const res = await withBrowser({ headed }, async ({ page }) => {
      // 1) landing -> entry link -> popup
      http.log('[enfia] GET dilosi-e9-enfia');
      await page.goto(ENFIA_LANDING); await page.waitLoad();
      let entry = 'a[href="https://www1.aade.gr/etak/"]';
      if (!(await page.count(entry))) entry = 'a:has-text("Είσοδος στην εφαρμογή")';
      if (!(await page.count(entry))) entry = 'a:has-text("Είσοδος")';
      if (!(await page.count(entry))) return { ok: false, reason: 'NoEntryLink' };
      http.log('[enfia] click «Είσοδος στην εφαρμογή» -> popup');
      const pop = await page.expectPopup(() => page.click(entry));
      await pop.sleep(2500);

      // 2) GSIS OAM login (== e9.py)
      if ((await pop.count('input[name="username"]')) && (await pop.count('input[name="password"]'))) {
        http.log('[enfia] GSIS login');
        await pop.fill('input[name="username"]', inp.user);
        await pop.fill('input[name="password"]', inp.pass);
        let btn = 'button[name="btn_login"]';
        if (!(await pop.count(btn))) btn = 'button:has-text("Συνδεση"), button:has-text("ΣΥΝΔΕΣΗ")';
        await pop.click(btn); await pop.waitLoad(); await pop.sleep(2500);
        const u = pop.url();
        if (/Error\.jsp|p_error_code/.test(u)) {
          const c = await pop.content();
          if (/OAM-6/.test(u) || c.includes('μέγιστο αριθμό περιόδων λειτουργίας')) return { ok: false, reason: 'GSIS OAM-6 session limit (κλείσε ανοιχτές συνεδρίες)' };
          return { ok: false, reason: 'GSIS login failed' };
        }
      }
      await pop.waitLoad(); await pop.sleep(2000);

      // 3) reach ETAK main + click «Είσοδος» (cbEnter) if present  (`:` in id -> attribute selector)
      if (pop.url().startsWith('https://www1.aade.gr/etak/') && !pop.url().includes('faces/main.jspx')) {
        await pop.goto(ETAK_MAIN); await pop.waitLoad(); await pop.sleep(2000);
      }
      if (await pop.count('button[id="pt1:cbEnter"]')) { http.log('[enfia] ETAK «Είσοδος» (cbEnter)'); await pop.clickNav('button[id="pt1:cbEnter"]'); await pop.sleep(2500); }
      else if (await pop.count('button:has-text("Είσοδος")')) { http.log('[enfia] ETAK «Είσοδος»'); await pop.clickNav('button:has-text("Είσοδος")'); await pop.sleep(2500); }
      const yearSel = 'select[id="pt1:yearSelect::content"], select[name="pt1:yearSelect"]';
      if (!(await pop.count(yearSel))) { await pop.goto(ETAK_MAIN); await pop.waitLoad(); await pop.sleep(2000); }

      // 4) year options
      if (!(await pop.count(yearSel))) return { ok: false, reason: 'NoYearSelect', out: { url: pop.url() } };
      const opts = await pop.options(yearSel);
      const yearsAvail = opts.map(o => (o.title || o.text).trim()).filter(Boolean);

      // Πολλά έτη σε ΜΙΑ συνεδρία. Το GSIS κλειδώνει τον λογαριασμό με πολλές
      // ταυτόχρονες συνδέσεις (OAM-6), και μια χωριστή σύνδεση ανά έτος είναι
      // ακριβώς ο τρόπος να το πετύχεις.
      const asked = String(inp.years || inp.year || '').split(',').map(s => s.trim()).filter(Boolean);
      const unknownYears = asked.filter(y => !yearsAvail.includes(y));
      let years = asked.filter(y => yearsAvail.includes(y));
      if (!years.length) {
        if (asked.length) return { ok: false, reason: 'YearNotAvailable', out: { yearsAvail, asked } };
        const sel = opts.find(o => o.selected);
        years = [sel ? (sel.title || sel.text).trim() : yearsAvail.slice().sort().slice(-1)[0]];
      }
      years.sort().reverse();
      http.log('[enfia] διαθέσιμα: ' + yearsAvail.join(', ') + ' | ζητούνται: ' + years.join(', '));
      if (unknownYears.length) http.log('[enfia] ⚠ άγνωστα έτη (παραλείπονται): ' + unknownYears.join(', '));

      const out = {
        portal: this.portal, user: inp.user, year: years[0], years,
        yearsAvailable: yearsAvail, unknownYears, retrievedAt: new Date().toISOString(),
      };
      const files = [];

      for (const year of years) {
        const match = opts.find(o => (o.title || o.text).trim() === year || o.value === year);
        if (!match) continue;
        if (match.value) await pop.selectByValue(yearSel, match.value); else await pop.selectByLabel(yearSel, match.text);
        await pop.sleep(2500);

        // BOTH prints live under the «Περιουσιακή Κατάσταση» tab· το κλικ
        // εφαρμόζει και το έτος. Ξαναπατιέται σε κάθε επανάληψη επίτηδες: το
        // ADF ξαναχτίζει το panel μετά από κάθε αλλαγή έτους.
        if (await pop.count('a[id="pt1:estatesAndLandsTab::disAcr"]')) await pop.click('a[id="pt1:estatesAndLandsTab::disAcr"]');
        else if (await pop.count('a:has-text("Περιουσιακή Κατάσταση")')) await pop.click('a:has-text("Περιουσιακή Κατάσταση")');
        await pop.waitLoad(); await pop.sleep(2500);

        // 5a) ΕΝΦΙΑ Εκκαθαριστικό — a[id^="pt1:clPrintEkk"].
        //
        //     ΠΡΟΣΟΧΗ: δίνει ΠΑΝΤΑ την **τελευταία** εκκαθάριση, ό,τι έτος κι
        //     αν είναι επιλεγμένο (επαληθεύτηκε: επιλογή 2025 -> PDF 2022). Το
        //     έτος του dropdown κρίνει μόνο αν εμφανίζεται ο σύνδεσμος. Γι'
        //     αυτό κατεβαίνει **μία φορά** και το πραγματικό έτος μπαίνει στο
        //     όνομα του αρχείου από το id, όχι από την επιλογή.
        const ekkSel = 'a[id^="pt1:clPrintEkk"]';
        if (!out.enfiaEkk && (await pop.count(ekkSel))) {
          const ekkId = (await pop.attr(ekkSel, 'id')) || '';
          const ekkYear = (ekkId.match(/clPrintEkk(\d{4})/) || [])[1] || year;
          const dest = path.join(http.dlDir, 'ENFIA_EKK_' + inp.user + '_' + ekkYear + '.pdf');
          http.log('[enfia] λήψη Εκκαθαριστικού (έτος εκκαθ. ' + ekkYear + ')');
          try {
            const dl = await pop.expectDownload(() => pop.click(ekkSel), dest);
            const bytes = fs.existsSync(dest) ? fs.statSync(dest).size : 0;
            out.enfiaEkk = { pdf: path.basename(dest), ekkYear, selectedYear: year, suggested: dl.filename, bytes }; files.push(path.basename(dest));
            http.log('[enfia] ✅ ' + path.basename(dest) + ' (' + bytes + ' b)');
          } catch (e) { out.enfiaEkk = { error: String(e && e.message || e) }; http.log('[enfia] εκκαθ. download error ' + (e && e.message ? e.message : e)); }
        }

        // 5b/6) Ε9 / Περιουσιακή — αυτή ΟΝΤΩΣ ακολουθεί το επιλεγμένο έτος.
        if (wantE9) {
          const perSel = 'a[id="pt1:iterPerStatus:0:cl24"]';
          const perSel2 = (await pop.count(perSel)) ? perSel : 'a:has-text("Εκτύπωση περιουσιακής")';
          if (await pop.count(perSel2)) {
            const dest = path.join(http.dlDir, 'PERIOUSIAKI_' + inp.user + '_' + year + '.pdf');
            http.log('[enfia] λήψη Περιουσιακής/Ε9 ' + year);
            try {
              const dl = await pop.expectDownload(() => pop.click(perSel2), dest);
              const bytes = fs.existsSync(dest) ? fs.statSync(dest).size : 0;
              (out.periousiaki || (out.periousiaki = {}))[year] = { pdf: path.basename(dest), suggested: dl.filename, bytes };
              files.push(path.basename(dest));
              http.log('[enfia] ✅ ' + path.basename(dest) + ' (' + bytes + ' b)');
            } catch (e) { (out.periousiaki || (out.periousiaki = {}))[year] = { error: String(e && e.message || e) }; }
          }
          // Τα grids μόνο για το πρώτο (νεότερο) έτος: είναι η εικόνα της
          // περιουσίας σήμερα, και σε τρία έτη θα τριπλασίαζαν το JSON χωρίς
          // να προσθέτουν κάτι που δεν είναι ήδη στα PDF.
          if (!out.e9Grids) {
            try {
              out.e9Grids = await pop.evaluate(() => {
                const clean = (t) => (t || '').replace(/\s+/g, ' ').trim();
                return [...document.querySelectorAll('div[role="grid"]')].map((g, gi) => ({
                  gridIndex: gi, gridId: g.id || '',
                  headers: [...g.querySelectorAll('[role="columnheader"], th')].map(h => clean(h.innerText)).filter(Boolean),
                  rows: [...g.querySelectorAll('[role="row"]')].map(r => [...r.querySelectorAll('[role="gridcell"], td')].map(c => clean(c.innerText))).filter(r => r.some(Boolean)),
                }));
              });
              out.e9GridsYear = year;
              // Οι σχέσεις ρητά, από το grid που έχει στήλη «Σχέση».
              try {
                const rel = (out.e9Grids || []).find(g => (g.headers || []).some(h => /Σχέση/i.test(h)));
                if (rel) {
                  const idx = (name) => (rel.headers || []).findIndex(h => new RegExp(name, 'i').test(h));
                  const iAfm = idx('Α\\.?Φ\\.?Μ'), iEp = idx('Επώνυμο'), iOn = idx('Όνομα'), iSx = idx('Σχέση');
                  out.relations = (rel.rows || [])
                    .filter(r => iAfm >= 0 && /^\d{9}$/.test(String(r[iAfm] || '').trim()))
                    .map(r => ({
                      afm: String(r[iAfm]).trim(),
                      lastName: iEp >= 0 ? String(r[iEp] || '').trim() : '',
                      firstName: iOn >= 0 ? String(r[iOn] || '').trim() : '',
                      relation: iSx >= 0 ? String(r[iSx] || '').trim() : '',
                    }));
                  http.log('[enfia] σχέσεις: ' + out.relations.map(r => r.relation).join(', '));
                }
              } catch (e) { out.relationsError = String(e && e.message || e); }
              http.log('[enfia] Ε9 grids: ' + (out.e9Grids || []).length + ' (έτος ' + year + ')');
            } catch (e) { out.e9Error = String(e && e.message || e); }
          }
        }
      }

      if (!out.enfiaEkk) { out.enfiaEkk = 'NoEkkButton'; http.log('[enfia] χωρίς κουμπί εκκαθαριστικού (καμία εκκαθάριση σε αυτά τα έτη)'); }

      const jf = 'AADE_enfia_' + inp.user + '.json';
      fs.writeFileSync(path.join(http.dlDir, jf), JSON.stringify(out, null, 2));
      return { ok: files.length > 0, files: [jf, ...files], out };
    });

    if (!res.ok) { http.log('[aade-enfia] FAILED: ' + (res.reason || 'unknown')); return { ok: false, reason: res.reason }; }
    http.log('[aade-enfia] ✅ done');
    return res;
  },
};
