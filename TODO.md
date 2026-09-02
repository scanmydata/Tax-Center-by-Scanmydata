# TODO

Κατάσταση ανά φάση. Ενημερώνεται σε **κάθε** commit που κλείνει ή ανοίγει σημείο.

Λεζάντα: `[x]` έτοιμο · `[~]` σε εξέλιξη · `[ ]` εκκρεμεί · `[!]` χρειάζεται
επαλήθευση σε πραγματική συσκευή ή με πραγματικό λογαριασμό.

---

## Φάση 1 — Σκελετός & build pipeline ✅

- [x] Gradle: AGP 8.7.3, Kotlin 2.1.0, compileSdk/targetSdk 35, minSdk 26, Java 17
- [x] Version catalog, χωρίς Gradle wrapper (το CI στήνει Gradle 8.11.1)
- [x] `release.yml`: tests → assembleRelease + bundleRelease → GitHub Release
- [x] Signing από env vars με αυτόματο debug fallback
- [x] **Keystore υπογραφής** (PKCS#12, RSA 4096) και τα τέσσερα GitHub Secrets
- [x] Εικονίδιο εφαρμογής από το λογότυπο (adaptive, όλα τα densities)
- [x] Λογότυπο light + dark (`drawable-nodpi` / `drawable-night-nodpi`)
- [x] Λογότυπο 120px για την οθόνη συγκατάθεσης OAuth
- [x] Monochrome layer στο adaptive icon (themed icons, Android 13+)
- [x] Splash δύο σταδίων: το system splash και μετά η οθόνη κλειδώματος με το
      λογότυπο στα 96dp. Δεν προστέθηκε τρίτη ενδιάμεση οθόνη — θα ήταν
      καθυστέρηση χωρίς περιεχόμενο.

## Φάση 2 — Βάση, κρυπτογράφηση, GDPR θεμέλια ✅

- [x] Room + SQLCipher, χωρίς destructive migration
- [x] `Crypto` AES-256-GCM ανά τιμή, πρόθεμα `enc:1:`, κλειδί στο Keystore
- [x] Σχήμα: clients, credentials, documents, audit_log, consents, sends, run_logs
- [x] Μη καταστροφικό upsert (κενή τιμή δεν σβήνει αποθηκευμένη)
- [x] `Redactor` — κωδικοί/κλειδάριθμοι/κλειδιά/tokens/ΑΜΚΑ έξω από τα logs
- [x] `allowBackup=false` + ρητοί κανόνες data-extraction
- [x] Ξεκλείδωμα με βιομετρικά ή κωδικό συσκευής, auto-lock στο παρασκήνιο
- [x] `FLAG_SECURE` — σε **όλη** την εφαρμογή, όχι μόνο στις οθόνες με κωδικούς
- [x] Πολιτική διατήρησης: αυτόματη διαγραφή PDF μετά από N μήνες (24 εξ ορισμού)
- [x] Εξαγωγή `audit_log` σε CSV (`;` και BOM, για το ελληνικό Excel)
- [x] Εξαγωγή όλων των δεδομένων πελάτη σε ZIP (φορητότητα, άρθρο 20)
- [x] Σκληρή διαγραφή πελάτη με επιβεβαίωση (άρθρο 17)

## Φάση 3 — Εισαγωγή πελατών ✅

- [x] `XlsxReader` — unzip + SAX, χωρίς POI, με κλειστές XML entities
- [x] `Normalize` — τελείες, τόνοι, τελικό σίγμα, ΑΦΜ, mod-11 (συμβουλευτικό)
- [x] `ColumnAliases` — whole-string matching + denylist e-timologio
- [x] `ImportPreview` — ΝΕΟΣ/ΕΝΗΜΕΡΩΣΗ/ΑΜΕΤΑΒΛΗΤΟΣ, συγχώνευση, μασκάρισμα
- [x] **Write path**: `ClientRepository.applyImport` με backup της βάσης πριν
- [x] Οθόνη επιλογής αρχείου (SAF) + οθόνη preview με μασκαρισμένα μυστικά
- [x] Προτροπή διαγραφής του XLSX μετά την εισαγωγή
- [x] Χειροκίνητη καρτέλα πελάτη (ίδια validations, ΑΦΜ κλειδωμένο σε edit)
- [!] Δοκιμή με πραγματικό export σε συσκευή

## Φάση 4 — JS engine on-device ✅

- [x] `shims.js` — fs/path/readline, fetch, Buffer, process, CommonJS require
- [x] `HttpBridge` — OkHttp, χωρίς redirects/cookies, charset αυτούσιο
- [x] `FileBridge` — app-private ρίζα, ελληνικά ονόματα, anti-traversal
- [x] `JsHost` — κρυφό WebView σε about:blank με `blockNetworkLoads`
- [x] `runner.js` — ισοδύναμο του `run-process.js`
- [x] `browser-step.js` + `WebViewBrowserPage` — το BrowserPage contract σε WebView
- [x] `page-helper.js` — selector engine με `:has-text()`, μετρητής ενεργών XHR
- [x] `vendor-engine.mjs --check` στο CI
- [x] Ορατό WebView μέσα στην οθόνη λήψης (απαραίτητο για OTP/CAPTCHA)
- [x] Καθαρό output: τα dumps και το `run.log` δεν γράφονται· μένουν μόνο
      `.pdf` και `.json`. Λύση στη μεριά του `FileBridge`, ώστε τα configs να
      μένουν αυτούσια από τον runner.
- [x] Logging: οι γραμμές του log στον πίνακα `run_logs`, με οθόνη ιστορικού
- [x] **Διόρθωση `expectDownload`**: το `armDownload` έλυνε τη σχετική διαδρομή
      ως προς το CWD της διεργασίας (`/`), όπου δεν υπάρχει δικαίωμα εγγραφής —
      το PDF θα χανόταν σιωπηλά. Τώρα λύνεται κάτω από τον φάκελο της εκτέλεσης.
- [!] **`aade-enfia` end-to-end σε συσκευή** — η μόνη browser διαδρομή
- [!] `expectDownload` σε πραγματική σελίδα ADF: τα κουμπιά στέλνουν POST και το
      `DownloadListener` δεν ενεργοποιείται πάντα. Υπάρχει fallback με OkHttp +
      cookies του `CookieManager`, **αλλά δεν έχει επαληθευτεί.**
- [ ] `renderPdf` (2η σελίδα δόσεων στο `aade-debts`) — υποβαθμίζεται χαριτωμένα

## Φάση 5 — Emails από το Μητρώο ΑΑΔΕ ✅

- [x] `aade-email` config — `getLdapInfo`, προτεραιότητα mail2 → mailemep → mail
- [x] Αποθήκευση στο `emailAade` + κουμπί «Ενημέρωση από ΑΑΔΕ» στην καρτέλα
- [x] Δεύτερη διεύθυνση (`emailManual`) + επιλογή προεπιλεγμένης
- [x] Μαζική ενημέρωση: επιλογή `aade-email` για πολλούς πελάτες στην οθόνη λήψης
- [ ] Preview πριν την αποθήκευση στη μαζική ενημέρωση (σήμερα γράφει απευθείας
      στο `emailAade`· η προηγούμενη τιμή δεν κρατιέται)

## Φάση 6 — Λήψη εντύπων ✅

- [x] `ConfigCatalog` από `configs.json`
- [x] `CredentialMap` — ποιο login θέλει κάθε config (TAXISnet vs ΙΚΑ εργοδότη)
- [x] `ProcessRunner` — σειριακά, audit ανά εκτέλεση, καταγραφή PDF
- [x] Οθόνη επιλογής: πελάτες × έντυπα × έτος
- [x] `FetchController` + `FetchService` (foreground) με ειδοποίηση προόδου
- [x] Επανάληψη μόνο των αποτυχημένων μιας παρτίδας
- [!] Επαλήθευση των 16 pure-HTTP configs με πραγματικό λογαριασμό

> **Γιατί όχι `WorkManager`:** το `aade-enfia` χρειάζεται **ορατό** WebView για
> OTP/CAPTCHA, που δεν παρακάμπτονται. Ένα Worker δεν μπορεί να δείξει σελίδα
> στον χρήστη. Ο `FetchController` ζει στο `AppContainer` και η οθόνη του δίνει
> container όποτε είναι ανοιχτή· το foreground service κρατά τη διεργασία ζωντανή.
> Το τίμημα: η παρτίδα δεν επιβιώνει από θάνατο της διεργασίας.

## Φάση 7 — Αποστολή με Gmail ✅

- [x] `GoogleAuthorizer` — `Identity.getAuthorizationClient`, scopes
      `gmail.send` + `drive.file` + `userinfo.email`
- [x] `GmailSender` — MIME με JavaMail, base64url, `users/me/messages/send`
- [x] Πρότυπα σε κείμενο **και** HTML (πολλοί clients μπλοκάρουν HTML)
- [x] **Αποστολή στοιχείων στον πελάτη** — ΑΦΜ, ΑΜΚΑ, χρήστης TAXISnet·
      συνθηματικό και κλειδάριθμος μόνο με ρητή ενεργοποίηση
- [x] Σήμανση `sentAt` στα έγγραφα + audit + εγγραφή στο ημερολόγιο
- [x] Οθόνη «Έγγραφα»: επιλογή αρχείων ανά πελάτη και αποστολή
- [x] Μαζική αποστολή με **ονομαστική** οθόνη επιβεβαίωσης παραληπτών
- [x] Πάνω από 8 MB, τα έντυπα ενός πελάτη μπαίνουν σε **ένα ZIP** — το Gmail
      κόβει στα ~25 MB και το base64 του MIME φουσκώνει τα δεδομένα κατά ~33%
- [x] Throttle 1,2s + retry με εκθετική αναμονή, μόνο σε παροδικές αποτυχίες
- [ ] Υπογραφή/όνομα γραφείου ανά πρότυπο (σήμερα ένα κοινό)
- [!] Δοκιμή πραγματικής αποστολής

## Φάση 8 — Backup, ενημερώσεις, τεκμηρίωση ✅

- [x] `docs/google-cloud.md` — στήσιμο OAuth client, με τα πραγματικά στοιχεία
- [x] README με αρχιτεκτονική και GDPR
- [x] `docs/privacy-policy.html` και `docs/terms.html`
- [x] GitHub Pages: https://scanmydata.github.io/ScanMyData-Tax-Center/
- [x] `UpdateChecker` από GitHub Releases μέσω `FileProvider`
- [x] Drive backup opt-in, AES-256-GCM με passphrase χρήστη (E2E), με επαναφορά
- [!] Δοκιμή κύκλου αντιγράφου-επαναφοράς σε δεύτερη συσκευή

## Φάση 9 — Ημερολόγιο αποστολών ✅

- [x] Πίνακας `sends`, ξεχωριστός από το `audit_log` (άλλος σκοπός, άλλος
      κύκλος ζωής) — καταγράφει και τις αποτυχημένες αποστολές
- [x] Προβολή μήνα και εβδομάδας, ζώνη Αθηνών
- [x] Λίστα ανά ημέρα: πελάτης, email, τι στάλθηκε, σφάλμα αν απέτυχε
- [x] Φίλτρο ανά πελάτη, ανά είδος αποστολής και «μόνο αποτυχίες»
- [x] Εξαγωγή του ημερολογίου σε CSV
- [x] Επανάληψη αποτυχημένης αποστολής με ένα πάτημα (νέα εγγραφή, όχι
      επιδιόρθωση της παλιάς — το αρχείο του τι συνέβη δεν ξαναγράφεται)

## Φάση 10 — Google Cloud ✅ (2 Σεπτεμβρίου 2026)

- [x] Ενεργοποίηση Gmail API και Google Drive API
- [x] Οθόνη συγκατάθεσης External, με home page / privacy / terms στο Pages
- [x] Authorised domain `scanmydata.github.io`
- [x] Scopes: `gmail.send`, `drive.file`, `userinfo.email` — κανένα restricted
- [x] Android OAuth client με το SHA-1 του keystore
- [x] Publishing status **In production**
- [ ] Λογότυπο στην οθόνη συγκατάθεσης — **δεν** ανέβηκε επίτηδες: θα επέβαλλε
      υποβολή για πιστοποίηση. Το αρχείο περιμένει στο `docs/oauth-logo.png`.

---

## Εικονίδια μενού ✅

Το φύλλο εικονιδίων (`branding/icon-sheet.jpg`, 1400×922) έχει glyphs ~44px και
είναι JPEG. Για 24dp σε xxxhdpi χρειάζονται 96px — η μεγέθυνση θα έβγαζε θολά,
και τα artifacts του JPEG γύρω από τις λεπτές γραμμές θα φαίνονταν.

**Απόφαση:** επανασχεδίαση ως vector drawables (24dp grid, stroke 1.8, round
caps), με το ίδιο γλωσσάρι σχημάτων. Το φύλλο μένει στο repo ως αναφορά.

| Λειτουργία | Σχήμα | Αρχείο |
|---|---|---|
| Πελάτες | handshake (δύο βραχίονες + λαβή) | `ic_menu_clients` |
| Εισαγωγή XLSX | clipboard-list | `ic_menu_import` |
| Λήψη εντύπων | database | `ic_menu_fetch` |
| Έγγραφα | window/tabs | `ic_menu_documents` |
| Ημερολόγιο | calendar | `ic_menu_calendar` |
| Αποστολή | envelope | `ic_menu_send` |
| Εξουσιοδοτήσεις | badge-check | `ic_menu_consents` |
| Ιστορικό / audit | clock | `ic_menu_logs` |
| Οφειλές | euro-circle | `ic_menu_debts` |
| Στατιστικά | trend-up | `ic_menu_stats` |
| Αναζήτηση | magnifier | `ic_menu_search` |
| Ασφάλεια / GDPR | shield | `ic_menu_security` |
| Ρυθμίσεις | gear | `ic_menu_settings` |

- [x] Σχεδίαση των vector drawables
- [x] Αντικατάσταση των προσωρινών Material icons στο `Destinations.kt`

Τα εικονίδια που δεν έχουν ακόμη θέση στο μενού (αποστολή, εξουσιοδοτήσεις,
οφειλές, στατιστικά, αναζήτηση, ασφάλεια) υπάρχουν έτοιμα· το
`shrinkResources` τα κόβει από το APK μέχρι να χρησιμοποιηθούν.

---

## Γνωστά όρια

* **2FA / OTP / CAPTCHA δεν παρακάμπτονται.** Όπου το TAXISnet τα ζητά, η σελίδα
  εμφανίζεται στον χρήστη μέσα στην οθόνη λήψης. Σχεδιαστική επιλογή, όχι έλλειψη.
* **GSIS `OAM-6`** — πολλές ταυτόχρονες συνεδρίες κλειδώνουν τον λογαριασμό.
  Γι' αυτό ο `ProcessRunner` τρέχει αυστηρά σειριακά, και γι' αυτό η μεμονωμένη
  αναζήτηση email αρνείται όταν τρέχει παρτίδα.
* **Η παρτίδα λήψης δεν επιβιώνει από θάνατο διεργασίας.** Το foreground service
  το κάνει απίθανο, όχι αδύνατο.
* **Τα debug builds δεν συνδέονται στο Google**: άλλο package (`.debug`) και
  άλλη υπογραφή από αυτά που δηλώθηκαν στον OAuth client.
* **ΕΝΦΙΑ δόσεις & ειδοποιητήριο** δεν υλοποιούνται· η υπηρεσία έχει κλείσει.
* **Φορολογική & ασφαλιστική ενημερότητα** δεν υπάρχουν στον runner.
* Οι πύλες αλλάζουν (ήδη αποσύρθηκαν ETAK/eDebtor/idika). Τα configs είναι
  assets, οπότε μπορούν να ανανεωθούν χωρίς νέο APK.
