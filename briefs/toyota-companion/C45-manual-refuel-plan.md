# C45 – ręczne dodanie tankowania (F60→F61 w PRD)

Cel: tankowanie, którego czujnik nie wykrył (dotankowanie poniżej progu 8 pp, auto spało, zgubione zdarzenie),
da się wpisać ręcznie w „Dzienniku tankowań", zanim popsuje interwały „komputer vs faktyczne", cenę w baku i koszty tras.

## Reguły
1. Wpis ręczny jest od razu `CONFIRMED` (właściciel podaje dane z paragonu). Pola: data i godzina (nie w przyszłości),
   litry (wymagane), cena za litr albo kwota (dokładnie jedno z nich, domena zlicza drugie), „do pełna" (opcjonalnie),
   stacja (opcjonalnie).
2. **Stan licznika i poziom przed tankowaniem** z ostatniego odczytu czujnika przed tą chwilą: poziom = odczyt
   minus paliwo tras po odczycie (ta sama reguła co prognoza, §20). Bez odczytu w ciągu 30 dni przed tą chwilą
   wpis jest odrzucany (`NoReading`) – bez poziomu nie da się policzyć średniej ceny w baku.
3. **Duplikat:** jeśli w oknie ±12 h jest już tankowanie (oczekujące albo potwierdzone, nie odrzucone), zapis
   nie następuje (`Duplicate`); UI pokazuje, które tankowanie uzupełnić zamiast dodawać nowe.
4. Zapis pod mutexem synchronizacji, potem przeliczenie kosztów (`recomputeAndSave`), jak przy potwierdzeniu.
5. Id wpisu: losowe UUID (`manual-…`), bez zmian schematu Room (kolumny już są).

## Zakres
- core: `RefuelOps.kt` (`manualRefuel`, `levelBeforeManualRefuel`, `findNearbyRefuel`), `RefuelChange`
  (`Duplicate`, `NoReading`), `SyncEngine.addManualRefuel`.
- app: `RefuelLogController.addManual`, przycisk i formularz w `RefuelLogScreen`, teksty w `Texts`.
- lustro Python: `backend/app/domain/refuel.py` (te same trzy funkcje, testy o tych samych nazwach).

## Poza zakresem (świadomie)
- wybór daty kalendarzem (pola tekstowe RRRR-MM-DD, GG:MM), edycja/usunięcie ręcznego wpisu (działa „Popraw" jak dla
  potwierdzonych), dopisanie poziomów przed/po z ręki.
