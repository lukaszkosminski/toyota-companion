# Decyzje (ADR)

## ADR-001 Backend w Pythonie + klient Android
Pytoyoda jest biblioteką Pythona. Backend trzyma dane logowania i archiwum; Android jest klientem.
Odrzucone: Chaquopy (dane logowania na telefonie), port do Kotlina (utrzymanie własnego klienta API).

## ADR-002 Koszt trasy = cena ostatniego potwierdzonego tankowania (ZASTĄPIONE przez ADR-005)
Prosta, przewidywalna. Średnia ważona w baku – później, wymaga pojemności baku (PRD §7).

## ADR-003 Poziom krytyczności: tier 1 (wewnętrzny, niskie ryzyko)
Projekt prywatny, jeden użytkownik. Kontrolki DoD stosujemy proporcjonalnie;
te, które nie mają zastosowania, są wypisane z uzasadnieniem w docs/DOD.md.

## ADR-004 Kod domeny powstał przed PRD
Moduł `app/domain` (commit bd80f80) powstał przed przyjęciem procesu z wytycznych.
Specyfikacja (docs/SPEC.md) była jednak zatwierdzona przed implementacją, a review i testy wykonano.
Odstępstwo udokumentowane zamiast ukryte.

## ADR-005 Koszt trasy = średnia ważona ceny paliwa w baku
Decyzja właściciela produktu (2026-10-28). Bak 43 l (Corolla Hybrid 2025).
Po tankowaniu cena paliwa w baku = (litry przed × cena przed + litry zatankowane × cena nowa) / litry po.
Litry przed = `level_before` % × 43 l. Wymaga podania litrów z paragonu (albo kwoty i ceny).
Specyfikacja i implementacja: do zrobienia (SPEC §6). Do tego czasu działa ADR-002.
