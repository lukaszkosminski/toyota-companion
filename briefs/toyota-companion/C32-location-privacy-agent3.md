# C32 – prywatność ekranu „Lokalizacja” po e48c58f (agent 3, tylko odczyt)

Data: 2026-11-10. Sprawdzone `git show e48c58f --stat` i wskazane pliki; bez Gradle/testów i bez zmian w repo.

## 1. rememberSaveable / SavedStateHandle / Bundle

**OK.** `known` to zwykły `remember`, nie `rememberSaveable`: `RemoteDataScreens.kt:197-198`
`// Plain \`remember\`: the places are only a name for the position and are forgotten with it.` /
`var known by remember { mutableStateOf<KnownPlaces?>(null) }`. Pozycja żyje w `StateFlow` kontrolera
(`RemoteDataController.kt:33-34`: `No database, no cache on disk — the location in particular stays in memory only`).
Mapa dokładna: `AppRoot.kt:94-96` `The exact position of the car ... is never saved: it lives in this plain \`remember\` state only`;
`AppRoot.kt:99-101` przy `exact = true` ustawia `mapData = null` i `exactMap = origin to points`. `mapData`
(`AppRoot.kt:93`) jest saveable, ale dla lokalizacji jest zerowane. Zapisany jest tylko enum ekranu
(`AppRoot.kt:86`). `SavedStateHandle` – brak w module. Wynik porównania (String) nie jest przechowywany:
liczy go `knownPlaceNear` przy rysowaniu (`VehicleDataFormatting.kt:105-112`).

## 2. SettingsStore, Room, pliki, kopia

**OK.** Grep w zmienionych plikach: brak `SettingsStore`, `edit {`, `putDouble/String`. Kontroler nie ma
cache'u na dysku (`RemoteDataController.kt:33-34`), a `knownPlaces` czyta wyłącznie archiwum. Kopia archiwum
nie dostaje nowego zapisu; historyczne współrzędne tras już w nim były i ten commit ich nie dopisuje.
Dokładna ostatnia pozycja nie trafia do archiwum.

## 3. Log.*, println, wyjątki, diagnostyka

**OK.** Brak `Log.`/`println` w zmienionych plikach i kontrolerze. Wyjątek z `findPlaces` jest połykany bez treści:
`RemoteDataScreens.kt:207-209` `} catch (e: Exception) {` / `null` / `}`. Kontroler również nie pokazuje tekstu:
`RemoteDataController.kt:73-76` `// The text of the exception is not shown (docs/SECURITY.md).` / `failed = true` / `null`.
Diagnostyka nie zawiera pozycji: grep `latitude|longitude|location|współrzęd` w `DiagnosticsFormatting.kt`
i `DiagnosticsScreen.kt` – 0 trafień. Nie sprawdzono logów biblioteki HTTP; w `core/toyota` brak `Log.`/`println`.

## 4. Źródło znanych miejsc i zapis przy wczytaniu

**OK.** `AppRoot.kt:345-348` `findPlaces = { ... knownPlaces(parkingLogOf(loadTrips()), ZoneId.systemDefault()) }`.
`loadTrips` to `syncEngine.trips()` (`AppContainer.kt:393`), czyli odczyt Room + pamięciowy `tripsSnapshot`
(`SyncEngine.kt:161-171`; jedyny zapis to `tripsSnapshot = ...` w pamięci, linia 170). Dalej funkcje czyste:
`parkingLogOf` (`ParkingFormatting.kt:56`), `parkingLog` (`ParkingStops.kt:95`), `knownPlaces` (`TripTypes.kt:64-76`).
Wczytanie nie zapisuje niczego nowego.

## 5. Nazwa miejsca poza ekranem

**OK.** Nazwa jest tylko rysowana: `RemoteDataScreens.kt:224-226` `knownPlaceNear(...)` → `Text(it, ...)`.
`RemoteScreen` ma wyłącznie przycisk „Wstecz” (`RemoteDataScreens.kt:49-52`); w pliku brak `EXTRA_TEXT`,
`Clipboard`, `ClipData`, `Share`. `knownPlaceNear`/`PLACE_HOME`/`PLACE_WORK` poza tym ekranem występują tylko
na ekranie „Postoje” (`ParkingScreen.kt:199-200`), nie w powiadomieniach, widżecie ani Android Auto
(grep w `notify/`, `widget/`, `car/` – 0 trafień). Auto i widżet dostają tylko `WidgetSnapshot`/`ConsumptionView`.

## Wniosek

**Ryzyka: 0.** Brak zapisu współrzędnych lub nazwy do stanu procesu, preferencji, bazy, plików, kopii i logów.
