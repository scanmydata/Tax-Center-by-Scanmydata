/*
 * configs/easynotify.js  --  EasyNotify: αυτόματη άντληση μηνυμάτων/ειδοποιήσεων (ΑΑΔΕ)
 * =============================================================================
 * FAITHFUL reproduction of Hyper.Server.Tax.dll  AadeMessagesService (v26.9.4.0,
 * non-obfuscated) — τα AADE-login message retrievers του EasyNotify:
 *   AADE     -> RetrieveMessages/GetMessagesAade  (taxisnet/mymessages inbox.htm scraping)
 *   PROPERTY -> GetMyPropertyMessages             (saadeapps3/myPROPERTY REST: getAllOldMsg + getallmessages)
 *   REQUESTS -> GetAadeRequests                   (saadekef/eticketaade /api/amsmsg/filterMessages)
 *
 * Το EasyNotify στο TaxSystem σώζει τα μηνύματα σε πίνακες (PARTY_AADE_MESSAGES,
 * PARTY_MY_PROPERTY_*, PARTY_RETRIEVE_AADE_REQ_DATA_*) με incremental LAST_ID. Εδώ
 * αντ' αυτού γράφουμε το ΠΛΗΡΕΣ αποτέλεσμα σε JSON (+ συνημμένα ΑΑΔΕ ως αρχεία).
 *
 * INPUTS: TAXISnet user/pass (+ προαιρετικά which, vat, since, files).
 * OUTPUT: EASYNOTIFY_<vat|user>.json  + AADE_MSG_<id>_<file> (συνημμένα, αν files=1).
 */
'use strict';
const path = require('path');
const fs = require('fs');

const AADE = 'https://www1.aade.gr';
const MYMSG = AADE + '/taxisnet/mymessages/protected/';
const MBS = AADE + '/saadeapps3/myPROPERTY';
const ETICKET = AADE + '/saadekef/eticketaade';

module.exports = {
  id: 'easynotify',
  title: 'EasyNotify — Μηνύματα/Ειδοποιήσεις ΑΑΔΕ (Μηνύματα / myPROPERTY / Τα Αιτήματά μου)',
  portal: 'AADE (GSIS OAM)',
  subsystem: 'Hyper.Server',
  actions: ['retrieve'],
  inputs: [
    { key: 'user', label: 'TAXISnet username', env: 'AADE_USER' },
    { key: 'pass', label: 'TAXISnet password', env: 'AADE_PASS', hidden: true },
    { key: 'which', label: 'Ποια (AADE/PROPERTY/REQUESTS/EFKA/KEAO/GEMH — κενό = όλες οι ΑΑΔΕ)', env: 'EN_WHICH', optional: true },
    { key: 'vat', label: 'ΑΦΜ (κενό = αυτόματα)', env: 'AADE_VAT', optional: true },
    { key: 'amka', label: 'ΑΜΚΑ (για ΕΦΚΑ/ΚΕΑΟ μηνύματα)', env: 'AADE_AMKA', optional: true },
    { key: 'gemhUser', label: 'Κωδικός Γ.Ε.ΜΗ. username (για GEMH)', env: 'GEMH_USER', optional: true },
    { key: 'gemhPass', label: 'Κωδικός Γ.Ε.ΜΗ. password (για GEMH)', env: 'GEMH_PASS', optional: true, hidden: true },
    { key: 'since', label: 'Μηνύματα ΑΑΔΕ από ημ/νία (dd/mm/yyyy, κενό = όλα)', env: 'EN_SINCE', optional: true },
    { key: 'files', label: 'Λήψη συνημμένων ΑΑΔΕ (1/0)', env: 'EN_FILES', optional: true },
  ],

  async run(http, inp, lib) {
    const want = (inp.which || '').trim().toUpperCase();
    // AADE-login types (default όλες οι ΑΑΔΕ όταν κενό)· οι ΕΦΚΑ/ΚΕΑΟ/ΓΕΜΗ μόνο ρητά (ή ALL).
    const all = want === 'ALL' || want === 'ΟΛΑ';
    const aadeDefault = !want; // κενό = AADE trio (backward compatible)
    const doAade = all || aadeDefault || /\bAADE\b|ΜΗΝΥΜ|MSG/.test(want);
    const doProp = all || aadeDefault || /PROP|ΠΕΡΙΟΥΣ/.test(want);
    const doReq = all || aadeDefault || /REQ|ΑΙΤΗΜ/.test(want);
    const doEfka = all || /EFKA|ΕΦΚΑ/.test(want);
    const doKeao = all || /KEAO|ΚΕΑΟ/.test(want);
    const doGemi = all || /GEMH|GEMI|ΓΕΜΗ/.test(want);
    const out = { portal: this.portal, retrievedAt: new Date().toISOString() };
    const files = [];
    let vat = (inp.vat || '').trim();

    // ── AADE portal (Μηνύματα / myPROPERTY / Αιτήματα) — ένα OAM login ──
    if (doAade || doProp || doReq) {
      const L = await lib.aadeLogin(http, inp);
      if (!L.ok) { http.log('AADE LOGIN FAILED: ' + L.reason); if (!(doEfka || doKeao || doGemi)) return { ok: false, reason: L.reason }; }
      else {
        if (!vat) vat = (lib.stripTags(L.page && L.page.text || '').match(/Α\.?Φ\.?Μ\.?\s*[:\-]?\s*(\d{9})/) || [])[1] || '';
        out.vat = vat;
        if (doAade) { try { out.aadeMessages = await this.getAadeMessages(http, lib, inp, files); } catch (e) { out.aadeMessages = 'ERR:' + e.message; http.log('[aade-msg] ERROR ' + e.message); } }
        if (doProp) { try { out.myProperty = await this.getMyProperty(http, lib, vat); } catch (e) { out.myProperty = 'ERR:' + e.message; http.log('[property] ERROR ' + e.message); } }
        if (doReq) { try { const r = await this.getAadeRequests(http, lib, vat); out.aadeRequests = { count: r.list.length, summary: r.summary, items: r.list }; if (!vat && r.vat) out.vat = vat = r.vat; } catch (e) { out.aadeRequests = 'ERR:' + e.message; http.log('[requests] ERROR ' + e.message); } }
      }
    }
    // ── ΚΕΑΟ μηνύματα (eDebtor inbox) — δικό του login ──
    if (doKeao) { try { out.keaoMessages = await this.getKeaoMessages(http, lib, inp); } catch (e) { out.keaoMessages = 'ERR:' + e.message; http.log('[keao-msg] ERROR ' + e.message); } }
    // ── ΕΦΚΑ μηνύματα (idika EfkaPersonalMessages) — δικό του login ──
    if (doEfka) { try { out.efkaMessages = await this.getEfkaMessages(http, lib, inp, vat); } catch (e) { out.efkaMessages = 'ERR:' + e.message; http.log('[efka-msg] ERROR ' + e.message); } }
    // ── ΓΕΜΗ μηνύματα (businessportal) — κωδικοί Γ.Ε.ΜΗ. ──
    if (doGemi) { try { out.gemiMessages = await this.getGemiMessages(http, lib, inp); } catch (e) { out.gemiMessages = 'ERR:' + e.message; http.log('[gemi-msg] ERROR ' + e.message); } }

    const n = (x, k) => (x && Array.isArray(x) ? x.length : (x && x[k] != null ? (Array.isArray(x[k]) ? x[k].length : x[k]) : (x && x.count != null ? x.count : '-')));
    const jf = path.join(http.dlDir, 'EASYNOTIFY_' + (vat || inp.user) + '.json');
    fs.writeFileSync(jf, JSON.stringify(out, null, 2));
    http.log('[easynotify] ✅ saved -> ' + path.basename(jf)
      + ' (aade=' + n(out.aadeMessages) + ' property=' + n(out.myProperty, 'messages') + ' requests=' + n(out.aadeRequests)
      + ' keao=' + n(out.keaoMessages) + ' efka=' + n(out.efkaMessages) + ' gemi=' + n(out.gemiMessages) + ')');
    return { ok: true, files: [path.basename(jf), ...files] };
  },

  // ── ΚΕΑΟ μηνύματα (== GetKEAOMessages: eDebtor inbox.xhtml) ──
  async getKeaoMessages(http, lib, inp) {
    const strip = lib.stripTags;
    const L = await lib.keaoEDebtorLogin(http, { user: inp.user, pass: inp.pass });
    if (!L.ok) { http.log('[keao-msg] LOGIN FAILED: ' + L.reason); return 'LOGIN:' + L.reason; }
    const html = (await http.follow('GET', 'https://apps.e-efka.gov.gr/eDebtor/secure/inbox.xhtml')).text;
    http.dump('keao_inbox.html', html);
    const grid = html.match(/id="inboxMessagesTable"[\s\S]*?<table[^>]*role="grid"[^>]*>([\s\S]*?)<\/table>/i);
    const rows = [];
    if (grid) {
      const tbody = grid[1].match(/<tbody[^>]*>([\s\S]*?)<\/tbody>/i);
      for (const tr of (tbody ? tbody[1] : grid[1]).matchAll(/<tr\b[^>]*>([\s\S]*?)<\/tr>/gi)) {
        const tds = [...tr[1].matchAll(/<td\b[^>]*>([\s\S]*?)<\/td>/gi)];
        if (tds.length < 4) continue;
        const info = strip(tds[1][1]).split(',').map(s => s.trim());
        const find = (p) => { const x = info.find(s => s.startsWith(p)); return x ? x.replace(p, '').trim() : ''; };
        const titleA = tds[2][1].match(/<a\b[^>]*>([\s\S]*?)<\/a>/i);
        rows.push({
          hmer: strip(tds[0][1]), amo: find('Α.Μ.Ο.:'), foreas: find('Φορέας:'),
          amStonForea: find('Α.Μ. στο Φορέα:'), titlos: strip(titleA ? titleA[1] : tds[2][1]), hmerAnagn: strip(tds[3][1]),
        });
      }
    }
    http.log('[keao-msg] ' + rows.length + ' μηνύματα ΚΕΑΟ');
    return rows;
  },

  // ── ΕΦΚΑ μηνύματα (== GetEfkaMessages: idika EfkaPersonalMessages ASPxGridView) ──
  // idika EfkaServices (www.idika.org.gr) — προς το παρόν 503 (μεταφορά στο νέο ΟΠΣ)· η ροή είναι πιστή.
  async getEfkaMessages(http, lib, inp, vat) {
    const strip = lib.stripTags;
    const L = await lib.idikaLoginAade(http, { user: inp.user, pass: inp.pass, afm: vat || inp.vat, amka: inp.amka });
    if (!L.ok) { http.log('[efka-msg] idika LOGIN FAILED: ' + L.reason + ' (idika EfkaServices πιθανόν 503/μεταφερθέν)'); return 'LOGIN:' + L.reason; }
    const IDIKA = L.IDIKA || 'https://www.idika.org.gr/EfkaServices';
    await http.follow('GET', IDIKA + '/Application/MyDashboard.aspx');
    const page = (await http.follow('GET', IDIKA + '/Application/EfkaPersonalMessages.aspx')).text;
    http.dump('efka_messages.html', page);
    const grid = page.match(/id="ContentPlaceHolder1_PersonalMessagesGV_DXMainTable"[\s\S]*?<\/table>/i);
    const keyIds = (lib.between(page, "'stateObject':{'keys':[", "'],")[0] || '').replace(/'/g, '').split(',').filter(Boolean);
    const rows = [];
    if (grid) {
      let i = 0;
      for (const tr of grid[0].matchAll(/<tr\b([^>]*id="[^"]*_DXDataRow[^"]*"[^>]*)>([\s\S]*?)<\/tr>/gi)) {
        const tds = [...tr[2].matchAll(/<td\b[^>]*>([\s\S]*?)<\/td>/gi)];
        if (tds.length < 3) continue;
        rows.push({ messageId: keyIds[i] || '', messageDate: strip(tds[1][1]), title: strip(tds[2][1]) });
        i++;
      }
    }
    // Σημ.: η σελιδοποίηση/άνοιγμα σώματος γίνονται με DevExpress ASPxGridView callbacks
    // (EfkaMessageNextPage/EfkaMessagePopUp)· απαιτούν ζωντανό idika για ακριβή αναπαραγωγή.
    http.log('[efka-msg] ' + rows.length + ' μηνύματα ΕΦΚΑ (σελ.1)');
    return rows;
  },

  // ── ΓΕΜΗ μηνύματα (== GetGemiMessages: businessportal emailsAsJSON + emailDetails) ──
  async getGemiMessages(http, lib, inp) {
    if (!inp.gemhUser || !inp.gemhPass) { http.log('[gemi-msg] χωρίς κωδικούς Γ.Ε.ΜΗ. (gemhUser/gemhPass) — παράλειψη'); return 'NoGemhCreds'; }
    const L = await lib.gemiLogin(http, { user: inp.gemhUser, pass: inp.gemhPass });
    if (!L.ok) { http.log('[gemi-msg] LOGIN FAILED: ' + L.reason); return 'LOGIN:' + L.reason; }
    const host = L.host;
    await http.follow('GET', host + 'api/authentication/checkSession?lang=el');
    const pageSize = 5; const msgs = []; let pg = 0, loop = true, guard = 0;
    while (loop) {
      const r = await http.follow('POST', host + 'api/email/emailsAsJSON?lang=el', { length: String(pageSize), start: String(pg * pageSize) });
      const res = this.json(r.text); if (!res) throw new Error('emailsAsJSON parse');
      const total = res.recordsTotal || 0;
      if ((pg === 0 && total === 0) || Math.ceil(total / pageSize) === pg + 1) loop = false;
      pg++; if (pg > 1000) break;
      for (const m of (res.data || [])) {
        const det = this.json((await http.follow('GET', host + '/api/email/emailDetails?emailId=' + m.id + '&type=' + m.messageTypeId + '&lang=el')).text);
        m._body = det && det.emailData ? det.emailData.body : '';
        if (!msgs.find(x => x.id === m.id)) msgs.push(m);
      }
    }
    http.log('[gemi-msg] ' + msgs.length + ' μηνύματα Γ.Ε.ΜΗ.');
    return msgs;
  },

  // ── AADE «Τα Μηνύματά μου» (== GetMessagesAade + ReadPage) ──
  async getAadeMessages(http, lib, inp, files) {
    const strip = lib.stripTags;
    const since = (inp.since || '').trim();
    const sinceD = since ? this.parseDate(since) : null;
    const saveFiles = /^(1|true|ναι|yes)$/i.test((inp.files || '').trim());

    let html = (await http.follow('GET', MYMSG + 'inbox.htm')).text;
    if (html.includes('Δεν έχετε κανένα μήνυμα') && !/class="pagelinks"/.test(html)) { http.log('[aade-msg] καμία εγγραφή'); return []; }
    // pagination: span.pagelinks -> «Τελευταία» href «...=N»
    const lastHref = this.pageLastHref(html);
    let totalPages = lastHref.total, nextPrefix = lastHref.prefix;
    const idUrls = new Map(); const importantIds = new Set();
    let page = 0, guard = 0, loop = totalPages > 0;
    // read page 1 (already loaded), then subsequent
    while (loop) {
      page++;
      const before = idUrls.size;
      const cont = this.readPage(html, sinceD, idUrls, importantIds, strip);
      if (idUrls.size === before) guard++; if (guard === 4) throw new Error('loop page error');
      if (cont && page < totalPages) {
        html = (await http.follow('GET', MYMSG + 'inbox.htm' + nextPrefix + (page + 1))).text;
        const lh = this.pageLastHref(html); if (lh.total > totalPages) totalPages = lh.total;
      } else loop = false;
    }
    // fetch each message (reverse order == C#)
    const msgs = [];
    const ids = [...idUrls.entries()];
    for (let i = ids.length - 1; i >= 0; i--) {
      const [id, url] = ids[i];
      const mh = (await http.follow('GET', MYMSG + url)).text;
      const m = { id, from: '', subject: '', sendDate: '', readDate: '', fileName: '', marked: importantIds.has(id), htmlText: '' };
      // td.tblFormPrompt label -> value = nextSibling.nextSibling (== next <td>)
      const cells = [...mh.matchAll(/<td\b([^>]*)>([\s\S]*?)<\/td>/gi)];
      for (let k = 0; k < cells.length; k++) {
        if (!/class="tblFormPrompt"/i.test(cells[k][1])) continue;
        const label = strip(cells[k][2]); const val = cells[k + 1] ? strip(cells[k + 1][2]) : '';
        if (label === 'Από') m.from = val;
        else if (label === 'Θέμα') m.subject = val;
        else if (label === 'Ημ/νία Αποστολής') m.sendDate = val;
        else if (label === 'Ημ/νία Ανάγνωσης') m.readDate = val;
        else if (label === 'Συνημμένο') {
          const a = (cells[k + 1] ? cells[k + 1][2] : '').match(/<a\b[^>]*href="([^"]*)"[^>]*>([\s\S]*?)<\/a>/i);
          if (a && strip(a[2])) {
            m.fileName = strip(a[2]);
            if (saveFiles) {
              const doc = await http.getDoc(AADE + lib.decodeHtml(a[1]));
              if (doc.buffer) { const f = ('AADE_MSG_' + id + '_' + m.fileName).replace(/[^\w.\-]/g, '_'); fs.writeFileSync(path.join(http.dlDir, f), doc.buffer); files.push(f); m.savedFile = f; }
            }
          }
        }
      }
      // body: element id="xxx" (== C# GetElementbyId("xxx"))
      const body = mh.match(/id="xxx"[^>]*>([\s\S]*?)<\/(?:div|td|table)>/i);
      m.htmlText = body ? body[1].trim() : '';
      msgs.push(m);
    }
    http.log('[aade-msg] ' + msgs.length + ' μηνύματα');
    return msgs;
  },
  pageLastHref(html) {
    const block = (html.match(/<span[^>]*class="pagelinks"[^>]*>([\s\S]*?)<\/span>/i) || [, ''])[1];
    for (const a of block.matchAll(/<a\b[^>]*href="([^"]*)"[^>]*>([\s\S]*?)<\/a>/gi)) {
      if (this.txt(a[2]) === 'Τελευταία') { const h = a[1].replace(/&amp;/g, '&'); const eq = h.indexOf('='); return { total: parseInt(h.slice(eq + 1), 10) || 0, prefix: h.slice(0, eq + 1) }; }
    }
    return { total: 0, prefix: '' };
  },
  readPage(html, sinceD, idUrls, importantIds, strip) {
    const sr = html.match(/id="searchResults"([\s\S]*?)<\/table>/i); if (!sr) return true;
    let cont = true;
    for (const tr of sr[1].matchAll(/<tr\b([^>]*)>([\s\S]*?)<\/tr>/gi)) {
      const cls = (tr[1].match(/class="([^"]*)"/) || [])[1] || '';
      if (cls !== 'odd' && cls !== 'even') continue;
      const tds = [...tr[2].matchAll(/<td\b[^>]*>([\s\S]*?)<\/td>/gi)];
      if (tds.length !== 5) continue;
      const a = tds[4][1].match(/<a\b[^>]*href="([^"]*)"/i); if (!a) continue;
      const href = a[1].replace(/&amp;/g, '&');
      const id = href.replace('viewMessage.htm?id=', '').trim();
      if (idUrls.has(id)) continue;
      const imgs = [...tds[1][1].matchAll(/<img\b/gi)];
      if (imgs.length === 1) importantIds.add(id);
      if (sinceD) { const d = this.parseDate(strip(tds[3][1])); if (d && d < sinceD) { cont = false; break; } }
      idUrls.set(id, href);
    }
    return cont;
  },

  // ── myPROPERTY μηνύματα (== GetMyPropertyMessages) ──
  async getMyProperty(http, lib, vat) {
    // establish myPROPERTY session (follow redirect chain), == C#
    await http.follow('GET', MBS + '?');
    let v = vat;
    if (!v) { const ud = (await http.follow('GET', MBS + '/webresources/capitalcommon/getuserdata/username')).text; v = (ud.match(/<afm>\s*(\d{9})\s*<\/afm>/i) || ud.match(/"afm"\s*:\s*"?(\d{9})/i) || [])[1] || ''; }
    else { await http.follow('GET', MBS + '/webresources/capitalcommon/getuserdata/username'); }
    await http.follow('GET', MBS + '/webresources/capitalcommon/getDownTime/username');
    const updates = this.json((await http.follow('GET', MBS + '/webresources/capitalcommon/getAllOldMsg/' + v)).text) || [];
    const messages = this.json((await http.follow('GET', MBS + '/webresources/capitalcommon/getallmessages')).text) || [];
    http.log('[property] updates=' + updates.length + ' messages=' + messages.length);
    return { updates, messages };
  },

  // ── ΑΑΔΕ «Τα Αιτήματά μου» (== GetAadeRequests via eticketaade) ──
  async getAadeRequests(http, lib, vat) {
    const gu = this.json((await http.follow('GET', ETICKET + '/api/amsmsg/getUser')).text) || {};
    const userVat = gu.userData && gu.userData.userVat ? String(gu.userData.userVat) : '';
    if (!vat) vat = userVat;
    const url = ETICKET + '/api/amsmsg/filterMessages';
    const all = []; const seen = new Set();
    const p1 = await this.reqPage(http, url, vat, 1, '');
    let pageCount = p1.pageCount || 1;
    for (const m of (p1.entityModels || [])) { if (!seen.has(m.messageId)) { seen.add(m.messageId); all.push(m); } }
    for (let pg = 2; pg <= pageCount; pg++) {
      const pn = await this.reqPage(http, url, vat, pg, '');
      pageCount = pn.pageCount || pageCount;
      for (const m of (pn.entityModels || [])) if (!seen.has(m.messageId)) { seen.add(m.messageId); all.push(m); }
    }
    // compact, human-readable view ανά αίτημα (πλήρη πεδία στο items)
    const summary = all.map(m => ({
      messageId: m.messageId, caseNumber: m.caseNumber,
      submittedDate: m.submittedDate, updatedDate: m.updatedDate,
      status: m.messageStatusText || m.messageStatus,
      thematiki: m.thematicalGroupText || m.processGroupText || m.diadikasiaText || '',
      ypiresia: [m.orgGroupText, m.functionalAreaText, m.protipoTmimaText].filter(Boolean).join(' / '),
      taxee: m.taxeeName, taxeeVat: m.taxeeVat,
      answer: (m.answerText || '').trim(),
    }));
    http.log('[requests] userVat=' + userVat + ' αιτήματα=' + all.length + ' (σελ ' + pageCount + ')');
    return { vat: userVat, list: all, summary };
  },
  // pageNumber is 1-based (like C#); the API's pageIndex is 0-based (== C# pageNumber-1).
  // Body is RAW application/json (== C# TaxExtPostData StringContent "jsonText" -> application/json).
  async reqPage(http, url, vat, pageNumber, caseNumber) {
    const jsonText = '{"pageIndex":' + (pageNumber - 1) + ',"pageSize":10,"startDate":"","endDate":"","representativeRole":0,"transactorVat":"' + vat + '","taxeeVat":"","caseNumber":"' + caseNumber + '","messageStatus":0,"sortBy":"submittedDate","sortByDesc":true}';
    const r = await http.api('POST', url, jsonText);
    if (process.env.EN_DEBUG) http.dump('DBG_filterMessages_p' + pageNumber + '.json', r.text);
    return this.json(r.text) || {};
  },

  // helpers
  txt(s) { return (s || '').replace(/<[^>]+>/g, '').replace(/&nbsp;/g, ' ').replace(/\s+/g, ' ').trim(); },
  json(s) { try { return JSON.parse(s); } catch (e) { return null; } },
  parseDate(s) { const m = (s || '').match(/(\d{1,2})[\/\-.](\d{1,2})[\/\-.](\d{2,4})/); if (!m) return null; return new Date(+((m[3].length === 2 ? '20' : '') + m[3]), +m[2] - 1, +m[1]); },
};
