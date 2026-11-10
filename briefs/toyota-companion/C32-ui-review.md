# C32 – przegląd czytelności kafelków i podstron (agent główny)

Data: 2026-11-10. Build debug z commita `84bad7f`, emulator `toyota36` (1080×2400, motyw jasny, język PL), dane
właściciela. Obejrzane: status, Trasy, Statystyki, Styl jazdy, Podsumowanie roku, Pogoda i spalanie, Wydatki,
Postoje, Stan pojazdu, Lokalizacja, Stan techniczny, Historia serwisowa.
Druga tura tego samego dnia: Dziennik tankowań, Powiadomienia, Diagnostyka synchronizacji, Kopia i eksport, Opcje
oraz motyw ciemny (status, siatka kafelków, Statystyki) – motyw ciemny bez uwag.
**Nie obejrzane**: język EN, większa czcionka systemowa, wąski ekran (360 dp).

## A. Błędy wyświetlania – do poprawy od razu (agent 2)

- **U1 Styl jazdy → „Ten sam odcinek”**: kolumna tekstu ma ok. 100 px, bo przycisk „Pokaż przebiegi na mapie” stoi
  w tym samym wierszu; tekst łamie się co słowo („ok. 7,9 km · / przejazdy: / 46 · zwykle / ok. 06:00”). Przycisk pod
  tekstem, tekst na całą szerokość.
- **U2 Statystyki → „Jazda na prądzie w tygodniach”**: podpisy pod słupkami ucięte („24.0”, „31.0” zamiast „24.08”,
  „31.08”), a drugi wiersz to pojedyncze cyfry („8 8 9 9 0 0 1 1”). Podpis ma być czytelną datą w całości.
- **U3 Styl jazdy → „Wynik eco Toyoty po miesiącach”**: podpisy miesięcy ucięte („kwiecie”, „czerwie”, „wrzesie”,
  „paździ”, „listopa”). Użyć skrótów jak na statusie („kwi”, „maj”, „cze”).
- **U4 Separator tysięcy**: obok „4 887,2 km” i „1 263,84 zł” są „4887 km” (Podsumowanie roku, Pogoda), „1115,0 km”
  i „2308,0 km” (Typy tras), „2694,8 km”, „3223,3 km” (Pogoda). Jeden format liczb w całej aplikacji.
- **U5 Kafelki liczb**: „1 263,84 zł” łamie „zł” do drugiej linii (Podsumowanie roku, Wydatki), przez co kafelki w
  rzędzie mają różną wysokość. Liczba z jednostką w jednej linii (mniejsza jednostka albo dopasowanie rozmiaru).
- **U6 Status traci przewinięcie**: po powrocie z podstrony ekran statusu wraca na samą górę, a kafelki są ok. trzy
  ekrany niżej – każde wejście w kolejny kafelek wymaga przewijania od nowa. Zachować pozycję przewinięcia.
- **U7 Status → pigułka trendu spalania**: ikona trendu i znak „↗” obok siebie („↗ ↗ rosnące”). Zostawić jedno.
- **U10 Postoje**: te same liczby dwa razy w jednej karcie („postoje: 183 · razem 150 d 17 h · ostatnio …”, a pod
  spodem znowu „postoje: 183 … 150 d 17 h”). Jedna linia z liczbą postojów i czasem, osobno „ostatnio”.

## B. Propozycje – wymagają decyzji właściciela

- **U13 Kafelki pod trzema ekranami kart**: siatka 16 kafelków jest na samym dole statusu. Warianty: (a) kafelki
  zaraz pod stanem synchronizacji, karty niżej; (b) dolny pasek z 3–4 działami; (c) zostawić, tylko z U6.
- **U8 Lokalizacja**: „Last Parked” to surowy tekst Toyoty po angielsku; dwie daty („Pozycja z”, „Ostatnia
  aktualizacja”) różniące się o minutę; same współrzędne. Propozycja: polski opis, jedna data, a gdy pozycja leży
  przy znanym miejscu z „Postojów” – jego nazwa („Dom”). Porównanie tylko w pamięci (ADR-022).
- **U9 Historia serwisowa**: kody „MNT”, „VDE” bez opisu; „0 km” przy wpisie z dnia odbioru. Opisy kodów dopisać
  dopiero po potwierdzeniu znaczenia w źródle, nie zgadywać.
- **U11 Pogoda i spalanie**: pod każdym wykresem te same kafelki „592 Trasy / 4887 km”; wiersz pasma powtarza wynik
  (w tekście i dużą liczbą z prawej) i łamie się na dwie linie. Propozycja: kafelki raz na górze, wiersz pasma jako
  „trasy · km” + jedna liczba l/100 km.
- **U12 Trasy**: „6,30 zł/l” w każdym wierszu, choć cena zmienia się tylko przy tankowaniu. Propozycja: cena w
  nagłówku miesiąca albo tylko przy trasie, od której się zmienia.
- **U14 Stan techniczny**: ekran z trzema liniami i słowem „backend”. Propozycja: połączyć ze „Stanem pojazdu”
  w jeden kafelek albo uprościć opis.
- **U16 Statystyki → lista tygodni**: 12 wierszy po dwie linie tekstu („09.11: 58 % na prądzie · 36,2 z 62,5 km ·
  152 min na prądzie · trasy: 7”). Propozycja: wiersz „tydzień – % – km” z paskiem, reszta po dotknięciu.
- **U17 Stan pojazdu**: wszystkie stany tym samym szarym tekstem. Propozycja: wyróżnić kolorem tylko odstępstwa
  (otwarte, niezablokowane), a przy komplecie „zamknięte” jedno zdanie na górze.

- **U18 Powiadomienia**: każda treść zaczyna się od zamaskowanego VIN („*************4408:”); kategorie po angielsku
  („General · alert”, „Vehicle Alerts · alert”); „przeczytane” w osobnej linii; wiele identycznych wpisów o
  klimatyzacji pod rząd; nieprzeczytane nie różnią się wyglądem. Propozycja: obciąć prefiks z VIN, polskie nazwy
  kategorii, stan jako mała etykieta przy dacie, nieprzeczytane wyróżnione.
- **U19 Kopia i eksport**: kafelek z datą ma podpis „Kopia i eksport” zamiast „Ostatnia kopia”; pięć pełnych
  czerwonych przycisków na jednym ekranie – propozycja: jeden główny („Eksportuj archiwum”), reszta obrysowane.
- **U20 Diagnostyka synchronizacji**: tytuł trzy razy (nagłówek, karta, pierwsza linia raportu); daty w formacie
  „2026-11-10 19:04”, a reszta aplikacji „10.11 19:04” (tak samo „Ostatnia synchronizacja” na statusie); ikona
  dyskietki przy „Kopiuj”. Propozycja: tabela etykieta–wartość na ekranie, format ISO tylko w kopiowanym raporcie.
- **U21 Dziennik tankowań**: wpis pokazuje tylko poziomy i cenę za litr; litry i kwota (są w danych) nie są widoczne.

## C. Do sprawdzenia

- **U15 Pogoda i spalanie** – potwierdzone 2026-11-10 po poprawkach sekcji A: stopka „Weather data by Open-Meteo.com
  (CC BY 4.0)” jest przypięta przy dolnej krawędzi i nachodzi na przewijaną treść. Do poprawy w następnej turze.
- Język EN, duża czcionka systemowa, wąski ekran 360 dp.

## D. Stan

Sekcja A (U1–U7, U10) poprawiona przez agenta 2 i sprawdzona na emulatorze 2026-11-10; U5: kwota mieści się w jednej
linii mniejszą czcionką, kafelki w rzędzie różnią się wysokością o kilka pikseli.

## D. Stan wdrożenia (agent 2, 2026-11-10)

Właściciel zatwierdził U13, U8, U9, U11, U12, U14, U16, U17 (decyzje w poleceniu agenta głównego). Wdrożone w kodzie, do sprawdzenia na emulatorze:

- **U13** – `StatusScreen.kt`: menu kafelków i przypomnienie o kopii zaraz pod przyciskami, karty (Prognoza, Spalanie, Cel, Koszty, Budżet) pod nimi; linia „Ostatnia kopia archiwum” zostaje przy kafelkach.
- **U8** – `VehicleDataFormatting.kt` (`locationNameText`, `locationTimeLines`, `knownPlaceNear` z istniejącym `distanceMeters`), `RemoteDataScreens.kt`, `AppRoot.kt` (miejsca z `loadTrips`, tylko w pamięci). Z referencji pytoyoda
  wynika tylko, że `displayName` to „human-readable name”; potwierdzona wartość jest jedna (`Last Parked`), więc tylko ona jest tłumaczona.
- **U9** – `ServiceFormatting.kt` (`serviceDetails`), `ServiceScreens.kt`: wiersze Przebieg / Kategoria (kod Toyoty) / Dealer / Czynności; kodów nie tłumaczymy.
- **U11** – `WeatherScreen.kt`, `WeatherFormatting.kt`: kafelki raz na górze; wiersz koszyka „24 trasy · 312,4 km”, l/100 km po prawej z jednostką, zł/100 km w linii pomocniczej.
- **U12** – `TripsGrouping.kt` (`tripIdsWithPriceLine`), `TripsScreen.kt`: cena za litr przy najnowszej trasie każdej serii o tej samej cenie (różni się od chronologicznie następnej) i przy najnowszej trasie miesiąca (korekta: lista jest od najnowszych).
- **U14** – `RemoteDataScreens.kt`: karta „Stan techniczny” w „Stanie pojazdu” z osobnym odczytem i błędem; kafelek, ekran i `Screen.VehicleHealth` usunięte; tekst bez „backend”.
- **U16** – `EvWeeks.kt`, `StatsScreen.kt`, `ChartKit.kt` (`MeterRow` z etykietą na resztę szerokości): jeden wiersz na tydzień, minuty poza listą.
- **U17** – `VehicleDataFormatting.kt` (`vehicleSummary`, `isDeviation`), `RemoteDataScreens.kt`: zdanie na górze karty, odstępstwa pogrubione w kolorze ostrzeżenia.
