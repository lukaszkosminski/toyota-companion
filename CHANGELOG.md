# Changelog

## Unreleased
- Szkielet repozytorium i specyfikacja domeny (docs/SPEC.md)
- Logika domeny: wykrywanie tankowań, potwierdzanie ceny, koszt tras (76 testów)
- Dokumentacja procesu: PRD, ARCHITECTURE, DECISIONS, ROADMAP, SECURITY, DOD, AGENTS.md
- Narzędzia jakości backend: ruff (format+lint) + mypy strict dla app/domain, requirements-dev.txt z wersjami, CI GitHub Actions (ruff/mypy/pytest, akcje na SHA) + skan sekretów gitleaks
- Decyzja ADR-006: aplikacja tylko na telefonie, klient Toyoty w Kotlinie
- Projekt Android (AGP 9.4.1, Kotlin 2.4.21, Compose): moduł `core` z portem domeny (76 testów) + szkielet `app`
- CI: build i testy Androida
- Klient Toyoty w Kotlinie (`core/toyota`): logowanie OAuth, odświeżanie tokenu, ponowienia 429/5xx, pojazdy, telemetria, trasy z paginacją, mapowanie na domenę (33 testy, bez sieci)
- NOTICE (MIT pytoyoda), THIRD_PARTY_LICENSES.md, wyjątki gitleaks dla fikcyjnych JWT i publicznego klucza aplikacji
- ADR-005: koszt trasy = średnia ważona ceny paliwa w baku (43 l); `tank_price_after_refuel`/`tankPriceAfterRefuel`
- Domenowe pochodzenie litrów: `liters_source` (receipt/estimate/derived) i `filled_to_full`
- ADR-005 poprawka: średnia ważona liczona względem sumy wymieszanego paliwa (wynik zawsze między cenami); szacunek z czujnika i tankowanie z samą ceną aktualizują cenę w baku
- Kanoniczne `id` tankowania: UTC ISO-8601 z `Z` (Python = Kotlin `Instant.toString()`)
- Telemetria: poziom baterii i zasięg elektryczny; daty bez strefy interpretowane jako UTC
- Archiwum tras: DTO zachowuje współrzędne start/koniec, `scores`, `hdc`, `durationIdle`, `maxSpeed`, `nightTrip`; paginacja wykrywa pętlę i ujemny offset
- Synchronizacja w `core` (SPEC §8): interfejsy repozytoriów z testem kontraktowym, `TripRecord` z polami archiwum, `ToyotaGateway`, `SyncEngine` (okno 365 dni, zakładka 2 dni, deduplikacja, wykrywanie tankowań, przeliczenie kosztów, błędy bez utraty danych, jedna synchronizacja naraz)
- Android: uprawnienie `INTERNET`; `backend/pyproject.toml` bez nieużywanych zależności (pytoyoda, FastAPI, uvicorn)
- `:core:liveCheck` – ręczny test klienta na prawdziwym koncie MyToyota (dane logowania ze zmiennych środowiskowych, tylko odczyt, poza CI)
- Baza Room 3 w `app/data/db` (v1, schemat w `app/schemas`): trasy, tankowania, odczyty paliwa, koszty, pojazdy, stan synchronizacji; `Room*Repository`; `RepositoryContract` przeniesiony do `core/testFixtures` i uruchamiany na pamięci (core, 163 testy bez zmian) oraz na Room (app, Robolectric, 15 scenariuszy + 6 testów konwerterów/schematu)
