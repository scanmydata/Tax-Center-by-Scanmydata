/*
 * configs/efka-teka-check.js  --  Ε.Φ.Κ.Α. - Υπηρεσία Επιβεβαίωσης Υπαγωγής ΤΕΚΑ (Έλεγχος Υπαγωγής Μισθωτών στο ΤΕΚΑ)
 * =============================================================================
 * Για κάθε ΑΜΚΑ εργαζομένου: υπάγεται στο ΤΕΚΑ; + ημερομηνία έναρξης ασφάλισης στο ΤΕΚΑ + αριθμός αναζητήσεων ημέρας.
 * ΟΛΑ τα ΑΜΚΑ σε ΜΙΑ συνεδρία (ένα login). ΚΑΘΑΡΟ HTTP.
 *
 * TaxSystem: EpsilonSubmit `YphresEpivYpagTeka` — μόνο login (j_username/j_password κωδικών ΙΚΑ + PrimeFaces «Είσοδος»).
 * Hyper.Server.Base.Scraper: IkaService.CheckTEKA / CheckTEKAWithRetry(eCheckTEKASessionID, gsis_cookie, amka, 2)
 * (decompiled ~5540-5900· login ~45800-46360: eAccess -> GET checkTEKA.xhtml -> cookies eCheckTEKASessionID+gsis_cookie).
 *
 * ΡΟΗ (ανά ΑΜΚΑ):
 *   GET  https://apps.e-efka.gov.gr/eCheckTEKA/secure/checkTEKA.xhtml          -> javax.faces.ViewState (αλλιώς: login)
 *   POST ίδιο URL (PrimeFaces partial/ajax):
 *        javax.faces.partial.ajax=true, javax.faces.source=<κουμπί>, javax.faces.partial.execute=@all,
 *        javax.faces.partial.render=ActionForm, <κουμπί>=<κουμπί>, CheckTEKAForm=CheckTEKAForm,
 *        CheckTEKAForm:CheckTEKA-criteria-AMKA_ID=<ΑΜΚΑ>, javax.faces.ViewState
 *        κουμπί = το CheckTEKAForm:* με «Αναζήτηση»/«Έλεγχος Ένταξης» (fallback CheckTEKAForm:j_idt143)
 *   POST ίδιο URL: source=<ActionForm:*> (fallback ActionForm:j_idt180), ActionForm=ActionForm, ViewState (από την 1η απάντηση)
 *   Απάντηση: «Δεν βρέθηκε ο AMKA!» -> AMKA_NOT_FOUND · «Αριθμός αναζητήσεων ημέρας: N» ·
 *             «Ο συγκεκριμένος ασφαλισμένος δεν υπάγεται στο ΤΕΚΑ» -> NOT_SUBJECT · αλλιώς SUBJECT +
 *             «Ημερομηνία έναρξης ασφάλισης στο ΤΕΚΑ : dd/mm/yyyy». ViewExpired -> επανάληψη.
 *
 * ⚠ LIVE 29/10/2026 (δοκιμαστικός λογαριασμός ΙΚΑ): login + GET OK, αλλά ΚΑΘΕ POST αναζήτησης -> 302 eCheckTEKA/secure/null/error.xhtml?e=appreq
 *   — ΙΔΙΟ και από headless Chromium με το κανονικό UI. Ο ΙΔΙΟΣ λογαριασμός ΔΟΥΛΕΥΕΙ από τον browser του χρήστη (Ελλάδα):
 *   «Έλεγχος Ένταξης στο ΤΕΚΑ» -> οθόνη «Υποβολή Ελέγχου» (= 2ο AJAX ActionForm) -> μηνύματα «Αριθμός αναζητήσεων ημέρας: N»,
 *   «Ο συγκεκριμένος ασφαλισμένος (δεν) υπάγεται στο ΤΕΚΑ», «Ημερομηνία έναρξης ασφάλισης στο ΤΕΚΑ : dd/mm/yyyy».
 *   Το αίτημα είναι ίδιο· η απόρριψη συνδέεται με την ΠΡΟΕΛΕΥΣΗ (cloud IP ΗΠΑ πίσω από F5 — cookie f5_cspm) ->
 *   τρέξτε το από υπολογιστή/server στην Ελλάδα (όπως το TaxSystem). Εδώ καταγράφεται ως SERVICE_REJECTED (appreq).
 *
 * INPUTS: user/pass (κωδικοί ΙΚΑ εργοδότη) · amka (ένα ή πολλά, κόμμα/κενό) · ame (προαιρετικό, ΑΜΟΕ — μόνο για το αρχείο·
 *         η φόρμα δεν το ζητά).
 * OUTPUT: TEKA_CHECK_<user>.json (+ dumps r1_/r2_<ΑΜΚΑ>.xml).
 */
'use strict';
const path = require('path');
const fs = require('fs');

const HOST = 'https://apps.e-efka.gov.gr';
const PAGE = HOST + '/eCheckTEKA/secure/checkTEKA.xhtml';
const AJAX = { 'Faces-Request': 'partial/ajax', 'X-Requested-With': 'XMLHttpRequest', Origin: HOST, Referer: PAGE };

// ViewState από πλήρη σελίδα ή από partial-response <update id="...ViewState..."><![CDATA[..]]>
function viewState(t) {
  return (t.match(/javax\.faces\.ViewState:1" value="([^"]+)"/) || t.match(/name="javax\.faces\.ViewState"[^>]*value="([^"]+)"/)
    || t.match(/<update id="[^"]*javax\.faces\.ViewState[^"]*"><!\[CDATA\[([^\]]+)\]\]>/) || [])[1] || null;
}
// PrimeFaces κουμπί με prefix id και κείμενο (π.χ. «Υποβολή Ελέγχου» — ΟΧΙ το πρώτο, που είναι «Επιστροφή»)
// -> {id, source (s:), render (u:)} από το onclick PrimeFaces.ab({s:"..",u:".."}) — ακριβώς ό,τι στέλνει ο browser.
function findButton(html, prefix, textRe) {
  const re = new RegExp('<button\\b([^>]*\\bid="(' + prefix.replace(/[:.]/g, '\\$&') + '[^"]+)"[^>]*)>([\\s\\S]*?)<\\/button>', 'gi');
  for (const m of html.matchAll(re)) {
    if (textRe && !textRe.test(m[3].replace(/<[^>]+>/g, ' '))) continue;
    const oc = decode((m[1].match(/onclick="([^"]*)"/) || [])[1] || '');
    return { id: m[2], source: (oc.match(/\bs:"([^"]+)"/) || [])[1] || m[2], render: (oc.match(/\bu:"([^"]+)"/) || [])[1] || null };
  }
  return null;
}
const buttonId = (html, prefix, textRe) => (findButton(html, prefix, textRe) || {}).id || null;
const decode = (s) => s.replace(/&quot;/g, '"').replace(/&#39;/g, "'").replace(/&amp;/g, '&').replace(/&lt;/g, '<').replace(/&gt;/g, '>');
// όλα τα πεδία μιας φόρμας (μέσα σε partial-response CDATA ή πλήρη σελίδα) -> {name: value}
function formFields(html, formId) {
  const i = html.search(new RegExp('<form\\b[^>]*id="' + formId + '"'));
  if (i < 0) return {};
  const seg = html.slice(i, html.indexOf('</form>', i) + 7);
  const out = {};
  for (const m of seg.matchAll(/<input\b[^>]*>/gi)) {
    const t = m[0]; const n = (t.match(/\bname="([^"]+)"/) || [])[1]; if (!n || /type="(submit|button|checkbox|radio)"/i.test(t)) continue;
    out[n] = decode((t.match(/\bvalue="([^"]*)"/) || [])[1] || '');
  }
  return out;
}
const between = (s, a, b) => { const i = s.indexOf(a); if (i < 0) return ''; const j = s.indexOf(b, i + a.length); return j < 0 ? '' : s.slice(i + a.length, j); };

// ανάλυση τελικής απάντησης (== CheckTEKA). ΜΟΝΟ τα μηνύματα αποτελέσματος μετράνε — η οθόνη επιβεβαίωσης περιέχει
// κι αυτή «Έλεγχος Ένταξης στο ΤΕΚΑ», οπότε η απλή λέξη «ΤΕΚΑ» ΔΕΝ είναι αποτέλεσμα.
function parseResult(t) {
  if (/Δεν βρέθηκε ο AMKA!/.test(t)) return { status: 'AMKA_NOT_FOUND' };
  const notSubject = /ασφαλισμένος δεν υπάγεται στο ΤΕΚΑ/.test(t);
  const subject = !notSubject && /ασφαλισμένος υπάγεται στο ΤΕΚΑ/.test(t);
  const n = parseInt(between(t, 'Αριθμός αναζητήσεων ημέρας: ', '<'), 10);
  if (!notSubject && !subject) return { status: 'NO_RESULT', searchesToday: Number.isFinite(n) ? n : null };
  const start = subject ? (between(t, 'Ημερομηνία έναρξης ασφάλισης στο ΤΕΚΑ : ', '<').match(/\d{2}\/\d{2}\/\d{4}/) || [])[0] || null : null;
  return { status: subject ? 'SUBJECT' : 'NOT_SUBJECT', subjectToTeka: subject, tekaStartDate: start, searchesToday: Number.isFinite(n) ? n : null };
}

module.exports = {
  id: 'efka-teka-check',
  title: 'Ε.Φ.Κ.Α. — Επιβεβαίωση Υπαγωγής ΤΕΚΑ ανά ΑΜΚΑ (πολλά ΑΜΚΑ σε μία συνεδρία)',
  portal: 'e-EFKA eCheckTEKA (eAccess, κωδικοί ΙΚΑ εργοδότη)',
  subsystem: 'Hyper.Server IkaService.CheckTEKA (+ EpsilonSubmit YphresEpivYpagTeka login)',
  actions: ['check'],
  inputs: [
    { key: 'user', label: 'Κωδικός ΙΚΑ (username εργοδότη)', env: 'IKA_USER' },
    { key: 'pass', label: 'Συνθηματικό ΙΚΑ', env: 'IKA_PASS', hidden: true },
    { key: 'amka', label: 'ΑΜΚΑ εργαζομένων (κόμμα για πολλά)', env: 'TEKA_AMKA' },
    { key: 'ame', label: 'ΑΜΟΕ/ΑΜΕ εργοδότη (προαιρετικό, μόνο για το αρχείο)', env: 'IKA_AME', optional: true },
  ],
  parseResult, viewState, buttonId, findButton, formFields,

  async run(http, inp, lib) {
    const amkas = [...new Set(String(inp.amka || '').split(/[\s,;]+/).map(s => s.trim()).filter(Boolean))];
    const bad = amkas.filter(a => !/^\d{11}$/.test(a));
    if (!amkas.length || bad.length) { http.log('Μη έγκυρα ΑΜΚΑ: ' + (bad.join(', ') || '(κενό)')); return { ok: false, reason: 'BadAmka' }; }
    let L = await lib.efkaErgodLogin(http, inp);
    if (!L.ok) { http.log('EFKA LOGIN FAILED: ' + L.reason); return { ok: false, reason: L.reason }; }
    // είσοδος στην εφαρμογή από το σημείο του μενού (SSO si= μέσω eAccess)
    await http.follow('GET', HOST + '/eCheckTEKA');

    const report = { portal: this.portal, user: inp.user, ame: inp.ame || null, checkedAt: new Date().toISOString(), results: [] };
    for (const amka of amkas) {
      let r = null;
      for (let attempt = 1; attempt <= 2; attempt++) {                 // == CheckTEKAWithRetry(maxAttempts = 2)
        try { r = await this.checkOne(http, amka); }
        catch (e) { r = { status: 'ERROR', error: String(e && e.message || e) }; }
        if (!/^(ERROR|SESSION_LOST|SERVICE_REJECTED)$/.test(r.status) || attempt === 2) break;
        http.log('[teka] ' + amka + ': ' + r.status + ' — νέα σύνδεση και επανάληψη');
        // μετά το appreq το eCheckTEKASessionID μένει «χαλασμένο» (και το GET πάει σε error) -> νέα συνεδρία εφαρμογής
        for (const h of Object.keys(http.jar)) delete http.jar[h].eCheckTEKASessionID;
        L = await lib.efkaErgodLogin(http, inp);
        if (!L.ok) break;
        await http.follow('GET', HOST + '/eCheckTEKA');
      }
      report.results.push({ amka, ...r });
      http.log('[teka] ' + amka + ': ' + r.status + (r.tekaStartDate ? ' · έναρξη ΤΕΚΑ ' + r.tekaStartDate : '') + (r.searchesToday != null ? ' · αναζητήσεις ημέρας ' + r.searchesToday : '') + (r.error ? ' · ' + r.error : ''));
    }
    const jf = 'TEKA_CHECK_' + inp.user + '.json';
    fs.writeFileSync(path.join(http.dlDir, jf), JSON.stringify(report, null, 2));
    http.log('[efka-teka-check] ✅ saved -> ' + jf);
    const okAny = report.results.some(x => /^(SUBJECT|NOT_SUBJECT|AMKA_NOT_FOUND)$/.test(x.status));
    return { ok: okAny, reason: okAny ? undefined : (report.results[0] && report.results[0].status), files: [jf], summary: report.results.map(x => ({ amka: x.amka, status: x.status })) };
  },

  async checkOne(http, amka) {
    const p = await http.follow('GET', PAGE);
    if (/\/eAccess\/login\.xhtml/.test(p.url)) return { status: 'SESSION_LOST', error: 'redirect σε login' };
    if (/error\.xhtml/.test(p.url)) return { status: 'SERVICE_REJECTED', error: 'GET -> ' + p.url };
    let V = viewState(p.text);
    if (!V) return { status: 'ERROR', error: 'Δεν βρέθηκε ViewState στην αρχική σελίδα' };
    const post = async (form, tag) => {
      const res = await http.once('POST', PAGE, form, AJAX);
      const loc = res.headers.get('location') || '';
      const text = await res.text();
      http.dump(tag + '_' + amka + '.xml', text);
      return { status: res.status, loc, text };
    };
    const redirected = (r, step) => r.status >= 300 && r.status < 400
      ? { status: /appreq/.test(r.loc) ? 'SERVICE_REJECTED' : 'ERROR', error: step + ' -> ' + r.status + ' ' + r.loc } : null;

    // 1) «Έλεγχος Ένταξης στο ΤΕΚΑ» -> οθόνη επιβεβαίωσης (ΧΡΗΣΤΗΣ / ΑΜΚΑ / Επιστροφή / Υποβολή Ελέγχου)
    const b1 = findButton(p.text, 'CheckTEKAForm:', /Έλεγχος Ένταξης|Αναζήτηση/) || { id: 'CheckTEKAForm:j_idt143', source: 'CheckTEKAForm:j_idt143', render: 'viewsPanel' };
    const r1 = await post({ 'javax.faces.partial.ajax': 'true', 'javax.faces.source': b1.source, 'javax.faces.partial.execute': '@all',
      'javax.faces.partial.render': b1.render || 'viewsPanel', [b1.id]: b1.id, CheckTEKAForm: 'CheckTEKAForm',
      'CheckTEKAForm:CheckTEKA-criteria-AMKA_ID': amka, 'javax.faces.ViewState': V }, 'r1');
    const e1 = redirected(r1, 'search 1'); if (e1) return e1;
    if (/ViewExpiredException|viewExpired/.test(r1.text)) return { status: 'ERROR', error: 'ViewState expired (search 1)' };
    const first = parseResult(r1.text);
    if (first.status !== 'NO_RESULT') return first;                       // π.χ. «Δεν βρέθηκε ο AMKA!»
    V = viewState(r1.text) || V;

    // 2) «Υποβολή Ελέγχου» στο ActionForm -> μηνύματα αποτελέσματος
    const b2 = findButton(r1.text, 'ActionForm:', /Υποβολή Ελέγχου|Υποβολή/);
    if (!b2) return { status: 'ERROR', error: 'Δεν βρέθηκε το κουμπί «Υποβολή Ελέγχου» στην απάντηση (δες r1_' + amka + '.xml)' };
    const f2 = { ...formFields(r1.text, 'ActionForm'), 'javax.faces.partial.ajax': 'true', 'javax.faces.source': b2.source,
      'javax.faces.partial.execute': '@all', 'javax.faces.partial.render': b2.render || 'ActionForm', [b2.id]: b2.id,
      ActionForm: 'ActionForm', 'javax.faces.ViewState': V };
    const r2 = await post(f2, 'r2');
    const e2 = redirected(r2, 'search 2'); if (e2) return e2;
    if (/ViewExpiredException|viewExpired/.test(r2.text)) return { status: 'ERROR', error: 'ViewState expired (search 2)' };
    const res = parseResult(r2.text);
    if (res.status === 'NO_RESULT') return { status: 'ERROR', error: 'Χωρίς μήνυμα αποτελέσματος μετά την «Υποβολή Ελέγχου» (δες r2_' + amka + '.xml)', searchesToday: res.searchesToday };
    return res;
  },
};
