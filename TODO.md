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
- [x] Εικονίδιο εφαρμογής από το λογότυπο (adaptive, όλα τα densities)
- [x] Λογότυπο light + dark (`drawable-nodpi` / `drawable-night-nodpi`)
- [x] Λογότυπο 120px για την οθόνη συγκατάθεσης OAuth
- [ ] Splash δύο σταδίων με το λογότυπο (σήμερα δείχνει το `ic_launcher_foreground`)
- [ ] Monochrome layer στο adaptive icon (themed icons, Android 13+)

## Φάση 2 — Βάση, κρυπτογράφηση, GDPR θεμέλια ✅ (πυρήνας)

- [x] Room + SQLCipher, χωρίς destructive migration
- [x] `Crypto` AES-256-GCM ανά τιμή, πρόθεμα `enc:1:`, κλειδί στο Keystore
- [x] Σχήμα: clients, credentials, documents, audit_log, consents
- [x] Μη καταστροφικό upsert (κενή τιμή δεν σβήνει αποθηκευμένη)
- [x] `Redactor` — κωδικοί/κλειδάριθμοι/κλειδιά/tokens/ΑΜΚΑ έξω από τα logs
- [x] `allowBackup=false` + ρητοί κανόνες data-extraction
- [ ] Ξεκλείδωμα με `BiometricPrompt` ή PIN, auto-lock στο background
- [ ] `FLAG_SECURE` στις οθόνες με κωδικούς
- [ ] Πολιτική διατήρησης: αυτόματη διαγραφή PDF μετά από N μήνες
- [ ] Εξαγωγή `audit_log` σε CSV
- [ ] Εξαγωγή όλων των δεδομένων πελάτη σε ZIP (φορητότητα, άρθρο 20)
- [ ] Σκληρή διαγραφή πελάτη με επιβεβαίωση (άρθρο 17)

## Φάση 3 — Εισαγωγή πελατών ✅ (πυρήνας)

- [x] `XlsxReader` — unzip + SAX, χωρίς POI, με κλειστές XML entities
- [x] `Normalize` — τελείες, τόνοι, τελικό σίγμα, ΑΦΜ, mod-11 (συμβουλευτικό)
- [x] `ColumnAliases` — whole-string matching + denylist e-timologio
- [x] `ImportPreview` — ΝΕΟΣ/ΕΝΗΜΕΡΩΣΗ/ΑΜΕΤΑΒΛΗΤΟΣ, συγχώνευση, μασκάρισμα
- [ ] **Write path**: `ClientRepository.applyImport(preview)` με backup πριν
- [ ] Οθόνη επιλογής αρχείου (SAF) + οθόνη preview
- [ ] Προτροπή διαγραφής του XLSX μετά την εισαγωγή
- [ ] Χειροκίνητη καρτέλα πελάτη (ίδια validations, ΑΦΜ κλειδωμένο σε edit)

## Φάση 4 — JS engine on-device ✅ (πυρήνας)

- [x] `shims.js` — fs/path/readline, fetch, Buffer, process, CommonJS require
- [x] `HttpBridge` — OkHttp, χωρίς redirects/cookies, charset αυτούσιο
- [x] `FileBridge` — app-private ρίζα, ελληνικά ονόματα, anti-traversal
- [x] `JsHost` — κρυφό WebView σε about:blank με `blockNetworkLoads`
- [x] `runner.js` — ισοδύναμο του `run-process.js`
- [x] `browser-step.js` + `WebViewBrowserPage` — το BrowserPage contract σε WebView
- [x] `page-helper.js` — selector engine με `:has-text()`, μετρητής ενεργών XHR
- [x] `vendor-engine.mjs --check` στο CI
- [ ] Οθόνη που δείχνει το ορατό WebView (απαραίτητη για OTP/CAPTCHA)
- [!] **`aade-enfia` end-to-end σε συσκευή** — η μόνη browser διαδρομή
- [!] `expectDownload`: τα κουμπιά ADF στέλνουν POST και το `DownloadListener`
      δεν ενεργοποιείται πάντα. Fallback: επανάληψη με OkHttp + cookies του
      `CookieManager`. **Δεν έχει επαληθευτεί σε πραγματική σελίδα.**
- [ ] `renderPdf` (2η σελίδα δόσεων στο `aade-debts`) — υποβαθμίζεται χαριτωμένα

## Φάση 5 — Emails από το Μητρώο ΑΑΔΕ ✅ (πυρήνας)

- [x] `aade-email` config — `getLdapInfo`, προτεραιότητα mail2 → mailemep → mail
- [ ] Αποθήκευση στο `emailAade` + UI «Ενημέρωση email από ΑΑΔΕ»
- [ ] Δεύτερη διεύθυνση (`emailManual`) + επιλογή προεπιλεγμένης
- [ ] Μαζική ενημέρωση email για επιλεγμένους πελάτες, με preview

## Φάση 6 — Λήψη εντύπων ✅ (πυρήνας)

- [x] `ConfigCatalog` από `configs.json`
- [x] `CredentialMap` — ποιο login θέλει κάθε config (TAXISnet vs ΙΚΑ εργοδότη)
- [x] `ProcessRunner` — σειριακά, audit ανά εκτέλεση, καταγραφή PDF
- [ ] `WorkManager` worker με foreground notification και πρόοδο
- [ ] Οθόνη επιλογής: πελάτες × έντυπα × έτος
- [ ] Επανάληψη μόνο των αποτυχημένων μιας παρτίδας
- [!] Επαλήθευση των 16 pure-HTTP configs με πραγματικό λογαριασμό

## Φάση 7 — Αποστολή με Gmail

- [ ] `GoogleAuthorizer` — `Identity.getAuthorizationClient`, scopes
      `gmail.send` + `drive.file` + `userinfo.email`
- [ ] `GmailSender` — MIME με JavaMail, base64url, `users/me/messages/send`
- [ ] Πρότυπα email με placeholders, επεξεργάσιμα στις Ρυθμίσεις
- [ ] Ένα έντυπο · όλα ενός πελάτη ως ZIP · μαζικά για επιλεγμένους
- [ ] Οθόνη επιβεβαίωσης με λίστα παραληπτών πριν από μαζική αποστολή
- [ ] Throttle + retry για τα όρια του Gmail
- [ ] Σήμανση `sentAt` στα έγγραφα + audit

## Φάση 8 — Backup, ενημερώσεις, τεκμηρίωση

- [x] `docs/google-cloud.md` — στήσιμο OAuth client
- [x] README με αρχιτεκτονική και GDPR
- [ ] Drive backup opt-in, AES-256-GCM με passphrase χρήστη (E2E)
- [ ] `UpdateChecker` από GitHub Releases μέσω `FileProvider`
- [ ] `docs/privacy-policy.html` — απαιτείται από την οθόνη συγκατάθεσης
- [ ] GitHub Pages για το `docs/`

---

## Εικονίδια μενού

Το φύλλο εικονιδίων (`branding/icon-sheet.jpg`, 1400×922) έχει glyphs ~44px. Για
24dp σε xxxhdpi χρειάζονται 96px — η μεγέθυνση θα έβγαζε θολά εικονίδια, και το
JPEG έχει ήδη artifacts γύρω από τις λεπτές γραμμές.

**Απόφαση:** επανασχεδίαση ως vector drawables (24dp grid, stroke 2, round caps),
με το ίδιο γλωσσάρι σχημάτων. Το φύλλο μένει στο repo ως αναφορά.

| Λειτουργία | Σχήμα από το φύλλο |
|---|---|
| Πελάτες | handshake |
| Εισαγωγή XLSX | clipboard-list |
| Λήψη εντύπων | database |
| Έγγραφα | window/tabs |
| Αποστολή | envelope |
| Εξουσιοδοτήσεις | badge-check σε χέρι |
| Ιστορικό / audit | clock |
| Οφειλές | dollar-circle |
| Στατιστικά | trend-up |
| Αναζήτηση | magnifier |
| Ασφάλεια / GDPR | shield |
| Ρυθμίσεις | gear |

- [ ] Σχεδίαση των 12 vector drawables
- [ ] Αντιστοίχιση σε προορισμούς πλοήγησης

---

## Γνωστά όρια

* **2FA / OTP / CAPTCHA δεν παρακάμπτονται.** Όπου το TAXISnet τα ζητά, η σελίδα
  εμφανίζεται στον χρήστη. Αυτό είναι σχεδιαστική επιλογή, όχι έλλειψη.
* **GSIS `OAM-6`** — πολλές ταυτόχρονες συνεδρίες κλειδώνουν τον λογαριασμό.
  Γι' αυτό ο `ProcessRunner` τρέχει αυστηρά σειριακά.
* **ΕΝΦΙΑ δόσεις & ειδοποιητήριο** δεν υλοποιούνται· η υπηρεσία έχει κλείσει.
* **Φορολογική & ασφαλιστική ενημερότητα** δεν υπάρχουν στον runner.
* Οι πύλες αλλάζουν (ήδη αποσύρθηκαν ETAK/eDebtor/idika). Τα configs είναι
  assets, οπότε μπορούν να ανανεωθούν χωρίς νέο APK.
