/*
 * configs/efka-notices.js  --  Process: "Ειδοποιητήρια Εισφορών ΕΦΚΑ/ΤΕΚΑ" (μη μισθωτοί)
 * Source: Hyper.Server.Tax.dll  Easy_EFKA_SelfEmployed (Login + GetNotice).
 * A process config = { id, title, portal, inputs, run(http, inputs, lib) }.
 *
 * ΖΩΝΤΑΝΑ, Οκτ. 2026 — η σελίδα «Υποχρεώσεις Ασφάλισης» άλλαξε:
 *  - οι σύνδεσμοι του μενού έχουν μπροστά το κείμενο του εικονιδίου
 *    («person Υποχρεώσεις Ασφάλισης»), οπότε ο σύνδεσμος βρίσκεται με το τέλος
 *    του ονόματος — και αλλιώς με τη γνωστή διαδρομή insuranceObligations.xhtml·
 *  - το tab «Τρέχουσες Οφειλές» έχει ΕΝΑΝ πίνακα (είδος, μήνας, πληρωτέο,
 *    εμπρόθεσμη πληρωμή, RF) και μόνο κουμπί IRIS: δεν έχει πια χωριστούς πίνακες
 *    ΕΦΚΑ/ΤΕΚΑ ούτε «Εκτύπωση». Διαβάζεται σε JSON (τα ποσά και το RF)·
 *  - το PDF του ειδοποιητηρίου βγαίνει από το tab «Μηνιαία Ειδοποιητήρια», όπου
 *    ο μήνας γράφεται 01/MM/YYYY. Κατεβαίνει ο πιο πρόσφατος μήνας (όλες οι
 *    γραμμές του — ΕΦΚΑ και ΤΕΚΑ όπου υπάρχουν).
 * Τα παλιά ids των πινάκων ΕΦΚΑ/ΤΕΚΑ μένουν ως πρώτη προσπάθεια, για όσο τα
 * σερβίρει ακόμη η πύλη σε κάποιους λογαριασμούς.
 */
'use strict';
const path = require('path');
const fs = require('fs');

const FORM_ID = 'form-id:accordion-insurance-obligations-citizen';
const DT = {
  EFKA: FORM_ID + ':notifications-accordion-panel:e-efka-last-notification-citizen-datatable',
  TEKA: FORM_ID + ':notifications-accordion-panel:teka-last-notification-citizen-datatable',
};
const OBLIGATIONS_PATH = '/ssp.efka.non.employees/views/insuranceObligations.xhtml';

// Όλα τα tabs της σελίδας: { label, dataIndex, href }.
function tabsOf(html, lib) {
  const out = [];
  for (const li of html.matchAll(/<li\b[^>]*class="[^"]*ui-tabs-header[^"]*"[^>]*>([\s\S]*?)<\/li>/gi)) {
    out.push({
      label: lib.stripTags(li[1]),
      dataIndex: (li[0].match(/data-index="([^"]*)"/i) || [])[1] || '',
      href: lib.decodeHtml((li[1].match(/<a\b[^>]*href="([^"]*)"/i) || [])[1] || ''),
    });
  }
  return out;
}

// Κείμενο κελιού χωρίς τα <script> του PrimeFaces (το κουμπί «PDF» φέρνει μαζί το widget του).
const cellText = (c, lib) => lib.stripTags(String(c || '').replace(/<script\b[\s\S]*?<\/script>/gi, ''));

// Γραμμές PrimeFaces datatable (όλων των πινάκων του αποσπάσματος), κελιά σε HTML.
function rowsOf(html) {
  return [...html.matchAll(/<tr\b[^>]*class="[^"]*ui-datatable-(?:even|odd)[^"]*"[^>]*>([\s\S]*?)<\/tr>/gi)]
    .map((r) => [...r[1].matchAll(/<td\b[^>]*>([\s\S]*?)<\/td>/gi)].map((t) => t[1]));
}

// «01/09/2026» -> «2026-09» (για σύγκριση και όνομα αρχείου).
function monthKey(text) {
  const m = String(text || '').match(/(\d{2})\/(\d{2})\/(\d{4})/);
  if (m) return m[3] + '-' + m[2];
  const n = String(text || '').match(/(\d{1,2})\/(\d{4})/);
  return n ? n[2] + '-' + n[1].padStart(2, '0') : '';
}

module.exports = {
  id: 'efka-notices',
  title: 'Ειδοποιητήρια Εισφορών ΕΦΚΑ/ΤΕΚΑ (μη μισθωτοί)',
  portal: 'e-EFKA (services.e-efka.gov.gr, non-employee)',
  subsystem: 'Hyper.Server',
  actions: ['retrieve', 'download'],
  inputs: [
    { key: 'user', label: 'TAXISnet username', env: 'EFKA_USER' },
    { key: 'pass', label: 'TAXISnet password', env: 'EFKA_PASS', hidden: true },
    { key: 'afm', label: 'ΑΦΜ', env: 'EFKA_AFM' },
    { key: 'amka', label: 'ΑΜΚΑ', env: 'EFKA_AMKA' },
    // «όχι» = μόνο τα ποσά και οι κωδικοί πληρωμής (ενημέρωση καρτέλας οφειλών), χωρίς PDF.
    { key: 'pdf', label: 'Λήψη PDF; (κενό = ναι, «όχι» = μόνο τα ποσά)', env: 'EFKA_PDF', optional: true },
  ],
  monthKey, // για τα τεστ

  async run(http, inp, lib) {
    // 1) login (reusable e-EFKA non-employee KeyCloak flow)
    const L = await lib.efkaNonEmployeeLogin(http, inp);
    if (!L.ok) { http.log('LOGIN FAILED: ' + L.reason); return { ok: false, reason: L.reason }; }
    const label = Object.keys(L.links).find((t) => t.replace(/\s+/g, ' ').trim().endsWith('Υποχρεώσεις Ασφάλισης'));
    const ypoUrl = (label && L.links[label]) || new URL(OBLIGATIONS_PATH, L.SVC).toString();
    http.log('[obligations] URL = ' + ypoUrl);

    // 2) confirm obligations page
    const r = await http.follow('GET', ypoUrl); http.dump('04_obligations.html', r.text);
    const tabs = tabsOf(r.text, lib);
    http.log('[obligations] tabs: ' + tabs.map((t) => t.label + '#' + t.dataIndex).join(' | '));
    if (!tabs.length) { http.log('obligations page without tabs'); return { ok: false, reason: 'PageError' }; }

    const noPdf = /^(όχι|οχι|no|0)$/i.test(String(inp.pdf || '').trim());
    const saved = [];
    const tables = { portal: this.portal, afm: inp.afm, retrievedAt: new Date().toISOString(), sections: {} };

    // 3a) παλιά διάταξη: ένας πίνακας ανά είδος, με «Εκτύπωση» στη γραμμή
    for (const kind of ['EFKA', 'TEKA']) {
      const res = await this.getNotice(http, lib, ypoUrl, kind, inp.afm, tables, noPdf);
      if (res) saved.push(res);
    }
    // 3b) νέα διάταξη: «Τρέχουσες Οφειλές» (ποσά, RF) + «Μηνιαία Ειδοποιητήρια» (PDF)
    if (!saved.length) saved.push(...await this.getMonthly(http, lib, ypoUrl, inp.afm, tables, noPdf));

    // every runner also emits a JSON of the targeted table strings (for future use)
    const jf = path.join(http.dlDir, 'EFKA_notices_' + inp.afm + '_table.json');
    fs.writeFileSync(jf, JSON.stringify(tables, null, 2));
    saved.push(path.basename(jf));
    http.log(saved.length > 1 ? ('DONE — ' + saved.join(', ')) : 'DONE — no current notices.');
    return { ok: true, files: saved };
  },

  // tabChange σε ένα tab της σελίδας -> το HTML του (partial-response update).
  async openTab(http, lib, ypoUrl, label) {
    const page = (await http.follow('GET', ypoUrl)).text;
    const vs = lib.viewState(page);
    const tab = tabsOf(page, lib).find((t) => t.label === label);
    if (!vs || !tab || !tab.href) return null;
    const newTab = tab.href.replace(/^#/, '');
    // Η πηγή του tabChange είναι το tabView που περιέχει το tab — από το href.
    const view = lib.hasId(page, FORM_ID) ? FORM_ID : newTab.slice(0, newTab.lastIndexOf(':'));
    const form = (view.split(':')[0]) || 'form-id';
    const resp = await http.follow('POST', ypoUrl, {
      'jakarta.faces.partial.ajax': 'true', 'jakarta.faces.source': view,
      'jakarta.faces.partial.execute': view, 'jakarta.faces.partial.render': view,
      'jakarta.faces.behavior.event': 'tabChange', 'jakarta.faces.partial.event': 'tabChange',
      [view + '_contentLoad']: 'true', [view + '_newTab']: newTab,
      [view + '_tabindex']: tab.dataIndex, [form]: form,
      [view + '_activeIndex']: tab.dataIndex, 'jakarta.faces.ViewState': vs,
    });
    return { html: lib.extractUpdate(resp.text, view), raw: resp.text, vs, view, form, tab };
  },

  async getMonthly(http, lib, ypoUrl, afm, tables, noPdf) {
    // Τρέχουσες Οφειλές: ποσά + RF, σε JSON (εδώ δεν υπάρχει εκτύπωση).
    const cur = await this.openTab(http, lib, ypoUrl, 'Τρέχουσες Οφειλές');
    if (cur) {
      http.dump('05_current_debts.html', cur.raw);
      const headers = [...cur.html.matchAll(/<th\b[^>]*>([\s\S]*?)<\/th>/gi)].map((t) => lib.stripTags(t[1])).filter(Boolean);
      tables.sections.CURRENT = { headers, rows: rowsOf(cur.html).map((r) => r.map((c) => cellText(c, lib))) };
      http.log('[notice] Τρέχουσες Οφειλές: ' + tables.sections.CURRENT.rows.length + ' γραμμές');
    }

    if (noPdf) return [];
    const mon = await this.openTab(http, lib, ypoUrl, 'Μηνιαία Ειδοποιητήρια');
    if (!mon) { http.log('[notice] δεν βρέθηκε tab «Μηνιαία Ειδοποιητήρια»'); return []; }
    http.dump('06_monthly_notices.html', mon.raw);
    const headers = [...mon.html.matchAll(/<th\b[^>]*>([\s\S]*?)<\/th>/gi)].map((t) => lib.stripTags(t[1])).filter(Boolean);
    const rows = rowsOf(mon.html).map((cells) => {
      const text = cells.map((c) => cellText(c, lib));
      const btn = cells.map((c) => (c.match(/<button\b[^>]*id="([^"]*)"/i) || [])[1]).find(Boolean)
        || cells.map((c) => (c.match(/<a\b[^>]*id="([^"]*)"[^>]*onclick="[^"]*PrimeFaces\.(?:ab|addSubmitParam)/i) || [])[1]).find(Boolean) || '';
      return { text, month: text.map(monthKey).find(Boolean) || '', btn };
    });
    tables.sections.MONTHLY = { headers, rows: rows.map((r) => r.text) };
    http.log('[notice] Μηνιαία Ειδοποιητήρια: ' + rows.length + ' γραμμές');
    const printable = rows.filter((r) => r.btn && r.month);
    if (!printable.length) { http.log('[notice] καμία γραμμή με εκτύπωση'); return []; }
    const latest = printable.map((r) => r.month).sort().pop();

    const out = [];
    for (const row of printable.filter((r) => r.month === latest)) {
      const kind = row.text.some((t) => /ΤΕΚΑ|TEKA/.test(t)) ? 'TEKA' : 'EFKA';
      const pdf = await http.postForPdf(ypoUrl, {
        [mon.form]: mon.form, [row.btn]: row.btn, [mon.view + '_activeIndex']: mon.tab.dataIndex, 'jakarta.faces.ViewState': mon.vs,
      });
      if (!pdf) { http.log('[notice:' + kind + ' ' + latest + '] no PDF returned'); continue; }
      let dest = path.join(http.dlDir, 'EFKA_' + kind + '_' + afm + '_' + latest + '.pdf');
      for (let n = 2; out.includes(path.basename(dest)); n++) dest = path.join(http.dlDir, 'EFKA_' + kind + '_' + afm + '_' + latest + '_' + n + '.pdf');
      fs.writeFileSync(dest, pdf);
      http.log('[notice:' + kind + ' ' + latest + '] ✅ PDF SAVED -> ' + path.basename(dest) + ' (' + pdf.length + ' bytes)');
      out.push(path.basename(dest));
    }
    return out;
  },

  async getNotice(http, lib, ypoUrl, kind, afm, tables, noPdf) {
    http.log('[notice:' + kind + '] GET obligations (fresh ViewState)');
    const page = (await http.follow('GET', ypoUrl)).text;
    const vs = lib.viewState(page);
    const tab = lib.findTabByText(page, 'Τρέχουσες Οφειλές');
    if (!vs || !tab) { http.log('[notice:' + kind + '] no ViewState/tab'); return null; }
    const newTab = tab.href.replace(/^#/, '');
    http.log('[notice:' + kind + '] tabChange (dataIndex=' + tab.dataIndex + ')');
    const tabResp = await http.follow('POST', ypoUrl, {
      'jakarta.faces.partial.ajax': 'true', 'jakarta.faces.source': FORM_ID,
      'jakarta.faces.partial.execute': FORM_ID, 'jakarta.faces.partial.render': FORM_ID,
      'jakarta.faces.behavior.event': 'tabChange', 'jakarta.faces.partial.event': 'tabChange',
      [FORM_ID + '_contentLoad']: 'true', [FORM_ID + '_newTab']: newTab,
      [FORM_ID + '_tabindex']: tab.dataIndex, 'form-id': 'form-id',
      [FORM_ID + '_activeIndex']: tab.dataIndex, 'jakarta.faces.ViewState': vs,
    });
    http.dump('06_notifications_' + kind + '.html', tabResp.text);
    const upd = lib.extractUpdate(tabResp.text, FORM_ID);
    const rows = lib.dataTableRows(upd, DT[kind]);
    // record the table strings (all rows, cells as text) into the JSON collector
    if (tables && rows) {
      const dtIdx = upd.indexOf('id="' + DT[kind] + '"');
      const headers = dtIdx >= 0 ? [...upd.slice(dtIdx).matchAll(/<th\b[^>]*>([\s\S]*?)<\/th>/gi)].map(t => lib.stripTags(t[1])).filter(Boolean).slice(0, 20) : [];
      tables.sections[kind] = { headers, rows: rows.map(r => r.map(c => lib.stripTags(c))) };
    }
    if (!rows) { http.log('[notice:' + kind + '] no ' + kind + ' notice (datatable absent)'); return null; }
    if (rows.length === 0) { http.log('[notice:' + kind + '] datatable empty'); return null; }
    if (noPdf) return null;
    const tds = rows[0];
    const td4 = tds[4] || '';
    const bm = td4.match(/<button\b([^>]*)>([\s\S]*?)<\/button>/i);
    if (!bm) { http.log('[notice:' + kind + '] no print button; cells: ' + tds.map(lib.stripTags).join(' | ')); return null; }
    const btnId = (bm[1].match(/id="([^"]*)"/i) || [])[1] || '';
    http.log('[notice:' + kind + '] print button id=' + btnId + ' text="' + lib.stripTags(bm[2]) + '"');
    if (!btnId) return null;
    const pdf = await http.postForPdf(ypoUrl, {
      'form-id': 'form-id', [btnId]: '', [FORM_ID + '_activeIndex']: tab.dataIndex, 'jakarta.faces.ViewState': vs,
    });
    if (!pdf) { http.log('[notice:' + kind + '] no PDF returned'); return null; }
    // naming == C# MetaData: AFM # cell1 cell2
    const meta = (afm + '_' + lib.stripTags(tds[1] || '') + '_' + lib.stripTags(tds[2] || '')).replace(/[^A-Za-z0-9_\-]/g, '_').slice(0, 60);
    const dest = path.join(http.dlDir, 'EFKA_' + kind + '_' + (meta || Date.now()) + '.pdf');
    fs.writeFileSync(dest, pdf);
    http.log('[notice:' + kind + '] ✅ PDF SAVED -> ' + dest + ' (' + pdf.length + ' bytes)');
    return path.basename(dest);
  },
};
