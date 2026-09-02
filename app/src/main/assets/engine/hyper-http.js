/*
 * lib/hyper-http.js  --  reusable engine for the config-driven process generator
 * =============================================================================
 * The proven building blocks (extracted 1:1 from the working efka-notices-http.js,
 * which is a faithful translation of the decompiled Hyper.Server scraper):
 *   - HyperHttp: cookie jar + once() + follow() (HTTP 3xx AND JSF partial-response)
 *                + postForPdf() (== C# PostDataStream)
 *   - HTML/JSF helpers: decodeHtml, stripTags, between, viewState, formActionOf,
 *                       ownFormAction, hasId, findTabByText, extractUpdate, dataTableRows
 *   - Portal logins: gsisApprove(), efkaNonEmployeeLogin()
 *   - creds/ask + logging
 *
 * A "process config" (configs/<id>.js) composes these. To add a process you write
 * a small config (from the decompiled code) — no new engine code.
 */
'use strict';
const fs = require('fs');
const path = require('path');
const readline = require('readline');

const UA = 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120 Safari/537.36';

// ---------- html helpers ----------
function decodeHtml(s) { return (s || '').replace(/&nbsp;/g, ' ').replace(/&euro;/g, '€').replace(/&#8364;/g, '€').replace(/&amp;/g, '&').replace(/&quot;/g, '"').replace(/&#47;/g, '/').replace(/&#0*47;/g, '/').replace(/&lt;/g, '<').replace(/&gt;/g, '>'); }
function stripTags(s) { return decodeHtml((s || '').replace(/<[^>]+>/g, ' ')).replace(/\s+/g, ' ').trim(); }
function between(s, a, b) { const out = []; let i = 0; while (true) { const p = s.indexOf(a, i); if (p < 0) break; const q = s.indexOf(b, p + a.length); if (q < 0) break; out.push(s.slice(p + a.length, q)); i = q + b.length; } return out; }
function viewState(html, name) {
  name = name || 'jakarta\\.faces\\.ViewState';
  return (html.match(new RegExp('name="' + name + '"[^>]*value="([^"]*)"', 'i'))
       || html.match(new RegExp('value="([^"]*)"[^>]*name="' + name + '"', 'i')) || [])[1] || '';
}
function formActionOf(html, id) {
  const e = id.replace(/[$:.]/g, '\\$&');
  let m = html.match(new RegExp('<form\\b[^>]*action="([^"]*)"[^>]*>(?:(?!</form>)[\\s\\S])*?id="' + e + '"', 'i'));
  if (m) return m[1];
  m = html.match(new RegExp('<form\\b(?:(?!</form>)[\\s\\S])*?id="' + e + '"(?:(?!</form>)[\\s\\S])*?action="([^"]*)"', 'i'));
  return m ? m[1] : '';
}
function ownFormAction(html, id) {
  const e = id.replace(/[$:.]/g, '\\$&');
  const m = html.match(new RegExp('<form[^>]*id="' + e + '"[^>]*action="([^"]*)"', 'i'))
        || html.match(new RegExp('<form[^>]*action="([^"]*)"[^>]*id="' + e + '"', 'i'));
  return m ? m[1] : '';
}
function hasId(html, id) { return new RegExp('id="' + id.replace(/[$:.]/g, '\\$&') + '"').test(html); }
// anchor href whose (last-span/whole) text matches predicate
function anchorHrefByText(html, pred) {
  for (const a of html.matchAll(/<a\b[^>]*href="([^"]*)"[^>]*>([\s\S]*?)<\/a>/gi)) {
    if (pred(stripTags(a[2]))) return decodeHtml(a[1]);
  }
  return '';
}
// PrimeFaces tab header <li class="ui-tabs-header..."> whose text==label -> {dataIndex, href}
function findTabByText(html, label) {
  for (const li of html.matchAll(/<li\b[^>]*class="ui-tabs-header[^"]*"[^>]*>([\s\S]*?)<\/li>/gi)) {
    if (stripTags(li[1]) !== label) continue;
    return {
      dataIndex: (li[0].match(/data-index="([^"]*)"/i) || [])[1] || '',
      href: decodeHtml((li[1].match(/<a\b[^>]*href="([^"]*)"/i) || [])[1] || ''),
    };
  }
  return null;
}
// JSF partial-response: <update id="X"><![CDATA[ html ]]></update>
function extractUpdate(xml, id) {
  const e = id.replace(/[.*+?^${}()|[\]\\:]/g, '\\$&');
  let m = xml.match(new RegExp('<update id="' + e + '"><!\\[CDATA\\[([\\s\\S]*?)\\]\\]></update>', 'i'));
  if (m) return m[1];
  m = xml.match(/<update[^>]*><!\[CDATA\[([\s\S]*?)\]\]><\/update>/i);
  return m ? m[1] : xml;
}
// data rows of a PrimeFaces datatable id (bounded to its ui-datatable-data tbody)
function dataTableRows(html, datatableId) {
  const idx = html.indexOf('id="' + datatableId + '"');
  if (idx < 0) return null;
  const after = html.slice(idx);
  const tb = after.match(/<tbody\b[^>]*class="[^"]*ui-datatable-data[^"]*"[^>]*>([\s\S]*?)<\/tbody>/i);
  const scope = tb ? tb[1] : after.slice(0, 8000);
  return [...scope.matchAll(/<tr\b[^>]*class="[^"]*ui-datatable-(?:even|odd)[^"]*"[^>]*>([\s\S]*?)<\/tr>/gi)]
    .map(r => [...r[1].matchAll(/<td\b[^>]*>([\s\S]*?)<\/td>/gi)].map(t => t[1]));
}

// ---------- interactive prompt ----------
function ask(q, hidden) {
  return new Promise((resolve) => {
    if (!hidden) { const rl = readline.createInterface({ input: process.stdin, output: process.stdout }); rl.question(q, a => { rl.close(); resolve(a); }); return; }
    process.stdout.write(q); const s = process.stdin; const raw = !!s.isRaw; if (s.setRawMode) s.setRawMode(true); s.resume(); let v = '';
    const on = (b) => { for (const ch of b.toString('utf8')) { const c = ch.charCodeAt(0);
      if (c === 13 || c === 10) { s.removeListener('data', on); if (s.setRawMode) s.setRawMode(raw); s.pause(); process.stdout.write('\n'); return resolve(v); }
      else if (c === 3) { process.exit(1); } else if (c === 127 || c === 8) { v = v.slice(0, -1); } else if (c >= 32) { v += ch; } } };
    s.on('data', on);
  });
}
// gather declared inputs from env or prompt; spec: [{key, label, hidden, optional}]
// env var present (even empty) is used as-is; only prompt when truly unset. Optional inputs are
// skipped (default '') in non-interactive runs so piped/env-driven runs don't block on them.
async function gatherInputs(spec) {
  const out = {};
  const interactive = !!process.stdin.isTTY;
  for (const it of spec) {
    let v = process.env[it.env || ('EFKA_' + it.key.toUpperCase())];
    if (v === undefined) {
      if (it.optional && !interactive) { out[it.key] = ''; continue; }
      v = await ask((it.label || it.key) + ': ', !!it.hidden);
    }
    out[it.key] = v;
  }
  return out;
}

// ---------- HTTP engine (cookie jar + manual redirects + JSF partial-response) ----------
class HyperHttp {
  constructor(dlDir) {
    this.jar = {};
    this.dlDir = path.resolve(dlDir || './downloads');
    fs.mkdirSync(this.dlDir, { recursive: true });
    this.logf = path.join(this.dlDir, 'run.log');
    fs.writeFileSync(this.logf, '=== ' + new Date().toISOString() + ' ===\n');
  }
  log(...a) { const s = a.join(' '); console.log(s); try { fs.appendFileSync(this.logf, s + '\n'); } catch (e) {} }
  dump(name, data) { try { fs.writeFileSync(path.join(this.dlDir, name), data); } catch (e) {} }
  _host(u) { return new URL(u).host; }
  setCookie(host, name, value) { this.jar[host] = this.jar[host] || {}; this.jar[host][name] = value; } // manual cookie (== C# AddCookieToHead)
  _store(url, res) {
    const h = this._host(url); this.jar[h] = this.jar[h] || {};
    for (const c of (res.headers.getSetCookie && res.headers.getSetCookie()) || []) {
      const kv = c.split(';')[0]; const i = kv.indexOf('='); if (i > 0) this.jar[h][kv.slice(0, i).trim()] = kv.slice(i + 1).trim();
    }
  }
  _cookie(url) {
    const host = this._host(url); const parts = [];
    for (const h of Object.keys(this.jar))
      if (host === h || host.endsWith(h) || h.endsWith('gsis.gr') || h.endsWith('e-efka.gov.gr') || h.endsWith('idika.org.gr'))
        for (const [k, v] of Object.entries(this.jar[h])) parts.push(k + '=' + v);
    return parts.join('; ');
  }
  async once(method, url, form) {
    const h = { 'User-Agent': UA, 'Accept': 'text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8', 'Accept-Language': 'el-GR,el;q=0.9,en;q=0.8' };
    const ck = this._cookie(url); if (ck) h['Cookie'] = ck;
    // charset=UTF-8 is REQUIRED — the e-EFKA JSF server otherwise transliterates Greek in the
    // partial-response to '?' (== C# sets HttpContent.Headers.ContentType.CharSet = "UTF-8").
    let body; if (form) { h['Content-Type'] = 'application/x-www-form-urlencoded; charset=UTF-8'; body = new URLSearchParams(form).toString(); }
    const res = await fetch(url, { method, headers: h, body, redirect: 'manual' });
    this._store(url, res);
    return res;
  }
  async follow(method, url, form) {
    let res = await this.once(method, url, form);
    let loc = res.headers.get('location'); let cur = url; let hops = 0;
    this.log(`  ${method} ${url} -> ${res.status}${loc ? ' -> ' + loc : ''}`);
    while (loc && res.status >= 300 && res.status < 400 && hops < 25) {
      cur = new URL(loc, cur).toString(); res = await this.once('GET', cur); loc = res.headers.get('location');
      this.log(`  GET ${cur} -> ${res.status}${loc ? ' -> ' + loc : ''}`); hops++;
    }
    let text = await res.text(); let guard = 0;
    while (text.indexOf('<partial-response><redirect url=') !== -1 && guard < 12) {
      const m = text.match(/redirect url="([^"]*)"\s*><\/redirect>/) || text.match(/redirect url="([^"]*)"/);
      if (!m) break;
      cur = new URL(m[1].replace(/&amp;/g, '&'), cur).toString();
      this.log('  [partial-redirect] -> ' + cur);
      res = await this.once('GET', cur); loc = res.headers.get('location');
      while (loc && res.status >= 300 && res.status < 400 && hops < 25) { cur = new URL(loc, cur).toString(); res = await this.once('GET', cur); loc = res.headers.get('location'); this.log(`  GET ${cur} -> ${res.status}`); hops++; }
      text = await res.text(); guard++;
    }
    return { url: cur, status: res.status, text };
  }
  // == C# PostDataStream: POST form, return Buffer if 200 + Content-Type application/pdf
  async postForPdf(url, form) {
    const res = await this.once('POST', url, form);
    const ct = (res.headers.get('content-type') || '').toLowerCase();
    this.log('  [pdf-post] ' + url + ' -> ' + res.status + ' ' + ct);
    if (res.status !== 200) return null;
    const buf = Buffer.from(await res.arrayBuffer());
    // accept by content-type OR by %PDF magic bytes (some apps send application/octet-stream)
    if (ct.includes('application/pdf') || (buf.length > 4 && buf.slice(0, 4).toString('latin1') === '%PDF')) return buf;
    return null;
  }
  // GET a document that may be a PDF or HTML: follows redirects, returns
  // {status, ct, buffer (if application/pdf), text (otherwise)}.
  async getDoc(url, headers) {
    let res = await this.once('GET', url, undefined);
    if (headers) { /* re-issue with extra headers if provided */ }
    let loc = res.headers.get('location'); let cur = url; let hops = 0;
    while (loc && res.status >= 300 && res.status < 400 && hops < 20) { cur = new URL(loc, cur).toString(); res = await this.once('GET', cur); loc = res.headers.get('location'); hops++; }
    const ct = (res.headers.get('content-type') || '').toLowerCase();
    this.log('  [doc] GET ' + url + ' -> ' + res.status + ' ' + ct);
    if (res.status === 200) {
      const buf = Buffer.from(await res.arrayBuffer());
      if (ct.includes('application/pdf') || (buf.length > 4 && buf.slice(0, 4).toString('latin1') === '%PDF')) return { status: 200, ct, buffer: buf };
      return { status: 200, ct, text: buf.toString('utf8') };
    }
    return { status: res.status, ct, text: await res.text() };
  }
  // Generic request with custom headers / JSON body (for REST/BFF APIs, not JSF forms).
  // bodyObj: undefined = no body; object = JSON.stringify + application/json. Returns {status,text,ct}.
  async api(method, url, bodyObj, headers) {
    const h = { 'User-Agent': UA, 'Accept': 'application/json, text/plain, */*', 'Accept-Language': 'el-GR,el;q=0.9,en;q=0.8', ...(headers || {}) };
    const ck = this._cookie(url); if (ck) h['Cookie'] = ck;
    let body;
    if (bodyObj !== undefined) { h['Content-Type'] = h['Content-Type'] || 'application/json'; body = typeof bodyObj === 'string' ? bodyObj : JSON.stringify(bodyObj); }
    const res = await fetch(url, { method, headers: h, body, redirect: 'manual' });
    this._store(url, res);
    const text = await res.text();
    this.log('  [api] ' + method + ' ' + url + ' -> ' + res.status);
    return { status: res.status, text, ct: (res.headers.get('content-type') || '').toLowerCase() };
  }
  // Form-urlencoded request with custom headers (e.g. bearer Authorization), returning either text
  // (XML/JSON) or PDF bytes. `form` = object -> application/x-www-form-urlencoded (UTF-8). For REST
  // apps that guard endpoints with a per-call token header (== C# PostData/PostDataStream + Authorization).
  // `form`: object -> application/x-www-form-urlencoded; string -> sent RAW (default Content-Type
  // application/json, override via headers) == C# StringContent for the "jsonText" body.
  async apiForm(method, url, form, headers) {
    const h = { 'User-Agent': UA, 'Accept': '*/*', 'Accept-Language': 'el-GR,el;q=0.9,en;q=0.8', ...(headers || {}) };
    const ck = this._cookie(url); if (ck) h['Cookie'] = ck;
    let body;
    if (typeof form === 'string') { h['Content-Type'] = h['Content-Type'] || 'application/json'; body = form; }
    else if (form !== undefined) { h['Content-Type'] = h['Content-Type'] || 'application/x-www-form-urlencoded; charset=UTF-8'; body = new URLSearchParams(form).toString(); }
    const res = await fetch(url, { method, headers: h, body, redirect: 'manual' });
    this._store(url, res);
    const buf = Buffer.from(await res.arrayBuffer());
    const ct = (res.headers.get('content-type') || '').toLowerCase();
    const isPdf = ct.includes('application/pdf') || (buf.length > 4 && buf.slice(0, 4).toString('latin1') === '%PDF');
    this.log('  [apiForm] ' + method + ' ' + url + ' -> ' + res.status + ' ' + ct);
    return { status: res.status, ct, buffer: isPdf ? buf : null, text: isPdf ? '' : buf.toString('utf8') };
  }
}

// ---------- portal logins (reusable across EFKA/AADE processes) ----------
// GSIS OAuth2 credential submit + approval; returns final {url,text}. Call after you've
// navigated to the GSIS login page (login.jsp). Handles confirmationForm approval.
async function gsisSubmitAndApprove(http, user, pass) {
  const rl = await http.follow('POST', 'https://oauth2.gsis.gr/oauth2server/j_spring_security_check', { j_username: user, j_password: pass });
  if (rl.url.endsWith('authentication_error=true') || hasId(rl.text, 'j_password')) return { ok: false, reason: 'InvalidCredentials' };
  let page = rl;
  if (/id="confirmationForm"/i.test(rl.text) || /user_oauth_approval/i.test(rl.text)) {
    page = await http.follow('POST', 'https://oauth2.gsis.gr/oauth2server/oauth/authorize', { user_oauth_approval: 'true', 'scope.read': 'true' });
  }
  return { ok: true, page };
}

// e-EFKA "μη μισθωτός" (non-employee) full login via services.e-efka.gov.gr KeyCloak.
// inputs: {user,pass,afm,amka}. Returns {ok, landing, links:{href by label}}.
async function efkaNonEmployeeLogin(http, { user, pass, afm, amka }) {
  const SVC = 'https://services.e-efka.gov.gr/';
  const LAND = SVC + 'ssp.commonservices.home/views/secure/index.xhtml';
  http.log('[efka-login] GET home');
  let r = await http.follow('GET', LAND); http.dump('01_home.html', r.text);
  const act = decodeHtml(formActionOf(r.text, 'social-external-non-employee'));
  if (!act) return { ok: false, reason: 'HomeForm' };
  http.log('[efka-login] enter non-employee -> GSIS');
  await http.follow('POST', new URL(act, SVC).toString(), {});
  http.log('[efka-login] TAXISnet credentials + approval');
  const g = await gsisSubmitAndApprove(http, user, pass);
  if (!g.ok) return g;
  http.dump('02_selectrole.html', g.page.text);
  const roleAction = decodeHtml(ownFormAction(g.page.text, 'kc-form-select-role'));
  if (!roleAction) return { ok: false, reason: 'SelectRole' };
  http.log('[efka-login] select role external-non-employee (afm+amka)');
  const rr = await http.follow('POST', new URL(roleAction, g.page.url).toString(), {
    role: 'external-non-employee', afm, amka, pa: '', ame: '', amoe: '',
    'authorizing-afm': '', 'authorizing-contractor-afm': '', 'submit-role-attribute': 'Υποβολή',
  });
  http.dump('03_landing.html', rr.text);
  if (!hasId(rr.text, 'viewsPanel') || !rr.text.includes('Καλώς ήρθατε')) return { ok: false, reason: 'LandPage' };
  const links = {};
  for (const a of rr.text.matchAll(/<a\b[^>]*href="([^"]*)"[^>]*>([\s\S]*?)<\/a>/gi)) {
    const t = stripTags(a[2]); if (t) links[t] = new URL(decodeHtml(a[1]), SVC).toString();
  }
  http.log('[efka-login] OK (AMKA ' + amka + ')');
  return { ok: true, landing: rr.text, links, SVC };
}

// e-EFKA GGPS login via apps.e-efka.gov.gr/eAccess (== Easy_EFKA_GGPS.Login).
// Different from efkaNonEmployeeLogin (that = services.e-efka.gov.gr KeyCloak).
// inputs: {user,pass,afm,amka}. On success the session also carries GSIS SSO,
// so a subsequent GET to atlas.gov.gr works. Returns {ok, page:{url,text}}.
async function efkaGgpsLogin(http, { user, pass, afm, amka }) {
  const EFKA = 'https://apps.e-efka.gov.gr';
  const eAccessPage = EFKA + '/eAccess/gsis/login.xhtml';
  http.log('[ggps-login] GET eAccess/login.xhtml');
  const r0 = await http.follow('GET', EFKA + '/eAccess/login.xhtml');
  http.dump('01_ggps_login.html', r0.text);
  // page has several  window.location = '...'  ; pick the GSIS oauth authorize link
  const gsis = between(r0.text, "window.location = '", "';")
    .find(l => l.startsWith('https://oauth2.gsis.gr/oauth2server/oauth/authorize?client_id='));
  if (!gsis) return { ok: false, reason: 'GetGsisLink' };
  http.log('[ggps-login] GET GSIS authorize (establish session)');
  await http.follow('GET', decodeHtml(gsis));
  http.log('[ggps-login] TAXISnet credentials + approval');
  const g = await gsisSubmitAndApprove(http, user, pass);
  if (!g.ok) return g;
  const accessText = g.page.text; http.dump('02_ggps_eaccess.html', accessText);
  // find <button type="submit"> whose text == "Είσοδος" -> its id
  let btnId = '';
  for (const b of accessText.matchAll(/<button\b([^>]*)>([\s\S]*?)<\/button>/gi)) {
    if (/type="submit"/i.test(b[1]) && stripTags(b[2]) === 'Είσοδος') { btnId = (b[1].match(/id="([^"]*)"/i) || [])[1] || ''; break; }
  }
  if (!btnId) return { ok: false, reason: 'EisodosButton' };
  const vs = viewState(accessText, 'javax\\.faces\\.ViewState');
  if (!vs) return { ok: false, reason: 'ViewState' };
  http.log('[ggps-login] submit afm+amka (button ' + btnId + ')');
  const rr = await http.follow('POST', eAccessPage, {
    'javax.faces.partial.ajax': 'true', 'javax.faces.source': btnId,
    'javax.faces.partial.execute': 'mainForm', 'javax.faces.partial.render': 'mainForm',
    [btnId]: btnId, 'mainForm': 'mainForm',
    'gsisTabView:afm': afm, 'gsisTabView:amka': amka, 'gsisTabView:afm1': afm,
    'gsisTabView_activeIndex': '0', 'javax.faces.ViewState': vs,
  });
  http.dump('03_ggps_landing.html', rr.text);
  const ok = rr.text.includes('Εφαρμογές του Χρήστη')
    && (rr.text.includes('Χρήστης/Α.Μ.Κ.Α.: ' + amka) || rr.text.includes('Χρήστης/ΑΜΚΑ: ' + amka));
  if (!ok) return { ok: false, reason: 'LandPage' };
  http.log('[ggps-login] OK (AMKA ' + amka + ')');
  return { ok: true, page: rr };
}

// AADE (www1.aade.gr) login via GSIS OAM (login.gsis.gr), == Easy_Aade login (decompiled ~56844).
// Different from the e-EFKA OAuth2/KeyCloak flows: OAM uses username/password/request_id/btn_login.
// inputs: {user,pass}. Returns {ok, page:{url,text}, AADE}. Unlocks all AADE processes.
async function aadeLogin(http, { user, pass }) {
  const AADE = 'https://www1.aade.gr';
  http.log('[aade-login] GET protected home -> OAM login');
  const home = await http.follow('GET', AADE + '/taxisnet/info/protected/home.htm');
  http.dump('01_aade_oam.html', home.text);
  const reqId = (home.text.match(/name="request_id"[^>]*value="([^"]*)"/i)
             || home.text.match(/value="([^"]*)"[^>]*name="request_id"/i) || [])[1];
  if (!reqId) return { ok: false, reason: 'NoRequestId' };
  http.log('[aade-login] POST OAM auth_cred_submit');
  const auth = await http.follow('POST', 'https://login.gsis.gr/oam/server/auth_cred_submit',
    { username: user, password: pass, request_id: reqId, btn_login: '' });
  http.dump('02_aade_authresp.html', auth.text);
  if (/An incorrect Username or Password|Καθορίστηκε λανθασμένο όνομα χρήστη ή κωδικός|κλειδωμένος ή απενεργοποιημένος|auth_fail_exception/i.test(auth.text)
      || (/name="username"/i.test(auth.text) && /name="password"/i.test(auth.text)))
    return { ok: false, reason: 'InvalidCredentials' };
  // manual cookies (== C#): SELF_SERVICE actor role + OAM hint
  http.setCookie('www1.aade.gr', 'gr.taxisnet.infrastructure.common.web.ActorRoleCookieResolver.ACTOR_ROLE', 'SELF_SERVICE');
  http.setCookie('www1.aade.gr', 'OAMAuthnHintCookie', '1');
  http.log('[aade-login] finish webtax/incomefp -> login.done');
  await http.follow('GET', AADE + '/webtax/incomefp/');
  const done = await http.follow('GET', AADE + '/webtax/incomefp/login.done');
  http.dump('03_aade_logindone.html', done.text);
  // logged-in if the protected home no longer shows the OAM login form
  const check = await http.follow('GET', AADE + '/taxisnet/info/protected/home.htm');
  http.dump('04_aade_home.html', check.text);
  if (/name="request_id"/i.test(check.text) && /name="password"/i.test(check.text)) return { ok: false, reason: 'NotLoggedIn' };
  http.log('[aade-login] OK');
  return { ok: true, page: check, AADE };
}

// e-EFKA EMPLOYER login (κωδικοί ΙΚΑ εργοδότη) — == Easy_EFKA_Erg.Login (decompiled ~58312).
// Simple JSF form login (NOT GSIS): GET apps.e-efka.gov.gr/eAccess/login.xhtml -> POST eAccess/j_security_check
// {j_username, j_password} -> landing must contain "Χρήστης: <user>". For ΑΠΔ/καρτέλα εργοδότη (EFKA_ERGOD).
async function efkaErgodLogin(http, { user, pass }) {
  const HOST = 'https://apps.e-efka.gov.gr/';
  http.log('[efka-erg] GET eAccess/login.xhtml');
  await http.follow('GET', HOST + 'eAccess/login.xhtml');
  http.log('[efka-erg] POST j_security_check (κωδικοί ΙΚΑ)');
  const r = await http.follow('POST', HOST + 'eAccess/j_security_check', { j_username: user, j_password: pass });
  http.dump('02_efkaerg_landing.html', r.text);
  if (!r.text.includes('Χρήστης: ' + user)) return { ok: false, reason: 'InvalidCredentials' };
  http.log('[efka-erg] OK (Χρήστης: ' + user + ')');
  return { ok: true, page: r, HOST };
}

// ΚΕΑΟ / e-Debtor (apps.e-efka.gov.gr/eDebtor) login. Based on WebRequestHelper.LoginKeao
// (decompiled ~73018) but with the CURRENT entry: the direct deep-link /eDebtor/secure/index.xhtml
// now 302s to secureError for an unauthenticated user (site changed). So we authenticate via the
// generic eAccess login (== efkaGgpsLogin: /eAccess/login.xhtml -> GSIS «Συνέχεια στο TAXISNET» ->
// PrimeFaces afm «Είσοδος» submit) and THEN enter eDebtor while authenticated.
// inputs: {user,pass, amka?}. Returns {ok, page:{url,text}, afm, EFKA}.
async function keaoLogin(http, { user, pass, amka }) {
  const EFKA = 'https://apps.e-efka.gov.gr';
  http.log('[keao-login] GET eAccess/login.xhtml');
  const r0 = await http.follow('GET', EFKA + '/eAccess/login.xhtml');
  http.dump('01_keao_login.html', r0.text);
  // «Συνέχεια στο TAXISNET» -> the page JS carries  window.location = 'https://oauth2.gsis.gr/...authorize?client_id='
  const gsis = between(r0.text, "window.location = '", "';")
    .find(l => l.startsWith('https://oauth2.gsis.gr/oauth2server/oauth/authorize?client_id='));
  if (!gsis) return { ok: false, reason: 'GetGsisLink' };
  http.log('[keao-login] GET GSIS authorize (Συνέχεια στο TAXISNET)');
  await http.follow('GET', decodeHtml(gsis));
  http.log('[keao-login] TAXISnet credentials + approval');
  const g = await gsisSubmitAndApprove(http, user, pass);
  if (!g.ok) return g;
  const accessText = g.page.text; http.dump('02_keao_eaccess.html', accessText);
  // eAccess afm page: submit button «Είσοδος» -> id, ViewState, gsisTabView:afm1 pre-filled value.
  let btnId = '';
  for (const b of accessText.matchAll(/<button\b([^>]*)>([\s\S]*?)<\/button>/gi)) {
    if (/type="submit"/i.test(b[1]) && stripTags(b[2]) === 'Είσοδος') { btnId = (b[1].match(/id="([^"]*)"/i) || [])[1] || ''; break; }
  }
  if (!btnId) return { ok: false, reason: 'EisodosButton' };
  const vs = viewState(accessText, 'javax\\.faces\\.ViewState');
  if (!vs) return { ok: false, reason: 'ViewState' };
  const afm = (accessText.match(/id="gsisTabView:afm1"[^>]*value="(\d{9})"/i)
    || accessText.match(/name="gsisTabView:afm1"[^>]*value="(\d{9})"/i) || [])[1] || '';
  if (!afm) return { ok: false, reason: 'AfmNotPrefilled' };
  const form = {
    'javax.faces.partial.ajax': 'true', 'javax.faces.source': btnId,
    'javax.faces.partial.execute': 'mainForm', 'javax.faces.partial.render': 'mainForm',
    [btnId]: btnId, 'mainForm': 'mainForm',
    'gsisTabView:afm1': afm, 'gsisTabView_activeIndex': '0', 'javax.faces.ViewState': vs,
  };
  if (/gsisTabView:amka/i.test(accessText) && amka) { form['gsisTabView:afm'] = afm; form['gsisTabView:amka'] = amka; }
  http.log('[keao-login] submit afm ' + afm + ' (button ' + btnId + ')');
  const rr = await http.follow('POST', EFKA + '/eAccess/gsis/login.xhtml', form);
  http.dump('03_keao_portal.html', rr.text);
  if (rr.text.includes('Σύνδεση με κωδικούς TAXISNET')) return { ok: false, reason: 'KeaoLoginFailed' };
  if (rr.text.includes('Αποστολή κωδικού επιβεβαίωσης')) return { ok: false, reason: 'ContactNotConfirmed' };
  // now authenticated -> enter eDebtor app (access.xhtml?si grants for the authenticated session)
  http.log('[keao-login] enter eDebtor (authenticated)');
  const ed = await http.follow('GET', EFKA + '/eDebtor/secure/index.xhtml');
  http.dump('04_keao_edebtor.html', ed.text);
  if (/secureError|Δεν έχετε δικαίωμα/.test(ed.text)) return { ok: false, reason: 'NoEdebtorRights' };
  http.log('[keao-login] OK (ΑΦΜ ' + afm + ')');
  return { ok: true, page: ed, afm, EFKA };
}

// idika EfkaServices login with GSIS OAuth2 == WebRequestHelper.LoginIdikaWithAadeAuth (decompiled ~72743).
// This is the portal for ΕΦΚΑ/ΚΕΑΟ ΟΦΕΙΛΕΣ (Debts.aspx) & χρεώσεις/πιστώσεις (Contributions.aspx),
// personal messages, βεβαιώσεις, ειδοποιητήρια. Flow:
//   GET  {IDIKA}/Account/GsisOAuth2Authenticate.aspx           (ASP.NET WebForms, __VIEWSTATE)
//   POST {IDIKA}/Account/GsisOAuth2Authenticate.aspx {btnGGPSAuth} -> redirect chain -> GSIS oauth2
//   gsisSubmitAndApprove (j_spring_security_check + user_oauth_approval)  -> back to idika ΑΜΚΑ page
//   POST {IDIKA}/Account/SocSecAuthenticate.aspx {ΑΦΜ + ΑΜΚΑ DevExpress callback}
//     -> success iff body starts with  0|/*DX*/({'redirect':'   -> follow that redirect.
// inputs: {user,pass,afm,amka}. Returns {ok, IDIKA}. Session lives in the shared cookie jar.
async function idikaLoginAade(http, { user, pass, afm, amka }) {
  const IDIKA = 'https://www.idika.org.gr/EfkaServices';
  http.log('[idika-login] GET GsisOAuth2Authenticate.aspx');
  const r1 = await http.follow('GET', IDIKA + '/Account/GsisOAuth2Authenticate.aspx');
  http.dump('01_idika_gsisauth.html', r1.text);
  const vs = viewState(r1.text, '__VIEWSTATE');
  const vsg = viewState(r1.text, '__VIEWSTATEGENERATOR');
  if (!vs) return { ok: false, reason: 'NoViewState' };
  http.log('[idika-login] POST btnGGPSAuth -> GSIS');
  await http.follow('POST', IDIKA + '/Account/GsisOAuth2Authenticate.aspx', {
    __EVENTTARGET: '', __EVENTARGUMENT: '', __VIEWSTATE: vs, __VIEWSTATEGENERATOR: vsg,
    'ctl00$TopPanel$NavMenu': '{"selectedItemIndexPath":"","checkedState":""}',
    'ctl00$ContentPlaceHolder1$btnGGPSAuth': 'Συνεχεια στο TaxisNet',
    DXScript: '1_10,1_11,1_22,1_62,1_13,1_14,1_47,1_16,1_23,1_32,1_256,1_41',
    DXCss: '0_1929,1_66,1_67,1_68,0_1934,0_1818,1_283,0_1823,../Content/bootstrap.min.css,../Content/sites.css,https://www.idika.org.gr/cookieconsent/css/cookieconsent.min.css',
  });
  http.log('[idika-login] TAXISnet credentials + approval');
  const g = await gsisSubmitAndApprove(http, user, pass);
  if (!g.ok) return g;
  const amkaPage = g.page.text; http.dump('02_idika_amka.html', amkaPage);
  // ΑΜΚΑ confirmation page (SocSecAuthenticate.aspx): ΑΦΜ + ΑΜΚΑ via DevExpress callback.
  const vs2 = viewState(amkaPage, '__VIEWSTATE');
  if (!vs2) { http.log('[idika-login] ΑΜΚΑ page not reached (δες 02_idika_amka.html)'); return { ok: false, reason: 'NoAmkaPage' }; }
  const P = 'ctl00$ContentPlaceHolder1$ASPxFormLayout1$ASPxFormLayout1_';
  http.log('[idika-login] POST SocSecAuthenticate (ΑΦΜ+ΑΜΚΑ)');
  const res = await http.once('POST', IDIKA + '/Account/SocSecAuthenticate.aspx', {
    __EVENTTARGET: '', __EVENTARGUMENT: '', __VIEWSTATE: vs2,
    __VIEWSTATEGENERATOR: viewState(amkaPage, '__VIEWSTATEGENERATOR'),
    __PREVIOUSPAGE: viewState(amkaPage, '__PREVIOUSPAGE'),
    'ctl00$TopPanel$NavMenu': '{"selectedItemIndexPath":"","checkedState":""}',
    [P + 'E2AFM']: afm,
    [P + 'E1AMKA$State']: '{"validationState":""}',
    [P + 'E1AMKA']: amka,
    DXScript: '1_10,1_11,1_22,1_62,1_13,1_14,1_47,1_16,1_23,1_32,1_59,1_257,1_258,1_256,1_8,1_41',
    DXCss: '0_1929,1_66,1_67,1_68,0_1934,1_284,0_1818,1_283,0_1823,../Content/bootstrap.min.css,../Content/sites.css,https://www.idika.org.gr/cookieconsent/css/cookieconsent.min.css',
    __CALLBACKID: 'ctl00$ContentPlaceHolder1$cbpAMKA',
    __CALLBACKPARAM: 'c0:' + amka,
    __EVENTVALIDATION: viewState(amkaPage, '__EVENTVALIDATION'),
  });
  const txt = await res.text(); http.dump('03_idika_socsec.txt', txt);
  if (!txt.startsWith("0|/*DX*/({'redirect':'")) { http.log('[idika-login] ΑΜΚΑ rejected (δες 03_idika_socsec.txt)'); return { ok: false, reason: 'AmkaRejected' }; }
  const redir = (txt.match(/'redirect':'([^']+)'/) || [])[1];
  if (redir) await http.follow('GET', new URL(decodeHtml(redir), IDIKA + '/').toString());
  http.log('[idika-login] OK (ΑΦΜ ' + afm + ')');
  return { ok: true, IDIKA };
}

// MyAMKA portal (www.amka.gr/app) — server-side OAuth (BFF) + REST.
// Faithful to the SPA: GET /app/oauth/login -> GSIS OAuth2 (j_username/j_password
// + approval) -> callback sets amka.gr session cookie -> GET /app/oauth/token
// returns the bearer token (text). inputs: {user,pass}. Returns {ok, token}.
async function myAmkaLogin(http, { user, pass }) {
  const APP = 'https://www.amka.gr/app';
  http.log('[myamka-login] GET /app/oauth/login -> GSIS');
  await http.follow('GET', APP + '/oauth/login');
  http.log('[myamka-login] TAXISnet credentials + approval');
  const g = await gsisSubmitAndApprove(http, user, pass);
  if (!g.ok) return g;
  http.log('[myamka-login] GET /app/oauth/token');
  const t = await http.api('GET', APP + '/oauth/token', undefined, {});
  const token = (t.text || '').trim();
  if (t.status !== 200 || !token || token.length > 4000 && /<html/i.test(token)) return { ok: false, reason: 'NoToken' };
  http.log('[myamka-login] OK (token ' + token.length + ' chars)');
  return { ok: true, token, APP };
}
// Call a MyAMKA AmkaCitizenAppService method (== SPA apiFetch, Authorization "ctaf2 <token>").
async function myAmkaApi(http, token, method, params) {
  const APP = 'https://www.amka.gr/app';
  const r = await http.api('POST', APP + '/api/AmkaCitizenAppService/' + method, params || {}, { Authorization: 'ctaf2 ' + token });
  if (r.status !== 200) return { ok: false, reason: 'API_' + r.status, raw: r.text };
  try { return { ok: true, data: JSON.parse(r.text), raw: r.text }; } catch (e) { return { ok: false, reason: 'BadJson', raw: r.text }; }
}

// Parse a DevExpress dxDataGrid embedded on the ATLAS InsuranceHistory page.
// == C# AtlasGetGridData: the <script> sibling of #<id> holds
//   .dxDataGrid({"dataSource":{"store":new DevExpress.data.ArrayStore({"data":[...]})},"showBorders"...
// Returns the [...] array (or null if empty/absent).
function atlasGrid(html, id) {
  const idx = html.indexOf('id="' + id + '"');
  const scope = idx >= 0 ? html.slice(idx) : html;
  const A = '.dxDataGrid({"dataSource":{"store":new DevExpress.data.ArrayStore({"data":';
  const B = '})},"showBorders"';
  const p = scope.indexOf(A); if (p < 0) return null;
  const q = scope.indexOf(B, p + A.length); if (q < 0) return null;
  try { const arr = JSON.parse(scope.slice(p + A.length, q)); return Array.isArray(arr) && arr.length ? arr : null; } catch (e) { return null; }
}

module.exports = {
  HyperHttp, ask, gatherInputs,
  decodeHtml, stripTags, between, viewState, formActionOf, ownFormAction, hasId,
  anchorHrefByText, findTabByText, extractUpdate, dataTableRows,
  gsisSubmitAndApprove, efkaNonEmployeeLogin, efkaGgpsLogin, atlasGrid,
  myAmkaLogin, myAmkaApi, aadeLogin, efkaErgodLogin, keaoLogin, idikaLoginAade,
};
