# Bezpieczeństwo

## Chronione dane
| Dana | Klasyfikacja | Gdzie |
|---|---|---|
| Login/hasło Toyoty | poufne | tylko zmienne środowiskowe backendu (`TOYOTA_USERNAME`, `TOYOTA_PASSWORD`) |
| Tokeny Toyoty | poufne | pamięć / plik poza repo |
| Trasy z GPS, VIN | dane osobowe | SQLite na backendzie |

## Zasady
- Sekrety nigdy w repo, logach ani w aplikacji Android. `.env`, `credentials.json`, `*.db` w `.gitignore`.
- Logi nie zawierają haseł, tokenów, VIN ani współrzędnych GPS.
- Backend nie jest wystawiany publicznie bez uwierzytelnienia (wymagane przed udostępnieniem API poza sieć lokalną).
- CI skanuje sekrety (gitleaks).
