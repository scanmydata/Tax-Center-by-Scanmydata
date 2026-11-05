/*
 * configs/efka-apd.js  --  e-ΕΦΚΑ ΑΠΔ (ΕΦΚΑ + ΤΕΚΑ): Αποδεικτικό Υποβολής + Εκτύπωση Αναλυτικών Εγγραφών
 * =============================================================================
 * Ανά μήνα: οι υποβληθείσες ΑΠΔ (Κανονική/Συμπληρωματική κ.λπ.) με κατάσταση «ΥΠΟΒΟΛΗ» και για καθεμία:
 *   • «Επανέκδοση Αποδεικτικού»       -> PDF «ΑΝΤΙΓΡΑΦΟ ΑΠΟΔΕΙΚΤΙΚΟΥ ΥΠΟΒΟΛΗΣ»   (όπως το TaxSystem)
 *   • «Εκτύπωση Αναλυτικών Εγγραφών»  -> PDF «ΑΝΑΛΥΤΙΚΗ ΠΕΡΙΟΔΙΚΗ ΔΗΛΩΣΗ»       (νέο)
 * + Ταυτότητα Οφειλής της σελίδας (resultTpte, RF…). ΚΑΘΑΡΟ HTTP.
 *
 * Πηγή: Hyper.Server.Tax Easy_EFKA_GovAme (Login / GetAPD / GetApdPdf / LogOut), Kinds APD + APD_Teka.
 *   LOGIN: GET ssp.efka.apd/secure/submissions.xhtml -> KeyCloak -> link #social-taxisnet-employer -> GSIS (j_username/
 *          j_password + approval) -> φόρμα #kc-form-select-role {role:external-employer, afm (προσυμπληρωμένος), ame, …}
 *   ΛΙΣΤΑ: submissions.xhtml #submissionTable_data, td[0]=αρ. υποβολής, td[1]=τύπος, td[2]=«M/YYYY - M/YYYY», td[3]=«ΥΠΟΒΟΛΗ»,
 *          td[4]=links (Τρέχουσα Κατάσταση / Επανέκδοση Αποδεικτικού / Εκτύπωση Αναλυτικών Εγγραφών) — ids δυναμικά (j_idtNNN)
 *          σελιδοποίηση: AJAX submissionTable_pagination/first/rows (όπως το GetAPD)
 *   ΑΠΟΔΕΙΚΤΙΚΟ: AJAX {source:<link>, render:dapyFilea5a:display5a, submissionShow, submissionTable_selection:''}
 *          -> POST {dapyFilea5a, :submissionPeriod5a=<td[2]>, :contractCode5a=<td[0]>, :cmndButtonReSubmit:''} -> PDF
 *   ΑΝΑΛΥΤΙΚΕΣ: πλήρες submit της φόρμας submissionShow + <link>=<link> (PrimeFaces.addSubmitParam(...).submit) -> PDF
 *   ΤΕΚΑ: ίδια ροή στο ssp.efka.apd.teka (SSO από την ίδια συνεδρία KeyCloak).
 *
 * ⚠ e-ΕΦΚΑ: (α) τα POST θέλουν Referer/Origin· (β) ΜΟΝΟ cookies όπως του browser (ίδιος host + Path) — με «ξένα»
 *   cookies (KEYCLOAK_* του /eEfkaExtAuth, gsis_cookie του oauth2.gsis.gr) -> 302 common/access.xhtml «Δεν επιτρέπεται η
 *   πρόσβαση»· (γ) το cookie OAuth_Token_Request_State διαγράφεται (Max-Age=0) μετά το login. Βλ. HyperHttp.browserCookie.
 *
 * INPUTS: user/pass TAXISnet εργοδότη · ame (ΑΜΕ/ΑΜΟΕ κοινών επιχειρήσεων) · period (2026-09 | 2026 | 2026-01..2026-06 ·
 *         κενό = προηγούμενος μήνας) · which (EFKA,TEKA· κενό = και τα δύο) · docs (APODEIKTIKO,ANALYTIKA· κενό = και τα δύο)
 * OUTPUT: APD_<ΑΦΜ>_<αίτημα>.json + APD_<EFKA|TEKA>_<ΑΦΜ>_<YYYY-MM>_<αρ.υποβολής>_<APODEIKTIKO|ANALYTIKA>.pdf
 */
'use strict';
const path = require('path');
const fs = require('fs');

const SVC = 'https://services.e-efka.gov.gr/';
const APPS = { EFKA: SVC + 'ssp.efka.apd/secure/submissions.xhtml', TEKA: SVC + 'ssp.efka.apd.teka/secure/submissions.xhtml' };

function parsePeriods(raw, now = new Date()) {
  const s = (raw || '').trim();
  const ym = (y, m) => y * 100 + m;
  let m;
  if (!s) { const d = new Date(now.getFullYear(), now.getMonth() - 1, 1); const v = ym(d.getFullYear(), d.getMonth() + 1); return { from: v, to: v, label: String(v).replace(/(\d{4})(\d\d)/, '$1-$2') }; }
  if ((m = s.match(/^(\d{4})$/))) return { from: ym(+m[1], 1), to: ym(+m[1], 12), label: m[1] };
  const one = (t) => { const a = t.match(/^(\d{4})[-/.](\d{1,2})$/) || t.match(/^(\d{1,2})[-/.](\d{4})$/); if (!a) return null; return a[1].length === 4 ? ym(+a[1], +a[2]) : ym(+a[2], +a[1]); };
  if ((m = s.match(/^(.+?)\s*(?:\.\.|–|—|έως)\s*(.+)$/))) { const f = one(m[1].trim()), t = one(m[2].trim()); if (f && t) return { from: Math.min(f, t), to: Math.max(f, t), label: s.replace(/\s+/g, '') }; }
  const v = one(s); if (v) return { from: v, to: v, label: String(v).replace(/(\d{4})(\d\d)/, '$1-$2') };
  return null;
}
// «9/2026 - 9/2026» -> 202609 (αρχή περιόδου)
const periodKey = (t) => { const m = (t || '').match(/(\d{1,2})\/(\d{4})/); return m ? +m[2] * 100 + +m[1] : 0; };
const tag = (k) => String(k).replace(/(\d{4})(\d\d)/, '$1-$2');

function formFields(html, id, decode) {
  const i = html.search(new RegExp('<form\\b[^>]*id="' + id + '"')); if (i < 0) return {};
  const seg = html.slice(i, html.indexOf('</form>', i)); const o = {};
  for (const m of seg.matchAll(/<input\b[^>]*>/gi)) {
    const t = m[0]; const n = (t.match(/\bname="([^"]+)"/) || [])[1];
    if (!n || /type="(submit|button|checkbox|radio)"/i.test(t)) continue;
    o[n] = decode((t.match(/\bvalue="([^"]*)"/) || [])[1] || '');
  }
  return o;
}
// γραμμές του πίνακα (πλήρης σελίδα ή CDATA του partial-response)
function tableRows(html, strip) {
  const body = (html.match(/id="submissionTable_data"[^>]*>([\s\S]*?)<\/tbody>/) || [])[1]
    || (html.match(/<update id="submissionTable"><!\[CDATA\[([\s\S]*?)\]\]><\/update>/) || [])[1] || '';
  return body.split(/<tr\b/).slice(1).map(row => {
    const tds = [...row.matchAll(/<td\b[^>]*>([\s\S]*?)<\/td>/gi)].map(x => x[1]);
    const links = {};
    for (const a of (tds[4] || '').matchAll(/<a\b([^>]*)>([\s\S]*?)<\/a>/gi)) { const id = (a[1].match(/id="([^"]+)"/) || [])[1]; if (id) links[strip(a[2])] = id; }
    const clean = (x) => strip((x || '').replace(/<script[\s\S]*?<\/script>/gi, ''));
    return { code: clean(tds[0]), type: clean(tds[1]), period: clean(tds[2]), state: clean(tds[3]), links };
  }).filter(r => r.code);
}
const vsOf = (t) => (t.match(/name="jakarta\.faces\.ViewState"[^>]*value="([^"]+)"/) || t.match(/ViewState:\d+"><!\[CDATA\[([^\]]+)\]\]>/) || [])[1] || null;

module.exports = {
  id: 'efka-apd',
  title: 'e-ΕΦΚΑ ΑΠΔ (ΕΦΚΑ + ΤΕΚΑ): Αποδεικτικό Υποβολής + Εκτύπωση Αναλυτικών Εγγραφών',
  portal: 'services.e-efka.gov.gr ssp.efka.apd / ssp.efka.apd.teka (KeyCloak + TAXISnet εργοδότη)',
  subsystem: 'Hyper.Server Easy_EFKA_GovAme (APD / APD_Teka)',
  actions: ['retrieve', 'download'],
  inputs: [
    { key: 'user', label: 'TAXISnet username εργοδότη', env: 'EFKA_USER' },
    { key: 'pass', label: 'TAXISnet password', env: 'EFKA_PASS', hidden: true },
    { key: 'ame', label: 'ΑΜΕ / ΑΜΟΕ (κοινών επιχειρήσεων)', env: 'EFKA_AME' },
    { key: 'period', label: 'Περίοδος (2026-09 | 2026 | 2026-01..2026-06 · κενό = προηγ. μήνας)', env: 'APD_PERIOD', optional: true },
    { key: 'which', label: 'ΑΠΔ (EFKA,TEKA· κενό = και τα δύο)', env: 'APD_WHICH', optional: true },
    { key: 'docs', label: 'Έγγραφα (APODEIKTIKO,ANALYTIKA· κενό = και τα δύο)', env: 'APD_DOCS', optional: true },
  ],
  parsePeriods, tableRows, periodKey,

  async run(http, inp, lib) {
    const req = parsePeriods(inp.period);
    if (!req) { http.log('Άγνωστη μορφή περιόδου: ' + inp.period); return { ok: false, reason: 'BadPeriod' }; }
    const which = ((inp.which || '').toUpperCase().match(/EFKA|TEKA/g) || ['EFKA', 'TEKA']);
    const docs = ((inp.docs || '').toUpperCase().match(/APODEIKTIKO|ANALYTIKA/g) || ['APODEIKTIKO', 'ANALYTIKA']);
    const strip = lib.stripTags, decode = lib.decodeHtml;
    http.useBrowserCookies = true;   // cookies ανά host+Path όπως ο browser (βλ. ⚠ στην κεφαλίδα)

    // ---- LOGIN (== Easy_EFKA_GovAme.Login) ----
    const r0 = await http.follow('GET', APPS.EFKA);
    const m = r0.text.match(/id="social-taxisnet-employer"[^>]*href="([^"]*)"/i) || r0.text.match(/href="([^"]*)"[^>]*id="social-taxisnet-employer"/i);
    if (!m) { http.dump('apd_01.html', r0.text); return { ok: false, reason: 'NoEmployerLogin' }; }
    await http.follow('GET', new URL(decode(m[1]), SVC).toString());
    const g = await lib.gsisSubmitAndApprove(http, inp.user, inp.pass);
    if (!g.ok) { http.log('GSIS LOGIN FAILED: ' + g.reason); return { ok: false, reason: g.reason }; }
    const roleAct = decode(lib.ownFormAction(g.page.text, 'kc-form-select-role') || '');
    if (!roleAct) { http.dump('apd_02_role.html', g.page.text); return { ok: false, reason: 'NoRoleForm' }; }
    const afm = (g.page.text.match(/id="afm"[^>]*value="(\d+)"/) || [])[1] || '';
    const land = await http.follow('POST', new URL(roleAct, g.page.url).toString(), { role: 'external-employer', afm, amka: '', pa: '', ame: inp.ame || '', amoe: '',
      'authorizing-afm': '', 'authorizing-contractor-afm': '', 'submit-role-attribute': 'Υποβολή' });
    if (!/afmFieldID/.test(land.text) || !land.text.includes(afm)) { http.dump('apd_03_land.html', land.text); http.log('[apd] ο ρόλος εργοδότη δεν έγινε δεκτός (ΑΦΜ/ΑΜΕ;)'); return { ok: false, reason: 'RoleRejected' }; }
    http.log('[apd] εργοδότης ΑΦΜ ' + afm + ' · ΑΜΕ ' + inp.ame + ' · περίοδος ' + req.label);

    const files = [];
    const report = { portal: this.portal, afm, ame: inp.ame, request: req.label, retrievedAt: new Date().toISOString(), apd: {} };
    for (const W of which) {
      try { report.apd[W] = await this.getApd(http, lib, W, W === 'EFKA' ? land : null, req, docs, afm, files); }
      catch (e) { report.apd[W] = { error: String(e && e.message || e) }; http.log('[apd ' + W + '] ' + report.apd[W].error); }
    }
    await this.logout(http, lib);
    const jf = 'APD_' + afm + '_' + req.label + '.json';
    fs.writeFileSync(path.join(http.dlDir, jf), JSON.stringify(report, null, 2));
    http.log('[efka-apd] ✅ saved -> ' + jf + ' (+' + files.length + ' PDF)');
    return { ok: files.length > 0, files: [jf, ...files] };
  },

  async getApd(http, lib, W, landing, req, docs, afm, files) {
    const APP = APPS[W];
    const strip = lib.stripTags, decode = lib.decodeHtml;
    const hdr = () => ({ Referer: APP, Origin: 'https://services.e-efka.gov.gr' });
    const ajax = () => ({ ...hdr(), 'Faces-Request': 'partial/ajax', 'X-Requested-With': 'XMLHttpRequest', Accept: 'application/xml, text/xml, */*; q=0.01' });
    const page = landing && landing.url.startsWith(APP) ? landing : await http.follow('GET', APP);   // ΤΕΚΑ: SSO
    if (!/submissionTable/.test(page.text)) { http.dump('apd_' + W + '_page.html', page.text); return { status: 'NoSubmissionsPage', url: page.url }; }
    let V = vsOf(page.text);
    const showFields = formFields(page.text, 'submissionShow', decode);
    const out = { tautotitaOfeilis: strip((page.text.match(/id="resultTpte"[^>]*>([\s\S]*?)<\//) || [])[1] || '') || null, submissions: [] };
    const pages = +((strip((page.text.match(/class="ui-paginator-current"[^>]*>([\s\S]*?)<\/span>/) || [])[1] || '').match(/από\s+(\d+)/) || [])[1] || 1);
    let rows = tableRows(page.text, strip);
    const perPage = rows.length || 10;

    // ⚠ live 29/10/2026: όταν αλλάζει το είδος αιτήματος (AJAX αποδεικτικού / σελιδοποίηση / submit αναλυτικών) το 1ο
    // POST παίρνει 302 -> common/access.xhtml και η ΑΜΕΣΗ επανάληψη (ίδιο σώμα/ViewState) περνάει -> 1 επανάληψη.
    const post = async (form, headers) => {
      let r;
      for (let attempt = 1; attempt <= 2; attempt++) {
        const res = await http.once('POST', APP, form, headers);
        const buf = Buffer.from(await res.arrayBuffer());
        r = { status: res.status, loc: res.headers.get('location') || '', ct: res.headers.get('content-type') || '', buf };
        if (!(r.status === 302 && /\/common\/access\.xhtml/.test(r.loc))) break;
      }
      return r;
    };
    const isPdf = (r) => r.status === 200 && r.buf.slice(0, 4).toString('latin1') === '%PDF';

    for (let p = 0; p < pages; p++) {
      if (p > 0) {                                   // == GetAPD pagination
        const r = await post({ 'jakarta.faces.partial.ajax': 'true', 'jakarta.faces.source': 'submissionTable', 'jakarta.faces.partial.execute': 'submissionTable',
          'jakarta.faces.partial.render': 'submissionTable', submissionTable: 'submissionTable', submissionTable_pagination: 'true',
          submissionTable_first: String(p * perPage), submissionTable_rows: String(perPage), submissionTable_encodeFeature: 'true',
          submissionShow: 'submissionShow', submissionTable_selection: '', 'jakarta.faces.ViewState': V }, ajax());
        const t = r.buf.toString('utf8');
        if (r.status !== 200) { out.paginationError = 'σελίδα ' + (p + 1) + ': ' + r.status + ' ' + r.loc; break; }
        V = vsOf(t) || V; rows = tableRows(t, strip);
      }
      if (!rows.length) break;
      for (const row of rows) {
        const k = periodKey(row.period);
        if (row.state !== 'ΥΠΟΒΟΛΗ' || k < req.from || k > req.to) continue;
        const sub = { code: row.code, type: row.type, period: row.period, docs: {} };
        const base = 'APD_' + W + '_' + afm + '_' + tag(k) + '_' + row.code;
        if (docs.includes('APODEIKTIKO') && row.links['Επανέκδοση Αποδεικτικού']) {
          const id = row.links['Επανέκδοση Αποδεικτικού'];
          // PrimeFaces.ab({s:<link>, f:"submissionShow", u:"dapyFilea5a:display5a"}) -> ΟΛΑ τα πεδία της submissionShow + partial params
          const a1 = await post({ ...showFields, 'jakarta.faces.partial.ajax': 'true', 'jakarta.faces.source': id, 'jakarta.faces.partial.execute': '@all',
            'jakarta.faces.partial.render': 'dapyFilea5a:display5a', [id]: id, 'jakarta.faces.ViewState': V }, ajax());
          V = vsOf(a1.buf.toString('utf8')) || V;
          const a2 = a1.status === 200 ? await post({ dapyFilea5a: 'dapyFilea5a', 'dapyFilea5a:submissionPeriod5a': row.period, 'dapyFilea5a:contractCode5a': row.code,
            'dapyFilea5a:cmndButtonReSubmit': '', 'jakarta.faces.ViewState': V }, hdr()) : a1;
          if (isPdf(a2)) { const f = base + '_APODEIKTIKO.pdf'; fs.writeFileSync(path.join(http.dlDir, f), a2.buf); files.push(f); sub.docs.APODEIKTIKO = { pdf: f, bytes: a2.buf.length }; }
          else sub.docs.APODEIKTIKO = { error: a2.status + ' ' + (a2.loc || a2.ct) };
        }
        if (docs.includes('ANALYTIKA') && row.links['Εκτύπωση Αναλυτικών Εγγραφών']) {
          const id = row.links['Εκτύπωση Αναλυτικών Εγγραφών'];
          const b = await post({ ...showFields, [id]: id, 'jakarta.faces.ViewState': V }, hdr());
          if (isPdf(b)) { const f = base + '_ANALYTIKA.pdf'; fs.writeFileSync(path.join(http.dlDir, f), b.buf); files.push(f); sub.docs.ANALYTIKA = { pdf: f, bytes: b.buf.length }; }
          else sub.docs.ANALYTIKA = { error: b.status + ' ' + (b.loc || b.ct) };
        }
        out.submissions.push(sub);
        http.log('[apd ' + W + '] ' + row.period + ' · ' + row.code + ' ' + row.type + ' · ' + Object.entries(sub.docs).map(([d, v]) => d + (v.pdf ? ' ✅' : ' ✗ ' + v.error)).join(' · '));
      }
      // ο πίνακας είναι φθίνων κατά περίοδο: σταματάμε όταν όλη η σελίδα είναι πριν από το αίτημα
      if (rows.every(r => periodKey(r.period) && periodKey(r.period) < req.from)) break;
    }
    if (!out.submissions.length) http.log('[apd ' + W + '] καμία υποβολή για ' + req.label);
    return out;
  },

  // == Easy_EFKA_GovAme.LogOut (μία ενεργή συνεδρία ανά χρήστη)
  async logout(http, lib) {
    try {
      const APP = APPS.EFKA; const h = (await http.follow('GET', APP)).text;
      const lo = (h.match(/<a\b[^>]*id="(logoutForm:[^"]+)"[^>]*>(?:(?!<\/a>)[\s\S])*?Αποσύνδεση/) || [])[1]; const V = vsOf(h);
      if (!lo || !V) return;
      await http.once('POST', APP, { 'jakarta.faces.partial.ajax': 'true', 'jakarta.faces.source': lo, 'jakarta.faces.partial.execute': '@all', [lo]: lo, logoutForm: 'logoutForm', 'jakarta.faces.ViewState': V },
        { Referer: APP, Origin: 'https://services.e-efka.gov.gr', 'Faces-Request': 'partial/ajax', 'X-Requested-With': 'XMLHttpRequest' });
      http.log('[apd] αποσύνδεση');
    } catch (e) { /* best effort */ }
  },
};
