# C42 – przegląd F60 „Automatyczna kopia archiwum do folderu” (niezacommitowane), tylko odczyt
Data: 2026-11-11. Autor: agent 3 (pane w8:pD). Zakres: `git status`/`git diff`, `ui/AutoBackup.kt`, `ui/AutoBackupOps.kt`, `ui/AutoBackupTest.kt`,
`ui/AutoBackupOpsTest.kt`, glue SAF w `AppContainer.kt` (`folderChildren`, `autoBackup`), karta w `ui/BackupScreen.kt`, `settings/SettingsStore.kt`.
Bez Gradle, bez testów, bez git poza odczytem, bez sieci i emulatora. **[K]** potwierdzone w kodzie, **[P]** przypuszczenie.
## Ważne
**1. [K] Zmiana folderu nie zeruje daty ostatniej kopii → nowy folder nie dostaje kopii, a karta kłamie.**
`AutoBackup.kt:93–99` (`chooseFolder`): `saveFolder(folder)` i `load()`, bez `saveLastCopy(null)` — inaczej niż `disable()` (`:105–113`).
Scenariusz: kopia do folderu A rano, o 12:00 wybór folderu B; `shouldCopyToday(hasFolder=true, lastCopy=dziś)` (`:127`) daje „nie” do północy,
więc karta pokazuje „Folder: B” i „Ostatnia automatyczna kopia: dziś”, choć w B nie ma pliku. Poprawka: `saveLastCopy(null)` w `chooseFolder`
albo porównywanie dnia per folder.
**2. [K] Rotacja może skasować dzisiejszą kopię, którą właśnie zapisano, a dzień zostaje oznaczony jako zrobiony.**
`AutoBackupOps.kt:35–39` (`copiesToDelete`) sortuje po dacie z nazwy malejąco i usuwa wszystko po `keep`, bez wyjątku dla nazwy dzisiejszej;
`AutoBackup.kt:129–131` zapisuje plik i **przed** rotacją `saveLastCopy(day)`. Scenariusz: zegar cofnięty o >10 dni albo folder z plikami z
przyszłości (przywrócony z telefonu z błędnym zegarem): dziś 2026-11-11, w folderze 10+ kopii z 2026-11-12…2026-12-01 → kopia dnia powstaje i
od razu leci do kosza, `lastCopy` = 11.11, kolejna synchronizacja tego dnia nic nie robi: dane dnia przepadają, karta pokazuje sukces.
Poprawka: wykluczyć `autoCopyName(day)` z kasowania albo rotować przed zapisem i sprawdzić po rotacji, że plik dnia istnieje, zanim zapisze się datę.
**3. [K] Rotacja nie rozróżnia pliku od katalogu — kasuje po nazwie, bez sprawdzenia typu.**
`AppContainer.kt:529–547` (`folderChildren`) pobiera tylko `COLUMN_DOCUMENT_ID` i `COLUMN_DISPLAY_NAME`, bez `COLUMN_MIME_TYPE`;
`:586–588` (`deleteCopy`) robi `DocumentsContract.deleteDocument` na tym, co przyszło z listy, a `AutoBackupOps.kt:20–27` uznaje za „nasze”
każdą nazwę `toyota-companion-RRRR-MM-DD.json`. Scenariusz: w folderze katalog o nazwie `toyota-companion-2026-11-01.json` (np. po ręcznym
uporządkowaniu) i >10 nowszych kopii → rotacja usuwa katalog z zawartością; ten sam mechanizm usuwa plik tej nazwy zrobiony ręcznie lub na
innym telefonie. Poprawka: dołożyć `COLUMN_MIME_TYPE` i dopuszczać do rotacji tylko `application/json`, nie-katalogi.
**4. [K] Uprawnienie trwałe nigdy nie jest zwalniane, a jego brak jest połykany bez komunikatu.**
`BackupScreen.kt:63–76`: `takePersistableUriPermission` w `try/catch (SecurityException)`, dalej `if (granted) chooseFolder(...)` — przy
`granted == false` nic się nie dzieje i nie ma żadnego tekstu; `AutoBackup.kt:93–113` nie woła `releasePersistableUriPermission`. Scenariusz:
kilka zmian folderu kumuluje granty (limit 128 na aplikację); po przekroczeniu `takePersistableUriPermission` rzuca i „Wybierz folder”
przestaje działać bez wyjaśnienia. Poprawka: zwalniać grant przy wyłączeniu i przed zapisaniem nowego folderu; przy `granted == false`
ustawić błąd karty („brak dostępu, wybierz folder ponownie”).
**5. [K] Nazwa nadana przez dostawcę przy `createDocument` nie jest sprawdzana → duplikaty „ (1)” nigdy nie są rotowane.**
`AppContainer.kt:574–584`: `folderChildren(folder)[name] ?: createDocument(...)`, wynik użyty bez odczytu nazwy; `AutoBackupOps.kt:20–24`
wymaga dokładnie `RRRR-MM-DD`, więc `toyota-companion-2026-11-11 (1).json` = obcy plik. Scenariusz: dostawca
(`ExternalStorageProvider.buildUniqueFile`) dopisze „ (1)”, bo zapytanie o dzieci nie widziało jeszcze pliku z poprzedniej próby → kopia dnia
istnieje pod inną nazwą, nie jest rozpoznawana przy ponownej próbie i nie wchodzi do rotacji, więc folder rośnie bez limitu 10, a karta
pokazuje sukces. Poprawka: po `createDocument` odczytać `COLUMN_DISPLAY_NAME`; inna nazwa → usunąć dokument i zwrócić błąd (albo objąć
sufiks ` (n)` rotacją).
## Drobne
**6. [K]** `load()` nie czyści `failed` (`AutoBackup.kt:67–91`) — po nieudanej kopii i ponownym wybraniu folderu karta dalej pokazuje błąd.
**7. [K]** `folderName` (`AppContainer.kt:560–569`) łapie wyjątek i zwraca `"?"`, więc po utracie grantu karta pokazuje „Folder: ?”, nie „brak dostępu”.
**8. [K]** `disable()`/`chooseFolder()` są poza `lock` (`AutoBackup.kt:93–113` vs `:121–131`): kopia może wylądować w folderze już wyłączonym,
a `saveLastCopy(day)` wykonać się po `saveLastCopy(null)` z `disable`.
**9. [K]** Pierwsza klatka karty pokazuje „Wyłączona – nie wybrano folderu” (stan startowy ma `folderName = null`), zanim `load()`
(`BackupScreen.kt:61`) przeczyta ustawienia.
**10. [K]** Zapis nie jest atomowy (`AppContainer.kt:582–583`): przerwany proces zostawia ucięty JSON pod nazwą dnia (ponowna próba nadpisze).
**11. [P]** `"wt"` dla istniejącego pliku (`AppContainer.kt:582`): dostawca, który nie obcina pliku, zostawia ogon starej treści i psuje JSON.
Ten sam tryb działa w eksporcie ręcznym (`:617`), więc ryzyko dotyczy głównie dostawców chmurowych.
**12. [P]** Kolizja z eksportem ręcznym: `BackupScreen.kt:240` proponuje `toyota-companion-RRRR-MM-DD.json`, czyli nazwę kopii
(`AutoBackupOps.kt:16`); ręczny eksport z tego dnia do tego folderu zostaje nadpisany przez kopię automatyczną.
**13. [P]** Testy z domyślnym `Locale`: `"2026-11-%02d".format(day)` w `AutoBackupOpsTest.kt:41,51` i `AutoBackupTest.kt:113`; w locale z
cyframi nie-ASCII (np. `ar`) nazwy przestają pasować do wzorca (SPEC §14.3: bez `Locale`).
## Obszary bez zastrzeżeń
- **I/O poza Main [K]:** `runAfterSync` wołany w `withContext(io)` z `SessionController.kt:142` i w `SyncWorker`; folder, zapis, lista i
  kasowanie idą przez `withContext(io)` (`AutoBackup.kt:124–150`), `export()` (Room + `encodeArchive`) też leci na IO.
- **Strefa i północ [K]:** dzień liczony `LocalDate.now(zone())` w momencie zapisu, `zone` wstrzykiwane, brak własnego alarmu.
- **Prywatność [K]:** URI folderu nie trafia do logów ani komunikatów (`failed` to `Boolean`, `AUTO_COPY_FAILED` ogólny); `ArchiveSnapshot`
  nie zawiera ustawień, więc URI nie wycieka do pliku kopii. Do docs: URI leży w `settings`, które Auto Backup wysyła na Dysk właściciela, a
  grant nie jest przywracany — po restarcie na innym telefonie folder będzie ustawiony, ale niedostępny (dopisać w SECURITY/SPEC §60).
- **Wylogowanie [K]:** `AppRoot.kt:138` woła `reset()`, które czyści tylko stan w pamięci; folder i grant zostają — zgodnie z planem.
## Luki w testach (obszar g)
Brak testów: dwóch równoległych `runAfterSync` (mutex opisany, nie sprawdzony); `export()` rzucającego (brak daty i `failed`); `readLastCopy`
rzucającego; przypadku z punktu 2 (dzisiejsza kopia wypada z rotacji); nazwy z sufiksem „ (1)”; `chooseFolder` nie zerującego daty;
`disable()` w trakcie zapisu; `copiesToDelete(keep = 0)`. Cała warstwa SAF (`folderChildren`, `createDocument`, `deleteDocument`,
`takePersistableUriPermission`) nie ma żadnego testu — jedyny niepokryty fragment.
## Nie sprawdzono
Zachowania dostawców (Dysk Google, NAS, karta SD) przy `createDocument`/`"wt"`/`deleteDocument`; emulatora; `docs/SPEC.md §60`,
`docs/SECURITY.md`, PRD F60 i CHANGELOG (jeszcze nie zmienione); lintu i kompilacji.
