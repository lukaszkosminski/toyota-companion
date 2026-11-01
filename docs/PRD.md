# PRD – toyota-companion

## 1. Problem
Aplikacja MyToyota pokazuje poziom paliwa tylko w %, nie przelicza tras na pieniądze,
nie prowadzi dziennika tankowań, a historia tras jest kasowana po ~12 miesiącach
(pytoyoda README, zgłoszenie #273).

## 2. Cel
Aplikacja na Androida (działa w całości na telefonie, ADR-006) dla właściciela Toyoty w Europie, która:
- archiwizuje trasy bezterminowo,
- wykrywa tankowania i prosi o cenę,
- przelicza koszt każdej trasy w PLN.

## 3. Użytkownicy
Jeden właściciel pojazdu (właściciel projektu). Brak użytkowników zewnętrznych.

## 4. Wymagania funkcjonalne (MVP)
| ID | Wymaganie | Kryterium akceptacji |
|---|---|---|
| F1 | Wykrycie tankowania | Skok `fuel_level` o ≥ 8 pp między odczytami tworzy wpis `pending` (docs/SPEC.md §1) |
| F2 | Podanie ceny | Użytkownik podaje cenę/l albo litry+kwotę; brakująca wartość jest wyliczana (SPEC §2) |
| F10 | Statystyki wieloletnie | Ekran „Statystyki”: lata i pory roku (meteorologiczne) z km, l/100 km, kosztem i zł/100 km, udziałem EV; rok do roku na tym samym zakresie dni (SPEC §17) |
| F11 | Jazda na prądzie | Udział km na prądzie łącznie i na trasę (brak danych ≠ 0 %), ranking 10 tras od 1 km (SPEC §16) |
| F12 | Styl jazdy | Ekran „Styl jazdy”: wynik eco Toyoty po miesiącach (ważony dystansem) i odcinki jeżdżone co najmniej 3 razy (start i koniec ≤ 300 m) z długością, godziną, wynikiem eco i spalaniem – bez położenia na ekranie (SPEC §19) |
| F13 | Anomalie spalania | Przejazd z l/100 km wyższym o ≥ 25 % od mediany swojego odcinka (≥ 5 przejazdów, ≥ 2 km) z podpowiedzią możliwych przyczyn, bez diagnozy (SPEC §19) |
| F14 | Prognoza tankowania | Na ekranie statusu: za ile dni (i którego dnia) paliwo spadnie do rezerwy 10 %, ile litrów i km zostało – z poziomu czujnika, tras od odczytu i średnich z 28 dni; bez najtańszej stacji (SPEC §20) |
| F15 | Pogoda i spalanie | Ekran „Pogoda i spalanie”: trasy wg temperatury (mróz, chłodno, łagodnie, ciepło) i opadów (sucho, mokro) z liczbą, km, l/100 km, zł/100 km, różnicą wobec „łagodnie”/„sucho” i udziałem jazdy na prądzie; dane z reanaliz Open-Meteo pobierane tylko po dotknięciu „Pobierz pogodę”, wysyłany jest wyłącznie start trasy zaokrąglony do ok. 1 km (SPEC §23, ADR-009) |
| F16 | Typy tras | Dom i praca rozpoznane automatycznie z postojów; trasy podzielone na dojazdy, sprawy, weekend i inne z liczbą, km i kosztem w „Statystykach” (SPEC §21) |
| F17 | Dziennik parkowania | Ekran „Postoje”: miejsca, do których auto wraca (liczba postojów, łączny czas, ostatni), i ostatnie postoje z czasem; postój = koniec trasy i start następnej ≤ 150 m, przerwa 30 min – 21 dni; położenie zaokrąglone do ok. 100 m, tylko na ekranie (SPEC §18) |
| F19 | Tempo jazdy a spalanie | Sekcja „Statystyk”: trasy od 2 km w pasmach średniej prędkości (poniżej 15, 15–25, 25–40, od 40 km/h) z liczbą, km, l/100 km i zł/100 km; dopłata za wolną jazdę, gdy oba pasma mają ≥ 10 tras. Wskaźnik pośredni, nie pomiar korków (SPEC §22) |
| F20 | Spalanie na ekranie statusu | Na ekranie statusu: średnie spalanie z całego okresu (l/100 km, km) i słupki średniego spalania miesięcy (do 12 ostatnich, miesiące od 50 km) ze strzałką trendu: rosnące, malejące albo stabilne (zmiana linii trendu o ≤ 5 % średniej to stabilne); oś słupków nie zaczyna się od zera i ekran o tym mówi (SPEC §24) |
| F21 | Mapa w aplikacji | Dotknięcie trasy na liście od razu pokazuje mapę OpenStreetMap z początkiem i końcem trasy (prosta linia, bo archiwum nie ma przebiegu); to samo przy miejscu w „Postojach”; położenie zaokrąglone do ok. 100 m, serwer kafelków widzi tylko okolicę (SPEC §26, ADR-010) |
| F22 | Cena ze stacji | Przy cenie domyślnej i potwierdzaniu tankowania wybór źródła ceny: ręcznie, Auchan Szczęśliwa albo WATIS (cena 95 z trojmiasto.pl pobrana po dotknięciu, z wiekiem ceny i uwagą o źródle); cena trafia tylko do pola, zapis jak dotąd po „Zapisz” (SPEC §25, ADR-011) |
| F23 | Przebieg trasy na mapie | Mapa trasy pokazuje konkretny przebieg z GPS Toyoty (zielony – jazda na prądzie, niebieski – silnik), dociągnięty przy otwarciu mapy i zapisany na telefonie; bez przebiegu (starsza trasa, brak sieci) – początek, koniec i prosta linia (SPEC §27, ADR-012) |
| F24 | Prędkość i temperatura | Na ekranie „Pogoda i spalanie” tabela: pasma tempa jazdy × temperatura dnia (l/100 km i liczba tras w komórce, komórki poniżej 5 tras bez wniosku) oraz „zimą przy tej prędkości” – różnica mróz wobec łagodnie w tym samym paśmie (SPEC §28) |
| F25 | Pogoda na liście tras | Przy każdej trasie na liście: symbol i słowo pogody dnia (słonecznie, deszcz, śnieg, burza…) i średnia temperatura (SPEC §10.3, §23) |
| F26 | Koszty miesięczne na ekranie statusu | Pod spalaniem: koszt paliwa każdego miesiąca (słupki do 12 ostatnich i lista z km, zł/100 km, trasami bez ceny) oraz suma z całego okresu (SPEC §24) |
| F27 | Rodzaj jazdy na liście tras | Przy każdej trasie: wolna jazda, miasto, płynnie albo trasa ze średnią prędkością (pasma z F19), krótki odcinek poniżej 2 km (SPEC §10.3) |
| F28 | Podsumowanie roku | Ekran „Podsumowanie roku”: km, litry, koszt, średnie spalanie, udział prądu, dni z jazdą, najdłuższa trasa, najlepszy/najwyższy/najdroższy miesiąc i zmiana wobec poprzedniego roku; udostępnienie jako tekst (SPEC §29) |
| F29 | Spalanie deklarowane | Właściciel wpisuje spalanie mieszane swojego modelu; karta „Spalanie” pokazuje, o ile procent jego średnie jest niższe lub wyższe od deklarowanego (SPEC §30) |
| F30 | Alert o rosnącym spalaniu | Powiadomienie, gdy spalanie rośnie trzy ukończone miesiące z rzędu poza zimą (marzec–październik), raz na miesiąc, z podpowiedzią np. o ciśnieniu w oponach (SPEC §31) |
| F9 | Dziennik tankowań i spalanie „komputer vs faktyczne” | Ekran „Dziennik tankowań”: potwierdzone tankowania od najnowszego; pod tankowaniem kończącym odcinek „do pełna – do pełna” spalanie faktyczne (litry z paragonu) i z komputera auta (trasy z tego odcinka) w l/100 km z różnicą w %, albo powód braku porównania; odcinek < 100 km nie jest porównywany; litry i „do pełna” potwierdzonego tankowania można poprawić z paragonu (SPEC §15) |
| F2a | Szacunek litrów z czujnika | Formularz ceny pokazuje „Szacunek z czujnika: ok. N l” (skok poziomu × 43 l); „Użyj szacunku” wpisuje litry i zapisuje `liters_source="estimate"`, ręczna zmiana albo „Z paragonu” daje `"receipt"`; szacunek bez ceny nie wylicza ceny (SPEC §2a, §11) |
| F2b | Cena domyślna paliwa | Użytkownik ustawia cenę za litr w aplikacji; trasy bez potwierdzonego tankowania przed startem dostają koszt z tej ceny (`source="default"`, SPEC §4, §12), a usunięcie ceny przywraca „brak ceny” |
| F2c | Pytanie o cenę zaraz po tankowaniu | Wykryte tankowanie pyta od razu: w tle powiadomieniem, które otwiera formularz ceny tego tankowania; w aplikacji oknem z polem „Cena za litr” (§13) |
| F3 | Koszt trasy | Koszt = `fuel_l` × średnia ważona cena paliwa w baku (ADR-005) po potwierdzonych tankowaniach przed startem trasy; przykład 0,60 l × 6,140 = 3,68 zł (SPEC §4, §6) |
| F4 | Przeliczenie wstecz | Po potwierdzeniu ceny koszty tras są przeliczane (SPEC §5) |
| F5 | Archiwum tras | Trasy z API Toyoty zapisywane lokalnie, deduplikacja po `trip.id`; zachowywane są też współrzędne start/koniec, `scores` i `hdc` (F11–F13) |
| F6 | Eksport CSV | Eksport tras wybranego pojazdu z kosztem do pliku CSV dla polskiego arkusza (`;`, przecinek, UTF-8 z BOM), od najstarszej; bez GPS i VIN; koszt jak na liście tras (SPEC §14.4) |
| F7 | Ręczny backup archiwum | Użytkownik eksportuje pełne archiwum (pojazdy, trasy, tankowania, odczyty) do wybranego pliku JSON (systemowy wybór pliku, ostrzeżenie o GPS i VIN) i może je zaimportować na nowym telefonie bez duplikatów (trasy i tankowania po `id`, odczyty po `(vin, ts)`, pojazdy po VIN); tankowanie odrzucone w telefonie, a wycenione w pliku, zostaje odrzucone i jest pokazane jako pominięte (SPEC §14) |
| F8 | Przypomnienie o backupie | Aplikacja pokazuje datę ostatniego eksportu i przypomina, gdy minęło > 30 dni albo kopii nie było, a archiwum ma trasy; przypomnienie w aplikacji, bez powiadomienia (SPEC §14.3) |

### Funkcje po MVP (zgłoszone przez właściciela 2026-10-28)
Źródło danych: pola dostępne w API Toyoty (model `_TripModel` w pytoyoda v6.0.0). Trasy mają `summary.start_lat/lon`,
`end_lat/lon`, `length`, `duration`, `durationIdle`, `averageSpeed`, `maxSpeed`, `fuelConsumption`, `nightTrip`,
`scores` (global, acceleration, braking, advice, constantSpeed) oraz
`hdc` (evDistance, evTime, ecoDist, ecoTime, powerDist, powerTime, chargeDist, chargeTime).

| ID | Funkcja | Dane | Ocena wykonalności |
|---|---|---|---|
| F9 | Dziennik tankowań + spalanie „komputer vs faktyczne” liczone od tankowania do tankowania | tankowania (F1–F2), trasy | Wykonalne. Wymaga litrów z paragonu – patrz F2a |
| F2a | Litry przy tankowaniu | `fuel_level` przed/po × 43 l | Aplikacja proponuje szacunek z czujnika (`liters_source="estimate"`); użytkownik potwierdza lub poprawia z paragonu (`"receipt"`, albo `"derived"` z kwoty i ceny). Do średniej ceny w baku wystarczy szacunek (użytkownik może podać tylko cenę). Do porównania „komputer vs faktyczne” liczą się tylko litry z paragonu (wpisane albo wyliczone z kwoty i ceny z paragonu) – szacunek z czujnika auta nie może weryfikować komputera auta |
| F10 | Statystyki wieloletnie: rok do roku, zima vs lato, trendy | archiwum lokalne | Wykonalne; dane od dnia uruchomienia (starsze niż ~12 mies. Toyota usunęła) |
| F11 | Udział jazdy elektrycznej na trasę + ranking tras | `hdc.evDistance/evTime`, `length` | Wykonalne |
| F12 | Styl jazdy: wynik eco w czasie, porównanie tras na tym samym odcinku | `scores`, start/end lat/lon | Wykonalne; „ten sam odcinek” = start i koniec w promieniu np. 300 m |
| F13 | Wykrywanie anomalii spalania | trasy podobne (odcinek, długość, pora roku) | Wykonalne jako „spalanie wyższe niż zwykle o X%”. Aplikacja nie rozpozna przyczyny (opony, bagażnik, usterka) – może je tylko podsunąć |
| F14 | Prognoza tankowania | zużycie, nawyki, `fuel_range` | Prognoza dnia – wykonalna. „Najtańsza stacja na trasie” – brak sprawdzonego źródła cen paliw w Polsce z API; do zbadania |
| F15 | Wpływ pogody (mróz, deszcz) na koszt | Open-Meteo (historyczne dane, bez klucza) | Wykonalne. Wysyła współrzędne do zewnętrznej usługi – zaokrąglać do ~1 km (SECURITY.md) |
| F16 | Automatyczne typy tras (dojazd do pracy, zakupy, weekend) | start/end lat/lon, godziny | Wykonalne (klasteryzacja miejsc + reguły czasu) |
| F17 | Dziennik parkowania (gdzie i jak długo) | koniec trasy → start następnej | Wykonalne. Strefy płatnego parkowania – brak źródła danych; tylko czas i miejsce postoju |
| F18 | Android Auto | – | Ryzykowne: Android Auto dopuszcza tylko wybrane kategorie aplikacji (np. nawigacja, parkingi, IoT); ekran statystyk nie pasuje. Do zbadania |

## 5. Poza zakresem MVP (non-goals)
- zdalne sterowanie autem (zamki, klimatyzacja),
- „ukryta rezerwa” paliwa i pojemność baku – brak danych w API, wymaga pomiaru,
- mapa zdarzeń hamowania – `behaviours` nie mają pozycji w pytoyoda,
- model rozładowania 12 V,
- regiony poza Europą (pytoyoda wspiera tylko EU).

## 6. Wymagania niefunkcjonalne
- Odpytywanie Toyoty oszczędne: `refresh_status()` drenuje akumulator 12 V – nie wołamy go automatycznie.
- Dane logowania i tokeny tylko na telefonie (Android Keystore), nigdy w repo ani logach (docs/SECURITY.md).
- Archiwum tras chronione ręcznym eksportem (F7); brak automatycznej chmury.
- Wszystkie daty w UTC (strefa czasowa świadoma).

## 7. Decyzje właściciela produktu (2026-10-28)
- Auto: Corolla Hybrid 2025, bak 43 l.
- Metoda ceny: średnia ważona w baku (ADR-005).

- Architektura: wszystko na telefonie, klient w Kotlinie (ADR-006).
- Backup: ręczny eksport do pliku + import (F7, F8).
- Cena domyślna paliwa: ustawienie w aplikacji, nie w kodzie (decyzja właściciela 2026-10-30, F2b); cena przeżywa wylogowanie, bo to ustawienie telefonu, nie konta.
- Pytanie o cenę zaraz po wykryciu tankowania (decyzja właściciela 2026-10-30, F2c):
  w tle powiadomienie, w aplikacji okno; „Później” odkłada tylko to jedno tankowanie
  do końca procesu aplikacji, a nowe tankowanie pyta ponownie.
- Szacunek litrów z czujnika (plan C6 zaakceptowany 2026-10-31, F2a): sama cena nie zapisuje litrów; szacunek
  zapisuje się tylko po „Użyj szacunku” i tylko razem z ceną, w pełnej precyzji domeny (cena w baku się nie zmienia);
  kwota wyliczona z szacunku jest szacunkowa; okno „Wykryto tankowanie” zostaje bez szacunku; pojemność baku stała 43 l.

## 8. Otwarte pytania
Brak.
