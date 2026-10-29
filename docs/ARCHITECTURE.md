# Architektura (ADR-006)

```
Telefon z Androidem
┌───────────────────────────────────────────────┐
│ app (Compose UI, WorkManager, Room, Keystore)  │
│        │                                       │
│ core (czysty Kotlin/JVM)                       │
│   ├─ domain: tankowania, koszt tras            │
│   └─ toyota: klient API (port z pytoyoda) ──────┼──> Toyota Connected Europe
└───────────────────────────────────────────────┘
```

## Komponenty
| Katalog | Odpowiedzialność | Zależności |
|---|---|---|
| `android/core` … `domain` | Czysta logika: tankowania, koszt tras. Bez I/O. | tylko JDK |
| `android/core` … `toyota` | Logowanie i endpointy Toyoty (port pytoyoda v6.0.0) | HTTP client (OkHttp) |
| `android/app` | UI, praca w tle, baza Room, bezpieczne przechowywanie hasła (planowane) | core |
| `backend/` | Wykonywalna specyfikacja domeny w Pythonie (testy referencyjne) | stdlib |

## Zasady
- `core` nie zależy od Androida — testy jednostkowe uruchamiane na JVM.
- Każda zmiana logiki domeny: najpierw SPEC i test w Pythonie, potem Kotlin z tym samym testem (te same nazwy).
- Pieniądze i litry: `BigDecimal` (Kotlin) / `Decimal` (Python), `HALF_UP`. Czas: `Instant` (UTC).
- Klient Toyoty to jedyne miejsce kontaktu z API; zmiany Toyoty śledzimy w pytoyoda i przenosimy.

## Zależności zewnętrzne i ich awaria
| Zależność | Awaria | Zachowanie |
|---|---|---|
| API Toyoty | 429 / 403 / zmiana API | Aplikacja pokazuje dane z lokalnej bazy jako nieaktualne; bez wybudzania auta |
| Retencja tras 12 mies. | Utrata starszych tras | Archiwum lokalne od uruchomienia + backup |
| Android (oszczędzanie baterii) | Opóźnione odczyty w tle | Tankowanie wykryte przy następnym odczycie |
