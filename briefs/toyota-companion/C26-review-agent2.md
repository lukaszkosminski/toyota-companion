# C26 – przegląd kodu: diagnostyka synchronizacji (do agenta 2)

Data: 2026-11-09
Zakres: niezacommitowana praca C26 (F46 / SPEC §47, Room 6→7, ostatnie dobre dane) oraz ostatni commit
`20a66b2` (F44/F45, SPEC §45–§46) – o ile zmiana dotyczy wspólnych elementów.
Recenzent: agent przeglądający. Adresat: agent 2 (implementacja).

**Werdykt:** kierunek i podział warstw są dobre, testy pokrywają główne ścieżki. Przed commitem trzeba
domknąć dwa punkty: gwarancję „raport bez VIN” (B1) i kod HTTP `-1` w diagnostyce (B2). Reszta to ważne
i drobne poprawki oraz kilka braków w testach.

Nie uruchamiałem Gradle (zgodnie z konwencją, gdy drugi agent pracuje na tym samym drzewie). Ustalenia
są z odczytu kodu; numery linii zweryfikowane na working tree z 2026-11-09.

---

## BLOKUJĄCE (przed commitem)

### B1. Raport diagnostyki nie maskuje „gołego” VIN – obietnica „bez VIN” nie jest gwarantowana

- `android/app/src/main/kotlin/pl/lk/toyotacompanion/ui/DiagnosticsFormatting.kt:55-58`
- `android/core/src/main/kotlin/pl/lk/toyotacompanion/core/toyota/ToyotaSecrets.kt:12-16`

`diagnosticsReport` przepuszcza `lastErrorMessage` tylko przez `ToyotaSecrets.sanitize`, a ten redaguje
wyłącznie wzorce typu `klucz=wartość` (`vin=`, `access_token=`, …). Komunikat bez klucza, np.
`"VIN1234567890 not found"` albo treść wyjątku z wklejonym VIN, przejdzie do raportu w całości. KDoc
(`DiagnosticsFormatting.kt:12-13`) i SPEC §47 obiecują „bez VIN”, więc to jest luka w gwarancji, nie
kosmetyka. Test `DiagnosticsFormattingTest.kt:93-112` tego nie łapie, bo używa właśnie formy `vin=…`.

**Propozycja:** w `diagnosticsReport` po `sanitize` zamaskować także `state.vin`, jeśli nie jest pusty –
tym samym sposobem co F45 (`*` + ostatnie 4 znaki, `VehicleDataMapper.maskedVin`). Warto wydzielić małą
funkcję czystą (np. `maskBareVin(text, vin)`) i pokryć ją testem:

- komunikat `"VIN1234567890 not found"` → w raporcie nie ma `VIN1234567890`, jest `**********7890`;
- `state.vin = null` → tekst bez zmian;
- obecny test `vin=…` dalej przechodzi.

To nie wymaga nowego pola ani zmiany API – `state.vin` już jest w `SyncStatus`.

---

### B2. `HTTP -1` może trafić do raportu jako kod błędu

- `android/core/src/main/kotlin/pl/lk/toyotacompanion/core/sync/SyncEngine.kt:601`
- `android/core/src/main/kotlin/pl/lk/toyotacompanion/core/toyota/ToyotaController.kt:322`
- `android/app/src/main/kotlin/pl/lk/toyotacompanion/ui/DiagnosticsFormatting.kt:52`

`classify` bezwarunkowo przenosi `ToyotaApiException.statusCode`. Tymczasem ścieżka „żądanie bez odpowiedzi”
rzuca `ToyotaApiException(statusCode = -1, …)`, więc diagnostyka pokaże `HTTP -1`, mimo że SPEC §47 mówi
„błąd transportu nie ma kodu HTTP”. Użytkownik zgłaszający błąd dostanie mylący kod.

**Propozycja:** przepuszczać tylko sensowne kody:

```kotlin
val status = (e as? ToyotaApiException)?.statusCode?.takeIf { it in 100..599 }
```

albo zamienić `-1` w `ToyotaController` na `null` (drugie jest szerszą zmianą, bo dotyka też innych
konsumentów). Test w `SyncEngineTest`: wyjątek z `statusCode = -1` → `lastErrorStatus == null`.

---

## WAŻNE

### W1. `lastTelemetrySuccessAt` zapisywany przed lokalnymi zapisami – możliwy fałszywy „sukces telemetrii”

- `android/core/src/main/kotlin/pl/lk/toyotacompanion/core/sync/SyncEngine.kt:454-456`
- dla porównania trasy: `SyncEngine.kt:485-487`

Telemetria ustawia czas sukcesu zaraz po udanym `gateway.telemetry(vin)`, **przed** `readings.addIfAbsent`
i przed detekcją/zapisem tankowania. Trasy robią to odwrotnie: najpierw `writingTrips { … }`, potem
`lastTripsSuccessAt`. Jeśli zapis do archiwum rzuci (STORAGE), `guarded` zwróci błąd, ale diagnostyka
zostanie z czasem „telemetria OK”, choć dane nie zostały zapisane.

To nie jest błąd krytyczny (komentarz w kodzie świadomie mówi „fetch worked”), ale trzeba **jednoznacznie
zdecydować**, co znaczy „ostatnie udane pobranie”:

- albo sukces = samo pobranie z Toyoty (wtedy dopisać to w KDoc/SPEC i zostawić),
- albo sukces = pobranie + zapis (wtedy przenieść `lastTelemetrySuccessAt` za blok zapisów, symetrycznie
  do tras).

Rekomendacja: druga opcja, bo użytkownik patrzy na to jako „dane są”, a nie „HTTP wrócił”.

### W2. Linia „Powyżej dane…” jest nad danymi, ale tekst mówi, że są powyżej

- `android/app/src/main/kotlin/pl/lk/toyotacompanion/ui/RemoteDataScreens.kt:96-98`
- `android/app/src/main/kotlin/pl/lk/toyotacompanion/ui/Texts.kt:1326-1328`
- `docs/SPEC.md:1101` („…linię »Powyżej dane z ostatniego udanego odczytu…« nad nimi”)

`RemoteState` renderuje `REMOTE_STALE` w bloku stanu, który wywołujący umieszcza **przed** kartą z danymi.
Tekst PL/EN mówi „Powyżej dane…” / „Above is…”, więc wskazuje w złą stronę. Ten sam błąd powtarza SPEC §47
(„Powyżej… nad nimi” jest wewnętrznie sprzeczne).

**Propozycja:** albo zmienić tekst na „Dane poniżej pochodzą z ostatniego udanego odczytu…” / „The data
below is from the last successful read…”, albo przenieść linię pod dane. Prostsze i mniej ryzykowne jest
poprawienie tekstu; wtedy zaktualizować też SPEC §47. Warto dodać test tekstu w `TextsTest` (PL i EN), żeby
kolejna zmiana układu nie rozjechała się z opisem.

### W3. Dwa szybkie `load()` mogą zakończyć się w złej kolejności

- `android/app/src/main/kotlin/pl/lk/toyotacompanion/ui/RemoteDataController.kt:44-45,51-77`

`generation` jest zwiększany tylko w `reset()`. Dwa wywołania `load()` (np. otwarcie ekranu + szybki
„Spróbuj ponownie”) dostają ten sam `startedIn`, a wolniejsze pierwsze żądanie może nadpisać świeższe
drugie. Skutek: na ekranie starsze dane, mimo że nowsze już przyszły.

**Propozycja:** licznik per wywołanie, np. `val token = ++generation` w `load()` i porównanie
`if (token != generation) return@launch`; `reset()` dalej inkrementuje. Dodać test z `CompletableDeferred`
dla dwóch równoległych odczytów (wolny pierwszy, szybki drugi) i sprawdzić, że wygrywa drugi.

### W4. KDoc `RemoteDataController` mówi o `ensureLoaded`, a ekrany wołają `load()`

- `android/app/src/main/kotlin/pl/lk/toyotacompanion/ui/RemoteDataController.kt:31-33`
- `RemoteDataScreens.kt:110,148,189`, `ServiceScreens.kt:40,83`, `DiagnosticsScreen.kt:41`

Opis „read once when the screen opens ([ensureLoaded])” nie odpowiada rzeczywistości: każdy ekran robi
`LaunchedEffect(Unit) { controller.load() }`, czyli świadomie odświeża przy każdym otwarciu (to jest
zgodne z intencją F41–F46). `ensureLoaded` zostaje wtedy nieużywane w produkcji, tylko w testach.

**Propozycja:** poprawić KDoc na „read on every opening (`load()`), `ensureLoaded` only for callers that
must read at most once” albo usunąć `ensureLoaded`, jeśli nie ma konsumenta. Nie zostawiać opisu
sprzecznego z kodem – to dokładnie ten rodzaj rozjazdu, który AGENTS.md każe zgłaszać.

---

## DROBNE

### D1. `DiagnosticsFormattingTest` zależy od globalnego `Lang.current`

- `android/app/src/test/kotlin/pl/lk/toyotacompanion/ui/DiagnosticsFormattingTest.kt:51-58,76,88`
- `android/app/src/main/kotlin/pl/lk/toyotacompanion/ui/Lang.kt:22-23`

Testy porównują twarde polskie etykiety, a `Lang.current` jest globalnym `mutableStateOf`; inne testy
(`EcoGoalsTest`, `TextsTest`, `TripDriveExtrasTest`, `VehicleDataFormattingTest`) ustawiają `EN` i wracają
do `PL`. Przy innym porządku klas lub równoległym uruchomieniu test może zobaczyć angielskie teksty.

**Propozycja:** w `@Before`/`@After` ustawiać `Lang.current = AppLanguage.PL` (i przywracać poprzednią
wartość), a asercje budować z `Texts.*`, nie z literałów. Dodać jeden test raportu po angielsku – to
sprawdzi, czy raport faktycznie jest tłumaczony, a nie tylko PL.

### D2. Pusty `versionName` daje „Toyota Companion ” bez wersji

- `android/app/src/main/kotlin/pl/lk/toyotacompanion/AppContainer.kt:197-199`

`versionName.orEmpty()` przy `null` daje pusty string, więc pierwsza linia raportu to `"Toyota Companion "`.
W buildach deweloperskich/podpisanych niestandardowo to realne.

**Propozycja:** fallback jawny, np. `versionName?.takeIf { it.isNotBlank() } ?: "?"` albo `"unknown"`.
Warto, żeby diagnostyka nie wyglądała na uciętą.

### D3. Komunikat błędu pokazywany bez rodzaju błędu

- `android/app/src/main/kotlin/pl/lk/toyotacompanion/ui/DiagnosticsFormatting.kt:48-58`

`lastErrorMessage` jest dodawany poza gałęzią `if (state.lastErrorKind == null)`. Stary lub częściowo
zapisany wiersz z komunikatem, ale bez rodzaju, da w raporcie „Ostatni błąd: brak”, a pod spodem
„Komunikat: …”. To mylące.

**Propozycja:** pokazywać komunikat tylko, gdy `lastErrorKind != null` (albo gdy jednocześnie istnieje
`lastErrorAt`). Dodać test: `lastErrorMessage` bez `lastErrorKind` → brak linii „Komunikat”.

### D4. Pusty `page.vin` omija maskowanie VIN w powiadomieniach (F44/F45)

- `android/core/src/main/kotlin/pl/lk/toyotacompanion/core/toyota/VehicleDataMapper.kt:109`

`page.vin ?: vin` podstawia `""`, jeśli Toyota przyśle pusty string (nie `null`). Wtedy `maskVin` w
`VehicleDataMapper.kt:137-139` dostaje `vin = ""`, a `maskVin` ma zabezpieczenie `isNullOrBlank()` i nic
nie maskuje. Pełny VIN z `NotificationDto.vin` i tak jest maskowany w `toNotification`, więc wyciek jest
mało prawdopodobny, ale intencja „pusty = brak” powinna być jawna.

**Propozycja:** `page.vin?.takeIf { it.isNotBlank() } ?: vin` i analogicznie w `toNotification`.
Test mapperа: strona z `vin = ""` i treścią zawierającą VIN spoza strony → VIN zamaskowany.

### D5. Ikona „Kopiuj diagnostykę” to dyskietka

- `android/app/src/main/kotlin/pl/lk/toyotacompanion/ui/DiagnosticsScreen.kt:66`

`AppIcons.Save` (floppy) sugeruje zapis pliku, a akcja kopiuje do schowka. `AppIcons.Share` obok jest
poprawna.

**Propozycja:** ikona treści/schowka (np. `ContentCopy`, jeśli jest w zestawie Material Symbols) albo
samo „Kopiuj” bez ikony. To UX, nie blokuje.

---

## Co jest zrobione dobrze

- Podział: `SyncState`/`SyncStatus` w `core`, migracja i UI w `app`; brak nowych zależności i zmian Gradle.
- Każdy krok zapisuje własny czas, więc późniejszy błąd nie gubi wcześniejszych – to dobrze pokrywa
  `SyncEngineTest`.
- Miękki błąd telemetrii nie zatrzymuje tras i kosztów; STORAGE nie jest mylony z „telemetrią niedostępną”.
- `RemoteDataController` zachowuje ostatnie dobre dane i odrzuca odpowiedzi po `reset()`; testy tego
  zachowania są czytelne.
- Migracja 6→7: nullable kolumny, test każdej wersji, `7.json` w repo, `CURRENT_VERSION = 7` – spójne.
- F44/F45: brak `serviceHistoryId`/`roNumber`/`notes`, maskowanie VIN w treści, `null` payload jako brak
  danych – zgodne z §45–§46.
- Dokumentacja i CHANGELOG uzupełnione w tej samej zmianie.

## Braki w testach (poza wymienionymi wyżej)

- Brak testu „raport bez gołego VIN” (B1) – obecny test sprawdza tylko formę `vin=`.
- Brak testu `statusCode = -1` → brak kodu HTTP (B2).
- Brak testu dwóch równoległych `load()` (W3).
- Brak testu `lastErrorMessage` bez `lastErrorKind` (D3).
- Warto dodać test angielskiego raportu (D1), skoro raport jest tekstem dla użytkownika.

## Propozycje nowych funkcji (dopisane do `docs/ROADMAP.md`, LATER)

Poniższe wynikają wyłącznie z danych/endpointów już obecnych w projekcie; nic nie budzi auta i nie
wymyśla nowych pól Toyoty. Zostały dopisane jako propozycje z przeglądu C26, niewybrane.

1. **Budżet miesięczny na paliwo i wydatki** – z istniejących kosztów tras i `expenses`; postęp na
   ekranie statusu i lokalne powiadomienie przy progu. Bez sieci.
2. **Historia i trend ceny paliwa** – wykorzystać istniejące pobrania z trojmiasto.pl po dotknięciu
   (F22/§25), zapisywać lokalne snapshoty i porównywać „ostatnio zapłacona” z bieżącą ceną stacji.
3. **Eksport CSV tankowań i wydatków** – rozszerzenie istniejącego eksportu tras CSV (F6/§14.4).
4. **Powiadomienie o niskim paliwie** – na podstawie ostatniej telemetrii (`distanceToEmptyKm`/poziom)
   po synchronizacji, z wiekiem odczytu w treści; bez wybudzania auta.
5. **Lokalny licznik nieprzeczytanych powiadomień Toyoty** – dodać `messageId` do
   `ToyotaNotification` (jest już w DTO) i lokalną tabelę „widziane”; bez oznaczania jako przeczytane
   po stronie Toyoty (endpoint tego nie ma, §46).

## Zalecana kolejność poprawek

1. B1 + test maskowania gołego VIN.
2. B2 + test `-1`.
3. W1 (decyzja: sukces = pobranie + zapis) i W3 (token per `load`).
4. W2 (tekst + SPEC) i W4 (KDoc).
5. D1–D5 i uzupełnienie testów.

Po poprawkach: walidacja wg AGENTS.md (`:core:test :app:testDebugUnitTest :app:assembleDebug`) i commit
dopiero na polecenie właściciela.
