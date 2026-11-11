# C32 – NBSP po 2f490e4: gdzie trafia liczba z grupowaniem (agent 3, tylko odczyt)

Data: 2026-11-10. Commit `2f490e4` (`git show --stat`: 35 plików, m.in. `TripsFormatting.kt`, ekrany, widget/Auto,
`docs/SPEC.md`). Bez zmian, bez Gradle/testów i bez gita poza odczytem.

`TripsFormatting.number` wstawia U+00A0 (`TripsFormatting.kt:134-141`); `plain` (:116) nie grupuje. `money` (:55),
`count` (:103) i `odometer` (:106) używają `number`, więc NBSP może wyjść każdą z tych dróg.

## 1. Plik CSV albo kopia JSON

**Brak – sprawdzone greppem:** `TripsFormatting\.(number|count|odometer)` w `ui/*Csv*`, `ui/*Backup*`, `ui/*Export*`
(0 trafień). CSV nadal idzie przez `plain`: `TripsCsv.kt:41-46`, `ExpensesCsv.kt:22`, `RefuelsCsv.kt:31-37`.
Kopia archiwum to serializacja danych, nie tekst ekranowy; brak wywołań formatowania w `security/` i `data/`.

## 2. Pole tekstowe jako wartość początkowa

**Brak aktywnego ryzyka – sprawdzone greppem:** `value = .*TripsFormatting\.(number|money|count|odometer)` w formularzach
(0 trafień). Pola zasilane są `plain` albo startują puste: `BudgetCard.kt:88` (`plain(it, 2)`), `DefaultPriceScreen.kt:88`,
`StationPricePicker.kt:43` (`onPrice(TripsFormatting.plain(value, 2))`), `RefuelFormatting.kt:35-36` (`litersInput`),
`ExpensesScreen.kt:196`, `RefuelFormScreen.kt:78`.

**Pułapka latentna (nieaktywna):** `parseExpenseAmount` (`Expenses.kt:53`) robi `filterNot { it.isWhitespace() }`,
a `Character.isWhitespace('\u00A0')` jest **false**. Gdyby kiedyś pole dostało `number`/`money`, tekst `1\u00A0234,50`
zostałby odrzucony jako nieprawidłowy. Dziś żadne wywołanie tak nie robi.

## 3. Tekst udostępniany / schowek diagnostyki

- **Udostępnianie roku – NBSP trafia:** `YearSummary.kt:100-115` buduje linie przez `number`, a `:196-204` zwraca
  `yearSummaryShareText`; `YearSummaryScreen.kt:241` wkłada go do `Intent.EXTRA_TEXT`. Scenariusz ryzyka tylko przy
  automatycznym parsowaniu: wklejenie do arkusza i suma „2\u00A0345” może nie uznać NBSP za separator. Dla człowieka
  w mailu/SMS jest poprawny i celowy.
- **Diagnostyka – brak:** `DiagnosticsFormatting.kt:42-65` nie używa `number`/`count`/`odometer`; schowek
  (`DiagnosticsScreen.kt:61`) i share (`:75`) dostają czysty raport z datami i tekstem.

## 4. Widget i Android Auto

**NBSP trafia, ale nie jest problemem technicznym** – to poprawny znak w `TextView`/`RemoteViews` i w tekście `CarRow`;
zapobiega łamaniu liczby. Miejsca: `WidgetSnapshot.kt:24-29` (main i cena przez `plain`, ale `line2` z
`ConsumptionFormatting.trendLine` → `signed` → `number`, `:64`), `line3` z `ForecastController.kt:28-29,44`, `line4`
z `EcoGoals.kt:164-178`; publikacja `ConsumptionWidgetProvider.kt:38-43`, Auto `ConsumptionCarAppService.kt:31-33`.

## 5. Treść powiadomień

**NBSP trafia, nie szkodzi** – powiadomienie nie jest parsowane, a NBSP jest poprawnym znakiem: `BudgetAlert.kt:68-73`
(`number`), publikacja `BudgetAlertNotifier.kt:120-121`; `ConsumptionAlert.kt:46` (`number`), publikacja
`ConsumptionAlertNotifier.kt:77-78`. Pozostałe pola tych treści idą przez `signed`/`plain`.

## Wniosek

**Ryzyka aktywne: 0.** Jedna pułapka latentna: pola tekstowe + `parseExpenseAmount` nie usuwający NBSP. Poza ekranem
NBSP trafia tylko do tekstu dla człowieka (share roku, widget/Auto, powiadomienia), gdzie jest nieszkodliwy lub celowy.
