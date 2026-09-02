/*
 * render-pdf.js — Android υλοποίηση του HTML→PDF contract.
 * =============================================================================
 * Ο desktop (`runner/lib/render-pdf.js`) χρησιμοποιεί Playwright/Chromium για το
 * rendering και `pdf-lib` για το merge. Τίποτε από τα δύο δεν είναι διαθέσιμο εδώ.
 *
 * Το contract όμως είναι ήδη σχεδιασμένο να υποβαθμίζεται: ο μόνος καταναλωτής
 * (`aade-debts`, δεύτερη σελίδα «Ανάλυση Δόσεων») ελέγχει `rr.ok` και, αν είναι
 * false, γράφει κανονικά το PDF της Ταυτότητας Οφειλής χωρίς τη 2η σελίδα — τα
 * δεδομένα των δόσεων μένουν ούτως ή άλλως στο JSON.
 *
 *   renderPdfs / renderMany  ->  native WebView print-to-PDF όταν υποστηρίζεται,
 *                                αλλιώς { ok:false, reason }
 *   mergePdf                 ->  δεν υποστηρίζεται (χρειάζεται pdf-lib)
 *   tableDoc                 ->  αυτούσιο από τον runner (καθαρό HTML, μηδέν deps)
 */
'use strict';

function nativeRender(items) {
  // items: [{ path, html }] — το native επιστρέφει { ok, count } ή { ok:false, reason }
  return new Promise(function (resolve) {
    var id = 'r' + (++nativeRender._seq);
    nativeRender._pending[id] = resolve;
    try {
      __bridge.pageCall(id, JSON.stringify({ handle: '', op: 'renderPdf', items: items }));
    } catch (e) {
      delete nativeRender._pending[id];
      resolve({ ok: false, reason: 'render-pdf δεν υποστηρίζεται σε αυτή τη συσκευή' });
    }
  });
}
nativeRender._seq = 0;
nativeRender._pending = Object.create(null);

(function bindResolvers() {
  var prevResolve = globalThis.__resolve;
  var prevReject = globalThis.__reject;
  globalThis.__resolve = function (id, json) {
    var p = nativeRender._pending[id];
    if (p) {
      delete nativeRender._pending[id];
      var out;
      try { out = json ? JSON.parse(json) : { ok: false, reason: 'κενή απάντηση' }; }
      catch (e) { out = { ok: false, reason: String(e) }; }
      p(out);
      return;
    }
    if (prevResolve) prevResolve(id, json);
  };
  globalThis.__reject = function (id, message) {
    var p = nativeRender._pending[id];
    if (p) {
      delete nativeRender._pending[id];
      p({ ok: false, reason: message || 'render error' });
      return;
    }
    if (prevReject) prevReject(id, message);
  };
})();

async function renderPdfs(items /* [{ path, html }] */) {
  if (!items || !items.length) return { ok: true, count: 0 };
  return nativeRender(items);
}

async function renderMany(htmls) {
  if (!htmls || !htmls.length) return { ok: true, buffers: [] };
  // Το native γράφει σε αρχεία, όχι σε buffers. Ο μόνος καταναλωτής θέλει
  // buffers για merge — και το merge δεν υποστηρίζεται — οπότε δηλώνουμε
  // ειλικρινά ότι δεν γίνεται, αντί να γυρίσουμε μισοτελειωμένο αποτέλεσμα.
  return { ok: false, reason: 'renderMany: χρειάζεται in-memory PDF rendering (μη διαθέσιμο σε Android)' };
}

async function mergePdf(buffers) {
  throw new Error('mergePdf: χρειάζεται pdf-lib (μη διαθέσιμο σε Android)');
}

// --- αυτούσιο από runner/lib/render-pdf.js ----------------------------------
// minimal styled HTML doc for a table-style report (Greek-safe)
function tableDoc(title, subtitle, sections /* [{ heading, headers:[], rows:[[]] }] */) {
  const esc = (s) => String(s == null ? '' : s).replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');
  const tbl = (s) => `<h2>${esc(s.heading || '')}</h2><table><thead><tr>${(s.headers || []).map(h => '<th>' + esc(h) + '</th>').join('')}</tr></thead>`
    + `<tbody>${(s.rows || []).map(r => '<tr>' + r.map(c => '<td>' + esc(c) + '</td>').join('') + '</tr>').join('')}</tbody></table>`;
  return `<!doctype html><html><head><meta charset="utf-8"><style>
    body{font-family:'DejaVu Sans','Arial',sans-serif;font-size:11px;color:#111;margin:0}
    h1{font-size:15px;margin:0 0 2px} h2{font-size:12px;margin:12px 0 4px;color:#1a4a7a}
    .sub{color:#555;font-size:11px;margin:0 0 6px}
    table{border-collapse:collapse;width:100%;margin-bottom:8px}
    th,td{border:1px solid #bbb;padding:3px 6px;text-align:left}
    th{background:#eef3f8}
    </style></head><body><h1>${esc(title)}</h1><p class="sub">${esc(subtitle || '')}</p>
    ${(sections || []).map(tbl).join('')}</body></html>`;
}

module.exports = { renderPdfs, renderMany, mergePdf, tableDoc };
