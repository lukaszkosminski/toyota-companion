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
- `android:allowBackup="true"` z listą dozwolonych plików (ADR-015): Auto Backup kopiuje na Dysk Google właściciela tylko bazę archiwum i `settings`; plik `secrets` (login, tokeny) jest wykluczony. Ręczny eksport (F7) zostaje.
- Plik eksportu zawiera dane osobowe (GPS, VIN) i nie zawiera hasła ani tokenów Toyoty. Aplikacja ostrzega o tym przy eksporcie.
  Zawartość (SPEC §14.1): pojazdy, trasy z GPS, tankowania, odczyty paliwa, wydatki inne niż paliwo (serwis, opony; opcjonalne pole `expenses`, format nadal w wersji 1); bez sekretów, `sync_state`, kosztów i ustawień
  telefonu. Nazwa pliku `toyota-companion-RRRR-MM-DD.json` nie zawiera VIN; treść pliku i wyjątki nie trafiają do logów ani na ekran.
- Arkusz CSV (F6, SPEC §14.4) nie zawiera GPS ani VIN, ale czasy i dystanse tras to też dane osobowe – plik trafia
  tylko tam, gdzie wskaże użytkownik; bez ostrzeżenia jak przy kopii. Komórki tekstowe chronione przed CSV injection.
- Ekran „Postoje” (F17, SPEC §18) jako jedyny pokazuje położenie: zaokrąglone do 0,001° (ok. 100 m), liczone z tras w
  archiwum; współrzędne nie trafiają do logów, CSV ani nazw plików; jedyne wyjście to mapa po dotknięciu trasy na liście lub „Otwórz w mapie” w „Postojach” (SPEC §26: kafelki OpenStreetMap z okolicy punktów zaokrąglonych do ok. 100 m; WebView zamknięty na pozostały ruch) oraz intent `geo:` z tą samą zaokrągloną wartością po dotknięciu „Otwórz w innej aplikacji z mapą”.
- Ceny stacji (F22, SPEC §25): jedno zwykłe `GET` publicznej strony trojmiasto.pl po dotknięciu stacji; bez parametrów, cookie, konta, położenia i VIN; komunikaty błędów bez treści strony i adresu.
- Przebieg trasy (F23, SPEC §27): dociągany z Toyoty tylko po otwarciu mapy tej trasy, jednym zwykłym `GET` (jak synchronizacja, bez `refresh_status`); zapisany lokalnie z dokładnością ok. 1 m, poza eksportem i CSV; na ekranie tylko mapa, a serwer kafelków OSM widzi oglądaną okolicę.
- Aktualizacja aplikacji (F36, SPEC §37, ADR-016): tylko po dotknięciu w „Opcjach” dwa `GET` do GitHuba (API wydań i plik APK), bez tokenu, konta, VIN i położenia; GitHub widzi adres IP i wersję w zapytaniu. APK jest instalowane tylko gdy zgadzają się rozmiar, SHA-256 z API, pakiet, wersja i certyfikat podpisu zainstalowanej aplikacji; system i tak pyta właściciela. Klucz podpisu wydań (`*.jks`) nigdy w repo – tylko w sekretach repozytorium GitHub i w kopii właściciela; jego utrata uniemożliwia aktualizację bez odinstalowania, a wyciek pozwala podpisać złośliwą wersję, którą telefon przyjmie jako aktualizację.
- CI skanuje sekrety (gitleaks).
- Zapytania do usług zewnętrznych (Open-Meteo, F15, SPEC §23) wysyłają tylko współrzędne startu trasy zaokrąglone do 0,01° (~1 km),
  daty i listę zmiennych; nigdy VIN, konto, token, surowy GPS ani id trasy. Automatycznie po synchronizacji i przy otwarciu ekranu pogody (przełącznik, domyślnie włączony, albo ręcznie po dotknięciu), tylko HTTPS. Treść odpowiedzi, adres ani współrzędne nie trafiają do logów ani komunikatów błędu. Cache pogody (zaokrąglone
  miejsca i daty) zostaje na telefonie, poza kopią archiwum i CSV. Ekran pogody nie pokazuje położenia.

### Treść powiadomienia (F2c)

Powiadomienie o wykrytym tankowaniu zawiera wyłącznie datę i godzinę tankowania oraz poziom
paliwa przed i po (np. „30.10 14:05 · 20% → 80%. Podaj cenę za litr.”). Nie ma VIN ani jego fragmentu, numeru
silnika, przebiegu, lokalizacji ani tokenów. Treść jest liczona z danych już zapisanych w bazie, a samo
wysłanie powiadomienia nie woła `refresh_status()` ani żadnego innego żądania do sieci
(§8.5). `PendingIntent` niesie wyłącznie `refuel_id` — klucz wpisu w lokalnej bazie, nie sekret i nie
współrzędne.

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
- Logowanie jest najpierw sprawdzane na koncie Toyoty ze **świeżym** magazynem tokenów (`InMemoryTokenStore` w `ToyotaGatewayFactory.verifyLogin`) – zapisany, wciąż ważny token nie może zatwierdzić błędnego hasła. Przy odmowie nie jest zapisywana ani jedna wartość (ani dane logowania, ani token) – docs/SPEC.md §9.
- Hasło istnieje tylko jako parametr `SessionController.login` i w polu ekranu (`remember`, nie `rememberSaveable`) – nie trafia do stanu UI (`UiState`), do Bundle zapisanego stanu ani do logów. `LoginCheck.Ok.toString()` nie zawiera tokenu; testy `no ui state contains the password` i `messages carry no secret` tego pilnują.
- Nie testujemy jednostkowo `AndroidKeystoreCipher`: Robolectric nie ma „AndroidKeyStore”. Testy używają `JceAesTestCipher` (ten sam format blobu, zwykły klucz JCE),
  więc weryfikują `EncryptedSecretStore`, formaty i degradację błędów, ale nie samą integrację z Keystore (generowanie klucza, parametry `KeyGenParameterSpec`).
  Wymaga ręcznego sprawdzenia na urządzeniu/emulatorze.

