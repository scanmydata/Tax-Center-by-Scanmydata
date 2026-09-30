/*
 * configs/gemi-login.js  --  ΓΕΜΗ (Γ.Ε.ΜΗ.) login — «ΓΕΜΗ - Αρχική σελίδα»
 * =============================================================================
 * FAITHFUL αναπαραγωγή του Hyper.Server.Tax.dll  WebRequestHelper.LoginGemi
 * (decompiled ~24080 — ΔΕΝ είναι obfuscated). Αντιστοιχεί στην επιλογή μενού
 * TaxSystem «ΓΕΜΗ - Αρχική σελίδα» (services.businessportal.gr).
 *
 * ΡΟΗ (== hyperserver):
 *   GET  https://services.businessportal.gr/                              (cookies)
 *   GET  .../api/public/getGlobalMessage?lang=el                          (200 αλλιώς PageError)
 *   POST .../api/welcome/login?lang=el   {username,password}              (form)
 *   GET  .../api/authentication/checkSession?lang=el -> session JSON      (session.username == user)
 *
 * ΚΩΔΙΚΟΙ: username/password Γ.Ε.ΜΗ. (F_GEMH_*) — ΟΧΙ TAXISnet OAM· είναι ο δικός
 * λογαριασμός businessportal του χρήστη. Το session cookie ζει στο κοινό jar, οπότε
 * μετά το login μπορεί να ακολουθήσει οποιοδήποτε businessportal API call.
 *
 * INPUTS: user/pass Γ.Ε.ΜΗ.
 * OUTPUT: gemi_session.json (τα στοιχεία session/χρήστη όπως τα επιστρέφει το checkSession).
 */
'use strict';
const path = require('path');
const fs = require('fs');

module.exports = {
  id: 'gemi-login',
  title: 'ΓΕΜΗ — Login / Αρχική σελίδα (services.businessportal.gr)',
  portal: 'Γ.Ε.ΜΗ. (services.businessportal.gr)',
  subsystem: 'Hyper.Server (WebRequestHelper.LoginGemi)',
  actions: ['login-check'],
  inputs: [
    { key: 'user', label: 'Username Γ.Ε.ΜΗ.', env: 'GEMI_USER' },
    { key: 'pass', label: 'Password Γ.Ε.ΜΗ.', env: 'GEMI_PASS', hidden: true },
  ],

  async run(http, inp, lib) {
    const L = await lib.gemiLogin(http, { user: inp.user, pass: inp.pass });
    if (!L.ok) {
      http.log('[gemi] LOGIN FAILED: ' + L.reason +
        (L.reason === 'InvalidCredentials' ? ' (λάθος κωδικοί Γ.Ε.ΜΗ.)' :
         L.reason === 'PageError' ? ' (σφάλμα στο Γ.Ε.ΜΗ. — getGlobalMessage != 200)' : ''));
      return { ok: false, reason: L.reason };
    }

    const s = L.session || {};
    const jf = path.join(http.dlDir, 'gemi_session.json');
    fs.writeFileSync(jf, JSON.stringify(s, null, 2));
    http.log('[gemi] ✅ LOGIN OK — username=' + (s.username || inp.user) +
      (s.name ? ' (' + s.name + ')' : '') + ' — session -> ' + path.basename(jf));
    return { ok: true, files: [path.basename(jf)], summary: { host: L.host, username: s.username || inp.user, session: s } };
  },
};
