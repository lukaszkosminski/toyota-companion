# toyota-companion

Prywatna aplikacja towarzysząca na Androida do hybrydowej Toyoty w Europie. Pobiera dane z konta MyToyota przez nieoficjalne API (kod klienta jest portem [pytoyoda](https://github.com/pytoyoda/pytoyoda) v6.0.0 do Kotlina), archiwizuje trasy bezterminowo, liczy koszt paliwa każdej trasy i pokazuje spalanie, statystyki i pogodę. Powstała dla jednego właściciela jednego auta (PRD §3); działa w całości na telefonie, bez własnego serwera.

Źródłem prawdy są dokumenty w [`docs/`](docs/): [PRD](docs/PRD.md) (funkcje F1–F37) i [SPEC](docs/SPEC.md) (kryteria akceptacji). Poniższy opis je skraca; gdy się różnią, wygrywają docs.

## Funkcje

### Synchronizacja i archiwum
- Synchronizacja co 30 minut w tle (WorkManager) i ręcznie „Synchronizuj teraz”: pojazd, telemetria, trasy. Trasy zapisywane lokalnie bez duplikatów, bo Toyota kasuje je po ok. 12 miesiącach (F5, SPEC §8). Aplikacja nigdy nie woła `refresh_status()`, który drenuje akumulator 12 V.
- Lista tras z kosztem, pogrupowana po miesiącach (SPEC §10), z pogodą dnia (F25) i rodzajem jazdy: wolna jazda, miasto, płynnie, trasa (F27, SPEC §10.3).

### Koszty i tankowania
- Wykrycie tankowania po skoku poziomu paliwa i pytanie o cenę powiadomieniem albo oknem w aplikacji (F1, F2, F2c, SPEC §1–§2, §11, §13).
- Szacunek litrów z czujnika (F2a, SPEC §2a), cena domyślna paliwa (F2b, SPEC §12), cena 95 z trojmiasto.pl dla dwóch stacji właściciela (F22, SPEC §25).
- Koszt trasy z średniej ważonej ceny paliwa w baku (F3, ADR-005, SPEC §4, §6), przeliczany wstecz po każdej potwierdzonej cenie (F4, SPEC §5).
- Dziennik tankowań i spalanie „komputer vs faktyczne” liczone od tankowania do tankowania (F9, SPEC §15).

### Spalanie, statystyki i styl jazdy
- Spalanie i koszty miesięczne na ekranie statusu, spalanie deklarowane producenta, alert o rosnącym spalaniu (F20, F26, F29, F30, SPEC §24, §30, §31).
- Statystyki wieloletnie: lata, pory roku, rok do roku (F10, SPEC §17); jazda na prądzie na trasę, ranking i tygodnie (F11, F35, SPEC §16, §36); podsumowanie roku (F28, SPEC §29).
- Styl jazdy: wynik eco po miesiącach, powtarzane odcinki i anomalie spalania (F12, F13, SPEC §19); typy tras: dom, praca, dojazdy, weekend (F16, SPEC §21).
- Prognoza dnia tankowania (F14, SPEC §20).

### Pogoda i tempo jazdy
- Wpływ temperatury i opadów na spalanie oraz tabela tempo jazdy × temperatura (F15, F24, SPEC §23, §28); spalanie w pasmach średniej prędkości (F19, SPEC §22).

### Postoje i mapa
- Dziennik postojów: miejsca, do których auto wraca, i czas postojów (F17, SPEC §18).
- Mapa OpenStreetMap po dotknięciu trasy, z przebiegiem z GPS Toyoty (zielony – prąd, niebieski – silnik) pobranym dopiero przy otwarciu mapy (F21, F23, SPEC §26, §27); zapis przebiegów starszych tras (F32, SPEC §33) i mapa ciepła powtarzanych odcinków (F33, SPEC §34).

### Wydatki
- Wydatki poza paliwem (serwis, przegląd, opony, ubezpieczenie, części, myjnia, opłaty, inne) z datą także wsteczną, sumami per kategoria i miesiąc (F37, SPEC §38, ADR-018). Tylko lokalnie.

### Widżet i Android Auto
- Widżet na ekran główny: średnie spalanie, trend, cena domyślna, prognoza tankowania (F31, SPEC §32).
- Ekran tylko do odczytu w Android Auto (F34, SPEC §35, ADR-014); do użytku własnego, wymaga włączenia „Nieznane źródła” w ustawieniach programisty Android Auto. SPEC zaznacza, że nie był uruchamiany na prawdziwym zestawie samochodu ani w Desktop Head Unit.

### Kopia i eksport
- Eksport i import całego archiwum do pliku JSON, przypomnienie po 30 dniach (F7, F8, SPEC §14); arkusz tras w CSV dla polskiego Excela (F6, SPEC §14.4); opcjonalna kopia Google Auto Backup (ADR-015).

### Aktualizacja z GitHub Releases
- „Opcje” → „Sprawdź aktualizacje” pobiera i instaluje nowsze wydanie, sprawdzając rozmiar, SHA-256, pakiet, wersję i podpis (F36, SPEC §37, ADR-016).

## Instalacja

Gotowe APK (ok. 4 MB, Android 10 lub nowszy, `minSdk` 29) jest w [wydaniach na GitHubie](https://github.com/lukaszkosminski/toyota-companion/releases). Pierwszą instalację robi się ręcznie: pobierz `toyota-companion-<wersja>.apk` i zezwól przeglądarce lub menedżerowi plików na „Instalowanie nieznanych aplikacji”. Kolejne wersje: w aplikacji „Opcje” → „Sprawdź aktualizacje” → „Pobierz i zainstaluj” (przy pierwszej aktualizacji aplikacja poprosi o tę samą zgodę dla siebie; system i tak pokazuje własne potwierdzenie).

Przejście z wersji debug (podpisanej innym kluczem, więc nie da się jej zaktualizować):
1. w starej wersji „Kopia i eksport” → „Eksportuj archiwum” (plik JSON zawiera GPS i VIN, trzymaj go w bezpiecznym miejscu),
2. odinstaluj wersję debug,
3. zainstaluj APK z wydania,
4. zaloguj się do MyToyota,
5. „Importuj archiwum”.

Klucz podpisu wydań jest tylko w sekretach repozytorium i w kopii właściciela; jego utrata oznacza kolejne odinstalowanie (ADR-016).

## Prywatność i bezpieczeństwo

Szczegóły i uzasadnienia: [docs/SECURITY.md](docs/SECURITY.md).

- Login, hasło i tokeny Toyoty są szyfrowane AES-256-GCM kluczem z Android Keystore (ADR-007) i nie trafiają do repozytorium, logów ani kopii; logi nie zawierają haseł, tokenów, VIN ani współrzędnych GPS.
- Trasy, VIN i wydatki są w bazie Room na telefonie. Plik eksportu zawiera GPS i VIN (bez hasła i tokenów), a aplikacja ostrzega o tym przy eksporcie.
- Co wychodzi z telefonu i dokąd:
  - **Toyota Connected Europe**: logowanie, pojazd, telemetria, trasy (jak synchronizacja) i przebieg wybranej trasy po otwarciu jej mapy.
  - **Open-Meteo** (HTTPS): tylko współrzędne startu trasy zaokrąglone do 0,01° (ok. 1 km), daty i lista zmiennych; nigdy VIN, konto ani token.
  - **OpenStreetMap** (kafelki mapy): serwer widzi oglądaną okolicę, współrzędne punktów zaokrąglone do ok. 100 m.
  - **trojmiasto.pl**: jedno zwykłe `GET` strony z cenami po dotknięciu stacji, bez żadnych danych właściciela.
  - **GitHub**: tylko po dotknięciu „Sprawdź aktualizacje”, bez tokenu i konta; GitHub widzi adres IP i wersję aplikacji.
  - **Dysk Google** (Auto Backup, ADR-015): baza archiwum i ustawienia; plik z loginem i tokenami jest wykluczony, więc po przywróceniu na nowym telefonie trzeba zalogować się ponownie.
- Sprawdzanie aktualizacji, pobieranie cen i przebiegów trasy nigdy nie dzieje się bez dotknięcia. Pogoda pobiera się automatycznie po synchronizacji, a przełącznik na ekranie pogody to wyłącza.
- CI skanuje repozytorium gitleaksem.

## Budowanie i testy

Wymagania: JDK 21, Android SDK (platforma 37), Python 3.12 dla `backend/`.

```bash
cd android
./gradlew :core:test :app:testDebugUnitTest :app:assembleDebug   # testy + APK debug: app/build/outputs/apk/debug/
./gradlew :app:lintDebug                                          # lint Androida
./gradlew :app:assembleRelease                                    # build zminifikowany przez R8 (ADR-017); bez klucza niepodpisany
```

```bash
cd backend
python3 -m venv .venv
.venv/bin/pip install -r requirements-dev.txt
.venv/bin/ruff format --check && .venv/bin/ruff check && .venv/bin/mypy
.venv/bin/python -m pytest -q
```

Zmiana logiki domeny wymaga testu w Pythonie i w Kotlinie o tej samej nazwie (docs/ARCHITECTURE.md).

**CI** (`.github/workflows/ci.yml`, push na `main` i pull requesty): ruff, mypy i pytest; testy core i app, APK debug, build release z R8 i lint Androida; gitleaks.

**Wydanie** (`.github/workflows/release.yml`): tag `vMAJOR.MINOR.PATCH` uruchamia testy i lint, buduje `assembleRelease` podpisane kluczem z sekretów repozytorium, sprawdza podpis i wersję w APK i publikuje GitHub Release z plikiem `toyota-companion-<wersja>.apk`; mapowanie R8 zostaje jako artefakt workflow. Procedura jednorazowa i lokalny podpisany build: [docs/HANDOFF.md](docs/HANDOFF.md).

## Struktura repozytorium

```
android/core/   logika domeny, synchronizacja i klient Toyoty (czysty Kotlin/JVM, bez Androida)
android/app/    aplikacja: Jetpack Compose, Room 3, WorkManager, Keystore, widżet, Android Auto
backend/        wykonywalna specyfikacja domeny w Pythonie (testy referencyjne, bez serwera)
docs/           dokumentacja projektu
```

| Dokument | Zawartość |
|---|---|
| [docs/PRD.md](docs/PRD.md) | wymagania i funkcje F1–F37, poza zakresem |
| [docs/SPEC.md](docs/SPEC.md) | specyfikacja i kryteria akceptacji (§1–§38) |
| [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) | komponenty, zasady i awarie zależności |
| [docs/DECISIONS.md](docs/DECISIONS.md) | decyzje (ADR-001 – ADR-019) |
| [docs/SECURITY.md](docs/SECURITY.md) | dane chronione, przepływy do usług zewnętrznych, szyfrowanie |
| [docs/ROADMAP.md](docs/ROADMAP.md) | zrobione, teraz, następne |
| [docs/DOD.md](docs/DOD.md) | definicja ukończenia |
| [docs/HANDOFF.md](docs/HANDOFF.md) | stan prac, wydanie, środowisko lokalne |
| [CHANGELOG.md](CHANGELOG.md) | zmiany w wydaniach |
| [THIRD_PARTY_LICENSES.md](THIRD_PARTY_LICENSES.md) | licencje bibliotek i danych |
| [AGENTS.md](AGENTS.md) | zasady pracy agentów AI (docs są nadrzędne, bez commitów bez polecenia) |

## Ograniczenia

- Tylko Europa (zakres pytoyoda); API Toyoty jest nieoficjalne i może się zmienić bez ostrzeżenia.
- Jedno konto Toyoty = jedno auto (ADR-019): nie ma wyboru pojazdu, a konto z kilkoma pojazdami kończy się błędem synchronizacji.
- Archiwum zaczyna się od pierwszej synchronizacji (pierwsza pobiera do 365 dni); trasy starsze niż ok. 12 miesięcy Toyota już skasowała.
- Brak napięcia akumulatora 12 V i poziomu baterii hybrydowej (pola nie ma w używanym API, a wywołania, które mogłyby je dać, budzą auto i są zakazane). Jedyny obraz baterii to udział jazdy na prądzie z danych tras (F35).
- Poza zakresem: zdalne sterowanie autem, „ukryta rezerwa” paliwa, mapa hamowań, model rozładowania 12 V, regiony poza Europą (PRD §5).
- Ceny stacji dotyczą dwóch stacji w Gdańsku zapisanych w kodzie (SPEC §25); cenę można też wpisać ręcznie.
- Android Auto nie jest dostępny przez Google Play (kategoria), tylko po włączeniu trybu programisty (SPEC §35).

## Licencje i atrybucje

Pełne zestawienie: [THIRD_PARTY_LICENSES.md](THIRD_PARTY_LICENSES.md).

- Kod klienta Toyoty to port [pytoyoda](https://github.com/pytoyoda/pytoyoda) v6.0.0 na licencji MIT (tekst w `android/core/NOTICE`).
- Leaflet 1.9.4 (BSD-2-Clause) w ekranie mapy; dane i kafelki © OpenStreetMap contributors (ODbL, kafelki CC BY-SA 2.0).
- Ikony: Material Symbols Outlined (Apache 2.0, tekst licencji w `android/app/src/main/assets/icons/APACHE-2.0.txt`).
- Dane pogodowe: [Open-Meteo.com](https://open-meteo.com/) (CC BY 4.0, atrybucja stale na ekranie pogody).
- Pozostałe biblioteki (OkHttp, Room, WorkManager, Compose, kotlinx i inne): Apache-2.0.

Repozytorium nie zawiera pliku licencji własnego kodu aplikacji.

Dane logowania Toyoty nigdy nie trafiają do repozytorium (`credentials.json`, `.env`, `*.db` i klucze `*.jks` są w `.gitignore`).
