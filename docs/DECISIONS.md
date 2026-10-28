# Decyzje (ADR)

## ADR-001 Backend w Pythonie + klient Android
Pytoyoda jest biblioteką Pythona. Backend trzyma dane logowania i archiwum; Android jest klientem.
Odrzucone: Chaquopy (dane logowania na telefonie), port do Kotlina (utrzymanie własnego klienta API).

## ADR-002 Koszt trasy = cena ostatniego potwierdzonego tankowania
Prosta, przewidywalna. Średnia ważona w baku – później, wymaga pojemności baku (PRD §7).

## ADR-003 Poziom krytyczności: tier 1 (wewnętrzny, niskie ryzyko)
Projekt prywatny, jeden użytkownik. Kontrolki DoD stosujemy proporcjonalnie;
te, które nie mają zastosowania, są wypisane z uzasadnieniem w docs/DOD.md.

## ADR-004 Kod domeny powstał przed PRD
Moduł `app/domain` (commit bd80f80) powstał przed przyjęciem procesu z wytycznych.
Specyfikacja (docs/SPEC.md) była jednak zatwierdzona przed implementacją, a review i testy wykonano.
Odstępstwo udokumentowane zamiast ukryte.
