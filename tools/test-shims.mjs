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
function aadeScenario({ goodCredentials, ldap, userdata, fysiko, epix }) {
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
      return html('<?xml version="1.0"?><userdata><afm>123456783</afm>' +
        (userdata || '') + '</userdata>');
    }
    if (req.url.includes('/getLdapInfo/')) {
      return html('<?xml version="1.0"?><ldap>' + (ldap || '') + '</ldap>');
    }
    // Τα δύο μητρώα. Κενό στοιχείο = «δεν υπάρχει», όπως ακριβώς απαντά η ΑΑΔΕ
    // για ιδιώτη χωρίς επιχείρηση (`<mhtrwoepixeirhshs/>`).
    if (req.url.includes('/getMhtrwoFusikou/')) {
      return html('<?xml version="1.0"?><mhtrwofusikou>' + (fysiko || '') + '</mhtrwofusikou>');
    }
    if (req.url.includes('/getMhtrwoEpixeirhshs/')) {
      return html('<?xml version="1.0"?><mhtrwoepixeirhshs>' + (epix || '') + '</mhtrwoepixeirhshs>');
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

console.log('\naade-profile — άντληση στοιχείων πελάτη\n');

const FYSIKO = '<afm>123456783</afm><armodiadoy>ΔΟΥ ΚΗΦΙΣΙΑΣ</armodiadoy>' +
  '<katastashforologoumenoy>ΚΑΝΟΝΙΚΗ</katastashforologoumenoy>' +
  '<epwnymoa>ΠΑΠΑΔΟΠΟΥΛΟΣ ΓΕΩΡΓΙΟΣ</epwnymoa>';
const EPIX = '<hmenarxhs>01/01/2015</hmenarxhs><doydescription>ΚΕΦΟΔΕ ΑΤΤΙΚΗΣ</doydescription>' +
  '<katastashepixeirhshs>ΕΝΕΡΓΗ</katastashepixeirhshs>';

/*
 * Διακομμένη επιχείρηση, **ακριβώς** όπως απαντά η ΑΑΔΕ: η κατάσταση λέει
 * «ΕΝΕΡΓΗ» και δίπλα της υπάρχει ημερομηνία διακοπής. Είναι η πραγματική
 * απάντηση για ΑΦΜ με παύση εργασιών από το 2018 — όχι κατασκευασμένο σενάριο.
 */
const EPIX_CLOSED = EPIX +
  '<hmdiakophs>11/12/2018</hmdiakophs><aitiadiakophs>ΠΑΥΣΗ ΕΡΓΑΣΙΩΝ</aitiadiakophs>';

async function profile(opts) {
  const { result } = await runConfig(
    { goodCredentials: true, ...opts },
    'aade-profile',
    { user: 'testuser', pass: 'testpass', vat: '' },
  );
  return result;
}

await checkAsync('μητρώο φυσικού χωρίς επιχείρηση -> ΙΔΙΩΤΗΣ', async () => {
  const r = await profile({
    userdata: '<onomatepwnymo>ΠΑΠΑΔΟΠΟΥΛΟΣ  ΓΕΩΡΓΙΟΣ</onomatepwnymo>',
    fysiko: FYSIKO,
  });
  assert(r.ok, `reason=${r.reason}`);
  assert(r.out.kind === 'ΙΔΙΩΤΗΣ', `kind=${r.out.kind}`);
  assert(r.out.hasAmka === true, 'ο ιδιώτης έχει ΑΜΚΑ');
  assert(r.out.doy === 'ΔΟΥ ΚΗΦΙΣΙΑΣ', `doy=${r.out.doy}`);
});

await checkAsync('φυσικό + επιχείρηση -> ΑΤΟΜΙΚΗ ΕΠΙΧΕΙΡΗΣΗ, ΔΟΥ από την επιχείρηση', async () => {
  const r = await profile({
    userdata: '<onomatepwnymo>ΠΑΠΑΔΟΠΟΥΛΟΣ  ΓΕΩΡΓΙΟΣ</onomatepwnymo>',
    fysiko: FYSIKO,
    epix: EPIX,
  });
  assert(r.out.kind === 'ΑΤΟΜΙΚΗ ΕΠΙΧΕΙΡΗΣΗ', `kind=${r.out.kind}`);
  assert(r.out.hasAmka === true, 'η ατομική έχει ΑΜΚΑ — υπάρχει φυσικό πρόσωπο από πίσω');
  assert(r.out.doy === 'ΚΕΦΟΔΕ ΑΤΤΙΚΗΣ', `doy=${r.out.doy}`);
});

await checkAsync('διακομμένη ατομική -> ΙΔΙΩΤΗΣ, παρότι η κατάσταση λέει ΕΝΕΡΓΗ', async () => {
  const r = await profile({
    userdata: '<onomatepwnymo>ΠΑΠΑΔΟΠΟΥΛΟΣ  ΓΕΩΡΓΙΟΣ</onomatepwnymo>',
    fysiko: FYSIKO,
    epix: EPIX_CLOSED,
  });
  assert(r.out.kind === 'ΙΔΙΩΤΗΣ', `kind=${r.out.kind}`);
  assert(r.out.formerBusiness === true, 'έπρεπε να σημειωθεί ως πρώην επιχείρηση');
  assert(r.out.businessEnd === '11/12/2018', `businessEnd=${r.out.businessEnd}`);
  assert(r.out.businessEndReason === 'ΠΑΥΣΗ ΕΡΓΑΣΙΩΝ', `αιτία=${r.out.businessEndReason}`);
  // Ο άνθρωπος παραμένει ενεργός πελάτης: έχει Ε1 κάθε χρόνο.
  assert(r.out.active === true, 'ο ιδιώτης μένει ενεργός μετά τη διακοπή');
  // Αρμόδια πλέον η ΔΟΥ του φυσικού προσώπου, όχι της κλειστής επιχείρησης.
  assert(r.out.doy === 'ΔΟΥ ΚΗΦΙΣΙΑΣ', `doy=${r.out.doy}`);
});

await checkAsync('διακομμένο νομικό πρόσωπο μένει νομικό πρόσωπο, αλλά ανενεργό', async () => {
  const r = await profile({
    userdata: '<longepwnymia>ΑΛΦΑ ΕΜΠΟΡΙΚΗ ΙΚΕ</longepwnymia>',
    epix: EPIX_CLOSED,
  });
  assert(r.out.kind === 'ΝΟΜΙΚΟ ΠΡΟΣΩΠΟ', `kind=${r.out.kind}`);
  assert(r.out.active === false, 'η διακομμένη εταιρεία δεν είναι ενεργός πελάτης');
  // Χωρίς φυσικό μητρώο, η μόνη ΔΟΥ που υπάρχει είναι της επιχείρησης.
  assert(r.out.doy === 'ΚΕΦΟΔΕ ΑΤΤΙΚΗΣ', `doy=${r.out.doy}`);
});

await checkAsync('μόνο επιχείρηση -> ΝΟΜΙΚΟ ΠΡΟΣΩΠΟ, χωρίς ΑΜΚΑ', async () => {
  const r = await profile({
    userdata: '<longepwnymia>ΑΛΦΑ ΕΜΠΟΡΙΚΗ ΙΚΕ</longepwnymia>',
    epix: EPIX,
  });
  assert(r.out.kind === 'ΝΟΜΙΚΟ ΠΡΟΣΩΠΟ', `kind=${r.out.kind}`);
  assert(r.out.hasAmka === false, 'το νομικό πρόσωπο ΔΕΝ έχει ΑΜΚΑ');
  assert(r.out.name === 'ΑΛΦΑ ΕΜΠΟΡΙΚΗ ΙΚΕ', `name=${r.out.name}`);
  assert(r.out.firstName === '', 'το νομικό πρόσωπο δεν έχει όνομα');
});

await checkAsync('το ονοματεπώνυμο σπάει στα δύο κενά της ΑΑΔΕ', async () => {
  const r = await profile({
    userdata: '<onomatepwnymo>ΠΑΠΑΔΟΠΟΥΛΟΣ  ΓΕΩΡΓΙΟΣ</onomatepwnymo>',
    fysiko: FYSIKO,
  });
  assert(r.out.name === 'ΠΑΠΑΔΟΠΟΥΛΟΣ', `name=${r.out.name}`);
  assert(r.out.firstName === 'ΓΕΩΡΓΙΟΣ', `firstName=${r.out.firstName}`);
});

await checkAsync('σύνθετο επώνυμο με ένα κενό ΔΕΝ σπάει στα τυφλά', async () => {
  // «ΠΑΠΑ ΓΕΩΡΓΙΟΥ» χωρίς διπλό κενό: δεν ξέρουμε πού τελειώνει το επώνυμο.
  // Καλύτερα όλο στο ένα πεδίο παρά λάθος σπάσιμο σε email προς τον πελάτη.
  const r = await profile({
    userdata: '<onomatepwnymo>ΠΑΠΑ ΓΕΩΡΓΙΟΥ</onomatepwnymo>',
    fysiko: FYSIKO,
  });
  assert(r.out.name === 'ΠΑΠΑ ΓΕΩΡΓΙΟΥ', `name=${r.out.name}`);
  assert(r.out.firstName === '', `firstName=${r.out.firstName}`);
});

await checkAsync('χωρίς κανένα μητρώο -> NoRegistry', async () => {
  const r = await profile({ userdata: '<onomatepwnymo>ΚΑΝΕΙΣ</onomatepwnymo>' });
  assert(r.ok === false, 'έπρεπε να αποτύχει');
  assert(r.reason === 'NoRegistry', `reason=${r.reason}`);
});

await checkAsync('η διεύθυνση επικοινωνίας έρχεται μαζί, σε μία σύνδεση', async () => {
  const r = await profile({
    userdata: '<onomatepwnymo>ΠΑΠΑΔΟΠΟΥΛΟΣ  ΓΕΩΡΓΙΟΣ</onomatepwnymo>',
    fysiko: FYSIKO,
    ldap: '<mail2>Pelatis@Example.GR</mail2>',
  });
  assert(r.out.email === 'pelatis@example.gr', `email=${r.out.email}`);
  assert(r.out.emailSource === 'mail2', `source=${r.out.emailSource}`);
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


console.log('\naade-debts.js — δοσολόγιο και κουμπί εκτύπωσης\n');

/*
 * Οι δύο συναρτήσεις που κρίνουν αν θα φύγει δοσολόγιο στον πελάτη ζουν στο
 * config και δεν εξάγονται (το `module.exports` είναι το συμβόλαιο του engine,
 * όχι δοκιμαστικό σημείο). Τις βγάζουμε από τον ΙΔΙΟ κώδικα που φορτώνει το
 * κινητό και τις τρέχουμε σε καθαρό context.
 *
 * Το δείγμα HTML είναι η δομή των πραγματικών σελίδων της ΑΑΔΕ — κρυφοί πίνακες
 * `installmentInfo_N`/`generalInstallmentInfo_N`, φόρμα εκτύπωσης που γεμίζει η
 * `doViewPdf()` — με συνθετικά νούμερα.
 */
const debtsSrc = fs.readFileSync(path.join(ASSETS, 'configs', 'aade-debts.js'), 'utf8');
const debtsCtx = vm.createContext({ URLSearchParams });
vm.runInContext(
  debtsSrc.slice(debtsSrc.indexOf('function parseInstallments'), debtsSrc.indexOf('const BASE =')),
  debtsCtx,
  { filename: 'aade-debts-helpers.js' },
);
debtsCtx.strip = (s) => String(s || '')
  .replace(/<[^>]+>/g, ' ').replace(/&nbsp;/g, ' ').replace(/&euro;/g, '€')
  .replace(/&amp;/g, '&').replace(/\s+/g, ' ').trim();

const TO_PAGE = `<html><body>
  <form name="viewPdf" method="post" action="debtInfoPdf.htm" target="_blank">
    <input type="hidden" name="mchDoy" value="" />
    <input type="hidden" name="mchDept" value="" />
    <input type="hidden" name="mchYear" value="" />
    <input type="hidden" name="withoutAmounts" value="false">
  </form>
  <input type="button" name="printPayment" value="Εκτύπωση"
    onclick="doViewPdf(document.viewPdf, 9776, 1,
             2026);" class="navbtn" />
  <script>
  function doViewPdf(frm, mchDoy, mchDept, mchYear) {
    frm.action = 'debtInfoPdf.htm';
    frm.elements['mchDoy'].value = mchDoy;
    frm.elements['mchDept'].value = mchDept;
    frm.elements['mchYear'].value = mchYear;
    frm.submit();
  }
  </script></body></html>`;

check('το κουμπί «Εκτύπωση» δίνει action και τιμές με τη σωστή σειρά', () => {
  const pf = JSON.parse(vm.runInContext(
    `JSON.stringify(printForm(${JSON.stringify(TO_PAGE)}))`, debtsCtx));
  assert(pf, 'δεν βρέθηκε φόρμα εκτύπωσης');
  assert(pf.action === 'debtInfoPdf.htm', `action: ${pf.action}`);
  // Το πρώτο όρισμα της doViewPdf είναι η ΦΟΡΜΑ, όχι παράμετρος: μια μετατόπιση
  // κατά ένα εδώ ζητά την ταυτότητα ΑΛΛΗΣ οφειλής, και η πύλη απαντά κανονικά.
  assert(pf.fields.mchDoy === '9776', `mchDoy: ${pf.fields.mchDoy}`);
  assert(pf.fields.mchDept === '1', `mchDept: ${pf.fields.mchDept}`);
  assert(pf.fields.mchYear === '2026', `mchYear: ${pf.fields.mchYear}`);
  // Τα hidden που δεν αγγίζει το κουμπί μένουν όπως τα έδωσε η σελίδα.
  assert(pf.fields.withoutAmounts === 'false', `withoutAmounts: ${pf.fields.withoutAmounts}`);
});

check('σελίδα χωρίς κουμπί εκτύπωσης δεν εφευρίσκει φόρμα', () => {
  const pf = vm.runInContext(
    `JSON.stringify(printForm('<html><body>Δεν βρέθηκαν εγγραφές</body></html>') || null)`, debtsCtx);
  assert(pf === 'null', `περίμενα null, πήρα ${pf}`);
});

/*
 * Η σελίδα **Ταυτότητας Ρυθμισμένης Οφειλής**. Η δομή είναι αυτή της
 * πραγματικής (επαληθεύτηκε ζωντανά, 30 Σεπτεμβρίου 2026): άλλη συνάρτηση
 * εκτύπωσης από τη σελίδα των οφειλών, άλλο endpoint, και **δύο** φόρμες — με
 * ποσά και χωρίς. Τα νούμερα είναι συνθετικά.
 */
const TRO_PAGE = `<html><body>
  <script>
  function doViewArrPdf(frm, arnDoy, arnDept, arnYear, arrAA) {
    frm.action = 'arrDebtInfoPdf.htm';
    frm.target = '_blank';
    frm.elements['arnDoy'].value = arnDoy;
    frm.elements['arnDept'].value = arnDept;
    frm.elements['arnYear'].value = arnYear;
    frm.elements['arrAA'].value = arrAA;
    frm.submit();
  }
  </script>
  <table>
    <tr><td class="wintxtB10nowrap">Τύπος Ρύθμισης</td><td>ΠΑΓΙΑ ΕΩΣ 24 ΔΟΣΕΙΣ</td></tr>
    <tr><td class="wintxtB10nowrap">Ποσό δόσης της 30/09/2026</td><td>92,78&nbsp;&euro;</td></tr>
    <tr><td class="wintxtB10nowrap">Ταυτότητα Ρυθμισμένης Οφειλής</td>
        <td align="left">123456783&nbsp;900000001&nbsp;200000000001</td></tr>
  </table>
  <form name="viewArrPdf" method="post" action="arrDebtInfoPdf.htm" target="_blank">
    <input type="hidden" name="arnDoy" value="" />
    <input type="hidden" name="arnDept" value="" />
    <input type="hidden" name="arnYear" value="" />
    <input type="hidden" name="arrAA" value="" />
    <input type="hidden" name="withoutAmounts" value="false">
  </form>
  <form name="viewNoValuesArrPdf" method="post" action="arrDebtInfoPdf.htm" target="_blank">
    <input type="hidden" name="arnDoy" value="" />
    <input type="hidden" name="arnDept" value="" />
    <input type="hidden" name="arnYear" value="" />
    <input type="hidden" name="arrAA" value="" />
    <input type="hidden" name="withoutAmounts" value="true">
  </form>
  <input type="button" name="printPayment" value="Εκτύπωση"
    onclick="doViewArrPdf(document.viewArrPdf, 9776, 1,
    2026, 7000001);" class="navbtn" />
  <input type="button" name="printPaymentNoAmnt" value="Εκτύπωση χωρίς Ποσά"
    onclick="doViewArrPdf(document.viewNoValuesArrPdf, 9776, 1,
    2026, 7000001);" class="navbtn" />
  </body></html>`;

check('η ταυτότητα ρύθμισης τυπώνεται με doViewArrPdf, όχι με doViewPdf', () => {
  // Το πραγματικό σφάλμα, κλειδωμένο: με το όνομα των οφειλών δεν βρίσκεται
  // κουμπί, και η ρύθμιση έμενε χωρίς ταυτότητα ενώ το δοσολόγιο έβγαινε.
  const none = vm.runInContext(
    `JSON.stringify(printForm(${JSON.stringify(TRO_PAGE)}) || null)`, debtsCtx);
  assert(none === 'null', `το doViewPdf δεν έπρεπε να ταιριάξει: ${none}`);

  const pf = JSON.parse(vm.runInContext(
    `JSON.stringify(printForm(${JSON.stringify(TRO_PAGE)}, 'doViewArrPdf'))`, debtsCtx));
  assert(pf, 'δεν βρέθηκε φόρμα εκτύπωσης ΤΡΟ');
  assert(pf.action === 'arrDebtInfoPdf.htm', `action: ${pf.action}`);
  assert(pf.fields.arnDoy === '9776', `arnDoy: ${pf.fields.arnDoy}`);
  assert(pf.fields.arnDept === '1', `arnDept: ${pf.fields.arnDept}`);
  assert(pf.fields.arnYear === '2026', `arnYear: ${pf.fields.arnYear}`);
  assert(pf.fields.arrAA === '7000001', `arrAA: ${pf.fields.arrAA}`);
});

check('από τις δύο φόρμες κρατιέται αυτή ΜΕ τα ποσά', () => {
  // Η «Εκτύπωση χωρίς Ποσά» δίνει σημείωμα που δεν λέει πόσα να πληρώσει ο
  // πελάτης. Είναι η δεύτερη φόρμα της σελίδας, οπότε ένα σκέτο «βρες την
  // πρώτη» δουλεύει σήμερα και σπάει την ημέρα που αλλάξει η σειρά.
  const pf = JSON.parse(vm.runInContext(
    `JSON.stringify(printForm(${JSON.stringify(TRO_PAGE)}, 'doViewArrPdf'))`, debtsCtx));
  assert(pf.fields.withoutAmounts === 'false', `withoutAmounts: ${pf.fields.withoutAmounts}`);

  const flipped = TRO_PAGE
    .replace('name="viewArrPdf"', 'name="zzzArrPdf"')
    .replace('document.viewArrPdf', 'document.zzzArrPdf');
  const both = JSON.parse(vm.runInContext(
    `JSON.stringify(printForm(${JSON.stringify(flipped)}, 'doViewArrPdf'))`, debtsCtx));
  assert(both.fields.withoutAmounts === 'false', 'και με άλλο όνομα φόρμας, τα ποσά μένουν');
});

check('ο κωδικός πληρωμής διαβάζεται από το κελί του, χωρίς την ετικέτα', () => {
  const code = vm.runInContext(
    `paymentCode(${JSON.stringify(TRO_PAGE)}, strip)`, debtsCtx);
  assert(code === '123456783 900000001 200000000001', `code: ${code}`);
  // Ταυτότητα ΚΕΑΟ (RF…) εξακολουθεί να πιάνεται.
  const rf = vm.runInContext(
    `paymentCode('<div>RF00000000000000001000001</div>', strip)`, debtsCtx);
  assert(rf === 'RF00000000000000001000001', `rf: ${rf}`);
  assert(vm.runInContext(`paymentCode('<div>τίποτα</div>', strip)`, debtsCtx) === null, 'χωρίς κωδικό -> null');
});

const ROWS_PAGE = `<html><body>
  <table class="table" id="generalInstallmentInfo_0" style="display:none">
    <tr><td>Αριθμός δόσεων</td><td>2</td></tr>
    <tr><td>Ημ/νία πρώτης δόσης</td><td>31/07/2026</td></tr>
  </table>
  <table class="table" id="installmentInfo_0" style="display:none">
    <tr class="tblHeader"><td>Α/Α δόσης</td><td>Ημ/νία λήξης δόσης</td><td>Υπόλοιπο Δόσης</td></tr>
    <tr><td>1</td><td>31/07/2026</td><td>0,00&nbsp;&euro;</td></tr>
    <tr><td>2</td><td>31/08/2026</td><td>1.000,00&nbsp;&euro;</td></tr>
  </table></body></html>`;

check('οι κρυφοί πίνακες δόσεων διαβάζονται χωριστά ο ένας από τον άλλον', () => {
  const inst = JSON.parse(vm.runInContext(
    `JSON.stringify(parseInstallments(${JSON.stringify(ROWS_PAGE)}, strip))`, debtsCtx));
  const gen = JSON.parse(vm.runInContext(
    `JSON.stringify(parseGeneralInstallments(${JSON.stringify(ROWS_PAGE)}, strip))`, debtsCtx));
  // `id="installmentInfo_0"` ΔΕΝ πρέπει να πιάσει το `generalInstallmentInfo_0`.
  assert(Object.keys(inst).length === 1, `πίνακες δόσεων: ${Object.keys(inst)}`);
  assert(inst['0'].headers[0] === 'Α/Α δόσης', JSON.stringify(inst['0'].headers));
  assert(inst['0'].rows.length === 2, `γραμμές: ${inst['0'].rows.length}`);
  assert(inst['0'].rows[1][2] === '1.000,00 €', `ποσό: ${inst['0'].rows[1][2]}`);
  assert(gen['0'].rows[0][1] === '2', JSON.stringify(gen['0'].rows));
});

check('το aade-debts δηλώνει τη λειτουργία «μόνο δεδομένα»', () => {
  // Η καρτέλα οφειλών ενημερώνεται με `pdf=όχι`. Αν το input χαθεί από το
  // config, η εφαρμογή θα συνεχίσει να το στέλνει και ο runner να το αγνοεί —
  // και κάθε αυτόματη ενημέρωση θα κατέβαζε ξανά όλα τα PDF.
  const cfg = vm.runInContext(
    `__preload('aade-debts', ${JSON.stringify(debtsSrc)}), require('aade-debts')`,
    ctx, { filename: 'aade-debts.js' },
  );
  const pdf = cfg.inputs.find((i) => i.key === 'pdf');
  assert(pdf && pdf.optional, 'λείπει το προαιρετικό input pdf');
  assert(/wantPdf/.test(debtsSrc) && /if \(!wantPdf\)/.test(debtsSrc), 'το pdf δεν ελέγχεται στη ροή');
});

console.log('\nkeao-debts.js — επιλογή μητρώων\n');

/*
 * Το φίλτρο κρίνεται στη γραμμή της λίστας φορέων, ΠΡΙΝ ανοίξει ο φορέας. Η
 * γραμμή έχει ΑΜΟ στο πρώτο κελί και Αρ. Μητρώου στο τρίτο — και ο λογιστής
 * μπορεί να δώσει οποιοδήποτε από τα δύο.
 */
const keaoCfg = vm.runInContext(
  `__preload('keao-debts', ${JSON.stringify(fs.readFileSync(path.join(ASSETS, 'configs', 'keao-debts.js'), 'utf8'))}), require('keao-debts')`,
  ctx, { filename: 'keao-debts.js' },
);
const keaoStrip = (s) => String(s || '').replace(/<[^>]+>/g, ' ').replace(/\s+/g, ' ').trim();
const CARRIER_ROW = '<td role="gridcell"> 1000001 </td><td>Ληξιπρόθεσμο - ΕΦΚΑ</td>' +
  '<td><span>9000000001</span></td><td>ΕΠΩΝΥΜΙΑ</td><td><button id="amoForm:dt-table:0:b"></button></td>';

check('χωρίς επιλογή μητρώων κατεβαίνουν όλοι οι φορείς', () => {
  assert(keaoCfg.carrierWanted(CARRIER_ROW, [], keaoStrip) === true, 'κενή λίστα = όλοι');
});

check('το μητρώο επιλέγεται με ΑΜΟ ή με Αρ. Μητρώου', () => {
  assert(keaoCfg.carrierWanted(CARRIER_ROW, ['1000001'], keaoStrip), 'ΑΜΟ');
  assert(keaoCfg.carrierWanted(CARRIER_ROW, ['9000000001'], keaoStrip), 'Αρ. Μητρώου');
  assert(!keaoCfg.carrierWanted(CARRIER_ROW, ['1000002'], keaoStrip), 'άλλος ΑΜΟ δεν περνά');
  // Όχι substring: ο ΑΜΟ 100000 δεν είναι ο 1000001.
  assert(!keaoCfg.carrierWanted(CARRIER_ROW, ['100000'], keaoStrip), 'μέρος αριθμού δεν περνά');
});

console.log('\naade-enfia-http.js — ETAK χωρίς browser\n');

const enfiaCfg = vm.runInContext(
  `__preload('aade-enfia-http', ${JSON.stringify(fs.readFileSync(path.join(ASSETS, 'configs', 'aade-enfia-http.js'), 'utf8'))}), require('aade-enfia-http')`,
  ctx, { filename: 'aade-enfia-http.js' },
);

check('η φόρμα f1 δίνει _ctrlstate και ViewState', () => {
  const html = '<form id="f1" name="f1" method="POST" action="/etak/faces/main.jspx?_adf.ctrl-state=abc&amp;x=1">' +
    '<input type="hidden" name="javax.faces.ViewState" value="!-42"></form>';
  const vs = enfiaCfg.viewState(html);
  assert(vs.ctrlstate === '/etak/faces/main.jspx?_adf.ctrl-state=abc&x=1', `ctrlstate: ${vs.ctrlstate}`);
  assert(vs.viewState === '!-42', `ViewState: ${vs.viewState}`);
});

check('το έτος βρίσκεται μόνο μέσα στο yearSelect', () => {
  const html = '<select id="other"><option value="9">2025</option></select>' +
    '<select id="pt1:yearSelect::content"><option value="0" selected>2026</option>' +
    '<option value="1">2025</option></select>';
  const y = enfiaCfg.yearOption(html, '2025');
  assert(y && y.value === '1' && !y.selected, JSON.stringify(y));
  assert(enfiaCfg.yearOption(html, '2026').selected, 'το 2026 είναι επιλεγμένο');
  assert(enfiaCfg.yearOption(html, '2019') === null, 'έτος που λείπει = δεν υπάρχει υποχρέωση');
});

check('η δήλωση Ε9 αναγνωρίζεται και με ελληνικά σε &#NNN;', () => {
  // «Αρ. δήλωσης:» όπως το στέλνει το ADF partial-response.
  const enc = [...'Αρ. δήλωσης:'].map((ch) => (ch.charCodeAt(0) > 127 ? `&#${ch.charCodeAt(0)};` : ch)).join('');
  const d = enfiaCfg.e9Declaration(`<a href="#">${enc} 1234567/2025</a>`);
  assert(d.submitted && d.declNum === '1234567/2025', JSON.stringify(d));
  assert(!enfiaCfg.e9Declaration('<div>τίποτα</div>').submitted, 'χωρίς δήλωση');
});

console.log('\neasynotify — «Τα Αιτήματά μου» της ΑΑΔΕ\n');

// Το σχήμα του eticketaade όπως επαληθεύτηκε ζωντανά (2 Οκτωβρίου 2026): φάκελος
// { entityModels, pageCount }, messageId ως αριθμός, ημερομηνίες «yyyy-MM-dd HH:mm:ss».
// Αριθμοί υποθέσεων και κείμενα είναι συνθετικά.
function requestRow(id, status, code, answer) {
  return {
    messageId: id, caseNumber: `100000${id}/20260901/0001`,
    submittedDate: '2026-09-01 09:15:00', updatedDate: '2026-09-30 10:00:00',
    messageStatus: code, messageStatusText: status,
    orgGroupText: 'Δ.Ο.Υ. ΔΟΚΙΜΗΣ', thematicalGroupText: 'Εισόδημα', diadikasiaText: 'Τροποποιητική δήλωση Ε1',
    messageText: 'Παρακαλώ για την εκκαθάριση.', answerText: answer, transactorVat: '123456783', rejected: false,
  };
}

async function runRequests(pages) {
  const base = aadeScenario({ goodCredentials: true });
  const posts = [];
  const json = (o) => ({ status: 200, headers: { 'content-type': 'application/json' }, body: Buffer.from(JSON.stringify(o), 'utf8') });
  const m = makeBridge((req) => {
    if (req.url.includes('/api/amsmsg/getUser')) return json({ userData: { userVat: '123456783' } });
    if (req.url.includes('/api/amsmsg/filterMessages')) {
      const body = JSON.parse(req.body);
      posts.push({ body, contentType: req.headers['Content-Type'] });
      return pages(body, json);
    }
    return base(req);
  });
  const c = makeContext(m);
  vm.runInContext(fs.readFileSync(path.join(ASSETS, 'runner.js'), 'utf8'), c, { filename: 'runner.js' });
  const waiter = m.awaitFinish('run');
  // Τα inputs όπως τα στέλνει η εφαρμογή: το `vat` είναι το ΑΦΜ του πελάτη.
  const inputs = { user: 'testuser', pass: 'testpass', vat: '123456783', which: 'REQUESTS', dataOnly: '1' };
  vm.runInContext(
    `__runConfig('run', 'easynotify', ${JSON.stringify(JSON.stringify(inputs))}, '.')`,
    c, { filename: 'invoke' },
  );
  return { result: await waiter, mock: m, posts };
}

await checkAsync('which=REQUESTS διαβάζει όλες τις σελίδες και γράφει EASYNOTIFY_<ΑΦΜ>.json', async () => {
  const { result, mock, posts } = await runRequests((body, json) => json({
    pageCount: 2,
    entityModels: body.pageIndex === 0
      ? [requestRow(1, 'Απαντημένο', 3, 'Η δήλωσή σας εκκαθαρίστηκε.')]
      : [requestRow(2, 'Σε επεξεργασία', 2, null)],
  }));
  assert(result.ok === true, `ok=${result.ok} reason=${result.reason}`);
  assert(result.files.includes('EASYNOTIFY_123456783.json'), `files: ${JSON.stringify(result.files)}`);

  // Σώμα JSON, σελίδα από το μηδέν, και το ΑΦΜ του πελάτη ως υποβάλλων.
  assert(posts.length === 2, `κλήσεις: ${posts.length}`);
  assert(posts[0].body.pageIndex === 0 && posts[1].body.pageIndex === 1, 'pageIndex 0-based');
  assert(posts[0].body.transactorVat === '123456783', `transactorVat: ${posts[0].body.transactorVat}`);
  assert(/application\/json/.test(posts[0].contentType || ''), `Content-Type: ${posts[0].contentType}`);

  const out = JSON.parse(mock.files.get('EASYNOTIFY_123456783.json').toString('utf8'));
  assert(out.aadeRequests.count === 2, `count: ${out.aadeRequests.count}`);
  assert(out.aadeRequests.items[0].messageId === 1, 'messageId αριθμός, όπως το δίνει η πύλη');
  assert(out.aadeRequests.items[1].answerText === null, 'αναπάντητο = null');
  // Μόνο τα αιτήματα: τα «Μηνύματά μου» και το myPROPERTY δεν ζητήθηκαν.
  assert(!('aadeMessages' in out) && !('myProperty' in out), 'ζητήθηκαν μόνο τα αιτήματα');
  assert(!mock.requests.some((r) => r.url.includes('mymessages') || r.url.includes('myPROPERTY')),
    'δεν έπρεπε να ανοίξει άλλη υπηρεσία');
});

await checkAsync('όταν το τμήμα των αιτημάτων σκάσει, το JSON γράφει «ERR:» και όχι κενή λίστα', async () => {
  const { result, mock } = await runRequests(() => ({ error: 'σύνδεση διακόπηκε' }));
  // Το config επιστρέφει ok — γι' αυτό η εφαρμογή διαβάζει το «ERR:» ως αποτυχία.
  assert(result.ok === true, `ok=${result.ok}`);
  const out = JSON.parse(mock.files.get('EASYNOTIFY_123456783.json').toString('utf8'));
  assert(typeof out.aadeRequests === 'string' && out.aadeRequests.startsWith('ERR:'), JSON.stringify(out.aadeRequests));
});

console.log('\naade-request — ένα αίτημα, πλήρες, με τα συνημμένα του\n');

// Το σχήμα του /fetch όπως επαληθεύτηκε ζωντανά (9 Οκτωβρίου 2026): messageFiles με
// fileId αριθμό και fileOrigin «attachedFile» (του πολίτη) ή «internalFile» (της
// υπηρεσίας). Το getMessageComm ήταν παντού [] — το σχήμα του εδώ είναι υπόθεση, και
// γι' αυτό το config ψάχνει αρχεία σε οποιοδήποτε βάθος. Όλα τα στοιχεία συνθετικά.
const PDF_A = Buffer.concat([Buffer.from('%PDF-1.4\n', 'latin1'), Buffer.from([0, 255, 128, 10, 13, 37])]);
const PNG_B = Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a, 0, 1, 2, 3]);

async function runRequest(inputs, { detail, comm, fileStatus } = {}) {
  const base = aadeScenario({ goodCredentials: true });
  const calls = [];
  const json = (o, status = 200) => ({ status, headers: { 'content-type': 'application/json' }, body: Buffer.from(JSON.stringify(o), 'utf8') });
  const m = makeBridge((req) => {
    if (!req.url.includes('/api/amsmsg/')) return base(req);
    calls.push({ method: req.method, url: req.url, body: req.body, contentType: (req.headers || {})['Content-Type'] });
    if (req.url.endsWith('/getUser')) return json({ userData: { userVat: '123456783' } });
    if (req.url.endsWith('/fetch')) return detail ? json(detail(req.body)) : json({ error: 'not found' }, 404);
    if (req.url.includes('/getMessageComm/')) return json(comm ?? []);
    if (req.url.includes('/downloadFile/')) {
      const id = req.url.split('/').pop();
      if (fileStatus && fileStatus[id]) return { status: fileStatus[id], headers: {}, body: Buffer.alloc(0) };
      return id === '9002'
        ? { status: 200, headers: { 'content-type': 'image/png', 'content-disposition': 'attachment; filename=x' }, body: PNG_B }
        : { status: 200, headers: { 'content-type': 'application/pdf', 'content-disposition': 'attachment; filename=x.pdf' }, body: PDF_A };
    }
    return json({}, 404);
  });
  const c = makeContext(m);
  vm.runInContext(fs.readFileSync(path.join(ASSETS, 'runner.js'), 'utf8'), c, { filename: 'runner.js' });
  const waiter = m.awaitFinish('run');
  vm.runInContext(
    `__runConfig('run', 'aade-request', ${JSON.stringify(JSON.stringify({ user: 'testuser', pass: 'testpass', vat: '123456783', ...inputs }))}, '.')`,
    c, { filename: 'invoke' },
  );
  return { result: await waiter, mock: m, calls };
}

const requestDetail = () => ({
  caseNumber: '1000001/20260901/0001', submittedDate: '2026-09-01', messageStatus: 3,
  messageText: 'Παρακαλώ για την εκκαθάριση.', answerText: 'Σας επισυνάπτουμε την πράξη.',
  thematicalGroupText: 'Εισόδημα', diadikasiaText: 'Τροποποιητική δήλωση Ε1', isArchived: false,
  messageFiles: [
    { fileId: 9001, fileName: 'Αίτηση πελάτη: τελική?.pdf', fileOrigin: 'attachedFile', fileSize: PDF_A.length },
    { fileId: 9002, fileName: 'σφραγίδα', fileOrigin: 'internalFile', fileSize: PNG_B.length },
  ],
});

await checkAsync('ανοίγει το αίτημα όπως το SPA και κατεβάζει συνημμένα υποβολής ΚΑΙ απάντησης', async () => {
  const { result, mock, calls } = await runRequest({ id: '4242' }, {
    detail: requestDetail,
    comm: [{ messageCommText: 'Συμπληρωματικά στοιχεία.', msgAttachedFiles: [{ fileId: 9003, fileName: 'extra.pdf' }] }],
  });
  assert(result.ok === true, `ok=${result.ok} reason=${result.reason}`);

  // Το σώμα του /fetch είναι ο αριθμός γυμνός — όχι αντικείμενο, όχι σε εισαγωγικά.
  const fetchCall = calls.find((x) => x.url.endsWith('/fetch'));
  assert(fetchCall.method === 'POST' && fetchCall.body === '4242', `body: ${JSON.stringify(fetchCall.body)}`);
  assert(/application\/json/.test(fetchCall.contentType || ''), `Content-Type: ${fetchCall.contentType}`);
  assert(calls.some((x) => x.url.endsWith('/getMessageComm/4242')), 'δεν ζητήθηκε η αλληλογραφία');

  // Ονόματα ασφαλή για δίσκο, με τα ελληνικά στη θέση τους· η κατάληξη που λείπει
  // βγαίνει από τον τύπο της απάντησης.
  const names = result.files;
  assert(names.includes('AITIMA_4242_9001_Αίτηση_πελάτη_τελική.pdf'), `files: ${JSON.stringify(names)}`);
  assert(names.includes('AITIMA_4242_9002_σφραγίδα.png'), `files: ${JSON.stringify(names)}`);
  assert(names.includes('AITIMA_4242_9003_extra.pdf'), `files: ${JSON.stringify(names)}`);
  // Τα bytes περνούν αυτούσια — και όταν δεν είναι PDF.
  assert(mock.files.get('AITIMA_4242_9001_Αίτηση_πελάτη_τελική.pdf').equals(PDF_A), 'το PDF αλλοιώθηκε');
  assert(mock.files.get('AITIMA_4242_9002_σφραγίδα.png').equals(PNG_B), 'το PNG αλλοιώθηκε');

  const out = JSON.parse(mock.files.get('AITIMA_4242.json').toString('utf8'));
  assert(out.detail.answerText === 'Σας επισυνάπτουμε την πράξη.', 'το πλήρες αίτημα μένει αυτούσιο');
  assert(out.files.length === 3, `files: ${out.files.length}`);
  assert(out.files[0].fileOrigin === 'attachedFile' && out.files[1].fileOrigin === 'internalFile', 'προέλευση ανά αρχείο');
  assert(out.files[2].fileOrigin === 'commFile', `αρχείο αλληλογραφίας: ${out.files[2].fileOrigin}`);
  assert(out.files.every((f) => f.savedFile && f.bytes > 0), 'κάθε αρχείο γράφει πού σώθηκε');
  assert(out.files[0].fileName === 'Αίτηση πελάτη: τελική?.pdf', 'το όνομα της πύλης μένει όπως ήταν');
});

await checkAsync('files=0: μόνο το κείμενο, κανένα αίτημα λήψης αρχείου', async () => {
  const { result, mock, calls } = await runRequest({ id: '4242', files: '0' }, { detail: requestDetail });
  assert(result.ok === true, `ok=${result.ok}`);
  assert(JSON.stringify(result.files) === '["AITIMA_4242.json"]', `files: ${JSON.stringify(result.files)}`);
  assert(!calls.some((x) => x.url.includes('/downloadFile/')), 'δεν έπρεπε να κατέβει αρχείο');
  const out = JSON.parse(mock.files.get('AITIMA_4242.json').toString('utf8'));
  assert(out.files.length === 2 && out.files.every((f) => !f.savedFile), 'τα αρχεία αναφέρονται, δεν σώζονται');
});

await checkAsync('ένα αρχείο που δεν κατεβαίνει δεν ρίχνει το αίτημα — γράφεται το σφάλμα του', async () => {
  const { result, mock } = await runRequest({ id: '4242' }, { detail: requestDetail, fileStatus: { 9002: 500 } });
  assert(result.ok === true, `ok=${result.ok}`);
  const out = JSON.parse(mock.files.get('AITIMA_4242.json').toString('utf8'));
  assert(out.files[0].savedFile && !out.files[0].error, 'το πρώτο αρχείο κατέβηκε');
  assert(!out.files[1].savedFile && /500/.test(out.files[1].error), `σφάλμα: ${out.files[1].error}`);
});

await checkAsync('αίτημα που η πύλη δεν δίνει -> RequestNotFound, χωρίς αρχείο', async () => {
  const { result, mock } = await runRequest({ id: '4242' }, {});
  assert(result.ok === false && result.reason === 'RequestNotFound', `ok=${result.ok} reason=${result.reason}`);
  assert(!mock.files.has('AITIMA_4242.json'), 'δεν έπρεπε να γραφτεί JSON');
});

await checkAsync('χωρίς αριθμό αιτήματος δεν ανοίγει καν σύνδεση', async () => {
  const { result, mock } = await runRequest({ id: 'abc' }, { detail: requestDetail });
  assert(result.ok === false && result.reason === 'NoRequestId', `reason=${result.reason}`);
  assert(mock.requests.length === 0, `αιτήματα δικτύου: ${mock.requests.length}`);
});

console.log(`\n${pass} πέρασαν, ${fail} απέτυχαν  (${loaded}/${configFiles.length} configs φορτώθηκαν)\n`);
process.exit(fail ? 1 : 0);
