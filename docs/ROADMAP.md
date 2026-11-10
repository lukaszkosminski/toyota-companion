# Roadmap

## DONE
- Najlepsza godzina wyjazdu (F53, SPEC §53, plan C34): w „Stylu jazdy” pod „Ten sam odcinek” do 5 najczęstszych odcinków z ≥ 5 przejazdami; mediana czasu przejazdu i l/100 km (Σ litrów / Σ km) wg pasma godziny startu (5–7 … 18–21, noc 21–5) i dnia (robocze, weekend); komórka od 3 przejazdów, wniosek od 2 komórek; czas lokalny strefy listy; tylko lokalne archiwum
- Kalkulator kosztu planowanej trasy (F52, SPEC §52, plan C33): przedział litrów i zł z pasm tempa F19 i z własnego archiwum; bez temperatury i sezonu (wersja 2, F24)
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
  odnowienie tokenu, testy migracji Room, ADR-019 jedno konto = jedno auto), v0.2.5 (2026-11-09: historia serwisowa
  i powiadomienia Toyoty, diagnostyka synchronizacji – F44–F46, SPEC §45–§47), v0.2.6 (2026-11-09: eksport CSV tankowań i wydatków – F47, SPEC §48),
  v0.2.7 (2026-11-10: budżet miesięczny i jego powiadomienie, krótkie trasy i zimny silnik, ślad CO₂ – F48–F51, SPEC §49–§51;
  czytelność statusu i podstron z przeglądu C32), v0.2.8 (2026-11-10: kalkulator kosztu trasy – F52, SPEC §52; kafelki z powrotem na dole statusu)
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
- Eksport CSV tankowań i wydatków (F47, SPEC §48, plan C27): dwa nowe arkusze obok tras – potwierdzone tankowania
  (data, licznik, poziomy, litry, cena, koszt, źródło litrów, „do pełna”) i wydatki poza paliwem (data, kategoria,
  kwota, notatka); ten sam format polskiego arkusza, bez VIN, GPS i `id`; przy pustej liście aplikacja nic nie zapisuje
  (systemowy wybór pliku może wcześniej utworzyć pusty plik)
- Budżet miesięczny (F48, SPEC §49, plan C28): karta „Budżet miesiąca” na statusie – jeden opcjonalny limit na telefon
  na paliwo (koszt jazdy z tras) i wydatki (data poniesienia), postęp z lokalnego archiwum, ostatnie dobre dane przy
  błędzie; limit w `settings`, przeżywa wylogowanie; bez sieci i bez budzenia auta
- Powiadomienie budżetowe (F49, SPEC §49, plan C29): kanał „Budżet”, progi 80% i 100% raz na miesiąc i próg, trigger
  po udanej synchronizacji i po zmianie wydatków, przełącznik w „Opcjach” (domyślnie włączony); reuse `POST_NOTIFICATIONS`
  bez nowej prośby, treść bez VIN/GPS
- Krótkie trasy i zimny silnik (F50, SPEC §50, plan C30): karta w „Statystykach” – trasy w pasmach długości (poniżej 2, 2–5, 5–10, 10–25, od 25 km) z udziałem tras i km, l/100 km jako Σ litrów / Σ km,
  nadwyżką krótkich tras wobec tras od 10 km (l/100 km, w archiwum litry i zł) i porównaniem zimnego startu (≥ 3 h od poprzedniej trasy) z ciepłym; progi liczności 5 i 10 tras; tylko lokalne archiwum. Sezon (zima) poza zakresem
- Ślad CO₂ (F51, SPEC §51, plan C31): szacunek z przepalonych litrów (2,3 kg CO₂/l benzyny, źródło NRCan) w nagłówku miesiąca na liście
  tras i w „Podsumowaniu roku” (ekran i tekst do udostępnienia), kg poniżej 1000 kg i tony od 1000 kg; bez prądu i produkcji auta, lokalnie, bez przechowywania.
  Wersja na pojedynczą trasę poza zakresem (pozycja nie obejmuje już „na trasę”)

## NOW
- Właściciel: test klienta na prawdziwym koncie `./gradlew -q :core:liveCheck` (docs/HANDOFF.md); przejście telefonu z wersji
  debug na wydanie (eksport archiwum → odinstalowanie → instalacja → logowanie → import)

## NEXT
- Propozycje zaakceptowane przez właściciela 2026-11-10 (tylko dane lokalne i już używane źródła, bez budzenia auta);
  każda dostaje numer F, wpis w PRD/SPEC i plan przed kodem, w tej kolejności:
  1. (zrobione jako F52, bez temperatury – patrz DONE) Kalkulator kosztu planowanej trasy
  4. Tygodniowe lub miesięczne podsumowanie w powiadomieniu: km, koszt, udział prądu, porównanie z poprzednim okresem
  6. Stacja przy tankowaniu: marka lub nazwa, średnia cena i faktyczne spalanie wg stacji
  7. Import historii tankowań i wydatków z CSV innych aplikacji (Fuelio, Drivvo)
  8. Zdjęcie paragonu przy tankowaniu lub wydatku (bez OCR; do decyzji, czy zdjęcia wchodzą do kopii archiwum)
  9. Utrata wartości auta w koszcie całkowitym (F38): cena zakupu i szacowana wartość wpisane ręcznie
  10. Opony: dwa komplety, przebieg każdego z tras między wymianami, przypomnienie o wymianie wg temperatury (F15)
  11. Odznaki i serie przy celach eco (F39) – najpierw sprawdzić, co już jest w SPEC
- Pozostałe drobne z przeglądu C22: D3–D7, D12 i D13 zrobione (commit a982de8); D2, D8, D11, D14 do potwierdzenia
  w kodzie; D9 i D10 są w LATER

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
  - Historia i trend ceny paliwa: zapisywać lokalne snapshoty z istniejących pobrań trojmiasto.pl po dotknięciu
    (F22, §25) i porównywać ostatnio zapłaconą cenę z bieżącą ceną stacji
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
