# Przegląd jakości kodu i testów: F56–F60 (C43)

Przegląd jakości implementacji, logiki, przypadków brzegowych, wątków i testów dla funkcji F56–F60 oraz migracji Room 7→8 i 8→9 w repozytorium `/home/lk/projekty/toyota-companion` (commit `fe4f308`, tag `v0.2.14`).

Zakres przeglądu:
- **F56**: Opony (`TyreOps.kt`, `TyreController.kt`, `TyreCard.kt`, `TyreReminderNotifier.kt`)
- **F57**: Stacja przy tankowaniu (`StationOps.kt`, `RefuelsCsv.kt`, `RefuelPromptController.kt`, `RefuelLogController.kt`, `RefuelFormScreen.kt`, migracja Room 7→8)
- **F58**: Serie i rekordy celów eco (`EcoStreaks.kt`, `EcoGoals.kt`, `EcoGoalsCard.kt`)
- **F59**: Zdjęcie paragonu (`ReceiptFiles.kt`, `ReceiptStore.kt`, `ReceiptRepository.kt`, `ReceiptUi.kt`, migracja Room 8→9)
- **F60**: Automatyczna kopia archiwum do folderu SAF (`AutoBackup.kt`, `AutoBackupOps.kt`, glue SAF w `AppContainer.kt:528-624`)

---

## 1. Ustalenia ważne (Ważne)

### 1.1 Wyciek uszkodzonego/pustego pliku w folderze SAF przy błędzie zapisu
- **Plik:linia**: [AppContainer.kt:591-613](file:///home/lk/projekty/toyota-companion/android/app/src/main/kotlin/pl/lk/toyotacompanion/AppContainer.kt#L591-L613)
- **Problem**: W metodzie `writeCopy` najpierw tworzony jest plik docelowy przez `DocumentsContract.createDocument(...)` (lub pobierany z `folderChildren`), a następnie otwierany jest strumień `openOutputStream(target, "wt")` i wywoływane `out.use { it.write(bytes) }`. Jeżeli operacja zapisu zostanie przerwana wyjątkiem `IOException` (np. brak miejsca na dysku, wyjęcie karty SD, błąd I/O dostawcy SAF), wyjątek jest przechwytywany dopiero w pętli `AutoBackup.runAfterSync()` jako ogólny błąd (`failed = true`). W folderze użytkownika pozostaje jednak utworzony, pusty (0 bajtów) lub częściowo zapisany plik JSON. Przy kolejnej próbie synchronizacji lub ręcznym imporcie użytkownik napotka uszkodzony plik.
- **Ważność**: Ważna
- **Poprawka**: W bloku `writeCopy` objąć operację zapisu blokiem `try ... catch (e: Exception)` i w razie niepowodzenia usunąć nieukończony plik docelowy za pomocą `DocumentsContract.deleteDocument(context.contentResolver, target)`, po czym ponownie rzucić wyjątek.

### 1.2 Pomijanie plików z sufiksem duplikatu w rotacji kopii zapasowych SAF
- **Plik:linia**: [AppContainer.kt:604-612](file:///home/lk/projekty/toyota-companion/android/app/src/main/kotlin/pl/lk/toyotacompanion/AppContainer.kt#L604-L612) oraz [AutoBackupOps.kt:20-25](file:///home/lk/projekty/toyota-companion/android/app/src/main/kotlin/pl/lk/toyotacompanion/ui/AutoBackupOps.kt#L20-L25)
- **Problem**: Jeżeli dostawca SAF utworzy plik z automatycznie nadanym sufiksem (np. `toyota-companion-2026-11-12 (1).json`), `AppContainer` próbuje wykonać `DocumentsContract.renameDocument(context.contentResolver, target, name)`. Jeśli dostawca odrzuci zmianę nazwy (co jest częste w chmurach i na kartach SD, gdzie dostawca zgłasza brak wsparcia dla zmiany nazw), wyjątek jest ignorowany (`catch (e: Exception) {}`). Tymczasem funkcja `autoCopyDate` w `AutoBackupOps.kt` weryfikuje nazwę ścisłym wyrażeniem regularnym `Regex("\\d{4}-\\d{2}-\\d{2}")`. W rezultacie plik `toyota-companion-2026-11-12 (1).json` zwraca `null` w `autoCopyDate`, przez co funkcja `copiesToDelete` uznaje go za plik obcy i nigdy go nie usunie podczas rotacji. Może to prowadzić do nieskończonego kumulowania starych kopii w folderze.
- **Ważność**: Ważna
- **Poprawka**: Zaktualizować parser nazwy `autoCopyDate`, aby akceptował i wyodrębniał datę także z plików z sufiksami systemowymi duplikatów (np. `Regex("""\d{4}-\d{2}-\d{2}(?: \(\d+\))?""")`), co pozwoli funkcji `copiesToDelete` na ich prawidłowe rotowanie.

### 1.3 Całkowity brak pokrycia testami automatycznymi warstwy SAF
- **Plik:linia**: [AppContainer.kt:528-624](file:///home/lk/projekty/toyota-companion/android/app/src/main/kotlin/pl/lk/toyotacompanion/AppContainer.kt#L528-L624)
- **Problem**: Testy `AutoBackupTest.kt` oraz `AutoBackupOpsTest.kt` weryfikują wyłącznie logikę domenową w oparciu o pamięciową atrapę `FakeFolder`. Cały kod integracji z platformą Android Storage Access Framework (`DocumentsContract.buildChildDocumentsUriUsingTree`, zapytania kursorów `DocumentsContract.Document`, odczyt nazwy katalogu, tworzenie, kasowanie i zmiana nazwy dokumentów, zwalnianie uprawnień `releasePersistableUriPermission`) nie posiada żadnego testu jednostkowego ani testu Robolectric.
- **Ważność**: Ważna
- **Poprawka**: Wdrożyć test integracyjny z użyciem Robolectric i zamockowanego lub testowego `DocumentsProvider` / `ContentProvider`, testujący operacje na kursorach SAF, obsługę brakujących kolumn oraz reakcję na błędy uprawnień (`SecurityException`).

### 1.4 Trzykrotne otwieranie strumienia wejściowego dla jednego pliku w `ReceiptStore`
- **Plik:linia**: [ReceiptStore.kt:33, 39, 76](file:///home/lk/projekty/toyota-companion/android/app/src/main/kotlin/pl/lk/toyotacompanion/receipts/ReceiptStore.kt#L33)
- **Problem**: W metodzie `importPhoto(source: Uri)` wywołanie `resolver.openInputStream(source)` następuje trzykrotnie: pierwszy raz dla `inJustDecodeBounds`, drugi raz dla właściwego dekodowania `BitmapFactory.decodeStream` ze współczynnikiem próbkowania, a trzeci raz w `rotationDegrees` dla `ExifInterface`. Część zewnętrznych dostawców URI (np. aplikacje aparatów z jednorazowymi URI lub dostawcy chmurowi) udostępnia strumienie jednorazowe, które po pierwszym zamknięciu nie mogą zostać ponownie otwarte i rzucają `IOException` bądź zwracają `null`.
- **Ważność**: Ważna
- **Poprawka**: W pierwszym kroku `importPhoto` skopiować strumień źródłowy do lokalnego pliku tymczasowego w `cacheDir` / `filesDir`, a następnie wykonać odczyt wymiarów, orientacji EXIF oraz skalowanie z tego pliku lokalnego, na końcu usuwając plik tymczasowy.

### 1.5 Zbyt wąski zakres przechwytywanych wyjątków w `ReceiptStore.rotationDegrees`
- **Plik:linia**: [ReceiptStore.kt:75-86](file:///home/lk/projekty/toyota-companion/android/app/src/main/kotlin/pl/lk/toyotacompanion/receipts/ReceiptStore.kt#L75-L86)
- **Problem**: Metoda `rotationDegrees` zabezpieczona jest klauzulą `catch (e: java.io.IOException)`. Konstruktor `android.media.ExifInterface(InputStream)` w systemie Android w przypadku uszkodzonych nagłówków JPEG lub nietypowych formatów graficznych może rzucić wyjątki typu `RuntimeException` (np. `IllegalArgumentException` lub `NullPointerException`). Nieprzechwycony błąd przerywa cały proces importu paragonu nieoczekiwaną awarią zamiast bezpiecznego powrotu do domyślnego obrotu 0°.
- **Ważność**: Ważna
- **Poprawka**: Rozszerzyć klauzulę przechwytywania na `catch (e: Exception)` (z pominięciem `CancellationException`), zwracając `0` przy dowolnym błędzie parsowania metadanych.

---

## 2. Ustalenia drobne i ulepszenia jakości (Drobne)

### 2.1 Myląca wartość rezerwowa `"?"` dla nazwy katalogu SAF w karcie kopii
- **Plik:linia**: [AppContainer.kt:589](file:///home/lk/projekty/toyota-companion/android/app/src/main/kotlin/pl/lk/toyotacompanion/AppContainer.kt#L589)
- **Problem**: W bloku `folderName` wywołanie zwraca `?: "?"` w przypadku błędu odczytu (np. usunięcia folderu lub cofnięcia uprawnień przez system). Zgodnie z komentarzem w kodzie, brak folderu powinien sygnalizować problem, tymczasem UI otrzymuje stan `folderName = "?"` z `failed = false`, co skutkuje wyświetleniem na karcie napisu `"Folder: ?"` bez jasnej informacji o konieczności ponownego wyboru folderu.
- **Ważność**: Drobna
- **Poprawka**: Zwracać `null` zamiast `"?"`, co pozwoli na spójne ustawienie flagi błędu w `AutoBackupUiState`.

### 2.2 Podwójne skanowanie pełnej historii tras w `EcoStreaks.ecoHistory`
- **Plik:linia**: [EcoStreaks.kt:58-59](file:///home/lk/projekty/toyota-companion/android/app/src/main/kotlin/pl/lk/toyotacompanion/ui/EcoStreaks.kt#L58-L59)
- **Problem**: W funkcji `ecoHistory` generowana jest sekwencja miesięcy, a następnie wykonywane jest dwukrotne mapowanie: `judgedByGoal = months.map { ecoMonth(goals, trips, zone, it) }` oraz `figures = months.map { ecoMonth(FIGURE_GOALS, trips, zone, it) }`. Każde wywołanie `ecoMonth` w pętli filtruje całą listę tras. Dla wieloletniej historii (np. 36 miesięcy i kilka tysięcy tras) oznacza to dziesiątki tysięcy zbędnych iteracji.
- **Ważność**: Drobna
- **Poprawka**: Zagregować `trips` jednokrotnie do mapy `Map<MonthKey, List<StoredTrip>>`, a następnie obliczać wskaźniki dla każdego miesiąca bez wielokrotnego przeszukiwania całej kolekcji.

### 2.3 Nieużywane pole `startOdometerKm` w module opon (F56)
- **Plik:linia**: [TyreOps.kt:18, 64](file:///home/lk/projekty/toyota-companion/android/core/src/main/kotlin/pl/lk/toyotacompanion/core/domain/TyreOps.kt#L18)
- **Problem**: Klasa `TyreChange` zawiera pole `val startOdometerKm: Int?`, które jest pobierane w formularzu, serializowane do ustawień i parsowane, lecz w żadnym miejscu aplikacji nie jest wykorzystywane w obliczeniach przebiegu kompletu opon ani prezentowane na karcie `TyreCard`.
- **Ważność**: Drobna
- **Poprawka**: Dodać wyświetlanie stanu licznika informacyjnie na karcie opon lub udokumentować, że pole to ma wyłącznie charakter archiwalnej notatki właściciela.

### 2.4 Potencjalne rozcięcie pary surogatów Unicode przy skracaniu nazwy stacji
- **Plik:linia**: [StationOps.kt:58](file:///home/lk/projekty/toyota-companion/android/core/src/main/kotlin/pl/lk/toyotacompanion/core/domain/StationOps.kt#L58)
- **Problem**: Funkcja `normalizeStation` używa `collapsed.take(STATION_MAX_LENGTH).trimEnd()`. Jeśli 60. znak przypadnie na wysoki surogat (np. znak emoji w nazwie stacji), para surogatów zostanie rozcięta, pozostawiając niepoprawny znak Unicode w bazie danych i plikach CSV/JSON.
- **Ważność**: Drobna
- **Poprawka**: Sprawdzać, czy ostatni pobrany znak nie jest `Character.isHighSurrogate(lastChar)`, i w razie potrzeby odrzucać go.

### 2.5 Brak opisu migracji 8→9 w komentarzu nagłówkowym `AppDatabase.kt`
- **Plik:linia**: [AppDatabase.kt:26](file:///home/lk/projekty/toyota-companion/android/app/src/main/kotlin/pl/lk/toyotacompanion/data/db/AppDatabase.kt#L26)
- **Problem**: Komentarz w linii 26 szczegółowo opisuje zmiany we wszystkich wcześniejszych migracjach schematu (od 1→2 do 7→8), ale urywa się przed migracją 8→9, mimo że linia 27 zawiera `AutoMigration(from = 8, to = 9)`.
- **Ważność**: Drobna
- **Poprawka**: Uzupełnić komentarz o informację o migracji 8→9 (dodanie kolumn `receipt_file` do tabel `refuels` i `expenses`).

---

## 3. Podsumowanie luk w testach

1. **Warstwa SAF (`AppContainer.kt:528-624`)**: Całkowity brak testów dla fizycznych wywołań `DocumentsContract` i `ContentResolver`.
2. **Krawędzie nazw duplikatów SAF**: Brak testu weryfikującego, jak `AutoBackupOps.copiesToDelete` reaguje na nazwy plików z dopiskiem ` (1)`.
3. **Wielokrotny odczyt URI w `ReceiptStore`**: Brak testu sprawdzającego zachowanie przy dostawcy zwracającym strumień jednorazowy.

---

## 4. Weryfikacja migracji Room (7→8 i 8→9)

- **Migracja 7→8 (`station` w `refuels`)**:
  - Kolumna `station TEXT NULL` poprawnie dodana w pliku schematu `8.json`.
  - Istniejące wiersze otrzymują `NULL` bez utraty danych.
  - Pokryta testem w [AppDatabaseMigrationTest.kt:141-150](file:///home/lk/projekty/toyota-companion/android/app/src/test/kotlin/pl/lk/toyotacompanion/data/db/AppDatabaseMigrationTest.kt#L141-L150).
- **Migracja 8→9 (`receipt_file` w `refuels` i `expenses`)**:
  - Kolumny `receipt_file TEXT NULL` poprawnie zadeklarowane w encjach i w pliku schematu `9.json`.
  - Pokryta testem w [AppDatabaseMigrationTest.kt:153-166](file:///home/lk/projekty/toyota-companion/android/app/src/test/kotlin/pl/lk/toyotacompanion/data/db/AppDatabaseMigrationTest.kt#L153-L166).
  - Weryfikacja zachowania powiązania przy aktualizacji rekordu (`updateKeepingVin`) działa prawidłowo.
