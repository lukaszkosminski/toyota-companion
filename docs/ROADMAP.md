# Roadmap

## DONE
- Narzędzia jakości i CI (ruff, mypy, pytest, gitleaks)
- Port domeny do Kotlina (`android/core`, 53/53 testów zgodnych z Pythonem)

## NOW
- Klient Toyoty w Kotlinie: logowanie (port `pytoyoda/controller.py`)
- Endpointy: lista tras, szczegóły trasy, telemetria (`fuel_level`, odometr)

## NEXT
- Baza Room (trasy, tankowania, odczyty) + synchronizacja w tle (WorkManager)
- UI: lista tras z kosztem, formularz ceny, powiadomienie o tankowaniu
- Średnia ważona ceny w baku (ADR-005)
- Eksport CSV i backup archiwum

## LATER
- Porównanie spalania: dystrybutor vs. komputer

## DEFERRED (brak danych w API)
- Ukryta rezerwa paliwa, mapa zdarzeń hamowania, model akumulatora 12 V

## NOT PLANNED
- Zdalne sterowanie autem, regiony poza EU
