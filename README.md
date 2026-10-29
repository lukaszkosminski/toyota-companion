# toyota-companion

Nieoficjalna aplikacja towarzysząca do Toyoty (Europa) oparta o [pytoyoda](https://github.com/pytoyoda/pytoyoda).

- `android/` – aplikacja Kotlin / Jetpack Compose; `core` = logika domeny i klient Toyoty (czysty JVM)
- `backend/` – wykonywalna specyfikacja logiki domeny w Pythonie (testy referencyjne)
- `docs/` – PRD, SPEC, ARCHITECTURE, SECURITY, DECISIONS, ROADMAP, DOD
- `AGENTS.md` – zasady dla agentów AI

## Android

Wymaga JDK 21 i Android SDK (platforma 37).

```bash
cd android
./gradlew :core:test :app:assembleDebug   # APK: app/build/outputs/apk/debug/
```

## Testy specyfikacji (Python)

```bash
cd backend
python3 -m venv .venv
.venv/bin/pip install -r requirements-dev.txt
.venv/bin/ruff format --check && .venv/bin/ruff check && .venv/bin/mypy
.venv/bin/python -m pytest -q
```

## Ograniczenia
- Tylko Europa (ograniczenie pytoyoda), nieoficjalne API Toyoty – może się zmienić bez ostrzeżenia.
- Archiwum tras działa od momentu uruchomienia; trasy starsze niż ~12 mies. są już usunięte po stronie Toyoty.

Dane logowania Toyoty nigdy nie trafiają do repozytorium (`credentials.json`, `.env` są w `.gitignore`).
