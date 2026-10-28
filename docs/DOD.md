# Definition of Done (tier 1)

Źródło: „Definition of Done – Kanban Mapping” i „Roles RACI Process Workbook”.
Tier 1 = wewnętrzny/niskie ryzyko (ADR-003). Kontrolki, które nie mają zastosowania, są wypisane z powodem.

## Obowiązuje dla każdej zmiany
- Wymaganie i kryterium akceptacji w docs/PRD.md lub docs/SPEC.md przed kodem.
- Agent AI: kontekst → plan → akceptacja planu → implementacja → walidacja → aktualizacja docs (AGENTS.md).
- ruff format, ruff check, mypy, pytest przechodzą lokalnie i w CI.
- Testy jednostkowe obejmują zmienione zachowanie, przypadki brzegowe i błędy; testy deterministyczne, bez sieci.
- Brak sekretów w repo i logach; gitleaks w CI.
- Zależności zadeklarowane i przypięte; akcje GitHub przypięte do SHA.
- Dokumentacja aktualizowana w tym samym commicie co zachowanie.
- Review: każda zmiana agenta przechodzi przegląd kodu przed commitem.

## Nie dotyczy (tier 1) – z uzasadnieniem
| Kontrolka DoD | Powód |
|---|---|
| SBOM, podpisywanie artefaktów | Brak dystrybucji artefaktów |
| Testy obciążeniowe, autoskalowanie, SLO/error budget | Jeden użytkownik |
| OpenTelemetry, dashboardy, alerty, telemetry smoke test | Brak środowiska produkcyjnego; zamiast tego logi strukturalne (planowane) |
| Staging, pen-testy, Red/Blue Team | Projekt prywatny; wróci przy wystawieniu API publicznie |
| RTO/RPO, testy DR | Kopia zapasowa SQLite opisana przy warstwie storage |
| Role RACI (19 ról) | Właściciel = Product Owner; Claude = Solution Architect + review; pi2 = developer |
