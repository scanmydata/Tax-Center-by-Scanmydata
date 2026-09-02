---
name: excel-etimologio-key-trap
description: Στο «Κωδικοί Υπόχρεων» το substring match στο «subscription key» αρπάζει το κλειδί του e-timologio
metadata:
  type: reference
---

Στο export «Κωδικοί Υπόχρεων» (83 στήλες) συνυπάρχουν:

* στήλη **BI** «Api myData» — το πραγματικό myDATA key
* στήλη **BL** «Subscription key e-timologio» — **άλλο προϊόν**

Ένα substring match στο «subscription key» αρπάζει το λάθος κλειδί και το
στέλνει ως myDATA key: **403 σε κάθε πελάτη, σιωπηλά**. Ομοίως το «Συνθηματικό
myData» είναι ο κωδικός ιστοσελίδας, όχι το κλειδί API.

**How to apply:** η αντιστοίχιση γίνεται σε ΟΛΟΚΛΗΡΗ την κανονικοποιημένη
επικεφαλίδα, ποτέ substring, με ρητό denylist (`ColumnAliases.FORBIDDEN`).
Στην κανονικοποίηση οι **τελείες σβήνονται** (όχι σε κενά), ώστε το `Α.Φ.Μ.` να
γίνει `αφμ` και όχι `α φ μ`.

Δύο ακόμη παγίδες του ίδιου αρχείου:

* Το export **δεν έχει στήλη email πελάτη** — μόνο «EMAIL INTRASTAT», που είναι
  άλλο πράγμα. Τα emails έρχονται από το Μητρώο Επικοινωνίας ΑΑΔΕ (`aade-email`).
* Το πραγματικό αρχείο έχει **ζωντανούς κωδικούς σε cleartext**. Δεν μπαίνει
  ποτέ σε repo, fixture ή log· τα tests χρησιμοποιούν συνθετικό.
