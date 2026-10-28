# Architektura

```
Android (Kotlin, Compose) ──HTTP/JSON──> backend (FastAPI) ──pytoyoda──> Toyota Connected Europe
                                              │
                                           SQLite
```

## Komponenty
| Katalog | Odpowiedzialność | Zależności |
|---|---|---|
| `backend/app/domain` | Czysta logika: tankowania, koszt tras. Bez I/O. | tylko stdlib |
| `backend/app/storage` | SQLite: trasy, tankowania, odczyty paliwa (planowane) | domain |
| `backend/app/toyota` | Adapter pytoyoda → typy domeny (planowane) | pytoyoda, domain |
| `backend/app/api` | FastAPI dla aplikacji Android (planowane) | domain, storage |
| `android/` | UI (planowane) | API backendu |

## Zasady
- Domena nie importuje pytoyoda, FastAPI ani SQLite.
- Pytoyoda jest jedynym miejscem kontaktu z Toyotą; zmiany API Toyoty izolujemy w adapterze.
- Pieniądze i litry: `Decimal`. Czas: `datetime` z UTC.

## Zależności zewnętrzne i ich awaria
| Zależność | Awaria | Zachowanie |
|---|---|---|
| API Toyoty (przez pytoyoda) | 429 / 403 / zmiana API | Backend serwuje dane z SQLite, oznacza je jako nieaktualne; bez wybudzania auta |
| Retencja tras 12 mies. | Utrata starszych tras | Archiwum lokalne – tylko od uruchomienia |
