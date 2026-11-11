# C40 – plan: zdjęcie paragonu przy tankowaniu lub wydatku (PRD F59, SPEC §59)

Status: **szkic do akceptacji koordynatora** (pozycja 8 w `docs/ROADMAP.md` NEXT, „bez OCR; do decyzji, czy zdjęcia
wchodzą do kopii archiwum”). Bez kodu, docs i testów do akceptacji. Data: 2026-11-11. Autor: agent 2.

## 1. Cel

Przy tankowaniu i przy wydatku można dołączyć zdjęcie paragonu, żeby mieć dowód przy sporze lub przy rozliczeniu.
Aplikacja nie odczytuje treści (bez OCR, bez sieci, bez zewnętrznej biblioteki). Zdjęcie jest opcjonalne i można je
usunąć razem z wpisem. Pytanie kluczowe: czy zdjęcia mają wejść do kopii (Auto Backup i ręczny eksport archiwum).

## 2. Ustalenia z kodu (stan dziś)

- **Pliki obsługiwane tylko przez SAF:** eksport i import archiwum oraz CSV idą przez
  `ActivityResultContracts.CreateDocument` i `OpenDocument` (`ui/BackupScreen.kt:57–69`), zapis przez
  `contentResolver.openOutputStream` (`AppContainer.kt:516`), odczyt przez `openInputStream` (`AppContainer.kt:521`).
  Plik wybiera użytkownik w systemowym pickerze; aplikacja nie trzyma żadnego URI.
- **Brak aparatu, galerii, FileProvider, `PickVisualMedia`, biblioteki obrazów i `CAMERA`:** `grep` po `TakePicture`,
  `PickVisualMedia`, `FileProvider`, `filesDir` i `takePersistableUriPermission` w `android/app/src/main` nie trafia.
  Jedyne `cacheDir` to APK aktualizacji (`AppContainer.kt:217`).
- **Manifest** (`app/src/main/AndroidManifest.xml`): uprawnienia tylko `INTERNET`, `POST_NOTIFICATIONS`,
  `REQUEST_INSTALL_PACKAGES`. Brak `<queries>` i `FileProvider`. `minSdk = 29`, `targetSdk = 37`
  (`app/build.gradle.kts:20–25`).
- **Auto Backup:** `res/xml/backup_rules.xml` (API ≤ 30, `fullBackupContent`) i `res/xml/data_extraction_rules.xml`
  (API 31+, `cloud-backup` i `device-transfer`) mają **listę włączeń**: tylko baza `toyota-companion.db` (+ `-wal`) i
  `sharedpref` `settings.xml`. Wszystko w `filesDir` jest poza kopią. Zdjęcie w prywatnym katalogu nie wejdzie do kopii,
  dopóki nie dopiszemy `<include domain="file" path="receipts/"/>` w obu plikach.
- **Limit kopii:** 25 MB na aplikację (podany przez właściciela; do potwierdzenia w dokumentacji Androida przed
  implementacją). To zwykła kopia Google Drive, nie archiwum aplikacji.
- **Archiwum JSON** (`core/data/ArchiveFile.kt`, `ARCHIVE_VERSION = 1`): zawiera tekst, nie pliki. Zdjęcia zrobiłyby
  z JSON-a plik wielomegabajtowy, a formatu bez binariów nie da się zmienić bez podbicia wersji.
- **Tankowanie i wydatki w Room:** `RefuelEntity` i `ExpenseEntity`; ostatnia migracja to v8 (`AutoMigration(7, 8)`,
  `AppDatabase.kt:25–27`). Dodanie kolumny to kolejna prosta `AutoMigration(8, 9)`, tak jak F57.
- **Prywatność już w SPEC:** współrzędne GPS maskowane i nie trafiają do eksportu (SPEC §27, §26), a
  `docs/SECURITY.md` wymienia listę tego, co nie idzie do logów i CSV.

## 3. Warianty

**(a) Kopia do prywatnego katalogu aplikacji + odnośnik w Room.**
- Wybór: `ActivityResultContracts.PickVisualMedia` (Photo Picker: bez uprawnień do galerii; na API 29–32 działa przez
  dostawcę systemowego) albo `TakePicture` (aparat). `TakePicture` wymaga `FileProvider` (wpis w manifeście, `xml/file_paths.xml`)
  i pliku tymczasowego w `cacheDir`; nie wymaga `CAMERA`, bo robi zdjęcie aplikacja aparatu.
- Po wyborze: dekodowanie, skalowanie (dłuższy bok ok. 1600 px), zapis JPEG ok. 80% do `filesDir/receipts/<losowa-nazwa>.jpg`.
  Ponowny zapis przez `Bitmap` **usuwa metadane EXIF**, w tym GPS (zdjęcie z telefonu zwykle je ma – to trzeba
  zrobić, inaczej zdjęcie ujawnia miejsce tankowania).
- Room: kolumna `receipt_file` (nazwa pliku, `NULL` = brak) w `refuels` i `expenses`; migracja 8→9 (`AutoMigration`).
- Usunięcie wpisu kasuje plik; przy imporcie archiwum osierocone pliki są sprzątane przy starcie.
- Kopia: **do decyzji** (patrz pkt 5). Jeśli tak, dwie linie `<include domain="file" path="receipts/"/>` w obu XML-ach.
- Zalety: zdjęcie przetrwa usunięcie w galerii i zmianę dostawcy; działa na wszystkich API ≥ 29.
- Wady: kod (kopiowanie, skalowanie, sprzątanie), migracja, potencjalnie kopia przekracza 25 MB.

**(b) Zapamiętanie URI z trwałym uprawnieniem odczytu, bez kopiowania.**
- `takePersistableUriPermission` działa dla URI z `OpenDocument` (SAF). **Nie wiadomo** (do sprawdzenia na emulatorze),
  czy URI z `PickVisualMedia` pozwala na trwałe uprawnienie; zakładam, że nie.
- Kod najprostszy: jedna kolumna z tekstem URI, zero plików, zero kopii, zero EXIF-u po stronie aplikacji.
- Wady: zdjęcie znika, gdy właściciel je usunie lub przeniesie; URI nie wchodzi do Auto Backup w sensie „zdjęcia”
  (przeżywa kopię tylko jako tekst, a po przywróceniu na innym telefonie uprawnienie jest nieważne); EXIF zostaje w oryginale,
  więc GPS nadal jest w pliku, który aplikacja tylko wskazuje; zależność od dostawcy treści.

**(c) Wybór wprost:** najmniej kodu to (b), ale daje najsłabszą gwarancję („paragon jest, kiedy go potrzebuję”).
Rekomendacja: **(a) z pickerem zdjęć i usuwaniem EXIF**, z kopią domyślnie **wyłączoną** (patrz pkt 5).

## 4. Rozmiar, kompresja, APK

- Szacunek (do zmierzenia na urządzeniu): JPEG 1600 px, q80, paragon ok. 250–500 KB. 25 MB to ok. 50–100 paragonów.
  Przy jednym tankowaniu w tygodniu i zdjęciach wydatków limit kopii może zostać przekroczony po kilku latach.
- Bez nowych bibliotek: `BitmapFactory`, `Bitmap.compress`, `ExifInterface` **nie** (nie trzeba – ponowny zapis usuwa EXIF).
  Photo Picker jest w `androidx.activity` (już w projekcie przez `rememberLauncherForActivityResult`); wersję trzeba
  potwierdzić w `gradle/libs.versions.toml` przed implementacją.
- APK: kilka kilobajtów kodu, bez zasobów; rozmiar danych użytkownika rośnie, nie APK.

## 5. Decyzje do podjęcia przez właściciela

1. **Czy zdjęcia są w kopii Auto Backup?** Tak: wygodnie przy zmianie telefonu, ale obowiązuje limit 25 MB i paragon
   trafia do chmury (dane karty, NIP). Nie: prywatniej i bez limitu, ale zdjęcia znikają przy zmianie telefonu
   (rekomendacja: nie, z komunikatem w ustawieniach „zdjęcia zostają na tym telefonie”).
2. **Czy zdjęcia są w ręcznym eksporcie archiwum?** Rekomendacja: nie. Zmiana formatu archiwum wymaga podbicia
   `ARCHIVE_VERSION`, a to zmienia zasady starych plików (§ archiwum). Osobny eksport ZIP to osobna pozycja.
3. **Czy w CSV jest kolumna „Zdjęcie”** (tak/nie)? Rekomendacja: nie; ścieżka pliku nie wchodzi do CSV ani do tekstów.
4. **Ile zdjęć?** Jedno na tankowanie i jedno na wydatek (rekomendacja), bez galerii.

## 6. Prywatność

- Paragon może zawierać dane karty (ostatnie cyfry), NIP, adres, imię i nazwisko. Nie do: tekstów udostępnianych,
  CSV, logów, powiadomień, diagnostyki (jak nazwa stacji w F57).
- EXIF usuwany przy zapisie (GPS nie zostaje w pliku aplikacji).
- Plik w `filesDir` jest prywatny dla aplikacji; zdjęcia nie są w żadnym współdzielonym katalogu.
- Aktualizacja `docs/SECURITY.md` (jedno zdanie) i `docs/SPEC.md` §59.

## 7. Pliki (przewidywane, jeśli wariant (a))

- Nowe: `ui/ReceiptPhoto.kt` (wybór, skalowanie, zapis, usuwanie), `core/domain/ReceiptOps.kt` (nazwa pliku, limit),
  `res/xml/file_paths.xml` (tylko przy aparacie), testy w `app/src/test` (skalowanie na Robolectric, usunięcie wpisu
  kasuje plik) i `backend` tylko jeśli pojawi się logika domenowa.
- Zmiana: `AppDatabase.kt` (v9, `AutoMigration(8, 9)`), `Entities.kt`, `Daos.kt`, `AndroidManifest.xml` (tylko przy aparacie),
  `backup_rules.xml` i `data_extraction_rules.xml` (tylko przy kopii), `ui/RefuelFormScreen.kt`, `ui/ExpensesScreen.kt`,
  `ui/Texts.kt` (pointwise), `docs/*`.
- Migracja testowana wzorem `AppDatabaseMigrationTest` (wersja 8 → 9, stare wiersze z `NULL`).

## 8. Walidacja i kolejność

1. Decyzje z pkt 5 od właściciela.
2. Sprawdzenie na emulatorze (właściciel): wybór zdjęcia, rozmiar pliku, brak EXIF GPS, usunięcie wpisu kasuje plik,
   kopia (jeśli włączona) – czy zdjęcia są w kopii i czy limit nie jest przekroczony.
3. Gradle `:core:test :app:testDebugUnitTest`; liczby z raportu; nic nie z Playa ani sieci.

## 9. Pytania otwarte

1. Czy zdjęcia w Auto Backup (pkt 5.1): rekomendacja nie.
2. Czy URI z `PickVisualMedia` da się trwale zapamiętać (wariant b) – do sprawdzenia na urządzeniu, nie w kodzie.
3. Limit 25 MB dla kopii Google Drive: potwierdzić w dokumentacji Androida przed implementacją.
4. Czy w starszym archiwum wpisy bez zdjęcia pozostają bez zmian (tak, zakładam; do potwierdzenia testem).
