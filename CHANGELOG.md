# Changelog

## Unreleased
- Szkielet repozytorium i specyfikacja domeny (docs/SPEC.md)
- Logika domeny: wykrywanie tankowań, potwierdzanie ceny, koszt tras (52 testy)
- Dokumentacja procesu: PRD, ARCHITECTURE, DECISIONS, ROADMAP, SECURITY, DOD, AGENTS.md
- Narzędzia jakości backend: ruff (format+lint) + mypy strict dla app/domain, requirements-dev.txt z wersjami, CI GitHub Actions (ruff/mypy/pytest, akcje na SHA) + skan sekretów gitleaks
- Decyzja ADR-006: aplikacja tylko na telefonie, klient Toyoty w Kotlinie
- Projekt Android (AGP 9.4.1, Kotlin 2.4.21, Compose): moduł `core` z portem domeny (53 testy) + szkielet `app`
- CI: build i testy Androida
- Klient Toyoty w Kotlinie (`core/toyota`): logowanie OAuth, odświeżanie tokenu, ponowienia 429/5xx, pojazdy, telemetria, trasy z paginacją, mapowanie na domenę (29 testów, bez sieci)
- NOTICE (MIT pytoyoda), THIRD_PARTY_LICENSES.md, wyjątki gitleaks dla fikcyjnych JWT i publicznego klucza aplikacji
