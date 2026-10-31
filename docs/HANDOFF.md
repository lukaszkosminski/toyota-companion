# Stan prac (2026-10-30)

## Gotowe
- Logika domeny (tankowania, koszt tras, ADR-005): Python `backend/` (specyfikacja, 76 testów) + Kotlin `android/core/.../domain` (76 testów, te same nazwy).
- Klient Toyoty w Kotlinie `android/core/.../toyota` (port pytoyoda v6.0.0); `:core:liveCheck` do testu na prawdziwym koncie.
- Synchronizacja `android/core/.../sync` (SPEC §8) + repozytoria `core/data` z kontraktem w `core/testFixtures`.
- `android/app`: baza Room 3 (v1), szyfrowane dane logowania i tokeny (Keystore, ADR-007), `SyncWorker` co 30 min.
- UI (SPEC §9): ekran logowania MyToyota + ekran statusu synchronizacji; `SessionController` ze `StateFlow` (bez ViewModela – `lifecycle-viewmodel-compose` nie jest zależnością), `CredentialVerifier` jako seam weryfikacji, hasło tylko w pamięci ekranu. Wylogowanie czyści token i dane oraz anuluje prace `toyota-sync`/`toyota-sync-now`, archiwum Room zostaje.
- Weryfikacja loginu w `core`: `LoginCheck` + `ToyotaGatewayFactory.verifyLogin` (świeży `InMemoryTokenStore`, bez `refresh_status`). Komunikat `INVALID_CREDENTIALS`: „Toyota odrzuciła logowanie – sprawdź login i hasło” (5xx z AMI też tu trafia).
- Wygląd (C1b, commit feat(app): Material 3 look, edge-to-edge, pull-to-refresh): `ToyotaTheme` – Material 3, jasny/ciemny wg systemu,
  dynamiczny kolor na Android 12+, poniżej własna paleta z czerwienią Toyoty; edge-to-edge (`enableEdgeToEdge`,
  przezroczyste paski w `res/values{,-night}/themes.xml`, paddingi z `Scaffold`, `imePadding`); formularz logowania
  wyśrodkowany (max 480 dp), `OutlinedTextField` + autouzupełnianie (`contentType`), tekstowy „Pokaż”/„Ukryj”,
  karta błędu `errorContainer`; status z `TopAppBar`, `PullToRefreshBox`, kartą liczników, chipem stanu,
  `HorizontalDivider` i `AlertDialog` potwierdzającym „Wyloguj”. `SessionController.reload()` powtarza tylko
  odczyt lokalnej bazy (bez API Toyoty i bez `refresh_status`).
- Testy: core 305, app 323 (JUnit 4, w tym Robolectric), Python 132. CI: ruff/mypy/pytest, Android (core + app unit), gitleaks 8.30.1.
- Lista tras z kosztem (SPEC §10): `SyncEngine.trips()` — trasy bieżącego VIN od najnowszej, wszystkie koszty jednym odczytem `costs.all()` (bez zapytania na trasę), brak VIN = pusta lista, bez sieci i bez `mutex` synchronizacji; w `app` czyste funkcje `groupTripsByMonth` (miesiąc z `trip.start` w przekazanej strefie, sumy km/l/zł, licznik tras bez ceny, `totalCost = null` gdy żadna trasa nie ma ceny) i `TripsFormatting` (przecinek, `HALF_UP`, własna tablica 12 nazw miesięcy w mianowniku, start `29.10 14:05`, formaty niezależne od `Locale`); ekran `TripsScreen` (nazwa + sumy miesiąca, wiersze z `key = trip.id`, cena za litr · a przy „cena domyślna” także dopisek w tej samej linii, `contentPadding` z `innerPadding`, stany ładowanie/pusto/błąd bez treści wyjątku, `TextButton` „Wstecz” + `BackHandler`) i przycisk „Trasy” na ekranie statusu; `AppContainer.tripsLoader` jako jedyne wejście do bazy, `AppRoot` przełącza ekrany enumem `Screen` w `rememberSaveable` (reset do statusu po wylogowaniu przez `LaunchedEffect`). Na ekranie brak współrzędnych GPS i VIN (SECURITY.md).
- Formularz ceny tankowania (SPEC §11): `pendingRefuels()`, `confirmRefuel(...)` i `dismissRefuel(id)` w `SyncEngine`
  zwracają `RefuelChange` (`Done`, `NotFound`, `NotPending`, `Invalid`, `NoVehicle`). Zapis i przeliczenie kosztów idą
  pod tym samym `mutex` co synchronizacja, ale przez `withLock`, więc potwierdzenie ceny czeka na pracującą
  synchronizację zamiast zostać pominięte. Wpis innego VIN-u jest `NotFound`, brak VIN-u to `NoVehicle`; domena
  (`core/domain`) pozostaje jedynym źródłem wyliczeń i wyboru `litersSource` (od F2a UI podaje tylko `ESTIMATE` po
  przyjęciu szacunku, §2a). W `app`: `parseRefuelAmount` i
  `checkRefuelForm` (przecinek albo kropka, puste = wielkość do wyliczenia, kombinacje z §2 z polskimi komunikatami),
  `RefuelFormatting` (`29.10 14:05`, `20% → 80%`, `12 345 km` ze spacją i `HALF_UP`, bez `Locale`), oraz
  `RefuelController` na scope'ie aplikacji (zapis przeżywa wyjście z ekranu) z `reset()` i licznikiem generacji.
  `openForm(id)` czyta listę, gdy jej jeszcze nie ma albo odczyt był błędny – bez tego formularz po śmierci procesu czekałby w nieskończoność na „Ładowanie…”. `NotFound`/`NotPending`/`NoVehicle` przeładowują listę obok komunikatu, a wyjątek bazy ma własną treść (`Texts.REFUEL_SAVE_FAILED`). Przycisk „Podaj cenę tankowania (N)” nie jest blokowany na czas synchronizacji (SPEC §11.1).
  Ekrany „Tankowania” i „Cena tankowania”, wejście z statusu przyciskiem „Podaj cenę tankowania (N)”, powrót z listy
  odświeża liczniki (`session.reload()` w callbacku nawigacji). `Texts.TRIPS_BACK` zastąpione przez `Texts.BACK`.
- Domyślna cena paliwa (SPEC §12, PRD F2b): `SyncEngine` dostaje `defaultPriceProvider: suspend () -> BigDecimal?`
  (domyślnie `config.defaultPrice`) i czyta go przy każdym `recomputeAndSave`, więc zmiana ceny działa też w pracy
  w tle bez restartu; `recomputeCostsNow(): CostRecompute` (`Done`, `NoVehicle`) liczy pod `mutex.withLock`, czyli
  czeka na trwającą synchronizację, bez sieci i bez zmian w `SyncState`; `core/domain`, Room i repozytoria bez zmian.
  W `app`: `settings/SettingsStore` (SharedPreferences `settings`, nie `secrets` — cena nie jest sekretem; tekst
  `toPlainString`, śmieci czytane jak brak ceny), `parseDefaultPrice` (przecinek albo kropka, puste pole to błąd,
  `<= 0` to błąd, zaokrąglenie do 0,001 HALF_UP), `DefaultPriceController` na scope'ie aplikacji (`reset()` i licznik
  generacji) oraz ekran „Cena domyślna” z jednym polem, „Zapisz” i „Usuń cenę domyślną” z `AlertDialog`; wejście z
  ekranu statusu przyciskiem pokazującym zapisaną cenę (`Texts.defaultPricePrompt`). **Cena przeżywa wylogowanie**
  (ustawienie telefonu, nie konta — ADR-006); odczyt i zapis preferencji idą z `Dispatchers.IO`, nie z wątku UI.
- Pytanie o cenę zaraz po tankowaniu (SPEC §13, PRD F2c): `notify/RefuelNotifier` woła dekorator `SyncRunner` w
  `AppContainer`, więc powiadamia kanałem „Tankowania” (`IMPORTANCE_HIGH`) po każdej synchronizacji, która
  wykryła tankowanie — `SyncWorker` i „Synchronizuj teraz” są tym samym traktowane. Id powiadomienia
  `NOTIFICATION_ID_BASE + Math.floorMod(id.hashCode(), 100000)` (`hashCode` bywa ujemny) jest również
  `requestCode` `PendingIntent`, który otwiera `MainActivity` (`launchMode="singleTop"` + `onNewIntent`, extra
  `refuel_id`) i prowadzi prosto na pełny formularz ceny z fokusem w polu (`showKeyboardOnFocus`: fokus i
  `LocalSoftwareKeyboardController.current?.show()` po pierwszej narysowanej klatce (`withFrameNanos`),
  z okna samego dialogu, nie z aktywności). Zbiór powiadomionych id i flaga
  „pytaliśmy już o uprawnienie” trzymamy w `SettingsStore` (plik jawny, to nie sekrety), więc powtórzona
  synchronizacja nie melduje ponownie, a śmierć procesu nie kasuje tego, co już wyszło. `cancel` przy
  `Confirmed`/`Rejected` (`RefuelController.onResolved`) i przy pokazaniu okna — okno i powiadomienie nigdy nie
  pytają o to samo. Okno: `RefuelPrompt.refuelPromptTarget` (czysta funkcja) + `RefuelPromptController` +
  `RefuelPromptDialog`; „Później” wycisza TYLKO dany wpis do końca procesu (wylogowanie kasuje), więc nowe
  tankowanie pytane jest nadal — to sedno F2c. `refresh()` dekoratora po `Ok && refuelsDetected > 0` pokazuje okno
  nawet gdy aplikacja stoi otworem, a `ensureLoaded()` czyta raz na logowanie (jak §12.3). Uprawnienie
  `POST_NOTIFICATIONS` (API 33+) pytane raz na ekranie statusu (`shouldAskNotificationPermission`, flaga w
  `SettingsStore`; decyzja bez wartości domyślnej, `MainActivity` podaje
  `container.askNotificationPermission` przez `AppRoot` do `StatusScreen`); po odmowie aplikacja działa jak
  wcześniej (okno + przyciski). Bez VIN, GPS i przebiegu w
  treści powiadomienia (`Texts.refuelNotificationText`, `RefuelFormatting.odometer` nie jest użyty). Dodano
  `res/drawable/ic_stat_refuel.xml` (od C10 aplikacja ma ikonę launchera: `mipmap-anydpi-v26/ic_launcher*`; wcześniej poza
  zakresem C5). Po przeglądzie: deep link przy zimnym starcie czeka na `loggedIn`, `post()` zwraca `Boolean`,
  `suspend cancel` z `SettingsStore` na `io`, `screenChanged()`/`resolvedElsewhere(id)` zamiast odczytu bazy przy
  każdej zmianie ekranu, `NoVehicle` w C3 nie anuluje powiadomienia; testy app 181, core bez zmian 198.
- Szacunek litrów z czujnika (PRD F2a, SPEC §2a, §11.2, §11.3): `estimate_refuel_liters` / `estimateRefuelLiters`
  w obu domenach (skok poziomu × 43 l, 0,001 l `HALF_UP`), §6 liczy tą samą funkcją, więc przyjęty szacunek nie
  zmienia ceny w baku; `SyncEngine.confirmRefuel(..., litersSource)`. W formularzu ceny wiersz „Szacunek z czujnika:
  ok. N l” z „Użyj szacunku” (pełna precyzja, `RefuelFormatting.litersInput`), po przyjęciu „Litry z szacunku
  czujnika” i „Z paragonu”; ręczna zmiana litrów zdejmuje oznaczenie. `checkRefuelForm(..., litersFromEstimate)`:
  `ESTIMATE` tylko dla pary cena + litry, szacunek z kwotą bez ceny to błąd pola „Cena” (D3). Okno C5 bez zmian.
  Plan: briefs/toyota-companion/C6-plan.md (v2, poza repo). Domena i testy Kotlin/Python oraz testy UI
  napisał agent 1, sprawdzone przez agenta 2; Python 84, core 208, app 193.
- Kopia archiwum (PRD F7, F8, SPEC §14): `core/data/ArchiveFile.kt` (JSON z `format`/`version`, `Unsupported`/`Corrupt`),
  `SyncEngine.exportArchive`/`importArchive` pod `mutex.withLock` (reguła kolizji w `core/sync`, bez lustra w Pythonie),
  `BackupController` (pliki jako tekst `Uri`, I/O w `AppContainer` przez `ContentResolver`, limit 20 MB), ekran „Kopia
  archiwum” z ostrzeżeniem o GPS/VIN, przypomnienie F8 na statusie. Format pliku i testy napisał agent 1, reszta i
  przegląd – agent 2; agent 1 znalazł 3 usterki w kodzie agenta 2 (odczyt daty z obcą wartością, podwójny odczyt
  daty, „trasy innego pojazdu” bez wybranego pojazdu) – poprawione z testami.
- Arkusz tras w CSV (PRD F6, SPEC §14.4): `app/ui/TripsCsv.kt` (`tripsCsv`, `csvCell`, `csvDateTime`), BOM doklejany w
  `BackupController.exportTripsCsv`; ekran „Kopia i eksport”. Testy napisał agent 1, kod i docs – agent 2.
- Dziennik tankowań i spalanie „komputer vs faktyczne” (PRD F9, SPEC §15): domena `fuel_economy_intervals` w Pythonie
  (`app/domain/economy.py`, wzorzec – agent 2) i Kotlinie (`core/domain/EconomyOps.kt`, lustro – agent 1, 26 testów o tych
  samych nazwach); odcinki „do pełna – do pełna”, litry `receipt`/`derived` (decyzja D7), próg 100 km (D8), dystans
  i paliwo komputera z tych samych tras. `SyncEngine.refuelLog()`, `RefuelLogController` (odczyt w callbacku nawigacji),
  ekran „Dziennik tankowań” ze statusu. Archiwum nie ma jeszcze potwierdzonych tankowań – na emulatorze tylko pusty stan.
- Domknięcie MVP (C10): ikona aplikacji (adaptacyjna, wektorowa, `mipmap-anydpi-v26`, tło `#EB0A1E`, monochrome)
  i „Popraw litry” w dzienniku (SPEC §15.3, `SyncEngine.amendRefuel`, `RefuelChange.NotConfirmed`). Pierwszy etap w trybie
  równoległym: kod i testy app – agent 2, testy `core` i przegląd – agent 1.
- Statystyki i postoje (C11, PRD F10, F11, F17, SPEC §16–§18, ADR-008): domena `tripstats.py` / `TripStatsOps.kt`
  (22 testy o tych samych nazwach; wzorzec i lustro – agent 2), `StatsController`/`StatsScreen`; postoje – czyste
  funkcje `ParkingStops.kt` i testy (agent 1), ekran „Postoje” (agent 2). Położenie tylko na ekranie „Postoje”.

## Następny krok
Sprawdzone na emulatorze (API 36) 2026-10-29: logowanie prawdziwym kontem, pierwsza synchronizacja (562 trasy, 1 odczyt),
sekrety zaszyfrowane w `secrets.xml` (Keystore), praca `toyota-sync` zaplanowana z wymogiem sieci, brak awarii i sekretów w logach.
Sprawdzone na emulatorze 2026-10-30: status, lista tras (motyw jasny i nocny, 562 trasy), ekran „Cena domyślna” –
6,19 zł/l nadaje koszt wszystkim trasom (październik 2026: 217,90 zł, dopisek „cena domyślna”), brak awarii.
Sprawdzone na emulatorze (API 36) 2026-10-31, build C5 po przeglądzie: prośba `POST_NOTIFICATIONS` po zalogowaniu (flaga `notifications_asked` zapisana), okno „Wykryto tankowanie” z klawiaturą numeryczną w oknie dialogu, „Później”, intent powiadomienia przy zimnym starcie i do działającej aplikacji (`onNewIntent`) – formularz z fokusem w cenie; brak awarii. Nie sprawdzone: samo wysłanie powiadomienia (wymaga nowego tankowania z czujnika; pokryte testami Robolectric) oraz zapis ceny z okna (wpis 30 % → 94 % zostawiony do testu właściciela). Uwaga: AVD ma `hw.keyboard = yes`, więc klawiatura ekranowa jest ukryta, dopóki `show_ime_with_hard_keyboard` = 0. Znane (C3, poza zakresem): komunikat błędu walidacji w formularzu znika po obrocie ekranu, bo `openForm(id)` przy odtworzeniu czyści `formError`; wpisane wartości zostają.
Sprawdzone na emulatorze 2026-10-31, build C6: wiersz „Szacunek z czujnika: ok. 27,52 l” dla wpisu 30 % → 94 %,
„Użyj szacunku” wpisuje 27,52, „Z paragonu” zdejmuje oznaczenie, szacunek z kwotą bez ceny daje błąd pola „Cena”
i nic nie zapisuje; brak awarii.
Sprawdzone na emulatorze 2026-10-31, build C7: karta „Zrób kopię archiwum…” na statusie, ostrzeżenie o GPS/VIN,
eksport do Downloads (`toyota-companion-2026-10-31.json`, 235 kB: 1 pojazd, 562 trasy, 1 tankowanie, 2 odczyty, bez
sekretów), import tego samego pliku – „trasy: 0, tankowania: 0, odczyty paliwa: 0”, plik ucięty → „uszkodzony”, obcy
format → „nie jest kopią…”, po eksporcie karta znika i jest „Ostatnia kopia archiwum: 31.10.2026”; brak awarii. Plik
kopii został w Downloads emulatora. Nie sprawdzone: import do czystej instalacji (wymagałby skasowania danych
właściciela; pokrywa go test `a file written here restores the same rows on an empty phone`).
Sprawdzone na emulatorze 2026-10-31, build C8: „Eksportuj trasy do CSV” zapisał `toyota-companion-trasy-2026-10-31.csv`
(42 kB, BOM, 563 linie CRLF, po 8 komórek, chronologicznie, cena 6,190 „cena domyślna”, bez VIN), data kopii archiwum
bez zmian; brak awarii. Plik CSV i kopia JSON zostały w Downloads emulatora.
Sprawdzone na emulatorze 2026-10-31, build C9: przycisk „Dziennik tankowań” na statusie, pusty stan z podpowiedzią
„Do pełna” + paragon; brak awarii. Karty spalania pokrywają testy (domena Python/Kotlin, formatowanie, kontroler).
Sprawdzone na emulatorze 2026-10-31, build C10: ikona w szufladzie aplikacji (czerwony kwadrat z białą kroplą). Okna
„Popraw litry” nie da się sprawdzić bez potwierdzonego tankowania – pokrywają go testy.
Sprawdzone na emulatorze 2026-10-31, build C11: „Statystyki” na 562 trasach (56,4 % km na prądzie, ranking 10 tras,
85 krótszych niż 1 km pominiętych, rok 2026 i pory roku), „Postoje” (miejsca od 173 postojów, położenie do 0,001°);
brak awarii. Rok do roku pojawi się, gdy archiwum obejmie dwa lata.
- Następny krok: do decyzji właściciela – F12, F13, F16, F14, F15 (F15 wysyła współrzędne – zgoda), przycisk mapy w „Postojach”.

Test klienta na prawdziwym koncie MyToyota (uruchamia właściciel; hasło nie trafia do repo ani rozmowy):
```bash
source ~/Android/env.sh && cd android
read -rp "Login MyToyota: " TOYOTA_USERNAME; read -rsp "Haslo: " TOYOTA_PASSWORD; echo
export TOYOTA_USERNAME TOYOTA_PASSWORD
./gradlew -q :core:liveCheck
unset TOYOTA_USERNAME TOYOTA_PASSWORD
```

Program tylko czyta (bez `refresh_status`), pokazuje VIN z maską i nie wypisuje pozycji GPS ani tokenów.
Sprawdzone 2026-10-29: fikcyjny login dostaje od prawdziwego serwera Toyoty „User Not Found”,
czyli pierwszy etap logowania działa z prawdziwym API.

## Środowisko lokalne
- `source ~/Android/env.sh` – JDK 21, Android SDK 37, Gradle 9.8.2
- `cd android && ./gradlew --no-daemon -q :core:test :app:testDebugUnitTest :app:assembleDebug`
- `cd backend && .venv/bin/ruff format --check && .venv/bin/ruff check && .venv/bin/mypy && .venv/bin/python -m pytest -q`
- Emulator: AVD `toyota36` (API 36, x86_64); brakujące biblioteki systemowe rozpakowane bez sudo do `~/Android/emulator-libs`:
  `LD_LIBRARY_PATH=~/Android/emulator-libs/usr/lib/x86_64-linux-gnu $ANDROID_HOME/emulator/emulator -avd toyota36 -gpu swiftshader_indirect -no-audio &`
  potem `./gradlew -q :app:installDebug`
- Referencja API: `/home/lk/projekty/pytoyoda-ref` (pytoyoda v6.0.0)
- gitleaks lokalnie: `~/Android/tools/gitleaks git . --log-opts="--all"`

## Proces z agentami
Kontekst → plan bez kodu → review i akceptacja → implementacja → niezależne review (uruchomienie testów,
porównanie z pytoyoda/Pythonem, odczyt plików z dysku) → commit. Zasady: `AGENTS.md`.
Agent 1 = pi w panelu `w8:p1` (od 2026-10-29 model claude-sonnet-5-5; 2026-10-31 stopka panelu pokazuje
`llama-swap/qwen3.8-flash-next-nvfp4` – sprawdzać jego wyniki w kodzie, a listę plików brać z `git diff --stat`).
Uwaga: lokalny model qwen potrafił zapisać `***` zamiast `${{` w plikach – sprawdzać zapisane pliki.
Znany szum testów: `:app:testDebugUnitTest` czasem wypisuje na stderr ślad stosu `CloseGuard` (ramki
`ThreadPoolExecutor`, `SandboxTestRunner`) przy EXIT=0 i 0 porażkach – to niezamknięta baza WorkManagera z
`WorkManagerTestInitHelper.initializeTestWorkManager` w `@Before` (`SessionControllerTest`, `SyncWorkerTest`), a nie
błąd. Nie dodawać `CloseGuardRule` (zamieniłby szum w porażki). Odtworzenie: `--tests '*SyncWorkerTest' --tests
'*SessionControllerTest' --rerun-tasks -i`.
Niezacommitowane zmiany nieznanego autora: najpierw review i testy, dopiero potem praca na nich.
