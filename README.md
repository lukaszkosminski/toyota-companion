# toyota-companion

A private companion app for Android for a hybrid Toyota in Europe. It reads data from the MyToyota account through the unofficial API (the Toyota client is a Kotlin port of [pytoyoda](https://github.com/pytoyoda/pytoyoda) v6.0.0), archives trips indefinitely, calculates the fuel cost of every trip and shows consumption, statistics and weather. It was built for one owner of one car (PRD §3) and runs entirely on the phone, with no server of its own.

The source of truth is the documents in [`docs/`](docs/), which are written in Polish: the [PRD](docs/PRD.md) (features F1–F55) and the [SPEC](docs/SPEC.md) (acceptance criteria). The description below is a short summary; where they differ, the docs win. The app itself is in Polish, with an English UI language available in "Opcje" (Options).

## Features

### Sync and archive
- Background sync every 30 minutes (WorkManager) and manually with "Synchronizuj teraz" (Sync now): vehicle, telemetry, trips. Trips are stored locally without duplicates, because Toyota deletes them after about 12 months (F5, SPEC §8). The app never calls `refresh_status()`, which drains the 12 V battery.
- Trip list with cost, grouped by month (SPEC §10), with the weather of the day (F25) and the kind of drive: slow, city, flowing, route (F27, SPEC §10.3).

### Costs and refuels
- Refuel detection from a jump in the fuel level, and a price question as a notification or an in-app dialog (F1, F2, F2c, SPEC §1–§2, §11, §13).
- Liters estimated from the sensor (F2a, SPEC §2a), a default fuel price (F2b, SPEC §12), the 95 price from trojmiasto.pl for the owner's two stations (F22, SPEC §25).
- Trip cost from the weighted average price of the fuel in the tank (F3, ADR-005, SPEC §4, §6), recalculated backwards after every confirmed price (F4, SPEC §5).
- Refuel log and "computer vs actual" consumption calculated from refuel to refuel (F9, SPEC §15).

### Consumption, statistics and driving style
- Consumption and monthly costs on the status screen, the manufacturer's declared consumption, an alert for rising consumption (F20, F26, F29, F30, SPEC §24, §30, §31).
- Eco goals: an optional monthly goal for consumption and for the share of electric driving, with progress on the status screen and in the widget (F39, SPEC §40).
- Monthly budget: one optional limit per phone for fuel and non-fuel expenses, with progress on the status screen counted only from the local archive (F48, SPEC §49); one notification from 80% of the limit and one when it is exceeded, once per month and threshold, with a switch in Options (F49).
- Period summary notification: off by default; one notification after each completed week or month with km, cost, litres per 100 km and the share on battery, and the comparison with the period before in the expanded view (F54, SPEC §54).
- Best time to leave: for the most driven repeated routes, the median drive time and litres per 100 km by the hour of the start and the day (working days or weekend), in the driving style screen (F53, SPEC §53).
- Trip cost calculator: kilometres and a kind of drive give a range of litres and zloty from your own trips of that kind, quartiles of the consumption, with the price of the last priced trip or the default price (F52, SPEC §52).
- Short trips and the cold engine: trips by length band (under 2, 2–5, 5–10, 10–25, 25 km and more) with the share of trips and km, litres per 100 km as sum over sum, the excess of short trips over trips from 10 km and cold against warm starts, from the local archive only (F50, SPEC §50).
- CO₂ footprint: an estimate from the litres burned, in the month header of the trip list and in the year summary (F51, SPEC §51); combustion of gasoline only, no electricity or car production.
- Multi-year statistics: years, seasons, year over year (F10, SPEC §17); electric driving per trip, ranking and weeks (F11, F35, SPEC §16, §36); year summary (F28, SPEC §29).
- Driving style: eco score by month, repeated routes and consumption anomalies (F12, F13, SPEC §19); trip types: home, work, commute, weekend (F16, SPEC §21).
- Refuel day forecast (F14, SPEC §20).

### Weather and driving pace
- Effect of temperature and rain on consumption, and a driving pace × temperature table (F15, F24, SPEC §23, §28); consumption by average speed band (F19, SPEC §22).

### Stops and map
- Parking diary: places the car returns to and stop durations (F17, SPEC §18).
- OpenStreetMap map after tapping a trip, with the GPS route from Toyota (green: electric, blue: engine) fetched only when the map is opened (F21, F23, SPEC §26, §27); saving the routes of older trips (F32, SPEC §33) and a heat map of repeated routes (F33, SPEC §34).

### Expenses
- Non-fuel expenses (service, inspection, tyres, insurance, parts, car wash, fees, other) with a date that can be in the past, and totals per category and month (F37, SPEC §38, ADR-018). Local only.
- Total cost of ownership in the year summary: fuel plus expenses, per km, per 100 km and per month (F38, SPEC §39, ADR-020).
- Depreciation in the year summary: the purchase price, purchase date, estimated value and its date typed in Options; a straight-line loss in days as a separate estimate next to the cash, never mixed with the expenses (F55, SPEC §55).

### Widget and Android Auto
- Home screen widget: average consumption, trend, default price, refuel forecast and the eco goal line (F31, SPEC §32).
- Read-only screen in Android Auto (F34, SPEC §35, ADR-014); for personal use, it requires "Unknown sources" enabled in the Android Auto developer settings. The SPEC notes that it was not run on a real car head unit or in the Desktop Head Unit.

### Backup and export
- Export and import of the whole archive as a JSON file, with a reminder after 30 days (F7, F8, SPEC §14); spreadsheets as CSV for Polish Excel: trips, refuels and expenses (F6, F47, SPEC §14.4, §48); optional Google Auto Backup (ADR-015).

### Update from GitHub Releases
- "Opcje" (Options) → "Sprawdź aktualizacje" (Check for updates) downloads and installs a newer release, verifying size, SHA-256, package, version and signature (F36, SPEC §37, ADR-016).

## Installation

The ready-made APK (about 4 MB, Android 10 or newer, `minSdk` 29) is in the [GitHub releases](https://github.com/lukaszkosminski/toyota-companion/releases). The first installation is manual: download `toyota-companion-<version>.apk` and allow the browser or file manager to "Install unknown apps". Later versions: in the app, "Opcje" → "Sprawdź aktualizacje" → "Pobierz i zainstaluj" (Download and install). At the first update the app asks for the same permission for itself, and the system still shows its own confirmation.

Moving from a debug build (signed with a different key, so it cannot be updated in place):
1. in the old version, "Kopia i eksport" (Backup and export) → "Eksportuj archiwum" (Export archive); the JSON file contains GPS and VIN, so keep it somewhere safe,
2. uninstall the debug build,
3. install the APK from the release,
4. log in to MyToyota,
5. "Importuj archiwum" (Import archive).

The release signing key lives only in the repository secrets and in the owner's own backup; losing it means another uninstall (ADR-016).

## Privacy and security

Details and reasoning: [docs/SECURITY.md](docs/SECURITY.md).

- The Toyota login, password and tokens are encrypted with AES-256-GCM using a key from the Android Keystore (ADR-007). They never reach the repository, the logs or the backups, and the logs contain no passwords, tokens, VIN or GPS coordinates.
- Trips, VIN and expenses are in a Room database on the phone. The export file contains GPS and VIN (but no password or tokens), and the app warns about it when exporting.
- What leaves the phone and where it goes:
  - **Toyota Connected Europe**: login, vehicle, telemetry, trips (as in sync), and the route of a chosen trip when its map is opened.
  - **Open-Meteo** (HTTPS): only the trip start coordinates rounded to 0.01° (about 1 km), dates and a list of variables; never VIN, account or token.
  - **OpenStreetMap** (map tiles): the server sees the area being viewed, with point coordinates rounded to about 100 m.
  - **trojmiasto.pl**: one plain `GET` of the price page after tapping a station, with no owner data.
  - **GitHub**: only after tapping "Sprawdź aktualizacje", with no token or account; GitHub sees the IP address and the app version.
  - **Google Drive** (Auto Backup, ADR-015): the archive database and settings; the file with the login and tokens is excluded, so after restoring on a new phone you have to log in again.
- Update checks, price fetches and trip routes never happen without a tap. Weather is fetched automatically after a sync, and the switch on the weather screen turns this off.
- CI scans the repository with gitleaks.

## Build and test

Requirements: JDK 21, Android SDK (platform 37), Python 3.12 for `backend/`.

```bash
cd android
./gradlew :core:test :app:testDebugUnitTest :app:assembleDebug   # tests + debug APK: app/build/outputs/apk/debug/
./gradlew :app:lintDebug                                          # Android lint
./gradlew :app:assembleRelease                                    # R8-minified build (ADR-017); unsigned without a key
```

```bash
cd backend
python3 -m venv .venv
.venv/bin/pip install -r requirements-dev.txt
.venv/bin/ruff format --check && .venv/bin/ruff check && .venv/bin/mypy
.venv/bin/python -m pytest -q
```

A change to the domain logic needs a test in Python and one in Kotlin with the same name (docs/ARCHITECTURE.md).

**CI** (`.github/workflows/ci.yml`, pushes to `main` and pull requests): ruff, mypy and pytest; core and app tests, the debug APK, the release build with R8 and Android lint; gitleaks.

**Release** (`.github/workflows/release.yml`): a tag `vMAJOR.MINOR.PATCH` runs the tests and lint, builds `assembleRelease` signed with the key from the repository secrets, checks the signature and the version in the APK and publishes a GitHub Release with the file `toyota-companion-<version>.apk`; the R8 mapping is kept as a workflow artifact. The one-time setup and a local signed build are in [docs/HANDOFF.md](docs/HANDOFF.md).

## Repository layout

```
android/core/   domain logic, sync and the Toyota client (pure Kotlin/JVM, no Android)
android/app/    the app: Jetpack Compose, Room 3, WorkManager, Keystore, widget, Android Auto
backend/        executable specification of the domain in Python (reference tests, no server)
docs/           project documentation (in Polish)
```

| Document | Contents |
|---|---|
| [docs/PRD.md](docs/PRD.md) | requirements and features F1–F39, out of scope |
| [docs/SPEC.md](docs/SPEC.md) | specification and acceptance criteria (§1–§40) |
| [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) | components, rules and dependency failures |
| [docs/DECISIONS.md](docs/DECISIONS.md) | decisions (ADR-001 – ADR-020) |
| [docs/SECURITY.md](docs/SECURITY.md) | protected data, flows to external services, encryption |
| [docs/ROADMAP.md](docs/ROADMAP.md) | done, now, next |
| [docs/DOD.md](docs/DOD.md) | definition of done |
| [docs/HANDOFF.md](docs/HANDOFF.md) | work status, release, local environment |
| [CHANGELOG.md](CHANGELOG.md) | changes per release |
| [THIRD_PARTY_LICENSES.md](THIRD_PARTY_LICENSES.md) | licenses of libraries and data |
| [AGENTS.md](AGENTS.md) | rules for AI agents (docs take precedence, no commits without an instruction) |

## Limitations

- Europe only (the scope of pytoyoda); the Toyota API is unofficial and may change without notice.
- One Toyota account = one car (ADR-019): there is no vehicle picker, and an account with several vehicles ends in a sync error.
- The archive starts at the first sync (the first one fetches up to 365 days); trips older than about 12 months have already been deleted by Toyota.
- No 12 V battery voltage and no hybrid battery level (the fields are not in the API in use, and the calls that could give them wake the car and are forbidden). The only picture of the battery is the share of electric driving from trip data (F35).
- Out of scope: remote control of the car, a "hidden reserve" of fuel, a braking map, a 12 V discharge model, regions outside Europe (PRD §5).
- Station prices cover two stations in Gdańsk hard-coded in the app (SPEC §25); the price can also be typed in.
- Android Auto is not available through Google Play (category), only with developer mode enabled (SPEC §35).

## Licenses and attributions

Full list: [THIRD_PARTY_LICENSES.md](THIRD_PARTY_LICENSES.md).

- The Toyota client code is a port of [pytoyoda](https://github.com/pytoyoda/pytoyoda) v6.0.0, MIT licensed (text in `android/core/NOTICE`).
- Leaflet 1.9.4 (BSD-2-Clause) in the map screen; data and tiles © OpenStreetMap contributors (ODbL, tiles CC BY-SA 2.0).
- Icons: Material Symbols Outlined (Apache 2.0, license text in `android/app/src/main/assets/icons/APACHE-2.0.txt`).
- Weather data: [Open-Meteo.com](https://open-meteo.com/) (CC BY 4.0, attribution always shown on the weather screen).
- Other libraries (OkHttp, Room, WorkManager, Compose, kotlinx and others): Apache-2.0.

The repository has no license file for the app's own code.

Toyota login data never goes into the repository (`credentials.json`, `.env`, `*.db` and `*.jks` keys are in `.gitignore`).
