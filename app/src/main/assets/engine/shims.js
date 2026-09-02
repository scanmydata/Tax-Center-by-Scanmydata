/*
 * shims.js — η γέφυρα Node → Android.
 * =============================================================================
 * Ο runner (`recerse-engineer/runner`) γράφτηκε για Node 18+. Τα configs και ο
 * engine (`hyper-http.js`, `browser-step.js`) αντιγράφονται εδώ **αυτούσια** —
 * καμία τροποποίηση, ώστε desktop και κινητό να μοιράζονται ένα source of truth.
 *
 * Ό,τι λείπει από το WebView το παρέχει αυτό το αρχείο, πάνω από ένα μοναδικό
 * native object `__bridge` (βλ. NativeBridge.kt):
 *
 *   require('fs' | 'path' | 'readline')   fetch()   Buffer   process   module
 *
 * Δεν χρησιμοποιείται το fetch του WebView: δεν διαβάζει `Set-Cookie` (forbidden
 * header) και δεν κάνει `redirect:'manual'` cross-origin — και τα δύο είναι
 * load-bearing στον engine. Όλο το HTTP περνά από OkHttp.
 */
'use strict';
(function () {

  // ---------------------------------------------------------------- async RPC
  // Το @JavascriptInterface είναι σύγχρονο. Κάθε async κλήση παίρνει callId, και
  // το Kotlin απαντά με __resolve/__reject.
  var _seq = 0;
  var _pending = Object.create(null);

  function rpc(fn, payload) {
    return new Promise(function (resolve, reject) {
      var id = 'c' + (++_seq);
      _pending[id] = { resolve: resolve, reject: reject };
      try { __bridge[fn](id, JSON.stringify(payload)); }
      catch (e) { delete _pending[id]; reject(e); }
    });
  }

  globalThis.__resolve = function (id, json) {
    var p = _pending[id]; if (!p) return; delete _pending[id];
    try { p.resolve(json ? JSON.parse(json) : null); } catch (e) { p.reject(e); }
  };
  globalThis.__reject = function (id, message) {
    var p = _pending[id]; if (!p) return; delete _pending[id];
    p.reject(new Error(message || 'native error'));
  };

  // ------------------------------------------------------------------- base64
  var B64 = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/';

  function b64ToBytes(b64) {
    if (!b64) return new Uint8Array(0);
    var clean = String(b64).replace(/[^A-Za-z0-9+/]/g, '');
    var n = clean.length, out = new Uint8Array((n * 3) >> 2), o = 0, buf = 0, bits = 0;
    for (var i = 0; i < n; i++) {
      buf = (buf << 6) | B64.indexOf(clean.charAt(i)); bits += 6;
      if (bits >= 8) { bits -= 8; out[o++] = (buf >> bits) & 0xff; }
    }
    return out.subarray(0, o);
  }

  function bytesToB64(bytes) {
    var out = '', i;
    for (i = 0; i + 2 < bytes.length; i += 3) {
      var t = (bytes[i] << 16) | (bytes[i + 1] << 8) | bytes[i + 2];
      out += B64[(t >> 18) & 63] + B64[(t >> 12) & 63] + B64[(t >> 6) & 63] + B64[t & 63];
    }
    var rem = bytes.length - i;
    if (rem === 1) {
      var a = bytes[i] << 16;
      out += B64[(a >> 18) & 63] + B64[(a >> 12) & 63] + '==';
    } else if (rem === 2) {
      var b = (bytes[i] << 16) | (bytes[i + 1] << 8);
      out += B64[(b >> 18) & 63] + B64[(b >> 12) & 63] + B64[(b >> 6) & 63] + '=';
    }
    return out;
  }

  // ------------------------------------------------------------------- Buffer
  // Ο engine χρησιμοποιεί μόνο: Buffer.from(arrayBuffer), .length,
  // .slice(a,b).toString('latin1'|'utf8'). Τίποτε άλλο.
  function Buf(bytes) { this._b = bytes; this.length = bytes.length; }
  Buf.prototype.slice = function (a, b) { return new Buf(this._b.subarray(a, b)); };
  Buf.prototype.toString = function (enc) {
    if (enc === 'latin1' || enc === 'binary') {
      var s = '';
      for (var i = 0; i < this._b.length; i++) s += String.fromCharCode(this._b[i]);
      return s;
    }
    if (enc === 'base64') return bytesToB64(this._b);
    return new TextDecoder('utf-8').decode(this._b);
  };
  Buf.prototype.__bytes = function () { return this._b; };
  Buf.isBuffer = function (x) { return x instanceof Buf; };
  Buf.from = function (src, enc) {
    if (src instanceof ArrayBuffer) return new Buf(new Uint8Array(src));
    if (src instanceof Uint8Array) return new Buf(src);
    if (src instanceof Buf) return src;
    if (typeof src === 'string') {
      if (enc === 'base64') return new Buf(b64ToBytes(src));
      return new Buf(new TextEncoder().encode(src));
    }
    return new Buf(new Uint8Array(src || 0));
  };
  globalThis.Buffer = Buf;

  // -------------------------------------------------------------------- fetch
  // Response-like με ΑΚΡΙΒΩΣ ό,τι αγγίζει ο engine:
  //   status · headers.get(name) · headers.getSetCookie() · text() · arrayBuffer()
  globalThis.fetch = function (url, opts) {
    opts = opts || {};
    return rpc('httpRequest', {
      url: String(url),
      method: opts.method || 'GET',
      headers: opts.headers || {},
      body: opts.body == null ? null : String(opts.body),
      // Ο engine δίνει πάντα redirect:'manual' — κυνηγά μόνος του τα redirects,
      // γιατί κρίνει InvalidCredentials από το τελικό URL.
      redirect: opts.redirect || 'manual'
    }).then(function (r) {
      var lower = Object.create(null);
      var raw = r.headers || {};
      for (var k in raw) {
        if (Object.prototype.hasOwnProperty.call(raw, k)) lower[k.toLowerCase()] = raw[k];
      }
      var setCookies = r.setCookie || [];
      var bytes = b64ToBytes(r.bodyB64 || '');
      return {
        status: r.status,
        ok: r.status >= 200 && r.status < 300,
        url: r.url,
        headers: {
          get: function (name) {
            var v = lower[String(name).toLowerCase()];
            return v === undefined ? null : v;
          },
          getSetCookie: function () { return setCookies.slice(); }
        },
        text: function () {
          return Promise.resolve(new TextDecoder('utf-8').decode(bytes));
        },
        arrayBuffer: function () {
          // Αντίγραφο, ώστε το Buffer.from να μην κρατά ζωντανό το υποκείμενο view.
          return Promise.resolve(bytes.buffer.slice(bytes.byteOffset, bytes.byteOffset + bytes.byteLength));
        },
        json: function () {
          return Promise.resolve(JSON.parse(new TextDecoder('utf-8').decode(bytes)));
        }
      };
    });
  };

  // ----------------------------------------------------------------------- fs
  function toB64(data) {
    if (Buf.isBuffer(data)) return bytesToB64(data.__bytes());
    if (data instanceof Uint8Array) return bytesToB64(data);
    return bytesToB64(new TextEncoder().encode(String(data)));
  }
  function fail(err) { if (err) throw new Error(err); }

  var fsShim = {
    writeFileSync: function (p, data) { fail(__bridge.fileWrite(String(p), toB64(data), false)); },
    appendFileSync: function (p, data) { fail(__bridge.fileWrite(String(p), toB64(data), true)); },
    mkdirSync: function (p) { fail(__bridge.mkdirs(String(p))); },
    existsSync: function (p) { return __bridge.fileExists(String(p)) === '1'; },
    statSync: function (p) {
      var size = parseInt(__bridge.fileSize(String(p)), 10);
      if (isNaN(size) || size < 0) throw new Error('ENOENT: ' + p);
      return {
        size: size,
        isFile: function () { return true; },
        isDirectory: function () { return false; }
      };
    },
    readFileSync: function (p, enc) {
      var b64 = __bridge.fileRead(String(p));
      if (b64 === null || b64 === undefined) throw new Error('ENOENT: ' + p);
      var b = Buf.from(b64, 'base64');
      return enc ? b.toString(enc) : b;
    }
  };

  // --------------------------------------------------------------------- path
  var pathShim = {
    sep: '/',
    join: function () {
      var parts = [];
      for (var i = 0; i < arguments.length; i++) {
        var s = String(arguments[i] == null ? '' : arguments[i]);
        if (s !== '') parts.push(s);
      }
      return parts.join('/').replace(/\/{2,}/g, '/');
    },
    resolve: function () {
      var out = '';
      for (var i = 0; i < arguments.length; i++) {
        var s = String(arguments[i] == null ? '' : arguments[i]);
        if (s.charAt(0) === '/') out = s; else out = out ? out + '/' + s : s;
      }
      // Ο engine καλεί path.resolve(dlDir || './downloads') — το './' περισσεύει.
      return out.replace(/^\.\//, '').replace(/\/{2,}/g, '/');
    },
    basename: function (p, ext) {
      var b = String(p).replace(/\/+$/, '').split('/').pop() || '';
      if (ext && b.slice(-ext.length) === ext) b = b.slice(0, -ext.length);
      return b;
    },
    dirname: function (p) {
      var parts = String(p).replace(/\/+$/, '').split('/');
      parts.pop();
      return parts.join('/') || '.';
    },
    extname: function (p) {
      var b = pathShim.basename(p), i = b.lastIndexOf('.');
      return i > 0 ? b.slice(i) : '';
    }
  };

  // ----------------------------------------------------------------- readline
  // Ο runner ζητά inputs με prompt. Στο κινητό τα δίνει το UI — αν κάτι φτάσει
  // εδώ, είναι bug και πρέπει να σκάσει θορυβωδώς, όχι να κρεμάσει.
  var readlineShim = {
    createInterface: function () {
      return {
        question: function () {
          throw new Error('Δεν υπάρχει διαδραστικό prompt στο κινητό — τα inputs έρχονται από το UI.');
        },
        close: function () {}
      };
    }
  };

  // ------------------------------------------------------------------ process
  globalThis.process = {
    env: {},
    platform: 'android',
    argv: ['node', 'app'],
    exit: function (code) { throw new Error('process.exit(' + code + ')'); },
    stdin: {
      isTTY: false,
      setRawMode: function () {}, resume: function () {}, pause: function () {},
      on: function () {}, removeListener: function () {}
    },
    stdout: { write: function (s) { __bridge.log(String(s)); } }
  };

  // ------------------------------------------------------------------ console
  function mkLog(level) {
    return function () {
      var parts = [];
      for (var i = 0; i < arguments.length; i++) {
        var a = arguments[i];
        if (typeof a === 'string') { parts.push(a); }
        else {
          try { parts.push(JSON.stringify(a)); } catch (e) { parts.push(String(a)); }
        }
      }
      __bridge.log(level + parts.join(' '));
    };
  }
  globalThis.console = {
    log: mkLog(''), info: mkLog(''), warn: mkLog('WARN '),
    error: mkLog('ERROR '), debug: function () {}
  };

  // ------------------------------------------------- CommonJS module resolver
  // Τα configs κάνουν require('fs'), require('path'), require('../lib/browser-step'),
  // require('../lib/render-pdf'). Οι δύο τελευταίες σερβίρονται από τα assets.
  var builtins = { fs: fsShim, path: pathShim, readline: readlineShim };
  var cache = Object.create(null);

  function normaliseId(id) {
    // '../lib/browser-step' | './lib/browser-step' | 'browser-step.js' -> 'browser-step'
    return String(id).replace(/^.*\//, '').replace(/\.js$/, '');
  }

  function define(name, src) {
    var key = normaliseId(name);
    var mod = { exports: {} };
    cache[key] = mod;
    try {
      new Function('module', 'exports', 'require', '__filename', '__dirname', src)
        .call(mod.exports, mod, mod.exports, globalThis.require, key + '.js', '.');
    } catch (e) {
      delete cache[key];
      throw new Error('Σφάλμα φόρτωσης module "' + key + '": ' + (e && e.message ? e.message : e));
    }
    return mod.exports;
  }

  globalThis.require = function (id) {
    if (Object.prototype.hasOwnProperty.call(builtins, id)) return builtins[id];
    var key = normaliseId(id);
    if (cache[key]) return cache[key].exports;
    var src = __bridge.moduleSource(key);
    if (src === null || src === undefined || src === '') {
      throw new Error('Δεν βρέθηκε module: ' + id + ' (ζητήθηκε ως "' + key + '")');
    }
    return define(key, src);
  };

  // Το Kotlin καταχωρεί εδώ modules που έχει ήδη διαβάσει από τα assets.
  globalThis.__preload = function (name, src) { define(name, src); return true; };

})();
