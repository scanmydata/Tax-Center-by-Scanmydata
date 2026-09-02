---
name: jsf-charset-utf8-required
description: "Τα form POST προς e-ΕΦΚΑ χρειάζονται ρητό charset=UTF-8, αλλιώς τα ελληνικά γίνονται ερωτηματικά"
metadata: 
  node_type: memory
  type: reference
  originSessionId: 6df1b97e-d2a2-4f05-a1d2-c496b70a70a2
  modified: 2026-09-02T07:19:13.126Z
---

Στα `application/x-www-form-urlencoded` POST προς τον JSF server του e-ΕΦΚΑ, το
`charset=UTF-8` στο `Content-Type` είναι **υποχρεωτικό**. Χωρίς αυτό ο server
μεταγράφει τα ελληνικά της partial-response σε `?`.

**Why:** το `FormBody` του OkHttp γράφει `application/x-www-form-urlencoded`
χωρίς charset — γι' αυτό ο `HttpBridge` χτίζει το σώμα με
`toRequestBody(mediaType)` και περνά τις κεφαλίδες του engine αυτούσιες.

Άλλα δύο load-bearing σημεία στην ίδια διαδρομή:

* **Κανένα auto-redirect** — ο engine κρίνει `InvalidCredentials` από το ΤΕΛΙΚΟ
  URL, οπότε `followRedirects(false)`.
* **`CookieJar.NO_COOKIES`** — ο engine κρατά δικό του jar και βάζει μόνος του
  την κεφαλίδα `Cookie`. Ο σκόπιμα «χαλαρός» κανόνας του (cookies του `gsis.gr`
  ταξιδεύουν προς `e-efka.gov.gr` για να δουλέψει το SSO) ζει στη JS πλευρά.

Επίσης: πολλά AADE endpoints στέλνουν PDF με `application/octet-stream`, οπότε ο
έλεγχος γίνεται από τα magic bytes `%PDF`.

Σχετικά: [[engine-runs-verbatim-from-runner]]
