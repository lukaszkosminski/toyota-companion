# C41 – przegląd jakości: zmiany po v0.2.6 (F52–F59), tylko odczyt

Data: 2026-11-11. Autor: agent 2. Zakres: `git log --oneline v0.2.6..HEAD` (HEAD = 5f766f4), 194 pliki, 18220 linii dodanych.
Bez zmian w kodzie, bez Gradle, bez git (poza odczytem), bez sieci i emulatora. Oznaczenia: **[K]** potwierdzone w kodzie,
**[P]** przypuszczenie do sprawdzenia na urządzeniu lub testem.

## Blokujące / ważne

**1. [K] Rozjazd: nazwa stacji idzie do Auto Backup, a dokumentacja mówi „tylko lokalnie”.**
- `docs/SECURITY.md:16`: „Auto Backup kopiuje na Dysk Google właściciela tylko bazę archiwum i `settings`”.
- `docs/SECURITY.md` (linia F57, ok. :33): „nazwa wpisana przez właściciela jest zapisana tylko lokalnie (w tankowaniu i archiwum)”.
- `docs/SPEC.md` §57, punkt „Prywatność”: „jest tylko na ekranach i w plikach CSV/archiwum, które właściciel sam eksportuje”.
- Kod: kolumna `station` jest w tabeli `refuels` (`data/db/Entities.kt`, `RefuelEntity`), a Auto Backup kopiuje całą bazę
  (`res/xml/backup_rules.xml`, `data_extraction_rules.xml`: `database/toyota-companion.db`). Nazwa stacji trafia więc do kopii na Dysku Google.
- Scenariusz: właściciel czyta SECURITY, uznaje, że nazwa stacji nie opuszcza telefonu, i nie wie, że trafia do chmury razem z trasami.
- Poprawka: w SECURITY dopisać, że nazwa stacji jest w bazie i w Auto Backup jak tankowania i trasy; w SPEC §57 dodać to samo zdanie.
  Decyzja produktowa: albo zostawić (rekomendacja: zostawić, bo trasy i tankowania i tak tam są), albo wyłączyć kolumnę z kopii (zmiana reguł).

**2. [P] Trzy okna dialogowe z polami bez przewijania i bez `imePadding`.**
- `ui/EcoGoalsCard.kt:49–52` (`EcoGoalsDialog`, dwa pola: spalanie i prąd). `text = { Column(verticalArrangement = …) {` bez `verticalScroll`.
- `ui/BudgetCard.kt:90–93` (`BudgetDialog`, pola limitu). `text = { Column(modifier = Modifier.fillMaxWidth()) {` bez przewijania.
- `ui/ConsumptionCard.kt:181–184` (spalanie deklarowane, pole). `text = { Column(verticalArrangement = …) {` bez przewijania.
- Scenariusz: mały ekran, klawiatura otwarta, `AlertDialog` ma wysokość treści; dolne pole i przycisk „Zapisz” są poza widocznym
  obszarem. Lekcja z „Opcji” i „Opon” mówi, że każde okno z klawiaturą musi się przewijać. Wzorzec już jest: `ui/RefuelPromptDialog.kt:65–66`
  (`Modifier.imePadding().verticalScroll(rememberScrollState())`).
- Poprawka: w trzech miejscach `Column(modifier = Modifier.imePadding().verticalScroll(rememberScrollState()), …)`.
- Sprawdzenie: emulator, mały ekran z klawiaturą (nie zrobiono w tym przeglądzie).

**3. [K] Gałąź obrotu zdjęcia z EXIF nie ma testu.**
- `receipts/ReceiptStore.kt:47–52` (obrót przez `Matrix.postRotate`) i `:72–82` (`rotationDegrees`, odczyt `TAG_ORIENTATION`).
- `grep -rln "ORIENTATION\|orientation\|rotat"` w `android/app/src/test`: brak trafienia w testach zdjęć (trafia tylko w niezwiązane testy sesji, pogody i aktualizacji).
- Scenariusz: zdjęcie z aparatu z orientacją 6 (90°) znów wyjdzie bokiem po następnej zmianie w `importPhoto`. Ta poprawka została naniesiona
  przez przegląd właściciela i nic jej nie chroni.
- Poprawka: test w `ReceiptStoreTest` (tryb NATIVE, jak reszta): zapisać JPEG, ustawić `ExifInterface.TAG_ORIENTATION = 6` przez
  `android.media.ExifInterface.saveAttributes()`, zaimportować, sprawdzić, że zapisany plik ma szerokość i wysokość zamienione względem źródła.

**4. [K] Brak testu `TyreController` (logika zmian, usuwania i progów F56).**
- `ui/TyreController.kt`: `addChange`, `removeLastChange`, `saveThresholds`, `load`. Testy obejmują tylko funkcje czyste
  (`TyreOpsTest`, `TyreFormattingTest`, `TyreReminderNotifierTest`). `ls android/app/src/test/.../ui | grep -i tyre` nie zwraca `TyreControllerTest`.
- Scenariusz: zmiana w `removeLastChange` zapomina klucze przypomnień albo kasuje złą zmianę, a żaden test tego nie złapie.
- Poprawka: `TyreControllerTest` w stylu `EcoGoalsControllerTest` (fake settings, `StandardTestDispatcher`): dodanie, usunięcie
  ostatniej (i nie pierwszej), zapisanie progów, błąd zapisu zostawia poprzedni stan.

## Drobne

5. [K] `README.md:123` mówi „features F1–F39”, a README:5 mówi F1–F59. Poprawka: F1–F59 w tabeli.
6. [K] `docs/PRD.md:130` wiersz F55 stoi po F59 (kolejność tabeli). Kosmetyka: przenieść przed F56.
7. [P] `ui/RefuelLogScreen.kt`, `AmendDialog`: `produceState<String?>(null, …)` zaczyna od `null`, więc przez chwilę widać „Dodaj zdjęcie paragonu”,
   a dopiero potem miniaturę. Dotknięcie „Dodaj” w tym czasie nadpisze istniejące zdjęcie (repozytorium je zastąpi i skasuje stare, więc nie ma utraty
   danych). Poprawka: stan „ładowanie” (np. `CircularProgressIndicator`) albo blokada przycisku do pierwszego odczytu.
8. [P] `ui/ExpensesScreen.kt`, `AddExpenseDialog`: `onDismissRequest` i „Anuluj” wołają `dropPhoto()`, które kasuje `pendingPhoto`,
   jeśli już jest. Gdy użytkownik zamknie formularz w trakcie importu (`importing == true`), `pendingPhoto` jest jeszcze `null`, więc nic
   nie zostaje skasowane, a import kończy się po zamknięciu i zapisuje plik bez odnośnika. Plik zniknie dopiero przy następnym starcie
   (sprzątanie). Poprawka: po `importPending` sprawdzić, czy formularz nadal istnieje (np. `rememberUpdatedState`), albo kasować w bloku `finally`.
9. [K] `ExpensesControllerTest` nie ma przypadku z plikiem zdjęcia (`add(…, receiptFile = "…")`). Fałszywy DAO w tym teście zwraca puste odnośniki,
   więc test nie mógłby wykryć błędnego zapisu odnośnika. Poprawka: przypadek z `rows[id]?.receiptFile`.
10. [K] `ExpensesController.add` zwraca `true` przed zakończeniem zapisu (`write` jest asynchroniczny). Formularz się zamyka, a błąd zapisu
    widać tylko jako `failed` na liście, bez wpisu w formularzu. To zachowanie sprzed F59 (nie nowe), ale przy zdjęciu oznacza, że przy błędzie
    zostaje plik bez odnośnika do następnego startu. Poprawka: zwracać wynik po zapisie albo zostawić jako znane ograniczenie w SPEC §59 (już jest w „Ograniczenia”).

## Sprawdzone i w porządku

- **Reset przy wylogowaniu (1):** wszystkie kontrolery wołane w `AppRoot.kt:133–159` mają `reset()` albo `close()` (`tripRoute.close()`, `heat.close()`).
  `DeclaredConsumptionController` nie ma resetu celowo: to ustawienie telefonu (SPEC §30), nie dane konta. `ReceiptRepository` nie ma resetu
  i nie musi: zdjęcia wydatków są lokalne jak wydatki (zgodnie z F59). `EcoGoalsController`, `TyreController` i `ExpensesController` resetują się.
- **Współbieżność powiadomień (5):** `PeriodSummaryNotifier.onSyncOutcome` i `TyreReminderNotifier.onSyncOutcome` trzymają `Mutex`, czytają klucz
  wewnątrz blokady, zapisują klucz dopiero po `showSummary`/`showReminder`, który zwraca `true`. Wyciszony kanał (`IMPORTANCE_NONE`) nie zużywa
  okresu ani przypomnienia. `summaryDecision` (`core/domain/PeriodSummaryOps.kt:134–142`) daje `Wait` poza 8–22, a pierwsze uruchomienie
  zapisuje klucz bez wysyłki (`Baseline`).
- **Granice dat (3):** `lastCompletedWindow` (`PeriodSummaryOps.kt:35–44`) poprawnie wybiera poprzedni tydzień (od poniedziałku) i miesiąc, w tym
  przełom roku. Pełne miesiące przy progu opon: `ChronoUnit.MONTHS.between` (Kotlin) i `_full_months` (Python) dają to samo; 31 stycznia do
  28 lutego to 0 miesięcy w obu. Miesiąc eco i stacje liczą z `trip.start.atZone(zone)`, więc 00:30 1 października w Warszawie to październik
  (test `EcoStreaksTest` to sprawdza). Historia serii nie zależy od zmiany czasu, bo liczy po dniach kalendarzowych.
- **Prywatność w logach i powiadomieniach (4):** jedyne `Log.w` w aplikacji (`AppContainer.kt:408`, i w `init` z sprzątaniem zdjęć) loguje
  wyłącznie nazwę klasy wyjątku. Treści powiadomień opon i okresów (`TyreFormatting.reminderLine`, `periodSummary`) nie zawierają nazwy stacji,
  VIN ani GPS. Diagnostyka i udostępnianie raportu (`DiagnosticsScreen.kt:75`) nie czytają stacji ani zdjęć.
- **Obrót i rozmiar zdjęcia (poprawka właściciela):** obrót stosowany po skalowaniu i przed zapisem JPEG; limit 1600 px zachowany po obrocie
  (obrót nie zmienia dłuższego boku). `ORIENTATION_NORMAL` (także lustra) daje 0 stopni, co jest bezpieczne.
- **Zdjęcia a kopie (4):** `backup_rules.xml` i `data_extraction_rules.xml` nie zawierają domeny `file`, więc zdjęcia nie wchodzą do Auto Backup;
  plik `receipts/` nie jest w eksporcie JSON ani w CSV (`RefuelsCsv.kt` zawiera tylko `station`).
- **Przywracanie z kopii (F59):** po przywróceniu bazy z nazwami zdjęć `ReceiptRepository.sweep` czyści odnośniki do brakujących plików
  i nie kasuje wpisów (`ReceiptRepositoryTest`, test sprzątania).
- **Wątki zdjęć (1):** wszystkie operacje na plikach i bazie idą przez `withContext(io)` pod jednym `Mutex` (`ReceiptRepository`);
  `ReceiptStore.decode` nie jest wołany na Main (podgląd przez `produceState` i `repository.decode`).
- **Odmiana i teksty (6):** liczebniki z `plPlural` mają trzy formy zgodne z użyciem (`miesiąc/miesiące/miesięcy`, `dzień/dni`,
  `trasa/trasy/tras`, `minął/minęły/minęło`). Angielska część `Lang.pick` nie zawiera polskich fraz (sprawdzono grepem na znaki diakrytyczne;
  trafienia to waluta `zł` albo tłumaczenie nagłówka CSV, które jest poprawne).
- **Przewijanie formularzy (2):** `ExpensesScreen` (wydatek, opony), `RefuelLogScreen` (korekta), `RefuelFormScreen`, `RefuelPromptDialog`,
  `DefaultPriceScreen`, `LoginScreen` i `OptionsScreen` mają `verticalScroll`; korekta i formularz wydatku dostały je w F59.
- **Zaplecze tankowania:** `RoomRefuelRepository.update` idzie przez `updateKeepingVin`, który zachowuje `receipt_file` (test migracji 8→9).
- **Schemat i migracja:** `9.json` z builda, różnica do `8.json` tylko w `refuels` i `expenses` (kolumna `receipt_file`); `AutoMigration(8, 9)` ma test.

## Nie sprawdzono

- **Logika F52 (kalkulator trasy), F53 (godzina wyjazdu), F50/F51 (CO₂, krótkie trasy):** tylko przegląd nagłówków i wywołań, bez czytania formuł.
- **F54 `periodTotals` i F55 utrata wartości (formuła liniowa w dniach):** nie sprawdzono granic dni i zaokrągleń.
- **Lustra Python ↔ Kotlin dla F54, F55, F58:** nie sprawdzono, czy nazwy testów i wartości się zgadzają (F58 nie ma lustra z założenia).
- **Układ na urządzeniu:** kolejność kart, zawijanie dwóch linii serii na 360 dp, dialogi z klawiaturą: tylko kod, bez emulatora.
- **`StationPriceController` i widżet/Android Auto po zmianach F58:** nie czytano.
- **Pełny diff 194 plików:** przejrzano pliki wskazane w zakresie (F52–F59 rdzeń, powiadomienia, reset, schemat), nie wszystkie linie.
- **Ponowne uruchomienie testów:** nie w tym przeglądzie (zakaz Gradle); ostatni wynik z sesji F59: core 579, app 990, backend 311, 0 błędów.

## Podsumowanie

- Blokujące / ważne: **4** (1 [K] rozjazd dokumentacji o kopii; 2 [P] trzy dialogi bez przewijania; 3 [K] brak testu obrotu; 4 [K] brak testu `TyreController`).
- Drobne: **6** (5–10).
- Sprawdzone i w porządku: **9** punktów.
- Nie sprawdzono: **6** punktów.

Plik: `briefs/toyota-companion/C41-review-agent2.md`.
