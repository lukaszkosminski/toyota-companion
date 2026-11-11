# C29 – dokumentacja vs kod (agent 3, tylko odczyt)

Data: 2026-11-10, ok. 18:40 CET. Repo: `/home/lk/projekty/toyota-companion`, gałąź `main`, zmiana C29 niezacommitowana.
Zakres: `docs/SPEC.md` §49 i §12.2, `docs/PRD.md` F48/F49, `docs/SECURITY.md`, `CHANGELOG.md` (Unreleased),
`docs/ROADMAP.md`, `docs/HANDOFF.md`, `README.md` oraz kod C29. Nic nie zmieniałem poza tym plikiem; nie uruchamiałem
Gradle, testów, ruffa ani sieci.

**Ważna uwaga o stanie plików.** Kod C29 był w trakcie mojego odczytu poprawiany (mtime
`ui/BudgetAlert.kt`, `notify/BudgetAlertNotifier.kt`, `ui/BudgetController.kt`, `ui/Texts.kt` = 2026-11-10 18:39).
Dokumentacja ma mtime 2026-11-09 21:38–21:39 i nie została w tym czasie zmieniona. Poniżej porównuję **aktualny
working tree kodu po tych poprawkach** z **niezmienioną dokumentacją**. To wyjaśnia, dlaczego część rozbieżności
dotyczy rzeczy, których nie było w pierwszej wersji C29 (np. „Zostało X zł” w treści ostrzeżenia).

---

## A. SPEC §49 / §12.2, PRD F48/F49, SECURITY, CHANGELOG, ROADMAP vs kod

### A.1. Nazwa klucza w SPEC §49 jest inna niż w kodzie (rozbieżność)

- Dokument: `docs/SPEC.md:1174` — „Klucz `RRRR-MM:80`/`RRRR-MM:100` w `SettingsStore` (`last_budget_alert`); powiadamiamy”.
- Kod: `android/app/src/main/kotlin/pl/lk/toyotacompanion/settings/SettingsStore.kt:227` —
  `const val KEY_BUDGET_ALERT_LAST = "budget_alert_last"`; odczyt w `:136`
  `fun lastBudgetAlert(): String? = prefs.getString(KEY_BUDGET_ALERT_LAST, null)`.
- Ten sam dokument w `docs/SPEC.md:440` podaje poprawnie: „`budget_alert` (przełącznik powiadomienia, domyślnie on)
  i `budget_alert_last` (klucz `RRRR-MM:80|100`) należą do F49/§49.”
- Wniosek: §49 podaje `last_budget_alert`, a kod (i §12.2) używają `budget_alert_last`. To nie jest nazwa funkcji
  (`lastBudgetAlert`), tylko klucz preferencji.

### A.2. PRD F48 nadal mówi, że powiadomienie to „osobny krok” (rozbieżność ze stanem po C29)

- Dokument: `docs/PRD.md:119` — „… karta niezależna od statusu Toyoty, bez sieci i bez budzenia auta;
  powiadomienie przy progu to osobny krok (SPEC §49)”.
- Kod i PRD: `docs/PRD.md:120` dodaje już F49 „Powiadomienie o budżecie”; implementacja istnieje w
  `ui/BudgetAlert.kt`, `notify/BudgetAlertNotifier.kt`, `AppContainer.kt:599-608` i `AppContainer.kt:375-377`.
- Wniosek: zdanie w F48 jest nieaktualne po dodaniu F49 w tej samej zmianie.

### A.3. CHANGELOG Unreleased: F48 nadal zapowiada powiadomienie jako osobny krok (rozbieżność)

- Dokument: `CHANGELOG.md:4` — „… ostatnie dobre dane zostają przy błędzie odczytu; powiadomienie przy progu to
  osobny krok (opisany w §49)”.
- Dokument obok: `CHANGELOG.md:5` — „Powiadomienie o budżecie (F49, SPEC §49, plan C29): kanał „Budżet”, jedno
  ostrzeżenie przy 80% i jedno przy przekroczeniu 100% limitu …”.
- Wniosek: dwa wpisy w tej samej sekcji `Unreleased` przeczą sobie; wpis F48 trzeba poprawić (np. usunąć końcowe
  zdanie albo zastąpić „powiadomienie opisane w F49/§49”).

### A.4. SPEC §49 nie wymienia „Zostało X zł” w treści ostrzeżenia 80% (brak w opisie treści)

- Dokument: `docs/SPEC.md:1181` — „Treść: miesiąc, wydane, limit, procent; przy 100% dodatkowo kwota przekroczenia;
  bez VIN i GPS.”
- Kod: `android/app/src/main/kotlin/pl/lk/toyotacompanion/ui/Texts.kt:818-821` —
  `fun budgetAlertWarn(month: String, spent: String, limit: String, percent: Int, left: String)` oraz treść
  „$month: wydano $spent z $limit zł ($percent%). Zostało $left zł.”; wywołanie z `remainingPln`:
  `ui/BudgetAlert.kt:73`.
- Wniosek: ostrzeżenie 80% ma dodatkowo kwotę „Zostało”, której §49 nie wymienia (kwota nadal jest „kwotą”, więc
  nie narusza SECURITY, ale opis treści jest niepełny).

### A.5. Próg 100% w SPEC §49 nie jest doprecyzowany względem `overBudget` (nieścisłość, nie twarda sprzeczność)

- Dokument: `docs/SPEC.md:1173-1174` — „progi **80%** (ostrzeżenie) i **100%** (przekroczenie)”.
- Kod: `ui/BudgetAlert.kt:27-31` — próg 100 jest wybierany przez `progress.overBudget` (`spent > limit`), a nie przez
  `percentUsed >= 100`; próg 80 przez `percentUsed >= BUDGET_ALERT_WARN_PCT`. `percentUsed` jest całym procentem
  HALF_UP (`core/domain/BudgetOps.kt:44`), więc 79,5% daje już 80%, a dokładnie 100% limitu (bez przekroczenia)
  daje ostrzeżenie 80%, nie „przekroczenie”.
- Wniosek: samo „100% (przekroczenie)” jest zbyt ogólne; warto w §49 napisać wprost, że próg 100 = `spent > limit`,
  a 80 = zaokrąglony cały procent ≥ 80 (łącznie z 79,5%). To nie zmienia kodu, tylko usuwa dwuznaczność.

### A.6. Lista plików w SPEC §49 jest niepełna (brak, nie fałsz)

- Dokument: `docs/SPEC.md:1183-1184` — „Pliki: `ui/BudgetAlert.kt` (czysta reguła progów), `notify/BudgetAlertNotifier.kt`,
  `ui/ExpensesController.kt` (`onChanged`), `AppContainer` (`syncRunner`), `settings/SettingsStore.kt`,
  `ui/OptionsScreen.kt`, `ui/Texts.kt`.”
- Kod: przełącznik wymaga też `ui/BudgetController.kt` (`readAlert`/`saveAlert`/`setAlert`, linie 32-33 i 129-133)
  oraz `ui/AppRoot.kt` (przekazanie `budgetAlert`/`onSetBudgetAlert`, linie 289-290). Dodatkowo `AppContainer.kt:607`
  przekazuje `alertEnabled` do notifiera, a nie tylko trigger w `syncRunner`.
- Wniosek: lista nie kłamie, ale nie obejmuje dwóch plików zmienionych w C29; przy okazji poprawki warto je dopisać.

### A.7. Id powiadomienia nie jest opisane w dokumentacji (brak wzmianki)

- Kod: `notify/BudgetAlertNotifier.kt:128-131` — `CHANNEL_ID = "budget"`, `NOTIFICATION_ID = 901`.
- Dokumentacja podaje kanał `budget` i `IMPORTANCE_DEFAULT` (`docs/SPEC.md:1173`), ale nigdzie nie podaje id 901.
- Wniosek: nie ma zdania sprzecznego; jest brak informacji. Jeśli dokument ma opisywać „id i ważność kanału”, trzeba
  dopisać id 901 (albo świadomie pozostawić jako szczegół implementacyjny).

### Co się zgadza (bez rozbieżności)

- Kanał: `budget` i `IMPORTANCE_DEFAULT` — `SPEC.md:1173` vs `BudgetAlertNotifier.kt:129-130` i `:109-111`.
- Wartość domyślna przełącznika: `true` — `SPEC.md:1182`, `SECURITY.md:15`, `SettingsStore.kt:129`, `BudgetState`
  (`ui/Budget.kt:33`) vs `OptionsScreen.kt:74`.
- Triggery: tylko `SyncOutcome.Ok` (`AppContainer.kt:375-377`) i udana zmiana wydatku po `add`/`delete`
  (`ExpensesController.kt:63-87`); brak triggera przy wejściu na status i przy `BudgetController.set(limit)`
  (`ui/BudgetController.kt:118-127` nie woła notifiera).
- Semantyka klucza: „80 po 100 cisza, 100 po 80 alert, nowy miesiąc od nowa” — `ui/BudgetAlert.kt:26-37`.
- Klucz zapisywany po `post`, brak zapisu bez uprawnienia/przełącznika/wyciszonego kanału —
  `BudgetAlertNotifier.kt:79-100` (`if (post(alert)) markAlerted(alert.key)` w `:92`).
- Brak VIN/GPS w treści — `ui/BudgetAlert.kt:65-74` używa tylko miesiąca, kwot i procentu; test
  `ui/BudgetAlertTest.kt:109-117`.
- Auto Backup limitu i przełącznika — `SECURITY.md:14-15`, `SPEC.md:1170-1182`; pliki reguł kopii zawierają
  `sharedpref/settings.xml`: `android/app/src/main/res/xml/data_extraction_rules.xml:7,12` i `backup_rules.xml:7`.
- SECURITY nie ma sprzeczności: `SECURITY.md:15` mówi o „miesiącu, kwotach i procencie”, a aktualna treść nadal
  zawiera tylko te dane („Zostało … zł” i „przekroczono o … zł” to kwoty).

**Liczba w A: 4 rozbieżności (A.1–A.4) + 2 nieścisłości/braki (A.5–A.6) + 1 brak wzmianki (A.7).**
Twarde rozbieżności: A.1 (klucz), A.2 (PRD F48), A.3 (CHANGELOG), A.4 (treść 80%).

---

## B. ROADMAP: nieaktualne wpisy w NOW i NEXT

Stan dowodowy: `git tag` zawiera `v0.2.3`, `v0.2.4`, `v0.2.5`, `v0.2.6`; `v0.2.3` wskazuje na commit `4dedf69`
(2026-11-08), a w jego historii są `d8835dd` (F40) i `49971b6` (F41–F43). `v0.2.6` jest z 2026-11-09 (`8d1be26`).
Czyli prace opisywane w NOW jako „w toku” i wydanie v0.2.3 są już wydane.

### B.1. NOW: „W toku” dla F40 i F41–F43 (nieaktualne)

- Dokument: `docs/ROADMAP.md:71-72` — „W toku (2026-11-08, agenci): pola autostrady i przekroczeń prędkości
  w archiwum tras (PRD F40, Room v6) oraz ekrany tylko do odczytu „Stan pojazdu”, „Lokalizacja”, „Stan techniczny”
  z chmury Toyoty, bez budzenia auta (SPEC §42–§44)”.
- Dowód: `git log` — `d8835dd feat: motorway and speeding fields in the trip archive (PRD F40)` i
  `49971b6 feat: vehicle status, location and vehicle health screens (PRD F41-F43)` są przed tagiem `v0.2.3`
  (`4dedf69`, 2026-11-08).
- Wniosek: wpis „W toku” jest nieaktualny; te funkcje są w DONE i wydane w v0.2.3.

### B.2. NEXT: „Wydanie v0.2.3 po zakończeniu prac w toku” (nieaktualne)

- Dokument: `docs/ROADMAP.md:79` — „Wydanie v0.2.3 po zakończeniu prac w toku”.
- Dowód: tagi `v0.2.3` (2026-11-08), `v0.2.4` (2026-11-08), `v0.2.5` (2026-11-09), `v0.2.6` (2026-11-09);
  najnowszy to v0.2.6.
- Wniosek: wpis trzeba zastąpić wydaniem po v0.2.6 (bez wymyślania numeru, dopóki właściciel go nie nada).

### B.3. NEXT: „D2–D14” z przeglądu C22 (częściowo nieaktualne)

- Dokument: `docs/ROADMAP.md:77-78` — „Drobne z przeglądu C22 …: D2–D14 – m.in. wyścig przeładowań w wydatkach
  i pogodzie, data zależna od ustawień regionalnych, `collectAsState` bez cyklu życia, limit rozmiaru strony cen,
  testy stylu jazdy”.
- Dowód: commit `a982de8 fix(app): minor items of the C22 review (D3-D7, D12, D13)` (w v0.2.3) załatwia
  D3–D7, D12 i D13 (w treści commita m.in. D5/D6 wyścigi, D7 data bez `String.format`, D13 limit strony cen,
  D12 testy stylu jazdy). Czyli większość przykładów z tego wpisu jest już zrobiona.
- Ograniczenie: plik `briefs/toyota-companion/C22-quality-review.md` nie istnieje w repo (`ls briefs/toyota-companion`
  go nie pokazuje), więc **nie sprawdzono** dokładnej treści D2, D8–D11 i D14; nie zgaduję, które z nich są otwarte.
  Pewne jest tylko to, że D3–D7, D12, D13 są zrobione.

### Proponowany dokładny nowy tekst NOW i NEXT

```markdown
## NOW
- Właściciel: test klienta na prawdziwym koncie `./gradlew -q :core:liveCheck` (docs/HANDOFF.md); przejście telefonu z wersji
  debug na wydanie (eksport archiwum → odinstalowanie → instalacja → logowanie → import)

## NEXT
- Pozostałe drobne z przeglądu C22 (briefs/toyota-companion/C22-quality-review.md) po v0.2.3: D3–D7, D12 i D13 są
  zrobione (commit a982de8); reszta – m.in. `collectAsState` bez cyklu życia – do potwierdzenia w pliku przeglądu
- Wydanie kolejnej wersji po v0.2.6 (2026-11-09) z budżetem miesięcznym (F48–F49, SPEC §49) po akceptacji zmian
```

Uwaga: numeru nowej wersji nie podaję, bo nie wynika z repo; „po v0.2.6” jest faktem z tagów. Jeśli właściciel chce
konkretny numer, trzeba go nadać przed wpisem.

**Liczba w B: 3 nieaktualne wpisy (B.1, B.2, B.3).**

---

## C. Czy HANDOFF.md i README.md wymagają wzmianki o F48/F49

### C.1. README.md — TAK, dwa miejsca

- `README.md:5` — „the [PRD](docs/PRD.md) (features F1–F39)”. To nieaktualny zakres: PRD ma już F40–F49
  (`docs/PRD.md:111-120`). Powinno być co najmniej „F1–F49”.
- `README.md:22` — „Monthly budget: one optional limit per phone for fuel and non-fuel expenses, with progress on the
  status screen counted only from the local archive (F48, SPEC §49); the threshold notification is a separate step.”
  Drugie zdanie jest nieaktualne: F49 jest zaimplementowane (`docs/PRD.md:120`, `ui/BudgetAlert.kt`,
  `notify/BudgetAlertNotifier.kt`). Wniosek: README wymaga wzmianki o F49, np. zastąpić końcówkę zdania opisem
  powiadomienia przy 80% i przy przekroczeniu limitu, raz na miesiąc i próg.

### C.2. docs/HANDOFF.md — TAK, co najmniej wzmianka/aktualizacja statusu

- `docs/HANDOFF.md:1` — „# Stan prac (2026-11-08)”; `:3` — „## Skrót – 2026-11-08”.
- `docs/HANDOFF.md:4` — wydania tylko „v0.2.0 (06.11), v0.2.1 i v0.2.2 (08.11)”, brak v0.2.3–v0.2.6.
- `docs/HANDOFF.md:6` — „PRD F1–F39 gotowe …; w toku: F40 … i ekrany stanu pojazdu, lokalizacji i stanu technicznego
  (SPEC §42–§44) – patrz ROADMAP „NOW””. To jest nieaktualne: F40–F43 są wydane w v0.2.3, a F48/F49 nie są w ogóle
  wymienione.
- `docs/HANDOFF.md:10` — „Przegląd jakości C22 … zostały drobne D2–D14”; część D jest zrobiona (commit `a982de8`:
  D3–D7, D12, D13).
- Wniosek: HANDOFF wymaga co najmniej wzmianki o F48/F49 i aktualizacji „w toku”; pełne odświeżenie HANDOFF
  (F44–F47 też go nie ma) wykracza poza C29, ale bez tego plik jest mylący. Propozycja minimalnego zdania do `Skrót`:
  „PRD F1–F49 gotowe (w tym F48 budżet i F49 powiadomienie o budżecie, SPEC §49); F40–F43 wydane w v0.2.3,
  v0.2.3–v0.2.6 na GitHub Releases.”

**Liczba w C: 3 miejsca w 2 plikach (README: 2, HANDOFF: 1).**

---

## D. Uwagi 1–17 z `briefs/toyota-companion/C28-review-agent2.md` – status

Legenda: **zamknięta** = wymaganie widać w kodzie/testach; **otwarta częściowo** = część wymagania nadal brak;
**otwarta** = wymaganie nadal niespełnione. Numery linii dotyczą aktualnego working tree.

1. **Zamknięta.** Karta poza `if (status != null)`: `ui/StatusScreen.kt:176` otwiera warunek, a `:190`
   `BudgetCard(budget) { editingBudget = true }` jest poza nim; komentarz `:188-189` mówi wprost
   „It does not depend on the Toyota status, so it stays visible when the status is missing”.
2. **Zamknięta.** Auto Backup/prywatność opisane: `docs/SECURITY.md:14` („limit w pliku `settings` – nie jest
   sekretem i trafia do Auto Backup …”), `docs/SPEC.md:1170-1172`; reguły kopii: `data_extraction_rules.xml:7,12`
   i `backup_rules.xml:7` zawierają `sharedpref/settings.xml`.
3. **Zamknięta.** Limit zaokrąglany przed sprawdzeniem i odrzucenie 0,004: `core/domain/BudgetOps.kt:32-37`
   (`require(limitPln.signum() > 0)`, `roundMoney`, `require(limit.signum() > 0)`), lustro
   `backend/app/domain/budget.py:47-58`; `settings/SettingsStore.kt:115-117` (`setScale(2, HALF_UP)`, `signum() <= 0`).
   Ujemne kwoty przed zaokrągleniem: `BudgetOps.kt:33,39`, `budget.py:50,53`; testy `BudgetOpsTest.kt:82,114`,
   `tests/test_domain_budget.py:69,96`.
4. **Zamknięta.** Powiadomienie doprecyzowane i zrobione: `docs/SPEC.md:1173-1184` (kanał, progi, klucz, trigger,
   reuse §13.2, treść), implementacja `ui/BudgetAlert.kt:26-37`, `notify/BudgetAlertNotifier.kt:79-100`,
   trigger `AppContainer.kt:375-377`, przełącznik `AppContainer.kt:607`; PRD F49 `docs/PRD.md:120`.
5. **Zamknięta.** Pozycja przeniesiona z LATER do DONE: `docs/ROADMAP.md:63-68` (F48 i F49 w DONE), w LATER
   (`:90-97`) nie ma już wpisu budżetowego; `git diff docs/ROADMAP.md` pokazuje usunięcie wpisu z LATER.
   Osobno: NOW/NEXT nadal nieaktualne – patrz B.
6. **Zamknięta.** Zakres: paliwo z tras wybranego auta po `costPln`/`hasNoCost` i starcie w miesiącu
   (`ui/Budget.kt:57-67`), wydatki po dacie (`:70-74`), zaokrąglenie sumy i procent HALF_UP
   (`BudgetOps.kt:44-52`), `overBudget = spent > limit` (dokładnie limit nie jest przekroczeniem), tylko PLN
   (brak kodu przeliczeń walut). Testy `BudgetTest.kt:71-113` i `BudgetOpsTest.kt:47-111`.
7. **Zamknięta.** Odświeżanie: `AppContainer.kt:375` (`budgetController.refresh()` po `SyncOutcome.Ok`),
   `ui/AppRoot.kt:160` (`ensureLoaded` po zalogowaniu), `:132` (`reset` przy wylogowaniu), `:174`
   (`refresh` przy wejściu na `Screen.Status`); opis północy/zmiany strefy w `docs/SPEC.md:1165-1167`.
8. **Zamknięta.** Błąd vs ładowanie: `ui/Budget.kt:23-34` ma `loading` i `failed`; `ui/BudgetCard.kt:37-79`
   pokazuje ostatnie dobre liczby z komunikatem błędu, a przy braku liczb osobny komunikat zamiast zer;
   `BudgetController.kt:76-113` zachowuje limit i ostatni dobry `progress` tego samego miesiąca; testy
   `BudgetControllerTest.kt:178-207`.
9. **Zamknięta.** PL/EN: `Texts.kt:788` `BUDGET_FIELD` = „Limit [zł]” / „Budget limit [zł]” (różne);
   `TextsTest.kt:647-648` tego pilnuje; nowe teksty są w sweepie `TextsTest.kt:282-304` i `:623-631`;
   `Lang.current` jest przywracany w `try/finally` (`TextsTest.kt:396-400` i dalej).
10. **Zamknięta (odczyt).** `backend/app/domain/__init__.py:3-4` — `from .budget` jest przed `from .cost`,
    więc I001 nie powinien wystąpić. **Nie uruchamiałem ruffa** (zasada tylko odczytu), więc to ocena z odczytu.
11. **Zamknięta przez testy (nie przez reużycie).** `monthFuelCost`/`monthExpenses` nadal mają własne filtrowanie
    (`ui/Budget.kt:57-74`), ale testy pilnują tej samej semantyki: `BudgetTest.kt:71-113` (`hasNoCost`, strefa,
    granica miesiąca, wydatki pierwszego/ostatniego dnia). Recenzja dopuszczała „reużyć albo testami pilnować”.
12. **Otwarta częściowo.** Jest granica miesiąca i DST wiosną: `BudgetTest.kt:85-92` (23:30 UTC 31.10 = 1.11
    w Warszawie), `:94-101` (DST wiosną 29.03), wydatki `:103-113` (1. i 31. dnia), `today` wstrzykiwane
    (`BudgetController.kt:41`, test `BudgetControllerTest.kt:93`). **Brak testu jesiennej zmiany czasu** –
    recenzja wymagała „DST wiosna/jesień”; tego nie znalazłem.
13. **Zamknięta (kod), z luką testową.** `ui/TripsGrouping.kt:36-37` — `hasNoCost(cost) = cost == null ||
    cost.costPln == null || cost.source == CostSource.NONE`; `Budget.kt:62-64` pomija `hasNoCost`, więc koszt 0,00
    jest wliczany. Test `BudgetTest.kt:71-82` pokrywa `cost == null`, `costPln == null` i `source == NONE`,
    ale **nie ma osobnego przypadku kosztu 0,00**; to luka testowa, nie błąd kodu.
14. **Zamknięta (kod), z luką testową.** `parseExpenseAmount`: `ui/Expenses.kt:51-55` — regex
    `\d{1,7}(\.\d{1,2})?` (1–7 cyfr, maks. 2 miejsca), puste/0/ujemne odrzucone. Test `BudgetTest.kt:43-68`
    pokrywa puste, przecinek/kropkę, 3 miejsca, zero/ujemne, tekst, NBSP, `1 234,50`, `1.234,56`, `1e3`
    i 8 cyfr (granica). **Brak osobnego testu dokładnie 7 cyfr**; recenzja wymieniała „7 cyfr”.
15. **Zamknięta.** Domena i lustro: `BudgetOpsTest.kt:47-118` oraz `tests/test_domain_budget.py:39-104`:
    ujemne `remaining`, procent 99,5→100 i 100,5→101, limit 0,01, dokładnie limit, przekroczenie o 0,01,
    limit 0,004 odrzucony, ujemne kwoty (także -0,004); nazwy testów w Kotlinie i Pythonie odpowiadają sobie
    (snake_case w obu).
16. **Otwarta częściowo.** Kontroler: `BudgetControllerTest.kt:100-315` pokrywa load, set, delete, invalid,
    błąd odczytu tras, reset + późny wynik, generation, limit przeżywa reset, `today`; SettingsStore:
    `SettingsStoreTest.kt:65-104` pokrywa round-trip, śmieci, zero/ujemne, usunięcie i zły typ dla
    `monthly_budget` (`:82-85`). **Ale** `ClassCastException` jest łapany tylko w `monthlyBudget()`
    (`SettingsStore.kt:105`); nowe klucze F49 `budgetAlert()` (`:129`, `getBoolean`) i `lastBudgetAlert()`
    (`:136`, `getString`) **nie mają catcha** i nie mają testu złego typu. Przy złym typie rzuci wyjątek,
    który wyżej złapie kontroler/notifier, ale samo wymaganie „dodać catch” nie jest spełnione dla tych kluczy.
17. **Otwarta.** Dokumentacja jest częściowo poprawiona, ale nie domknięta:
    - SPEC §12.2: `docs/SPEC.md:440` podaje poprawnie `budget_alert_last` – OK.
    - PRD F48: `docs/PRD.md:119` nadal „osobny krok” – A.2.
    - SPEC §49: `docs/SPEC.md:1174` zły klucz `last_budget_alert` – A.1; treść `:1181` nie wymienia
      „Zostało” – A.4; próg `:1173` niedoprecyzowany – A.5.
    - SECURITY: `docs/SECURITY.md:14-15` – OK.
    - ROADMAP LATER→DONE: `docs/ROADMAP.md:63-68` – OK; ale NOW/NEXT nieaktualne – B.
    - CHANGELOG Unreleased: `CHANGELOG.md:5` dodane, ale `:4` nadal „osobny krok” – A.3.
    - README: `README.md:22` nadal „separate step” i `:5` F1–F39 – C.
    - `C28-plan.md` jest w repo/commicie (`git ls-files` pokazuje `briefs/toyota-companion/C28-plan.md`);
      `C28-review-agent2.md` jest untracked (`git status`), więc nie został zacommitowany – ta część OK.
      `C29-plan.md` i `C29-review-agent2.md` są untracked; nie ruszałem ich.

**Liczba w D: 14 zamkniętych (1–11, 13–15), 2 otwarte częściowo (12, 16), 1 otwarta (17).**

---

## Podsumowanie liczb

- **A:** 4 rozbieżności (A.1–A.4); dodatkowo 2 nieścisłości/braki (A.5–A.6) i 1 brak wzmianki (A.7).
- **B:** 3 nieaktualne wpisy (B.1–B.3).
- **C:** 3 miejsca w 2 plikach (README 2, HANDOFF 1).
- **D:** 14 zamkniętych, 2 otwarte częściowo, 1 otwarta.

Czego **nie sprawdzono**: nie uruchamiałem Gradle, testów ani ruffa; nie sprawdzałem zachowania na urządzeniu;
nie sprawdzałem treści `briefs/toyota-companion/C22-quality-review.md`, bo pliku nie ma w repo; nie weryfikowałem
aktualności zadań właściciela z NOW (liveCheck, przejście debug→release). Nie zmieniałem kodu ani docs.
