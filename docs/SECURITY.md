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

## Szyfrowanie sekretów na telefonie (ADR-007)
- Login, hasło i tokeny: `EncryptedSecretStore` w SharedPreferences `secrets`. Wartość = Base64 z `wersja (1 B) || IV (12 B) || szyfrogram+tag`.
- AES-256-GCM (`AES/GCM/NoPadding`, tag 128 bit), losowe IV od szyfru. Klucz w „AndroidKeyStore” (alias `toyota-companion-secrets-v1`), nieeksportowalny,
  `setUserAuthenticationRequired(false)` (synchronizacja w tle przy zablokowanym telefonie), bez StrongBox.
- AAD = nazwa klucza w preferencjach: wartość skopiowana pod inny klucz się nie odszyfruje. Klucz tokenu to SHA-256 loginu (login nie jest jawny w pliku).
- Odczyt, którego nie da się odszyfrować, zwraca `null` (bez wyjątku, bez logowania treści). Wpis jest **usuwany tylko przy trwałym uszkodzeniu**:
  `AEADBadTagException` (zły tag/AAD/zmieniony klucz), niepoprawny Base64, `UnsupportedSecretBlobException` (nieznana wersja bloba)
  albo `KeyPermanentlyInvalidatedException`. Każdy inny błąd (np. przejściowy `KeyStoreException`/`ProviderException` zaraz po starcie telefonu)
  daje `null`, ale wpis zostaje – inaczej chwilowa awaria Keystore kasowałaby hasło właściciela. Skutek trwałego uszkodzenia: ponowne logowanie.
- Utworzenie klucza w Keystore jest chronione blokadą (`synchronized`, ponowne sprawdzenie wewnątrz), żeby UI i worker nie wygenerowali dwóch kluczy.
- `Credentials.toString()` nie zawiera loginu ani hasła.
- Nie testujemy jednostkowo `AndroidKeystoreCipher`: Robolectric nie ma „AndroidKeyStore”. Testy używają `JceAesTestCipher` (ten sam format blobu, zwykły klucz JCE),
  więc weryfikują `EncryptedSecretStore`, formaty i degradację błędów, ale nie samą integrację z Keystore (generowanie klucza, parametry `KeyGenParameterSpec`).
  Wymaga ręcznego sprawdzenia na urządzeniu/emulatorze.

