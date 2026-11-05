/*
 * lib/akeed-http.js  --  AKEED (Αναlytical Κατάσταση Eiiforash Diadikyou — Εfarmob EDOEAP e AKEED)
 * =====================================================================================
 * Faithful HTTP translation of the captured AKEED flow (runner/downloads/akel-ed/), FIXED
 * for the 500 «View /indexACD.xhtml could not be restored» (javax.faces ViewExpiredException)
 * that the naive replay produced: cause was POSTing to indexACD.xhtml with a STALE
 * javax.faces.ViewState. The correct (CefSharp spec AkaAkeEdoeap) routine never POSTs the
 * login page — it GETs the «Πατήστε Εδώ» link `gsis/serviceACD`, which 302s to the GSIS
 * AAA OAuth1 provider (www1.gsis.gr/gsisapps/soasgsisws2), and authenticates there:
 *
 *   1 GET  apps.e-efka.gov.gr/EDOEAPeACD/                    (landing; EDOEAPeACDSessionID, gsis_cookie)
 *   2 GET  apps.ika.gr/gsis-edoeap-consumer/indexACD.xhtml   («Σύνδεση με κωδικούς TAXISNET»; link gsis/serviceACD)
 *   3 GET  apps.ika.gr/gsis-edoeap-consumer/gsis/serviceACD  -> 302 www1.gsis.gr/gsisapps/soasgsisws2/oauth/confirm_access?oauth_token=T
 *   4 GET  .../oauth/confirm_access?oauth_token=T            -> 302 .../login.jsp;jsessionid=...   (login form j_username/j_password, action /gsisapps/soasgsisws2/login.do)
 *   5 POST .../login.do {j_username,j_password}              (AAA; on wrong creds -> error page; on OK -> auto-continue: 302 OR HTML auto-submit form/window.location)
 *      -> follow chain back to the consumer (apps.ika.gr) with its session -> authenticated app
 *   6 GET  indexACD.xhtml again (fresh)                      -> authenticated iff NO userRegistration form (no «Σύνδεση με κωδικούς TAXISNET»)
 *
 * AAA auto-continue handling is defensive (up to 4 hops): 3xx (engine follow()), HTML
 * `window.location = '...'`, or hidden <form> auto-submits (oauth_token/oauth_verifier).
 */
'use strict';
const path = require('path');
const fs = require('fs');
const hyper = require('../lib/hyper-http');

const APP = 'https://apps.e-efka.gov.gr';        // landing shell (EDOEAPeACD)
const CONSUMER = 'https://apps.ika.gr/gsis-edoeap-consumer';
const PROVIDER = 'https://www1.gsis.gr/gsisapps/soasgsisws2';

/* ---------- AKEED login (TAXISnet credentials) ----------
 * inputs: {user,pass}. Returns {ok, page:{url,text}} (authenticated consumer page) or
 * {ok:false, reason}. Session cookies (consumer JSESSIONID etc.) ζει στο shared jar. */
async function akeedDoeapLogin(http, { user, pass, afm, captcha }) {
  http.log('[akeed-login] GET landing /EDOEAPeACD/');
  const land = await http.follow('GET', APP + '/EDOEAPeACD/');
  http.dump('00_landing.html', land.text);

  http.log('[akeed-login] GET consumer indexACD.xhtml (login page)');
  const loginPage = await http.follow('GET', CONSUMER + '/indexACD.xhtml');
  http.dump('01_consumer_login.html', loginPage.text);

  // «Πατήστε Εδώ» link -> gsis/serviceACD (href relative on the page)
  const href = (loginPage.text.match(/href="gsis\/serviceACD"/i) || [])[0] ? 'gsis/serviceACD' : '';
  if (!href && /Σύνδεση με κωδικούς TAXISNET/i.test(loginPage.text) === false) {
    // already authenticated? unlikely first visit; bail with page
    return { ok: false, reason: 'NoServiceACDLink', page: loginPage };
  }
  http.log('[akeed-login] GET gsis/serviceACD -> OAuth1 provider (302)');
  let r = await http.follow('GET', CONSUMER + '/gsis/serviceACD');
  http.dump('02_serviceACD.html', r.text);

  http.log('[akeed-login] credentials POST login.do');
  // r should now be the AAA login.jsp page (form action /gsisapps/soasgsisws2/login.do)
  const act = (r.text.match(/<form\b[^>]*action="([^"]*)"[^>]*>(?:(?!\s*<\/form>)[\s\S])*?j_username/i) || [])[1]
           || (r.text.match(/<form\b[^>]*j_username(?:(?!\s*<\/form>)[\s\S])*?action="([^"]*)"/i) || [])[1];
  if (!/j_password/i.test(r.text)) return { ok: false, reason: 'NoLoginForm', page: r };
  if (!act) return { ok: false, reason: 'LoginFormAction' };
  let post = await http.follow('POST', new URL(act, r.url).toString(), { j_username: user, j_password: pass });
  r = post; http.dump('03_login_do_resp.html', r.text);
  // AAA rejects wrong credentials by 302-ing login.do back to login.jsp (form re-shown)
  if (/login\.jsp/i.test(r.url) && /name=["']j_password["']/i.test(r.text))
    return { ok: false, reason: 'InvalidCredentials', page: r };
  // continue auto-submits if AAA returned one (hidden oauth form / window.location)
  for (let hop = 0; hop < 4; hop++) {
    const wl = (r.text.match(/window\.location\s*=\s*'([^']+)'/i) || [])[1];
    if (wl) { r = await http.follow('GET', new URL(decodeHtml(wl), r.url).toString()); http.dump('03_wl_' + hop + '.html', r.text); continue; }
    const hf = r.text.match(/<form\b[^>]*action="([^"]*)"((?:(?!\s*<\/form>)[\s\S])*)/i);
    if (hf && /name=["'](oauth_token|oauth_verifier|target|requestToken|authorize)["']/i.test(hf[2]) && !/j_password/i.test(hf[2])) {
      const fields = {};
      for (const inp of hf[2].matchAll(/<input\b[^>]*name="([^"]*)"[^>]*value="([^"]*)"/gi)) fields[inp[1]] = inp[2];
      for (const inp of hf[2].matchAll(/<input\b[^>]*value="([^"]*)"[^>]*name="([^"]*)"/gi)) if (!(inp[2] in fields)) fields[inp[2]] = inp[1];
      r = await http.follow('POST', new URL(decodeHtml(hf[1]), r.url).toString(), fields);
      http.dump('04_hidden_' + hop + '.html', r.text);
      if (/login\.jsp/i.test(r.url) && /name=["']j_password["']/i.test(r.text))
        return { ok: false, reason: 'InvalidCredentials', page: r };
      continue;
    }
    break; // chain ended (normal 3xx follow already done by follow())
  }
  http.dump('05_after_gsis.html', r.text);

  // AKEED 2nd stage: login.xhtml?id=<GSIS ticket> «Σyndeoh Hristiti» — requires afm + captchaID
  let attempts = 0;
  while (/name="captchaID"/i.test(r.text) && attempts < 5) {
    attempts++;
    const capImgUrl = new URL((r.text.match(/id="captchaImage"[^>]*src="([^"]*)"/i) || [])[1] || '/EDOEAPeACD/resources/captcha.jpg', r.url).toString();
    if (!captcha) {
      try {
        const cres = await http.once('GET', capImgUrl);
        const img = Buffer.from(await cres.arrayBuffer());
        const fn = 'captcha_AKEED' + (attempts > 1 ? '_' + attempts : '') + '.jpg';
        const fp = path.join(http.dlDir, fn);
        await fs_writeRetry(fp, img);
        http.log('[akeed-login] CAPTCHA required (attempt ' + attempts + ') — saved captcha (' + img.length + ' b) -> ' + fn);
        if (!captcha) captcha = await solveCaptchaDdddocr(fp);
        if (captcha) http.log('[akeed-login] captcha solved in-script (ddddocr guess: ' + captcha + ')');
        if (!captcha && process.stdin.isTTY)
          captcha = await hyper.ask('AKEED captcha code (open ' + fp + ', attempt ' + attempts + '): ', true);
        if (!captcha) return { ok: false, reason: 'CaptchaRequired', captchaFile: fn, page: r };
      } catch (e) { http.log('[akeed-login] captcha image fetch failed: ' + e.message + ' (retry)'); continue; }
    }
    const btn = r.text.match(/<button\b[^>]*type="submit"[^>]*>/i) ||
                 r.text.match(/<button\b[^>]*id="([^"]*)"[^>]*onclick="PrimeFaces\.(ab|abc|bcn)/i);
    const btnId = btn ? ((btn[1] || (btn[0].match(/id="([^"]*)"/) || [])[1]) || '') : '';
    const act2 = (r.text.match(/<form[^>]*id="mainForm"[^>]*action="([^"]*)"/i) || [])[1] ||
                 (r.text.match(/<form[^>]*action="([^"]*)"[^>]*id="mainForm"/i) || [])[1] || 'login.xhtml';
    const vs = (r.text.match(/name="javax\.faces\.ViewState"[^>]*value="([^"]*)"/i) || [])[1] ||
               (r.text.match(/value="([^"]*)"[^>]*name="javax\.faces\.ViewState"/i) || [])[1] ||
               (r.text.match(/<update id="javax\.faces\.ViewState"><!\[CDATA\[([^\]]*)/i) || [])[1] || '';
    const afmFinal = (afm && String(afm).trim()) || (/^\d{9}$/.test(user) ? user : '');
    if (!btnId || !vs) return { ok: false, reason: 'CaptchaFormParse', page: r };
    if (!afmFinal) return { ok: false, reason: 'AfmMissing', page: r };
    http.log('[akeed-login] mainForm submit: afm=' + afmFinal + ' + captcha (button ' + btnId + ')');
    const form = {
      'javax.faces.partial.ajax': 'true', 'javax.faces.source': btnId,
      'javax.faces.partial.execute': 'mainForm', 'javax.faces.partial.render': 'mainForm',
      'mainForm': 'mainForm', [btnId]: btnId,
      'afm': afmFinal, 'captchaID': captcha, 'javax.faces.ViewState': vs,
    };
    r = await http.follow('POST', new URL(act2, r.url).toString(), form);
    http.dump('07_after_captcha.html', r.text);
    captcha = ''; // always re-read captcha on the next iteration (image re-rendered per request)
  }
  if (/name="captchaID"/i.test(r.text)) return { ok: false, reason: 'CaptchaRejected', page: r };

  // authenticated view: the chain resolved to EDOEAPeACD index.xhtml (app on apps.e-efka.gov.gr).
  http.log('[akeed-login] GET EDOEAPeACD index.xhtml (authenticated)');
  let auth = await http.follow('GET', APP + '/EDOEAPeACD/index.xhtml');
  http.dump('06_authenticated.html', auth.text);
  if (/name="captchaID"/i.test(auth.text) || auth.status === 404 || /id="userRegistration"/i.test(auth.text)) {
    auth = { url: r.url, status: r.status, text: r.text }; // fallback: page right after captcha submit
    http.dump('06_authenticated.html', auth.text);
    if (/name="captchaID"/i.test(auth.text)) {
      http.log('[akeed-login] ✗ captcha rejected / flow broke');
      return { ok: false, reason: 'StillAnonymous', page: auth };
    }
  }
  http.log('[akeed-login] OK — authenticated EDOEAPeACD app');
  return { ok: true, page: auth, CONSUMER, APP };
}
async function fs_writeRetry(fp, buf) {
  for (let w = 0; ; w++) {
    try { fs.writeFileSync(fp, buf); return; }
    catch (e) { if (w >= 4) throw e; await new Promise((z) => setTimeout(z, 400 * (w + 1))); }
  }
}

/* ---------- captcha: ddddocr ONLY (tesseract removed 2026-10-31) ----------
 * solveCaptchaDdddocr(imgPath) spawns scripts/captcha-solve-ddddocr.py under python<=3.12
 * (ddddocr requires it; PY env var overrides). The solver does its own pipeline:
 * adaptive edge-bar crop (solid black cols) -> Otsu -> noise-line removal (morph open
 * wide+diagonal kernels) -> per-glyph boxes -> ddddocr default+beta per-glyph vote +
 * full-image cross-vote. stdout: "-> OK <code>" confident, "-> LOW <code>" retry guess.
 * Captcha token is per-render: on rejection the caller MUST re-GET login.xhtml and re-OCR
 * the fresh captcha.jpg — reuse of a code from a previous attempt image fails; always re-OCR the fresh render.
 */
async function solveCaptchaDdddocr(imgPath) {
  const spawnSync = require('child_process').spawnSync;
  const solver = 'scripts/captcha-solve-ddddocr.py';
  const pyCands = [process.env.PY, 'C:/Users/Antonis/AppData/Local/Programs/Python/Python312/python.exe',
    'python3.12', 'python3.11', 'python', 'python3'].filter((x) => typeof x === 'string' && x);
  for (const py of pyCands) {
    let sp = null;
    try { sp = spawnSync(py, [solver, imgPath], { encoding: 'utf-8', timeout: 120000 }); }
    catch (e) { continue; }
    if (!sp || (sp.error && /ENOENT|ENOEXEC/.test(String(sp.error.code || '')))) continue;
    const out = String(sp.stdout || '') + '\n' + String(sp.stderr || '');
    const m = /-> (OK|LOW)\s+([a-z0-9]{4,7})/.exec(out);
    if (m && m[2].length >= 4) return m[2];
    if (/ImportError|ModuleNotFound|No such file|cannot execute/i.test(out)) continue;
    break; // solver ran but produced no guess — do not retry with other interpreters
  }
  return '';
}

module.exports = { akeedDoeapLogin, solveCaptchaDdddocr };

function decodeHtml(s) { return (s || '').replace(/&nbsp;/g, ' ').replace(/&amp;/g, '&').replace(/&quot;/g, '\"').replace(/&#47;/g, '/').replace(/&lt;/g, '<').replace(/&gt;/g, '>'); }

