# C38 – plan: stacja przy tankowaniu (proponowany PRD F57, SPEC §57)

Status: **szkic do akceptacji**. Data: 2026-11-11. Pozycja 6 z `docs/ROADMAP.md` NEXT („Stacja przy tankowaniu: marka lub nazwa,
średnia cena i faktyczne spalanie wg stacji”). Numer F57/§57 proponowany; **nie sprawdzono**, czy F56 nie zajął inny wątek.
Tylko plan: bez kodu, testów, Gradle i gita.

## 1. Cel
Przy potwierdzaniu tankowania zapisać opcjonalnie nazwę/markę stacji (wybór z wcześniej użytych lub wpis ręczny), a w „Dzienniku
tankowań” pokazać dla stacji: liczbę tankowań, średnią cenę zł/l i faktyczne spalanie l/100 km. Lokalnie, bez sieci i budzenia auta.

## 2. Ustalenia z kodu
**Model i zapis:** `core/domain/Models.kt:26` – `Refuel` nie ma pola stacji; konstruktory PENDING tworzą obiekt bez niego, więc nowe
pole musi mieć default. `core/domain/RefuelOps.kt:56` – `confirmRefuel` waliduje cenę/litry/kwotę; nowy parametr musi przejść tę
drogę i wrócić w `copy`. `core/sync/SyncEngine.kt:201` – `confirmRefuel`, `:230` `amendRefuel` (korekta po paragonie), `:304`
`refuelLog()` (potwierdzone + interwały), `:318` `confirmedRefuels()` (CSV). `data/db/Entities.kt:130` – `RefuelEntity`, mapowania
`:150`. `data/db/AppDatabase.kt:25` – **wersja 7**; `:29-35` auto-migracje 1→…→7; schematy `app/schemas/.../1..7.json`.
`AppDatabaseMigrationTest.kt:31` – `CURRENT_VERSION = 7`; testy budują plik wersji N z JSON-a i otwierają obecną bazą; `:141`
„current version opens its own schema unchanged”.

**Formularze:** `ui/RefuelFormScreen.kt:63` – pełny formularz, kolumna ma `verticalScroll` (`:100`); picker ceny `:147`, „do pełna”
`:230`, zapis `:241` przez `RefuelController.confirm`. `ui/RefuelPromptDialog.kt:34` – okno „Wykryto tankowanie” pyta **tylko o cenę**;
„Więcej” otwiera pełny formularz. Sygnatury: `RefuelController.kt:52-61,128`, `RefuelPromptController.kt:52`, `AppContainer.kt:436,476`.
`ui/StationPricePicker.kt:31` – źródło ceny F22; `StationPriceController.kt:20,23` – `PriceStation(id, name)` i `PRICE_STATIONS =
[Auchan Szczęśliwa (33831), WATIS (97185)]` (w kodzie **dwie** stacje, nie trzy); wybór wpisuje dotąd tylko cenę. Drugi caller:
`DefaultPriceScreen.kt:104`. `ui/RefuelLogScreen.kt:86-140` – lista; karta ekonomii pod tankowaniem zamykającym interwał `:262`,
otwarty interwał na górze `:127`; nowa karta pasuje między wykres poziomu `:100` i pierwszą pozycję. `RefuelLogController.kt:44`
czyta `SyncEngine.refuelLog` – nowy kontroler nie jest potrzebny.

**F9:** `core/domain/EconomyOps.kt:21-75` – interwał otwiera potwierdzone tankowanie `filledToFull == true` z litrami, zamyka następne;
faktyczne litry = suma w `(start, end]`, km = trasy startujące w `[start.ts, end.ts)`, `OK` od 100 km i poprawnych litrów.
`core/domain/Models.kt:68-81` – `EconomyInterval` ma **`startRefuelId`**, więc przypisanie do stacji otwarcia nie wymaga nowych danych.

**Kopia i CSV:** `core/data/ArchiveFile.kt:46` – `ARCHIVE_VERSION = 1`; `:196-210` `RefuelDto`; `:426-429` `Json { ignoreUnknownKeys
= true }` – stary plik bez pola daje `null`, nowy plik w starej appce pomija klucz (przy ponownym eksporcie stacja zginie).
`ui/RefuelsCsv.kt:22-45` – arkusz tankowań; `ui/Texts.kt:1536` – nagłówek kończy się na „Do pełna”.

## 3. Decyzje
- **Pole:** `station: String?` w `Refuel`/`RefuelEntity` (`TEXT NULL`) i Pythonie (`models.py:85`, `refuel.py:83`). Normalizacja: `trim`,
  zwinięcie spacji, puste → `null`, max 60 znaków; grupowanie bez wielkości liter, wyświetlana pisownia z najnowszego tankowania.
  Brak stacji nie blokuje zapisu.
- **Historia:** podpowiedzi = nazwy z potwierdzonych tankowań bieżącego auta, sortowane po liczbie użyć, potem `ts` (max 6 chipów);
  zawsze można wpisać nową. Lista idzie do formularza parametrem z już czytanego logu, bez dodatkowego odczytu bazy.
- **F22 a nazwa:** `StationPricePicker` dostaje `onStation: (String?) -> Unit = {}`; klik chipu woła `onStation(station.name)`.
  Formularz wstawia nazwę **tylko gdy pole jest puste albo wciąż trzyma poprzednią podpowiedź z pickera** – ręcznej nie nadpisuje.
  „Ręcznie” nie czyści stacji. `DefaultPriceScreen` używa no-op, więc się nie zmienia.
- **Prompt:** okno „Wykryto tankowanie” dostaje kompaktowe pole stacji + poziomo przewijane chipy (max 4); cena zostaje pierwsza
  i focusowana. Szybkie potwierdzenie też zbiera stację, pełny formularz ma szerszą listę.
- **Korekta:** dialog „Popraw litry” dostaje pole stacji (prefill); `amendRefuel` przyjmuje nową wartość (`null` = wyczyść).
- **Spalanie wg stacji, uczciwie:** zamknięty interwał F9 przypisujemy do stacji **tankowania otwierającego** (tego „do pełna”,
  które ustawiło bak); paliwa kupionego w środku gdzie indziej nie rozdzielamy. Liczymy zbiorczo `Σ actualLiters / Σ distanceKm × 100`
  dla interwałów `OK`, nigdy średniej średnich. **Minimum 3 interwały `OK`**; przy 1–2 „N interwałów – za mało do spalania”.
  Przy 3–5 to nadal orientacja, nie pomiar – karta zawsze podaje liczbę interwałów i km oraz „stacja otwarcia interwału”.
- **Cena:** `Σ totalCost / Σ liters` po potwierdzonych tankowaniach stacji z oboma polami (ważona ilością, nie średnia cen);
  od **1** tankowania, z licznikiem. Tankowania bez stacji nie tworzą wiersza; „Bez stacji: N” tylko gdy N > 0.
- **Prywatność:** nazwa stacji bywa wskazówką miejsca (nie GPS/VIN). Nie wchodzi do tekstów udostępnianych ani powiadomień, nie jest
  logowana. Do archiwum JSON i CSV trafia tylko jako eksport wywołany przez właściciela; SECURITY dostanie jedno zdanie.
- **Archiwum:** `station: String? = null` w `RefuelDto`; **bez podbijania `ARCHIVE_VERSION`** (stary plik → `null`, nowy w starej
  appce czytany dzięki `ignoreUnknownKeys`). Import starej kopii = tankowania bez stacji.
- **CSV:** kolumna **„Stacja” na końcu** (nie przesuwa istniejących), pusta gdy brak; nagłówek w `Texts`, test CSV.
- **Room:** wersja **8**, `AutoMigration(from = 7, to = 8)`, kolumna `station TEXT NULL`, nowy `8.json`, test migracji 7→8 i wpis
  w „current”. DAO bez zmian poza mapowaniem.

## 4. Interfejs (Kotlin; Python snake_case, te same nazwy testów)
```
// core/domain/StationOps.kt (nowy) + backend/app/domain/stations.py
data class StationSummary(name, refuels, pricedRefuels, avgPricePerLiter?, intervals, distanceKm, lPer100Km?)
fun normalizeStation(text: String?): String?
fun stationSummaries(refuels: List<Refuel>, intervals: List<EconomyInterval>): List<StationSummary>
```
`confirmRefuel(..., station: String? = null)`, `SyncEngine.confirmRefuel(..., station)`, `amendRefuel(..., station: String?)`.
Stałe: `STATION_MAX_LENGTH = 60`, `STATION_SUGGESTIONS = 6`, `STATION_MIN_INTERVALS = 3`.

## 5. Pliki
| Warstwa | Plik |
|---|---|
| domena | `core/domain/{Models,RefuelOps,StationOps}.kt` (ostatni nowy); `backend/app/domain/{models,refuel,stations}.py` |
| sync/db | `core/sync/SyncEngine.kt`, `data/db/{Entities,AppDatabase}.kt`, `app/schemas/.../8.json`, `AppDatabaseMigrationTest.kt` |
| UI | `ui/{RefuelFormScreen,RefuelController,RefuelPromptDialog,RefuelPromptController,StationPricePicker,RefuelLogScreen,RefuelLogController,Texts,AppContainer}.kt` |
| eksport | `core/data/ArchiveFile.kt`, `ui/RefuelsCsv.kt` |
| testy | `core/.../StationOpsTest.kt` + `backend/tests/test_domain_stations.py` (te same nazwy), `RefuelOpsTest`, `SyncEngine*`, `RefuelFormTest`, `RefuelsCsvTest`, migracja; asercje F9/F22/F38 bez zmian |
| docs | PRD (F57), SPEC (§57), SECURITY, ROADMAP, CHANGELOG, README |

## 6. Testy i brzegi
- Normalizacja: `null`, `""`, `"  "` → brak; wielokrotne spacje; 60/61 znaków; różna wielkość liter → jeden wiersz.
- Zapis: prompt i pełny formularz; brak stacji; picker nie nadpisuje ręcznej; „Ręcznie” nie czyści; rotacja zachowuje wpis.
- Statystyki: 0/1/2/3 interwały; `TOO_SHORT`/`MISSING_RECEIPT` pominięte; cena z 1 tankowania; brak ceny; suma ważona; kolejność
  (liczba tankowań malejąco, potem nazwa); „Bez stacji”.
- Migracja 7→8: stare wiersze `station IS NULL`, nowe pole zapisuje/czyta; archiwum sprzed F57 importuje się, eksport zawiera stację,
  stara appka pomija klucz; CSV ma kolumnę na końcu.
- Regresja: F9 (interwały, karta ekonomii), F22 (picker), F2c (prompt), F47 (CSV), F38 – bez zmiany liczb.

## 7. Ryzyka
- **Mała próba** – 3 interwały mogą być przypadkiem; licznik i „orientacyjnie” w treści, bez ukrywania.
- **Stacja otwarcia** – paliwo z innych stacji w środku interwału zniekształca obraz; opisane w SPEC, nie „naprawiane” sztucznie.
- **Picker** – łatwo nadpisać ręczną nazwę; reguła „puste/poprzednia podpowiedź” + test.
- **Migracja** – dodanie `NULL` jest bezpieczne, ale wymaga `8.json` i testu; brak testu blokuje wydanie.

## 8. Pytania otwarte
1. Czy F57/§57 są wolne (F56 mogło zająć inne zadanie)? Jeśli nie – przesunąć numer przed implementacją.
2. Czy edycja stacji w dialogu korekty po paragonie jest OK (proponuję tak), czy stacja ma być niezmienna po potwierdzeniu?
3. „Bez stacji” jako osobny wiersz karty czy tylko licznik (proponuję licznik)?
4. Czy CSV dostaje stację tylko w arkuszu tankowań (proponuję tak; trasa nie ma stacji)?
5. Próg 3 interwały czy 5 (proponuję 3 + jawne „orientacyjnie”)?

**Ścieżka planu:** `/home/lk/projekty/toyota-companion-a3/briefs/toyota-companion/C38-plan.md`.
