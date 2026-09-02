/*
 * configs/keao-debts.js  --  Οφειλές ΕΦΚΑ/ΚΕΑΟ ανά Φορέα + Χρεώσεις/Πιστώσεις  (νέο ΟΠΣ e-ΕΦΚΑ)
 *
 * Το idika EfkaServices (LoginIdikaWithAadeAuth, decompiled) ΕΧΕΙ ΑΠΟΣΥΡΘΕΙ: «Οι Ηλεκτρονικές Υπηρεσίες
 * Μη Μισθωτών έχουν μεταφερθεί στο νέο ΟΠΣ του e-ΕΦΚΑ». Το e-EFKA eDebtor δίνει μόνο μηνύματα (και συχνά
 * ο λογαριασμός δεν έχει πρόσβαση). Η ΤΡΕΧΟΥΣΑ πηγή είναι το νέο ΟΠΣ:
 *   services.e-efka.gov.gr/ssp.efka.non.employees/views/insuranceObligations.xhtml  («Υποχρεώσεις Ασφάλισης»)
 * με login τον ίδιο (services.e-efka.gov.gr non-employee, TAXISnet+ΑΦΜ+ΑΜΚΑ) όπως efka-notices/certificate.
 *
 * Η σελίδα είναι PrimeFaces TabView (form-id:accordion-insurance-obligations-citizen) με 6 tabs:
 *   Τρέχουσες Οφειλές · Ασφαλιστικές Εισφορές (Φορέας/Ιδιότητα) · Μηνιαία Ειδοποιητήρια ·
 *   Εκκαθαρίσεις · Βεβαιωμένες Οφειλές (ΚΕΑΟ) · Καταβολές
 * Κάθε tab φορτώνει τα δεδομένα του με AJAX tabChange (== efka-teka-certificate). Για κάθε βεβαιωμένη
 * οφειλή ΚΕΑΟ υπάρχει κουμπί «Εκτύπωση ΠΒΟ» (download-pbo) & «Εκτύπωση Επίδοσης» (download-epid) -> PDF.
 *
 * INPUTS: TAXISnet user/pass + ΑΦΜ + ΑΜΚΑ (+ pdf; ναι/όχι για λήψη ΠΒΟ ΚΕΑΟ, default ναι).
 * OUTPUT: KEAO_debts_<ΑΦΜ>.json (όλοι οι πίνακες ως strings, ομαδοποίηση ανά Φορέα) + KEAO_PBO_<ΑΦΜ>_<αρ.πράξης>.pdf.
 */
'use strict';
const path = require('path');
const fs = require('fs');

const OBLIG_PATH = '/ssp.efka.non.employees/views/insuranceObligations.xhtml';
const FORM_ID = 'form-id:accordion-insurance-obligations-citizen';
const TABS = [
  { key: 'trexouses', label: 'Τρέχουσες Οφειλές' },
  { key: 'eisfores', label: 'Ασφαλιστικές Εισφορές' },
  { key: 'eidopoiitiria', label: 'Μηνιαία Ειδοποιητήρια' },
  { key: 'ekkatharisis', label: 'Εκκαθαρίσεις' },
  { key: 'keao', label: 'Βεβαιωμένες Οφειλές (ΚΕΑΟ)' },
  { key: 'katavoles', label: 'Καταβολές' },
];

// ViewState from a JSF partial-response (<update id="...ViewState...">value</update>)
function vsFromPartial(xml) {
  const m = xml.match(/<update id="[^"]*ViewState[^"]*"><!\[CDATA\[([\s\S]*?)\]\]><\/update>/i);
  return m ? m[1] : '';
}
// action-cell text is noise (PrimeFaces.cw scripts + button labels) -> blank it for clean strings
const cellText = (strip, c) => (/<button|PrimeFaces\.cw|<script|widget_/i.test(c) ? '' : strip(c));

module.exports = {
  id: 'keao-debts',
  title: 'Οφειλές ΕΦΚΑ/ΚΕΑΟ ανά Φορέα + Χρεώσεις/Πιστώσεις',
  portal: 'e-EFKA νέο ΟΠΣ (services.e-efka.gov.gr, non-employee)',
  subsystem: 'Hyper.Server',
  actions: ['retrieve', 'download'],
  inputs: [
    { key: 'user', label: 'TAXISnet username', env: 'KEAO_USER' },
    { key: 'pass', label: 'TAXISnet password', env: 'KEAO_PASS', hidden: true },
    { key: 'afm', label: 'ΑΦΜ', env: 'KEAO_AFM' },
    { key: 'amka', label: 'ΑΜΚΑ', env: 'KEAO_AMKA' },
    { key: 'pdf', label: 'Λήψη PDF ΠΒΟ ΚΕΑΟ; (ναι/όχι, default ναι)', env: 'KEAO_PDF', optional: true },
  ],

  async run(http, inp, lib) {
    const L = await lib.efkaNonEmployeeLogin(http, inp);
    if (!L.ok) { http.log('LOGIN FAILED: ' + L.reason); return { ok: false, reason: L.reason }; }
    const strip = lib.stripTags;
    const afm = (inp.afm || '').trim();
    const wantPdf = !/^(οχι|όχι|no|n|0)$/i.test((inp.pdf || '').trim());

    const url = L.links['Υποχρεώσεις Ασφάλισης']
      || (((L.landing || '').match(/href="([^"]*insuranceObligations\.xhtml[^"]*)"/i) || [])[1]
        ? new URL(lib.decodeHtml((L.landing.match(/href="([^"]*insuranceObligations\.xhtml[^"]*)"/i))[1]), L.SVC).toString()
        : new URL(OBLIG_PATH, L.SVC).toString());
    http.log('[keao] Υποχρεώσεις Ασφάλισης -> ' + url);
    const page = (await http.follow('GET', url)).text;
    if (!lib.hasId(page, FORM_ID)) { http.log('[keao] page missing obligations accordion (δες τα dumps)'); http.dump('keao_page.html', page); return { ok: false, reason: 'NoAccordion' }; }
    let vs = lib.viewState(page);

    const result = { portal: this.portal, afm, amka: inp.amka, retrievedAt: new Date().toISOString(), tabs: {} };
    const files = [];
    let keaoRows = [], keaoHeaders = [], keaoDataIndex = null, keaoVs = vs;

    for (const t of TABS) {
      const tab = lib.findTabByText(page, t.label);
      if (!tab || !tab.href) { http.log('[keao] tab «' + t.label + '» not found'); continue; }
      const newTab = tab.href.replace(/^#/, '');
      const resp = await http.follow('POST', url, {
        'jakarta.faces.partial.ajax': 'true', 'jakarta.faces.source': FORM_ID,
        'jakarta.faces.partial.execute': FORM_ID, 'jakarta.faces.partial.render': FORM_ID,
        'jakarta.faces.behavior.event': 'tabChange', 'jakarta.faces.partial.event': 'tabChange',
        [FORM_ID + '_contentLoad']: 'true', [FORM_ID + '_newTab']: newTab, [FORM_ID + '_tabindex']: tab.dataIndex,
        'form-id': 'form-id', [FORM_ID + '_activeIndex']: tab.dataIndex,
        'jakarta.faces.ViewState': vs,
      });
      http.dump('keao_tab_' + t.key + '.html', resp.text);
      const upd = lib.extractUpdate(resp.text, FORM_ID) || resp.text;
      const newVs = vsFromPartial(resp.text); if (newVs) vs = newVs;

      const headers = [...upd.matchAll(/<th\b[^>]*>([\s\S]*?)<\/th>/gi)].map(h => strip(h[1]).replace(/Φιλτράρισμα.*$/, '').trim()).filter(Boolean);
      const rawRows = [...upd.matchAll(/<tr\b[^>]*class="[^"]*ui-datatable-(?:even|odd)[^"]*"[^>]*>([\s\S]*?)<\/tr>/gi)]
        .map(r => [...r[1].matchAll(/<td\b[^>]*>([\s\S]*?)<\/td>/gi)].map(c => c[1]));
      const rows = rawRows.map(tds => tds.map(c => cellText(strip, c)));

      const section = { label: t.label, headers, rowCount: rows.length, rows };
      // ομαδοποίηση ανά Φορέα όπου υπάρχει στήλη Φορέας/Ιδιότητα
      const fi = headers.findIndex(h => /^Φορέα|Φορέας|Ιδιότητα|Ταμεί/i.test(h));
      if (fi >= 0 && rows.length) {
        const by = {};
        for (const r of rows) { const f = (r[fi] || '—').trim() || '—'; (by[f] = by[f] || []).push(r); }
        section.byForeas = by;
      }
      result.tabs[t.key] = section;
      http.log('[keao] ' + t.label + ': ' + rows.length + ' γραμμές' + (section.byForeas ? ' (' + Object.keys(section.byForeas).length + ' φορείς)' : ''));

      if (t.key === 'keao') { keaoRows = rawRows; keaoHeaders = headers; keaoDataIndex = tab.dataIndex; keaoVs = vs; }
    }

    // Λήψη PDF ΠΒΟ για κάθε βεβαιωμένη οφειλή ΚΕΑΟ (κουμπί download-pbo)
    if (wantPdf && keaoRows.length) {
      http.log('[keao] λήψη ' + keaoRows.length + ' ΠΒΟ...');
      for (let i = 0; i < keaoRows.length; i++) {
        const tds = keaoRows[i];
        const arithmos = strip(tds[0] || '') || String(i);
        const actionCell = tds[tds.length - 1] || '';
        const pboId = (actionCell.match(/id="([^"]*keao-debts-citizen-datatable:\d+:download-pbo)"/i) || [])[1];
        if (!pboId) { http.log('[keao ' + arithmos + '] no ΠΒΟ button'); continue; }
        const pdf = await http.postForPdf(url, {
          'form-id': 'form-id', [pboId]: '',
          [FORM_ID + '_activeIndex']: keaoDataIndex, 'jakarta.faces.ViewState': keaoVs,
        });
        if (!pdf) { http.log('[keao ' + arithmos + '] no PDF'); continue; }
        const f = 'KEAO_PBO_' + afm + '_' + arithmos + '.pdf';
        fs.writeFileSync(path.join(http.dlDir, f), pdf); files.push(f);
        http.log('[keao ' + arithmos + '] ✅ ΠΒΟ -> ' + f + ' (' + pdf.length + ' b)');
      }
    }

    const jf = path.join(http.dlDir, 'KEAO_debts_' + afm + '.json');
    fs.writeFileSync(jf, JSON.stringify(result, null, 2));
    http.log('[keao-debts] ✅ saved -> ' + path.basename(jf) + ' (' + files.length + ' PDF)');
    return { ok: true, files: [path.basename(jf), ...files] };
  },
};
