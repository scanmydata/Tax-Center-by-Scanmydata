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
    { key: 'which', label: 'Ποια (AADE / PROPERTY / REQUESTS — κενό = όλα)', env: 'EN_WHICH', optional: true },
    { key: 'vat', label: 'ΑΦΜ (κενό = αυτόματα)', env: 'AADE_VAT', optional: true },
    { key: 'since', label: 'Μηνύματα ΑΑΔΕ από ημ/νία (dd/mm/yyyy, κενό = όλα)', env: 'EN_SINCE', optional: true },
    { key: 'files', label: 'Λήψη συνημμένων ΑΑΔΕ (1/0)', env: 'EN_FILES', optional: true },
  ],

  async run(http, inp, lib) {
    const L = await lib.aadeLogin(http, inp);
    if (!L.ok) { http.log('AADE LOGIN FAILED: ' + L.reason); return { ok: false, reason: L.reason }; }
    const want = (inp.which || '').trim().toUpperCase();
    const doAade = !want || /AADE|ΜΗΝΥΜ|MSG/.test(want);
    const doProp = !want || /PROP|ΠΕΡΙΟΥΣ/.test(want);
    const doReq = !want || /REQ|ΑΙΤΗΜ/.test(want);
    let vat = (inp.vat || '').trim()
      || (lib.stripTags(L.page && L.page.text || '').match(/Α\.?Φ\.?Μ\.?\s*[:\-]?\s*(\d{9})/) || [])[1] || '';
    const out = { portal: this.portal, vat, retrievedAt: new Date().toISOString() };
    const files = [];

    if (doAade) { try { out.aadeMessages = await this.getAadeMessages(http, lib, inp, files); } catch (e) { out.aadeMessages = 'ERR:' + e.message; http.log('[aade-msg] ERROR ' + e.message); } }
    if (doProp) { try { out.myProperty = await this.getMyProperty(http, lib, vat); } catch (e) { out.myProperty = 'ERR:' + e.message; http.log('[property] ERROR ' + e.message); } }
    if (doReq) { try { const r = await this.getAadeRequests(http, lib, vat); out.aadeRequests = r.list; if (!vat && r.vat) out.vat = vat = r.vat; } catch (e) { out.aadeRequests = 'ERR:' + e.message; http.log('[requests] ERROR ' + e.message); } }

    const jf = path.join(http.dlDir, 'EASYNOTIFY_' + (vat || inp.user) + '.json');
    fs.writeFileSync(jf, JSON.stringify(out, null, 2));
    http.log('[easynotify] ✅ saved -> ' + path.basename(jf)
      + ' (aade=' + (out.aadeMessages && out.aadeMessages.length != null ? out.aadeMessages.length : '-')
      + ' property=' + (out.myProperty && out.myProperty.messages ? out.myProperty.messages.length : '-')
      + ' requests=' + (Array.isArray(out.aadeRequests) ? out.aadeRequests.length : '-') + ')');
    return { ok: true, files: [path.basename(jf), ...files] };
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
    http.log('[requests] userVat=' + userVat + ' αιτήματα=' + all.length + ' (σελ ' + pageCount + ')');
    return { vat: userVat, list: all };
  },
  async reqPage(http, url, vat, pageIndex, caseNumber) {
    const jsonText = '{"pageIndex":' + pageIndex + ',"pageSize":10,"startDate":"","endDate":"","representativeRole":0,"transactorVat":"' + vat + '","taxeeVat":"","caseNumber":"' + caseNumber + '","messageStatus":0,"sortBy":"submittedDate","sortByDesc":true}';
    const r = await http.follow('POST', url, { jsonText });
    if (process.env.EN_DEBUG) http.dump('DBG_filterMessages_p' + pageIndex + '.json', r.text);
    return this.json(r.text) || {};
  },

  // helpers
  txt(s) { return (s || '').replace(/<[^>]+>/g, '').replace(/&nbsp;/g, ' ').replace(/\s+/g, ' ').trim(); },
  json(s) { try { return JSON.parse(s); } catch (e) { return null; } },
  parseDate(s) { const m = (s || '').match(/(\d{1,2})[\/\-.](\d{1,2})[\/\-.](\d{2,4})/); if (!m) return null; return new Date(+((m[3].length === 2 ? '20' : '') + m[3]), +m[2] - 1, +m[1]); },
};
