# C42 – plan: automatyczna kopia archiwum do wybranego folderu (PRD F60, SPEC §60)

Status: **zaakceptowany przez agenta głównego 2026-11-11** (zastępuje wstrzymany import z Fuelio/Drivvo).
Autor: agent główny. Data: 2026-11-11.

## 1. Cel

Właściciel wybiera raz folder (dowolny dostawca: karta SD, Dysk Google, NAS przez aplikację), a aplikacja po każdej
udanej synchronizacji dopisuje **jedną kopię dziennie** archiwum w JSON i trzyma tylko ostatnie 10. Kopia liczy się jak
każda inna (data „Ostatnia kopia archiwum” i przypomnienie F8 przestają napominać). Bez nowych uprawnień w Manifeście
(SAF: `ACTION_OPEN_DOCUMENT_TREE` + trwałe uprawnienie do URI), bez Room, bez sieci i bez budzenia auta.

## 2. Decyzje jawne

- **Format:** ten sam plik co ręczny eksport (`encodeArchive`, §14), więc każdą kopię można zaimportować ręcznie.
- **Nazwa:** `toyota-companion-RRRR-MM-DD.json` (data lokalna). Nazwa jest kluczem rotacji: tylko pliki z tym wzorcem
  są kasowane, obce pliki w folderze zostają nietknięte.
- **Raz dziennie:** kopia powstaje przy pierwszej udanej synchronizacji dnia; kolejne synchronizacje tego dnia nic nie
  robią. Nie ma własnego alarmu ani zadania w tle (zasada aplikacji: bez budzenia i bez harmonogramu poza WorkManagerem).
- **Rotacja:** po udanym zapisie lista plików wzorca jest sortowana po dacie z nazwy malejąco; zostaje 10 najnowszych,
  starsze są kasowane. Kasowanie idzie po dokładnym URI dziecka, nigdy po samym prefiksie.
- **Błąd:** nie zapisuje daty (następna synchronizacja spróbuje znowu), nie psuje synchronizacji, a ekran „Kopia
  i eksport” pokazuje jedno zdanie „ostatnia automatyczna kopia się nie udała”.
- **Wyłączenie:** usunięcie folderu („Wyłącz”) kasuje URI i datę ostatniej automatycznej kopii; istniejące pliki zostają
  w folderze (aplikacja ich nie usuwa przy wyłączaniu).
- **Ustawienia telefonu:** URI folderu i data ostatniej automatycznej kopii w `SettingsStore` (Auto Backup, nie sekret).
  Wylogowanie ich nie czyści (to ustawienie telefonu, jak cena domyślna).
- **Prywatność:** kopie zawierają to samo co ręczny eksport (trasy z GPS, VIN, tankowania, wydatki) – leżą w folderze
  wskazanym przez właściciela, więc SECURITY mówi to wprost; brak sieci po stronie aplikacji (synchronizacja folderu
  w chmurze to decyzja dostawcy folderu, nie aplikacji).

## 3. Pliki

| Warstwa | Plik |
|---|---|
| `app/ui` | `AutoBackupOps.kt` (nowy): nazwa i data z nazwy, decyzja „kopia dzisiaj?”, rotacja |
| `app/ui` | `AutoBackup.kt` (nowy): kontroler (folder, data, błąd, `chooseFolder`, `disable`, `runAfterSync`, `reset`) |
| `app/ui` | `BackupScreen.kt`: karta „Automatyczna kopia” (wybór folderu, nazwa, wyłączenie, data, błąd) |
| `app` | `AppContainer.kt`: lambdy SAF (lista, zapis, kasowanie, nazwa folderu) i wywołanie w `syncRunner` |
| `settings` | `SettingsStore.kt`: `auto_backup_folder`, `auto_backup_last` |
| `ui/Texts.kt` | teksty karty PL/EN |

Bez zmian Gradle, Room i Manifestu; bez nowych zależności (`DocumentsContract` + `ContentResolver` wystarczą).

## 4. Testy

- `AutoBackupOpsTest`: nazwa z daty, data z nazwy, obca nazwa = brak daty; decyzja: brak folderu = nie, brak kopii = tak,
  ten sam dzień = nie, wczoraj = tak; rotacja: 12 plików wzorca → 2 najstarsze do usunięcia, obce nazwy nietknięte,
  mniej niż limit → nic.
- `AutoBackupTest` (kontroler, bez Androida): zapis raz dziennie, drugie wywołanie tego dnia nic, brak folderu nic,
  błąd zapisu nie zapisuje daty i ustawia komunikat, sukces zapisuje datę i datę eksportu, rotacja woła kasowanie
  dla właściwych nazw, „Wyłącz” czyści folder i datę, `reset` czyści tylko stan w pamięci.
- `SettingsStoreTest`: round-trip obu kluczy, śmieci i zły typ czytane jako brak.

## 5. Prywatność i bezpieczeństwo

Kopia jest plikiem właściciela w jego folderze; aplikacja nie wysyła jej nigdzie sama. URI folderu jest w `settings`
(objęty Auto Backup, nie sekret). Komunikaty błędów nie zawierają ścieżki ani treści wyjątku. Brak nowych uprawnień.

## 6. Walidacja

- Python: bez zmian (funkcja jest w warstwie `app`).
- Android: `:core:test`, `:app:testDebugUnitTest`, `:app:lintDebug`, `:app:assembleDebug`, `:app:assembleRelease`.
- Emulator: wybór folderu, zapis kopii po synchronizacji, druga synchronizacja tego dnia bez nowego pliku, rotacja
  przy 12 plikach, wyłączenie, zachowanie po restarcie aplikacji.

## 7. Kolejność

1. `AutoBackupOps` + testy. 2. Kontroler + testy. 3. SettingsStore + teksty. 4. Karta w „Kopia i eksport” i glue SAF
w `AppContainer` + wywołanie w `syncRunner`. 5. Docs. 6. Walidacja, emulator, commit.

## 8. Poza zakresem

- Automatyczna kopia przy każdym tankowaniu lub codziennie o godzinie (wymagałoby harmonogramu).
- Wybór liczby trzymanych kopii w UI (stała 10) i szyfrowanie kopii.
- Import z CSV innych aplikacji (wstrzymany: brak wzorca formatu).
