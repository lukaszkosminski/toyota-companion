# Bezpieczeństwo

## Chronione dane
| Dana | Klasyfikacja | Gdzie |
|---|---|---|
| Login/hasło Toyoty | poufne | telefon, szyfrowane kluczem z Android Keystore |
| Tokeny Toyoty | poufne | pamięć / plik poza repo |
| Trasy z GPS, VIN | dane osobowe | baza Room na telefonie; plik eksportu w miejscu wybranym przez użytkownika |

## Zasady
- Sekrety nigdy w repo, logach ani w aplikacji Android. `.env`, `credentials.json`, `*.db` w `.gitignore`.
- Logi nie zawierają haseł, tokenów, VIN ani współrzędnych GPS.
- `android:allowBackup="false"` – systemowy backup nie kopiuje bazy ani sekretów; backup archiwum tylko przez ręczny eksport (F7).
- Plik eksportu zawiera dane osobowe (GPS, VIN) i nie zawiera hasła ani tokenów Toyoty. Aplikacja ostrzega o tym przy eksporcie.
- CI skanuje sekrety (gitleaks).
- Zapytania do usług zewnętrznych (np. Open-Meteo, F15) wysyłają tylko zaokrąglone współrzędne (~1 km) i czas; nigdy VIN ani identyfikator konta.
