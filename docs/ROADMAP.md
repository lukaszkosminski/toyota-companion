# Roadmap

## DONE
- Narzędzia jakości i CI (ruff, mypy, pytest, gitleaks)
- Port domeny do Kotlina (`android/core`, Python 76 / Kotlin 76 testów domeny, nazwy 1:1)
- Klient Toyoty w Kotlinie: logowanie, pojazdy, telemetria, trasy (33 testy na mock serwerze)
- ADR-005: średnia ważona ceny paliwa w baku (`tank_price_after_refuel`, `compute_trip_cost`)
- Pochodzenie litrów (`liters_source`: receipt/estimate/derived) i `filled_to_full` w domenie
- Archiwum: DTO trasy zachowuje współrzędne start/koniec, `scores`, `hdc`, prędkość i czas postoju;
  paginacja odrzuca pętlę i ujemny offset
- Synchronizacja w `core` (SPEC §8) z testami na repozytoriach w pamięci
- Baza Room 3 (`app/data/db`, schemat v1) przechodząca ten sam kontrakt co repozytoria w pamięci
- Szyfrowane przechowywanie loginu, hasła i tokenów (Android Keystore, ADR-007), WorkManager co 30 min
- Weryfikacja loginu w `core` (`LoginCheck`, `verifyLogin` – tylko logowanie, świeży magazyn tokenów)
- UI: ekran logowania (weryfikacja → CredentialStore → SyncScheduler.onLogin) i ekran statusu synchronizacji z „Synchronizuj teraz” i „Wyloguj” (SPEC §9)
- Wygląd UI (Material 3, `ToyotaTheme`, edge-to-edge, jasny/ciemny, `PullToRefreshBox` – odświeżenie tylko z lokalnej bazy)
- UI: lista tras z kosztem (`SyncEngine.trips`, SPEC §10): miesiące w strefie lokalnej, sumy km/l/zł, „brak ceny”, „cena domyślna”
- UI: formularz ceny tankowania (`SyncEngine.confirmRefuel`, SPEC §11): lista oczekujących, cena albo litry i kwota,
  odrzucenie wpisu i przeliczenie kosztów (F2, F4)
- UI: domyślna cena paliwa jako ustawienie telefonu (`defaultPriceProvider`, `recomputeCostsNow`, SPEC §12):
  ekran „Cena domyślna”, zapis w `SettingsStore`, przeliczenie kosztów bez synchronizacji (F2b)

- UI: pytanie o cenę zaraz po tankowaniu (`RefuelNotifier`, `RefuelPromptController`, SPEC §13):
  powiadomienie z deep linkiem do formularza ceny i okno w aplikacji (F2c)
- Szacunek litrów z czujnika i wybór źródła litrów (`estimate_refuel_liters`, SPEC §2a, §11): „Użyj szacunku”
  / „Z paragonu” w formularzu ceny, `liters_source` zapisywane przez `SyncEngine.confirmRefuel` (F2a)
- Odporność na błędy lokalnego archiwum: `SyncErrorKind.STORAGE`, synchronizacja bez wyjątków (SPEC §8.4–§8.5)
- Kopia archiwum: eksport/import całego archiwum do pliku JSON i przypomnienie o kopii po 30 dniach (SPEC §14, F7, F8)
- Arkusz tras w CSV dla polskiego arkusza, ekran „Kopia i eksport” (SPEC §14.4, F6)

## NOW
- Test klienta Toyoty na prawdziwym koncie: `./gradlew -q :core:liveCheck` (uruchamia właściciel, docs/HANDOFF.md)

## NEXT
- Do decyzji właściciela – wymagania MVP F1–F8 są zrobione; kandydat z LATER: F9 dziennik tankowań
  + „komputer vs faktyczne” (litry z paragonu są już zapisywane jako `receipt`, F2a)

## LATER (kolejność wg wartości / kosztu)
- F9 dziennik tankowań + „komputer vs faktyczne” (tylko litry z paragonu, SPEC §2)
- F11 udział EV, F12 styl jazdy i ten sam odcinek, F10 statystyki wieloletnie
- F16 typy tras, F17 dziennik parkowania, F13 anomalie spalania
- F15 pogoda (Open-Meteo), F14 prognoza tankowania

## DO ZBADANIA
- F14 źródło cen paliw na stacjach (API/licencja)
- F18 Android Auto (dozwolone kategorie aplikacji)

## DEFERRED (brak danych w API)
- Ukryta rezerwa paliwa, mapa zdarzeń hamowania, model akumulatora 12 V

## NOT PLANNED
- Zdalne sterowanie autem, regiony poza EU
