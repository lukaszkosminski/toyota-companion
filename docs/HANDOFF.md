# Stan prac (2026-10-29)

## Gotowe
- Logika domeny (tankowania, koszt tras): Python `backend/` (specyfikacja, 76 testów) + Kotlin `android/core/.../domain` (76 testów, te same nazwy).
- ADR-005: koszt trasy liczony ze średniej ważonej ceny paliwa w baku (43 l); wystarczy podać cenę, litry szacowane z czujnika.
- Klient Toyoty w Kotlinie `android/core/.../toyota` (port pytoyoda v6.0.0): logowanie, odświeżanie tokenu, pojazdy, telemetria (poziom paliwa, bateria, zasięg), trasy z paginacją i polami archiwum (33 testy na mock serwerze).
- Szkielet aplikacji `android/app` (pusty ekran Compose, APK się buduje, uprawnienie `INTERNET`).
- CI: ruff/mypy/pytest, Android build + testy, gitleaks 8.30.1.

## Następny krok
Test klienta na prawdziwym koncie MyToyota (uruchamia właściciel; hasło nie trafia do repo ani rozmowy):

```bash
source ~/Android/env.sh && cd android
read -rp "Login MyToyota: " TOYOTA_USERNAME; read -rsp "Haslo: " TOYOTA_PASSWORD; echo
export TOYOTA_USERNAME TOYOTA_PASSWORD
./gradlew -q :core:liveCheck
unset TOYOTA_USERNAME TOYOTA_PASSWORD
```

Program tylko czyta (bez `refresh_status`), pokazuje VIN z maską i nie wypisuje pozycji GPS ani tokenów.
Sprawdzone 2026-10-29: fikcyjny login dostaje od prawdziwego serwera Toyoty „User Not Found”,
czyli pierwszy etap logowania działa z prawdziwym API.

## Środowisko lokalne
- `source ~/Android/env.sh` – JDK 21, Android SDK 37, Gradle 9.8.2
- `cd android && ./gradlew --no-daemon -q :core:test :app:assembleDebug`
- `cd backend && .venv/bin/ruff format --check && .venv/bin/ruff check && .venv/bin/mypy && .venv/bin/python -m pytest -q`
- Referencja API: `/home/lk/projekty/pytoyoda-ref` (pytoyoda v6.0.0)
- gitleaks lokalnie: `~/Android/tools/gitleaks git . --log-opts="--all"`

## Proces z agentem pi2
Kontekst → plan bez kodu → review i akceptacja → implementacja → niezależne review (uruchomienie testów,
porównanie z pytoyoda/Pythonem, odczyt plików z dysku) → commit. Zasady: `AGENTS.md`.
Uwaga: narzędzie pi2 potrafi zapisać `***` zamiast `${{` w plikach – sprawdzać zapisane pliki.
