/*
 * configs/aade-request.js  --  ΑΑΔΕ «Τα Αιτήματά μου»: ΕΝΑ αίτημα, πλήρες, με τα συνημμένα του
 * =============================================================================
 * Το `easynotify` (which=REQUESTS) φέρνει τη ΛΙΣΤΑ των αιτημάτων (filterMessages). Εδώ
 * ανοίγουμε ένα αίτημα όπως το ανοίγει το ίδιο το SPA της πύλης (eticketaade, React):
 *
 *   POST /api/amsmsg/fetch              body = ο messageId ως γυμνός JSON αριθμός
 *        -> πλήρες αίτημα + messageFiles[{ fileId, fileName, fileOrigin, fileSize }]
 *   GET  /api/amsmsg/getMessageComm/<id>   -> η μετέπειτα αλληλογραφία (πίνακας· συχνά κενός)
 *   GET  /api/amsmsg/downloadFile/<fileId> -> το αρχείο (Content-Disposition: attachment)
 *
 * fileOrigin (από το bundle του SPA + ζωντανή επαλήθευση 9 Οκτ 2026):
 *   attachedFile    = ό,τι ΥΠΕΒΑΛΕ ο πολίτης μαζί με το αίτημα
 *   certificateFile = το έγγραφο εκπροσώπησης (όταν υποβάλλει εκπρόσωπος)
 *   internalFile    = τα ΑΠΑΝΤΗΤΙΚΑ έγγραφα της υπηρεσίας (το SPA τα δείχνει μέσα στην απάντηση)
 *
 * Το σχήμα του getMessageComm δεν έχει φανεί με περιεχόμενο (όλα τα δείγματα ήταν []).
 * Γι' αυτό σώζεται ΑΥΤΟΥΣΙΟ, και τα αρχεία του εντοπίζονται γενικά: κάθε αντικείμενο με
 * fileId + fileName, σε οποιοδήποτε βάθος.
 *
 * INPUTS: TAXISnet user/pass + id (messageId· πολλά με κόμμα) (+ files=0 για μόνο κείμενο).
 * OUTPUT: AITIMA_<id>.json  + AITIMA_<id>_<fileId>_<όνομα> ανά συνημμένο.
 */
'use strict';
const path = require('path');
const fs = require('fs');

const AADE = 'https://www1.aade.gr';
const API = AADE + '/saadekef/eticketaade/api/amsmsg';

module.exports = {
  id: 'aade-request',
  title: 'ΑΑΔΕ — Αίτημα (πλήρες περιεχόμενο + συνημμένα υποβολής και απάντησης)',
  portal: 'AADE (GSIS OAM)',
  subsystem: 'eticketaade',
  actions: ['retrieve'],
  inputs: [
    { key: 'user', label: 'TAXISnet username', env: 'AADE_USER' },
    { key: 'pass', label: 'TAXISnet password', env: 'AADE_PASS', hidden: true },
    { key: 'id', label: 'messageId αιτήματος (πολλά με κόμμα)', env: 'REQ_ID' },
    { key: 'files', label: 'Λήψη συνημμένων (1/0, κενό = 1)', env: 'REQ_FILES', optional: true },
  ],

  async run(http, inp, lib) {
    const ids = [...new Set(String(inp.id || '').split(/[\s,;]+/).filter(s => /^\d{1,18}$/.test(s)))];
    if (!ids.length) return { ok: false, reason: 'NoRequestId' };
    const saveFiles = !/^(0|false|όχι|οχι|no)$/i.test(String(inp.files || '').trim());

    const L = await lib.aadeLogin(http, inp);
    if (!L.ok) { http.log('AADE LOGIN FAILED: ' + L.reason); return { ok: false, reason: L.reason }; }
    // == SPA: το getUser προηγείται κάθε άλλης κλήσης (στήνει τη συνεδρία του eticketaade).
    await http.follow('GET', API + '/getUser');

    const written = []; let found = 0;
    for (const id of ids) {
      const r = await http.api('POST', API + '/fetch', id);
      const detail = this.json(r.text);
      if (r.status !== 200 || !detail || typeof detail !== 'object' || Array.isArray(detail)) {
        http.log('[request] ' + id + ' -> ' + r.status + ' (δεν δόθηκε)');
        continue;
      }
      found++;
      let comm = [];
      try { const c = await http.api('GET', API + '/getMessageComm/' + id); const cj = this.json(c.text); if (c.status === 200 && cj != null) comm = cj; }
      catch (e) { http.log('[request] comm ERROR ' + e.message); }

      const files = this.filesOf(detail.messageFiles, null).concat(this.filesOf(comm, 'commFile'));
      for (const f of files) {
        if (!saveFiles) continue;
        try {
          const got = await this.download(http, API + '/downloadFile/' + f.fileId);
          if (!got.buffer || !got.buffer.length) { f.error = 'HTTP ' + got.status; http.log('[request] file ' + f.fileId + ' -> ' + got.status); continue; }
          const name = 'AITIMA_' + id + '_' + f.fileId + '_' + this.san(f.fileName, got.ct);
          fs.writeFileSync(path.join(http.dlDir, name), got.buffer);
          f.savedFile = name; f.bytes = got.buffer.length; written.push(name);
        } catch (e) { f.error = e.message; http.log('[request] file ' + f.fileId + ' ERROR ' + e.message); }
      }

      const jf = 'AITIMA_' + id + '.json';
      fs.writeFileSync(path.join(http.dlDir, jf), JSON.stringify({
        portal: this.portal, retrievedAt: new Date().toISOString(), messageId: id, detail, comm, files,
      }, null, 2));
      written.push(jf);
      http.log('[request] ✅ ' + id + ' -> ' + jf + ' (συνημμένα ' + files.filter(f => f.savedFile).length + '/' + files.length + ')');
    }
    if (!found) return { ok: false, reason: 'RequestNotFound' };
    return { ok: true, files: written };
  },

  // Κάθε αντικείμενο με fileId + fileName, σε οποιοδήποτε βάθος. `origin` = προεπιλογή όταν λείπει.
  filesOf(node, origin, out, depth) {
    out = out || []; depth = depth || 0;
    if (!node || typeof node !== 'object' || depth > 6) return out;
    if (Array.isArray(node)) { for (const x of node) this.filesOf(x, origin, out, depth + 1); return out; }
    if (node.fileId != null && node.fileName != null) {
      if (!out.some(f => String(f.fileId) === String(node.fileId)))
        out.push({ fileId: node.fileId, fileName: String(node.fileName), fileOrigin: node.fileOrigin || origin || '', fileSize: node.fileSize != null ? node.fileSize : null });
      return out;
    }
    for (const k of Object.keys(node)) this.filesOf(node[k], origin, out, depth + 1);
    return out;
  },

  // Οποιοσδήποτε τύπος αρχείου (όχι μόνο PDF, όπως το getDoc): η πύλη δέχεται και εικόνες.
  async download(http, url) {
    let res = await http.once('GET', url, undefined, { Accept: '*/*' });
    let loc = res.headers.get('location'); let cur = url; let hops = 0;
    while (loc && res.status >= 300 && res.status < 400 && hops < 5) { cur = new URL(loc, cur).toString(); res = await http.once('GET', cur, undefined, { Accept: '*/*' }); loc = res.headers.get('location'); hops++; }
    const ct = (res.headers.get('content-type') || '').toLowerCase();
    http.log('  [file] GET ' + url + ' -> ' + res.status + ' ' + ct);
    if (res.status !== 200) return { status: res.status, ct, buffer: null };
    return { status: 200, ct, buffer: Buffer.from(await res.arrayBuffer()) };
  },

  // Όνομα αρχείου ασφαλές για δίσκο· τα ελληνικά μένουν. Η κατάληξη διατηρείται (ή μαντεύεται από τον τύπο).
  san(name, ct) {
    let s = String(name || '').trim().replace(/[\\/:*?"<>|\u0000-\u001f]+/g, '_').replace(/\s+/g, '_').replace(/_+/g, '_').replace(/^[_.]+|[_.]+$/g, '');
    let ext = (s.match(/\.([A-Za-z0-9]{1,5})$/) || [])[1] || '';
    if (ext) s = s.slice(0, -(ext.length + 1));
    else ext = /pdf/.test(ct || '') ? 'pdf' : /jpe?g/.test(ct || '') ? 'jpg' : /png/.test(ct || '') ? 'png' : /gif/.test(ct || '') ? 'gif' : '';
    s = s.slice(0, 60).replace(/^[_.]+|[_.]+$/g, '') || 'arxeio';
    return ext ? s + '.' + ext.toLowerCase() : s;
  },

  json(s) { try { return JSON.parse(s); } catch (e) { return null; } },
};
