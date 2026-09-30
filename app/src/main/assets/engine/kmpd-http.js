/*
 * lib/kmpd-http.js  --  Κεντρικό Μητρώο Πραγματικών Δικαιούχων (ΚΜΠΔ) — ΚΑΘΑΡΟ HTTP module
 * =============================================================================
 * Κατεβάζει «Εκτύπωση δικαιούχων» + «Εκτύπωση Βεβαίωσης οριστικοποίησης» ΧΩΡΙΣ browser
 * (γρήγορο/ελαφρύ). Zero-dependency (Node 18+ fetch). Drop-in module για άλλη εφαρμογή.
 *
 * Ροή (reverse-engineered, GSIS OAM + JSF/PrimeFaces webapps.gsis.gr/dsae/boregistry):
 *   1) GET boregistry -> OAM redirects -> login.jsp (request_id) -> POST auth_cred_submit
 *      {username,password,request_id,btn_login} -> redirects πίσω -> index.xhtml (+ JSESSIONID, ViewState)
 *   2) POST index.xhtml (partial.ajax, source=form1:enterButton) -> redirect selectrole.xhtml
 *   3) GET selectrole.xhtml -> datatable: βρες <tr data-rk> με ΑΦΜ στα κελιά -> rowKey + επωνυμία
 *   4) POST selectrole.xhtml (source=<Είσοδος button id>, form1:radioDT_selection=rowKey, radioDT_radio=on)
 *      -> redirect entrance.xhtml
 *   5) POST entrance.xhtml (full form) με trigger:
 *        form1:tabViewMainForm:print          -> PDF «Δικαιούχοι»
 *        form1:tabViewMainForm:print_bebaiosi  -> PDF «Βεβαίωση Οριστικοποίησης»
 *
 * ΓΙΑ ΕΤΑΙΡΙΑ: user/pass = ΝΟΜΙΜΟΥ ΕΚΠΡΟΣΩΠΟΥ· afm = ΑΦΜ της εταιρίας (ψάχνεται στο datatable).
 * Ονομασία PDF (ελληνικά): «<Επωνυμία> - <ΑΦΜ> - <Είδος>.pdf».
 *
 * API:  const { fetchBeneficialOwners } = require('./lib/kmpd-http');
 *       const r = await fetchBeneficialOwners({ user, pass, afm, outDir, docs, log });
 *       // r = { ok, entity:{afm,name}, files:[{type,name,path,bytes}], reason? }
 */
'use strict';
const fs = require('fs');
const path = require('path');

const APP = 'https://webapps.gsis.gr/dsae/boregistry';
const UA = 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124 Safari/537.36';
const DOC_TYPES = {
  dikaiouxoi: { trigger: 'form1:tabViewMainForm:print', label: 'Δικαιούχοι' },
  bebaiosi:   { trigger: 'form1:tabViewMainForm:print_bebaiosi', label: 'Βεβαίωση Οριστικοποίησης' },
};

// ---------- cookie jar (per registrable domain, gsis.gr-wide για OAM SSO) ----------
function makeJar() { return {}; }
function regDom(h) { return h.split('.').slice(-2).join('.'); }
function hostOf(u) { return new URL(u).host; }
function storeCookies(jar, url, res) {
  const h = hostOf(url); jar[h] = jar[h] || {};
  const set = (res.headers.getSetCookie && res.headers.getSetCookie()) || [];
  for (const c of set) { const kv = c.split(';')[0]; const i = kv.indexOf('='); if (i > 0) jar[h][kv.slice(0, i).trim()] = kv.slice(i + 1).trim(); }
}
function cookieHeader(jar, url) {
  const host = hostOf(url), rd = regDom(host), parts = [];
  for (const h of Object.keys(jar)) if (host === h || host.endsWith('.' + h) || h.endsWith('.' + host) || regDom(h) === rd)
    for (const [k, v] of Object.entries(jar[h])) parts.push(k + '=' + v);
  return parts.join('; ');
}

// ---------- HTTP (manual redirects, keeps cookies; returns buffer+text) ----------
async function once(jar, method, url, { form, headers } = {}) {
  const h = Object.assign({ 'User-Agent': UA, 'Accept': 'text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8', 'Accept-Language': 'el-GR,el;q=0.9,en;q=0.8' }, headers || {});
  const ck = cookieHeader(jar, url); if (ck) h['Cookie'] = ck;
  let body; if (form) { h['Content-Type'] = 'application/x-www-form-urlencoded'; body = new URLSearchParams(form).toString(); }
  const res = await fetch(url, { method, headers: h, body, redirect: 'manual' });
  storeCookies(jar, url, res);
  return res;
}
async function follow(jar, method, url, opts = {}) {
  let res = await once(jar, method, url, opts);
  let loc = res.headers.get('location'), cur = url, hops = 0;
  while (loc && res.status >= 300 && res.status < 400 && hops < 25) { cur = new URL(loc, cur).toString(); res = await once(jar, 'GET', cur); loc = res.headers.get('location'); hops++; }
  const ct = (res.headers.get('content-type') || '').toLowerCase();
  const cd = (res.headers.get('content-disposition') || '');
  const buf = Buffer.from(await res.arrayBuffer());
  const isPdf = ct.includes('application/pdf') || (buf.length > 4 && buf.slice(0, 4).toString('latin1') === '%PDF');
  return { status: res.status, url: cur, ct, cd, buffer: buf, text: isPdf ? '' : buf.toString('utf8'), isPdf };
}

// ---------- HTML/JSF helpers ----------
const dec = (s) => (s || '').replace(/&amp;/g, '&').replace(/&quot;/g, '"').replace(/&#0*39;/g, "'").replace(/&lt;/g, '<').replace(/&gt;/g, '>');
function viewStateOf(html) {
  return (html.match(/name="javax\.faces\.ViewState"[^>]*value="([^"]*)"/) || html.match(/<update id="[^"]*ViewState[^"]*"><!\[CDATA\[([^\]]*)\]\]/) || [])[1] || '';
}
function partialRedirect(xml) { const m = xml.match(/<redirect url="([^"]*)"/); return m ? dec(m[1]) : ''; }
function stripTags(s) { return dec((s || '').replace(/<[^>]+>/g, ' ')).replace(/\s+/g, ' ').trim(); }
// datatable: rows [{rk, cells[]}]
function dataRows(html) {
  return [...html.matchAll(/<tr\b[^>]*data-rk="([^"]*)"[^>]*>([\s\S]*?)<\/tr>/gi)]
    .map(m => ({ rk: dec(m[1]), cells: [...m[2].matchAll(/<td\b[^>]*>([\s\S]*?)<\/td>/gi)].map(c => stripTags(c[1])) }));
}
// find command button id (name) by visible label (e.g. «Είσοδος»)
function buttonIdByText(html, label) {
  for (const m of html.matchAll(/<(?:button|a)\b[^>]*\bid="([^"]*)"[^>]*>([\s\S]*?)<\/(?:button|a)>/gi)) if (stripTags(m[2]) === label) return dec(m[1]);
  // PrimeFaces.ab({s:"id",...}) onclick fallback
  for (const m of html.matchAll(/PrimeFaces\.ab\(\{s:"([^"]+)"/g)) { /* first as fallback */ return dec(m[1]); }
  return '';
}
function sanitize(s) { return (s || '').replace(/[\\/:*?"<>|\r\n\t]+/g, ' ').replace(/\s+/g, ' ').trim().slice(0, 120); }

// ---------- main ----------
async function fetchBeneficialOwners({ user, pass, afm, outDir, docs, log }) {
  log = log || (() => {});
  afm = String(afm || '').trim();
  if (!/^\d{9}$/.test(afm)) return { ok: false, reason: 'NoAfm' };
  outDir = outDir || '.'; fs.mkdirSync(outDir, { recursive: true });
  const want = (docs && docs.length ? docs : ['dikaiouxoi', 'bebaiosi']).filter(d => DOC_TYPES[d]);
  const jar = makeJar();

  // 1) OAM login
  log('[kmpd-http] GET app -> OAM login');
  let r = await follow(jar, 'GET', APP);
  // login form: request_id + action
  const reqId = (r.text.match(/name="request_id"[^>]*value="([^"]*)"/) || r.text.match(/value="([^"]*)"[^>]*name="request_id"/) || [])[1];
  const action = (r.text.match(/<form[^>]*\baction="([^"]*)"[^>]*>/i) || [])[1];
  if (!reqId || !action) return { ok: false, reason: 'NoLoginForm', url: r.url };
  const loginUrl = new URL(dec(action), r.url).toString();
  log('[kmpd-http] POST auth_cred_submit');
  r = await follow(jar, 'POST', loginUrl, { form: { username: user, password: pass, request_id: reqId, btn_login: '' } });
  if (/name="request_id"/.test(r.text) && /name="password"/.test(r.text)) return { ok: false, reason: 'InvalidCredentials' };
  if (!/boregistry/.test(r.url)) { // ensure we reached the app
    r = await follow(jar, 'GET', APP + '/faces/pages/mainmenu/index.xhtml');
  }
  const base = r.url.replace(/\/faces\/pages\/mainmenu\/.*$/, '/faces/pages/mainmenu/');
  const jsid = (r.url.match(/;jsessionid=([^?]+)/) || [])[1] || '';
  const suf = jsid ? (';jsessionid=' + jsid) : '';
  const U = (name) => base + name + suf;
  let vs = viewStateOf(r.text);
  if (!vs) return { ok: false, reason: 'NoViewState', url: r.url };
  log('[kmpd-http] logged in (ViewState ' + vs.slice(0, 14) + '…)');
  const ajax = { 'Faces-Request': 'partial/ajax', 'X-Requested-With': 'XMLHttpRequest', 'Accept': 'application/xml, text/xml, */*; q=0.01' };

  // 2) enterButton -> selectrole
  r = await follow(jar, 'POST', U('index.xhtml'), { headers: ajax, form: {
    'javax.faces.partial.ajax': 'true', 'javax.faces.source': 'form1:enterButton', 'javax.faces.partial.execute': '@all',
    'javax.faces.partial.render': 'form1', 'form1:enterButton': 'form1:enterButton', 'form1': 'form1', 'javax.faces.ViewState': vs } });
  let red = partialRedirect(r.text); if (viewStateOf(r.text)) vs = viewStateOf(r.text);
  r = await follow(jar, 'GET', red ? new URL(red, base).toString() : U('selectrole.xhtml'));
  if (viewStateOf(r.text)) vs = viewStateOf(r.text);

  // 3) find entity row by AFM
  let rows = dataRows(r.text);
  // αν δεν είναι στη 1η σελίδα, set rows/page=5 + pagination (best-effort)
  let entity = rows.find(row => row.cells.includes(afm));
  if (!entity) {
    // try enlarging page size once
    await follow(jar, 'POST', U('selectrole.xhtml'), { headers: ajax, form: {
      'javax.faces.partial.ajax': 'true', 'javax.faces.source': 'form1:radioDT', 'javax.faces.partial.execute': 'form1:radioDT',
      'javax.faces.partial.render': 'form1:radioDT', 'form1:radioDT': 'form1:radioDT', 'form1:radioDT_pagination': 'true',
      'form1:radioDT_first': '0', 'form1:radioDT_rows': '100', 'form1:radioDT_rppDD': '100', 'form1': 'form1', 'javax.faces.ViewState': vs } });
    r = await follow(jar, 'GET', U('selectrole.xhtml')); if (viewStateOf(r.text)) vs = viewStateOf(r.text);
    rows = dataRows(r.text); entity = rows.find(row => row.cells.includes(afm));
  }
  if (!entity) return { ok: false, reason: 'AfmNotFound' };
  const entityName = entity.cells.find(c => c && c !== afm && /[Α-Ωα-ω]/.test(c) && c.length > 3) || '';
  log('[kmpd-http] entity: ' + afm + ' ' + entityName + ' (rowKey ok)');

  // 4) enter (Είσοδος) -> entrance.xhtml
  const enterId = buttonIdByText(r.text, 'Είσοδος') || 'form1:j_idt51';
  r = await follow(jar, 'POST', U('selectrole.xhtml'), { headers: ajax, form: {
    'javax.faces.partial.ajax': 'true', 'javax.faces.source': enterId, 'javax.faces.partial.execute': '@all',
    'javax.faces.partial.render': 'form1', [enterId]: enterId, 'form1': 'form1', 'form1:radioDT_radio': 'on',
    'form1:radioDT_rppDD': '5', 'form1:radioDT_selection': entity.rk, 'javax.faces.ViewState': vs } });
  red = partialRedirect(r.text); if (viewStateOf(r.text)) vs = viewStateOf(r.text);
  r = await follow(jar, 'GET', red ? new URL(red, base).toString() : U('entrance.xhtml'));
  if (viewStateOf(r.text)) vs = viewStateOf(r.text);

  // 5) prints (full non-ajax form POST -> PDF)
  const files = [];
  for (const d of want) {
    const t = DOC_TYPES[d];
    const form = { 'form1': 'form1', [t.trigger]: '', 'form1:tabViewMainForm:eidosId_filter': '',
      'form1:tabViewMainForm:countryCompId_filter': '', 'form1:tabViewMainForm:countryReprId_filter': '',
      'form1:tabViewMainForm:ownerHolderSelectionDt_selection': '', 'form1:tabViewMainForm_activeIndex': '0',
      'javax.faces.ViewState': vs };
    const pr = await follow(jar, 'POST', U('entrance.xhtml'), { form });
    if (pr.isPdf && pr.buffer.length > 500) {
      const name = sanitize(entityName + ' - ' + afm + ' - ' + t.label) + '.pdf';
      const dest = path.join(outDir, name); fs.writeFileSync(dest, pr.buffer);
      files.push({ type: d, name, path: dest, bytes: pr.buffer.length });
      log('[kmpd-http] ✅ ' + name + ' (' + pr.buffer.length + ' b)');
    } else { log('[kmpd-http] ⚠ ' + d + ': δεν επεστράφη PDF (status ' + pr.status + ' ' + pr.ct + ')'); }
  }
  return { ok: files.length > 0, entity: { afm, name: entityName }, files, reason: files.length ? undefined : 'NoPdf' };
}

module.exports = { fetchBeneficialOwners, DOC_TYPES };

// ---------- CLI ----------
if (require.main === module) {
  const arg = (k, d) => { const i = process.argv.indexOf('--' + k); return i >= 0 ? process.argv[i + 1] : (process.env[k] || d); };
  const user = arg('user', process.env.KMPD_USER), pass = arg('pass', process.env.KMPD_PASS), afm = arg('afm', process.env.KMPD_AFM);
  const outDir = arg('out', process.env.KMPD_OUT || './downloads/kmpd');
  if (!user || !pass || !afm) { console.error('usage: node lib/kmpd-http.js --user U --pass P --afm 999999999 [--out dir]\n(ΕΤΑΙΡΙΑ: user/pass = νόμιμου εκπροσώπου, afm = ΑΦΜ εταιρίας)'); process.exit(1); }
  fetchBeneficialOwners({ user, pass, afm, outDir, log: (...a) => console.log(a.join(' ')) })
    .then(r => { console.log(r.ok ? ('DONE: ' + r.files.map(f => f.name).join(' | ')) : ('FAILED: ' + r.reason)); process.exit(r.ok ? 0 : 1); })
    .catch(e => { console.error('ERROR: ' + (e && e.stack || e)); process.exit(1); });
}
