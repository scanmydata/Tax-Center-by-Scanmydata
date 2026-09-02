#!/usr/bin/env node
/*
 * tools/test-shims.mjs — αποδεικνύει ότι ο engine τρέχει πάνω στα shims.
 * =============================================================================
 * Στήνει ένα `vm` context ΧΩΡΙΣ κανένα Node builtin (χωρίς require, fs, path,
 * Buffer, fetch) — δηλαδή όσο κοντά γίνεται στο WebView — φορτώνει shims.js με
 * ένα mock `__bridge`, και μετά τρέχει τον ΑΥΤΟΥΣΙΟ `hyper-http.js` και τα
 * ΑΥΤΟΥΣΙΑ configs.
 *
 * Αν αυτό περνά, το μόνο που μένει για το Android είναι να υλοποιήσει το
 * `__bridge` σε Kotlin — η JS πλευρά είναι ήδη αποδεδειγμένη.
 *
 * Χρήση: node tools/test-shims.mjs
 */
import fs from 'node:fs';
import path from 'node:path';
import vm from 'node:vm';
import { fileURLToPath } from 'node:url';

const HERE = path.dirname(fileURLToPath(import.meta.url));
const ASSETS = path.resolve(HERE, '../app/src/main/assets/engine');

let pass = 0, fail = 0;
const ok = (name) => { pass++; console.log(`  ✓ ${name}`); };
const no = (name, err) => { fail++; console.log(`  ✗ ${name}\n      ${err}`); };
function check(name, fn) { try { fn(); ok(name); } catch (e) { no(name, e && e.message ? e.message : e); } }
async function checkAsync(name, fn) { try { await fn(); ok(name); } catch (e) { no(name, e && e.message ? e.message : e); } }
function assert(cond, msg) { if (!cond) throw new Error(msg || 'assertion failed'); }

// --------------------------------------------------------------- mock bridge
function makeBridge(routes) {
  const files = new Map();          // path -> Buffer (Node Buffer, μόνο στο mock)
  const dirs = new Set();
  const logs = [];
  const requests = [];
  let ctx = null;                   // τίθεται μετά τη δημιουργία του context

  const b64 = {
    dec: (s) => Buffer.from(s || '', 'base64'),
    enc: (buf) => Buffer.from(buf).toString('base64'),
  };

  const bridge = {
    // async
    httpRequest(callId, json) {
      const req = JSON.parse(json);
      requests.push(req);
      const route = routes(req);
      // Το πραγματικό bridge απαντά ασύγχρονα· το μιμούμαστε.
      setTimeout(() => {
        if (route.error) {
          ctx.__reject(callId, route.error);
          return;
        }
        ctx.__resolve(callId, JSON.stringify({
          status: route.status ?? 200,
          url: route.url ?? req.url,
          headers: route.headers ?? {},
          setCookie: route.setCookie ?? [],
          bodyB64: b64.enc(route.body ?? ''),
        }));
      }, 0);
    },
    pageCall(callId, json) {
      setTimeout(() => ctx.__reject(callId, 'no WebView in test harness'), 0);
    },
    // sync
    fileWrite(p, dataB64, append) {
      const buf = b64.dec(dataB64);
      const prev = append && files.has(p) ? files.get(p) : Buffer.alloc(0);
      files.set(p, Buffer.concat([prev, buf]));
      return '';
    },
    fileRead(p) { return files.has(p) ? b64.enc(files.get(p)) : null; },
    fileExists(p) { return files.has(p) || dirs.has(p) ? '1' : '0'; },
    fileSize(p) { return files.has(p) ? String(files.get(p).length) : '-1'; },
    mkdirs(p) { dirs.add(p); return ''; },
    log(line) { logs.push(String(line)); return ''; },
    moduleSource(name) {
      const f = path.join(ASSETS, `${name}.js`);
      return fs.existsSync(f) ? fs.readFileSync(f, 'utf8') : null;
    },
  };
  return { bridge, files, logs, requests, setCtx: (c) => { ctx = c; } };
}

// ------------------------------------------------------------------ context
function makeContext(mock) {
  // ΜΟΝΟ μη-standard globals. Τα ECMAScript builtins (Object, Promise, Function…)
  // τα έχει ήδη το vm context — και ΔΕΝ πρέπει να περάσουν απ' έξω: το
  // `new Function` του module loader θα compilάριζε τα modules στο ΕΞΩΤΕΡΙΚΟ
  // realm, όπου το `fetch` είναι του Node και όχι το shim μας. Στο WebView
  // υπάρχει ένα μόνο realm, οπότε εκεί δεν τίθεται τέτοιο θέμα.
  const sandbox = {
    __bridge: mock.bridge,
    TextDecoder, TextEncoder, URL, URLSearchParams,
    setTimeout, clearTimeout,
  };
  const ctx = vm.createContext(sandbox);
  mock.setCtx(ctx);
  vm.runInContext(fs.readFileSync(path.join(ASSETS, 'shims.js'), 'utf8'), ctx, { filename: 'shims.js' });
  return ctx;
}

function loadModule(ctx, name) {
  const src = fs.readFileSync(path.join(ASSETS, `${name}.js`), 'utf8');
  return vm.runInContext(
    `__preload(${JSON.stringify(name)}, ${JSON.stringify(src)}), require(${JSON.stringify(name)})`,
    ctx, { filename: `${name}.js` },
  );
}

console.log('\nshims.js — βασικά\n');

// ------------------------------------------------------------------- tests
const html = '<html><body><input name="j_username"><form action="/next"></form>Καλώς ήρθατε</body></html>';
const pdfBytes = Buffer.concat([Buffer.from('%PDF-1.4\n'), Buffer.alloc(64, 0x41)]);

const mock = makeBridge((req) => {
  if (req.url.includes('/pdf')) {
    return { status: 200, headers: { 'content-type': 'application/octet-stream' }, body: pdfBytes };
  }
  if (req.url.includes('/redirect')) {
    return { status: 302, headers: { location: 'https://example.gr/done' }, setCookie: ['SESS=abc; Path=/'] };
  }
  if (req.url.includes('/greek')) {
    return { status: 200, headers: { 'content-type': 'text/html; charset=UTF-8' }, body: Buffer.from(html, 'utf8') };
  }
  return { status: 200, headers: { 'content-type': 'text/html' }, body: Buffer.from(html, 'utf8') };
});
const ctx = makeContext(mock);

check('shims φορτώνονται χωρίς Node builtins', () => {
  assert(typeof ctx.require === 'function', 'λείπει require');
  assert(typeof ctx.fetch === 'function', 'λείπει fetch');
  assert(typeof ctx.Buffer === 'function', 'λείπει Buffer');
});

check('base64 round-trip σε binary bytes', () => {
  const out = vm.runInContext(
    `(function(){ var b = Buffer.from(new Uint8Array([0,1,2,253,254,255])); return b.toString('base64'); })()`,
    ctx);
  assert(out === Buffer.from([0, 1, 2, 253, 254, 255]).toString('base64'), `πήρα ${out}`);
});

check('Buffer.slice().toString("latin1") — ο %PDF έλεγχος', () => {
  const out = vm.runInContext(
    `(function(){ var b = Buffer.from(new TextEncoder().encode('%PDF-1.4 xx')); return b.slice(0,4).toString('latin1'); })()`,
    ctx);
  assert(out === '%PDF', `πήρα ${JSON.stringify(out)}`);
});

check('path.join / basename όπως ο Node', () => {
  const j = vm.runInContext(`require('path').join('a','b','c.pdf')`, ctx);
  const b = vm.runInContext(`require('path').basename('/x/y/Φ2_123_2025.pdf')`, ctx);
  assert(j === 'a/b/c.pdf', `join -> ${j}`);
  assert(b === 'Φ2_123_2025.pdf', `basename -> ${b}`);
});

check('fs γράφει ελληνικά σωστά (UTF-8)', () => {
  vm.runInContext(`require('fs').writeFileSync('/t/greek.txt', 'Καλώς ήρθατε — ΑΦΜ')`, ctx);
  assert(mock.files.get('/t/greek.txt').toString('utf8') === 'Καλώς ήρθατε — ΑΦΜ',
    'το κείμενο δεν γύρισε σωστά');
});

console.log('\nhyper-http.js — ο engine αυτούσιος\n');

let lib;
check('φορτώνεται χωρίς τροποποίηση', () => {
  lib = loadModule(ctx, 'hyper-http');
  assert(typeof lib.HyperHttp === 'function', 'λείπει HyperHttp');
  for (const fn of ['aadeLogin', 'efkaNonEmployeeLogin', 'stripTags', 'viewState', 'dataTableRows']) {
    assert(typeof lib[fn] === 'function', `λείπει ${fn}`);
  }
});

check('οι καθαροί parsers δουλεύουν', () => {
  assert(lib.stripTags('<b>Οφειλή</b> 1.234,56') === 'Οφειλή 1.234,56', 'stripTags');
  // hasId ψάχνει id="…", και κάνει escape τα $ : . του ADF (π.χ. pt1:cbEnter)
  assert(lib.hasId('<input id="j_password">', 'j_password'), 'hasId');
  assert(lib.hasId('<button id="pt1:cbEnter">', 'pt1:cbEnter'), 'hasId με ADF id');
  assert(!lib.hasId('<input name="j_password">', 'j_password'), 'hasId δεν πρέπει να πιάνει name=');
  assert(lib.decodeHtml('Οφειλή &amp; Πληρωμή') === 'Οφειλή & Πληρωμή', 'decodeHtml');
  assert(lib.viewState('<input name="jakarta.faces.ViewState" value="abc123">') === 'abc123', 'viewState');
});

await checkAsync('HyperHttp: GET + cookie jar + follow redirect', async () => {
  const run = vm.runInContext(`(async function(){
    var lib = require('hyper-http');
    var http = new lib.HyperHttp('/dl');
    var r = await http.follow('GET', 'https://example.gr/redirect');
    return { url: r.url, status: r.status, hasText: typeof r.text === 'string', cookie: JSON.stringify(http.jar) };
  })()`, ctx);
  const r = await run;
  assert(r.status === 200, `status ${r.status}`);
  assert(r.url === 'https://example.gr/done', `κατέληξε σε ${r.url}`);
  assert(r.cookie.includes('SESS'), 'το cookie δεν μπήκε στο jar');
});

await checkAsync('HyperHttp: το charset=UTF-8 μπαίνει στα form POST', async () => {
  const run = vm.runInContext(`(async function(){
    var lib = require('hyper-http');
    var http = new lib.HyperHttp('/dl');
    await http.follow('POST', 'https://example.gr/greek', { j_username: 'χρήστης' });
    return true;
  })()`, ctx);
  await run;
  const post = mock.requests.find((r) => r.method === 'POST');
  assert(post, 'δεν έγινε POST');
  const ct = post.headers['Content-Type'] || post.headers['content-type'] || '';
  assert(/charset=UTF-8/i.test(ct), `Content-Type ήταν: ${ct}`);
  assert(post.redirect === 'manual', `redirect ήταν: ${post.redirect}`);
});

await checkAsync('HyperHttp: getDoc αναγνωρίζει PDF από magic bytes', async () => {
  const run = vm.runInContext(`(async function(){
    var lib = require('hyper-http');
    var http = new lib.HyperHttp('/dl');
    var d = await http.getDoc('https://example.gr/pdf');
    return { hasBuffer: !!d.buffer, len: d.buffer ? d.buffer.length : 0, ct: d.ct };
  })()`, ctx);
  const d = await run;
  assert(d.hasBuffer, `δεν αναγνωρίστηκε ως PDF (content-type: ${d.ct})`);
  assert(d.len === pdfBytes.length, `μήκος ${d.len} αντί ${pdfBytes.length}`);
});

console.log('\nconfigs — και τα 18 αυτούσια\n');

const configDir = path.join(ASSETS, 'configs');
const configFiles = fs.readdirSync(configDir).filter((f) => f.endsWith('.js')).sort();

let loaded = 0;
for (const f of configFiles) {
  const id = f.replace(/\.js$/, '');
  check(`φορτώνεται: ${id}`, () => {
    const src = fs.readFileSync(path.join(configDir, f), 'utf8');
    const cfg = vm.runInContext(
      `__preload(${JSON.stringify(id)}, ${JSON.stringify(src)}), require(${JSON.stringify(id)})`,
      ctx, { filename: f },
    );
    assert(cfg && cfg.id, 'δεν εξάγει id');
    assert(typeof cfg.run === 'function', 'δεν εξάγει run()');
    assert(Array.isArray(cfg.inputs), 'δεν εξάγει inputs[]');
    loaded++;
  });
}

console.log(`\n${pass} πέρασαν, ${fail} απέτυχαν  (${loaded}/${configFiles.length} configs φορτώθηκαν)\n`);
process.exit(fail ? 1 : 0);
