/*
 * page-helper.js — ο selector engine μέσα στη σελίδα-στόχο.
 * =============================================================================
 * Εγχέεται στο **ορατό** WebView (σελίδες ΑΑΔΕ/ΕΦΚΑ) και ΜΟΝΟ εκεί. Αυτό το
 * WebView δεν έχει κανένα `@JavascriptInterface`: το Kotlin παίρνει αποτελέσματα
 * αποκλειστικά από το callback του `evaluateJavascript`, ώστε μια σελίδα τρίτου
 * να μην μπορεί ποτέ να καλέσει native κώδικα.
 *
 * Ο λόγος ύπαρξης: τα configs γράφτηκαν για Playwright και χρησιμοποιούν το
 * ψευδο-επιλογέα `:has-text("…")`, που το `querySelectorAll` δεν ξέρει:
 *
 *     a[href="https://www1.aade.gr/etak/"]
 *     a:has-text("Είσοδος στην εφαρμογή")
 *     button:has-text("Συνδεση"), button:has-text("ΣΥΝΔΕΣΗ")
 *
 * Υποστηρίζονται: λίστες με κόμμα, `:has-text("…")` σε οποιοδήποτε μέρος, και
 * ids με `:` μέσα τους (Oracle ADF: `pt1:cbEnter`) — αυτά περνούν αυτούσια στο
 * CSS, γιατί τα configs τα γράφουν ήδη ως `[id="pt1:cbEnter"]`.
 *
 * Κάθε συνάρτηση επιστρέφει JSON-serialisable τιμή· το Kotlin κάνει JSON.parse.
 */
'use strict';
(function () {
  if (window.__page) return;

  // ------------------------------------------------------------- selectors
  /** Σπάει «a, b:has-text("x")» σε μέρη, σεβόμενο κόμματα μέσα σε εισαγωγικά/παρενθέσεις. */
  function splitTopLevel(sel) {
    var parts = [], depth = 0, quote = null, cur = '';
    for (var i = 0; i < sel.length; i++) {
      var c = sel.charAt(i);
      if (quote) {
        if (c === quote && sel.charAt(i - 1) !== '\\') quote = null;
      } else if (c === '"' || c === "'") {
        quote = c;
      } else if (c === '(' || c === '[') {
        depth++;
      } else if (c === ')' || c === ']') {
        depth--;
      } else if (c === ',' && depth === 0) {
        parts.push(cur); cur = ''; continue;
      }
      cur += c;
    }
    if (cur.trim()) parts.push(cur);
    return parts.map(function (p) { return p.trim(); }).filter(Boolean);
  }

  var HAS_TEXT = /:has-text\(\s*(['"])([\s\S]*?)\1\s*\)/;

  /** Χωρίζει ένα μέρος σε καθαρό CSS και σε ζητούμενα κείμενα. */
  function parsePart(part) {
    var texts = [];
    var css = part;
    var m;
    // Μπορεί να υπάρχουν πολλά :has-text() στο ίδιο μέρος.
    while ((m = css.match(HAS_TEXT))) {
      texts.push(m[2]);
      css = css.replace(HAS_TEXT, '');
    }
    return { css: css.trim() || '*', texts: texts };
  }

  function queryPart(part) {
    var parsed = parsePart(part);
    var texts = parsed.texts;
    var css = parsed.css;
    var found;
    try {
      found = Array.prototype.slice.call(document.querySelectorAll(css));
    } catch (e) {
      return [];
    }
    if (!texts.length) return found;
    return found.filter(function (el) {
      var t = (el.innerText || el.textContent || '').replace(/\s+/g, ' ').trim();
      return texts.every(function (needle) { return t.indexOf(needle) !== -1; });
    });
  }

  function q(sel) {
    var out = [];
    splitTopLevel(String(sel)).forEach(function (part) {
      queryPart(part).forEach(function (el) { if (out.indexOf(el) === -1) out.push(el); });
    });
    return out;
  }

  function first(sel) { return q(sel)[0] || null; }

  // ------------------------------------------------------------- ενέργειες
  function fire(el, type, opts) {
    var ev;
    try {
      ev = new Event(type, Object.assign({ bubbles: true, cancelable: true }, opts || {}));
    } catch (e) {
      ev = document.createEvent('HTMLEvents');
      ev.initEvent(type, true, true);
    }
    el.dispatchEvent(ev);
  }

  window.__page = {
    count: function (sel) { return q(sel).length; },

    text: function (sel) {
      var el = first(sel);
      if (!el) throw new Error('δεν βρέθηκε: ' + sel);
      return (el.innerText || el.textContent || '').trim();
    },

    attr: function (sel, name) {
      var el = first(sel);
      return el ? el.getAttribute(name) : null;
    },

    /**
     * Συμπληρώνει πεδίο. Δεν αρκεί `el.value = x`: το Oracle ADF και το
     * PrimeFaces ακούν input/change και χωρίς αυτά η τιμή δεν «κολλάει».
     */
    fill: function (sel, value) {
      var el = first(sel);
      if (!el) throw new Error('δεν βρέθηκε: ' + sel);
      el.focus();
      if (el.isContentEditable) {
        el.textContent = value;
      } else {
        // Ο native setter παρακάμπτει τους React/Angular wrappers.
        var proto = el instanceof HTMLTextAreaElement ? HTMLTextAreaElement.prototype : HTMLInputElement.prototype;
        var setter = Object.getOwnPropertyDescriptor(proto, 'value');
        if (setter && setter.set) setter.set.call(el, value); else el.value = value;
      }
      fire(el, 'input');
      fire(el, 'change');
      return true;
    },

    click: function (sel) {
      var el = first(sel);
      if (!el) throw new Error('δεν βρέθηκε: ' + sel);
      el.scrollIntoView({ block: 'center' });
      if (typeof el.click === 'function') el.click();
      else fire(el, 'click');
      return true;
    },

    options: function (sel) {
      var el = first(sel);
      if (!el) return [];
      return Array.prototype.slice.call(el.querySelectorAll('option')).map(function (o) {
        return {
          value: o.getAttribute('value') || '',
          title: o.getAttribute('title') || '',
          text: (o.innerText || o.textContent || '').trim(),
          selected: o.selected === true || o.getAttribute('selected') !== null,
        };
      });
    },

    selectByValue: function (sel, value) {
      var el = first(sel);
      if (!el) throw new Error('δεν βρέθηκε: ' + sel);
      el.value = value;
      fire(el, 'input');
      fire(el, 'change');
      return true;
    },

    selectByLabel: function (sel, label) {
      var el = first(sel);
      if (!el) throw new Error('δεν βρέθηκε: ' + sel);
      var opts = Array.prototype.slice.call(el.querySelectorAll('option'));
      for (var i = 0; i < opts.length; i++) {
        var t = (opts[i].innerText || opts[i].textContent || '').trim();
        if (t === label || opts[i].getAttribute('title') === label) {
          el.selectedIndex = i;
          fire(el, 'input');
          fire(el, 'change');
          return true;
        }
      }
      throw new Error('δεν βρέθηκε επιλογή: ' + label);
    },

    content: function () { return document.documentElement.outerHTML; },
    title: function () { return document.title || ''; },
    href: function () { return location.href; },

    /**
     * Εκτεθειμένο για tests: πώς αναλύθηκε ένας επιλογέας. Δεν αγγίζει DOM,
     * οπότε ελέγχεται σε σκέτο JS χωρίς browser.
     */
    __parse: function (sel) { return splitTopLevel(String(sel)).map(parsePart); },

    /** Είναι η σελίδα «ήσυχη»; Προσέγγιση του networkidle του Playwright. */
    quiet: function () {
      return document.readyState === 'complete' &&
        (!window.__pageActiveRequests || window.__pageActiveRequests <= 0);
    },
  };

  // --------------------------------------------- μετρητής ενεργών αιτημάτων
  // Το WebView δεν έχει 'networkidle'. Μετράμε XHR/fetch μόνοι μας, ώστε το
  // waitLoad να μπορεί να περιμένει ησυχία αντί για σταθερό χρόνο.
  window.__pageActiveRequests = 0;

  var origOpen = XMLHttpRequest.prototype.open;
  var origSend = XMLHttpRequest.prototype.send;
  XMLHttpRequest.prototype.open = function () {
    this.__tracked = true;
    return origOpen.apply(this, arguments);
  };
  XMLHttpRequest.prototype.send = function () {
    if (this.__tracked) {
      window.__pageActiveRequests++;
      var done = function () { window.__pageActiveRequests = Math.max(0, window.__pageActiveRequests - 1); };
      this.addEventListener('loadend', done);
    }
    return origSend.apply(this, arguments);
  };

  if (window.fetch) {
    var origFetch = window.fetch;
    window.fetch = function () {
      window.__pageActiveRequests++;
      return origFetch.apply(this, arguments).finally(function () {
        window.__pageActiveRequests = Math.max(0, window.__pageActiveRequests - 1);
      });
    };
  }
})();
