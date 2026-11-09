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
- Dziennik tankowań i spalanie „komputer vs faktyczne” od tankowania do pełna do tankowania do pełna (SPEC §15, F9)
- Ikona aplikacji (adaptacyjna, wektorowa) i poprawka litrów potwierdzonego tankowania z paragonu (SPEC §15.3)
- Statystyki: jazda na prądzie i ranking tras, lata, pory roku, rok do roku (SPEC §16–§17, F10, F11); „Postoje” (SPEC §18, F17)
- Styl jazdy: wynik eco po miesiącach, ten sam odcinek, anomalie spalania (SPEC §19, F12, F13)
- Prognoza dnia tankowania na ekranie statusu (SPEC §20, F14) i typy tras z automatycznym Dom/Praca (SPEC §21, F16)
- Pogoda i spalanie z Open-Meteo (F15, SPEC §23), tempo jazdy a spalanie (F19, §22), prędkość × temperatura (F24, §28),
  pogoda i rodzaj jazdy przy trasie (F25, F27)
- Spalanie i koszty miesięczne na statusie (F20, F26, §24), spalanie deklarowane (F29, §30), alert o rosnącym spalaniu (F30, §31)
- Mapa trasy i postojów na kafelkach OSM, przebieg trasy z GPS Toyoty, zapis przebiegów, mapa ciepła (F21, F23, F32, F33, §26–§27, §33–§34);
  ceny 95 z trojmiasto.pl (F22, §25)
- Podsumowanie roku (F28, §29), widżet (F31, §32), Android Auto tylko do odczytu (F34, §35), jazda na prądzie w tygodniach (F35, §36)
- Wydania na GitHub Releases i aktualizacja w „Opcjach” (F36, §37, ADR-016): v0.2.0 (2026-11-06), v0.2.1 (R8 – APK 4,3 MB,
  ikony Material Symbols, kontrast; ADR-017), v0.2.2 (przegląd jakości C22: odświeżanie statusu, liczebniki, jedno
  odnowienie tokenu, testy migracji Room, ADR-019 jedno konto = jedno auto), v0.2.5 (w przygotowaniu: historia serwisowa
  i powiadomienia Toyoty – F44/F45, SPEC §45–§46)
- Wydatki poza paliwem (F37, §38, ADR-018), koszt całkowity auta (F38, §39, ADR-020), cele eco (F39, §40)
- Jakość (C22): wspólna kopia listy tras po synchronizacji zamiast kilku pełnych odczytów (§10.1)
- Historia serwisowa z Toyoty (`/v1/servicehistory/vehicle/summary`) jako kafelek „Historia serwisowa” (F44, SPEC §45,
  plan C25): data, przebieg z jednostką, kategoria, czynności i dealer (etykieta „Dealer”: `servicingDealer`, fallback
  `serviceProvider`; oba puste = „brak danych”), bez `serviceHistoryId`, `roNumber`, `notes` i `customerCreatedRecord`
- Powiadomienia Toyoty (`/v2/notification/history`) jako kafelek „Powiadomienia” (F45, SPEC §46, plan C25): data, stan
  przeczytania, kategoria i typ, treść; wszystkie wpisy, najnowsze pierwsze; VIN w treści maskowany; `null` w `payload`
  (konto bez powiadomień) traktowany jako brak danych
- Podstawa pod przyszłe przypomnienia serwisowe (interwał km/miesiące) i powiadomienie Androida o nowym wpisie Toyoty:
  dane są już mapowane w `core/domain`, brakuje stanu w bazie i reguł – poza zakresem C25
- Diagnostyka synchronizacji (F46, SPEC §47, plan C26): czasy ostatniego udanego pobrania pojazdów/konta, telemetrii
  i tras, czas stanu auta z Toyoty (`occurrence_date`), czas i kod ostatniego błędu (np. 429, 403), liczniki archiwum;
  po chwilowym błędzie ekrany Toyoty zachowują ostatnie dobre dane; raport do schowka/udostępnienia bez VIN, GPS i tokenów
  (Room 7: nowe kolumny `sync_state`, migracja automatyczna)

## NOW
- W toku (2026-11-08, agenci): pola autostrady i przekroczeń prędkości w archiwum tras (PRD F40, Room v6) oraz ekrany
  tylko do odczytu „Stan pojazdu”, „Lokalizacja”, „Stan techniczny” z chmury Toyoty, bez budzenia auta (SPEC §42–§44)
- Właściciel: test klienta na prawdziwym koncie `./gradlew -q :core:liveCheck` (docs/HANDOFF.md); przejście telefonu z wersji
  debug na wydanie (eksport archiwum → odinstalowanie → instalacja → logowanie → import)

## NEXT
- Drobne z przeglądu C22 (briefs/toyota-companion/C22-quality-review.md): D2–D14 – m.in. wyścig przeładowań w wydatkach
  i pogodzie, data zależna od ustawień regionalnych, `collectAsState` bez cyklu życia, limit rozmiaru strony cen, testy stylu jazdy
- Wydanie v0.2.3 po zakończeniu prac w toku

## LATER (kolejność wg wartości / kosztu)
- Automatyczna kopia archiwum do wybranego folderu (rotacja), przypomnienia serwisowe (przebieg, przegląd, OC), ewidencja
  przebiegu (kilometrówka) – propozycje z 2026-11-08, niewybrane
- Przeniesienie logiki domenowej z `app/ui` do `core/domain` z lustrem w Pythonie przy okazji zmian (C22 D9), podział
  największych plików (D10)
- Propozycje z przeglądu ha_toyota (2026-11-09), niewybrane; tylko odczyt, bez budzenia auta; endpointy są w pytoyoda,
  ale nie sprawdzone na koncie właściciela – najpierw dodać je do `:core:liveCheck`:
  - Lista kontrolek ostrzegawczych (`dashboard.warning_lights`: nazwa, status, kategoria) zamiast samej liczby w F43
    i powiadomienie o nowej kontrolce – do sprawdzenia, czy auto zwraca te dane
- Propozycje z przeglądu C26 (2026-11-09), niewybrane; tylko dane i endpointy już obecne w projekcie, bez budzenia auta:
  - Budżet miesięczny na paliwo i wydatki: postęp z istniejących kosztów tras i `expenses`, na ekranie statusu,
    plus lokalne powiadomienie przy progu; bez sieci i bez nowych pól Toyoty
  - Historia i trend ceny paliwa: zapisywać lokalne snapshoty z istniejących pobrań trojmiasto.pl po dotknięciu
    (F22, §25) i porównywać ostatnio zapłaconą cenę z bieżącą ceną stacji
  - Eksport CSV tankowań i wydatków: rozszerzenie istniejącego eksportu tras CSV (F6, §14.4)
  - Powiadomienie o niskim paliwie: na podstawie ostatniej telemetrii (`distanceToEmptyKm`/poziom paliwa) po
    synchronizacji, z wiekiem odczytu w treści; bez wybudzania auta
  - Lokalny licznik nieprzeczytanych powiadomień Toyoty: dodać `messageId` do `ToyotaNotification` (jest już w DTO)
    i lokalną tabelę „widziane”; bez oznaczania jako przeczytane po stronie Toyoty (endpoint tego nie ma, §46);
    rozwinięcie podstawy opisanej w DONE

## DO ZBADANIA
- Wybór pojazdu dla konta z kilkoma autami – dziś świadomie nie (ADR-019)
- Nieaktualny stan w F41: obserwacja z ha_toyota (`refresh_strategy.py`, 2026-04) – 429 + APIGW-403 przy pustej lub
  starej pamięci podręcznej Toyoty – dotyczyła `/v1/global/remote/status`, wycofanego przez Toyotę ok. 26.06.2026
  (pytoyoda PR #268); F41 czyta już `/v1/vehicle/status`. Do sprawdzenia w `:core:liveCheck`, czy nowy endpoint zachowuje
  się podobnie; niezależnie od tego: wyraźny wiek danych i komunikat błędu, który nie wygląda jak błąd logowania
- Alert „auto zostało otwarte” po postoju: koniec jazdy rozpoznany po zmianie przebiegu między synchronizacjami
  (jak czujnik `driving` w ha_toyota), potem jeden odczyt zamków bez wybudzania; ryzyko nieaktualnych danych (punkt wyżej),
  a wybudzanie łamie zakaz `refresh_status()` (12 V)
- Temperatura w kabinie (`/v1/vehicle/climate-status`, tylko odczyt): aktualne dane prawdopodobnie wymagają wcześniej
  `refresh-climate-status`, czyli budzenia auta; mała wartość

## ZAMKNIĘTE BADANIA
- F14/F22 ceny na stacjach: strona trojmiasto.pl po dotknięciu (SPEC §25); F18 Android Auto: wersja ograniczona jako F34 (ADR-014)

## DEFERRED (brak danych w API)
- Ukryta rezerwa paliwa, mapa zdarzeń hamowania, model akumulatora 12 V (potwierdzone 2026-11-01: napięcia 12 V i poziomu baterii hybrydowej nie ma w API dla tego auta – briefs/C16-battery.md)

## NOT PLANNED
- Zdalne sterowanie autem, regiony poza EU
