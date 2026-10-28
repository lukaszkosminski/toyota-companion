# Roadmap

## DONE
- Narzędzia jakości i CI (ruff, mypy, pytest, gitleaks)
- Port domeny do Kotlina (`android/core`, 53/53 testów zgodnych z Pythonem)
- Klient Toyoty w Kotlinie: logowanie, pojazdy, telemetria, trasy (testy na mock serwerze)

## NOW
- Test klienta Toyoty na prawdziwym koncie (wymaga danych logowania właściciela)

## NEXT
- Baza Room (trasy, tankowania, odczyty) + synchronizacja w tle (WorkManager)
- UI: lista tras z kosztem, formularz ceny, powiadomienie o tankowaniu
- Średnia ważona ceny w baku (ADR-005)
- Eksport CSV, ręczny eksport/import archiwum, przypomnienie o backupie (F6–F8)

## LATER (kolejność wg wartości / kosztu)
- F9 dziennik tankowań + „komputer vs faktyczne”, F2a szacunek litrów
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
