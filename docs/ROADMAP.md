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

## NOW
- Etap B: Room (implementacje repozytoriów, test kontraktowy), Keystore, WorkManager – KSP 2.3.12 + Room 3.0.3 sprawdzone z Kotlin 2.4.21
- Test klienta Toyoty na prawdziwym koncie (wymaga danych logowania właściciela)

## NEXT
- UI: lista tras z kosztem, formularz ceny (z wyborem źródła litrów), powiadomienie o tankowaniu
- Eksport CSV, ręczny eksport/import archiwum, przypomnienie o backupie (F6–F8)

## LATER (kolejność wg wartości / kosztu)
- F9 dziennik tankowań + „komputer vs faktyczne”, F2a szacunek litrów w UI
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
