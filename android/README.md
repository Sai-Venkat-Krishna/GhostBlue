# GhostBlue for Android

The Android version of GhostBlue, built natively in Kotlin with Jetpack Compose
and **Material 3 Expressive**. It's being ported in phases; this folder holds
phases 1 to 3.

| Phase | What | State |
|---|---|---|
| 1 | Vehicle API in Kotlin (BetterBlueKit port): Hyundai USA, test car, tests | **Done** |
| 2 | Storage (Room), charge-session detection, smart charging, backups in both directions | **Done** |
| 3 | Screens: Home, Location, Energy, Trips, Settings; foldable layouts | **Done** |
| 4 | Widgets, Quick Settings tiles, notifications, background automation | Next |
| 5 | Wear OS | |

## Modules

- **`betterbluekit/`**: the vehicle API, a plain Kotlin/JVM library, ported from
  [BetterBlueKit](../BetterBlueKit) one file at a time:
  - `model/`: vehicles, status, commands, trips, errors and HTTP logs.
    Encoded statuses use Swift's property names, so JSON moves between the two
    apps.
  - `api/APIClientBase.kt`: requests over OkHttp, with the same error mapping,
    logging and redaction as the Swift version.
  - `api/hyundaiusa/`: the Hyundai USA (Bluelink) client: sign-in, vehicles,
    cached or live status, lock and unlock, climate, charging, charge limits,
    and EV trips.
  - `api/fake/`: the test car, including GhostBlue's charging simulation. Its
    storage sits behind `FakeVehicleStore`; the app keeps it in its database.
- **`data/`**: the app's storage and the logic built on it, ported from
  `GhostBlue/Models` and `GhostBlue/Utility`. An Android library, so widgets,
  tiles and Wear OS can share it later.
  - `model/`, `db/`: Room entities with the SwiftData models' names and fields
    (accounts, vehicles with every owner setting, charge sessions, climate
    presets, trips, the command log, maintenance, automations, HTTP logs).
  - `vehicle/`: applying a status (`updateStatus`), charge-session detection
    and energy integration, prices, alert rules, the trip archive and the
    interruptible waits between command checks.
  - `charging/`: `ChargeStopPolicy`, `BatteryCapacityCatalog`, the SDG&E
    schedule, tariffs, charging display states and the CSV export.
  - `account/`: `AccountController`, the run-time half of iOS's `BBAccount`:
    login and token reuse, re-authentication rules, commands, trips and
    smart charging. Also `CachedAPIClient`, the HTTP log sink and
    `RoomFakeVehicleStore`, which makes the test car's server state the app's
    own vehicle row, as on iOS.
  - `backup/`: `.ghostbluebackup` files in exactly the iOS format, so a backup
    moves between an iPhone and an Android phone in either direction.
- **`bbcli/`**: command-line tool, the Kotlin counterpart of BetterBlueKit's
  `bbcli`.
- **`app/`**: the Android app, in Material 3 Expressive (wallpaper colours,
  expressive motion and shapes), with the iOS app's Rivian look as an option
  in Settings. Every screen observes Room and acts through `data`; nothing
  of its logic is reimplemented.
  - `GarageModel`: the app's state and actions for every screen (the
    selected car, commands in flight with their progress line, refreshes,
    sign-in, the owner's edits). Commands send, then wait for the car to
    report the change, as iOS's buttons do; tapping again cancels.
  - `MainScaffold`: `NavigationSuiteScaffold`, a bar on phones and a rail on
    foldables and tablets: Home, Location, Energy, Trips and Settings.
  - `home/`: the car (the same renders as iOS), lock and unlock as a
    connected button group, climate as a split button with its presets,
    charging and charge limits, the smart briefing, status and location.
    `RivianDashboard` is the Rivian theme's version.
  - `location/`: the car on Google Maps with Navigate.
  - `energy/`: the charging log, as `ChargeHistoryView`: status, Week /
    Month / 3 months, energy, stats, the trend, insights, sessions with
    editing and manual entry, prices, the SDG&E schedule and CSV export.
  - `trips/`: as `TripsView`: filters, the efficiency trend, energy use,
    saved trips with rename, favourite and delete (with undo), comparison
    and CSV export. `TripAnalytics` ports iOS's `TripAnalytics`,
    `TripChartData` and `TripCSVExporter`.
  - `settings/`: accounts (sign-in, including the test account and its test
    cars), vehicles, theme, units, privacy, backups through the share sheet
    and file picker (and `.ghostbluebackup` files opened from other apps),
    HTTP logs and about.
  - Layouts use two columns from 600 dp wide, as iOS's `AdaptiveLayout`.

  iOS screens not ported in phase 3: rearranging Home's sections, the ABRP
  route planner, the ownership hub (maintenance reminders, automations), the
  test-car editor, the verification-code (MFA) prompt, Face ID lock,
  troubleshooting and the debug export. Smart charging runs only from
  "Check charging now" until phase 4 schedules it.

Not ported yet: Kia USA, Hyundai Canada and Europe, and MFA. The factory
reports these as not yet supported; unlike iOS, which falls back to the test
car when it can't make a client, Android says so.

Where the data layer differs from iOS:

- A VIN is unique in the database. iOS needs `StoreReconciler` to merge the
  duplicates iCloud creates; Room enforces it instead, so that isn't ported.
- `AccountController` addresses cars by VIN and reads the current row at every
  step, rather than holding a model object, so a change made elsewhere (a
  screen, the test car's server) is never overwritten by a stale copy.
- Alerts go to `VehicleAlerts.sink`, which posts nothing until phase 4 adds
  notifications. Live Activity hooks and reminder scheduling are phase 4 too.

## Build and test

Use Android Studio's bundled JDK. Gradle downloads everything else, including
the Android 17 (API 37) platform that Material 3 1.5 needs to compile against.

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
```

```bash
./gradlew :betterbluekit:test :data:testDebugUnitTest :app:testDebugUnitTest :app:assembleDebug
```

The `data` tests run Room under Robolectric, so they need no emulator. So do
the `app` tests: Compose UI tests of the main flows against the test car
(signing in, lock and climate, each tab, units, the Rivian theme, a manual
charging session, trips, privacy, backups, HTTP logs), the two-column layouts
at a Pixel Fold's inner-screen size, and the screens' ported rules. They
include ports of the iOS suites (EnergySessionAccuracy, ChargingReliability,
SmartChargingAutomation, WakeUpReduction, BackupArchive, BackupTransfer,
VehicleArchive, OwnershipFeature) with the smart-charging waits on virtual time.

### Google Maps

The Location tab uses Google Maps. Create a Maps SDK for Android key in Google
Cloud and add it to `local.properties`, which stays out of git:

```properties
MAPS_API_KEY=your-key
```

Without a key the map is a placeholder showing the car's coordinates;
Navigate still works.

### Emulators

`GhostBlue_Pixel_9` and `GhostBlue_Pixel_9_Pro_Fold` (Android 16, Google
APIs) run the app. Give them the host GPU (`hw.gpu.mode=host`) and 4 GB of
RAM; with software rendering every frame takes seconds.

## Try it with your car

```bash
./gradlew :bbcli:installDist && bbcli/build/install/bbcli/bin/bbcli
```

It asks for your Hyundai email, password and PIN; the password and PIN aren't
echoed. Then pick from the menu: fetch vehicles, status (cached, or live from
the car), lock, unlock, climate, charging, charge limits and trips. Sign in as
`testaccount@betterblue.com` with password `betterblue` to use the test car.

## Matching the Swift app

`tools/parity/run.sh` builds the Swift `bbcli` from `../BetterBlueKit`. It runs
every response in `betterbluekit/src/test/resources/fixtures` through both
parsers and compares the results field by field (17 of 17 identical). It also
regenerates the golden files in `src/test/resources/golden` from the Swift code
itself: log redaction, number and duration formatting, and a Swift-encoded
status. The Kotlin tests check against those files.

`tools/parity/app-parity.sh` does the same for the data layer, using the iOS
app's own code. It copies the iOS project to a scratch folder (the real one is
left alone), adds `tools/parity/ios/AndroidParityGoldens.swift` to its tests and
runs it on a simulator. That writes `data/src/test/resources/golden`:

- a backup exported by the iOS app, which the Android tests import, check
  (energy, cost, CSV, the stored login) and export again unchanged;
- what the iOS app made of a backup the Android code wrote: its import summary,
  energy and cost, and its own export, which must match Android's;
- 60 generated runs of statuses through `updateStatus`, compared step by step
  (pending stops, commanded stops, display state) and session by session;
- the SDG&E schedule for every half hour of 2025–2028, tariff costs across time
  zones and daylight-saving changes, and the battery defaults.
