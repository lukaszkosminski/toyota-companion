# Architektura (ADR-006)

```
Telefon z Androidem
┌───────────────────────────────────────────────┐
│ app (Compose UI, WorkManager, Room, Keystore)  │
│        │                                       │
│ core (czysty Kotlin/JVM)                       │
│   ├─ domain: tankowania, koszt tras            │
│   ├─ data + sync: repozytoria, synchronizacja  │
│   └─ toyota: klient API (port z pytoyoda) ──────┼──> Toyota Connected Europe
└───────────────────────────────────────────────┘
```

## Komponenty
| Katalog | Odpowiedzialność | Zależności |
|---|---|---|
| `android/core` … `domain` | Czysta logika: tankowania, koszt tras. Bez I/O. | tylko JDK |
| `android/core` … `toyota` | Logowanie i endpointy Toyoty (port pytoyoda v6.0.0) | HTTP client (OkHttp) |
| `android/core` … `data` | Interfejsy repozytoriów, `TripRecord` (archiwum) | domain |
| `android/core` … `sync` | `SyncEngine`: pojazdy → telemetria → tankowanie → trasy → koszty (SPEC §8) | data, toyota |
| `android/core` … `testFixtures/data` | `RepositoryContract` (scenariusze bez frameworka testowego) i repozytoria w pamięci | data |
| `android/app` … `data/db` | Baza Room 3 (`AppDatabase` v1, encje, DAO, konwertery) i `Room*Repository` implementujące interfejsy z `core/data`; schemat w `app/schemas` | core |
| `android/core` … `sync/ToyotaGatewayFactory` | Buduje `ToyotaGateway` (wspólny `OkHttpClient`), żeby `app` nie zależał od OkHttp | toyota |
| `android/app` … `security` | `SecretCipher` (AES-256-GCM, `AndroidKeystoreCipher`), `EncryptedSecretStore` (SharedPreferences, Base64, AAD = nazwa klucza), `CredentialStore`, `KeystoreTokenStore` (ADR-007) | core.toyota |
| `android/app` … `sync` | `SyncWorker` (WorkManager) → `SyncRunner` → `SyncEngine.fullSync()`; `SyncScheduler`: unikalna praca okresowa `toyota-sync` co 30 min (sieć, backoff wykładniczy 30 s) i jednorazowa po zalogowaniu | core.sync |
| `android/app` … `AppContainer`, `ToyotaApp` | Ręczne zależności (bez Hilt/Koin), wszystko leniwie; `ToyotaApp` jest `Configuration.Provider` (domyślny inicjalizator WorkManagera wyłączony w manifeście) | wszystkie |
| `android/app` … `ui` | `SessionController` (stan `StateFlow`, bez ViewModela – `lifecycle-viewmodel-compose` nie jest zależnością), `CredentialVerifier`, `UiState`, `Texts` (teksty po polsku), ekrany Compose: `LoginScreen`, `StatusScreen`, `AppRoot` (docs/SPEC.md §9) | core.sync, core.data, security, sync |
| `android/app` … `ui/CredentialVerifier` | Seam weryfikacji loginu: produkcyjnie `ToyotaGatewayFactory.verifyLogin` (core), w testach fałszywy weryfikator – `app` nie widzi OkHttp ani nie łączy się z siecią | core.sync |
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
