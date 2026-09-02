#!/usr/bin/env node
/*
 * tools/vendor-engine.mjs — συγχρονισμός του engine από τον runner.
 * =============================================================================
 * Αντιγράφει ΑΥΤΟΥΣΙΑ (byte-για-byte) από το `recerse-engineer/runner`:
 *
 *   lib/hyper-http.js  ->  app/src/main/assets/engine/hyper-http.js
 *   configs/*.js       ->  app/src/main/assets/engine/configs/
 *
 * ΔΕΝ αντιγράφει τα `lib/browser-step.js` και `lib/render-pdf.js`: αυτά είναι
 * δεμένα με Playwright/Chromium. Στο Android υπάρχουν ισοδύναμα που υλοποιούν
 * το ΙΔΙΟ contract πάνω σε WebView — ζουν στο repo και δεν τα πειράζει αυτό
 * το script.
 *
 * Παράγει επίσης `configs.json`: κατάλογος με id/title/portal/inputs, ώστε το
 * UI να μη χρειάζεται να parsάρει JavaScript.
 *
 * Χρήση:
 *   node tools/vendor-engine.mjs [--runner &lt;path&gt;] [--check]
 *
 *   --check  δεν γράφει τίποτα· βγάζει exit 1 αν τα assets έχουν αποκλίνει από
 *            τον runner (για CI / pre-commit).
 */
import { createRequire } from 'node:module';
import { createHash } from 'node:crypto';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const HERE = path.dirname(fileURLToPath(import.meta.url));
const REPO = path.resolve(HERE, '..');
const ASSETS = path.join(REPO, 'app/src/main/assets/engine');

// Αυτά ΔΕΝ έρχονται από τον runner — έχουν Android υλοποίηση.
const ANDROID_OWNED = new Set(['browser-step.js', 'render-pdf.js', 'shims.js', 'runner.js']);

function arg(name, fallback) {
  const i = process.argv.indexOf(name);
  return i > -1 && process.argv[i + 1] ? process.argv[i + 1] : fallback;
}
const CHECK = process.argv.includes('--check');
const RUNNER = path.resolve(arg('--runner', path.join(REPO, '../recerse-engineer/runner')));

if (!fs.existsSync(path.join(RUNNER, 'configs'))) {
  console.error(`Δεν βρέθηκε ο runner στο: ${RUNNER}`);
  console.error('Δώσε τη διαδρομή με: node tools/vendor-engine.mjs --runner <path>');
  process.exit(1);
}

const sha = (buf) => createHash('sha256').update(buf).digest('hex').slice(0, 12);
let drift = 0;

function place(relFrom, relTo) {
  const src = path.join(RUNNER, relFrom);
  const dst = path.join(ASSETS, relTo);
  const bytes = fs.readFileSync(src);
  const existing = fs.existsSync(dst) ? fs.readFileSync(dst) : null;

  if (existing && existing.equals(bytes)) return { rel: relTo, state: 'same', hash: sha(bytes) };
  drift++;
  if (CHECK) return { rel: relTo, state: 'DRIFT', hash: sha(bytes) };
  fs.mkdirSync(path.dirname(dst), { recursive: true });
  fs.writeFileSync(dst, bytes);
  return { rel: relTo, state: existing ? 'updated' : 'new', hash: sha(bytes) };
}

// ------------------------------------------------------------------- engine
const rows = [place('lib/hyper-http.js', 'hyper-http.js')];

// ------------------------------------------------------------------ configs
const configFiles = fs
  .readdirSync(path.join(RUNNER, 'configs'))
  .filter((f) => f.endsWith('.js'))
  .sort();

for (const f of configFiles) rows.push(place(path.join('configs', f), path.join('configs', f)));

// --------------------------------------------------------------- κατάλογος
// Τα configs είναι απλά module.exports objects — τα φορτώνουμε από τη ΘΕΣΗ ΤΟΥΣ
// στον runner, ώστε τα σχετικά require('../lib/...') να λύνονται κανονικά.
const require_ = createRequire(path.join(RUNNER, 'configs', 'x.js'));
const requireLocal = createRequire(path.join(ASSETS, 'configs', 'x.js'));
const catalog = [];
const broken = [];

function describe(id, file, cfg, owner) {
  catalog.push({
    id: cfg.id || id,
    file,
    owner,
    title: cfg.title || '',
    portal: cfg.portal || '',
    subsystem: cfg.subsystem || '',
    actions: cfg.actions || [],
    needsBrowser: !!cfg.needsBrowser,
    inputs: (cfg.inputs || []).map((i) => ({
      key: i.key,
      label: i.label || i.key,
      env: i.env || '',
      hidden: !!i.hidden,
      optional: !!i.optional,
    })),
  });
}

for (const f of configFiles) {
  const id = f.replace(/\.js$/, '');
  try {
    describe(id, f, require_(path.join(RUNNER, 'configs', f)), 'runner');
  } catch (e) {
    broken.push({ id, error: e.message });
  }
}

// Configs που ανήκουν στην εφαρμογή και δεν υπάρχουν στον runner (π.χ.
// `aade-email`). Δεν αντιγράφονται και δεν θεωρούνται drift — απλώς μπαίνουν
// στον κατάλογο. Επιτρέπεται να κάνουν require μόνο fs/path, ώστε να
// φορτώνονται και εδώ και στο κινητό χωρίς shims.
const localConfigs = fs
  .readdirSync(path.join(ASSETS, 'configs'))
  .filter((f) => f.endsWith('.js') && !configFiles.includes(f))
  .sort();

for (const f of localConfigs) {
  const id = f.replace(/\.js$/, '');
  try {
    describe(id, f, requireLocal(path.join(ASSETS, 'configs', f)), 'app');
  } catch (e) {
    broken.push({ id, error: e.message });
  }
}

const catalogJson = JSON.stringify(
  { generatedFrom: 'recerse-engineer/runner', count: catalog.length, configs: catalog },
  null,
  2,
) + '\n';

const catalogPath = path.join(ASSETS, 'configs.json');
const catalogOld = fs.existsSync(catalogPath) ? fs.readFileSync(catalogPath, 'utf8') : null;
if (catalogOld !== catalogJson) {
  drift++;
  if (!CHECK) fs.writeFileSync(catalogPath, catalogJson);
}

// ------------------------------------------------------------------ αναφορά
const width = Math.max(...rows.map((r) => r.rel.length));
for (const r of rows) {
  console.log(`  ${r.rel.padEnd(width)}  ${r.hash}  ${r.state}`);
}
console.log(`\n  ${catalog.length} configs στον κατάλογο, ${rows.length} αρχεία vendored.`);
for (const b of broken) console.warn(`  ⚠️  ${b.id}: ${b.error}`);

const stillMissing = [...ANDROID_OWNED].filter(
  (f) => f !== 'shims.js' && !fs.existsSync(path.join(ASSETS, f)),
);
if (stillMissing.length) {
  console.warn(`\n  ⚠️  Λείπουν οι Android υλοποιήσεις: ${stillMissing.join(', ')}`);
}

if (CHECK && drift) {
  console.error(`\n  ✗ Τα assets έχουν αποκλίνει από τον runner (${drift} αρχεία). Τρέξε: node tools/vendor-engine.mjs`);
  process.exit(1);
}
console.log(CHECK ? '\n  ✓ Συγχρονισμένα.' : '\n  ✓ Έτοιμο.');
