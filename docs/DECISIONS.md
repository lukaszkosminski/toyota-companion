# Decyzje (ADR)

## ADR-001 Backend w Pythonie + klient Android (ZASTĄPIONE przez ADR-006)
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
Po tankowaniu cena paliwa w baku = (litry przed × cena przed + litry zatankowane × cena nowa) / (litry przed + litry zatankowane).
Litry przed = `level_before` % × 43 l. Litry zatankowane: z paragonu, wyliczone z kwoty i ceny albo szacunek z czujnika;
gdy użytkownik poda tylko cenę – szacunek `(level_after − level_before)` % × 43 l (właściciel: „pyta o cenę, ilość aplikacja sprawdzi sama”).
Mianownik to suma wymieszanego paliwa, więc wynik zawsze leży między starą i nową ceną (poziom po tankowaniu z czujnika
nie jest używany – niezgodny z paragonem dawałby cenę spoza zakresu).
Szacunek z czujnika wystarcza do średniej ceny; do porównania spalania „komputer vs faktyczne” (F9) liczą się tylko litry z paragonu –
wpisane (`receipt`) albo wyliczone z kwoty i ceny z paragonu (`derived`, decyzja właściciela 2026-10-31).
Zaimplementowane w Pythonie i Kotlinie (SPEC §6, `tank_price_after_refuel`/`tankPriceAfterRefuel`).
Pierwsze tankowanie albo brak poprzedniej ceny -> własna cena paliwa. Zastępuje ADR-002.

## ADR-006 Aplikacja tylko na telefonie, klient Toyoty w Kotlinie (zastępuje ADR-001)
Decyzja właściciela produktu (2026-10-28): całość działa na telefonie, bez backendu.
Chaquopy odrzucone: pytoyoda wymaga pydantic 2 → `pydantic-core` (Rust), którego Chaquopy nie dostarcza
(chaquo/chaquopy #1017 i PR #1413 otwarte), a PyPI nie ma wheeli dla Androida (stan 2026-10-28).
Klient Toyoty i logika domeny są portowane do Kotlina (`android/core`, czysty JVM).
pytoyoda służy jako wzór zmian API Toyoty; `backend/` zostaje jako wykonywalna specyfikacja domeny z testami,
a testy Kotlin mają te same nazwy i wartości (stan 2026-10-29: Python 76, Kotlin 109 – 76 domeny + 33 klienta Toyoty).
Konsekwencje: hasło Toyoty na telefonie (Android Keystore); archiwum tras tylko na telefonie → wymagany backup.

## ADR-007 Hasło MyToyota przechowywane na telefonie
Decyzja właściciela produktu (2026-10-29). Synchronizacja w tle (WorkManager co 30 min) loguje się bez udziału
użytkownika, więc login i hasło są zapisane zaszyfrowane kluczem AES-256-GCM z Android Keystore
(klucz nieeksportowalny, bez wymogu odblokowania urządzenia, żeby działał w tle).
`androidx.security:security-crypto` nie jest używane (wycofane; 1.1.0 to ostatnie wydanie).
Odrzucone: tylko token odświeżania – czas jego ważności zależy od Toyoty, a wygaśnięcie zatrzymuje archiwum.
Ryzyko zaakceptowane: osoba z pełnym dostępem do odblokowanego lub zrootowanego telefonu może odczytać hasło.
Hasło nie trafia do logów, stanu synchronizacji ani pliku eksportu (SECURITY.md); `allowBackup=false`.

## ADR-008 Statystyki tras: sezon meteorologiczny, porównywalne okno, próg rankingu (2026-10-31)
Sezon meteorologiczny (XII–II, III–V, VI–VIII, IX–XI), grudzień liczony do zimy roku stycznia. Rok do roku tylko na
tym samym zakresie dni obu lat. Ranking jazdy na prądzie od 1 km (krótsze trasy dają szum), brak danych `hdc` to
„brak danych”, nie 0 %. Udział czasowy EV odłożony – archiwum nie ma czasu jazdy (wymagałby migracji Room).
Postój (F17) = koniec trasy i start następnej w promieniu 150 m, przerwa od 30 min do 21 dni; miejsca tylko na
ekranie, zaokrąglone do 0,001°, współrzędne wychodzą do innej aplikacji tylko przez „Otwórz w mapie” po dotknięciu (decyzja właściciela 2026-11-01, SPEC §18, §10.3).

## ADR-009 Pogoda z Open-Meteo: reanaliza dzienna, ~1 km, tylko po dotknięciu (2026-11-01)
Druga usługa zewnętrzna obok Toyoty, ale dane, nie konto: archiwum reanaliz Open-Meteo (`/v1/archive`, bez klucza, CC BY 4.0).
Wybrane: tylko `daily`, jedno miejsce na żądanie, współrzędne startu trasy zaokrąglone do 0,01° (zgoda właściciela 2026-11-01;
pomiar: 0,37/0,38/0,372 dają te same wartości dzienne), pobieranie wyłącznie po dotknięciu (nie w tle i nie w synchronizacji),
cache w Room (v2, migracja automatyczna) poza kopią archiwum. Odrzucone: godzinowe dane, `forecast` z `past_days` (to model
prognostyczny, nie obserwacja), geokodowanie, automatyczne odświeżanie, „koszt mrozu” w złotówkach (fałszywa precyzja – zostają
l/100 km i zł/100 km na koszyk z udziałem jazdy na prądzie). Ryzyko: reanaliza to model (siatka kilku–kilkunastu km, opóźnienie
kilku dni) i korelacja, nie przyczyna – ekran tak to nazywa.

## ADR-010 Mapa w aplikacji: Leaflet w zamkniętym WebView, kafelki OpenStreetMap (2026-11-01)
Właściciel chce mapy od razu po dotknięciu trasy (intent `geo:` nie działa bez aplikacji z mapą). Wybrane: dołączony Leaflet 1.9.4 w
WebView (bez zależności Gradle i bez klucza API) i kafelki `tile.openstreetmap.org`; trzecia usługa zewnętrzna obok Toyoty i Open-Meteo.
Cena: serwer kafelków widzi okolicę punktów (zaokrąglonych do ok. 100 m), nie dokładne położenie – zgoda właściciela, ekran o tym
mówi. Zamknięcie WebView: lista dozwolonych adresów, brak mostu JS, brak dostępu do plików. Odrzucone: osmdroid/MapLibre (nowa
zależność Gradle, wymaga akceptacji), Google Maps SDK (klucz, śledzenie), mapa z przebiegiem trasy (archiwum go nie ma).
Ryzyko: polityka kafelków OSM dopuszcza tylko lekkie użycie – przy większym ruchu zmienić dostawcę lub dodać pamięć podręczną.

