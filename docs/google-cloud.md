# Στήσιμο OAuth client στο Google Cloud

Οδηγός για τη σύνδεση της εφαρμογής με τον λογαριασμό Google του γραφείου, ώστε
να στέλνει τα φορολογικά έντυπα με **Gmail API** και (προαιρετικά) να ανεβάζει
κρυπτογραφημένο αντίγραφο στο **Drive**.

---

## Πριν ξεκινήσεις: δεν χρειάζεται client secret

Στα Android OAuth clients **δεν υπάρχει client secret**, και **δεν μπαίνει
client id στον κώδικα**. Η Google ταυτοποιεί την εφαρμογή από το ζεύγος:

```
package name  +  SHA-1 της υπογραφής του APK
```

Γι' αυτό:

* Τα `client_id_center` και `client_secret_tax_center` **δεν χρειάζονται** εδώ.
  Άφησέ τα στο Infisical — θα χρειαστούν μόνο αν αργότερα φτιάξουμε server
  component (π.χ. web dashboard), που θέλει **Web** OAuth client.
* Η ενσωμάτωση client secret σε APK είναι αντι-πρότυπο: το APK αποσυμπιέζεται σε
  δευτερόλεπτα και το secret διαρρέει. Η Google θεωρεί τις εφαρμογές κινητών
  «public clients» ακριβώς γι' αυτόν τον λόγο.

**Συνέπεια:** το keystore γίνεται κρίσιμο. Αν χαθεί, δεν μπορείς ούτε να
ενημερώσεις την εφαρμογή (αλλάζει η υπογραφή) ούτε να συνδεθείς στο Google
(αλλάζει το SHA-1). Κράτα το εκτός repo, με αντίγραφο.

---

## 1. Δημιουργία keystore (μία φορά)

Πρώτα το keystore, γιατί από αυτό βγαίνει το SHA-1 που ζητά η Google.

```bash
keytool -genkeypair -v -keystore release.jks -keyalg RSA -keysize 2048 \
  -validity 10000 -alias taxcenter
```

Κράτησέ το **έξω** από τον φάκελο του repo, π.χ. `Documents/TaxCenter-keystore/`.

Το SHA-1:

```bash
keytool -list -v -keystore release.jks -alias taxcenter | grep SHA1
```

Θα δώσει κάτι σαν `SHA1: AB:CD:...:12`. Κράτα το.

---

## 2. GitHub Secrets

Χωρίς αυτά το CI βγάζει **debug-signed** APK και το γράφει στις σημειώσεις της
έκδοσης. Λειτουργεί για δοκιμή, αλλά το Google Sign-In θέλει τη σωστή υπογραφή.

```bash
base64 -w0 release.jks > release.jks.b64
gh secret set KEYSTORE_BASE64 < release.jks.b64 --repo scanmydata/ScanMyData-Tax-Center
gh secret set KEYSTORE_PASSWORD --repo scanmydata/ScanMyData-Tax-Center
gh secret set KEY_ALIAS --repo scanmydata/ScanMyData-Tax-Center
gh secret set KEY_PASSWORD --repo scanmydata/ScanMyData-Tax-Center
```

Το `KEY_ALIAS` είναι `taxcenter`. Σβήσε το `release.jks.b64` μετά.

---

## 3. Google Cloud project

1. [console.cloud.google.com](https://console.cloud.google.com) → **New Project**
   → όνομα `scanmydata-tax-center`.
2. **APIs & Services → Library** → ενεργοποίησε:
   * **Gmail API**
   * **Google Drive API**

---

## 4. OAuth consent screen

**APIs & Services → OAuth consent screen**, τύπος **External**.

| Πεδίο | Τιμή |
|---|---|
| App name | `ScanMyData Tax Center` |
| User support email | ο λογαριασμός σου |
| App logo | `docs/oauth-logo.png` (120×120, παράγεται από το `tools/make-icons.ps1`) |
| Application home page | `https://scanmydata.github.io/ScanMyData-Tax-Center/` |
| Privacy policy link | `https://scanmydata.github.io/ScanMyData-Tax-Center/privacy-policy.html` |
| Authorised domain | `scanmydata.github.io` |
| Developer contact | ο λογαριασμός σου |

### Scopes

**Add or remove scopes** → πρόσθεσε **μόνο** αυτά τα τρία:

| Scope | Τι κάνει | Κατηγορία |
|---|---|---|
| `.../auth/gmail.send` | αποστολή email — **μόνο** αποστολή, καμία ανάγνωση | sensitive |
| `.../auth/drive.file` | πρόσβαση **μόνο** στα αρχεία που δημιουργεί η ίδια η εφαρμογή | non-sensitive |
| `.../auth/userinfo.email` | ποιος λογαριασμός στέλνει | non-sensitive |

**Κανένα restricted scope.** Είναι σκόπιμη απόφαση: τα restricted (π.χ. το πλήρες
`drive` ή το `gmail.readonly`) απαιτούν **επί πληρωμή έλεγχο ασφαλείας από τρίτο
φορέα**, ενώ τα sensitive όχι. Το `drive.file` αρκεί για το αντίγραφο ασφαλείας,
γιατί η εφαρμογή διαβάζει μόνο ό,τι έγραψε η ίδια.

---

## 5. Android OAuth client

**APIs & Services → Credentials → Create credentials → OAuth client ID**

| Πεδίο | Τιμή |
|---|---|
| Application type | **Android** |
| Name | `Tax Center Android` |
| Package name | `gr.scanmydata.taxcenter` |
| SHA-1 certificate fingerprint | το SHA-1 από το βήμα 1 |

> Για δοκιμή με debug build, πρόσθεσε **δεύτερο** Android client με το SHA-1 του
> debug keystore (`~/.android/debug.keystore`, κωδικός `android`, alias
> `androiddebugkey`) και package `gr.scanmydata.taxcenter.debug`.

Δεν εμφανίζεται client secret — σωστό, δεν υπάρχει.

---

## 6. Δημοσίευση

**OAuth consent screen → Publishing status → Publish app**.

Χωρίς πιστοποίηση (verification) η εφαρμογή δουλεύει κανονικά, με τρεις
περιορισμούς:

* δεν εμφανίζεται το λογότυπο στην οθόνη συγκατάθεσης,
* ο χρήστης βλέπει **μία φορά** την προειδοποίηση «Google hasn't verified this app»
  (Advanced → Continue),
* όριο **100 χρήστες**.

Για ένα λογιστικό γραφείο αυτά είναι μη-θέματα, και **η άδεια δεν λήγει**. Όσο
είσαι σε *Testing*, πρόσθεσε τον λογαριασμό σου στους **Test users** — εκεί η
άδεια λήγει σε 7 ημέρες, γι' αυτό προτίμησε *In production*.

---

## 7. Έλεγχος

1. Εγκατάστησε το APK από το τελευταίο [release](https://github.com/scanmydata/ScanMyData-Tax-Center/releases).
2. Ρυθμίσεις → **Σύνδεση με Google**.
3. Θα ζητηθεί άδεια για αποστολή email και για αρχεία της εφαρμογής στο Drive.
4. Αν βγει `Error 10: DEVELOPER_ERROR`, δεν ταιριάζει το ζεύγος package/SHA-1 —
   συνήθως επειδή δοκιμάζεις debug build ενώ καταχώρησες μόνο το release SHA-1.

---

## Τι δεν κάνει η εφαρμογή

* Δεν διαβάζει το γραμματοκιβώτιό σου. Το `gmail.send` δίνει **μόνο** αποστολή —
  γι' αυτό, τεχνικά, τα μηνύματα φεύγουν χωρίς κεφαλίδα `From:` και τη
  συμπληρώνει ο ίδιος ο Gmail.
* Δεν βλέπει τα υπόλοιπα αρχεία του Drive σου. Το `drive.file` περιορίζεται σε
  ό,τι δημιούργησε η εφαρμογή.
* Δεν στέλνει δεδομένα πελατών σε κανέναν άλλον. Οι μόνοι προορισμοί είναι ΑΑΔΕ/
  ΕΦΚΑ (για τη λήψη) και Google (για την αποστολή και το προαιρετικό αντίγραφο).
* Το αντίγραφο στο Drive είναι κρυπτογραφημένο **πριν** ανέβει, με passphrase που
  ξέρεις μόνο εσύ. Η Google βλέπει μόνο κρυπτογράφημα — και δεν μπορεί να το
  ανακτήσει αν χάσεις την passphrase.
