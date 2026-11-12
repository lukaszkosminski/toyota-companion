# C43 – przegląd spójności dokumentacji z kodem (tylko odczyt)

Repo: `/home/lk/projekty/toyota-companion`, gałąź `main`, commit `fe4f308` (2026-11-11 17:59), najnowszy tag `v0.2.14`.
Zakres: `docs/PRD.md`, `docs/SPEC.md`, `docs/ROADMAP.md`, `docs/HANDOFF.md`, `docs/DECISIONS.md`, `README.md`, `CHANGELOG.md`.
Pytania: (1) pokrycie F1–F60 w PRD / SPEC / ROADMAP DONE, (2) aktualność HANDOFF.md, (3) sekcje CHANGELOG dla v0.2.0–v0.2.14,
(4) sprzeczności między dokumentami, (5) pozycje ROADMAP NEXT/NOW.

Weryfikacja tylko do odczytu: `git log`, `git tag`, `grep`/`sed` po plikach, liczba adnotacji `@Test` i `def test_`, zawartość
`android/app/schemas` (1.json–9.json), `AndroidManifest.xml`. Nie zmieniono kodu ani dokumentów, nie wykonano commitów.
Stan drzewa: czysty poza tym plikiem (untracked). Liczby testów podane niżej są liczbą adnotacji/metod w źródłach, nie wynikiem
uruchomienia suit – do wpisania w dokumenty trzeba wziąć wynik z CI.

## 0. Odpowiedzi w skrócie

1. **PRD**: każda z F1–F60 ma wiersz (F1–F40 i F9/F2a–F8 w tabeli MVP w liniach 20–61, F41–F60 w liniach 112–131). F18 jest
   w tabeli po-MVP jako „wykonane w wersji ograniczonej jako F34”.
2. **SPEC**: każda funkcja ma treść, ale **F25, F26 i F27 nie mają własnych nagłówków ani oznaczeń** (siedzą anonimowo w §10.3/§10.4,
   a opis F26 leży w złej sekcji – §28), a F18 nie ma sekcji (zastąpione przez §35/F34).
3. **ROADMAP DONE**: **nie**. Brak wpisów dla **F40, F41, F42, F43** (wydane w v0.2.3, którego nie ma w podsumowaniu wydań),
   F1, F3 i F5 są tylko opisane tekstem bez identyfikatora, F18 jest w `## ZAMKNIĘTE BADANIA` (i słusznie – jako F34).
4. **HANDOFF.md**: **nieaktualny w stopniu krytycznym** – zatrzymany na 2026-11-10 i v0.2.9, zaniżone liczby testów, „Room 3 (v1)”,
   twierdzenie, że F50 ma tylko plan, oraz sekcja „Następny krok” z października.
5. **CHANGELOG**: **tak** – wszystkie 15 wersji v0.2.0…v0.2.14 mają własne sekcje z datami; `## Unreleased` jest puste.
6. **Sprzeczności**: PRD F15 (pogoda „tylko po dotknięciu”) vs SPEC §23/ADR-013/README (pobieranie automatyczne); PRD F26 → SPEC §24,
   a treść F26 leży w §28; README/ARCHITECTURE/HANDOFF/ROADMAP mówią „Room 3 / AppDatabase v1”, kod ma schemat 9; README podaje
   ADR-001–ADR-020, DECISIONS ma ADR-022; ADR-007 mówi `allowBackup=false`, ADR-015 i manifest – `true`.
7. **NEXT/NOW**: `## NEXT` ma zrobioną pozycję 1, numerację 1→7 (dziura 2–6), jedyną „żywą” pozycją jest wstrzymany import CSV,
   a w `## NOW` wiszą zadania właściciela z początku listopada – szczegóły w §4.

---

## 1. WAŻNE

### W1. `docs/ROADMAP.md:51–59` – podsumowanie wydań pomija v0.2.3 i v0.2.4, przez co F40–F43 nie mają wpisu w DONE
**Problem.** Lista wydań w wpisie „Wydania na GitHub Releases…” przechodzi z `v0.2.2` prosto do `v0.2.5`. Tagi istnieją
(`git tag`: v0.2.3 i v0.2.4 z 2026-11-08), a CHANGELOG 0.2.3 opisuje F40 (autostrada i przekroczenia), F41 (stan pojazdu),
F42 (lokalizacja), F43 (stan techniczny). Skutek: w `## DONE` nie ma ani jednego wystąpienia `F40` ani `F42`; `F41` występuje
tylko w `## DO ZBADANIA` (linie 124–126), a `F43` tylko w `## LATER` (linia 111) – czyli dokument twierdzi, że funkcje wydane
w v0.2.3 są nierozwiązane.
**Poprawka.** Dopisać do linii 51–59: `v0.2.3 (2026-11-08: stan pojazdu, lokalizacja i stan techniczny, autostrada i przekroczenia
prędkości – F40–F43, SPEC §41–§44, ADR-021/ADR-022, baza Room 6)` oraz `v0.2.4 (2026-11-08: poprawka ADR-005 – cena domyślna
w pierwszym tankowaniu)`, a w `## DONE` dodać osobne wpisy `(F40, SPEC §41, ADR-021)` i `(F41–F43, SPEC §42–§44, ADR-022)`.

### W2. `docs/PRD.md:110–131` – wiersze F41–F60 wklejone pod „## 8. Otwarte pytania” bez nagłówka tabeli
**Problem.** Po `## 8. Otwarte pytania` (linia 110) i `Brak.` (linia 111) idą wiersze `| F41 | …` … `| F60 | …` bez wiersza
nagłówkowego `| ID | Wymaganie | Kryterium akceptacji |` i separatora `|---|---|---|`. Markdown nie renderuje tego jako tabeli
(w praktyce 20 wierszy to akapit z pionkami). Dodatkowo kolejność jest zaburzona: F50, F54, F55, F53, F52, F51, F56… (linie 121–127).
**Poprawka.** Wydzielić nową podsekcję w rozdziale 4 (np. `### Funkcje dodane po MVP (v0.2.3–v0.2.14)`) z pełnym nagłówkiem tabeli
i posortować wiersze F41→F60; `## 8. Otwarte pytania` zostawić na końcu dokumentu.

### W3. `docs/HANDOFF.md:1, 3–11, 14, 18, 27, 37, 118–153` – stan prac zatrzymany na 2026-11-10 / v0.2.9
**Problem.** Konkretne, sprawdzone rozjazdy:
- linia 1: tytuł `# Stan prac (2026-11-10)`, a HEAD jest z 2026-11-11 i wydany jako v0.2.14;
- linie 3–8: lista wydań kończy się na `v0.2.9`, brak v0.2.10–v0.2.14 (5 wydań, m.in. F56–F60);
- linie 9–10: „Po v0.2.6 na `main`, bez nowego wydania: F48–F49…” oraz „F50 (krótkie trasy i zimny silnik) ma tylko plan
  `briefs/toyota-companion/C30-plan.md`” – nieprawda, F48–F51 weszły w v0.2.7 (CHANGELOG 0.2.7, ROADMAP DONE);
- linia 11: „core 458, app 853, Python 198” (1509), a w źródłach jest 572 adnotacji `@Test` w `android/core`, 1018 w `android/app`
  i 303 metod `def test_` w `backend/tests` (razem 1893); różnica +384;
- linia 27: „baza Room 3 (v1)” – `android/app/schemas` ma 1.json…**9.json**, SPEC §57/§59 i ROADMAP DONE mówią o Room v8/v9;
- linia 37: „Testy: core 316, app 380 … Python 143” – sprzeczne z linią 11 i z rzeczywistością (podwójna nieaktualność w jednym pliku);
- linia 14 vs 18: „Emulator 10.11: 592 trasy” vs „Emulator `toyota36` ma … 585 tras” – dwie różne liczby w tym samym skrócie;
- linie 118–153: `## Następny krok` to notatki z 29–31.10.2026 (C11–C13, F15 „do sprawdzenia przez właściciela”, „F18 Android Auto
  (PRD odradza)”), czyli stan sprzed 10 wydań.
**Poprawka.** Przepisać górny blok na stan v0.2.14 (2026-11-11): wydania v0.2.0–v0.2.14 z jednozdaniowym opisem, właściwe liczby
testów (najlepiej „wg CI na commicie …”: core 572, app 1018, Python 303), „baza Room 9 (schemat 9.json)”, usunąć zdania o „brak
wydania” i o F50 „tylko plan”, ujednolicić liczbę tras emulatora, a `## Następny krok` zastąpić realnymi otwartymi punktami
(np. z `## NOW`/`## NEXT` ROADMAP). Sekcje historyczne (C1–C13) zostawić, ale opatrzyć datami.

### W4. `docs/PRD.md:27` (F15) vs `docs/SPEC.md:814`, `docs/DECISIONS.md:93` (ADR-013), `README.md:85` – sprzeczny tryb pobierania pogody
**Problem.** PRD F15 mówi: „dane z reanaliz Open-Meteo pobierane **tylko po dotknięciu** „Pobierz pogodę” (SPEC §23, ADR-009)”.
SPEC §23 (linia 814) mówi „Pobieranie (decyzja właściciela 2026-11-01: **automatycznie**): po każdej udanej synchronizacji i przy
otwarciu ekranu… przełącznik domyślnie włączony”, ADR-013 zmienia ADR-009, a README (linia 85) potwierdza pobieranie automatyczne.
PRD nie wspomina też o przełączniku.
**Poprawka.** W `docs/PRD.md:27` zmienić na: „dane z reanaliz Open-Meteo pobierane automatycznie po synchronizacji i przy otwarciu
ekranu, z przełącznikiem (domyślnie włączony); przycisk „Pobierz pogodę” robi to samo ręcznie (SPEC §23, ADR-009 + ADR-013)” i dodać
odesłanie do ADR-013.

### W5. `docs/PRD.md:37` (F26 → „SPEC §24”) vs `docs/SPEC.md:832` i `docs/SPEC.md:900–902` – specyfikacja kosztów miesięcznych leży w §28
**Problem.** PRD F26 odsyła do §24, ale §24 (linia 832) to „Spalanie na ekranie statusu (PRD F20)” i nie zawiera kosztów miesięcznych.
Właściwy akapit „Koszty miesięczne (ta sama karta, pod słupkami spalania)…” (linie 900–902) jest w **§28 „Prędkość i temperatura
(PRD F24)”** – czyli specyfikacja F26 wisi w sekcji o innej funkcji, a odsyłacz z PRD prowadzi donikąd.
**Poprawka.** Przenieść akapit z linii 900–902 do §24 (po opisie słupków spalania), zmienić nagłówek §24 na
`## 24. Spalanie i koszty miesięczne na ekranie statusu (PRD F20, F26)` i zostawić w PRD F26 odsyłacz do §24.

### W6. `README.md:117` – „Room 3” przy schemacie 9 (ta sama nieaktualność w `docs/ARCHITECTURE.md:28` i `docs/SPEC.md:113`)
**Problem.** README w opisie układu repo podaje `android/app/ the app: Jetpack Compose, Room 3, …`. Kod ma `schemas/1.json…9.json`,
CHANGELOG 0.2.11 i ROADMAP DONE mówią „baza Room v9”, a migracje 5→6 (F40), 6→7 (F46), 7→8 (F57), 8→9 (F59) są opisane w SPEC.
ARCHITECTURE (linia 28) i SPEC §8.2 (linia 113) powtarzają „Baza Room 3 (`AppDatabase` v1)”.
**Poprawka.** W README: „Room 9 (schemat `9.json`, migracje automatyczne)”; w ARCHITECTURE i SPEC §8.2 analogicznie „AppDatabase v9”.

### W7. `README.md` (sekcja Features) – brak opisów zrealizowanych F40–F46, choć dokument deklaruje zakres F1–F60
**Problem.** Linie 5 i 124 mówią „features F1–F60”, a lista funkcji nie zawiera: F40 (autostrada i przekroczenia prędkości w wierszu
trasy), F41 (stan pojazdu), F42 (lokalizacja), F43 (stan techniczny), F44 (historia serwisowa), F45 (powiadomienia Toyoty),
F46 (diagnostyka synchronizacji). Wszystkie są w PRD (linie 51, 112–117), SPEC (§41–§47) i CHANGELOG 0.2.3/0.2.5. Czytelnik README
nie dowie się o całej grupie ekranów tylko do odczytu z chmury Toyoty.
**Poprawka.** Dodać sekcję np. `### Toyota cloud (read-only)` z jednym punktem na F41–F46 („vehicle state, location, technical state,
service history, notifications, sync diagnostics — read from the Toyota cloud on tap, never waking the car”) i dopisać F40 do punktu
o liście tras.

---

## 2. DROBNE

### D1. `README.md:127` – zakres ADR nieaktualny
`docs/DECISIONS.md` ma ADR-001…**ADR-022**, README podaje „ADR-001 – ADR-020”. **Poprawka:** `(ADR-001 – ADR-022)`.

### D2. `docs/DECISIONS.md:52` vs `:111` – ADR-007 nadal mówi `allowBackup=false`
ADR-007 (linia 52) kończy się „`allowBackup=false`”, ADR-015 (linia 111) „Zmienia `allowBackup=false` z ADR-006/ADR-007”, a
`android/app/src/main/AndroidManifest.xml:17` ma `allowBackup="true"`. **Poprawka:** w ADR-007 dopisać „(uchylone przez ADR-015:
`allowBackup=true` z listą plików w `backup_rules.xml`/`data_extraction_rules.xml`)”, żeby czytelnik nie trafił na nieaktualny fakt.

### D3. `docs/DECISIONS.md` – brak ADR dla decyzji z F57/F59/F60, ADR-015 nie zna zdjęć paragonów
F57 (nazwa stacji jako kolumna bazy, Room v8, trafia do Auto Backup), F59 (zdjęcia paragonów w prywatnym katalogu, poza kopią,
bez OCR) i F60 (folder SAF, 10 kopii, rotacja tylko własnych plików) to decyzje o przechowywaniu i prywatności, a lista ADR kończy się
na ADR-022. ADR-015 (Auto Backup) wymienia tylko bazę i `settings` – nie mówi, że zdjęcia są wyłączone z kopii (mówi to dopiero
SECURITY.md:17 i SPEC §59). **Poprawka:** dodać ADR-023 (zdjęcia paragonów: katalog prywatny, poza Auto Backup/JSON/CSV, bez OCR)
i ADR-024 (automatyczna kopia do folderu: SAF, rotacja 10, tylko własne pliki) albo rozszerzyć ADR-015 o zdjęcia.

### D4. `docs/SPEC.md:781–1052` – niejednolity styl nagłówków
§1–§21 i §42–§60 używają `## §NN …`, a §22–§41 `## NN. …` (np. linia 781 `## 22. Tempo jazdy…`, linia 1066 `## §42 Stan pojazdu…`).
**Poprawka:** ujednolicić do `## §22 …`–`## §41 …` (spis treści i odsyłacze `§NN` już tego formatu używają).

### D5. `docs/SPEC.md:1163` – nagłówek §49 pomija F49
`## §49 Budżet miesięczny (PRD F48)`, choć §49.2 (linia 1194) w całości specyfikuje powiadomienie F49. **Poprawka:**
`## §49 Budżet miesięczny i powiadomienie o progu (PRD F48, F49)`.

### D6. `docs/SPEC.md:284, 311–315` – F25, F26, F27 bez oznaczeń
Wiersz pogody (F25) i rodzaj jazdy (F27) są w §10.3 (linie 308–309), karta „Koszty miesięczne” (F26) w §10.4 (linia 315) – bez tokenów
`F25`/`F26`/`F27` (grep nie znajduje ich w całym SPEC). **Poprawka:** dopisać `(F25)`, `(F27)` przy wierszach listy i `(F26)` przy karcie
kosztów, żeby dało się prześledzić PRD→SPEC.

### D7. `docs/SPEC.md:167, 243, 303` – odsyłacze do „ROADMAP NOW” dla rzeczy już zrobionych
Trzy miejsca uzasadniają wymaganie jako „ROADMAP NOW” (logowanie/ADR-007, §10 lista tras, §11 formularz ceny), a te pozycje dawno są
w DONE. **Poprawka:** zamienić na „ROADMAP DONE” albo usunąć odsyłacz (wymaganie i tak jest w PRD).

### D8. `docs/SPEC.md:900` – literówka
„(nie zgadujemy różnicy z kilku ttras)” → „z kilku tras”.

### D9. `docs/ROADMAP.md:4` i `:59` – F60 opisane dwa razy
Pierwszy wpis DONE (linia 4) i ogon listy wydań (linia 59: „v0.2.13 i v0.2.14 … F60, SPEC §60”) mówią to samo. **Poprawka:** zostawić
wpis funkcji w linii 4, a w linii 59 podać tylko wydania i odsyłacz „(szczegóły wyżej)”.

### D10. `docs/ROADMAP.md:15` – nieaktualne liczby testów domeny
„Port domeny do Kotlina (Python 76 / Kotlin 76 testów domeny, nazwy 1:1)” – dziś w `backend/tests` jest 303 metod `def test_`, a w
`android/core/src/test` ~307 adnotacji `@Test` w testach domeny (lustro 1:1 nadal prawdziwe, liczby nie). **Poprawka:** usunąć liczby
albo podać aktualne.

### D11. `docs/ROADMAP.md:22` – „Baza Room 3 (schemat v1)” w DONE myli
Wpis historyczny, ale bez znacznika „stan z v0.1” czyta się jak aktualny stan bazy (schemat jest 9). **Poprawka:** dopisać
„(stan z pierwszego wydania; dziś Room 9, §57/§59)” albo przenieść do wpisu historycznego o synchronizacji.

### D12. `docs/ROADMAP.md:3–11` – DONE nie jest w porządku chronologicznym
Kolejność: F60, F59, F58, F57, F56, **F54, F55, F53**, F52… (F54 przed F55 i F53). **Poprawka:** sortować malejąco po numerze funkcji
lub po wydaniu (v0.2.14 → v0.2.0), żeby dopisywanie kolejnych pozycji nie wymagało przeskakiwania.

### D13. `docs/HANDOFF.md:20, 148, 150` i `docs/ROADMAP.md:138` – odsyłacze do nieistniejących plików
`briefs/toyota-companion/C14-review.md` (HANDOFF:148, 150), `C15-review.md` (HANDOFF:148), `C16-battery.md` (ROADMAP:138) oraz
`briefs/toyota-companion/emu-backup/2026-11-08/data.tar` (HANDOFF:20) nie istnieją – w `briefs/toyota-companion` są tylko C25–C43.
**Poprawka:** przywrócić pliki albo zamienić odsyłacze na opis bez ścieżki.

### D14. `docs/HANDOFF.md:11` vs `:37` – dwie różne liczby testów w jednym pliku
„core 458, app 853, Python 198” (linia 11) i „core 316, app 380 … Python 143” (linia 37) – obie nieaktualne, ale też wzajemnie sprzeczne.
**Poprawka:** jedna, aktualna liczba w obu miejscach (albo „patrz CI”).

### D15. `docs/HANDOFF.md:14` vs `:18` – 592 vs 585 tras na emulatorze
Ta sama migawka opisana dwiema liczbami. **Poprawka:** ujednolicić (i opatrzyć datą).

### D16. `CHANGELOG.md:5` i pozostałe nagłówki – brak prefiksu `v`
CHANGELOG używa `## 0.2.14`, a tagi, ROADMAP i HANDOFF – `v0.2.14`. **Poprawka:** dodać `v` w nagłówkach (albo jedno zdanie na górze,
że numer bez `v` = tag z `v`).

### D17. `CHANGELOG.md:3–4` – puste `## Unreleased`
Po wydaniu 0.2.14 sekcja jest pusta. **Poprawka:** zostawić (miejsce na kolejne zmiany) albo usunąć do pierwszego commita po wydaniu.

### D18. `CHANGELOG.md:16` – deklaracja o kolejności wierszy PRD nie odpowiada plikowi
0.2.12 twierdzi „…kolejność wierszy PRD”, a tabela MVP nadal ma F10–F40 w liniach 22–51 i F9/F2a/F2b/F2c/F3–F8 dopisane w liniach 52–61
(porządek: F1, F2, F10…F40, F9, F2a…F8). **Poprawka:** albo uporządkować tabelę PRD (F1, F2, F2a, F2b, F2c, F3…F9, F10…), albo
sprecyzować w CHANGELOG, że chodziło o wiersze po-MVP.

### D19. `docs/PRD.md:71–80` – F9, F2a, F10–F17 zduplikowane w dwóch tabelach
Te same identyfikatory są w tabeli MVP (linie 20–61) i w tabeli „Funkcje po MVP (zgłoszone 2026-10-28)” (linie 71–80), z innym
brzmieniem kryteriów (np. F2a w linii 53 vs 72). **Poprawka:** zostawić jedną tabelę wymagań, a drugą oznaczyć jako historyczną listę
zgłoszeń (albo usunąć zduplikowane wiersze).

### D20. `docs/PRD.md:110–111` – „Otwarte pytania: Brak.” przy otwartych pytaniach
F14 (linia 26/77 „do zbadania”), F18 (linia 81 „Do zbadania”) i `## DO ZBADANIA` w ROADMAP (linie 122–132) przeczą „Brak.”.
**Poprawka:** wymienić otwarte punkty albo zmienić zdanie na „Brak poza pozycjami „do zbadania” w F14/F18 (ROADMAP DO ZBADANIA)”.

### D21. `docs/PRD.md:81` – F18 bez jasnego statusu
„Ryzykowne (wykonane w wersji ograniczonej jako F34)” – czytelnik nie wie, czy F18 to requirement, czy identyfikator wycofany.
**Poprawka:** „F18 – zastąpione przez F34 (SPEC §35, ADR-014); identyfikator F18 nie jest realizowany”.

### D22. `docs/ROADMAP.md:90–92` – `## NOW` prawdopodobnie wyczerpane
Zadania właściciela (test `:core:liveCheck`, przejście debug→release) pochodzą z początku listopada; od tego czasu wyszło 15 wydań
z kluczem wydań, a README opisuje migrację jako wykonaną procedurę. **Poprawka:** potwierdzić u właściciela i albo zamknąć (przenieść do
DONE), albo zawęzić do tego, co naprawdę zostało (np. „test na prawdziwym koncie po v0.2.14”).

### D23. `CHANGELOG.md` – brak wpisu o aktualizacji dokumentacji dla F60
DOD wymaga „Dokumentacja aktualizowana w tym samym commicie co zachowanie”, a 0.2.13/0.2.14 nie odnotowują zmian README (F1–F60, §60)
i SPEC §60, choć 0.2.12 taki wpis miało. **Poprawka:** dopisać zdanie o dokumentacji w 0.2.13 (albo w `Unreleased`).

---

## 3. Macierz pokrycia F1–F60

Legenda: `OK` – wpis/sekcja istnieje; `ID?` – treść jest, brak oznaczenia funkcji; `BRAK` – nie ma wpisu.

| ID | PRD (linia) | SPEC (sekcja, linia) | ROADMAP DONE |
|---|---|---|---|
| F1 | OK (20) | OK (§1, 19) | ID? (opis w 17–21, bez `F1`) |
| F2 | OK (21) | OK (§2, 26) | OK (29) |
| F2a | OK (53; też 72) | OK (§2a, 41) | OK (36) |
| F2b | OK (54) | OK (§12, 415) | OK (31) |
| F2c | OK (55) | OK (§13, 483) | OK (34) |
| F3 | OK (56) | OK (§4, 54; §6, 70) | ID? (opis w 17, bez `F3`) |
| F4 | OK (57) | OK (§5, 67) | OK (29) |
| F5 | OK (58) | OK (§8, 99; §10, 242) | ID? (opis w 19–21, bez `F5`) |
| F6 | OK (59) | OK (§14.4, 622) | OK (39) |
| F7 | OK (60) | OK (§14, 565) | OK (38) |
| F8 | OK (61) | OK (§14.3, 611) | OK (38) |
| F9 | OK (52; też 71) | OK (§15, 645) | OK (40) |
| F10 | OK (22; też 73) | OK (§17, 702) | OK (42) |
| F11 | OK (23; też 74) | OK (§16, 691) | OK (42) |
| F12 | OK (24; też 75) | OK (§19, 727) | OK (43) |
| F13 | OK (25; też 76) | OK (§19, 727) | OK (43) |
| F14 | OK (26; też 77) | OK (§20, 746) | OK (44) |
| F15 | OK (27; też 78) | OK (§23, 793) – sprzeczne z PRD (W4) | OK (45) |
| F16 | OK (28; też 79) | OK (§21, 770) | OK (44) |
| F17 | OK (29; też 80) | OK (§18, 713) | OK (42) |
| F18 | OK (81, „jako F34”) | BRAK (§35 = F34) | BRAK w DONE (ZAMKNIĘTE BADANIA, 135) |
| F19 | OK (30) | OK (§22, 781) | OK (13, 45) |
| F20 | OK (31) | OK (§24, 832) | OK (47) |
| F21 | OK (32) | OK (§26, 862) | OK (48) |
| F22 | OK (33) | OK (§25, 846) | OK (49) |
| F23 | OK (34) | OK (§27, 877) | OK (48) |
| F24 | OK (35) | OK (§28, 892) | OK (13, 45) |
| F25 | OK (36) | ID? (§10.3, 308) | OK (46) |
| F26 | OK (37) | ID? + zła sekcja (§28, 900–902; nie §24) | OK (47) |
| F27 | OK (38) | ID? (§10.3, 309) | OK (46) |
| F28 | OK (39) | OK (§29, 907) | OK (50) |
| F29 | OK (40) | OK (§30, 918) | OK (47) |
| F30 | OK (41) | OK (§31, 926) | OK (47) |
| F31 | OK (42) | OK (§32, 937) | OK (50) |
| F32 | OK (43) | OK (§33, 945) | OK (48) |
| F33 | OK (44) | OK (§34, 954) | OK (48) |
| F34 | OK (45) | OK (§35, 963) | OK (50) |
| F35 | OK (46) | OK (§36, 973) | OK (50) |
| F36 | OK (47) | OK (§37, 983) | OK (51) |
| F37 | OK (48) | OK (§38, 1005) | OK (60) |
| F38 | OK (49) | OK (§39, 1021) | OK (60) |
| F39 | OK (50) | OK (§40, 1036) | OK (60) |
| F40 | OK (51) | OK (§41, 1052) | **BRAK** (W1) |
| F41 | OK (112) | OK (§42, 1066) | **BRAK** (tylko DO ZBADANIA, 124–126) |
| F42 | OK (113) | OK (§43, 1080) | **BRAK** |
| F43 | OK (114) | OK (§44, 1093) | **BRAK** (tylko LATER, 111) |
| F44 | OK (115) | OK (§45, 1101) | OK (62) |
| F45 | OK (116) | OK (§46, 1113) | OK (65) |
| F46 | OK (117) | OK (§47, 1122) | OK (70) |
| F47 | OK (118) | OK (§48, 1133) | OK (74) |
| F48 | OK (119) | OK (§49, 1163) | OK (76) |
| F49 | OK (120) | OK (§49.2, 1194; nagłówek bez F49 – D5) | OK (78) |
| F50 | OK (121) | OK (§50, 1213) | OK (87) |
| F51 | OK (126) | OK (§51, 1234) | OK (88) |
| F52 | OK (125) | OK (§52, 1253) | OK (13) |
| F53 | OK (124) | OK (§53, 1271) | OK (12) |
| F54 | OK (122) | OK (§54, 1292) | OK (10) |
| F55 | OK (123) | OK (§55, 1311) | OK (11) |
| F56 | OK (127) | OK (§56, 1336) | OK (9) |
| F57 | OK (128) | OK (§57, 1360) | OK (8) |
| F58 | OK (129) | OK (§58, 1385) | OK (7) |
| F59 | OK (130) | OK (§59, 1403) | OK (6) |
| F60 | OK (131) | OK (§60, 1430) | OK (4) |

Podsumowanie (identyfikatorów jest 63: F1–F60 + F2a/F2b/F2c): PRD 63/63, SPEC 63/63 treści (59 z własnym nagłówkiem,
3 bez oznaczenia – F25/F26/F27, F18 bez sekcji), ROADMAP DONE 55/63 z jawnym identyfikatorem + 3 tylko opisowe (F1, F3, F5)
+ 4 bez wpisu (F40, F41, F42, F43) + F18 w `## ZAMKNIĘTE BADANIA`.

---

## 4. ROADMAP `## NOW` / `## NEXT` – proponowane uporządkowanie

Stan obecny (`docs/ROADMAP.md:90–102`):

```
## NOW
- Właściciel: test klienta na prawdziwym koncie `./gradlew -q :core:liveCheck`; przejście telefonu z wersji debug na wydanie
## NEXT
- Propozycje zaakceptowane przez właściciela 2026-11-10 … w tej kolejności:
  1. (zrobione jako F52, bez temperatury – patrz DONE) Kalkulator kosztu planowanej trasy
  7. Import historii tankowań i wydatków z CSV innych aplikacji … wstrzymane 2026-11-11
- Pozostałe drobne z przeglądu C22: D3–D7, D12 i D13 zrobione; D2, D8, D11, D14 do potwierdzenia w kodzie; D9 i D10 są w LATER
```

Problemy: (a) pozycja 1 jest zrobiona i nadal wisi w NEXT; (b) numeracja 1 → 7, brak 2–6 (HANDOFF opisuje to jako „numeracja pomija 5”,
ale dziś brakuje pięciu pozycji); (c) jedyna niezrobiona pozycja jest wstrzymana z braku danych wejściowych; (d) pozycje C22 D2/D8/D11/D14
wiszą od commita `a982de8` (2026-11-08) bez kryterium zamknięcia; (e) nagłówek NEXT zapowiada listę „propozycji zaakceptowanych
2026-11-10”, której już nie ma.

Proponowany kształt:

```
## NOW
- Właściciel: test klienta na prawdziwym koncie po v0.2.14 (`./gradlew -q :core:liveCheck`) – potwierdzić, czy po przejściu na wydanie
  jest jeszcze do zrobienia (D22)

## NEXT
- C22: potwierdzić w kodzie D2, D8, D11, D14 (od `a982de8`) i zamknąć albo przenieść do LATER z kryterium

## LATER (kolejność wg wartości / kosztu)
- Import historii tankowań i wydatków z CSV innych aplikacji (Fuelio, Drivvo) – wstrzymane 2026-11-11: brak prawdziwego pliku eksportu,
  formatu nie zgadujemy; wróci, gdy plik się pojawi (dziś: pozycja 7 w NEXT)
- (reszta LATER bez zmian)
```

Czyli: usunąć z NEXT pozycję 1 (jest w DONE), przenieść pozycję 7 do LATER/DEFERRED z warunkiem powrotu, przewidzieć miejsce na kolejne
propozycje z numeracją od 1 bez dziur, a `## NOW` zamknąć po potwierdzeniu u właściciela.

---

## 5. Poza zakresem, ale ta sama nieaktualność (do rozważenia przy okazji)

- `docs/ARCHITECTURE.md:28` – „Baza Room 3 (`AppDatabase` v1)”, dziś schemat 9 (W6).
- `docs/SPEC.md:113` – to samo w §8.2 („`AppDatabase` v1”).
- `docs/DOD.md` – bez zmian merytorycznych; lista kontroli nadal aktualna.
- `docs/SECURITY.md` – spójny z F57/F59 (nazwa stacji w bazie/Auto Backup, zdjęcia poza kopią); bez uwag.

---

## 6. Podsumowanie liczbowe

- PRD: 60/60 funkcji, ale 20 wierszy (F41–F60) poza tabelą markdown i w złej kolejności (W2).
- SPEC: 60/60 treści, 3 funkcje bez oznaczenia (F25/F26/F27), 1 funkcja w złej sekcji (F26 w §28), 1 bez sekcji (F18).
- ROADMAP DONE: 55 identyfikatorów z ID, 3 tylko opisowe (F1, F3, F5), 4 bez wpisu (F40–F43), F18 w ZAMKNIĘTYCH BADANIACH
  (63 identyfikatorów: F1–F60 + F2a/F2b/F2c).
- HANDOFF: 5 brakujących wydań (v0.2.10–v0.2.14), 2 różne (i zaniżone) liczby testów, „Room 3” zamiast 9, nieaktualny „Następny krok”.
- CHANGELOG: 15/15 wersji (v0.2.0–v0.2.14) – komplet; uwagi tylko stylistyczne (D16–D18, D23).
