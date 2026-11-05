/*
 * lib/tpd-http.js  --  curl transport + TPD e-Services (e-services.tpd.gr) login
 * =============================================================================
 * WHY curl: Node 24's TLS stack fails the handshake on this host
 * (ERR_SSL_PACKET_LENGTH_TOO_LONG / TLS1.3 stalls); `curl.exe --tlsv1.2 --http1.1`
 * is the proven path (see out/processes/TPD-eServices/PlhrTelwnDiaf.md — the throwaway probe/tracer
 * scripts that discovered this chain were folded in here and deleted from runner/scripts/).
 * The host also throws intermittent connect resets (code=000) => every call retries
 * until it gets a real HTTP status.
 *
 * CurlHttp mirrors the HyperHttp surface a config needs (follow/api/postForPdf/log/dump)
 * but shells curl, so the SAME config shape runs on TLS-broken gov hosts.
 *
 * TPD e-Services login (OAuth2 GSIS/TAXIS, verified live 2026-10-29):
 *   1) GET  {E}/user-service/oauth2/authorization/taxis        -302-> GSIS oauth/authorize
 *        (client_id LVSMI2R9045, state, redirect_uri {E}/user-service/login/oauth2/code/taxis)
 *   2) GET  oauth/authorize                                    -302-> login.jsp
 *   3) POST oauth2server/j_spring_security_check {j_username,j_password}
 *        -> 302 back to the SAME oauth/authorize URL (no failedLogin on the page)
 *   4) GET  authorize -> consent page <form id="confirmationForm" action="/oauth2server/oauth/authorize">
 *        hidden inputs user_oauth_approval=true, scope.read=true  -> POST those fields
 *        (THE STEP THE OLD STANDALONE OMITTED -> endpoints then 403'd)
 *   5) follow Location -> {E}/user-service/login/oauth2/code/taxis?code=..&state=..  -> e-services
 *      sets its own SESSION; landing authenticated.
 * Endpoints need those cookies; GET only unless a config says otherwise.
 */
'use strict';
const fs = require('fs');
const path = require('path');
const execFile = require('child_process').execFile;

const UA = 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120 Safari/537.36';
const CURL_EXE = process.env.CURL_EXE || 'curl.exe';

class CurlHttp {
  constructor(dlDir) {
    this.dlDir = path.resolve(dlDir || './downloads');
    fs.mkdirSync(this.dlDir, { recursive: true });
    this.logf = path.join(this.dlDir, 'run.log');
    fs.writeFileSync(this.logf, '=== ' + new Date().toISOString() + ' ===\n');
    this.cj = path.join(this.dlDir, 'cj.txt'); // netscape cookie jar shared by all calls
    fs.rmSync(this.cj, { force: true });
  }
  log(...a) { const s = a.join(' '); console.log(s); try { fs.appendFileSync(this.logf, s + '\n'); } catch (e) {} }
  dump(name, data) { try { fs.writeFileSync(path.join(this.dlDir, name), typeof data === 'string' ? data : Buffer.from(data)); } catch (e) {} }
  // one curl call; retries connect resets/timeouts until a real status appears
  async call(args, { tries = 20, label = 'call' } = {}) {
    let wait = 1200;
    for (let i = 1; i <= tries; i++) {
      const r = await new Promise((Pr) => execFile(CURL_EXE, args, { maxBuffer: 128e6, timeout: 60000 }, (_e, stdout, stderr) => {
        const m = String(stdout).match(/code=(\d+)/);
        Pr({ code: m ? +m[1] : 0, why: _e ? (String(_e.code) + ' ' + String(_e.code === 0 ? (_e.message || '') : (_e.stderr || ''))) : '' });
      }));
      if (r.code >= 100) return r;
      this.log('  [' + label + ' retry ' + i + '] code=0 (' + r.why.trim() + ')');
      await new Promise((p) => setTimeout(p, wait));
      wait = Math.min(wait * 1.6, 12000);
    }
    return { code: 0, why: 'unreachable after ' + tries + ' tries' };
  }
  _base() { return ['--tlsv1.2', '--http1.1', '-m', '30', '-sk', '-A', UA]; }
  // GET/POST WITHOUT -L: we follow hops ourselves so we can see each page (forms/consent)
  // returns {status, url, text, headersText, location (absolute or '')}
  async once(method, url, { form = null, json = null, accept = null, referer = null, out = null, raw = null } = {}) {
    const args = this._base().concat(['-b', this.cj, '-c', this.cj, '-D', this.dlDir + '/.h',
      '-o', out || this.dlDir + '/.b', '-w', 'code=%{http_code}']);
    if (method !== 'GET') args.push('-X', method);
    if (form) for (const [k, v] of Object.entries(form)) args.push('--data-urlencode', k + '=' + v);
    if (json !== null) args.push('--data', JSON.stringify(json), '-H', 'Content-Type: application/json');
    if (raw) args.push('--data', raw, '-H', 'Content-Type: ' + (/^[{[]/.test(raw) ? 'application/json' : 'application/x-www-form-urlencoded'));
    if (accept) args.push('-H', 'Accept: ' + accept);
    if (referer) args.push('-H', 'Referer: ' + referer);
    args.push(url);
    const r = await this.call(args, { label: method + ' ' + url });
    const h = fs.readFileSync(this.dlDir + '/.h', 'utf8');
    const loc = [...h.matchAll(/^(?:[Ll]ocation):\s*(\S+)\s*$/gim)].map((m) => m[1].trim());
    const body = out ? (fs.existsSync(out || this.dlDir + '/.b') ? fs.readFileSync(out || this.dlDir + '/.b') : Buffer.from('')) : (await Promise.resolve(fs.readFileSync(this.dlDir + '/.b')));
    if (this._quiet !== true) this.log('  [' + (method) + '] ' + url + ' -> ' + r.code);
    return { status: r.code, url, text: Buffer.from(body).toString('utf8'), buffer: Buffer.from(body), headersText: h, location: loc.length ? loc[loc.length - 1] : '' };
  }
  // follow redirects (and GSIS openredirect / JSF partial-redirect pages) until a terminal page
  async follow(method, url, opts = {}) {
    let cur = url; let hops = 0; let r;
    while (true) {
      r = await this.once(method, cur, opts);
      const next = await this._nextUrl(r, cur);
      if (next && hops < 25) { cur = next; hops++; method = 'GET'; opts = Object.assign(Object.assign({}, opts), { referer: url }); continue; }
      return r;
    }
  }
  // resolve the next hop: HTTP Location, or an in-page <openredirect?to=> / JSF partial-redirect
  async _nextUrl(r, cur) {
    if (r.location) { try { return new URL(r.location, cur).toString(); } catch (e) { return '' } }
    const o = r.text.match(/[?&]to=([^"'\s&<>]+)/);
    if (/openredirect/i.test(r.text) && o) { try { return new URL(decodeURIComponent(o[1]), cur).toString(); } catch (e) { return ''; } }
    const p = r.text.match(/<partial-response><redirect url="([^"]*)"\s*><\/redirect>/);
    if (p) { try { return new URL(p[1].replace(/&amp;/g, '&'), cur).toString(); } catch (e) { return ''; } }
    return '';
  }
  // JSON API GET (endpoints want Accept: application/json)
  async api(method, url, bodyObj, headers) {
    const r = await this.once(method, url, { json: bodyObj, accept: 'application/json, text/plain, */*', referer: (headers && headers.referer) || null });
    return { status: r.status, text: r.text, ct: 'application/json' };
  }
  async apiForm(method, url, form, headers) {
    const r = await this.once(method, url, { form: typeof form === 'string' ? null : form, raw: typeof form === 'string' ? form : null, referer: (headers && headers.referer) || null });
    return { status: r.status, ct: '', buffer: r.buffer, text: r.text };
  }
  async postForPdf(url, form) {
    const r = await this.once('POST', url, { form });
    return (r.buffer.length > 4 && r.buffer.slice(0, 4).toString('latin1') === '%PDF') ? r.buffer : null;
  }
}

// ---------- TPD e-Services login (see header) ----------
// inputs {user,pass}. Returns {ok, page, E}. On ok the cookie jar carries e-services SESSION.
async function tpdEservicesLogin(http, { user, pass }, E) {
  E = E || (process.env.TPD_HOST || 'https://e-services.tpd.gr');
  http.log('[tpd-login] kick oauth/authorize');
  const kick = await http.once('GET', E + '/user-service/oauth2/authorization/taxis', { accept: 'text/html', referer: E + '/login' });
  http.dump('01_kick.html', kick.text);
  const auth = kick.location ? new URL(kick.location, E + '/').toString() : '';
  if (!auth) return { ok: false, reason: 'NoAuthorizeRedirect' };
  http.log('[tpd-login] authorize page (login.jsp)');
  let page = await http.follow('GET', auth, { accept: 'text/html', referer: E + '/login' });
  http.dump('02_authorize.html', page.text);
  // 3) credentials — POST the login-form's own action (never guessed)
  if (/id="login-form"/i.test(page.text)) {
    const act = (page.text.match(/<form\b[^>]*id="login-form"[^>]*action="([^"]*)"/i) || [])[1] || 'j_spring_security_check';
    const credUrl = new URL(act, 'https://oauth2.gsis.gr/oauth2server/login.jsp').toString();
    http.log('[tpd-login] POST creds -> ' + credUrl);
    const cr = await http.once('POST', credUrl, { form: { j_username: user, j_password: pass }, referer: auth, accept: 'text/html' });
    http.dump('03_creds.html', cr.text);
    if (/failedLogin|authentication_error|incorrect username/i.test(cr.text)) return { ok: false, reason: 'InvalidCredentials' };
    page = cr.url === credUrl ? cr : page; // keep the POST response; the hops below move on
    page = await hop(http, page);
    http.dump('04_aftercreds.html', page.text);
    // flaky GSIS sometimes sends Location to a bare base URL (404); then re-offer authorize
    if ((page.status === 404 || page.text.length < 50) && !/confirmationForm/i.test(page.text)) {
      http.log('[tpd-login] creds hop dead-ended -> re-GET authorize');
      page = await http.once('GET', auth, { accept: 'text/html', referer: credUrl });
      http.dump('04b_reauthorize.html', page.text);
    }
  }
  // 4) consent / confirmationForm -> POST its OWN fields (user_oauth_approval, scope.read checked)
  for (let hop2 = 0; hop2 < 8; hop2++) {
    if (!/id="confirmationForm"/i.test(page.text)) { page = await hop(http, page); continue; }
    const block = (page.text.match(/<form\b[^>]*id="confirmationForm"[\s\S]*?<\/form>/i) || [])[0] || '';
    const act = (block.match(/<form\b[^>]*action="([^"]*)"/i) || [])[1] || '';
    if (!act) return { ok: false, reason: 'NoConsentAction' };
    const fields = {};
    for (const m of block.matchAll(/<input\b[^>]*/gi)) {
      const tag = m[0];
      if (/type="radio"/i.test(tag) && !/checked/i.test(tag)) continue; // only the checked radio
      const nm = (tag.match(/name="([^"]*)"/) || [])[1] || '';
      const vl = (tag.match(/value="([^"]*)"/) || [])[1] || '';
      if (!nm) continue;
      fields[nm] = vl;
    }
    if (!Object.keys(fields).length) return { ok: false, reason: 'NoConsentFields' };
    const consentUrl = new URL(act, page.url).toString();
    http.log('[tpd-login] POST consent (' + Object.keys(fields).join(',') + ')');
    const cp = await http.once('POST', consentUrl, { form: fields, referer: auth, accept: 'text/html' });
    http.dump('06_consent.html', cp.text);
    page = await hop(http, cp);
    http.dump('07_afterconsent.html', page.text);
    break;
  }
  // 5) land back on e-services authenticated (follow the code/state redirect)
  for (let hop3 = 0; hop3 < 10; hop3++) {
    const nx = await http._nextUrl(page, page.url);
    if (!nx || /error=|access_denied/i.test(nx)) break;
    page = await http.once('GET', nx, { accept: 'text/html', referer: auth });
    http.dump('08_landing' + hop3 + '.html', page.text);
  }
  const probe = await http.api('GET', E + '/requests-service/v2/requests/');
  if (probe.status !== 200 || !probe.text) return { ok: false, reason: 'EndpointsForbidden', page, probe };
  const types = await safeJson(probe.text);
  if (!types) return { ok: false, reason: 'EndpointsNotJson', page };
  http.log('[tpd-login] OK (' + types.length + ' request types)');
  return { ok: true, page, E, types };
}
// follow ONE hop (Location / openredirect / partial-redirect) — else return the same response
async function hop(http, r) {
  const nx = await http._nextUrl(r, r.url);
  if (!nx) return r;
  if (/error=|access_denied/i.test(nx)) { http.log('[tpd-login] OAuth error in chain (not following): ' + nx); return r; }
  return await http.once('GET', nx, { accept: 'text/html', referer: r.url });
}
async function safeJson(t) { try { return JSON.parse(t); } catch (e) { return null; } }

module.exports = { CurlHttp, tpdEservicesLogin, UA };
