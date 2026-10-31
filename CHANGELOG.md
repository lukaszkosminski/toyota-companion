# Changelog

## Unreleased
- Szkielet repozytorium i specyfikacja domeny (docs/SPEC.md)
- Logika domeny: wykrywanie tankowań, potwierdzanie ceny, koszt tras (76 testów)
- Dokumentacja procesu: PRD, ARCHITECTURE, DECISIONS, ROADMAP, SECURITY, DOD, AGENTS.md
- Narzędzia jakości backend: ruff (format+lint) + mypy strict dla app/domain, requirements-dev.txt z wersjami, CI GitHub Actions (ruff/mypy/pytest, akcje na SHA) + skan sekretów gitleaks
- Decyzja ADR-006: aplikacja tylko na telefonie, klient Toyoty w Kotlinie
- Projekt Android (AGP 9.4.1, Kotlin 2.4.21, Compose): moduł `core` z portem domeny (76 testów) + szkielet `app`
- CI: build i testy Androida
- Klient Toyoty w Kotlinie (`core/toyota`): logowanie OAuth, odświeżanie tokenu, ponowienia 429/5xx, pojazdy, telemetria, trasy z paginacją, mapowanie na domenę (33 testy, bez sieci)
- NOTICE (MIT pytoyoda), THIRD_PARTY_LICENSES.md, wyjątki gitleaks dla fikcyjnych JWT i publicznego klucza aplikacji
- ADR-005: koszt trasy = średnia ważona ceny paliwa w baku (43 l); `tank_price_after_refuel`/`tankPriceAfterRefuel`
- Domenowe pochodzenie litrów: `liters_source` (receipt/estimate/derived) i `filled_to_full`
- ADR-005 poprawka: średnia ważona liczona względem sumy wymieszanego paliwa (wynik zawsze między cenami); szacunek z czujnika i tankowanie z samą ceną aktualizują cenę w baku
- Kanoniczne `id` tankowania: UTC ISO-8601 z `Z` (Python = Kotlin `Instant.toString()`)
- Telemetria: poziom baterii i zasięg elektryczny; daty bez strefy interpretowane jako UTC
- Archiwum tras: DTO zachowuje współrzędne start/koniec, `scores`, `hdc`, `durationIdle`, `maxSpeed`, `nightTrip`; paginacja wykrywa pętlę i ujemny offset
- Synchronizacja w `core` (SPEC §8): interfejsy repozytoriów z testem kontraktowym, `TripRecord` z polami archiwum, `ToyotaGateway`, `SyncEngine` (okno 365 dni, zakładka 2 dni, deduplikacja, wykrywanie tankowań, przeliczenie kosztów, błędy bez utraty danych, jedna synchronizacja naraz)
- Android: uprawnienie `INTERNET`; `backend/pyproject.toml` bez nieużywanych zależności (pytoyoda, FastAPI, uvicorn)
- `:core:liveCheck` – ręczny test klienta na prawdziwym koncie MyToyota (dane logowania ze zmiennych środowiskowych, tylko odczyt, poza CI)
- Baza Room 3 w `app/data/db` (v1, schemat w `app/schemas`): trasy, tankowania, odczyty paliwa, koszty, pojazdy, stan synchronizacji; `Room*Repository`; `RepositoryContract` przeniesiony do `core/testFixtures` i uruchamiany na pamięci (core, 163 testy bez zmian) oraz na Room (app, Robolectric, 15 scenariuszy + 6 testów konwerterów/schematu)
- Bezpieczne przechowywanie sekretów (ADR-007): `SecretCipher`/`AndroidKeystoreCipher` (AES-256-GCM, Keystore), `EncryptedSecretStore`, `CredentialStore`, `KeystoreTokenStore`; synchronizacja w tle: `SyncWorker`, `SyncScheduler` (`toyota-sync` co 30 min, sieć, backoff 30 s), `AppContainer`/`ToyotaApp` z ręcznie wyłączonym inicjalizatorem WorkManagera; `ToyotaGatewayFactory` w core (app: 40 testów, core: 163)
- Weryfikacja loginu w `core`: `LoginCheck`/`LoginFailure`/`classifyLoginFailure` i `ToyotaGatewayFactory.verifyLogin` (tylko logowanie, świeży `InMemoryTokenStore`, bez `refresh_status`; 8 testów klasyfikacji błędów)
- Pierwszy UI (`app/ui`, SPEC §9): ekran logowania MyToyota i ekran statusu synchronizacji (`SessionController` ze `StateFlow` zamiast ViewModela, `LoginScreen`, `StatusScreen`, `AppRoot`, `Texts`); po sukcesie zapis danych i tokenu oraz `SyncScheduler.onLogin`; „Synchronizuj teraz” w procesie, „Wyloguj” czyści token i dane oraz anuluje `toyota-sync` i `toyota-sync-now`, archiwum Room zostaje; `AppContainer.onLoggedIn` usuwany (zastępuje go `SessionController`) (app: 64 testy, core: 171)
- Odświeżenie wyglądu UI (C1b): `ToyotaTheme` w Material 3 (jasny/ciemny wg systemu, dynamiczny kolor na Android 12+, poniżej paleta z czerwienią Toyoty), edge-to-edge (`enableEdgeToEdge`, przezroczyste paski w `themes.xml` + wariant nocny, paddingi z `Scaffold`, `imePadding`), ekran logowania jako wyśrodkowany formularz (max 480 dp, `OutlinedTextField`, autouzupełnianie przez `contentType`, przyciski tekstowe „Pokaż”/„Ukryj”, karta błędu w `errorContainer`), ekran statusu z `TopAppBar`, `PullToRefreshBox` (powtarza tylko odczyt lokalnej bazy – `SessionController.reload`, bez API Toyoty), karta liczników, chip stanu, `HorizontalDivider` i `AlertDialog` potwierdzającym „Wyloguj”; testy: `SessionControllerTest` +2 scenariusze odświeżania (app: 66 testów, core: 171)
- Lista tras z kosztem (SPEC §10): `SyncEngine.trips()` – trasy bieżącego VIN od najnowszej, koszty jednym odczytem `costs.all()` (bez zapytania na trasę), brak VIN = pusta lista, bez sieci i bez blokady synchronizacji; w `app` czyste funkcje `groupTripsByMonth` (miesiąc z `trip.start` w przekazanej strefie, sumy km/l/zł, licznik tras bez ceny, `totalCost = null` gdy brak ceny) i `TripsFormatting` (przecinek, `HALF_UP`, własna tablica 12 nazw miesięcy, start `29.10 14:05`, niezależne od `Locale`); ekran `TripsScreen` (`Scaffold` + `TopAppBar` z `TextButton` „Wstecz”, `BackHandler`, `LazyColumn` z kluczami `month-*`/`trip.id`, `contentPadding` z `innerPadding`, wiersz trasy pokazuje pod kosztem cenę za litr · a przy cenie domyślnej także „cena domyślna” · bez ceny tylko „brak ceny”, stany ładowanie/pusto/błąd bez treści wyjątku, odczyt na `Dispatchers.IO` z `produceState`, `CancellationException` przepuszczana), wejście przez przycisk „Trasy” na ekranie statusu, przełączanie ekranów enumem `Screen` w `rememberSaveable` (`LaunchedEffect` resetuje po wylogowaniu), `AppContainer.tripsLoader` jako jedyne wejście do bazy; brak GPS i VIN na ekranie; testy: core +7 (`trips_*`, w tym licznik zapytań kosztów na 500 trasach), app +23 (`TripsGroupingTest`, `TripsFormattingTest`, `TextsTest` objęło nowe teksty) (app: 89 testów, core: 178)
- Formularz ceny tankowania (SPEC §11): w `core` `pendingRefuels()`, `confirmRefuel(...)` i `dismissRefuel(id)`
  zwracają `RefuelChange` (`Done`, `NotFound`, `NotPending`, `Invalid`, `NoVehicle`); zapis i przeliczenie kosztów
  idą pod tym samym `mutex` co synchronizacja, ale przez `withLock`, więc potwierdzenie ceny czeka na pracującą
  synchronizację zamiast zostać pominięte (inaczej niż `fullSync` z `tryLock`); walidacja i wyliczenia wyłącznie
  przez domenowe `confirmRefuel`/`dismissRefuel` (§2, §3), `litersSource` nadal wyborem domeny; w `app` czyste
  `parseRefuelAmount` (przecinek albo kropka, puste = wielkość do wyliczenia) i `checkRefuelForm` (kombinacje z §2
  i znak wartości po polsku) oraz `RefuelFormatting` (czas ze strefą, `20% → 80%`, przebieg `12 345 km` ze spacją
  i `HALF_UP`, bez `Locale`); stan trzyma `RefuelController` na scope'ie aplikacji, żeby zapis przeżył wyjście z
  ekranu, z `reset()` i licznikiem generacji (późny wynik po wylogowaniu nie przywraca listy); ekrany „Tankowania”
  i „Cena tankowania” (`KeyboardType.Decimal`, wartości w `rememberSaveable`, „Do pełna” tylko przy litrach,
  `AlertDialog` przy odrzucaniu, błąd w karcie `errorContainer`); wejście z ekranu statusu przyciskiem „Podaj cenę
  tankowania (N)”, powrót z listy na status odświeża liczniki (`session.reload()` w callbacku nawigacji, nie w
  `LaunchedEffect`); enum `Screen` o `Refuels` i `RefuelForm`, id edytowanego wpisu w `rememberSaveable`;
  `Texts.TRIPS_BACK` zastąpione przez `Texts.BACK`; testy: core +14 (m.in. potwierdzenie samą ceną, litry i kwota,
  zła kombinacja, wpis innego VIN-u i czekanie na trwającą synchronizację), app +27 (`RefuelFormTest`,
  `RefuelFormattingTest`, `RefuelControllerTest`, `TextsTest`) (app: 116 testów, core: 192)
- `openForm(id)` czyta listę, gdy jej jeszcze nie ma albo odczyt był błędny – bez tego formularz po śmierci procesu czekałby w nieskończoność na „Ładowanie…”.
  `NotFound`, `NotPending` i `NoVehicle` przeładowują listę obok komunikatu, a wyjątek bazy ma własną treść (`Texts.REFUEL_SAVE_FAILED`). Przycisk „Podaj cenę tankowania (N)” nie jest blokowany na czas synchronizacji, bo potwierdzenie ceny czeka na `mutex` (SPEC §11.1).
- Domyślna cena paliwa jako ustawienie telefonu (SPEC §12, PRD F2b): `SyncEngine` dostaje
  `defaultPriceProvider: suspend () -> BigDecimal?` (domyślnie `config.defaultPrice`) i czyta go przy każdym
  `recomputeAndSave`, więc zmiana ceny jest widoczna także w synchronizacji w tle bez restartu aplikacji; nowa
  operacja `recomputeCostsNow(): CostRecompute` (`Done(tripsCosted)` / `NoVehicle`) liczy pod `mutex.withLock`, czyli
  czeka na trwającą synchronizację zamiast być pominięta — bez sieci i bez zmian w `SyncState`; `core/domain`,
  schemat Room i interfejsy repozytoriów bez zmian. W `app`: `settings/SettingsStore` (SharedPreferences `settings`,
  nie `secrets` — cena nie jest sekretem; tekst `toPlainString`, śmieci czytane jak brak ceny), `parseDefaultPrice`
  (przecinek albo kropka, puste pole to błąd, zaokrąglenie do 0,001 ROUND_HALF_UP jak ceny z §2 i dopiero potem sprawdzenie `<= 0` (wartość zaokrąglająca się do zera jest odrzucana),
  `DefaultPriceController` na scope'ie aplikacji (`reset()` i licznik generacji; wylogowanie nie czyści ceny, bo to
  ustawienie telefonu) oraz ekran „Cena domyślna” z jednym polem, „Zapisz”, „Usuń cenę domyślną” z `AlertDialog` i
  wejściem z ekranu statusu (`Texts.defaultPricePrompt` pokazuje zapisaną cenę); enum `Screen` o `DefaultPrice`;
  `NoVehicle` i samo nieudane przeliczenie nie cofają zapisanej ceny — przeliczy ją najbliższa synchronizacja.
  Testy: core +6 (cena z dostawcy czytana przy każdym przeliczeniu i raz na przeliczenie, potwierdzone tankowanie
  wygrywa z ceną domyślną, `fullSync` zna aktualną cenę, `recomputeCostsNow` czeka na trwającą synchronizację i
  zgłasza brak pojazdu), app +24 (`SettingsStoreTest`, `DefaultPriceFormTest`, `DefaultPriceControllerTest`,
  `TextsTest`) (app: 140 testów, core: 198)
- UI: pytanie o cenę zaraz po tankowaniu (`RefuelNotifier`, `RefuelPromptController`, SPEC §13): kanał
  „Tankowania” (`IMPORTANCE_HIGH`) melduje każde nowe tankowanie bez ceny — hak w dekoratorze `SyncRunner` w
  `AppContainer`, więc działa i w `SyncWorker`, i w „Synchronizuj teraz”. `PendingIntent` (`FLAG_IMMUTABLE`,
  `requestCode` = id powiadomienia `NOTIFICATION_ID_BASE + Math.floorMod(id.hashCode(), 100000)`) otwiera
  `MainActivity` (`launchMode="singleTop"`, `onNewIntent`) z extra `refuel_id`, a `AppRoot` idzie od razu na
  pełny formularz ceny z fokusem w polu (`showKeyboardOnFocus`). Zbiór powiadomionych id i flaga „pytaliśmy już o
  uprawnienie” trafia do `SettingsStore` (`refuel_notified_ids`, `notifications_asked`), a `cancel` kasuje
  powiadomienie, gdy wpis przestaje czekać (`RefuelController.onResolved` albo `RefuelPromptController`).
  W aplikacji okno `RefuelPromptDialog` dla najnowszego tankowania bez ceny: „Zapisz” przez
  `SyncEngine.confirmRefuel` (§11.2) i `session.reload()`, „Później” wycisza tylko ten wpis do końca procesu,
  „Więcej” otwiera formularz C3. Decyzję podejmuje czysta funkcja `refuelPromptTarget` (`RefuelPromptTest`), a
  stan trzyma `RefuelPromptController`: `priceText` przeżywa obrót ekranu, `reset()` przy wylogowaniu kasuje
  wyciszenie. Okno anuluje powiadomienie tego tankowania i nie pojawia się na liście tankowań ani na żadnym
  formularzu; `NotFound`/`NotPending` przechodzi do następnego wpisu bez błędu, błąd zapisu zostaje w oknie.
  Uprawnienie `POST_NOTIFICATIONS` (API 33+) pytane raz po zalogowaniu na ekranie statusu
  (`shouldAskNotificationPermission`, `rememberLauncherForActivityResult`), decyzja bez wartości
  domyślnej — `MainActivity` podaje `container.askNotificationPermission` przez `AppRoot` do `StatusScreen`;
  odmowa nic nie blokuje, a powiadomienie przyjdzie, gdy uprawnienie zostanie przyznane. Dodana ikona
  `res/drawable/ic_stat_refuel.xml` — aplikacja nadal nie ma ikony launchera (poza zakresem C5).
  Po przeglądzie: zimny start z powiadomienia czeka na wczytanie logowania (`LaunchedEffect(deepLinkRefuelId,
  loggedIn)`), `post()` zwraca `Boolean`, więc cofnięte w trakcie uprawnienie nie oznacza wpisu, `cancel` jest
  `suspend` i dotyka `SettingsStore` na `io`, zmiana ekranu decyduje z ostatniego odczytu (`screenChanged()`,
  `resolvedElsewhere(id)`) bez czytania bazy, a `NoVehicle` w formularzu C3 nie anuluje powiadomienia.
  Po teście na emulatorze: fokus i klawiatura formularza otwartego z powiadomienia przy zimnym starcie (efekt
  w gałęzi z polem, nie przed „Ładowanie…”), flaga wejścia z powiadomienia w `remember` (obrót nie ściąga kursora
  z powrotem do ceny), marginesy linii z datą i karty błędu w formularzu C3.
  `core` bez zmian; testy: app +41 (`RefuelNotifierTest` z Robolectric, `NotificationPermissionTest`,
  `RefuelPromptTest`, `RefuelPromptControllerTest`, `SettingsStoreTest`, `TextsTest`, `RefuelControllerTest`)
  (app: 181 testów, core: 198)
- Domena: szacunek litrów z czujnika (PRD F2a, SPEC §2a) — `estimate_refuel_liters` / `estimateRefuelLiters`
  (`(poziom po − poziom przed) % × 43 l`, 0,001 l `HALF_UP`, `None` bez skoku, przy poziomach poza zakresem albo
  wyniku zaokrąglonym do 0); §6 liczy szacunek tą samą funkcją, więc przyjęty szacunek daje tę samą cenę w baku co
  sama cena. `SyncEngine.confirmRefuel` przyjmuje opcjonalne `litersSource` (UI poda `ESTIMATE` tylko po jawnym
  przyjęciu szacunku); walidacja nadal wyłącznie w domenie. SPEC §2: kwota przy `ESTIMATE` jest szacunkowa, a F9
  bierze tylko `RECEIPT`. Testy: Python +8, core +10 (8 w `RefuelOpsTest` o nazwach jak w Pythonie, 2 w
  `SyncEngineTest`) (Python: 84, core: 208)
- UI: szacunek litrów w formularzu ceny (PRD F2a, SPEC §11.2, §11.3): wiersz „Szacunek z czujnika: ok. N l” z „Użyj
  szacunku”, po przyjęciu „Litry z szacunku czujnika” i „Z paragonu”; litry w pełnej precyzji domeny
  (`RefuelFormatting.litersInput`); `checkRefuelForm(..., litersFromEstimate)` daje `ESTIMATE` tylko dla pary cena +
  litry, a szacunek z kwotą bez ceny to błąd pola „Cena”. Okno „Wykryto tankowanie” bez zmian. Testy: app +12
  (`RefuelFormTest`, `RefuelFormattingTest`, `TextsTest`, `RefuelControllerTest`) (app: 193 testy, core: 208, Python: 84)
- Odporność na błędy lokalnego archiwum (SPEC §8.4, §8.5, §9.3): nowy rodzaj błędu `STORAGE` (ponawiany);
  `SyncEngine.fullSync`/`recomputeCosts` zamieniają każdy wyjątek bazy na `Failed(STORAGE)` z samą nazwą klasy
  w komunikacie, zapis błędu w stanie jest odporny na zepsutą bazę, a błąd zapisu odczytu albo wykrytego tankowania
  nie udaje już „telemetria niedostępna” (tankowanie nie przepada za wynikiem `Ok`). „Synchronizuj teraz” nie
  zostaje w stanie „synchronizuję”, „Wyloguj” zawsze kończy na logowaniu, `uiScope` ma `CoroutineExceptionHandler`
  (log tylko z nazwą klasy), nieznany rodzaj błędu w bazie czyta się jako brak. Testy: core +8, app +3
  (app: 196 testów, core: 216, Python: 84)
- Kopia archiwum (PRD F7, F8, SPEC §14): eksport całego archiwum (pojazdy, trasy z GPS, tankowania, odczyty paliwa)
  do pliku JSON przez systemowy wybór pliku, po ostrzeżeniu o GPS i VIN; import bez duplikatów (trasy i tankowania po
  `id`, odczyty po `(vin, ts)`, pojazdy po VIN), tankowanie oczekujące w telefonie przejmuje cenę z pliku, odrzucone
  zostaje odrzucone i jest liczone jako pominięte; koszty przeliczane po imporcie. Format w `core/data/ArchiveFile.kt`
  (koperta z `format`/`version`, własne serializery `Instant`/`BigDecimal`, `Unsupported`/`Corrupt` zamiast wyjątków),
  limit importu 20 MB. Na statusie przycisk „Kopia archiwum”, data ostatniej kopii i przypomnienie, gdy kopii nie było
  albo minęło > 30 dni (`backupReminder`, `SettingsStore.lastArchiveExport`). Testy: core +23 (`ArchiveFileTest`,
  `SyncEngineArchiveTest`), app +30 (`BackupControllerTest` bez Robolectric, `BackupRulesTest`, `SettingsStoreTest`,
  `TextsTest`) (app: 226 testów, core: 239, Python: 84)
- Arkusz tras w CSV (PRD F6, SPEC §14.4): trasy wybranego pojazdu z kosztem dla polskiego arkusza – `;`, przecinek,
  UTF-8 z BOM, CRLF, od najstarszej; kolumny bez jednostek (Start, Koniec, Dystans, Paliwo, Dystans EV, Cena z 3
  miejscami, Koszt, Źródło ceny), puste komórki ceny dokładnie przy „brak ceny” na liście tras, zaokrąglenie wspólne
  z listą (`TripsFormatting.plain`), cytowanie RFC 4180 i ochrona przed CSV injection; bez GPS, VIN i sum. Ekran
  i przycisk na statusie nazywają się „Kopia i eksport”; przycisk CSV nieaktywny bez tras; eksport CSV nie zmienia
  daty kopii archiwum. Testy: app +24 (`TripsCsvTest`, `BackupControllerTest`, `TripsFormattingTest`, `TextsTest`)
  (app: 250 testów, core: 239, Python: 84)
- Dziennik tankowań i spalanie „komputer vs faktyczne” (PRD F9, SPEC §15): domena `fuel_economy_intervals` w Pythonie
  i Kotlinie (te same 26 testów) – odcinki od tankowania do pełna do tankowania do pełna, paliwo faktyczne z litrów
  z paragonu (`receipt` albo `derived` – decyzja właściciela), dystans i paliwo komputera z tych samych tras, statusy
  braku porównania (czeka na cenę, odrzucone, brak paragonu, brak tras, odcinek < 100 km), nowe zaokrąglenia
  `round_consumption`/`round_percent`/`round_distance`. `SyncEngine.refuelLog()`, ekran „Dziennik tankowań” ze statusu
  (karta spalania pod tankowaniem kończącym odcinek, bieżący odcinek, licznik informacyjnie, „w tym EV”). Testy:
  Python +26, core +31, app +20 (app: 270 testów, core: 270, Python: 110)
