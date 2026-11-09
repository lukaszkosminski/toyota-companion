# C26 – plan: diagnostyka synchronizacji

Status: **zaakceptowany przez polecenie właściciela 2026-11-09** (praca zlecona agentowi; bez pytań otwartych).
Data: 2026-11-09. Źródło: `docs/ROADMAP.md` (LATER, propozycje z przeglądu ha_toyota).

## 1. Cel

Ekran „Diagnostyka” w menu statusu, **tylko do odczytu**: pokazuje, kiedy ostatnio udało się pobrać
pojazdy/konto, telemetrię i trasy, kiedy Toyota ostatnio widziała auto (`occurrence_date` telemetrii),
czas i kod ostatniego błędu (np. 429, 403) oraz stan archiwum. Do tego:

- ostatnie dobre dane zostają na ekranie po chwilowym błędzie odczytu (komunikat nad danymi, nie puste ekrany),
- „Kopiuj diagnostykę” i „Udostępnij” dają tekst do zgłoszenia błędu **bez VIN, GPS i tokenów**.

Poza zakresem: `refresh_status()`, budzenie auta, nowe endpointy Toyoty, prawdziwe dane konta w testach,
plik eksportu na dysk (diagnostyka jest tekstem do schowka/udostępnienia), zmiany w `:core:liveCheck`.

## 2. Kontekst – co już jest

- `SyncState` (Room `sync_state`, jedna wiersz): `vin`, `watermarkEpochDay`, `lastAttemptAt`,
  `lastSuccessAt`, `lastErrorAt`, `lastErrorKind`, `lastErrorMessage`; `isStale` = błąd po sukcesie.
- `SyncEngine.fullSync()`: pojazdy → telemetria (miękki błąd) → trasy → koszty; `failAt` zapisuje błąd,
  nic nie kasuje. `SyncStatus` dokłada liczniki archiwum i przebieg.
- `RemoteDataController<T>`: jeden odczyt na otwarcie ekranu, 403/404 = „Toyota nie udostępnia”,
  inny błąd = `failed`; dziś **czyści `data`** przy błędzie.
- Room: wersja 6, `AutoMigration` 1→6, schematy `app/schemas/.../N.json` w repo, test migracji każdej wersji.

## 3. Zmiany

### core (`android/core`)

| Plik | Zmiana |
|---|---|
| `core/data/Models.kt` | `SyncState`: `lastVehiclesSuccessAt`, `lastTelemetrySuccessAt`, `lastTripsSuccessAt`, `lastErrorStatus` (HTTP, nullable); `SyncStatus`: `carStateAt` (czas `ts` najnowszego odczytu = `occurrence_date` Toyoty) |
| `core/sync/SyncOutcome.kt` | `SyncError.status: Int? = null` (kod HTTP z `ToyotaApiException`) |
| `core/sync/SyncEngine.kt` | po każdym udanym kroku zapis stanu z czasem kroku (żeby błąd później nie zgubił wcześniejszych czasów); `classify` przenosi `statusCode`; `failAt` zapisuje `lastErrorStatus`; `status()` dokłada `carStateAt` |

Kroki: **pojazdy/konto**, **telemetria**, **trasy**. Telemetria z miękkim błędem nie aktualizuje swojego czasu.

### app (`android/app`)

| Plik | Zmiana |
|---|---|
| `data/db/Entities.kt` | 4 nowe nullable kolumny `sync_state` + mapowanie |
| `data/db/AppDatabase.kt` | wersja 7 + `AutoMigration(6, 7)`; schemat `7.json` (generowany przez Room) |
| `data/db/AppDatabaseMigrationTest.kt` | `CURRENT_VERSION = 7`, test 6→7 (stary wiersz zostaje, nowe kolumny `null`, zapis działa) |
| `ui/RemoteDataController.kt` | przy `failed`/`unavailable` zachowuje poprzednie `data`; `reset()` czyści jak dziś |
| `ui/RemoteDataScreens.kt` | `RemoteState(hasData = …)`: błąd pokazujemy nad danymi, a nie zamiast nich; pierwszy błąd bez danych = sam komunikat; wspólna linia „dane z ostatniego udanego odczytu” |
| `ui/ServiceScreens.kt` | to samo dla F44/F45 |
| `ui/DiagnosticsFormatting.kt` (nowy) | czysta funkcja raportu: wersja aplikacji, czasy kroków, `carStateAt`, błąd (czas, rodzaj, HTTP, komunikat po `ToyotaSecrets.sanitize`), liczniki archiwum; **bez VIN/GPS/tokenów** |
| `ui/DiagnosticsScreen.kt` (nowy) | ekran „Diagnostyka”: odczyt lokalny raz na otwarcie, „Kopiuj diagnostykę” (schowek) i „Udostępnij” (`ACTION_SEND`), bez zapisu na telefonie |
| `ui/UiState.kt` | `Screen.Diagnostics` |
| `ui/AppRoot.kt` | kontroler diagnostyki, trasa ekranu, `reset()` przy wylogowaniu |
| `ui/StatusScreen.kt` | kafelek „Diagnostyka” w menu statusu |
| `ui/Texts.kt` | teksty ekranu, etykiety czasów, „dane z ostatniego udanego odczytu” (PL/EN) |
| `AppContainer.kt` | `diagnosticsController` (`RemoteDataController<SyncStatus>` czytający `SyncEngine.status()`, bez sieci) i `appVersionName` z `PackageManager` |
| `MainActivity.kt` | przekazanie kontrolera i wersji do `AppRoot` |

### Dokumentacja

`docs/PRD.md` (F46), `docs/SPEC.md` (§47), `docs/ROADMAP.md` (przeniesienie pozycji z LATER),
`CHANGELOG.md` (Unreleased). Bez zmian w Gradle i bez nowych zależności.

Backend Python: **bez zmian** – to diagnostyka stanu i formatowanie, nie logika domenowa.

## 4. Testy

- `core` (`SyncEngineTest`): czasy kroków po sukcesie; telemetria z błędem nie rusza swojego czasu, ale trasy tak;
  błąd tras zachowuje czas pojazdów i telemetrii; `lastErrorStatus = 429`/`403`; `null` dla błędu sieci;
  `carStateAt` = `ts` najnowszego odczytu, `null` bez odczytu.
- `app` (`AppDatabaseMigrationTest`): 6→7 zachowuje wiersz `sync_state`, nowe kolumny `null` i zapisywalne.
- `app` (`RemoteDataControllerTest`): błąd po sukcesie zostawia `data` i podnosi `failed`; `reset()` czyści.
- `app` (`DiagnosticsFormattingTest`): raport ma czasy, kod błędu i liczniki; nie ma VIN, współrzędnych ani tokenu;
  komunikat błędu przechodzi przez `sanitize`.

## 5. Przypadki brzegowe

- Pierwsza synchronizacja: wszystkie czasy `null` → „brak”, nie zmyślona data.
- Błąd telemetrii (miękki): trasy i koszty idą dalej, `lastSuccessAt` się ustawia, `lastTelemetrySuccessAt` zostaje stary.
- Błąd przed pierwszym krokiem: `lastErrorStatus` z HTTP, czasy kroków z poprzedniego udanego przebiegu.
- 403/404 na ekranie Toyoty: „Toyota nie udostępnia tych danych”, a jeśli były dane – zostają z linią „ostatni udany odczyt”.
- Brak auta: `carStateAt` = `null`, diagnostyka nadal pokazuje błąd i archiwum.
- Stara baza (v6): migracja automatyczna, nic nie ginie, nowe kolumny `null`.

## 6. Prywatność

Raport nie zawiera VIN, współrzędnych GPS ani tokenów; komunikat błędu jest już sanityzowany przy zapisie
i przechodzi `ToyotaSecrets.sanitize` jeszcze raz przy budowie raportu. Ekran czyta tylko lokalny stan
i archiwum – żadnego wywołania Toyoty, więc auto nie jest budzone. Nic nie trafia do bazy poza licznikami czasu.

## 7. Walidacja

- Python: `cd backend && .venv/bin/ruff format --check && .venv/bin/ruff check && .venv/bin/mypy && .venv/bin/python -m pytest -q`
- Android: `source ~/Android/env.sh && cd android && ./gradlew --no-daemon -q :core:test :app:testDebugUnitTest :app:lintDebug :app:assembleRelease`
- gitleaks na historii (jak CI).

## 8. Kolejność

1. `core`: model + `SyncEngine` + testy.
2. Room 6→7 + test migracji + schemat.
3. `RemoteDataController` (ostatnie dobre dane) + ekrany.
4. Ekran „Diagnostyka”, raport, teksty, nawigacja, `AppContainer`.
5. Docs + CHANGELOG, walidacja, commit.

## 9. Poprawki po przeglądzie (2026-11-09)

Przegląd w `briefs/toyota-companion/C26-review-agent2.md` (plik poza commitem – cudza praca) domknięty
w commicie `fix(app)`:

- **B1** – `ToyotaSecrets.maskVin` (wspólny z F45) maskuje każde wystąpienie gołego VIN w całym raporcie,
  także wartości ≤ 4 znaków; testy gołego VIN, krótkiego VIN i komunikatu bez VIN-u.
- **B2** – kod HTTP tylko z zakresu 100–599; `-1` z `ToyotaController` (brak odpowiedzi) → brak kodu; test.
- **W1** – `lastTelemetrySuccessAt` zapisywany po zapisach odczytu/tankowania, jak trasy; test STORAGE
  sprawdza, że nieudany zapis nie wygląda na sukces telemetrii.
- **W2** – tekst „Dane poniżej pochodzą z ostatniego udanego odczytu…” PL/EN, SPEC §47 poprawiony, test PL/EN.
- **W3** – token generacji na każde `load()`, więc wolniejszy pierwszy odczyt nie nadpisze świeższego;
  test z dwoma `CompletableDeferred`.
- **W4** – KDoc opisuje `load()` na każdym otwarciu i rolę `ensureLoaded` (używanego przez inne kontrolery).
- **D1–D4** – testy raportu ustawiają język PL i przywracają poprzedni, test raportu EN; fallback wersji „?”;
  komunikat błędu tylko razem z rodzajem; pusty `vin` strony/wpisu traktowany jak brak.
- **D5 (pominięte)** – „Kopiuj diagnostykę” nadal ma `AppIcons.Save` (dyskietka), bo w `AppIcons.kt` nie ma
  `content_copy`; dodanie ikony to osobna, kosmetyczna zmiana zestawu ikon. Do rozważenia przy okazji
  porządkowania `AppIcons` (razem z innymi brakującymi symbolami Material).
