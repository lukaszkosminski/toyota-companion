# C44 – plan: wydzielenie i testy warstwy SAF (Storage Access Framework) dla automatycznej kopii (PRD F60, SPEC §60)

Status: **oczekuje na decyzję architekta / użytkownika** (zgłoszenie z pkt 1.3 `C43-code-review.md`).
Autor: agent programistyczny. Data: 2026-11-12.

---

## 1. Diagnoza testowalności warstwy SAF w `AppContainer.kt` bez wydzielenia

Przeanalizowano kod integracji z platformą Android Storage Access Framework (SAF) w pliku [AppContainer.kt:528-627](file:///home/lk/projekty/toyota-companion/android/app/src/main/kotlin/pl/lk/toyotacompanion/AppContainer.kt#L528-L627):
- Metoda `folderChildren(folder: String): Map<String, Uri>` jest prywatna (`private fun`) wewnątrz `AppContainer`.
- Operacje SAF (`folderName`, `writeCopy`, `listCopies`, `deleteCopy`, oraz `releasePersistableUriPermission` w `saveFolder`) są anonimowymi lambdami przekazywanymi bezpośrednio do konstruktora `AutoBackup` w bloku `val autoBackup: AutoBackup by lazy { ... }`.
- Klasa `AutoBackup` przyjmuje lambdy funkcyjne i nie udostępnia metod I/O na zewnątrz (są one prywatnymi polami instancji).
- Utworzenie instancji `AppContainer` w teście Robolectric pociąga za sobą pełne środowisko aplikacji: konfigurację bazy Room (`AppDatabase`), `SyncEngine`, repozytoria, `WorkManager` oraz `SettingsStore`. Wywołanie metody `autoBackup.runAfterSync()` uruchamia pełną serializację archiwum (`encodeArchive(archiveSnapshot())`), co wymaga spójnego stanu kilkunastu tabel bazy SQLite.
- Bezpośrednie przetestowanie zachowań brzegowych i awarii (np. usunięcie nieukończonego pliku po błędzie strumienia – pkt 1.1 `C43-code-review.md`, ignorowanie błędu zmiany nazwy pliku przy sufiksie `(1)` – pkt 1.2, czy brak uprawnień) wymagałoby głębokiego mockowania niskopoziomowych wywołań IPC `ContentResolver.call` lub duplikowania logiki produkcyjnej w plikach testowych (tak jak zrobiono w `ReleaseTreeDocumentUriPermissionTest.kt`), co nie zapewnia testowania faktycznego kodu produkcyjnego.

**Wniosek:** Warstwa SAF w obecnym kształcie `AppContainer.kt` **nie jest testowalna jednostkowo bez wydzielenia**. Aby umożliwić bezpośrednie testy Robolectric bez mockowania całego kontenera aplikacji, logika SAF powinna zostać wydzielona do dedykowanego komponentu.

---

## 2. Minimalny plan wydzielenia

### 2.1 Nowa klasa: `SafFolderStorage`

Wydzielenie logiki operacji na plikach i katalogach SAF do osobnej klasy:
- **Pakiet:** `pl.lk.toyotacompanion.storage` (w module `android/app`)
- **Plik:** `android/app/src/main/kotlin/pl/lk/toyotacompanion/storage/SafFolderStorage.kt`
- **Konstruktor:** przyjmuje wyłącznie `private val contentResolver: ContentResolver` (opcjonalnie `Context`).
- **Zależności:** brak zależności od domenowych klas aplikacji (`AutoBackup`, `SettingsStore`, `Room`, `TripRepository`). Czysta warstwa I/O Android SAF.

### 2.2 Zestaw funkcji do wydzielenia

```kotlin
package pl.lk.toyotacompanion.storage

import android.content.ContentResolver
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import java.io.IOException

class SafFolderStorage(
    private val contentResolver: ContentResolver,
) {
    /** Odczytuje nazwę wyświetlaną folderu (COLUMN_DISPLAY_NAME) dla karty kopii zapasowej. */
    fun folderDisplayName(treeUri: Uri): String? {
        return try {
            val self = DocumentsContract.buildDocumentUriUsingTree(treeUri, DocumentsContract.getTreeDocumentId(treeUri))
            contentResolver.query(self, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)
                ?.use { if (it.moveToFirst()) it.getString(0) else null }
        } catch (e: Exception) {
            null
        }
    }

    /** Zwraca mapę plików (z pominięciem podkatalogów) w folderze: nazwa -> URI dokumentu. */
    fun listFiles(treeUri: Uri): Map<String, Uri> {
        val parentId = DocumentsContract.getTreeDocumentId(treeUri)
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentId)
        val result = LinkedHashMap<String, Uri>()
        contentResolver.query(
            childrenUri,
            arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE,
            ),
            null,
            null,
            null,
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                val id = cursor.getString(0) ?: continue
                val name = cursor.getString(1) ?: continue
                if (cursor.getString(2) == DocumentsContract.Document.MIME_TYPE_DIR) continue
                result[name] = DocumentsContract.buildDocumentUriUsingTree(treeUri, id)
            }
        }
        return result
    }

    /**
     * Zapisuje bajty do wskazanego pliku. Jeśli plik istnieje, nadpisuje go.
     * W przypadku błędu zapisu usuwa plik tymczasowy/częściowy (pkt 1.1).
     * Jeśli dostawca nadał nazwę z sufiksem duplikatu, podejmuje próbę renameDocument.
     */
    fun writeDocument(treeUri: Uri, name: String, mimeType: String, bytes: ByteArray): Uri {
        val target = listFiles(treeUri)[name]
            ?: DocumentsContract.createDocument(
                contentResolver,
                DocumentsContract.buildDocumentUriUsingTree(treeUri, DocumentsContract.getTreeDocumentId(treeUri)),
                mimeType,
                name,
            )
            ?: throw IOException("Failed to create document: $name")

        try {
            val out = contentResolver.openOutputStream(target, "wt")
                ?: throw IOException("Failed to open output stream for: $target")
            out.use { it.write(bytes) }
        } catch (e: Exception) {
            try {
                DocumentsContract.deleteDocument(contentResolver, target)
            } catch (ignored: Exception) {
            }
            throw e
        }

        if (name !in listFiles(treeUri)) {
            try {
                DocumentsContract.renameDocument(contentResolver, target, name)
            } catch (e: Exception) {
                // Dostawca może nie wspierać zmiany nazwy (np. chmura lub karta SD)
            }
        }
        return target
    }

    /** Usuwa plik o wskazanej nazwie z folderu. Zwraca true jeśli plik istniał i został usunięty. */
    fun deleteDocument(treeUri: Uri, name: String): Boolean {
        val docUri = listFiles(treeUri)[name] ?: return false
        return DocumentsContract.deleteDocument(contentResolver, docUri)
    }

    /** Zwalnia trwałe uprawnienia do URI folderu porzuconego przez użytkownika. */
    fun releaseFolderPermission(treeUri: Uri) {
        try {
            contentResolver.releasePersistableUriPermission(
                treeUri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
        } catch (e: SecurityException) {
            // Uprawnienie zostało już cofnięte przez system
        }
    }
}
```

---

## 3. Integracja w `AppContainer.kt`

W [AppContainer.kt](file:///home/lk/projekty/toyota-companion/android/app/src/main/kotlin/pl/lk/toyotacompanion/AppContainer.kt):
1. Usunięcie prywatnej metody `folderChildren`.
2. Dodanie instancji pomocniczej:
   ```kotlin
   private val safFolderStorage by lazy { SafFolderStorage(context.contentResolver) }
   ```
3. Uproszczenie konfiguracji `autoBackup` do bezpośrednich wywołań:
   ```kotlin
   val autoBackup: AutoBackup by lazy {
       AutoBackup(
           readFolder = { settingsStore.autoBackupFolder() },
           saveFolder = { next ->
               val previous = settingsStore.autoBackupFolder()
               if (previous != null && previous != next) {
                   safFolderStorage.releaseFolderPermission(previous.toUri())
               }
               settingsStore.saveAutoBackupFolder(next)
           },
           readLastCopy = { settingsStore.autoBackupLastDay() },
           saveLastCopy = { settingsStore.saveAutoBackupLastDay(it) },
           folderName = { folder -> safFolderStorage.folderDisplayName(folder.toUri()) ?: "?" },
           writeCopy = { folder, name, bytes ->
               safFolderStorage.writeDocument(folder.toUri(), name, "application/json", bytes)
           },
           listCopies = { folder -> safFolderStorage.listFiles(folder.toUri()).keys.toList() },
           deleteCopy = { folder, name -> safFolderStorage.deleteDocument(folder.toUri(), name) },
           export = { encodeArchive(archiveSnapshot()) },
           saveLastExport = { at -> settingsStore.saveLastArchiveExport(at) },
           scope = uiScope,
           io = Dispatchers.IO,
       )
   }
   ```

---

## 4. Plan testów jednostkowych (Robolectric bez nowych bibliotek)

### 4.1 Plik testowy
- **Ścieżka:** `android/app/src/test/kotlin/pl/lk/toyotacompanion/storage/SafFolderStorageTest.kt`
- **Konfiguracja:** `@RunWith(RobolectricTestRunner::class)` (Robolectric jest już w zależnościach `:app`).
- **Brak nowych zależności:** wykorzystanie standardowej klasy `android.provider.DocumentsProvider` z Android SDK do utworzenia pamięciowego testowego dostawcy dokumentów `TestDocumentsProvider` rejestrowanego przez `Robolectric.buildContentProvider(TestDocumentsProvider::class.java).create("pl.lk.toyotacompanion.test.documents")`.

### 4.2 Zakres testów

| # | Przypadek testowy | Weryfikowane zachowanie |
|---|---|---|
| 1 | `folderDisplayName returns name when query succeeds` | Poprawne odczytanie nazwy katalogu z kursora |
| 2 | `folderDisplayName returns null on error or empty cursor` | Bezpieczna obsługa błędu odczytu (brak rzucania wyjątków) |
| 3 | `listFiles returns non-directory files with tree document URIs` | Poprawne parsowanie kursora dzieci i mapowanie nazwa -> URI |
| 4 | `listFiles ignores entries with MIME_TYPE_DIR` | Pomijanie podkatalogów (nieusuwanie podkatalogów przy rotacji) |
| 5 | `writeDocument creates document and writes bytes` | Poprawny zapis danych do pliku |
| 6 | `writeDocument overwrites existing file without creating duplicates` | Brak tworzenia plików z sufiksem `(1)` przy ponownym zapisie |
| 7 | `writeDocument deletes incomplete file on stream write failure` | **Pkt 1.1:** Weryfikacja usunięcia częściowego pliku przez `deleteDocument` przy wyjątku `IOException` |
| 8 | `writeDocument attempts rename when provider adds duplicate suffix` | Tolerancyjna próba zmiany nazwy i ciche ignorowanie błędu, gdy dostawca nie pozwala na rename |
| 9 | `deleteDocument deletes document when file exists and returns true` | Skuteczne usunięcie pliku z folderu |
| 10 | `deleteDocument returns false when file does not exist` | Bezpieczne zakończenie przy braku pliku |
| 11 | `releaseFolderPermission releases flags and ignores SecurityException` | Poprawne przekazanie flag odczytu i zapisu oraz obsługa unieważnionego URI |

---

## 5. Rekomendacja

Plan wymaga modyfikacji tylko dwóch plików produkcyjnych (`SafFolderStorage.kt` – nowy, oraz `AppContainer.kt` – odchudzenie o 60 linii) i utworzenia jednego pliku testowego (`SafFolderStorageTest.kt`). Nie wymaga żadnych zmian w Gradle, bazie danych Room ani modelach domenowych.
