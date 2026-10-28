# toyota-companion

Nieoficjalna aplikacja towarzysząca do Toyoty (Europa) oparta o [pytoyoda](https://github.com/pytoyoda/pytoyoda).

- `backend/` – Python (FastAPI + pytoyoda + SQLite): archiwum tras, wykrywanie tankowań, koszt tras w PLN
- `android/` – aplikacja Kotlin / Jetpack Compose (klient backendu)
- `docs/SPEC.md` – specyfikacja funkcji

Dane logowania Toyoty nigdy nie trafiają do repozytorium (`credentials.json`, `.env` są w `.gitignore`).
