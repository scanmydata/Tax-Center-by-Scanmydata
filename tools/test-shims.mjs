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
  const norm = (p) => String(p).replace(/\\/g, '/').split('/').filter((x) => x && x !== '.').join('/');

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
    // sync — οι διαδρομές κανονικοποιούνται όπως στο FileBridge.resolve():
    // τα κενά τμήματα και τα '.' πέφτουν, ώστε './run.log' και 'run.log' να
    // είναι το ίδιο αρχείο.
    fileWrite(p, dataB64, append) {
      const k = norm(p);
      const buf = b64.dec(dataB64);
      const prev = append && files.has(k) ? files.get(k) : Buffer.alloc(0);
      files.set(k, Buffer.concat([prev, buf]));
      return '';
    },
    fileRead(p) { const k = norm(p); return files.has(k) ? b64.enc(files.get(k)) : null; },
    fileExists(p) { const k = norm(p); return files.has(k) || dirs.has(k) ? '1' : '0'; },
    fileSize(p) { const k = norm(p); return files.has(k) ? String(files.get(k).length) : '-1'; },
    mkdirs(p) { dirs.add(norm(p)); return ''; },
    log(line) { logs.push(String(line)); return ''; },
    // Ίδια σειρά αναζήτησης με το EngineAssets.moduleSource: πρώτα η ρίζα του
    // engine, μετά ο φάκελος configs/.
    moduleSource(name) {
      for (const f of [path.join(ASSETS, `${name}.js`), path.join(ASSETS, 'configs', `${name}.js`)]) {
        if (fs.existsSync(f)) return fs.readFileSync(f, 'utf8');
      }
      return null;
    },
    finish(callId, resultJson) {
      const r = finishers.get(callId);
      if (r) { finishers.delete(callId); r(JSON.parse(resultJson)); }
    },
  };
  const finishers = new Map();
  return {
    bridge, files, logs, requests,
    setCtx: (c) => { ctx = c; },
    awaitFinish: (callId) => new Promise((res) => finishers.set(callId, res)),
  };
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
  // Το κλειδί είναι κανονικοποιημένο ('t/greek.txt'), όπως κάνει το FileBridge.
  assert(mock.files.get('t/greek.txt').toString('utf8') === 'Καλώς ήρθατε — ΑΦΜ',
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

console.log('\nrunner.js — πλήρης διαδρομή με ψεύτικη ΑΑΔΕ\n');

/**
 * Στήνει καθαρό context με mock που μιμείται τη ροή του `aadeLogin`:
 * home.htm -> OAM login -> auth_cred_submit -> incomefp -> login.done -> home.htm
 */
function aadeScenario({ goodCredentials, ldap }) {
  let homeHits = 0;
  const loginForm =
    '<html><body><form><input name="request_id" value="RQ-42">' +
    '<input name="username"><input name="password"></form></body></html>';
  const homeLoggedIn =
    '<html><body><ul>' +
    '<li><a href="/webtax/incomefp/">Δήλωση Ε1</a></li>' +
    '<li><a href="/taxisnet/info/protected/displayDebtInfoAndPay.htm">Οφειλές</a></li>' +
    '<li><a href="https://example.gr/εκτός">Άσχετο</a></li>' +
    '</ul></body></html>';

  return (req) => {
    const html = (body) => ({ status: 200, headers: { 'content-type': 'text/html; charset=UTF-8' }, body: Buffer.from(body, 'utf8') });
    if (req.url.includes('/taxisnet/info/protected/home.htm')) {
      homeHits++;
      // 1η φορά: μη συνδεδεμένος -> OAM φόρμα. Μετά το login: η πραγματική αρχική.
      return html(homeHits === 1 ? loginForm : homeLoggedIn);
    }
    if (req.url.includes('auth_cred_submit')) {
      return html(goodCredentials
        ? '<html><body>Καλώς ήρθατε</body></html>'
        : '<html><body>Καθορίστηκε λανθασμένο όνομα χρήστη ή κωδικός</body></html>');
    }
    // --- Μητρώο Επικοινωνίας (comregistry) ---
    if (req.url.includes('/getuserdata/username')) {
      return html('<?xml version="1.0"?><userdata><afm>123456783</afm></userdata>');
    }
    if (req.url.includes('/getLdapInfo/')) {
      return html('<?xml version="1.0"?><ldap>' + (ldap || '') + '</ldap>');
    }
    return html('<html><body>ok</body></html>');
  };
}

async function runConfig(scenarioOpts, configId, inputs) {
  const m = makeBridge(aadeScenario(scenarioOpts));
  const c = makeContext(m);
  vm.runInContext(fs.readFileSync(path.join(ASSETS, 'runner.js'), 'utf8'), c, { filename: 'runner.js' });
  const waiter = m.awaitFinish('run');
  vm.runInContext(
    `__runConfig('run', ${JSON.stringify(configId)}, ${JSON.stringify(JSON.stringify(inputs))}, '.')`,
    c, { filename: 'invoke' },
  );
  return { result: await waiter, mock: m };
}

await checkAsync('aade-login-check: επιτυχής σύνδεση γράφει τα menu links', async () => {
  const { result, mock } = await runConfig({ goodCredentials: true }, 'aade-login-check',
    { user: 'testuser', pass: 'testpass' });

  assert(result.ok === true, `ok=${result.ok} reason=${result.reason}`);
  assert(result.files.includes('aade_menu_links.json'), `files: ${JSON.stringify(result.files)}`);

  const written = mock.files.get('aade_menu_links.json');
  assert(written, 'δεν γράφτηκε το aade_menu_links.json');
  const links = JSON.parse(written.toString('utf8'));
  assert(links['Δήλωση Ε1'] === 'https://www1.aade.gr/webtax/incomefp/', `Ε1 -> ${links['Δήλωση Ε1']}`);
  assert(links['Οφειλές'], 'δεν βρέθηκε ο σύνδεσμος Οφειλές');
  assert(!links['Άσχετο'], 'ο άσχετος σύνδεσμος δεν έπρεπε να περάσει το φίλτρο');

  // Ο engine γράφει και τα dumps των σταδίων — χρήσιμα για post-mortem.
  assert(mock.files.has('01_aade_oam.html'), 'λείπει το dump 01');
  assert(mock.files.has('04_aade_home.html'), 'λείπει το dump 04');
  assert(mock.files.has('run.log'), 'λείπει το run.log');
});

await checkAsync('aade-login-check: λάθος κωδικός -> InvalidCredentials, όχι exception', async () => {
  const { result } = await runConfig({ goodCredentials: false }, 'aade-login-check',
    { user: 'testuser', pass: 'λάθος' });
  assert(result.ok === false, 'έπρεπε να αποτύχει');
  assert(result.reason === 'InvalidCredentials', `reason=${result.reason}`);
  assert(!result.error, `δεν έπρεπε να πεταχτεί exception: ${result.error}`);
});

await checkAsync('άγνωστο config -> δομημένο σφάλμα, όχι κρέμασμα', async () => {
  const { result } = await runConfig({ goodCredentials: true }, 'δεν-υπάρχει', {});
  assert(result.ok === false, 'έπρεπε να αποτύχει');
  assert(/Δεν βρέθηκε module/.test(result.reason), `reason=${result.reason}`);
});

await checkAsync('οι κωδικοί δεν διαρρέουν στο run.log', async () => {
  const { mock } = await runConfig({ goodCredentials: true }, 'aade-login-check',
    { user: 'testuser', pass: 'μυστικό-ΣΥΝΘΗΜΑΤΙΚΟ-42' });
  const log = mock.files.get('run.log').toString('utf8');
  const all = log + '\n' + mock.logs.join('\n');
  assert(!all.includes('μυστικό-ΣΥΝΘΗΜΑΤΙΚΟ-42'),
    'ο κωδικός βρέθηκε σε log — το Redactor.kt πρέπει να τον κόψει και στο native');
});

console.log('\naade-email — Μητρώο Επικοινωνίας ΑΑΔΕ\n');

async function lookupEmail(ldap) {
  const { result, mock } = await runConfig(
    { goodCredentials: true, ldap },
    'aade-email',
    { user: 'testuser', pass: 'testpass', vat: '' },
  );
  return { result, mock };
}

await checkAsync('προτεραιότητα mail2 -> mailemep -> mail', async () => {
  const all = await lookupEmail(
    '<mail2>pelatis@example.gr</mail2><mailemep>logistis@example.gr</mailemep><mail>palio@example.gr</mail>',
  );
  assert(all.result.ok, `reason=${all.result.reason}`);
  assert(all.result.out.email === 'pelatis@example.gr', `πήρα ${all.result.out.email}`);
  assert(all.result.out.source === 'mail2', `source=${all.result.out.source}`);

  // Χωρίς mail2 πέφτουμε στον εκπρόσωπο.
  const rep = await lookupEmail('<mailemep>logistis@example.gr</mailemep><mail>palio@example.gr</mail>');
  assert(rep.result.out.email === 'logistis@example.gr', `πήρα ${rep.result.out.email}`);
  assert(rep.result.out.source === 'mailemep', `source=${rep.result.out.source}`);

  // Και τελευταία η παλιά διεύθυνση του TAXISnet.
  const old = await lookupEmail('<mail>palio@example.gr</mail>');
  assert(old.result.out.email === 'palio@example.gr', `πήρα ${old.result.out.email}`);
});

await checkAsync('άκυρη διεύθυνση αγνοείται και πάμε στην επόμενη', async () => {
  const r = await lookupEmail('<mail2>δεν-ειναι-email</mail2><mailemep>logistis@example.gr</mailemep>');
  assert(r.result.out.email === 'logistis@example.gr', `πήρα ${r.result.out.email}`);
});

await checkAsync('το email πεζογραφείται', async () => {
  const r = await lookupEmail('<mail2>Pelatis@Example.GR</mail2>');
  assert(r.result.out.email === 'pelatis@example.gr', `πήρα ${r.result.out.email}`);
});

await checkAsync('χωρίς διεύθυνση -> NoEmail, με το JSON γραμμένο', async () => {
  const r = await lookupEmail('<mail2></mail2>');
  assert(r.result.ok === false, 'έπρεπε να αποτύχει');
  assert(r.result.reason === 'NoEmail', `reason=${r.result.reason}`);
  assert(r.mock.files.has('AADE_email_123456783.json'), 'το JSON έπρεπε να γραφτεί ούτως ή άλλως');
});

console.log('\npage-helper.js — ανάλυση επιλογέων Playwright\n');

/**
 * Το page-helper τρέχει μέσα στη σελίδα-στόχο και χρειάζεται DOM. Ελέγχουμε
 * όμως το κομμάτι που δεν αγγίζει DOM — την ανάλυση του `:has-text()` — που
 * είναι και το μόνο μη τετριμμένο: το `querySelectorAll` δεν ξέρει αυτόν τον
 * ψευδο-επιλογέα, και τα configs τον χρησιμοποιούν παντού.
 */
const selectorCtx = vm.createContext({ window: {}, document: {}, XMLHttpRequest: function () {} });
selectorCtx.window = selectorCtx;
vm.runInContext(fs.readFileSync(path.join(ASSETS, 'page-helper.js'), 'utf8'), selectorCtx, {
  filename: 'page-helper.js',
});
const parseSel = (sel) =>
  JSON.parse(vm.runInContext(`JSON.stringify(__page.__parse(${JSON.stringify(sel)}))`, selectorCtx));

check('απλός CSS περνά αυτούσιος', () => {
  const r = parseSel('a[href="https://www1.aade.gr/etak/"]');
  assert(r.length === 1, `μέρη: ${r.length}`);
  assert(r[0].css === 'a[href="https://www1.aade.gr/etak/"]', `css: ${r[0].css}`);
  assert(r[0].texts.length === 0, 'δεν έπρεπε να βρει κείμενα');
});

check('ADF id με άνω-κάτω τελεία δεν σπάει', () => {
  // `pt1:cbEnter` — τα configs το γράφουν ως attribute selector ακριβώς γι' αυτό.
  const r = parseSel('button[id="pt1:cbEnter"]');
  assert(r.length === 1 && r[0].css === 'button[id="pt1:cbEnter"]', JSON.stringify(r));
});

check('has-text εξάγεται και μένει καθαρό CSS', () => {
  const r = parseSel('a:has-text("Είσοδος στην εφαρμογή")');
  assert(r.length === 1, `μέρη: ${r.length}`);
  assert(r[0].css === 'a', `css: ${r[0].css}`);
  assert(r[0].texts[0] === 'Είσοδος στην εφαρμογή', `text: ${r[0].texts[0]}`);
});

check('λίστα με κόμμα χωρίζεται σωστά', () => {
  const r = parseSel('button:has-text("Συνδεση"), button:has-text("ΣΥΝΔΕΣΗ")');
  assert(r.length === 2, `μέρη: ${r.length}`);
  assert(r[0].texts[0] === 'Συνδεση' && r[1].texts[0] === 'ΣΥΝΔΕΣΗ', JSON.stringify(r));
});

check('κόμμα ΜΕΣΑ σε εισαγωγικά δεν χωρίζει', () => {
  // Πραγματικό: ονόματα εντύπων με κόμμα, π.χ. «Φ2, Φ4».
  const r = parseSel('a:has-text("Φ2, Φ4")');
  assert(r.length === 1, `χωρίστηκε λάθος σε ${r.length} μέρη`);
  assert(r[0].texts[0] === 'Φ2, Φ4', `text: ${r[0].texts[0]}`);
});

check('κόμμα μέσα σε αγκύλες δεν χωρίζει', () => {
  const r = parseSel('select[id="pt1:yearSelect::content"], select[name="pt1:yearSelect"]');
  assert(r.length === 2, `μέρη: ${r.length}`);
});

check('πολλαπλά has-text στο ίδιο μέρος', () => {
  const r = parseSel('tr:has-text("2025"):has-text("Οριστική")');
  assert(r.length === 1 && r[0].css === 'tr', `css: ${r[0].css}`);
  assert(r[0].texts.length === 2, `texts: ${JSON.stringify(r[0].texts)}`);
});

check('σκέτο has-text δίνει καθολικό επιλογέα', () => {
  const r = parseSel(':has-text("Καλώς ήρθατε")');
  assert(r[0].css === '*', `css: ${r[0].css}`);
});

check('οι επιλογείς του aade-enfia αναλύονται όλοι', () => {
  // Ακριβώς όσοι εμφανίζονται στο config — αν αλλάξει, θέλουμε να το μάθουμε εδώ.
  const real = [
    'a[href="https://www1.aade.gr/etak/"]',
    'a:has-text("Είσοδος στην εφαρμογή")',
    'input[name="username"]',
    'button[name="btn_login"]',
    'button:has-text("Συνδεση"), button:has-text("ΣΥΝΔΕΣΗ")',
    'button[id="pt1:cbEnter"]',
    'select[id="pt1:yearSelect::content"], select[name="pt1:yearSelect"]',
    'a[id="pt1:estatesAndLandsTab::disAcr"]',
    'a[id^="pt1:clPrintEkk"]',
    'a[id="pt1:iterPerStatus:0:cl24"]',
  ];
  for (const sel of real) {
    const r = parseSel(sel);
    assert(r.length >= 1 && r.every((p) => p.css.length > 0), `απέτυχε: ${sel}`);
  }
});

console.log(`\n${pass} πέρασαν, ${fail} απέτυχαν  (${loaded}/${configFiles.length} configs φορτώθηκαν)\n`);
process.exit(fail ? 1 : 0);
