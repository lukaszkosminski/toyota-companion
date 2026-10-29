# Stan prac (2026-10-29, wieczór)

## Gotowe
- Logika domeny (tankowania, koszt tras, ADR-005): Python `backend/` (specyfikacja, 76 testów) + Kotlin `android/core/.../domain` (76 testów, te same nazwy).
- Klient Toyoty w Kotlinie `android/core/.../toyota` (port pytoyoda v6.0.0); `:core:liveCheck` do testu na prawdziwym koncie.
- Synchronizacja `android/core/.../sync` (SPEC §8) + repozytoria `core/data` z kontraktem w `core/testFixtures`.
- `android/app`: baza Room 3 (v1), szyfrowane dane logowania i tokeny (Keystore, ADR-007), `SyncWorker` co 30 min.
- UI (SPEC §9): ekran logowania MyToyota + ekran statusu synchronizacji; `SessionController` ze `StateFlow` (bez ViewModela – `lifecycle-viewmodel-compose` nie jest zależnością), `CredentialVerifier` jako seam weryfikacji, hasło tylko w pamięci ekranu. Wylogowanie czyści token i dane oraz anuluje prace `toyota-sync`/`toyota-sync-now`, archiwum Room zostaje.
- Weryfikacja loginu w `core`: `LoginCheck` + `ToyotaGatewayFactory.verifyLogin` (świeży `InMemoryTokenStore`, bez `refresh_status`). Komunikat `INVALID_CREDENTIALS`: „Toyota odrzuciła logowanie – sprawdź login i hasło” (5xx z AMI też tu trafia).
- Wygląd (C1b, commit `dee6299`): `ToyotaTheme` – Material 3, jasny/ciemny wg systemu,
  dynamiczny kolor na Android 12+, poniżej własna paleta z czerwienią Toyoty; edge-to-edge (`enableEdgeToEdge`,
  przezroczyste paski w `res/values{,-night}/themes.xml`, paddingi z `Scaffold`, `imePadding`); formularz logowania
  wyśrodkowany (max 480 dp), `OutlinedTextField` + autouzupełnianie (`contentType`), tekstowy „Pokaż”/„Ukryj”,
  karta błędu `errorContainer`; status z `TopAppBar`, `PullToRefreshBox`, kartą liczników, chipem stanu,
  `HorizontalDivider` i `AlertDialog` potwierdzającym „Wyloguj”. `SessionController.reload()` powtarza tylko
  odczyt lokalnej bazy (bez API Toyoty i bez `refresh_status`).
- Testy: core 171, app 66 (Robolectric), Python 76. CI: ruff/mypy/pytest, Android (core + app unit), gitleaks 8.30.1.

## Następny krok
Sprawdzone na emulatorze (API 36) 2026-10-29: logowanie prawdziwym kontem, pierwsza synchronizacja (562 trasy, 1 odczyt),
sekrety zaszyfrowane w `secrets.xml` (Keystore), praca `toyota-sync` zaplanowana z wymogiem sieci, brak awarii i sekretów w logach.
- Do zrobienia: przejrzenie ekranów na emulatorze w motywem jasnym i nocnym (dynamiczny kolor na API 31+),
  następny krok: lista tras z kosztem (ROADMAP „NOW”).
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
- `cd android && ./gradlew --no-daemon -q :core:test :app:testDebugUnitTest :app:assembleDebug`
- `cd backend && .venv/bin/ruff format --check && .venv/bin/ruff check && .venv/bin/mypy && .venv/bin/python -m pytest -q`
- Emulator: AVD `toyota36` (API 36, x86_64); brakujące biblioteki systemowe rozpakowane bez sudo do `~/Android/emulator-libs`:
  `LD_LIBRARY_PATH=~/Android/emulator-libs/usr/lib/x86_64-linux-gnu $ANDROID_HOME/emulator/emulator -avd toyota36 -gpu swiftshader_indirect -no-audio &`
  potem `./gradlew -q :app:installDebug`
- Referencja API: `/home/lk/projekty/pytoyoda-ref` (pytoyoda v6.0.0)
- gitleaks lokalnie: `~/Android/tools/gitleaks git . --log-opts="--all"`

## Proces z agentami
Kontekst → plan bez kodu → review i akceptacja → implementacja → niezależne review (uruchomienie testów,
porównanie z pytoyoda/Pythonem, odczyt plików z dysku) → commit. Zasady: `AGENTS.md`.
Agent 1 = pi w panelu `w8:p1` (od 2026-10-29 model claude-sonnet-5-5).
Uwaga: lokalny model qwen potrafił zapisać `***` zamiast `${{` w plikach – sprawdzać zapisane pliki.
Niezacommitowane zmiany nieznanego autora: najpierw review i testy, dopiero potem praca na nich.
