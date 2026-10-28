# AGENTS.md – zasady dla agentów AI

## Źródło prawdy
`docs/` jest nadrzędne: PRD.md, SPEC.md, ARCHITECTURE.md, SECURITY.md, DECISIONS.md, DOD.md.
Jeśli kod i docs się różnią albo czegoś brakuje – zgłoś konflikt, nie zgaduj.

## Workflow (obowiązkowy)
1. Kontekst: przeczytaj docs i powiązany kod, streść zrozumienie, wypisz konflikty/braki. Bez zmian w kodzie.
2. Plan: pliki do zmiany, zależności, testy, przypadki brzegowe, ryzyka. Bez kodu.
3. Czekaj na akceptację planu.
4. Implementacja: najmniejsza poprawna zmiana, bez zmian w niepowiązanych plikach.
5. Walidacja: `cd backend && .venv/bin/python -m pytest -q` (+ ruff/mypy gdy skonfigurowane).
6. Aktualizacja docs w tej samej zmianie.

## Zakazy
- Nie wymyślaj wymagań, pól bazy, endpointów ani pól API Toyoty – sprawdź w kodzie pytoyoda.
- Nie commituj i nie pushuj bez polecenia.
- Nie dotykaj sekretów; nie loguj haseł, tokenów, VIN, GPS.
- Nie wywołuj `refresh_status()` automatycznie (drenuje akumulator 12 V).

## Zakończenie
Raport: lista zmienionych plików, wynik walidacji, decyzje podjęte bez specyfikacji.
