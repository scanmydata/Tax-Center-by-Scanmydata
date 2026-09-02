/*
 * runner.js — το ισοδύναμο του `run-process.js` του runner, για Android.
 * =============================================================================
 * Ο desktop runner ζητά inputs με prompt και τυπώνει στο stdout. Εδώ τα inputs
 * έρχονται από το UI και το αποτέλεσμα γυρίζει στο Kotlin.
 *
 * Η ροή είναι ίδια με του `run-process.js`:
 *   const http = new lib.HyperHttp(outDir);
 *   const res  = await cfg.run(http, inputs, lib);
 *
 * Τα σφάλματα ΔΕΝ πετιούνται προς τα έξω: επιστρέφονται ως τιμή, όπως κάνει και
 * ο runner, ώστε μια αποτυχία σε έναν πελάτη να μη σταματά μια μαζική λήψη.
 */
'use strict';

globalThis.__runConfig = function (callId, configId, inputsJson, outDir) {
  (async function () {
    var started = Date.now();
    var http = null;
    try {
      var lib = require('hyper-http');
      var cfg = require(configId);

      if (!cfg || typeof cfg.run !== 'function') {
        throw new Error('Το config "' + configId + '" δεν εξάγει run()');
      }

      http = new lib.HyperHttp(outDir);
      http.log('=== process: ' + (cfg.id || configId) + ' — ' + (cfg.title || '') + ' ===');

      var inputs = JSON.parse(inputsJson || '{}');
      // Ο runner λογαριάζει τα κενά optional inputs ως ''· κρατάμε το ίδιο.
      (cfg.inputs || []).forEach(function (spec) {
        if (inputs[spec.key] === undefined || inputs[spec.key] === null) inputs[spec.key] = '';
      });

      var res = await cfg.run(http, inputs, lib);
      var okFlag = !!(res && res.ok);
      http.log(okFlag
        ? 'RESULT: ok' + (res.files && res.files.length ? ' (' + res.files.length + ' file(s))' : '')
        : 'RESULT: failed' + (res && res.reason ? ' (' + res.reason + ')' : ''));

      __bridge.finish(callId, JSON.stringify({
        ok: okFlag,
        reason: (res && res.reason) || '',
        files: (res && res.files) || [],
        out: (res && res.out) || null,
        durationMs: Date.now() - started
      }));
    } catch (e) {
      var msg = (e && e.stack) ? e.stack : String(e);
      try { if (http) http.log('ERROR: ' + msg); } catch (e2) { /* το log δεν πρέπει να κρύψει το σφάλμα */ }
      __bridge.finish(callId, JSON.stringify({
        ok: false,
        reason: (e && e.message) ? e.message : String(e),
        error: msg,
        files: [],
        durationMs: Date.now() - started
      }));
    }
  })();
};

/** Επιστρέφει τα μεταδεδομένα ενός config χωρίς να το τρέξει. */
globalThis.__describeConfig = function (configId) {
  try {
    var cfg = require(configId);
    return JSON.stringify({
      id: cfg.id || configId,
      title: cfg.title || '',
      portal: cfg.portal || '',
      needsBrowser: !!cfg.needsBrowser,
      inputs: cfg.inputs || []
    });
  } catch (e) {
    return JSON.stringify({ id: configId, error: String(e && e.message ? e.message : e) });
  }
};
