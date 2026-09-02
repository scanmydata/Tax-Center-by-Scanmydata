# Στήσιμο OAuth client στο Google Cloud

Οδηγός για τη σύνδεση της εφαρμογής με τον λογαριασμό Google του γραφείου, ώστε
να στέλνει τα φορολογικά έντυπα με **Gmail API** και (προαιρετικά) να ανεβάζει
κρυπτογραφημένο αντίγραφο στο **Drive**.

> **Κατάσταση: έγινε στις 2 Σεπτεμβρίου 2026.** Το project
> `scanmydata-tax-center` είναι πλήρως ρυθμισμένο και δημοσιευμένο. Ο οδηγός
> μένει ως τεκμηρίωση — και για την περίπτωση που χρειαστεί νέο keystore ή νέο
> project. Ό,τι είναι ήδη ρυθμισμένο σημειώνεται με ✅.

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
(αλλάζει το SHA-1).

---

## 1. Keystore ✅

Δημιουργήθηκε στις 2 Σεπτεμβρίου 2026, **εκτός του repo**:

```
<φάκελος keystore, εκτός repo>
├─ release.p12              ← το keystore (PKCS#12, RSA 4096, λήγει 2054)
├─ cert.pem                 ← το πιστοποιητικό (δημόσιο, για να ξαναβγεί το SHA-1)
└─ keystore-password.txt    ← ο κωδικός
```

Η ακριβής διαδρομή δεν γράφεται εδώ: το αποθετήριο είναι δημόσιο και δεν έχει
νόημα να δημοσιεύεται πού βρίσκεται ο κωδικός του keystore.

| | |
|---|---|
| Alias | `taxcenter` |
| SHA-1 | `28:18:12:4B:1B:11:B7:F3:7E:17:A4:2C:7B:51:4A:4D:B9:D1:4D:1D` |
| Subject | `CN=ScanMyData Tax Center, O=ScanMyData, L=Athens, C=GR` |

> ⚠️ **Δύο πράγματα να κάνεις μόλις προλάβεις:**
> 1. Πέρασε τον κωδικό στον διαχειριστή κωδικών σου και **σβήσε το
>    `keystore-password.txt`** — είναι ο κωδικός σε καθαρό κείμενο δίπλα στο
>    ίδιο το keystore.
> 2. Κράτα αντίγραφο του `release.p12` σε άλλο μέσο. Χωρίς αυτό η εφαρμογή δεν
>    ενημερώνεται ποτέ ξανά και το Google Sign-In σταματά.

Αν χρειαστεί ποτέ **νέο** keystore — δεν υπάρχει Java σε αυτό το μηχάνημα, οπότε
όχι `keytool`· το `openssl` του Git Bash κάνει την ίδια δουλειά:

```bash
export MSYS2_ARG_CONV_EXCL='*'
openssl req -x509 -newkey rsa:4096 -sha256 -days 10000 -nodes -keyout key.pem -out cert.pem -subj '/CN=ScanMyData Tax Center/O=ScanMyData/L=Athens/C=GR'
openssl pkcs12 -export -inkey key.pem -in cert.pem -name taxcenter -out release.p12 -certpbe AES-256-CBC -keypbe AES-256-CBC -macalg sha256
openssl x509 -in cert.pem -noout -fingerprint -sha1
```

Το `MSYS2_ARG_CONV_EXCL` είναι απαραίτητο: αλλιώς το Git Bash νομίζει ότι το
`/CN=...` είναι διαδρομή αρχείου και το μετατρέπει σε `C:\...`.

Το `app/build.gradle.kts` περιμένει **PKCS#12** (`storeType = PKCS12`), οπότε
το `release.p12` μπαίνει αυτούσιο.

---

## 2. GitHub Secrets ✅

Χωρίς αυτά το CI βγάζει **debug-signed** APK και το γράφει στις σημειώσεις της
έκδοσης. Τέθηκαν και τα τέσσερα:

| Secret | Τιμή |
|---|---|
| `KEYSTORE_BASE64` | `base64 -w0 release.p12` |
| `KEYSTORE_PASSWORD` | από το `keystore-password.txt` |
| `KEY_PASSWORD` | ίδιο με το παραπάνω (το PKCS#12 δεν ξεχωρίζει τα δύο) |
| `KEY_ALIAS` | `taxcenter` |

```bash
base64 -w0 release.p12 > release.p12.b64
gh secret set KEYSTORE_BASE64   --repo scanmydata/ScanMyData-Tax-Center < release.p12.b64
gh secret set KEYSTORE_PASSWORD --repo scanmydata/ScanMyData-Tax-Center < keystore-password.txt
gh secret set KEY_PASSWORD      --repo scanmydata/ScanMyData-Tax-Center < keystore-password.txt
printf 'taxcenter' | gh secret set KEY_ALIAS --repo scanmydata/ScanMyData-Tax-Center
rm -f release.p12.b64
```

---

## 3. Google Cloud project ✅

Project `scanmydata-tax-center`. Ενεργοποιημένα API:

* **Gmail API**
* **Google Drive API**

---

## 4. Οθόνη συγκατάθεσης (Google Auth Platform) ✅

| Πεδίο | Τιμή |
|---|---|
| App name | `ScanMyData Tax Center` |
| User type | **External** |
| User support email | `adonis.douramanis@gmail.com` |
| Application home page | `https://scanmydata.github.io/ScanMyData-Tax-Center/` |
| Privacy policy link | `https://scanmydata.github.io/ScanMyData-Tax-Center/privacy-policy.html` |
| Terms of Service link | `https://scanmydata.github.io/ScanMyData-Tax-Center/terms.html` |
| Authorised domain | `scanmydata.github.io` |
| Developer contact | `adonis.douramanis@gmail.com` |
| App logo | **δεν ανέβηκε** — βλ. παρακάτω |

### Γιατί δεν ανέβηκε το λογότυπο

Η ίδια η κονσόλα το λέει: μόλις ανεβάσεις λογότυπο, η εφαρμογή πρέπει να
υποβληθεί για πιστοποίηση, εκτός αν είναι internal ή σε κατάσταση *Testing*.
Αφού η εφαρμογή είναι **In production**, το ανέβασμα του λογοτύπου θα την έβαζε
υποχρεωτικά σε διαδικασία πιστοποίησης.

Το αρχείο υπάρχει έτοιμο στο `docs/oauth-logo.png` (120×120). Ανέβασέ το μόνο αν
κάποτε αποφασίσεις να περάσεις πιστοποίηση.

### Scopes ✅

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

## 5. Android OAuth client ✅

| Πεδίο | Τιμή |
|---|---|
| Application type | **Android** |
| Name | `ScanMyData Tax Center (Android release)` |
| Package name | `gr.scanmydata.taxcenter` |
| SHA-1 | `28:18:12:4B:1B:11:B7:F3:7E:17:A4:2C:7B:51:4A:4D:B9:D1:4D:1D` |
| Client ID | `987658095551-k49h5caf02stavpkf9hc9o3sh6tocu3b.apps.googleusercontent.com` |

Δεν εμφανίστηκε client secret — σωστό, δεν υπάρχει. Το client ID δεν χρειάζεται
πουθενά στον κώδικα· καταγράφεται εδώ μόνο για αναφορά.

> **Τα debug builds δεν θα συνδεθούν στο Google.** Το `build.gradle.kts` βάζει
> `applicationIdSuffix = ".debug"`, άρα το package είναι
> `gr.scanmydata.taxcenter.debug` και η υπογραφή είναι debug — δύο λόγοι να μην
> ταιριάζει. Αν χρειαστεί δοκιμή σε debug build, φτιάξε **δεύτερο** Android
> client με εκείνο το package και το SHA-1 του debug keystore.

---

## 6. Δημοσίευση ✅

Publishing status: **In production**, χωρίς πιστοποίηση. Συνέπειες:

* ο χρήστης βλέπει **μία φορά** την προειδοποίηση «Google hasn't verified this
  app» και πατά *Advanced → Continue*,
* όριο **100 χρήστες** σε όλη τη ζωή του project,
* δεν εμφανίζεται λογότυπο στην οθόνη συγκατάθεσης.

Για ένα λογιστικό γραφείο αυτά είναι μη-θέματα, και **η άδεια δεν λήγει**.

Γιατί όχι *Testing*: εκεί **η εξουσιοδότηση λήγει κάθε 7 ημέρες**, οπότε θα
έπρεπε να ξανασυνδέεσαι στο Google κάθε εβδομάδα.

Η κονσόλα δείχνει μια κίτρινη ειδοποίηση «Your app requires verification». Είναι
αναμενόμενη επειδή το `gmail.send` είναι sensitive· η εφαρμογή δουλεύει κανονικά
χωρίς να την ακολουθήσεις.

---

## 7. Έλεγχος

1. Εγκατάστησε το APK από το τελευταίο [release](https://github.com/scanmydata/ScanMyData-Tax-Center/releases)
   — πρέπει να είναι **release-signed**, όχι debug.
2. Ρυθμίσεις → **Σύνδεση με Google**.
3. Θα ζητηθεί άδεια για αποστολή email και για αρχεία της εφαρμογής στο Drive.
4. Αν βγει `Error 10: DEVELOPER_ERROR`, δεν ταιριάζει το ζεύγος package/SHA-1 —
   σχεδόν πάντα επειδή δοκιμάζεις debug build.

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
