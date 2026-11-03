/*
 * configs/aade-income-info.js  --  Ενημερωτικό εισοδημάτων-δαπανών υπόχρεου / συζύγου
 * =============================================================================
 * Ίδια σελίδα με το Ε1/Ε2/Ε3 του `aade-income`: το μενού της δήλωσης
 * (webtax/incomefp/year{Y}-income-menu.do, GSIS OAM). Εκεί η ΑΑΔΕ δίνει και το
 * ενημερωτικό με τα προσυμπληρωμένα εισοδήματα και δαπάνες, χωριστά για τον
 * υπόχρεο και τη σύζυγο.
 *
 * Τα ονόματα πεδίων του κουμπιού ΔΕΝ είναι γνωστά από τον runner (δεν υπάρχει
 * αντίστοιχο στο Easy_Aade). Γι' αυτό το κουμπί βρίσκεται από την **ετικέτα**
 * του («Ενημερωτικό…», ή κουμπί «Υπόχρεου»/«Συζύγου» σε γραμμή με αυτόν τον
 * τίτλο) και η ενέργειά του διαβάζεται από το ίδιο το HTML:
 *
 *   onclick  ...forms[0].<πεδίο>.value='<τιμή>' ... .action='<url>' ... submit()
 *            -> POST <url | year{Y}-income-menuPrint.do> {κρυφά πεδία της φόρμας + <πεδίο>=<τιμή>}
 *   onclick  window.open('<url>') / location.href='<url>'  -> GET <url>
 *   submit   <button|input type=submit name=N value=V>   -> POST action της φόρμας {κρυφά + N=V}
 *   <a href> -> GET href
 *
 * Το μενού αποθηκεύεται πάντα (`income_info_menu_<Y>.html`) και το JSON γράφει
 * ποια κουμπιά βρέθηκαν: αν η πύλη το σερβίρει αλλιώς, η πρώτη ζωντανή
 * εκτέλεση δείχνει τι να διορθωθεί.
 *
 * ΣΗΜΕΙΩΣΗ: config της εφαρμογής (δεν υπάρχει στον runner). Μόνο fs/path.
 *
 * INPUTS: TAXISnet user/pass + Έτος (όπως στο Ε1) + docs (YPO,SYZ· κενό = YPO).
 * OUTPUT: ENIMEROTIKO_<ΑΦΜ>_<έτος>.pdf, ENIMEROTIKO_SYZ_<ΑΦΜ>_<έτος>.pdf, AADE_income_info_<ΑΦΜ>_<έτος>.json
 */
'use strict';
const path = require('path');
const fs = require('fs');

const DOCS = {
  YPO: { spouse: false, prefix: 'ENIMEROTIKO_', label: 'Ενημερωτικό υπόχρεου' },
  SYZ: { spouse: true, prefix: 'ENIMEROTIKO_SYZ_', label: 'Ενημερωτικό συζύγου' },
};

// πεζά, χωρίς τόνους: «Ενημερωτικό» / «ΕΝΗΜΕΡΩΤΙΚΟ» / «ενημερωτικο» είναι το ίδιο
const fold = (s) => String(s || '').normalize('NFD').replace(/[\u0300-\u036f]/g, '').toLowerCase();
const attr = (tag, name) => {
  const m = tag.match(new RegExp('\\b' + name + '\\s*=\\s*(["\'])([\\s\\S]*?)\\1', 'i')) || tag.match(new RegExp('\\b' + name + '\\s*=\\s*([^\\s>]+)', 'i'));
  return m ? (m[2] !== undefined ? m[2] : m[1]) : '';
};

// Όλα τα «πατήσιμα» στοιχεία της σελίδας με θέση, ετικέτα και ιδιότητες.
function clickables(html, lib) {
  const out = [];
  for (const m of html.matchAll(/<input\b[^>]*>/gi)) {
    const type = fold(attr(m[0], 'type'));
    if (!/^(button|submit|image)$/.test(type)) continue;
    out.push({ at: m.index, tag: m[0], kind: 'input', type, label: lib.decodeHtml(attr(m[0], 'value') || attr(m[0], 'title') || attr(m[0], 'alt')) });
  }
  for (const m of html.matchAll(/<button\b([^>]*)>([\s\S]*?)<\/button>/gi)) {
    out.push({ at: m.index, tag: '<button' + m[1] + '>', kind: 'button', type: fold(attr(m[1], 'type')) || 'submit', label: lib.stripTags(m[2]) || lib.decodeHtml(attr(m[1], 'title')) });
  }
  for (const m of html.matchAll(/<a\b([^>]*)>([\s\S]*?)<\/a>/gi)) {
    out.push({ at: m.index, tag: '<a' + m[1] + '>', kind: 'a', type: 'link', label: lib.stripTags(m[2]) || lib.decodeHtml(attr(m[1], 'title')) });
  }
  return out.map(c => ({ ...c, name: attr(c.tag, 'name'), value: lib.decodeHtml(attr(c.tag, 'value')), onclick: lib.decodeHtml(attr(c.tag, 'onclick')).replace(/&#0*39;/g, "'"), href: lib.decodeHtml(attr(c.tag, 'href')) }))
    .sort((a, b) => a.at - b.at);
}

// Τα κουμπιά του ενημερωτικού: είτε η ετικέτα τους λέει «ενημερωτικ», είτε
// βρίσκονται στη γραμμή πίνακα (<tr>) που το λέει.
function findButtons(html, lib) {
  const all = clickables(html, lib);
  const isSpouse = (c) => /συζ/.test(fold(c.label)) || /syz/i.test(c.name) || /syz/i.test(c.onclick);
  const direct = all.filter(c => /ενημερωτικ/.test(fold(c.label)) || /enim|enhm/i.test(c.name));
  let found = direct;
  if (!found.length) {
    const rowsAt = [...html.matchAll(/<tr\b[^>]*>([\s\S]*?)<\/tr>/gi)]
      .filter(r => /ενημερωτικ/.test(fold(lib.stripTags(r[1]))))
      .map(r => [r.index, r.index + r[0].length]);
    found = all.filter(c => rowsAt.some(([s, e]) => c.at >= s && c.at < e));
  }
  return found.map(c => ({ ...c, spouse: isSpouse(c) }));
}

// Η φόρμα που περιέχει τη θέση `at`: action και κρυφά πεδία.
function formAt(html, at, lib) {
  for (const m of html.matchAll(/<form\b([^>]*)>([\s\S]*?)<\/form>/gi)) {
    if (at < m.index || at > m.index + m[0].length) continue;
    const hidden = {};
    for (const h of m[2].matchAll(/<input\b[^>]*>/gi)) {
      if (fold(attr(h[0], 'type')) !== 'hidden') continue;
      const n = attr(h[0], 'name'); if (n) hidden[n] = lib.decodeHtml(attr(h[0], 'value'));
    }
    return { action: lib.decodeHtml(attr(m[1], 'action')), hidden };
  }
  return { action: '', hidden: {} };
}

// Τι κάνει το onclick: πεδία που γεμίζει, action, ή URL που ανοίγει. Αν καλεί
// συνάρτηση της σελίδας, διαβάζεται και το σώμα της.
function readScript(js, html) {
  let code = js || '';
  const call = code.match(/^\s*(?:return\s+)?([A-Za-z_$][\w$]*)\s*\(([^)]*)\)/);
  if (call && !/^(window|document|location)$/.test(call[1])) {
    const def = html.match(new RegExp('function\\s+' + call[1].replace(/\$/g, '\\$') + '\\s*\\(([^)]*)\\)\\s*\\{([\\s\\S]*?)\\n?\\s*\\}', ''));
    if (def) {
      const params = def[1].split(',').map(s => s.trim()).filter(Boolean);
      const args = call[2].split(',').map(s => s.trim().replace(/^["']|["']$/g, ''));
      let body = def[2];
      params.forEach((p, i) => { body = body.replace(new RegExp('\\b' + p + '\\b(?!\\s*=)', 'g'), "'" + (args[i] || '') + "'"); });
      code += ';' + body;
    }
  }
  const fields = {};
  for (const m of code.matchAll(/(?:\.([A-Za-z_][\w]*)|\[\s*["']([^"']+)["']\s*\])\s*\.value\s*=\s*["']([^"']*)["']/g)) fields[m[1] || m[2]] = m[3];
  const action = (code.match(/\.action\s*=\s*["']([^"']+)["']/) || [])[1] || '';
  const open = (code.match(/(?:window\.open|location(?:\.href)?\s*=|location\.assign)\s*\(?\s*["']([^"']+)["']/) || [])[1] || '';
  return { fields, action, open };
}

module.exports = {
  id: 'aade-income-info',
  title: 'Ενημερωτικό εισοδημάτων-δαπανών (υπόχρεου / συζύγου)',
  portal: 'AADE webtax/incomefp (GSIS OAM)',
  subsystem: 'ScanMyData Tax Center',
  actions: ['retrieve', 'download'],
  inputs: [
    { key: 'user', label: 'TAXISnet username', env: 'AADE_USER' },
    { key: 'pass', label: 'TAXISnet password', env: 'AADE_PASS', hidden: true },
    { key: 'year', label: 'Έτος (όπως στο Ε1)', env: 'AADE_YEAR' },
    { key: 'docs', label: 'Ενημερωτικά (YPO,SYZ — κενό = YPO)', env: 'AADE_INFO_DOCS', optional: true },
  ],

  async run(http, inp, lib) {
    const L = await lib.aadeLogin(http, inp);
    if (!L.ok) { http.log('AADE LOGIN FAILED: ' + L.reason); return { ok: false, reason: L.reason }; }
    const Y = parseInt((inp.year || '').trim(), 10);
    if (!Y) { http.log('Χρειάζεται Έτος'); return { ok: false, reason: 'NoYear' }; }
    const afm = (lib.stripTags(L.page.text || '').match(/Α\.?Φ\.?Μ\.?\s*[:\-]?\s*(\d{9})/) || [])[1] || inp.user;
    const H = (rel) => new URL(rel, new URL('/webtax/incomefp/', L.AADE)).toString();
    let want = (inp.docs || '').toUpperCase().split(/[,\s]+/).filter(k => DOCS[k]);
    if (!want.length) want = ['YPO'];

    // ΜΟΝΟ ανάγνωση του μενού — ποτέ «Υποβολή» ή τροποποιητική
    const menuUrl = H('year' + Y + '-income-menu.do');
    const menu = (await http.follow('GET', menuUrl)).text || '';
    http.dump('income_info_menu_' + Y + '.html', menu);
    const buttons = findButtons(menu, lib);
    http.log('[info] έτος ' + Y + ' | κουμπιά ενημερωτικού: ' + (buttons.map(b => '«' + b.label + '»' + (b.name ? ' ' + b.name : '') + (b.spouse ? ' (συζ.)' : '')).join(', ') || '—'));

    const result = { portal: this.portal, afm, year: Y, retrievedAt: new Date().toISOString(),
      found: buttons.map(b => ({ label: b.label, kind: b.kind, name: b.name, value: b.value, onclick: b.onclick, href: b.href, spouse: b.spouse })), docs: {} };
    const files = [];
    for (const k of want) {
      const D = DOCS[k];
      const b = buttons.find(x => x.spouse === D.spouse && !/disabled/i.test(x.tag));
      if (!b) {
        const off = buttons.some(x => x.spouse === D.spouse);
        result.docs[k] = { label: D.label, status: off ? 'NotAvailable (κουμπί disabled)' : 'NotFound' };
        http.log('[info ' + k + '] ' + (off ? 'μη διαθέσιμο (κουμπί disabled)' : 'δεν βρέθηκε κουμπί (δες income_info_menu_' + Y + '.html)'));
        continue;
      }
      try {
        const s = readScript(b.onclick, menu);
        const form = formAt(menu, b.at, lib);
        let pdf = null, via = '';
        const url = s.open || (b.kind === 'a' && b.href && !/^(#|javascript:)/i.test(b.href) ? b.href : '');
        if (url) {
          via = 'GET ' + url;
          const d = await http.getDoc(new URL(url, menuUrl).toString());
          pdf = d.buffer || null;
          if (!pdf) http.dump('income_info_' + k + '_' + Y + '.html', d.text || '');
        } else {
          const body = { ...form.hidden, ...s.fields };
          if (b.name && (b.kind === 'button' || b.type === 'submit' || b.type === 'image')) body[b.name] = b.value || b.name;
          const action = s.action || form.action || ('year' + Y + '-income-menuPrint.do');
          via = 'POST ' + action;
          pdf = await http.postForPdf(new URL(action, menuUrl).toString(), body);
        }
        if (!pdf) { result.docs[k] = { label: D.label, status: 'NoPDF', via }; http.log('[info ' + k + '] δεν επέστρεψε PDF (' + via + ')'); continue; }
        const f = D.prefix + afm + '_' + Y + '.pdf';
        fs.writeFileSync(path.join(http.dlDir, f), pdf); files.push(f);
        result.docs[k] = { label: D.label, status: 'ok', via, pdf: f, bytes: pdf.length };
        http.log('[info ' + k + '] ✅ PDF -> ' + f + ' (' + pdf.length + ' b)');
      } catch (e) { result.docs[k] = { label: D.label, status: 'Error', error: String(e && e.message || e) }; http.log('[info ' + k + '] error ' + (e && e.message ? e.message : e)); }
    }

    const jf = 'AADE_income_info_' + afm + '_' + Y + '.json';
    fs.writeFileSync(path.join(http.dlDir, jf), JSON.stringify(result, null, 2));
    // Χωρίς κουμπί = δεν υπάρχει ενημερωτικό (κενό, όπως στο aade-income).
    // Κουμπί που δεν έδωσε PDF είναι βλάβη και πρέπει να φανεί ως τέτοια.
    const broken = !files.length && Object.values(result.docs).some(d => d.status === 'NoPDF' || d.status === 'Error');
    return broken ? { ok: false, reason: 'NoPDF', files: [jf] } : { ok: true, files: [jf, ...files] };
  },

  // για τεστ
  _findButtons: findButtons,
  _readScript: readScript,
  _formAt: formAt,
};
