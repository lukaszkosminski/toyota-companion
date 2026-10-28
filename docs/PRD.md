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
| F3 | Koszt trasy | Koszt = `fuel_l` × cena ostatniego potwierdzonego tankowania przed startem trasy; przykład 0,60 l × 6,19 = 3,71 zł (SPEC §4) |
| F4 | Przeliczenie wstecz | Po potwierdzeniu ceny koszty tras są przeliczane (SPEC §5) |
| F5 | Archiwum tras | Trasy z pytoyoda zapisywane lokalnie, deduplikacja po `trip.id` |
| F6 | Eksport CSV | Eksport wszystkich tras z kosztem |
| F7 | Ręczny backup archiwum | Użytkownik eksportuje pełne archiwum (trasy, tankowania, odczyty) do wybranego pliku (systemowy wybór pliku) i może je zaimportować na nowym telefonie bez duplikatów (deduplikacja po `id`) |
| F8 | Przypomnienie o backupie | Aplikacja pokazuje datę ostatniego eksportu i przypomina, gdy minęło > 30 dni |

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

## 8. Otwarte pytania
Brak.
