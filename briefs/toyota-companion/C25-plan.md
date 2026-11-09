# C25 – plan: historia serwisowa i powiadomienia Toyoty (F44, F45)

Status: **zaakceptowany 2026-11-09** (pytania 1-4 rozstrzygnięte przez właściciela) i **zaimplementowany** – kod, testy i docs w tej samej zmianie; czeka na sondę struktury na żywym koncie.
Data: 2026-11-09. Źródło pomysłów: przegląd ha_toyota 22c2c79 i pytoyoda v6.0.0.

## 1. Cel

Dwa nowe, **tylko do odczytu** kafelki w menu statusu, w tym samym wzorcu co F41–F43
(`docs/SPEC.md` §42–§44): odczyt z chmury Toyoty po dotknięciu, bez `refresh_status()`,
bez zapisu w bazie, bez budzenia auta.

- **F44 Historia serwisowa** – ostatni serwis i lista wpisów (data, przebieg, kategoria, czynności,
  notatki, serwis, dealer, numer RO, wpis właściciela).
- **F45 Powiadomienia Toyoty** – lista powiadomień z chmury (data, kategoria, typ, treść, przeczytane),
  najnowsze pierwsze; VIN w treści maskowany.

Poza zakresem: przypomnienia serwisowe (interwał km/miesiące) i powiadomienie Androida o nowym wpisie –
to osobne funkcje (LATER), bo wymagają stanu w bazie i logiki w `core/domain` z lustrem w Pythonie.

## 2. Kontekst – co już jest

- `Probe.kt` sondy `TOYOTA_PROBE=structure` **już odpytuje** `/v1/servicehistory/vehicle/summary` (poz. [7])
  i `/v2/notification/history` (poz. [6]), więc weryfikacja na żywym koncie nie wymaga zmian w kodzie:
  `./gradlew -q :core:liveCheck` z `TOYOTA_PROBE=structure` (HANDOFF).
- Wzorzec odczytu: `ToyotaApi` → `VehicleDataMapper` → `core/domain/VehicleData.kt` →
  `VehicleDataGateway` → `app/ui/RemoteDataController.kt` + `RemoteDataScreens.kt` → `AppRoot.kt`.
- `VehicleDto` **nie** parsuje `features`/`extendedCapabilities` z `/v2/vehicle/guid`.
  pytoyoda bramkuje historię serwisową przez `features.service_history` (`vehicle.py:197-206`),
  a powiadomienia przez `capable=True` (zawsze).

## 3. Endpointy i pola (z pytoyoda v6.0.0, nie sprawdzone na koncie)

**F44** `GET /v1/servicehistory/vehicle/summary` (`const.py:67`, `service_history.py:12-58`):
`payload.serviceHistories[]` z `serviceDate` (data), `mileage` (int) + `unit`, `serviceCategory`,
`operationsPerformed` (`Any`), `notes` (`Any`), `serviceProvider`, `servicingDealer` (`Any`),
`roNumber` (`Any`), `customerCreatedRecord` (bool), `serviceHistoryId`.

**F45** `GET /v2/notification/history` (`const.py:62`, `notifications.py:22-66`):
`payload[]` (strony, każda `vin` + `notifications[]`), wpis: `messageId`, `notificationDate` (datetime),
`isRead` (bool), `readTimestamp`, `message`, `type`, `category`, `displayCategory`, `iconUrl`.
Brak parametrów stronicowania i brak oznaczania jako przeczytane (`api.py:302-320`).

## 4. Pliki do zmiany

Android (`core`):

| Plik | Zmiana |
|---|---|
| `core/toyota/ToyotaConst.kt` | `SERVICE_HISTORY_ENDPOINT`, `NOTIFICATION_HISTORY_ENDPOINT` |
| `core/toyota/dto/ServiceHistoryDto.kt` (nowy) | `ServiceHistoryResponse`, `ServiceHistoryDto` (`@SerialName` 1:1 z pytoyoda) |
| `core/toyota/dto/NotificationDto.kt` (nowy) | `NotificationHistoryResponse`, `NotificationPageDto`, `NotificationDto`; `payload` i `notifications` jako nullable (pusty `payload` to `null`, nie błąd) |
| `core/toyota/ToyotaApi.kt` | `getServiceHistory(vin)`, `getNotifications(vin)` przez istniejące `decode` |
| `core/toyota/VehicleDataMapper.kt` | `toServiceHistory`, `toNotifications`; sortowanie po dacie malejąco, pomijanie wpisów bez daty na końcu |
| `core/domain/VehicleData.kt` | `ServiceRecord`, `ServiceHistory`, `ToyotaNotification`; wszystkie pola nullable |
| `core/vehicle/VehicleDataGateway.kt` | `serviceHistory(vin)`, `notifications(vin)` + implementacja |

Android (`app`):

| Plik | Zmiana |
|---|---|
| `app/ui/RemoteDataController.kt` | stan i odczyt dla obu ekranów, błędy jak §42 |
| `app/ui/ServiceScreens.kt` (nowy, nie `RemoteDataScreens.kt` – D10) | ekrany „Historia serwisowa” i „Powiadomienia” |
| `app/ui/AppRoot.kt` | dwa kafelki w menu statusu i trasy nawigacji |
| `app/ui/RemoteDataFormatting`/`VehicleDataFormatting.kt` | format daty i przebiegu (`km`/`mi` z `unit`) |

Dokumentacja: `docs/PRD.md` (F44, F45), `docs/SPEC.md` (§45, §46), `docs/ROADMAP.md` (przeniesienie z LATER),
`CHANGELOG.md`, `THIRD_PARTY_LICENSES.md` bez zmian (żadnej nowej zależności).

Backend Python: **bez zmian** – to odczyt i formatowanie, bez logiki domenowej, więc nie ma czego lustrzyć.

## 5. Testy

- `core` (mapper, fixtures z pytoyoda): pełny payload; `payload = null`; `serviceHistories = []`;
  `notifications = null` (konto bez powiadomień – pytoyoda rzuca wtedy `TypeError`); `notes`/`operationsPerformed`
  jako string, lista i `null`; `unit = "mi"`; wpis bez `serviceDate`; nieznane pola (ignorowane).
- `core` (gateway): mapowanie błędów 403/404 na `null` zgodnie z §42.
- `app` (controller): stan „ładowanie → dane”, „403 → Toyota nie udostępnia tych danych”, „200 bez payloadu →
  Toyota nie przysłała tych danych”, odczyt raz na otwarcie ekranu.
- `app` (formatting): maskowanie VIN w treści powiadomienia (zostają 4 ostatnie znaki), data w strefie lokalnej.

## 6. Przypadki brzegowe i zasady

- **Nie interpretujemy treści**: `operationsPerformed`/`notes` pokazujemy jako tekst, gdy są tekstem;
  lista → liczba pozycji; inny typ → „Toyota nie przysłała opisu”. Bez zgadywania kodów.
- **Brak danych ≠ 0**: brak listy → „Toyota nie przysłała tych danych.”, pusta lista → „Brak wpisów w historii Toyoty.”
- **VIN**: maskowany w `message` powiadomienia i nigdy nie trafia do logów, komunikatów błędów ani eksportu.
- **Prywatność**: oba ekrany tylko w pamięci – bez Room, eksportu archiwum, CSV i `rememberSaveable`;
  `reset()` przy „Wstecz” i wylogowaniu (jak §43).
- **Czas**: `notificationDate` i `serviceDate` w UTC z API, prezentacja w strefie lokalnej.
- **Odczyt**: raz na otwarcie ekranu, bez pętli i bez odpytywania w tle; brak `refresh_status()`.
- **Capability**: kafelek historii serwisowej pokazujemy tylko, gdy `/v2/vehicle/guid` zwraca
  `features.serviceHistory = true`; brak pola traktujemy jako „nie wiem” i pokazujemy kafelek,
  a ekran mówi „Toyota nie udostępnia tych danych dla tego auta.” przy 403/404.
- **Długość**: powiadomienia mogą być długie – lista bez limitu, ale przewijana; brak stronicowania w API.

## 7. Ryzyka

- Kształty payloadów są **niezweryfikowane** na koncie właściciela – przed kodem uruchomić sondę struktury
  (`TOYOTA_PROBE=structure`); jeśli pola się różnią, plan wraca do korekty.
- `features.serviceHistory` może nie istnieć w odpowiedzi dla tego auta → traktujemy jak „nie wiem” (wyżej).
- Toyota może wycofać endpointy bez ostrzeżenia (jak `/v1/global/remote/status` w 2026-06) → ekran pokazuje
  błąd odczytu, nie udaje pustej historii.

## 8. Odpowiedzi właściciela (2026-11-09)

1. Oba kafelki w menu statusu, obok „Stan pojazdu”, „Lokalizacja” i „Stan techniczny” – tak.
2. Wpis serwisowy pokazuje: dealera, datę, przebieg, kategorię i czynności. „Dealer” = `servicingDealer`, a gdy brak –
   `serviceProvider` (jedna etykieta „Dealer”); oba puste = „brak danych”. **Nie pokazujemy:** `serviceHistoryId`,
   `roNumber`, `notes`, `customerCreatedRecord`. Decyzję potwierdza sonda `TOYOTA_PROBE=structure` (właściciel odpala sam,
   bo wymaga loginu); DTO zostaje tolerancyjne (wszystkie pola nullable, nieznane klucze ignorowane), żeby wynik sondy
   nie wymusił przeróbek.
3. Wszystkie powiadomienia, bez limitu i bez „Pokaż więcej”.
4. Stan „przeczytane” widoczny w wierszu; aplikacja go nie zmienia, bo API na to nie pozwala.

## 9. Walidacja (po akceptacji i implementacji)

- Python: `cd backend && .venv/bin/ruff format --check && .venv/bin/ruff check && .venv/bin/mypy && .venv/bin/python -m pytest -q`
- Android: `source ~/Android/env.sh && cd android && ./gradlew --no-daemon -q :core:test :app:testDebugUnitTest :app:lintDebug :app:assembleRelease`
- DoD: wymaganie w PRD/SPEC przed kodem, docs w tym samym commicie, brak sekretów, review przed commitem.

## 10. Kolejność

1. Sonda struktury na żywym koncie (właściciel) i korekta planu, jeśli kształty się różnią.
2. F44 (historia serwisowa) – prostszy payload, zamyka punkt „przypomnienia serwisowe” z LATER.
3. F45 (powiadomienia) – osobny commit, bo maskowanie VIN i obsługa `null` to dodatkowe przypadki.
