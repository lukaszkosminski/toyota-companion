# toyota-companion

Nieoficjalna aplikacja towarzysząca do Toyoty (Europa) oparta o [pytoyoda](https://github.com/pytoyoda/pytoyoda).

- `backend/` – Python (FastAPI + pytoyoda + SQLite): archiwum tras, wykrywanie tankowań, koszt tras w PLN
- `android/` – aplikacja Kotlin / Jetpack Compose (klient backendu, planowana)
- `docs/` – PRD, SPEC, ARCHITECTURE, SECURITY, DECISIONS, ROADMAP, DOD
- `AGENTS.md` – zasady dla agentów AI

## Testy

```bash
cd backend
python3 -m venv .venv && .venv/bin/pip install pytest
.venv/bin/python -m pytest -q
```

## Ograniczenia
- Tylko Europa (ograniczenie pytoyoda), nieoficjalne API Toyoty – może się zmienić bez ostrzeżenia.
- Archiwum tras działa od momentu uruchomienia; trasy starsze niż ~12 mies. są już usunięte po stronie Toyoty.

Dane logowania Toyoty nigdy nie trafiają do repozytorium (`credentials.json`, `.env` są w `.gitignore`).
