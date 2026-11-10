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
| F15 | Pogoda i spalanie | Ekran „Pogoda i spalanie”: trasy wg temperatury (silny mróz, mróz, chłodno, łagodnie, ciepło) i opadów (sucho, mokro) z liczbą, km, l/100 km, zł/100 km, różnicą wobec „łagodnie”/„sucho” i udziałem jazdy na prądzie; dane z reanaliz Open-Meteo pobierane tylko po dotknięciu „Pobierz pogodę”, wysyłany jest wyłącznie start trasy zaokrąglony do ok. 1 km (SPEC §23, ADR-009) |
| F16 | Typy tras | Dom i praca rozpoznane automatycznie z postojów; trasy podzielone na dojazdy, sprawy, weekend i inne z liczbą, km i kosztem w „Statystykach” (SPEC §21) |
| F17 | Dziennik parkowania | Ekran „Postoje”: miejsca, do których auto wraca (liczba postojów, łączny czas, ostatni), i ostatnie postoje z czasem; postój = koniec trasy i start następnej ≤ 150 m, przerwa 30 min – 21 dni; położenie zaokrąglone do ok. 100 m, tylko na ekranie (SPEC §18) |
| F19 | Tempo jazdy a spalanie | Sekcja „Statystyk”: trasy od 2 km w pasmach średniej prędkości (poniżej 15, 15–25, 25–40, od 40 km/h) z liczbą, km, l/100 km i zł/100 km; dopłata za wolną jazdę, gdy oba pasma mają ≥ 10 tras. Wskaźnik pośredni, nie pomiar korków (SPEC §22) |
| F20 | Spalanie na ekranie statusu | Na ekranie statusu: średnie spalanie z całego okresu (l/100 km, km) i słupki średniego spalania miesięcy (do 12 ostatnich, miesiące od 50 km) ze strzałką trendu: rosnące, malejące albo stabilne (zmiana linii trendu o ≤ 5 % średniej to stabilne); oś słupków nie zaczyna się od zera i ekran o tym mówi (SPEC §24) |
| F21 | Mapa w aplikacji | Dotknięcie trasy na liście od razu pokazuje mapę OpenStreetMap z początkiem i końcem trasy (prosta linia, bo archiwum nie ma przebiegu); to samo przy miejscu w „Postojach”; położenie zaokrąglone do ok. 100 m, serwer kafelków widzi tylko okolicę (SPEC §26, ADR-010) |
| F22 | Cena ze stacji | Przy cenie domyślnej i potwierdzaniu tankowania wybór źródła ceny: ręcznie, Auchan Szczęśliwa albo WATIS (cena 95 z trojmiasto.pl pobrana po dotknięciu, z wiekiem ceny i uwagą o źródle); cena trafia tylko do pola, zapis jak dotąd po „Zapisz” (SPEC §25, ADR-011) |
| F23 | Przebieg trasy na mapie | Mapa trasy pokazuje konkretny przebieg z GPS Toyoty (zielony – jazda na prądzie, niebieski – silnik), dociągnięty przy otwarciu mapy i zapisany na telefonie; bez przebiegu (starsza trasa, brak sieci) – początek, koniec i prosta linia (SPEC §27, ADR-012) |
| F24 | Prędkość i temperatura | Na ekranie „Pogoda i spalanie” tabela: pasma tempa jazdy × temperatura dnia (l/100 km i liczba tras w komórce, komórki poniżej 5 tras bez wniosku) oraz „zimą przy tej prędkości” – różnica mróz (-10…0 °C) wobec łagodnie w tym samym paśmie (SPEC §28) |
| F25 | Pogoda na liście tras | Przy każdej trasie na liście: symbol i słowo pogody dnia (słonecznie, deszcz, śnieg, burza…) i średnia temperatura (SPEC §10.3, §23) |
| F26 | Koszty miesięczne na ekranie statusu | Pod spalaniem: koszt paliwa każdego miesiąca (słupki do 12 ostatnich i lista z km, zł/100 km, trasami bez ceny) oraz suma z całego okresu (SPEC §24) |
| F27 | Rodzaj jazdy na liście tras | Przy każdej trasie: wolna jazda, miasto, płynnie albo trasa ze średnią prędkością (pasma z F19), krótki odcinek poniżej 2 km (SPEC §10.3) |
| F28 | Podsumowanie roku | Ekran „Podsumowanie roku”: km, litry, koszt, średnie spalanie, udział prądu, dni z jazdą, najdłuższa trasa, najlepszy/najwyższy/najdroższy miesiąc i zmiana wobec poprzedniego roku; udostępnienie jako tekst (SPEC §29) |
| F29 | Spalanie deklarowane | Właściciel wpisuje spalanie mieszane swojego modelu; karta „Spalanie” pokazuje, o ile procent jego średnie jest niższe lub wyższe od deklarowanego (SPEC §30) |
| F30 | Alert o rosnącym spalaniu | Powiadomienie, gdy spalanie rośnie trzy ukończone miesiące z rzędu poza zimą (marzec–październik), raz na miesiąc, z podpowiedzią np. o ciśnieniu w oponach (SPEC §31) |
| F31 | Widżet na ekran główny | Widżet: średnie spalanie z całego okresu, trend, cena domyślna i prognoza tankowania; odświeżany po synchronizacji, czyszczony przy wylogowaniu (SPEC §32) |
| F32 | Zapis przebiegów starszych tras | Na ekranie „Trasy” karta z liczbą zapisanych przebiegów i przyciskiem, który po dotknięciu pobiera z Toyoty do 100 przebiegów (od najstarszych), zanim Toyota usunie trasy (SPEC §33) |
| F33 | Mapa ciepła ulubionych tras | Przy każdym powtarzanym odcinku („Styl jazdy”) mapa ze wszystkimi przejazdami, które mają zapisany przebieg, pokolorowanymi spalaniem od zielonego (najmniej) do czerwonego (najwięcej) (SPEC §34) |
| F34 | Android Auto | Ekran tylko do odczytu w Android Auto: średnie spalanie, trend i cena, prognoza tankowania; kategoria IoT, do użytku własnego po włączeniu „Nieznane źródła” w ustawieniach programisty Android Auto (SPEC §35, ADR-014) |
| F35 | Jazda na prądzie w tygodniach | W „Statystykach”: słupki i lista udziału jazdy na prądzie w 12 ostatnich tygodniach (km, %, minuty); jedyny obraz baterii hybrydowej z danych tras, bo Toyota nie podaje jej poziomu ani napięcia 12 V (SPEC §36) |
| F36 | Aktualizacja aplikacji z GitHuba | W „Opcjach” przycisk „Sprawdź aktualizacje”: najnowsze wydanie z GitHub Releases repozytorium; gdy jest nowsze – „Pobierz i zainstaluj” pobiera APK, sprawdza je (rozmiar, SHA-256, pakiet, wersja, klucz podpisu) i przekazuje instalatorowi systemu; nic nie idzie do sieci bez dotknięcia (SPEC §37, ADR-016) |
| F37 | Wydatki poza paliwem | Ręczne wpisy: serwis, przegląd, opony, ubezpieczenie, części, myjnia, opłaty, inne – kwota, data (także wstecz) i opcjonalna notatka; sumy per kategoria i per miesiąc oraz zestawienie z kosztem paliwa; tylko lokalnie w bazie, w eksporcie i w kopii archiwum, bez Toyoty (SPEC §38, ADR-018) |
| F38 | Koszt całkowity auta | W „Podsumowaniu roku” karta „Koszt całkowity”: paliwo z tras z ceną plus wydatki roku (F37), w zł, zł/km, zł/100 km i średnio na miesiąc, udział paliwa, trzy największe kategorie i zmiana wobec poprzedniego roku; także w udostępnianym tekście (SPEC §39, ADR-020) |
| F39 | Cele eco | Dwa niezależne, opcjonalne cele miesiąca ustawiane przez właściciela: spalanie najwyżej X l/100 km i jazda na prądzie co najmniej Y % km; karta „Cel miesiąca” na ekranie statusu z postępem bieżącego miesiąca i wynikiem poprzedniego (osiągnięty/nieosiągnięty), poniżej 50 km w miesiącu bez oceny; czwarta, ukrywana linia widżetu; cele tylko w ustawieniach telefonu, poza archiwum i CSV (SPEC §40) |
| F40 | Autostrada i przekroczenia prędkości | Archiwum tras zapisuje to, co Toyota podaje w podsumowaniu trasy: metry i sekundy jazdy autostradą oraz ponad dozwoloną prędkością; wiersz trasy pokazuje „autostrada 4,2 km · przekroczenia 1,3 km (9 %)”, gdy są dane; pola są w eksporcie i imporcie archiwum, w trasach sprzed tej wersji brak danych (SPEC §41, ADR-021) |
| F9 | Dziennik tankowań i spalanie „komputer vs faktyczne” | Ekran „Dziennik tankowań”: potwierdzone tankowania od najnowszego; pod tankowaniem kończącym odcinek „do pełna – do pełna” spalanie faktyczne (litry z paragonu) i z komputera auta (trasy z tego odcinka) w l/100 km z różnicą w %, albo powód braku porównania; odcinek < 100 km nie jest porównywany; litry i „do pełna” potwierdzonego tankowania można poprawić z paragonu (SPEC §15) |
| F2a | Szacunek litrów z czujnika | Formularz ceny pokazuje „Szacunek z czujnika: ok. N l” (skok poziomu × 43 l); „Użyj szacunku” wpisuje litry i zapisuje `liters_source="estimate"`, ręczna zmiana albo „Z paragonu” daje `"receipt"`; szacunek bez ceny nie wylicza ceny (SPEC §2a, §11) |
| F2b | Cena domyślna paliwa | Użytkownik ustawia cenę za litr w aplikacji; trasy bez potwierdzonego tankowania przed startem dostają koszt z tej ceny (`source="default"`, SPEC §4, §12); to też cena paliwa w baku przed pierwszym potwierdzonym tankowaniem, więc pierwsze tankowanie liczy się średnią ważoną z nią (ADR-005, poprawka 2026-11-08); usunięcie ceny przywraca „brak ceny” i własną cenę pierwszego tankowania |
| F2c | Pytanie o cenę zaraz po tankowaniu | Wykryte tankowanie pyta od razu: w tle powiadomieniem, które otwiera formularz ceny tego tankowania; w aplikacji oknem z polem „Cena za litr” (§13) |
| F3 | Koszt trasy | Koszt = `fuel_l` × średnia ważona cena paliwa w baku (ADR-005) po potwierdzonych tankowaniach przed startem trasy (przed pierwszym tankowaniem w baku jest paliwo po cenie domyślnej, F2b); przykład 0,60 l × 6,140 = 3,68 zł (SPEC §4, §6) |
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
| F18 | Android Auto | – | Ryzykowne (wykonane w wersji ograniczonej jako F34): Android Auto dopuszcza tylko wybrane kategorie aplikacji (np. nawigacja, parkingi, IoT); ekran statystyk nie pasuje. Do zbadania |

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
| F41 | Stan pojazdu | Kafelek „Stan pojazdu” w menu: drzwi, maska i okna (otwarte/zamknięte, zablokowane/odblokowane) oraz liczba aktywnych ostrzeżeń z chmury Toyoty, z czasem stanu; na górze karty jedno zdanie („Wszystko zamknięte i zablokowane” albo liczba odstępstw), odstępstwa pogrubione; karta „Stan techniczny” (F43) na tym samym ekranie; odczyt tylko po dotknięciu, bez budzenia auta (SPEC §42) |
| F42 | Lokalizacja | Kafelek „Lokalizacja”: ostatnia znana pozycja auta z chmury Toyoty – nazwa miejsca Toyoty po polsku (`Last Parked` → „Ostatnie parkowanie”), nazwa znanego miejsca (Dom, Praca) gdy pozycja leży do 150 m od niego (porównanie tylko w pamięci) i dokładne współrzędne, z jedną linią czasu; „Pokaż na mapie” po dotknięciu; pozycja tylko w pamięci, bez bazy i archiwum (SPEC §43, ADR-022) |
| F43 | Stan techniczny | Karta „Stan techniczny” na ekranie „Stan pojazdu”: liczba ostrzeżeń i informacja o danych oleju silnika z chmury Toyoty, z czasem; własny odczyt i własny błąd; treści wpisów nieinterpretowane (SPEC §44) |
| F44 | Historia serwisowa | Kafelek „Historia serwisowa” w menu: wpisy serwisowe zapisane przez Toyotę – data oraz wiersze etykieta–wartość: przebieg z jednostką, kategoria (kod Toyoty, bez tłumaczenia znaczenia), czynności i dealer (etykieta „Dealer” = `servicingDealer`, a gdy brak – `serviceProvider`; oba puste = „brak danych”); nie pokazujemy `serviceHistoryId`, `roNumber`, `notes` ani `customerCreatedRecord`; odczyt tylko po dotknięciu, bez budzenia auta (SPEC §45) |
| F45 | Powiadomienia | Kafelek „Powiadomienia” w menu: historia powiadomień Toyoty – data, stan przeczytania, kategoria i typ oraz treść, najnowsze pierwsze, wszystkie wpisy; VIN w treści maskowany; aplikacja nie oznacza ich jako przeczytane, bo API na to nie pozwala; odczyt tylko po dotknięciu (SPEC §46) |
| F46 | Diagnostyka synchronizacji | Kafelek „Diagnostyka”: czasy ostatniego udanego pobrania pojazdów/konta, telemetrii i tras, czas stanu auta z Toyoty, czas i kod ostatniego błędu oraz liczniki archiwum; po chwilowym błędzie ekrany Toyoty zachowują ostatnie dobre dane; raport do schowka/udostępnienia bez VIN, GPS i tokenów (SPEC §47) |
| F47 | Arkusze tankowań i wydatków | W sekcji „Arkusz kalkulacyjny” ekranu „Kopia i eksport” dwa nowe pliki CSV obok tras: potwierdzone tankowania (data, licznik, poziomy, litry, cena, koszt, źródło litrów, „do pełna”) i wydatki poza paliwem (data, kategoria, kwota, notatka); ten sam format polskiego arkusza, bez VIN, GPS i identyfikatorów; puste listy nie tworzą pliku (SPEC §48) |
| F48 | Budżet miesięczny | Karta „Budżet miesiąca” na ekranie statusu: jeden opcjonalny limit na telefon na paliwo i wydatki poza paliwem, postęp z lokalnego archiwum (wydane, paliwo, wydatki, zostało albo przekroczono, procent), ustawienie w telefonie przeżywające wylogowanie; karta niezależna od statusu Toyoty, bez sieci i bez budzenia auta; powiadomienie przy progu to F49 (SPEC §49) |
| F49 | Powiadomienie o budżecie | Jedno powiadomienie przy 80% limitu miesięcznego i jedno przy jego przekroczeniu, raz na miesiąc dla każdego progu; nowy kanał „Budżet”, treść tylko miesiąc/kwoty/procent bez VIN i GPS; trigger po udanej synchronizacji i po zmianie wydatków; przełącznik w „Opcjach” (domyślnie włączony), reuse istniejącego uprawnienia i jednej prośby o powiadomienia (SPEC §49) |
| F50 | Krótkie trasy i zimny silnik | Karta w „Statystykach”: trasy w pasmach długości (poniżej 2, 2–5, 5–10, 10–25, od 25 km) z udziałem tras i km, l/100 km pasma jako Σ litrów / Σ km, nadwyżką wobec tras od 10 km (l/100 km; w archiwum litry i zł) i porównaniem zimnego startu (co najmniej 3 h od poprzedniej trasy) z ciepłym; liczby dopiero od progu liczności; wyłącznie lokalne archiwum, bez sieci, bez budzenia auta i bez nowego przechowywania (SPEC §50) |
| F53 | Najlepsza godzina wyjazdu | W „Stylu jazdy” pod „Ten sam odcinek”: dla do 5 najczęstszych odcinków z co najmniej 5 przejazdami – mediana czasu przejazdu, l/100 km (Σ litrów / Σ km) i liczba przejazdów wg pasma godziny startu (5–7, 7–9, 9–12, 12–15, 15–18, 18–21, noc 21–5) i dnia (robocze, weekend); komórka od 3 przejazdów; wniosek „najszybciej / najwolniej” od 2 komórek; wyłącznie lokalne archiwum (SPEC §53) |
| F52 | Kalkulator kosztu planowanej trasy | Karta na górze „Statystyk”: kilometry i rodzaj jazdy (cztery pasma tempa z F19) → przedział litrów i zł z własnego archiwum tras; populacja to trasy tego rodzaju o długości od 0,5× do 2× wpisanych km, gdy jest ich co najmniej 10, inaczej wszystkie trasy tego rodzaju (też co najmniej 10, inaczej „za mało tras”); kwartyle P25–P75 l/100 km, litry co 0,1 l, złote całe; cena z ostatniej wycenionej trasy, potem z ceny domyślnej, bez ceny tylko litry; bez sieci, map, GPS, budzenia auta i nowego przechowywania (SPEC §52) |
| F51 | Ślad CO₂ | Szacowany ślad CO₂ z przepalonych litrów: w nagłówku miesiąca na liście tras i w „Podsumowaniu roku” (ekran i tekst do udostępnienia), w kg poniżej 1000 kg i w tonach od 1000 kg; liczony lokalnie z zapisanych litrów (2,3 kg CO₂/l benzyny), bez prądu, produkcji i utylizacji auta, bez sieci i nowego przechowywania (SPEC §51) |
