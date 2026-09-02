/*
 * browser-step.js — Android υλοποίηση του BrowserPage contract.
 * =============================================================================
 * Το πρωτότυπο (`recerse-engineer/runner/lib/browser-step.js`) οδηγεί Playwright
 * Chromium στον desktop. Εδώ υλοποιείται ΤΟ ΙΔΙΟ contract πάνω σε native
 * `WebView`, ώστε τα configs να τρέχουν αυτούσια:
 *
 *   goto · waitLoad · sleep · count · text · attr · fill · click · clickNav
 *   selectByValue · selectByLabel · options · evaluate · url · title · content
 *   expectPopup · expectDownload
 *
 * Ο λόγος ύπαρξης είναι ο ίδιος με του πρωτοτύπου: το ETAK (ΕΝΦΙΑ) κάνει Oracle
 * ADF loopback + F5 BIG-IP client-verification, που θέλουν πραγματικό browser.
 * Το WebView τρέχει το ΙΔΙΟ page-JS on-device και ικανοποιεί τους ελέγχους
 * φυσιολογικά. Δεν πλαστογραφείται κανένα cookie — ούτε εδώ, ούτε στο πρωτότυπο.
 *
 * Κάθε μέθοδος γίνεται RPC στο `__bridge.pageCall`. Το native κρατά τα WebView
 * instances και τα αναγνωρίζει με `handle`.
 */
'use strict';

const UA = 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36';

function pageRpc(handle, op, args) {
  return new Promise(function (resolve, reject) {
    var id = 'p' + (++pageRpc._seq);
    pageRpc._pending[id] = { resolve: resolve, reject: reject };
    try {
      __bridge.pageCall(id, JSON.stringify(Object.assign({ handle: handle, op: op }, args || {})));
    } catch (e) {
      delete pageRpc._pending[id];
      reject(e);
    }
  });
}
pageRpc._seq = 0;
pageRpc._pending = Object.create(null);

// Το native απαντά μέσω των καθολικών __resolve/__reject του shims.js· εδώ
// συνδέουμε το δικό μας registry σε αυτά, χωρίς να τα αντικαταστήσουμε.
(function bindResolvers() {
  var prevResolve = globalThis.__resolve;
  var prevReject = globalThis.__reject;
  globalThis.__resolve = function (id, json) {
    var p = pageRpc._pending[id];
    if (p) {
      delete pageRpc._pending[id];
      try { p.resolve(json ? JSON.parse(json) : null); } catch (e) { p.reject(e); }
      return;
    }
    if (prevResolve) prevResolve(id, json);
  };
  globalThis.__reject = function (id, message) {
    var p = pageRpc._pending[id];
    if (p) {
      delete pageRpc._pending[id];
      p.reject(new Error(message || 'browser error'));
      return;
    }
    if (prevReject) prevReject(id, message);
  };
})();

/**
 * Λεπτό, φορητό wrapper πάνω από μία σελίδα. Ίδιες μέθοδοι με τον desktop.
 */
class BrowserPage {
  constructor(handle) { this._h = handle; this._url = ''; this._title = ''; }
  get raw() { return this._h; }

  async goto(url, opts) {
    const r = await pageRpc(this._h, 'goto', {
      url: String(url),
      waitUntil: (opts && opts.waitUntil) || 'domcontentloaded',
      timeout: (opts && opts.timeout) || 90000,
    });
    this._url = r && r.url ? r.url : String(url);
    return r;
  }

  // Το WebView δεν έχει 'networkidle'. Το native το προσεγγίζει με quiet-period
  // polling πάνω στα εν εξελίξει requests — δεν πετάει ποτέ, όπως το πρωτότυπο.
  async waitLoad(state, timeout) {
    try { await pageRpc(this._h, 'waitLoad', { state: state || 'networkidle', timeout: timeout || 30000 }); }
    catch (e) { /* σιωπηλά, όπως ο desktop */ }
  }

  async sleep(ms) { await pageRpc(this._h, 'sleep', { ms: ms }); }

  async count(sel) { return (await pageRpc(this._h, 'count', { sel: sel })).value; }
  async text(sel) { return (await pageRpc(this._h, 'text', { sel: sel })).value; }
  async attr(sel, name) { return (await pageRpc(this._h, 'attr', { sel: sel, name: name })).value; }
  async fill(sel, value) { await pageRpc(this._h, 'fill', { sel: sel, value: String(value) }); }
  async click(sel) { await pageRpc(this._h, 'click', { sel: sel }); }

  async clickNav(sel, timeout) {
    await pageRpc(this._h, 'clickNav', { sel: sel, timeout: timeout || 30000 });
    await this.waitLoad();
  }

  async selectByValue(sel, value) { await pageRpc(this._h, 'selectByValue', { sel: sel, value: String(value) }); }
  async selectByLabel(sel, label) { await pageRpc(this._h, 'selectByLabel', { sel: sel, label: String(label) }); }
  async options(sel) { return (await pageRpc(this._h, 'options', { sel: sel })).value || []; }

  // Ο desktop δέχεται συνάρτηση· εδώ τη στέλνουμε ως πηγαίο κείμενο και το
  // native την τρέχει με evaluateJavascript μέσα στη σελίδα.
  async evaluate(fn, arg) {
    const src = typeof fn === 'function' ? fn.toString() : String(fn);
    return (await pageRpc(this._h, 'evaluate', { fn: src, arg: arg === undefined ? null : arg })).value;
  }

  url() { return this._url; }
  async syncUrl() { this._url = (await pageRpc(this._h, 'url', {})).value || ''; return this._url; }
  async title() { try { return (await pageRpc(this._h, 'title', {})).value || ''; } catch (e) { return ''; } }
  async content() { return (await pageRpc(this._h, 'content', {})).value || ''; }

  /** Περιμένει popup που ανοίγει η action(). Επιστρέφει τυλιγμένο BrowserPage. */
  async expectPopup(action) {
    await pageRpc(this._h, 'armPopup', {});
    await action();
    const r = await pageRpc(this._h, 'awaitPopup', { timeout: 60000 });
    const popup = new BrowserPage(r.handle);
    await popup.syncUrl();
    await popup.waitLoad();
    return popup;
  }

  /** Περιμένει download που πυροδοτεί η action()· αποθηκεύει στο destPath. */
  async expectDownload(action, destPath, timeout) {
    await pageRpc(this._h, 'armDownload', { dest: String(destPath) });
    await action();
    const r = await pageRpc(this._h, 'awaitDownload', { timeout: timeout || 120000 });
    return { path: destPath, filename: r.filename || '' };
  }
}

/**
 * Ίδιο συμβόλαιο με τον desktop: ποτέ δεν πετάει για απόντα engine — επιστρέφει
 * { ok:false, reason } ώστε ο caller να υποβαθμίζεται χαριτωμένα.
 */
async function withBrowser(opts, fn) {
  opts = opts || {};
  let handle = null;
  try {
    const r = await pageRpc('', 'open', {
      userAgent: UA,
      locale: 'el-GR',
      headed: !!opts.headed,
    });
    handle = r.handle;
  } catch (e) {
    return { ok: false, reason: 'WebView δεν είναι διαθέσιμο: ' + (e && e.message ? e.message : e) };
  }

  const page = new BrowserPage(handle);
  try {
    return (await fn({ page, context: handle, browser: handle, BrowserPage })) || { ok: true };
  } catch (e) {
    return { ok: false, reason: e && e.message ? e.message : String(e) };
  } finally {
    try { await pageRpc(handle, 'close', {}); } catch (e) { /* ignore */ }
  }
}

module.exports = { withBrowser, BrowserPage, UA };
