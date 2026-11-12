# C43 – przegląd bezpieczeństwa i prywatności (tylko odczyt)

Data: 2026-11-12. Repo: `/home/lk/projekty/toyota-companion`, gałąź `main`, commit `fe4f308` (tag `v0.2.14`).
Zakres: `docs/SECURITY.md`, `AndroidManifest.xml`, reguły kopii (`data_extraction_rules.xml`, `backup_rules.xml`), obsługa Keystore i magazynów sekretów (`security/`), logowanie w kodzie, `ToyotaSecrets.sanitize`, ruch sieciowy i usługi zewnętrzne (`Open-Meteo`, `OSM`, `trojmiasto.pl`, `GitHub`), zależności (`libs.versions.toml`, `requirements-dev.txt`, `pyproject.toml`) oraz workflow GitHub Actions (`.github/workflows/ci.yml`, `release.yml`).
Status repo: brak zmian w kodzie, brak commitów, brak wywołań sieciowych i Gradle.

---

## Podsumowanie stanu bezpieczeństwa

Architektura bezpieczeństwa projektu jest zaprojektowana na bardzo wysokim poziomie, ściśle realizując zasady minimalnych uprawnień, ochrony danych osobowych i izolacji poświadczeń:
1. **Manifest i uprawnienia:** Aplikacja deklaruje tylko 3 uprawnienia (`INTERNET`, `POST_NOTIFICATIONS`, `REQUEST_INSTALL_PACKAGES`). Brak uprawnień do pamięci masowej, aparatu i GPS telefonu – pobieranie pozycji odbywa się z chmury Toyoty, zdjęcia paragonów przez systemowy Photo Picker, a pliki przez Storage Access Framework.
2. **Kopia zapasowa (Auto Backup):** Ścisła biała lista w `data_extraction_rules.xml` i `backup_rules.xml` dopuszcza jedynie bazę `toyota-companion.db` i plik `settings.xml`. Magazyn haseł i tokenów (`secrets.xml`) oraz prywatny katalog ze zdjęciami paragonów (`filesDir/receipts`) są całkowicie wyłączone z kopii.
3. **Komponenty Androida:** `MainActivity` jest jedyną aktywnością (`exported="true"`), `ConsumptionCarAppService` posiada walidację hostów Android Auto na podstawie certyfikatów Google w wydaniu release, a pozostałe odbiorniki i dostawcy mają `exported="false"`. W projekcie **nie używa się FileProvidera** – instalacja aktualizacji APK wykorzystuje bezpieczny strumieniowy interfejs `PackageInstaller.Session`.
4. **Keystore i sekrety:** Klucze AES-256-GCM generowane w `AndroidKeyStore` są nieeksportowalne. Zastosowano AAD (Additional Authenticated Data) powiązane z kluczem preferencji, co chroni przed atakami podmiany wartości. Hasła nie trafiają do `Credentials.toString()`, UI State ani pamięci trwałej.
5. **Logowanie i wycieki:** W całym kodzie Androida zidentyfikowano jedynie dwa wywołania `Log.w` – oba logują wyłącznie nazwę klasy wyjątku (`e.javaClass.simpleName`). Żadne dane osobowe (VIN, GPS, tokeny, hasła) nie trafiają do logów systemowych. Eksporty CSV są chronione przed atakiem CSV Injection (formuły `=+-@`). Raporty diagnostyczne i błędy są czyszczone przez `ToyotaSecrets.sanitize` i `maskVin`.
6. **Ruch sieciowy:** Aplikacja nie zezwala na ruch nieszyfrowany (brak `usesCleartextTraffic`, domyślny wymóg HTTPS na Android 9+). Do Open-Meteo trafiają wyłącznie współrzędne zaokrąglone do ~1 km (`0.01°`) bez identyfikatorów. WebView mapy jest całkowicie zamknięte (`allowFileAccess=false`, blokada wszystkich żądań poza `tile.openstreetmap.org` i zasobami lokalnymi).
7. **CI/CD i zależności:** Wszystkie akcje w workflow CI i Release są przypięte do pełnych commit SHA (40 znaków). CI skanuje historię narzędziem Gitleaks. Wydania weryfikują odcisk certyfikatu podpisu APK oraz wersję. Zależności Gradle i Pythona mają sztywno przypięte wersje.

---

## Lista uwag i usterek

### WAŻNE

1. **`android/core/src/main/kotlin/pl/lk/toyotacompanion/core/update/AppUpdates.kt:202` — Warunkowa weryfikacja SHA-256 pobranego APK aktualizacji**
   - **Problem:** W metodzie `download` sprawdzanie skrótu SHA-256 pobranego pliku APK wykonuje się tylko wtedy, gdy `release.sha256 != null`:
     `if (release.sha256 != null && actual != release.sha256) throw ...`
     API wydań GitHuba dla pojedynczych assetów standardowo nie zwraca pola `digest` (zwraca jedynie `size`, `browser_download_url` itp.). Jeśli pole `digest` jest nieobecne w odpowiedzi API, `release.sha256` ma wartość `null`, a weryfikacja skrótu kryptograficznego zostaje po cichu pominięta. Wprawdzie `AndroidApkInstaller.check` weryfikuje certyfikat podpisu APK (`signerMatches`) i numer wersji, ale `docs/SECURITY.md:41` deklaruje twarde sprawdzanie SHA-256 z API.
   - **Ważność:** Średnia / Ważna (spójność deklaracji bezpieczeństwa z zachowaniem kodu).
   - **Proponowana poprawka:** Jeśli GitHub nie dostarcza `digest` w JSON assetu, publikować plik sum kontrolnych (np. `SHA256SUMS`) w release lub uaktualnić `docs/SECURITY.md`, wyjaśniając, że integralność i autentyczność opiera się na weryfikacji certyfikatu podpisu APK (`apksigner`) i rozmiarze, a SHA-256 jest sprawdzany opcjonalnie, gdy API je udostępnia.

2. **`.github/workflows/release.yml:54–65` — Brak jawnego usuwania pliku keystore po zbudowaniu wydania w CI**
   - **Problem:** W kroku `Keystore from the secrets` plik klucza jest dekodowany do `$RUNNER_TEMP/release.jks`. W kolejnych krokach następuje budowanie i publikacja, jednak w workflow brak bloku `if: always()` usuwającego plik klucza (`rm -f "$RUNNER_TEMP/release.jks"`). Na publicznych runnerach GitHub maszyna wirtualna jest niszczona po zakończeniu joba, ale dobra praktyka hardeningu CI wymaga bezzwłocznego usunięcia klucza prywatnego po zakończeniu kroku kompilacji.
   - **Ważność:** Średnia (bezpieczeństwo łańcucha dostaw / CI best practice).
   - **Proponowana poprawka:** Dodać po kroku kompilacji krok czyszczący:
     ```yaml
     - name: Clean up keystore
       if: always()
       run: rm -f "$RUNNER_TEMP/release.jks"
     ```

---

### DROBNE

3. **`android/app/src/main/res/xml/data_extraction_rules.xml:5–6` i `backup_rules.xml:5–6` — Brak pliku shared memory SQLite WAL (`.db-shm`) w regułach kopii zapasowej**
   - **Problem:** W regułach kopii zdefiniowano:
     `<include domain="database" path="toyota-companion.db" />`
     `<include domain="database" path="toyota-companion.db-wal" />`
     Pominięto plik `toyota-companion.db-shm`. Choć plik shared memory jest indeksowy i SQLite generuje go automatycznie przy otwarciu bazy, to w przypadku kopii bazy w trybie WAL standardem jest dołączanie obu plików towarzyszących (`.db-wal` i `.db-shm`) lub wymuszenie checkpointu przed zapisem.
   - **Ważność:** Drobna.
   - **Proponowana poprawka:** Dodać `<include domain="database" path="toyota-companion.db-shm" />` w obu plikach reguł.

4. **`android/app/src/main/AndroidManifest.xml:15–23` — Brak jawnego pliku `networkSecurityConfig`**
   - **Problem:** W elemencie `<application>` brak atrybutu `android:networkSecurityConfig`. Aplikacja nie ustawia `usesCleartextTraffic` (co na Android 9+ blokuje HTTP), jednak jawny plik `network_security_config.xml` pozwoliłby na formalne zablokowanie cleartextu oraz ewentualny certificate pinning kluczowych domen API.
   - **Ważność:** Drobna (rekomendacja hardeningowa).
   - **Proponowana poprawka:** Dodać `res/xml/network_security_config.xml` z regułą `<base-config cleartextTrafficPermitted="false" />` i podpiąć go w manifeście.

5. **`android/app/src/main/AndroidManifest.xml:56` — `ConsumptionWidgetProvider` z `exported="false"`**
   - **Problem:** `ConsumptionWidgetProvider` ma `android:exported="false"`. Chociaż wywołania wewnętrzne działają, standardowy systemowy `AppWidgetManager` wymaga niekiedy `android:exported="true"` z odpowiednim intent-filterem na starszych wersjach API lub specyficznych nakładkach producentów.
   - **Ważność:** Drobna.
   - **Proponowana poprawka:** Zweryfikować zachowanie odświeżania widżetu systemowego w testach na różnych wersjach ROM/Android API.

---

## Tabela kontrolna obszarów bezpieczeństwa

| Obszar | Status | Wdrożone zabezpieczenia |
|---|---|---|
| **Uprawnienia Androida** | ZGODNE | Tylko 3 uprawnienia; brak uprawnień lokalizacji, aparatu i pamięci masowej |
| **Kopia zapasowa (Backup)** | ZGODNE | Wykluczenie `secrets.xml` i zdjęć paragonów; ścisła biała lista bazy i ustawień |
| **Eksport komponentów** | ZGODNE | Tylko `MainActivity` i `ConsumptionCarAppService` (z HostValidator); reszta `exported="false"` |
| **FileProvider** | ZGODNE | Brak FileProvidera; używany SAF (`content://`) i `PackageInstaller.Session` |
| **Magazyn sekretów (Keystore)** | ZGODNE | AES-256-GCM, nieeksportowalny klucz, AAD powiązane z nazwą preferencji, brak logowania haseł |
| **Logowanie w kodzie** | ZGODNE | Tylko 2 wywołania `Log.w`, logujące wyłącznie `e.javaClass.simpleName` |
| **Ochrona danych osobowych** | ZGODNE | VIN i GPS wyłączone z CSV i raportów; sanitizacja i maskowanie w `ToyotaSecrets` |
| **Ochrona przed CSV Injection** | ZGODNE | Formuły `=+-@` poprzedzane apostrofem `'` w `csvCell` |
| **Prywatność Open-Meteo** | ZGODNE | Współrzędne zaokrąglane do ~1 km (`0.01°`), brak VIN i tokenów |
| **Bezpieczeństwo WebView (OSM)** | ZGODNE | `allowFileAccess=false`, `shouldInterceptRequest` blokuje wszystko poza kafelkami OSM |
| **trojmiasto.pl** | ZGODNE | Bezpieczny GET, brak parametrów/cookie, limit odpowiedzi 2 MB chroniący przed DoS OOM |
| **Zdjęcia paragonów (F59)** | ZGODNE | Zapis w `filesDir`, usuwanie metadanych EXIF z GPS, brak w kopiach i eksportach |
| **Aktualizacja aplikacji (F36)** | ZGODNE z uwagą | Weryfikacja certyfikatu podpisu i wersji; uwaga dot. warunkowego sprawdzania SHA-256 |
| **Zależności projektu** | ZGODNE | Wszystkie wersje w `libs.versions.toml` i `requirements-dev.txt` sztywno przypięte |
| **Workflows GitHub Actions** | ZGODNE z uwagą | Przypięte commit SHA dla akcji, skan gitleaks, weryfikacja certyfikatu wydania; uwaga dot. czyszczenia keystore |
