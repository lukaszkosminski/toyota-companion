# Stan prac (2026-10-28)

## Gotowe
- Logika domeny (tankowania, koszt tras): Python `backend/` (specyfikacja, 53 testy) + Kotlin `android/core/.../domain` (53 testy, te same nazwy).
- Klient Toyoty w Kotlinie `android/core/.../toyota` (port pytoyoda v6.0.0): logowanie, odświeżanie tokenu, pojazdy, telemetria, trasy (29 testów na mock serwerze).
- Szkielet aplikacji `android/app` (pusty ekran Compose, APK się buduje).
- CI: ruff/mypy/pytest, Android build + testy, gitleaks 8.30.1.

## Następny krok
Test klienta na prawdziwym koncie MyToyota: mały program czytający login/hasło ze zmiennych
środowiskowych, uruchamiany przez właściciela w terminalu (hasło nie trafia do repo ani rozmowy).
Potem: baza Room + synchronizacja w tle, UI (lista tras z kosztem, formularz ceny), ADR-005 (średnia ważona).

## Środowisko lokalne
- `source ~/Android/env.sh` – JDK 21, Android SDK 37, Gradle 9.8.2
- `cd android && ./gradlew --no-daemon -q :core:test :app:assembleDebug`
- Referencja API: `/home/lk/projekty/pytoyoda-ref` (pytoyoda v6.0.0)
- gitleaks lokalnie: `~/Android/tools/gitleaks git . --log-opts="--all"`

## Proces z agentem pi2
Kontekst → plan bez kodu → review i akceptacja → implementacja → niezależne review (uruchomienie testów,
porównanie z pytoyoda/Pythonem, odczyt plików z dysku) → commit. Zasady: `AGENTS.md`.
Uwaga: narzędzie pi2 potrafi zapisać `***` zamiast `${{` w plikach – sprawdzać zapisane pliki.
