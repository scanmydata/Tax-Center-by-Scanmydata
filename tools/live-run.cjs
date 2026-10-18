#!/usr/bin/env node
/*
 * tools/live-run.cjs — ζωντανή εκτέλεση configs των assets, από τον υπολογιστή.
 * =============================================================================
 * Τρέχει τον ΙΔΙΟ κώδικα που τρέχει στο κινητό (app/src/main/assets/engine),
 * με τον ίδιο engine (`hyper-http.js`), αλλά σε Node: για να δεις τι απαντά
 * σήμερα η πύλη όταν μια διαδικασία σταματήσει να φέρνει έντυπα.
 *
 *   node tools/live-run.cjs                                   # λίστα των configs
 *   node tools/live-run.cjs efka-notices                      # ρωτά τα inputs και τρέχει
 *   node tools/live-run.cjs efka-notices efka-teka-certificate
 *
 * Με πολλά configs τα inputs ρωτιούνται **μία φορά** (ίδιο κλειδί = ίδια τιμή)
 * και τα configs τρέχουν σειριακά — ποτέ παράλληλα (GSIS OAM-6).
 *
 * Τα inputs έρχονται από μεταβλητές περιβάλλοντος (το `env` κάθε input) ή
 * ρωτιούνται· το συνθηματικό γράφεται κρυφά και δεν αποθηκεύεται πουθενά.
 *
 * Log, σελίδες της πύλης (dumps) και PDF γράφονται στο `.live/<id>/`, που δεν
 * μπαίνει στο git: περιέχουν στοιχεία του λογαριασμού.
 */
'use strict';
const fs = require('fs');
const path = require('path');

const REPO = path.resolve(__dirname, '..');
const ENGINE = path.join(REPO, 'app/src/main/assets/engine');
const lib = require(path.join(ENGINE, 'hyper-http.js'));

(async () => {
  const ids = process.argv.slice(2);
  const dir = path.join(ENGINE, 'configs');
  if (!ids.length) {
    for (const f of fs.readdirSync(dir).filter((f) => f.endsWith('.js')).sort()) console.log('  ' + f.replace(/\.js$/, ''));
    return;
  }
  const configs = ids.map((id) => require(path.join(dir, id + '.js')));

  // Ένα input ανά κλειδί, με το πρώτο label/env που δηλώθηκε.
  const spec = [];
  for (const cfg of configs) for (const it of cfg.inputs) if (!spec.some((s) => s.key === it.key)) spec.push(it);
  const inputs = await lib.gatherInputs(spec);

  for (const cfg of configs) {
    const out = path.join(REPO, '.live', cfg.id);
    fs.rmSync(out, { recursive: true, force: true });
    const http = new lib.HyperHttp(out);
    http.log('=== ' + cfg.id + ' — ' + cfg.title + ' ===');
    try {
      const res = await cfg.run(http, Object.assign({}, inputs), lib);
      http.log('RESULT: ' + JSON.stringify(res));
    } catch (e) {
      http.log('ERROR: ' + (e && e.stack ? e.stack : e));
    }
    http.log('Log + dumps: ' + out);
  }
})();
