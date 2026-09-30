/*
 * lib/tekmarto-min-income.js  --  Ελάχιστο τεκμαρτό καθαρό εισόδημα ατομικών/ελ. επαγγελματιών (28Α ΚΦΕ)
 * =============================================================================
 * FAITHFUL αναπαραγωγή του υπολογισμού του TaxSystem (Ν.5073/2023, άρθρο 28Α ΚΦΕ).
 * Πηγή (decompiled, ΜΗ obfuscated): OnLineTaxClearance.dll → πεδία `F_TE_*`, και η ΜΗΧΑΝΗ
 * `D26.E126.Forms.fE1Tab6.ActCalc(bool Ypo)` + `fE1Tab6.CalcClimaka(double)` (D26.dll, δηλώσεις 2025/
 * εισοδήματα 2024). Καθαρή ντετερμινιστική αριθμητική (Elib.Round = 2 δεκαδικά). ΜΗΝ πειράζει βάση.
 *
 * ΑΠΟ ΠΟΥ ΑΝΤΛΕΙ ΤΑ ΣΤΟΙΧΕΙΑ (τα πεδία F_TE_* τα προσυμπληρώνει η ΑΑΔΕ στο Ε1/Ε3· ο χρήστης μπορεί
 * να αμφισβητήσει/επαναϋπολογίσει από τη φόρμα «Αμφισβήτηση ελάχιστου ποσού καθαρού εισοδήματος»):
 *   • years        (F_TE_TOTAL_YEARS)  → έτη άσκησης ατομικής επιχ. (από ημ. έναρξης, μητρώο ΑΑΔΕ)
 *   • maxSalary    (F_TE_MAXSALARY)    → μικτές αποδοχές υπαλλήλου με μεγαλύτερο μισθό (ΑΠΔ/ΕΡΓΑΝΗ) — cap 30.000
 *   • payrollCost  (F_TE_PAYROLLCOST)  → συνολικό κόστος μισθοδοσίας (ΑΠΔ)
 *   • turnover     (F_TE_TURNOVER)     → τζίρος ατομικής (Ε3/ΦΠΑ)
 *   • averageKad   (F_TE_AVERAGE_KAD)  → μέσος όρος τζίρου ΚΑΔ κλάδου (πίνακες ΑΑΔΕ)
 *   • incomeEmployee (F_TE_INCOME_EMPLOYEE) → εισόδημα από μισθωτή/συντάξεις (μείωση)
 *   • incomeFarm     (F_TE_INCOME_FARM)     → εισόδημα από αγροτική δραστηριότητα (μείωση)
 *   • accTaxable   (F401/F402)         → λογιστικό φορολογητέο κέρδος από Ε3 (για σύγκριση)
 *   • reduceRight  (F_TE_REDUCE_RIGHT) → 0=δεν υπολογίζεται, 1=πλήρες, 2=μείωση 50%
 *   • endDate/docYear                  → μερική χρήση (διακοπή εντός έτους → αναλογία ημερών)
 *
 * Αποτέλεσμα: το τεκμαρτό φορολογητέο (F_TE_TAX_TAXABLE) μεταφέρεται στους κωδ. Ε1 405 (υπόχρεος)/406 (σύζυγος).
 */
'use strict';

// 2 δεκαδικά, τραπεζική στρογγυλοποίηση (== Elib.Round)
function round2(x) {
  const n = Number(x) || 0;
  return Math.round((n + Number.EPSILON) * 100) / 100;
}

// -------------------------------------------------------------------------
// Έτος-ειδικές σταθερές (== hardcoded στο ActCalc του κάθε D-DLL). KEYED ΑΝΑ ΕΤΟΣ ΕΙΣΟΔΗΜΑΤΩΝ.
// Επιβεβαιωμένα live από decompile: 2023 (D24), 2024 (D25), 2025 (D26).
//   base3 = βασικό ελάχιστο (= ετήσιος κατώτατος μικτός μισθός)· base4/5/6plus = +10% σωρευτικά.
//   variant 'base_floor' (2023/D24): έτη 1/2 = ΑΠΕΥΘΕΙΑΣ μειωμένη βάση (×1/3, ×2/3)· δάπεδο maxSalary
//     εφαρμόζεται στη ΒΑΣΗ πριν τις προσαυξήσεις. variant 'total_floor' (2024+/D25,D26): προσαυξήσεις
//     πρώτα, δάπεδο maxSalary στο ΣΥΝΟΛΟ, μετά factor (1/3, 2/3) για έτη 1/2.
//   scale = κλίμακα φόρου επιχ. δραστηριότητας (CalcClimaka). cap/ceiling/payrollCap κοινά.
// ΝΕΟΤΕΡΟ ΕΤΟΣ: για χρήση χωρίς κλεισμένο D-DLL (π.χ. 2026) βλ. σχόλιο στο τέλος + `estimateOpenYear`.
// -------------------------------------------------------------------------
const SCALE = [[10000, 0.09], [10000, 0.22], [10000, 0.28], [10000, 0.36], [0, 0.44]];
const COMMON = { payrollIncreaseRate: 0.10, payrollIncreaseCap: 15000, turnoverIncreaseRate: 0.05, maxSalaryCap: 30000, ceiling: 50000, scale: SCALE };
const YEAR_PARAMS = {
  // εισοδήματα 2023 (D24, δηλώσεις 2024) — κατώτατος 780×14 = 10.920· ΠΡΩΤΟ έτος 28Α
  2023: { ...COMMON, variant: 'base_floor', minWage: 10920, base1: 3640, base2: 7280, base3: 10920, base4: 12012, base5: 13213.2, base6plus: 14534.52 },
  // εισοδήματα 2024 (D25, δηλώσεις 2025) — κατώτατος 830×14 = 11.620
  2024: { ...COMMON, variant: 'total_floor', minWage: 11620, base3: 11620, base4: 12782, base5: 14060.2, base6plus: 15466.22, firstYearFactor: 0.3333333, secondYearFactor: 0.6666667 },
  // εισοδήματα 2025 (D26, δηλώσεις 2026) — κατώτατος 880×14 = 12.320
  2025: { ...COMMON, variant: 'total_floor', minWage: 12320, base3: 12320, base4: 13552, base5: 14907.2, base6plus: 16397.92, firstYearFactor: 0.3333333, secondYearFactor: 0.6666667 },
};
const LATEST_YEAR = 2025;

// κλίμακα φόρου επιχ. δραστηριότητας (== fE1Tab6.CalcClimaka)
function calcClimaka(checkPoso, scale) {
  const amt = round2(checkPoso);
  if (amt <= 0) return 0;
  let tax = 0, lower = 0;
  for (let i = 0; i < scale.length; i++) {
    const [width, rate] = scale[i];
    const isLast = i === scale.length - 1 || width === 0;
    if (isLast || amt <= lower + width) { tax += round2((amt - lower) * rate); return round2(tax); }
    tax += round2(width * rate); lower += width;
  }
  return round2(tax);
}

/*
 * calcTekmarto(inp) — υπολογισμός ελάχιστου τεκμαρτού καθαρού εισοδήματος για ΕΝΑ πρόσωπο.
 * inp: {
 *   year,                 // φορ. έτος εισοδημάτων (default 2024) — επιλέγει YEAR_PARAMS
 *   years,                // F_TE_TOTAL_YEARS (ακέραιος έτη· 0 => δεν υπολογίζεται)
 *   reduceRight,          // 0/1/2  (0 => δεν υπολογίζεται, 2 => μείωση 50%)   default 1
 *   maxSalary,            // F_TE_MAXSALARY
 *   payrollCost,          // F_TE_PAYROLLCOST
 *   turnover,             // F_TE_TURNOVER
 *   averageKad,           // F_TE_AVERAGE_KAD
 *   incomeEmployee,       // F_TE_INCOME_EMPLOYEE
 *   incomeFarm,           // F_TE_INCOME_FARM
 *   accTaxable,           // F401/F402 (λογιστικό φορολογητέο) — για σύγκριση
 *   endDate, docYear,     // (προαιρετικά) μερική χρήση: αν διακοπή εντός έτους εισοδημάτων → αναλογία ημερών
 * }
 * Επιστρέφει το πλήρες breakdown (== τα πεδία F_TE_* της φόρμας).
 */
function calcTekmarto(inp = {}) {
  const year = inp.year || LATEST_YEAR;
  const P = YEAR_PARAMS[year] || YEAR_PARAMS[LATEST_YEAR];
  const years = Math.trunc(Number(inp.years) || 0);
  const reduceRight = (inp.reduceRight === undefined || inp.reduceRight === null) ? 1 : Math.trunc(Number(inp.reduceRight));
  const maxSalary = Math.min(round2(inp.maxSalary), P.maxSalaryCap);   // cap 30.000
  const payrollCost = round2(inp.payrollCost);
  const turnover = round2(inp.turnover);
  // averageKad: ρητό, αλλιώς auto-lookup από ΚΑΔ+έτος (data/kad-averages-{year}.json)
  let averageKad = round2(inp.averageKad);
  if ((inp.averageKad === undefined || inp.averageKad === null || inp.averageKad === '') && inp.kad) {
    try { const r = require('./kad-average').getKadAverage(inp.kad, year); if (r) averageKad = round2(r.amount); } catch (e) {}
  }
  const incomeEmployee = round2(inp.incomeEmployee);
  const incomeFarm = round2(inp.incomeFarm);
  const accTaxable = round2(inp.accTaxable);

  // μερική χρήση (== ActCalc: αν F_TE_END μέσα στο έτος εισοδημάτων = DocYear-1 = `year`)
  let days = -1;
  if (inp.endDate) {
    const end = new Date(inp.endDate);
    if (end.getFullYear() === year) days = Math.floor((end - new Date(year, 0, 1)) / 86400000) + 1;
  }

  const out = {
    year, variant: P.variant, minWage: P.minWage, years, reduceRight,
    minIncome: 0, increasePayroll: 0, increaseTurnover: 0, reductionIncome: 0,
    taxTaxable: 0, taxIncome: 0, accTaxable, accIncome: 0, balTaxable: 0, balIncome: 0,
    applied: false, notes: [],
  };

  // == ActCalc ==
  if (reduceRight === 0 || years === 0) { out.notes.push('Δεν υπολογίζεται (reduceRight=0 ή years=0)'); return out; }

  // βασικό ελάχιστο ανά έτη λειτουργίας
  let base, factor = 1.0;
  if (P.variant === 'base_floor') {
    // 2023/D24: έτη 1/2 = απευθείας μειωμένη βάση (χωρίς factor)
    base = (years === 1) ? P.base1 : (years === 2) ? P.base2 : (years === 3) ? P.base3 : (years === 4) ? P.base4 : (years === 5) ? P.base5 : P.base6plus;
  } else {
    // 2024+/D25,D26: βάση + factor (1/3, 2/3) στα έτη 1/2
    base = (years <= 3) ? P.base3 : (years === 4) ? P.base4 : (years === 5) ? P.base5 : P.base6plus;
    if (years === 1) factor = round2(P.firstYearFactor);
    else if (years === 2) factor = round2(P.secondYearFactor);
  }

  out.reductionIncome = round2(incomeEmployee + incomeFarm);
  out.increasePayroll = Math.min(round2(payrollCost * P.payrollIncreaseRate), P.payrollIncreaseCap); // +10% κόστους, cap 15.000
  if (turnover > 0 && averageKad > 0 && turnover > averageKad) {
    out.increaseTurnover = round2((turnover - averageKad) * P.turnoverIncreaseRate);               // +5% υπέρβασης τζίρου
  }

  let poso;
  if (P.variant === 'base_floor') {
    // 2023: δάπεδο maxSalary στη ΒΑΣΗ, μετά προσθήκη προσαυξήσεων
    let b = base; if (maxSalary > b) b = maxSalary;
    out.minIncome = b;
    poso = round2(b + out.increasePayroll + out.increaseTurnover);
  } else {
    // 2024+: προσαυξήσεις πρώτα, δάπεδο στο ΣΥΝΟΛΟ, μετά factor
    out.minIncome = base;
    poso = round2(base + out.increasePayroll + out.increaseTurnover);
    if (poso < maxSalary) poso = maxSalary;            // δάπεδο = μισθός μεγαλύτερου υπαλλήλου (cap 30.000)
    if (factor < 1.0) poso = round2(poso * factor);    // αναλογία 1ου/2ου έτους (1/3, 2/3)
  }

  let taxable = poso;
  if (taxable > P.ceiling) taxable = P.ceiling;        // πλαφόν 50.000
  if (reduceRight === 2) taxable = round2(taxable / 2.0); // μείωση 50%
  if (out.reductionIncome > 0) taxable = Math.max(round2(taxable - out.reductionIncome), 0); // μείωση λόγω μισθωτής/αγροτικής
  if (days > 0 && days < 365) taxable = round2(taxable * days / 365.0); // μερική χρήση (διακοπή)

  out.taxTaxable = round2(taxable);
  out.taxIncome = calcClimaka(out.taxTaxable, P.scale);
  out.accIncome = calcClimaka(accTaxable, P.scale);
  out.balTaxable = round2(out.taxTaxable - accTaxable);        // διαφορά τεκμαρτού − λογιστικού
  out.balIncome = round2(out.taxIncome - out.accIncome);       // διαφορά φόρου
  out.applied = true;
  if (days > 0 && days < 365) out.notes.push('Μερική χρήση: ' + days + ' ημέρες');
  return out;
}

// υπόχρεος + σύζυγος (ξεχωριστά, όπως το TaxSystem: κωδ. 405/406)
function calcHousehold({ ypoxr = {}, syz = null, year = LATEST_YEAR } = {}) {
  const res = { year, ypoxr: calcTekmarto({ ...ypoxr, year }) };
  if (syz && Object.keys(syz).length) res.syz = calcTekmarto({ ...syz, year });
  res.totalTaxable = round2((res.ypoxr.taxTaxable || 0) + (res.syz ? res.syz.taxTaxable : 0));
  return res;
}

/*
 * estimateOpenYear(inp) — ΠΡΟΣΟΜΟΙΩΣΗ για ΤΡΕΧΟΥΣΑ/ΑΝΟΙΧΤΗ χρήση χωρίς κλεισμένο D-DLL & χωρίς ΑΑΔΕ prefill
 * (π.χ. χρήση 2026: το D27 δεν έχει εκδοθεί, η ΑΑΔΕ δεν έχει προσυμπληρώσει τίποτα — δεν υπάρχει «επίσημος»
 * υπολογισμός· γίνεται μόνο εκτίμηση). Χρησιμοποιεί τη μεθοδολογία του πιο πρόσφατου γνωστού έτους (2025/D26,
 * variant total_floor) με:
 *   - minWage: τον κατώτατο μισθό της χρήσης (inp.minWage· default = LATEST). Οι σκάλες base3..6plus
 *     ανακλιμακώνονται αναλογικά με το minWage (base3=minWage, base4=×1.1, base5=×1.1², base6+=×1.1³).
 *   - turnover/payrollCost: ΠΡΟΒΛΕΨΗ (running ΦΠΑ Φ2 × 12/μήνες, ή εκτίμηση).
 *   - averageKad: ο ΤΕΛΕΥΤΑΙΟΣ δημοσιευμένος μέσος όρος ΚΑΔ (εκδίδεται με υστέρηση).
 * Επιστρέφει το ίδιο shape με `estimated:true`. ΠΡΟΣΟΧΗ: εκτίμηση, όχι οριστικός υπολογισμός.
 */
function estimateOpenYear(inp = {}) {
  const ref = YEAR_PARAMS[LATEST_YEAR];
  const minWage = round2(inp.minWage) || ref.minWage;
  const params = {
    ...ref, minWage,
    base3: minWage, base4: round2(minWage * 1.1), base5: round2(minWage * 1.1 * 1.1), base6plus: round2(minWage * 1.1 * 1.1 * 1.1),
  };
  const key = 'openYear_' + (inp.year || 'current');
  YEAR_PARAMS[key] = params;
  const r = calcTekmarto({ ...inp, year: key });
  delete YEAR_PARAMS[key];
  r.year = inp.year || null; r.estimated = true;
  r.notes = (r.notes || []).concat('ΕΚΤΙΜΗΣΗ ανοιχτής χρήσης (χωρίς κλεισμένο D-DLL / ΑΑΔΕ prefill· minWage=' + minWage + ')');
  return r;
}

module.exports = { calcTekmarto, calcHousehold, estimateOpenYear, calcClimaka, round2, YEAR_PARAMS, LATEST_YEAR };

// -------------------------------------------------------------------------
// CLI / αυτο-έλεγχος:  node lib/tekmarto-min-income.js --years 4 --payrollCost 20000 --turnover 80000 --averageKad 50000
// -------------------------------------------------------------------------
if (require.main === module) {
  const a = process.argv.slice(2);
  if (a.includes('--selftest') || a.length === 0) {
    const ex = { years: 4, reduceRight: 1, maxSalary: 18000, payrollCost: 20000, turnover: 80000, averageKad: 50000, accTaxable: 10000 };
    console.log('=== self-test (ίδια inputs, 3 έτη — 4 έτη λειτ., κόστος μισθ. 20.000, τζίρος 80k vs ΚΑΔ 50k, υπάλληλος 18.000) ===');
    for (const y of [2023, 2024, 2025]) {
      const r = calcTekmarto({ ...ex, year: y });
      console.log(`  ${y} (base4=${YEAR_PARAMS[y].base4}): minIncome=${r.minIncome} +payroll=${r.increasePayroll} +turnover=${r.increaseTurnover} → taxable=${r.taxTaxable} φόρος=${r.taxIncome}`);
    }
    console.log('=== εκτίμηση ανοιχτής χρήσης (2026, minWage 12.320) ===');
    const est = estimateOpenYear({ year: 2026, ...ex });
    console.log(`  2026*: taxable=${est.taxTaxable} φόρος=${est.taxIncome} (estimated=${est.estimated})`);
    process.exit(0);
  }
  const opt = {};
  for (let i = 0; i < a.length; i++) { const m = a[i].match(/^--(.+)$/); if (m) opt[m[1]] = (a[i + 1] && !a[i + 1].startsWith('--')) ? a[++i] : true; }
  const num = (k) => opt[k] !== undefined ? Number(opt[k]) : undefined;
  const args = {
    year: num('year') || LATEST_YEAR, years: num('years'), reduceRight: num('reduceRight'),
    maxSalary: num('maxSalary'), payrollCost: num('payrollCost'), turnover: num('turnover'),
    averageKad: num('averageKad'), incomeEmployee: num('incomeEmployee'), incomeFarm: num('incomeFarm'),
    accTaxable: num('accTaxable'), endDate: opt.endDate, minWage: num('minWage'),
  };
  const r = opt.estimate ? estimateOpenYear(args) : calcTekmarto(args);
  console.log(JSON.stringify(r, null, 2));
}
