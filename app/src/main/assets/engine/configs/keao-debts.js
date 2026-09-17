/*
 * configs/keao-debts.js  --  Οφειλές ΚΕΑΟ ανά φορέα (Ηλεκτρονική Καρτέλα Οφειλέτη)
 * =============================================================================
 * FAITHFUL reproduction of Hyper.Server.Tax.dll  KeaoRetrieveService.RetrieveData
 * (v26.9.4.0, decompiled — NOT obfuscated). Δομή == KeaoRetrieve.KeaoResult:
 *   Carriers[] { Amo, CarrierDescr, CarrierAm, CompanyName,
 *     DeptorTransactions { DeptorID, BranchName,
 *       Debits  { Debit,Credit,Deleted,AdditionalFeesReduction,AdditionalFeesPaid,Reduction,Balance, DebitData[] },
 *       Credits { TotalAmount,AdditionalFees,PrimaryAmount,Increments, CreditData[] },
 *       Regulated { RegulatedData[] { ..., RegulatedInstallmentData[] } } },
 *     Payments { CovidDeptorID, OutOfRegulatedDepts[] } }
 *
 * ΡΟΗ (== hyperserver):
 *   Login (services.e-efka.gov.gr ssp.commonservices.home, role external-non-employee/-employer)
 *     -> landing "Καλώς ήρθατε" -> landingNavigation <a> με span == "Ηλεκτρονική Πλατφόρμα Οφειλετών - KEAO"
 *   GET KeaolandUrl -> amoForm (postUrl=action, jakarta.faces.ViewState), λίστα φορέων amoForm:dt-table (paginated 20)
 *   ΓΙΑ ΚΑΘΕ φορέα (td: 0=Amo,1=CarrierDescr,2=CarrierAm,3=CompanyName,4=button):
 *     EnableForea(button)  -> update#MenuForm
 *     ClickMenuItem(MenuForm,"Κινήσεις Οφειλέτη") -> update#mainContentPanel
 *       DeptorID/BranchName + tab debtorTransForm:mainTabView:
 *         tab0 Χρεώσεις (dt-table-1), tabChange(1) Πιστώσεις (dt-table-2),
 *         tabChange(2) Ρυθμίσεις (dt-table-3) + ανά γραμμή «Δόσεων» -> settlementInstallmentAnalysis
 *     ClickMenuItem(MenuForm,"Πληρωμή/IRIS") -> mainContentPanel: CovidDeptorID + paymentsForm:currentDebtsTable
 *     (μετά από κάθε φορέα, re-GET KeaolandUrl για φρέσκο viewState/postUrl)
 *   Logout(logoutForm «Αποσύνδεση»)
 *
 * INPUTS: TAXISnet user/pass + ΑΦΜ + ΑΜΚΑ (+ legal: Αρ.Μ.Εργοδότη).
 * OUTPUT: KEAO_ofeiles_<afm>.json (πλήρης δομή ανά φορέα, όπως το hyperserver).
 */
'use strict';
const path = require('path');
const fs = require('fs');

const HOST = 'https://services.e-efka.gov.gr/';
const AJAX = { 'Faces-Request': 'partial/ajax', 'X-Requested-With': 'XMLHttpRequest' };

// ---- helpers (== HtmlAgilityPack/XDocument bits used by hyperserver) ----
const cellsTd = (trHtml, strip) => [...trHtml.matchAll(/<td\b[^>]*>([\s\S]*?)<\/td>/gi)].map(c => strip(c[1]));
// rows with data-ri >= 0 (PrimeFaces datatable body rows)
function dataRiRowsFull(html) {
  const rows = []; const re = /<tr\b([^>]*)>([\s\S]*?)<\/tr>/gi; let m;
  while ((m = re.exec(html))) { const ri = (m[1].match(/data-ri="(-?\d+)"/) || [])[1]; if (ri !== undefined && parseInt(ri, 10) >= 0) rows.push({ ri: parseInt(ri, 10), html: m[2] }); }
  return rows;
}
const dataRiRows = (html) => dataRiRowsFull(html).map(r => r.html);
// ordered PrimeFaces panelgrid cells (ui-md-3 label/value pairs)
function panelCells(html, strip) {
  return [...html.matchAll(/<div\b[^>]*class="[^"]*ui-panelgrid-cell[^"]*"[^>]*>([\s\S]*?)<\/div>/gi)].map(c => strip(c[1]));
}
function labeledVal(html, label, strip) {
  const cells = panelCells(html, strip);
  for (let i = 0; i < cells.length - 1; i++) if (cells[i] === label) return cells[i + 1];
  return '';
}
// CDATA payload of the first (or id-matched) <update> in a partial-response
function updateCdata(xml, id) {
  if (id) { const e = id.replace(/[.*+?^${}()|[\]\\:]/g, '\\$&'); const m = xml.match(new RegExp('<update id="' + e + '"><!\\[CDATA\\[([\\s\\S]*?)\\]\\]></update>', 'i')); if (m) return m[1]; }
  const m = xml.match(/<update[^>]*><!\[CDATA\[([\s\S]*?)\]\]><\/update>/i); return m ? m[1] : '';
}
const rowCountOf = (html, lib, id) => {
  const e = id.replace(/[$:.]/g, '\\$&');
  const seg = (html.match(new RegExp('id="' + e + '"[\\s\\S]{0,4000}?rowCount:\\s*(\\d+)', 'i')) || [])[1];
  return seg ? parseInt(seg, 10) : 0;
};
const num = (s) => { const t = (s || '').trim(); return t; };  // keep raw Greek-formatted string (faithful data, not type)
// PrimeFaces <extension>{"totalRecords":N}</extension> — the quote is HTML-encoded (&#34;) whose
// digits (34) must NOT be captured, so require the quote-entity/quote then colon then the number.
const totalRecordsOf = (xml) => { const m = xml.match(/totalRecords(?:&#34;|&quot;|")\s*:\s*(\d+)/i); return m ? parseInt(m[1], 10) : 0; };

module.exports = {
  id: 'keao-debts',
  title: 'Οφειλές ΚΕΑΟ ανά φορέα (Ηλεκτρονική Καρτέλα Οφειλέτη)',
  portal: 'e-EFKA / ΚΕΑΟ (services.e-efka.gov.gr, non-employee/employer)',
  subsystem: 'Hyper.Server',
  actions: ['retrieve'],
  inputs: [
    { key: 'user', label: 'TAXISnet username', env: 'EFKA_USER' },
    { key: 'pass', label: 'TAXISnet password', env: 'EFKA_PASS', hidden: true },
    { key: 'afm', label: 'ΑΦΜ', env: 'EFKA_AFM' },
    { key: 'amka', label: 'ΑΜΚΑ (φυσικά πρόσωπα)', env: 'EFKA_AMKA' },
    { key: 'ame', label: 'Αρ.Μ.Εργοδότη (νομικά πρόσωπα — κενό για φυσικά)', env: 'EFKA_AME', optional: true },
  ],

  async run(http, inp, lib) {
    const strip = lib.stripTags;
    const isLegal = !!(inp.ame && inp.ame.trim());
    // ── Login (== KeaoRetrieveService.Login) — non-employee ή employer ──
    const L = await lib.efkaServicesLogin(http, {
      user: inp.user, pass: inp.pass, afm: inp.afm, amka: inp.amka, ame: inp.ame, isLegal,
    });
    if (!L.ok) { http.log('[keao] LOGIN FAILED: ' + L.reason); return { ok: false, reason: L.reason }; }
    const landing = L.landing;

    // landingNavigation -> «Ηλεκτρονική Πλατφόρμα Οφειλετών - KEAO»
    // (σαρώνουμε ΟΛΟ το landing· τα anchors έχουν nested spans, οπότε κρατάμε το ΤΕΛΕΥΤΑΙΟ span == τίτλος)
    let KeaolandUrl = '';
    for (const a of landing.matchAll(/<a\b[^>]*href="([^"]*)"[^>]*>([\s\S]*?)<\/a>/gi)) {
      const spans = [...a[2].matchAll(/<span[^>]*>([\s\S]*?)<\/span>/gi)];
      const lastSpan = spans.length ? strip(spans[spans.length - 1][1]) : strip(a[2]);
      if (lastSpan === 'Ηλεκτρονική Πλατφόρμα Οφειλετών - KEAO') KeaolandUrl = HOST + lib.decodeHtml(a[1]).replace(/^\//, '');
    }
    if (!KeaolandUrl) {
      http.dump('keao_landing.html', landing);
      http.log('[keao] ⚠ δεν βρέθηκε ο σύνδεσμος ΚΕΑΟ στο landingNavigation (δες keao_landing.html)');
      return { ok: false, reason: 'NoKeaoLink' };
    }
    http.log('[keao] KeaolandUrl = ' + KeaolandUrl);

    const result = { portal: this.portal, afm: inp.afm, retrievedAt: new Date().toISOString(), carriers: [] };
    const rowsPerPage = 20;
    let totalPages = 1, totalRows = 0, rowCounter = 0;

    for (let inStep = 1; inStep <= totalPages; inStep++) {
      let page = await this.carrierPage(http, lib, KeaolandUrl, inStep, rowsPerPage);
      if (inStep === 1) { totalRows = page.totalRows; totalPages = Math.max(1, Math.ceil(totalRows / rowsPerPage)); http.log('[keao] φορείς: ' + totalRows + ' σε ' + totalPages + ' σελ.'); }
      for (let cid = 0; cid < page.rows.length; cid++) {
        rowCounter++;
        const carrier = await this.getForea(http, lib, page.viewState, page.postUrl, page.rows[cid]);
        http.log('[keao] φορέας ' + rowCounter + '/' + totalRows + ': ' + carrier.CarrierDescr + ' (ΑΜ ' + carrier.CarrierAm + ')');
        result.carriers.push(carrier);
        // refresh viewState/postUrl (== hyperserver re-GET after each forea)
        page = await this.carrierPage(http, lib, KeaolandUrl, inStep, rowsPerPage);
      }
    }

    await this.logout(http, lib, KeaolandUrl).catch(() => {});
    const jf = path.join(http.dlDir, 'KEAO_ofeiles_' + inp.afm + '.json');
    fs.writeFileSync(jf, JSON.stringify(result, null, 2));
    http.log('[keao] ✅ ' + result.carriers.length + ' φορείς -> ' + path.basename(jf));
    return { ok: true, files: [path.basename(jf)] };
  },

  // == GetDataFormCarierPage: returns {rows:[trHtml], viewState, postUrl, totalRows}
  async carrierPage(http, lib, KeaolandUrl, pageNumber, rowsPerPage) {
    const strip = lib.stripTags;
    const land = (await http.follow('GET', KeaolandUrl)).text;
    const amoForm = land.match(/<form\b[^>]*id="amoForm"[^>]*>/i);
    if (!amoForm) { http.dump('keao_land_' + pageNumber + '.html', land); throw new Error('amoForm not found'); }
    const postUrl = HOST + ((amoForm[0].match(/action="([^"]*)"/i) || [])[1] || '').replace(/^\//, '');
    const viewState = lib.viewState(land);
    // empty?
    const dataTbl = land.match(/id="amoForm:dt-table_data"[^>]*>([\s\S]*?)<\/tbody>/i);
    if (dataTbl && /Δεν βρέθηκαν εγγραφές\./.test(dataTbl[1])) return { rows: [], viewState, postUrl, totalRows: 0 };
    let totalRows = 0;
    if (pageNumber === 1) totalRows = rowCountOf(land, lib, 'amoForm:dt-table_s');
    const src = 'amoForm:dt-table';
    const form = {
      'jakarta.faces.partial.ajax': 'true', 'jakarta.faces.source': src,
      'jakarta.faces.partial.execute': src, 'jakarta.faces.partial.render': src, [src]: src,
      [src + '_pagination']: 'true', [src + '_first']: String((pageNumber - 1) * rowsPerPage),
      [src + '_rows']: String(rowsPerPage), [src + '_skipChildren']: 'true', [src + '_encodeFeature']: 'true',
      'amoForm': 'amoForm', 'jakarta.faces.ViewState': viewState,
    };
    const xml = (await http.follow('POST', postUrl, form, AJAX)).text;
    const html = updateCdata(xml);
    return { rows: dataRiRows(html), viewState, postUrl, totalRows };
  },

  // == GetForeaFromList
  async getForea(http, lib, viewState, postUrl, foreasRow) {
    const strip = lib.stripTags;
    const tds = [...foreasRow.matchAll(/<td\b[^>]*>([\s\S]*?)<\/td>/gi)].map(c => c[1]);
    const res = {
      Amo: strip(tds[0] || ''), CarrierDescr: strip(tds[1] || ''),
      CarrierAm: strip(tds[2] || ''), CompanyName: strip(tds[3] || ''),
      DeptorTransactions: null, Payments: null,
    };
    const btnId = ((tds[4] || '').match(/<button\b[^>]*id="([^"]*)"/i) || [])[1] || '';
    // EnableForea -> MenuForm
    const enableXml = await this.enableForea(http, viewState, postUrl, btnId);
    const menuForm = updateCdata(enableXml, 'MenuForm');
    if (!menuForm) throw new Error('MenuForm not found (EnableForea)');
    // Κινήσεις Οφειλέτη -> mainContentPanel
    const transXml = await this.clickMenuItem(http, lib, viewState, postUrl, menuForm, 'Κινήσεις Οφειλέτη');
    const transPanel = updateCdata(transXml, 'mainContentPanel');
    res.DeptorTransactions = await this.getDeptorTransactions(http, lib, viewState, postUrl, transPanel);
    // Πληρωμή/IRIS -> mainContentPanel
    const payXml = await this.clickMenuItem(http, lib, viewState, postUrl, menuForm, 'Πληρωμή/IRIS');
    const payPanel = updateCdata(payXml, 'mainContentPanel');
    res.Payments = await this.getDeptorPayments(http, lib, viewState, postUrl, payPanel);
    return res;
  },

  async enableForea(http, viewState, postUrl, sourceItem) {
    const form = {
      'jakarta.faces.partial.ajax': 'true', 'jakarta.faces.source': sourceItem,
      'jakarta.faces.partial.execute': '@all', 'jakarta.faces.partial.render': 'amoForm sidebarUserInfo MenuForm mainContentPanel',
      [sourceItem]: sourceItem, 'amoForm': 'amoForm', 'jakarta.faces.ViewState': viewState,
    };
    return (await http.follow('POST', postUrl, form, AJAX)).text;
  },

  async clickMenuItem(http, lib, viewState, postUrl, menuDocument, menuText) {
    // <a role="menuitem"> innerText==menuText -> onclick PrimeFaces.ab({s:"SRC",
    let src = '';
    for (const a of menuDocument.matchAll(/<a\b([^>]*role="menuitem"[^>]*)>([\s\S]*?)<\/a>/gi)) {
      if (lib.stripTags(a[2]) === menuText) {
        const oc = lib.decodeHtml((a[1].match(/onclick="([^"]*)"/i) || [])[1] || '');
        src = (lib.between(oc, 'PrimeFaces.ab({s:"', '",')[0]) || '';
        break;
      }
    }
    if (!src) throw new Error('menu item not found: ' + menuText);
    const form = {
      'jakarta.faces.partial.ajax': 'true', 'jakarta.faces.source': src,
      'jakarta.faces.partial.execute': '@all', 'jakarta.faces.partial.render': 'mainContentPanel MenuForm',
      [src]: src, 'MenuForm': 'MenuForm', 'jakarta.faces.ViewState': viewState,
    };
    return (await http.follow('POST', postUrl, form, AJAX)).text;
  },

  // == GetDeptorTransactions
  async getDeptorTransactions(http, lib, viewState, postUrl, mainContentPanel) {
    const strip = lib.stripTags;
    if (process.env.KEAO_DEBUG) http.dump('DBG_mainContentPanel.html', mainContentPanel);
    const res = { DeptorID: '', BranchName: '', Debits: null, Credits: null, Regulated: null };
    // label <span>…</span></div> then value cell <div …>VALUE</div>  (ui-grid-col-5 label / col-7 value)
    const gridVal = (label) => {
      const m = mainContentPanel.match(new RegExp(label.replace(/[.*+?^${}()|[\]\\]/g, '\\$&') + '<\\/span>\\s*<\\/div>\\s*<div[^>]*>([\\s\\S]*?)<\\/div>', 'i'));
      return m ? strip(m[1]) : '';
    };
    res.DeptorID = gridVal('Ταυτότητα Οφειλέτη:');
    res.BranchName = gridVal('Αρμόδιο Υποκ/μα:');
    const rpp = 20;
    // tab0 Debit — summary from mainContentPanel, lines via dt-table-1
    res.Debits = await this.getDebit(http, lib, viewState, postUrl, mainContentPanel, rpp);
    // tabChange(1) -> Credit
    const credXml = await this.changeTab(http, viewState, postUrl, 1);
    const credTab = updateCdata(credXml, 'debtorTransForm:mainTabView');
    res.Credits = await this.getCredit(http, lib, viewState, postUrl, credTab, rpp);
    // tabChange(2) -> Regulated
    const regXml = await this.changeTab(http, viewState, postUrl, 2);
    const regTab = updateCdata(regXml, 'debtorTransForm:mainTabView');
    res.Regulated = await this.getRegulated(http, lib, viewState, postUrl, regTab, rpp);
    return res;
  },

  async getDebit(http, lib, viewState, postUrl, tabHtml, rpp) {
    const strip = lib.stripTags;
    const res = {
      Debit: labeledVal(tabHtml, 'Χρέωση:', strip), Credit: labeledVal(tabHtml, 'Πίστωση:', strip),
      Deleted: labeledVal(tabHtml, 'Διαγραφή:', strip), AdditionalFeesReduction: labeledVal(tabHtml, 'Έκπτωση Προσθέτων Τελών:', strip),
      Balance: labeledVal(tabHtml, 'Υπόλοιπο:', strip), AdditionalFeesPaid: labeledVal(tabHtml, 'Καταβληθέντα Πρόσθετα Τέλη:', strip),
      Reduction: labeledVal(tabHtml, 'Έκπτωση:', strip), DebitData: [],
    };
    let totalPages = 1;
    for (let inStep = 1; inStep <= totalPages; inStep++) {
      const xml = await this.debitTable(http, viewState, postUrl, 0, inStep, rpp);
      if (process.env.KEAO_DEBUG && inStep === 1) http.dump('DBG_debitTable.xml', xml);
      const html = updateCdata(xml);
      if (inStep === 1) { const trc = totalRecordsOf(xml); totalPages = trc > 0 ? Math.ceil(trc / rpp) : 1; }
      for (const trh of dataRiRows(html)) {
        const c = cellsTd(trh, strip);
        res.DebitData.push({ Branch: c[0], IssueDate: c[1], DocumentInfo: c[2], ConfirmInfo: (c[3] || '').replace(/\n/g, ''), Debit: c[4], Balance: c[5], Credit: c[6], Reduction: c[7], DeletedAmount: c[8], Additional: c[9], AdditionalReduction: c[10] });
      }
    }
    return res;
  },

  async getCredit(http, lib, viewState, postUrl, tabHtml, rpp) {
    const strip = lib.stripTags;
    const res = {
      TotalAmount: labeledVal(tabHtml, 'Συνολικό Ποσό:', strip), AdditionalFees: labeledVal(tabHtml, 'Πρόσθετα Τέλη:', strip),
      PrimaryAmount: labeledVal(tabHtml, 'Κύρια Εισφορά:', strip), Increments: labeledVal(tabHtml, 'Προσαυξήσεις:', strip), CreditData: [],
    };
    let totalPages = 1;
    for (let inStep = 1; inStep <= totalPages; inStep++) {
      const xml = await this.debitTable(http, viewState, postUrl, 1, inStep, rpp);
      const html = updateCdata(xml);
      if (inStep === 1) { const trc = totalRecordsOf(xml); totalPages = trc > 0 ? Math.ceil(trc / rpp) : 1; }
      for (const trh of dataRiRows(html)) {
        const c = cellsTd(trh, strip);
        res.CreditData.push({ Branch: c[0], IssueDate: c[1], DocumentInfo: c[2], TransCode: c[3], Total: c[4], CreditPrimary: c[5], Additional: c[6], Increments: c[7] });
      }
    }
    return res;
  },

  async getRegulated(http, lib, viewState, postUrl, tabHtml, rpp) {
    const strip = lib.stripTags;
    const res = { RegulatedData: [] };
    let totalPages = 1;
    for (let inStep = 1; inStep <= totalPages; inStep++) {
      const xml = await this.debitTable(http, viewState, postUrl, 2, inStep, rpp);
      const html = updateCdata(xml);
      if (inStep === 1) { const trc = totalRecordsOf(xml); totalPages = trc > 0 ? Math.ceil(trc / rpp) : 1; }
      const rows = dataRiRowsFull(html);
      for (const row of rows) {
        const c = cellsTd(row.html, strip);
        const line = { Branch: c[0], ResolutionInfo: (c[1] || '').replace(/\n/g, ''), RegulatePrimary: c[2], Additional: c[3], Interest: c[4], Total: c[5], ResolutionType: c[6], PayType: c[7], RegulatedStatus: c[8], RegulatedInstallmentData: [] };
        // per-row «Δόσεων» menu -> settlement installment analysis (absolute data-ri, == hyperserver)
        const menuId = 'debtorTransForm:mainTabView:dt-table-3:' + row.ri + ':actionSelect3_menu';
        const menuBlock = html.match(new RegExp('id="' + menuId.replace(/[$:.]/g, '\\$&') + '"([\\s\\S]*?)<\\/ul>', 'i'));
        let dosSrc = '';
        if (menuBlock) { for (const a of menuBlock[1].matchAll(/<a\b[^>]*id="([^"]*)"[^>]*>([\s\S]*?)<\/a>/gi)) if (strip(a[2]) === 'Δόσεων') { dosSrc = a[1]; break; } }
        if (dosSrc) line.RegulatedInstallmentData = await this.getInstallments(http, lib, viewState, postUrl, dosSrc);
        res.RegulatedData.push(line);
      }
    }
    return res;
  },

  async getInstallments(http, lib, viewState, postUrl, sourceElement) {
    const strip = lib.stripTags;
    // open dialog
    const openForm = {
      'jakarta.faces.partial.ajax': 'true', 'jakarta.faces.source': sourceElement, 'jakarta.faces.partial.execute': sourceElement,
      'jakarta.faces.partial.render': 'debtorTransForm:settlementInstallmentAnalysisDialog debtorTransForm:dt-settlement-installment-analysis',
      [sourceElement]: sourceElement, 'debtorTransForm': 'debtorTransForm', 'debtorTransForm:mainTabView_activeIndex': '2', 'jakarta.faces.ViewState': viewState,
    };
    const dlgXml = (await http.follow('POST', postUrl, openForm, AJAX)).text;
    const dlg = updateCdata(dlgXml, 'debtorTransForm:settlementInstallmentAnalysisDialog');
    const rc = rowCountOf(dlg, lib, 'debtorTransForm:dt-settlement-installment-analysis_s');
    const rpp = 10; const out = [];
    const totalPages = rc > 0 ? Math.ceil(rc / rpp) : 0;
    const src = 'debtorTransForm:dt-settlement-installment-analysis';
    for (let inStep = 1; inStep <= totalPages; inStep++) {
      const form = {
        'jakarta.faces.partial.ajax': 'true', 'jakarta.faces.source': src, 'jakarta.faces.partial.execute': src, 'jakarta.faces.partial.render': src, [src]: src,
        [src + '_pagination']: 'true', [src + '_first']: String((inStep - 1) * rpp), [src + '_rows']: String(rpp), [src + '_skipChildren']: 'true', [src + '_encodeFeature']: 'true',
        'debtorTransForm': 'debtorTransForm', 'debtorTransForm:mainTabView_activeIndex': '2', 'jakarta.faces.ViewState': viewState,
      };
      const xml = (await http.follow('POST', postUrl, form, AJAX)).text;
      const html = updateCdata(xml);
      for (const trh of dataRiRows(html)) {
        const c = cellsTd(trh, strip);
        out.push({ RowAA: c[0], ExpirationDate: c[1], Amount: c[2], Payed: c[3], Balance: c[4], Increments: c[5] });
      }
    }
    return out;
  },

  async changeTab(http, viewState, postUrl, tabIndex) {
    const form = {
      'jakarta.faces.partial.ajax': 'true', 'jakarta.faces.source': 'debtorTransForm:mainTabView',
      'jakarta.faces.partial.render': 'debtorTransForm:mainTabView', 'jakarta.faces.partial.execute': 'debtorTransForm:mainTabView',
      'jakarta.faces.behavior.event': 'tabChange', 'jakarta.faces.partial.event': 'tabChange',
      'debtorTransForm:mainTabView_contentLoad': 'true', 'debtorTransForm:mainTabView_newTab': 'debtorTransForm:mainTabView:tab' + (tabIndex + 1),
      'debtorTransForm:mainTabView_tabindex': String(tabIndex), 'debtorTransForm': 'debtorTransForm',
      'debtorTransForm:mainTabView_activeIndex': String(tabIndex), 'jakarta.faces.ViewState': viewState,
    };
    return (await http.follow('POST', postUrl, form, AJAX)).text;
  },

  async debitTable(http, viewState, postUrl, activeIndex, pageNumber, rpp) {
    const src = 'debtorTransForm:mainTabView:dt-table-' + (activeIndex + 1);
    const form = {
      'jakarta.faces.partial.ajax': 'true', 'jakarta.faces.source': src, 'jakarta.faces.partial.execute': src, 'jakarta.faces.partial.render': src, [src]: src,
      [src + '_pagination']: 'true', [src + '_first']: String((pageNumber - 1) * rpp), [src + '_rows']: String(rpp), [src + '_skipChildren']: 'true', [src + '_encodeFeature']: 'true',
      'debtorTransForm': 'debtorTransForm', 'debtorTransForm:mainTabView_activeIndex': String(activeIndex), 'jakarta.faces.ViewState': viewState,
    };
    return (await http.follow('POST', postUrl, form, AJAX)).text;
  },

  // == GetDeptorPayments (+ OutOfRegulatedDepts)
  async getDeptorPayments(http, lib, viewState, postUrl, mainContentPanel) {
    const strip = lib.stripTags;
    const res = { CovidDeptorID: '', OutOfRegulatedDepts: [] };
    // CovidDeptorID: label «Ταυτότητα Οφειλέτη:» whose 2nd-following cell == «Συνολικό Ποσό Οφειλών COVID»
    const cells = panelCells(mainContentPanel, strip);
    for (let i = 0; i < cells.length - 2; i++) {
      if (cells[i].startsWith('Ταυτότητα Οφειλέτη:') && cells[i + 2] === 'Συνολικό Ποσό Οφειλών COVID') { res.CovidDeptorID = cells[i + 1]; break; }
    }
    const rc = rowCountOf(mainContentPanel, lib, 'paymentsForm:currentDebtsTable_s');
    const rpp = 10; const totalPages = rc > 0 ? Math.ceil(rc / rpp) : 0;
    const src = 'paymentsForm:currentDebtsTable';
    for (let inStep = 1; inStep <= totalPages; inStep++) {
      const form = {
        'jakarta.faces.partial.ajax': 'true', 'jakarta.faces.source': src, 'jakarta.faces.partial.execute': src, 'jakarta.faces.partial.render': src, [src]: src,
        [src + '_pagination']: 'true', [src + '_first']: String((inStep - 1) * rpp), [src + '_rows']: String(rpp), [src + '_skipChildren']: 'true', [src + '_encodeFeature']: 'true',
        'paymentsForm': 'paymentsForm', 'debtorTransForm:mainTabView_activeIndex': '2', 'jakarta.faces.ViewState': viewState,
      };
      const xml = (await http.follow('POST', postUrl, form, AJAX)).text;
      const html = updateCdata(xml);
      for (const trh of dataRiRows(html)) {
        const c = cellsTd(trh, strip);
        res.OutOfRegulatedDepts.push({ IssueDate: c[0], DocumentInfo: c[1], Primary: c[2], Additional: c[3], Total: c[4] });
      }
    }
    return res;
  },

  async logout(http, lib, KeaolandUrl) {
    const strip = lib.stripTags;
    const html = (await http.follow('GET', KeaolandUrl)).text;
    const vs = lib.viewState(html);
    const logoutBlock = html.match(/id="logoutForm"([\s\S]*?)<\/form>/i);
    if (!logoutBlock) return;
    let jt = '';
    for (const a of logoutBlock[1].matchAll(/<a\b[^>]*id="([^"]*)"[^>]*>([\s\S]*?)<\/a>/gi)) if (strip(a[2]).endsWith('Αποσύνδεση')) { jt = a[1]; break; }
    if (!jt) return;
    const form = {
      'jakarta.faces.partial.ajax': 'true', 'jakarta.faces.source': jt, 'jakarta.faces.partial.execute': '@all',
      [jt]: jt, 'logoutForm': 'logoutForm', 'jakarta.faces.ViewState': vs,
    };
    const postUrl = HOST + (((html.match(/<form\b[^>]*id="logoutForm"[^>]*action="([^"]*)"/i) || [])[1] || '').replace(/^\//, ''));
    await http.follow('POST', postUrl || KeaolandUrl, form, AJAX);
  },
};
