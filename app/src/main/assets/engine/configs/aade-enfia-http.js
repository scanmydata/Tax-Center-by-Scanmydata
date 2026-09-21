/*
 * configs/aade-enfia-http.js  --  ΕΝΦΙΑ / Ε9 completion check — ΚΑΘΑΡΟ HTTP (όπως το TaxSystem)
 * =============================================================================
 * Το TaxSystem ΔΕΝ χρησιμοποιεί browser για Ε9/ΕΝΦΙΑ — το κάνει με ΚΑΘΑΡΟ HTTP πάνω στην
 * Oracle ADF/Trinidad εφαρμογή ETAK (Hyper.Server.Tax.dll Easy_Aade.GetAdde_E9Page). Εδώ
 * αναπαράγεται πιστά η ADF πλοήγηση για ΕΛΕΓΧΟ ΟΛΟΚΛΗΡΩΣΗΣ (χωρίς λήψη PDF):
 *
 *   1) Είσοδος ETAK: GET {AADE}/etak/  -> _afrLoop ('_afrLoop',\n '...'), _afrWindowId
 *      ('_afrPage',\n '',\n '...'), JSESSIONID-ETAK -> GET του _afrLoop URL (matrix jsessionid).
 *      (fallback: GET /webtax3/etak/faces/index.jspx -> _afrLoop από query=_addParam(...)).
 *   2) GetE9ViewState: form#f1 @action = _ctrlstate, input[name=javax.faces.ViewState] = ViewState.
 *   3) ADF events (POST {AADE}{_ctrlstate}, header Adf-Rich-Message:true, ακολουθεί <redirect url>):
 *      pt1:cbEnter («Είσοδος») -> [pt1:j_id_id8 αν υπάρχει] -> pt1:cbOpenOwnAFM («δικό μου ΑΦΜ»).
 *   4) pt1:yearSelect::content options -> αν λείπει το έτος: NOT_OBLIGATED. αλλιώς επιλογή έτους
 *      (POST pt1:yearSelect valueChange) -> MainPageText.
 *   5) COMPLETION signals στο MainPageText (== hyperserver):
 *      • Ε9 υποβλήθηκε ⇔ div.x3q ... a[href='#'] innerText περιέχει «Αρ. δήλωσης:» (+ αριθμός δήλωσης)
 *      • ΕΝΦΙΑ Εκκαθαριστικό ⇔ 'lnkPrintEkk' (ή element 'pt1:clPrintEkk2022')
 *      • ΕΝΦΙΑ Δόσεις       ⇔ 'lnkPrintEnfiaDoseis'
 *      • ΕΝΦΙΑ Περιουσιακή  ⇔ 'pt1:iterPerStatus:0:cl24'
 *
 * INPUTS: TAXISnet user/pass + Έτος (ένα ή πολλά με κόμμα).
 * OUTPUT: AADE_enfia_e9_<afm>.json (ανά έτος: E9/ENFIA_EKK/ENFIA_DOSEIS/ENFIA_PERIOUSIAKI).
 * ⚠ Πιστή αναπαραγωγή, ΑΛΛΑ δεν έγινε live επαλήθευση εδώ (χωρίς creds· ADF stateful).
 */
'use strict';
const path = require('path');
const fs = require('fs');

const dec = (s) => (s || '').replace(/&amp;/g, '&').replace(/&#(\d+);/g, (_, n) => String.fromCharCode(+n)).replace(/&quot;/g, '"').replace(/&lt;/g, '<').replace(/&gt;/g, '>');
const ACTION = '<m xmlns="http://oracle.com/richClient/comm"><k v="type"><s>action</s></k></m>';
const VALUECHANGE = '<m xmlns="http://oracle.com/richClient/comm"><k v="autoSubmit"><b>1</b></k><k v="suppressMessageShow"><s>true</s></k><k v="type"><s>valueChange</s></k></m>';

// form#f1 action (_ctrlstate) + ViewState  (== GetE9ViewState)
function viewState(html) {
  const form = html.match(/<form\b[^>]*\bid="f1"[^>]*>/i);
  let action = '';
  if (form) action = (form[0].match(/\baction="([^"]*)"/i) || [])[1] || '';
  const vs = (html.match(/name="javax\.faces\.ViewState"[^>]*value="([^"]*)"/i)
          || html.match(/value="([^"]*)"[^>]*name="javax\.faces\.ViewState"/i) || [])[1] || '';
  return { ctrlstate: dec(action), viewState: vs };
}
// year <option> value μέσα στο select id="pt1:yearSelect::content"
function yearOption(html, year) {
  const sel = html.match(/id="pt1:yearSelect::content"[\s\S]*?<\/select>/i);
  const scope = sel ? sel[0] : html;
  for (const m of scope.matchAll(/<option\b([^>]*)>([\s\S]*?)<\/option>/gi)) {
    if (m[2].replace(/<[^>]+>/g, '').trim() === String(year)) {
      return { value: (m[1].match(/value="([^"]*)"/i) || [])[1] || '', selected: /\bselected\b/i.test(m[1]) };
    }
  }
  return null;
}
// Ε9 «Αρ. δήλωσης:» — entity-aware (ETAK ADF partial-response: κείμενο σε &#NNN;). Δουλεύει και στο
// νέο markup (tab «Δηλώσεις Ε9») και στο παλιό (div.x3q). Επιστρέφει τον αριθμό τελευταίας δήλωσης αν υπάρχει.
function e9Declaration(html) {
  const t = dec(html).replace(/<[^>]+>/g, ' ').replace(/\s+/g, ' ');
  const m = t.match(/Αρ\.?\s*δήλωσης:?\s*([0-9][0-9\/\-]*)/);
  if (m) return { submitted: true, declNum: m[1].trim() };
  return { submitted: false };
}

module.exports = {
  id: 'aade-enfia-http',
  title: 'ΕΝΦΙΑ/Ε9 — Έλεγχος ολοκλήρωσης (καθαρό HTTP, ADF/ETAK)',
  portal: 'AADE webtax3/etak (GSIS OAM)',
  subsystem: 'Hyper.Server',
  actions: ['check'],
  inputs: [
    { key: 'user', label: 'TAXISnet username', env: 'AADE_USER' },
    { key: 'pass', label: 'TAXISnet password', env: 'AADE_PASS', hidden: true },
    { key: 'year', label: 'Έτος/η (κόμμα)', env: 'AADE_YEAR' },
    { key: 'docs', label: 'Λήψη PDF; (EKK=εκκαθαριστικό, PERIOUSIAKI=περιουσιακή· κόμμα· κενό=μόνο status)', env: 'AADE_ENFIA_DOCS', optional: true },
  ],
  viewState, yearOption, e9Declaration,   // exported για tests

  async run(http, inp, lib) {
    const years = String(inp.year || '').split(',').map(s => s.trim()).filter(y => /^\d{4}$/.test(y));
    if (!years.length) { http.log('Χρειάζεται έγκυρο Έτος (YYYY)'); return { ok: false, reason: 'NoYear' }; }
    const L = await lib.aadeLogin(http, inp);
    if (!L.ok) { http.log('AADE LOGIN FAILED: ' + L.reason); return { ok: false, reason: L.reason }; }
    const AADE = L.AADE;
    const afm = (lib.stripTags(L.page && L.page.text || '').match(/Α\.?Φ\.?Μ\.?\s*[:\-]?\s*(\d{9})/) || [])[1] || inp.user;

    // POST ενός ADF event -> ακολουθεί <redirect url> αν υπάρχει -> τελικό HTML.
    const adfPost = async (ctrlstate, fields, extra) => {
      const r = await http.follow('POST', new URL(ctrlstate, AADE).toString(), fields, Object.assign({ 'Adf-Rich-Message': 'true' }, extra || {}));
      let text = r.text;
      const m = text.match(/<redirect url="([^"]*)"/i);
      if (m) text = (await http.follow('GET', new URL(dec(m[1]), AADE).toString())).text;
      return text;
    };
    const evt = (id, vs, val, win) => {
      const f = { 'org.apache.myfaces.trinidad.faces.FORM': 'f1', 'javax.faces.ViewState': vs, event: id, ['event.' + id]: (val || ACTION), 'oracle.adf.view.rich.PROCESS': id };
      if (win) f['Adf-Window-Id'] = win;
      return f;
    };

    // ---- 1) Είσοδος ETAK + _afrLoop/_afrWindowId ----
    let source, afrWindowId = '';
    try {
      const entry = await http.follow('GET', AADE + '/etak/');
      let afrLoop = (entry.text.match(/'_afrLoop'\s*,\s*'([^']+)'/) || [])[1] || '';
      afrWindowId = (entry.text.match(/'_afrPage'\s*,\s*''\s*,\s*'([^']+)'/) || [])[1] || '';
      if (afrLoop) {
        const jsid = ((http.jar['www1.aade.gr'] || {})['JSESSIONID-ETAK']) || '';
        const jseg = jsid ? ';JSESSIONID-ETAK=' + jsid : '';
        const url = AADE + '/etak/' + jseg + '?_afrLoop=' + afrLoop + '&_afrWindowMode=2&Adf-Window-Id=' + afrWindowId +
          '&_afrFS=16&_afrMT=screen&_afrMFW=1920&_afrMFH=947&_afrMFDW=1920&_afrMFDH=1080&_afrMFC=8&_afrMFCI=0&_afrMFM=0&_afrMFR=96&_afrMFG=0&_afrMFS=0&_afrMFO=0';
        source = (await http.follow('GET', url)).text;
      } else {
        // fallback index.jspx
        const idx = await http.follow('GET', AADE + '/webtax3/etak/faces/index.jspx');
        afrLoop = (idx.text.match(/query\s*=\s*_addParam\(query,\s*"_afrLoop",\s*"([^"]+)"/) || [])[1] || '';
        if (!afrLoop) throw new Error('_afrLoop not found');
        source = (await http.follow('GET', AADE + '/webtax3/etak/faces/index.jspx?_afrLoop=' + afrLoop + '&_afrWindowMode=0&_afrWindowId=l9ehp7vh7_6')).text;
      }
      http.dump('etak_entry.html', source);
    } catch (e) { http.log('[enfia] ETAK entry error: ' + e.message); return { ok: false, reason: 'EtakEntry:' + e.message }; }

    // ---- 2/3) ViewState + ADF events (Είσοδος -> [j_id_id8] -> δικό μου ΑΦΜ) ----
    try {
      let { ctrlstate, viewState: vs } = viewState(source);
      source = await adfPost(ctrlstate, evt('pt1:cbEnter', vs, ACTION, afrWindowId)); ({ ctrlstate, viewState: vs } = viewState(source));
      if (source.includes('"pt1:j_id_id8"')) { source = await adfPost(ctrlstate, evt('pt1:j_id_id8', vs, ACTION, afrWindowId)); ({ ctrlstate, viewState: vs } = viewState(source)); }
      if (source.includes('"pt1:cbOpenOwnAFM"')) { source = await adfPost(ctrlstate, evt('pt1:cbOpenOwnAFM', vs, ACTION, afrWindowId)); ({ ctrlstate, viewState: vs } = viewState(source)); }
      http.dump('etak_main.html', source);

      const wantDocs = String(inp.docs || '').toUpperCase();
      const wantEkk = /EKK/.test(wantDocs);
      const wantPer = /PERIOUSIAKI|ΠΕΡΙΟΥΣ/.test(wantDocs);
      const files = [];

      const report = { portal: this.portal, afm, checkedAt: new Date().toISOString(), years: [] };
      for (const year of years) {
        const yr = { year, e9: null, enfiaEkk: null, enfiaDoseis: null, enfiaPeriousiaki: null };
        const opt = yearOption(source, year);
        if (!opt) { yr.status = 'NOT_OBLIGATED'; http.log('[enfia] ' + year + ': δεν υπάρχει στη λίστα ετών'); report.years.push(yr); continue; }
        let main = source;
        if (!opt.selected && opt.value) {
          main = await adfPost(ctrlstate, Object.assign(evt('pt1:yearSelect', vs, VALUECHANGE, afrWindowId), { 'pt1:yearSelect': opt.value }), { 'Adf-Ads-Page-Id': '1' });
          const nv = viewState(main); if (nv.ctrlstate) { ctrlstate = nv.ctrlstate; vs = nv.viewState; }
          http.dump('etak_year_' + year + '.html', main);
        }
        const mainDec = dec(main);
        // ΕΝΦΙΑ εκκαθαριστικό: id pt1:clPrintEkk<YYYY> (fixed base στο ETAK) ή label «Εκτύπωση Εκκαθαριστικού»
        yr.enfiaEkk = (/id="pt1:clPrintEkk\d{4}"/.test(main) || /lnkPrintEkk/.test(main) || /Εκτύπωση Εκκαθαριστικού/.test(mainDec)) ? { status: 'AVAILABLE' } : { status: 'NOT_AVAILABLE' };
        // Δόσεις: link/label
        yr.enfiaDoseis = (/lnkPrintEnfiaDoseis|clPrintEnfiaDoseis/.test(main) || /Εκτύπωση Δόσεων|Δόσεις ΕΝΦΙΑ/.test(mainDec)) ? { status: 'AVAILABLE' } : { status: 'NOT_AVAILABLE' };
        // Περιουσιακή Κατάσταση διαθέσιμη (tab/link)
        yr.enfiaPeriousiaki = (/pt1:iterPerStatus:\d+:cl24/.test(main) || /pt1:estatesAndLandsTab/.test(main)) ? { status: 'AVAILABLE' } : { status: 'NOT_AVAILABLE' };

        // Ε9 δηλώσεις: το νέο ETAK τις έχει σε ξεχωριστό tab «Δηλώσεις Ε9» (pt1:e9Tab) — disclosure event.
        let e9 = e9Declaration(main);
        if (!e9.submitted && /pt1:e9Tab/.test(main)) {
          try {
            const E9DISC = '<m xmlns="http://oracle.com/richClient/comm"><k v="type"><s>disclosure</s></k><k v="disclosed"><b>1</b></k></m>';
            const e9page = await adfPost(ctrlstate, evt('pt1:e9Tab', vs, E9DISC, afrWindowId));
            http.dump('etak_e9tab_' + year + '.html', e9page);
            const nv2 = viewState(e9page); if (nv2.ctrlstate) { ctrlstate = nv2.ctrlstate; vs = nv2.viewState; }
            e9 = e9Declaration(e9page);
            e9.tabChecked = true;
          } catch (e) { http.log('[enfia] e9Tab nav error: ' + e.message); }
        }
        yr.e9 = e9.submitted ? { status: 'SUBMITTED', declNum: e9.declNum } : { status: (e9.tabChecked ? 'NOT_SUBMITTED' : 'UNKNOWN_needs_e9Tab') };
        yr.status = 'CHECKED';
        http.log('[enfia] ' + year + ': E9 ' + yr.e9.status + (e9.declNum ? ' (#' + e9.declNum + ')' : '') + ' | Εκκαθ. ' + yr.enfiaEkk.status + ' | Δόσεις ' + yr.enfiaDoseis.status + ' | Περιουσ. ' + yr.enfiaPeriousiaki.status);

        // ---- ΛΗΨΗ PDF (== GetAdde_E9Page PostDataStream, καθαρό HTTP) ----
        // ΕΝΦΙΑ Εκκαθαριστικό: event pt1:clPrintEkk<...> (το «2022» στο id είναι στατικό component-id, ΟΧΙ έτος).
        // Το ΕΤΟΣ καθορίζεται από το pt1:yearSelect που στέλνουμε -> ανά ΕΠΙΛΕΓΜΕΝΟ έτος, PDF του ίδιου έτους.
        if (wantEkk) {
          const ekkId = (main.match(/id="(pt1:clPrintEkk\d{4})"/) || [])[1];
          if (ekkId) {
            const form = Object.assign(evt(ekkId, vs, ACTION, afrWindowId), { 'pt1:yearSelect': opt.value || '' });
            const pdf = await http.postForPdf(new URL(ctrlstate, AADE).toString(), form);
            if (pdf && pdf.length > 500) {
              const fn = 'ENFIA_EKK_' + afm + '_' + year + '.pdf'; fs.writeFileSync(path.join(http.dlDir, fn), pdf);
              files.push(fn); yr.enfiaEkkPdf = { file: fn, year, bytes: pdf.length };
              http.log('[enfia] ✅ εκκαθαριστικό PDF ' + year + ' -> ' + fn + ' (' + pdf.length + ' b)');
            } else http.log('[enfia] εκκαθαριστικό ' + year + ': δεν επεστράφη PDF μέσω HTTP');
          } else http.log('[enfia] εκκαθαριστικό ' + year + ': δεν υπάρχει σύνδεσμος (χωρίς εκκαθάριση για το έτος)');
        }
        // Περιουσιακή Κατάσταση: ακολουθεί το επιλεγμένο έτος -> ανά έτος.
        if (wantPer) {
          const perId = (main.match(/id="(pt1:iterPerStatus:\d+:cl24)"/) || [])[1] || 'pt1:iterPerStatus:0:cl24';
          const form = Object.assign(evt(perId, vs, ACTION, afrWindowId), { 'pt1:yearSelect': opt.value || '' });
          const pdf = await http.postForPdf(new URL(ctrlstate, AADE).toString(), form);
          if (pdf && pdf.length > 500) {
            const fn = 'PERIOUSIAKI_' + afm + '_' + year + '.pdf'; fs.writeFileSync(path.join(http.dlDir, fn), pdf);
            files.push(fn); yr.periousiakiPdf = { file: fn, bytes: pdf.length };
            http.log('[enfia] ✅ περιουσιακή PDF ' + year + ' -> ' + fn + ' (' + pdf.length + ' b)');
          } else http.log('[enfia] περιουσιακή ' + year + ': δεν επεστράφη PDF μέσω HTTP');
        }
        report.years.push(yr);
      }

      const jf = path.join(http.dlDir, 'AADE_enfia_e9_' + afm + '.json');
      fs.writeFileSync(jf, JSON.stringify(report, null, 2));
      http.log('[aade-enfia-http] ✅ saved -> ' + path.basename(jf) + (files.length ? ' (+' + files.length + ' PDF)' : ''));
      return { ok: true, files: [path.basename(jf), ...files], summary: report.years.map(y => ({ year: y.year, status: y.status })) };
    } catch (e) { http.log('[enfia] ADF flow error: ' + e.message); return { ok: false, reason: 'AdfFlow:' + e.message }; }
  },
};
