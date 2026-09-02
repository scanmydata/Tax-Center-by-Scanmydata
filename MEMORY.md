# Μνήμη έργου

Οι αποφάσεις που δεν φαίνονται από τον κώδικα, και οι παγίδες που κόστισαν χρόνο.
Κάθε αρχείο κρατά **ένα** πράγμα, με το «γιατί» — όχι μόνο το «τι».

Καθρεφτίζεται από τη μνήμη του Claude Code
(`~/.claude/projects/…/memory/`), ώστε να είναι versioned μαζί με τον κώδικα και
να μπορεί να τη διαβάσει όποιος δουλέψει στο repo.

| Απόφαση | Ουσία |
|---|---|
| [Ο engine τρέχει αυτούσιος από τον runner](docs/decisions/engine-runs-verbatim-from-runner.md) | Κανένα port σε Kotlin· vendoring + shims, ένα source of truth. |
| [charset=UTF-8 στα form POST](docs/decisions/jsf-charset-utf8-required.md) | Χωρίς αυτό ο JSF του e-ΕΦΚΑ γυρίζει τα ελληνικά ως `?`. Μαζί: no-redirects, NO_COOKIES, `%PDF` sniff. |
| [GSIS OAM-6: σειριακά, πάντα](docs/decisions/gsis-serial-sessions-oam6.md) | Παραλληλία κλειδώνει τον λογαριασμό. 2FA/CAPTCHA δεν παρακάμπτονται. |
| [Android OAuth χωρίς client secret](docs/decisions/android-oauth-needs-no-client-secret.md) | Τα κλειδιά του Infisical δεν μπαίνουν στο APK. |
| [Η παγίδα του e-timologio key](docs/decisions/excel-etimologio-key-trap.md) | Whole-string matching + denylist, αλλιώς 403 σε κάθε πελάτη. |
| [Κωδικοί με email: δικλείδες](docs/decisions/credentials-email-safeguards.md) | Επιτρέπεται, αλλά κλειστό εξ ορισμού, με προειδοποίηση ανά αποστολή και καταγραφή. |
| [Παγίδες με ελληνικά σε Windows](docs/decisions/greek-tooling-pitfalls-windows.md) | BOM σε `.ps1`, εμφωλευμένα σχόλια Kotlin, ASCII `\b` στη Java. |

Η τρέχουσα κατάσταση ανά φάση είναι στο [TODO.md](TODO.md).
