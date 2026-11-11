# C29 – recenzja zmiany niezacommitowanej (agent 2, read-only)

Data: 2026-11-10. Zakres: `git status` + `git diff` oraz nowe pliki `notify/BudgetAlertNotifier.kt`, `ui/BudgetAlert.kt`,
`notify/BudgetAlertTest.kt`, `ui/BudgetAlertTest.kt`. Plan: `C29-plan.md`. Nic nie uruchamiałem (ani Gradle, ani testów) –
wszystko poniżej to czytanie kodu. Oznaczenia: **[K]** potwierdzone w kodzie, **[P]** przypuszczenie / skutek wynikający
z kodu, ale niezweryfikowany uruchomieniem. Numery linii dotyczą stanu working tree (ścieżki skrócone do `app/src/main/kotlin/pl/lk/toyotacompanion/`).

Werdykt: logika klucza `RRRR-MM:80|100`, kolejność „klucz po udanym post”, wyciszony kanał, brak uprawnienia, brak
sieci i brak VIN/GPS są zrobione poprawnie i zgodnie ze wzorcem `ConsumptionAlertNotifier`. Są jednak dwa realne błędy
(fałszywe „przekroczono” oraz wyścig dwóch triggerów) i dwa problemy wątkowe, które warto naprawić przed commitem.

## Blokujące / ważne

### 1. Próg 100% włącza się przy `percentUsed >= 100`, a nie przy faktycznym przekroczeniu → fałszywe „Budżet przekroczony” **[K]**
- `ui/BudgetAlert.kt:27-31` (`budgetAlert`): `progress.percentUsed >= 100` → próg 100. `percentUsed` jest zaokrąglany
  HALF_UP do całych procent (`core/domain/BudgetOps.kt:44`), a `overBudget = spent > limit` (`BudgetOps.kt:52`).
- Scenariusze (limit 800 zł):
  - wydano 799,60 → 99,95% → `percentUsed = 100`, `overBudget = false`, `remainingPln = +0,40`. Powiadomienie: tytuł
    „Budżet miesiąca przekroczony”, treść „… (100%), przekroczono o 0,40 zł.” (`ui/BudgetAlert.kt:60-70`,
    `Texts.kt:824` + `.abs()` na `remainingPln`). Informacja jest fałszywa – karta budżetu w tym samym momencie pokazuje
    „Zostało: 0,40 zł”.
  - wydano dokładnie 800,00 → `overBudget = false`, treść „przekroczono o 0,00 zł”. Test `the_limit_and_over_are_the_hundred_percent_alert`
    (`ui/BudgetAlertTest.kt:53-57`) wręcz utrwala ten przypadek jako alert „przekroczenia”.
  - skutek uboczny: skoro klucz `:100` zostaje zapisany, prawdziwe przekroczenie (800,01) tego samego miesiąca jest już ciszą.
- Poprawka: rozdzielić „próg” od „treści”. Najprościej: próg 100 gdy `progress.overBudget`; w zakresie `percentUsed >= 100 && !overBudget`
  zostać na progu 80 z osobnym tekstem „limit wykorzystany” (albo dodać trzeci wariant treści bez słowa „przekroczono”).
  Zmienić test na 800,00 → NIE „przekroczenie”, dopisać 799,60 i 800,01. Zaktualizować plan §2 / SPEC §49 („przekroczenie 100%” = `overBudget`).
- Powiązane z 80%: 636,00/800 = 79,5% → `percentUsed = 80` → ostrzeżenie „(80%)” już przy 79,5%. To jest zgodne z planem
  („cały procent HALF_UP”), więc nie błąd – tylko warto to wprost zapisać w SPEC.

### 2. Dwa triggery naraz nie są serializowane: duplikat powiadomienia i „cofnięcie” klucza z 100 na 80 **[K kod / P skutek]**
- `notify/BudgetAlertNotifier.kt:72-88` (`check`): odczyt `lastAlertKey` → `canPost` → `post` → `markAlerted` bez muteksu.
  Wołają go równolegle: wątek `SyncWorker`/`syncNow` (`AppContainer.kt:377`) i Main przez `ExpensesController.onChanged` (`AppContainer.kt:120`).
- Scenariusz A (duplikat): oba odczytują `last = null` przy 85% → oba robią `notify(901)`; drugie `notify` z tym samym id
  przy `IMPORTANCE_DEFAULT` znów dzwoni/wibruje (brak `setOnlyAlertOnce`).
- Scenariusz B (klucz cofnięty): sync czyta trasy (80%), użytkownik dodaje wydatek, który daje 105%; wątek wydatku posta 100
  i zapisuje `:100`, a wolniejszy wątek sync dopiero potem robi `notify` z treścią 80 (nadpisuje na ekranie powiadomienie 100) i
  `markAlerted("…:80")` – klucz spada z `:100` do `:80`, więc następny trigger znów powiadomi 100 (duplikat).
- Poprawka: `private val lock = Mutex()` w notifierze i `lock.withLock { … }` wokół całego `check()`, z odczytem `lastAlertKey`
  **wewnątrz** blokady (nie przed nią). Dodatkowo `markAlerted` powinien nigdy nie obniżać (porównać z aktualnym kluczem
  – jest już w `budgetAlert()`, więc wystarczy lock). Test: dwa równoległe `async { n.onExpensesChanged() }`/`onSyncOutcome`
  z `StandardTestDispatcher` i bramką w `readTrips` → `posted().size == 1` i `last == "…:100"`.

### 3. `post()`, `canPost()` i odczyt ustawień na wątku Main, w dodatku pod muteksem kolejki wydatków **[K kod / P skutek]**
- `ExpensesController.kt:59-82`: `onChanged()` jest wołane w `queue.withLock` w `scope` = `Dispatchers.Main.immediate`.
  W `check()` tylko odczyty (`readLimit`, `readTrips`/`readExpenses`, `lastAlertKey`, `markAlerted`) idą przez `withContext(io)`;
  `canPost()` (`BudgetAlertNotifier.kt:81`, `:95`), `NotificationManagerCompat.createNotificationChannel`, `getNotificationChannel`
  i `notify` (`:96-112`) wykonują się na Main. W `AppContainer.kt:605-611` `canPost` czyta `settingsStore.budgetAlert()`
  (pierwszy odczyt SharedPreferences = I/O z dysku na Main, ostrzeżenie StrictMode) i wywołuje `areNotificationsEnabled()` (binder).
  `ConsumptionAlertNotifier` ma ten sam kształt, ale jest wołany tylko z wątku synchronizacji – tu ścieżka wydatków jest nowa.
- Skutek: pod mutexem kolejki każde dodanie/usunięcie wydatku czeka na pełny odczyt tras (`syncEngine.trips()`) i binder
  notyfikacji; szybka seria usunięć = N pełnych odczytów archiwum po kolei, a następne zapisy stoją w kolejce.
- Poprawka: (a) cały `post`+`canPost` przenieść do `withContext(io)` w `check()`; (b) w `ExpensesController` wołać `onChanged`
  poza `queue.withLock` (np. `scope.launch { onChanged() }` po zwolnieniu blokady), żeby kolejka zapisów nie czekała na budżet.
  Test: `onChanged` z długą bramką nie blokuje kolejnego `add`.

### 4. `BudgetController.refresh()` wołane z wątku roboczego modyfikuje `generation` bez synchronizacji **[K]**
- `ui/BudgetController.kt:55` `val token = ++generation` – nowy zapis; przed zmianą `refresh()` tylko czytał `generation`.
  `refresh()` jest wołane z `syncRunner` (`AppContainer.kt:375`, wątek WorkManagera / `syncNow`), a `reset()`/`set()`/`refresh()`
  z Main. `++` na zwykłym `var` to read-modify-write: równoległy `reset()` (wylogowanie) albo `refresh()` z wejścia na Status
  może zgubić inkrementację, więc stary odczyt przejdzie test `token != generation` i nadpisze stan po wylogowaniu
  (karta pokaże dane poprzedniego konta po `reset()`).
- Poprawka: `private val generation = AtomicInteger()` (`incrementAndGet`) albo cały początek `refresh()` (`loadStarted`, token, `ui.value =`)
  wykonywać w `scope.launch` (Main), albo `@Volatile` + `synchronized`. Test: wołanie `refresh()` z innego dyspozytora w trakcie `reset()`.

### 5. `setAlert()` może zostać cofnięty przez wolniejszy `refresh()` **[K]**
- `ui/BudgetController.kt:70` i `:85-91`: `alertEnabled` jest czytane z magazynu w `withContext(io)` przed zapisem stanu, a
  `setAlert` (`:124-127`) nie podbija `generation` (w przeciwieństwie do `reset`).
- Scenariusz: trwa odświeżenie po synchronizacji (czyta `alert = true`), użytkownik w Opcjach wyłącza przełącznik → `saveAlert(false)`,
  `ui.alertEnabled = false`; wolniejszy `refresh` kończy się z tokenem nadal aktualnym i ustawia `alertEnabled = true`. Przełącznik
  w UI wraca na „włączony”, a magazyn ma `false`; `canPost()` faktycznie nie powiadomi, ale UI kłamie (i kolejny tap „wyłącz” zrobi „włącz”).
- Poprawka: w `setAlert` zrobić `generation++` (albo `AtomicInteger`), lub w `refresh()` nie nadpisywać `alertEnabled` z odczytu
  (`ui.value = next.copy(alertEnabled = ui.value.alertEnabled)` przy przypisywaniu). Test: bramka w `readTrips`, `setAlert(false)` w trakcie, po zwolnieniu `alertEnabled == false`.

## Drobne / testy

6. **Dwa wywołania `today()` i brak strefy [K]** – `notify/BudgetAlertNotifier.kt:74`: `MonthKey(today().year, today().monthValue)`; o północy
   na styku miesięcy rok z jednego, miesiąc z drugiego wywołania (np. 2026-12-31 → rok 2026, miesiąc 1 = „2026-01”).
   Poprawka: `val d = today(); MonthKey(d.year, d.monthValue)`. Domyślne `today` = `LocalDate.now()` nie używa wstrzykniętego `zone()`;
   lepiej `LocalDate.now(zone())` (zgodne z `BudgetController`, jeśli i tam to ujednolicić).
7. **`onChanged` po wylogowaniu [P]** – `ExpensesController.kt:71-79` nie sprawdza `generation == startedIn`. Zapis zakolejkowany tuż
   przed wylogowaniem po `reset()` jeszcze wywoła `check()`; limit przeżywa wylogowanie, więc jeśli archiwum nie zostało wyczyszczone, powiadomienie może wyjść po wylogowaniu.
   Poprawka: `if (generation == startedIn) onChanged()`; test z `reset()` między zapisem a callbackiem.
8. **Podniesienie/obniżenie limitu w tym samym miesiącu [K kod / decyzja]** – klucz jest niezależny od limitu. Limit 800 → alert 100 → użytkownik
   podnosi limit do 1500, wydaje 1300 (87%) albo 1600 – cisza do końca miesiąca. Plan mówi „zmiana limitu to nie trigger” i „raz na miesiąc”,
   więc to zgodne, ale warto jedno zdanie w SPEC §49 (albo zerować klucz przy `BudgetController.set`).
9. **Treść na ekranie blokady [P]** – kwoty wydatków widać bez odblokowania. Dla `NotificationCompat.Builder` rozważyć
   `setVisibility(VISIBILITY_PRIVATE)` + `setPublicVersion` z samym tytułem. Wzorzec spalania tego nie robi, ale kwoty są wrażliwsze niż sama informacja o spalaniu.
10. **Kosmetyka powiadomienia [K]** – `ic_stat_refuel` (dystrybutor paliwa) dla budżetu (`BudgetAlertNotifier.kt:106`); brak `contentIntent` – dotknięcie
    powiadomienia nic nie robi (wzorzec spalania też; `setAutoCancel` bez intentu jest martwe). Rozważyć intent do Status.
11. **KDoc vs domyślne `canPost` [K]** – `BudgetAlertNotifier.kt:46` mówi „… AND the owner's budget-alert switch”, ale domyślne
    `canPost` (`:58-60`) nie zawiera przełącznika; robi to dopiero `AppContainer.kt:605-611`. Dodatkowo `canPost()` jest oceniane
    dwa razy (`:81` i `:95`). Albo przenieść przełącznik do notifiera (`alertEnabled: () -> Boolean`), albo poprawić KDoc; logika
    „przełącznik wyłączony = brak powiadomienia i brak klucza” jest w `AppContainer` całkowicie bez testu.
12. **Luki w testach względem planu §4 [K]**
    - `notify/BudgetAlertTest`: plan wymaga „tytuł i treść PL”, test sprawdza tylko tytuł; brak asercji na `contentText`/`bigText`
      i brak testu `the_alert_texts…` na poziomie notyfikacji (VIN w ładunku: `trip()` ma `vin = "VINAAA111"` – wystarczy `assertFalse("VINAAA111" in text)`).
    - brak: 100 po 100 cisza i 80 po 100 cisza **w notifierze** (usunięcie wydatku po przekroczeniu), `post()` zwracające `false` bez zapisu klucza
      poza kanałem wyciszonym (np. `canPost` zmienia się między `check` a `post`), przypadek granicznych 799,60/800,00/800,01 (pkt 1),
      test wyścigu (pkt 2), brak testu odblokowania wyciszonego kanału (po odciszeniu powiadomienie dochodzi).
    - brak testu przełącznika `budget_alert` po stronie `AppContainer`/notifiera (pkt 11) i testu, że błąd odczytu wydatków (a nie tras) też nie psuje (`readExpenses` rzuca).
    - `SettingsStoreTest`: plan §4 wspomina „śmieci” dla `last_budget_alert` – jest tylko round-trip (śmieci pokryte po stronie `parseBudgetAlertKey`, OK, ale `lastBudgetAlert()` z nie-stringiem w prefs nie jest sprawdzone; `getString` na innym typie rzuci `ClassCastException` – złapane dopiero w `check()`).
    - `ExpensesControllerTest`: brak testu, że wyjątek z `onChanged` nie psuje zapisu, oraz że po `reset()` nie jest wołany (pkt 7).
    - `BudgetControllerTest`: brak testu pkt 4/5.
13. **Jakość kodu [K]** – `ui/BudgetAlertTest.kt:236-238` komentarz „639.99 would round to 80 and is not ‘below’” – prawdziwy, ale nie ma testu, który to utrwala
    (639,99 → ostrzeżenie); dodać asercję, żeby reguła „HALF_UP” była świadoma. Nazwy testów mieszają `snake_case` i zdania w backtickach – tak jest w repo, bez uwag.

## Sprawdzone i w porządku (potwierdzone w kodzie)
- Logika klucza (`ui/BudgetAlert.kt:26-48`): `last.thresholdPct >= threshold` dla tego samego miesiąca → 80 po 100 cisza, 100 po 80 alert,
  nowy miesiąc od nowa, śmieci = „nic nie powiedziano”. Klucz zapisywany dopiero po `post()==true` (`BudgetAlertNotifier.kt:82`);
  wyciszony kanał (`:101`) i brak uprawnienia (`:81`) nie zużywają progu. Brak sieci i VIN/GPS w treści; brak nowego uprawnienia i nowej prośby.
- Błędy odczytu tras/wydatków → `check()` połyka wyjątek i niczego nie oznacza; trigger z `ExpensesController` tylko po udanym zapisie
  (`write()` kończy `return@withLock` przy wyjątku) i błąd `onChanged` nie psuje zapisu. Trigger w `syncRunner` po `SyncOutcome.Ok`, po `budgetController.refresh()`.
- Teksty PL/EN: miesiąc jest lokalizowany (`TripsFormatting.monthLabel` używa `Lang`), odmiana „przekroczono o X zł” poprawna (nie wymaga liczebnika).
- Wylogowanie: `budget.reset()`/`expenses.reset()` zerują kontrolery; przełącznik i klucz zostają w `settings` zgodnie z ADR-006 i SPEC.
