# GhostBlue for Android

The Android version of GhostBlue, built natively in Kotlin with Jetpack Compose
and **Material 3 Expressive**, with a Wear OS app. It was ported in five
phases, all of them in this folder.

| Phase | What | State |
|---|---|---|
| 1 | Vehicle API in Kotlin (BetterBlueKit port): Hyundai USA, test car, tests | **Done** |
| 2 | Storage (Room), charge-session detection, smart charging, backups in both directions | **Done** |
| 3 | Screens: Home, Location, Energy, Trips, Settings; foldable layouts | **Done** |
| 4 | Widgets, Quick Settings tiles, notifications, background automation | **Done** |
| 5 | Wear OS: the watch app, a tile, complications, accounts from the phone | **Done** |

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
  `GhostBlue/Models` and `GhostBlue/Utility`. An Android library, shared by the
  phone app and the watch.
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
    own vehicle row, as on iOS, and `RemoteCommandLog`, which trims the
    command log. `AccountSetup` signs an account in (the test
    account with its test cars) and `RemoteAction` sends a command and waits
    for the car, for the phone, its widgets and the watch alike;
    `AccountHandoff` is the format and merge rules of the accounts the phone
    sends the watch.
  - `model/VehicleImageURLs`: the car renders iOS shows, by model and colour.
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
    HTTP logs and about. Each car has its maintenance reminders (iOS's
    `MaintenanceCenterView`: Quick add, a reminder by date or odometer with
    notes and cost, edited, completed or deleted later) and its remote
    activity; Privacy has every car's remote activity (iOS's
    `RemoteCommandActivityView`, newest first, swipe to delete, Clear).
  - The Energy tab's Charging Costs sheet is iOS's Charging Costs plus the
    tariff of its Charging Intelligence: this month's cost, projection and
    energy against a monthly budget, the super off-peak, on-peak (4–9 PM),
    DC fast and other rates, and the off-peak hours used when SDG&E smart
    charging is off.
  - `security/` and `lock/`: the app lock, iOS's Face ID lock. With it on
    (Settings › Privacy), GhostBlue opens behind `BiometricPrompt`
    (fingerprint, face, or the screen lock as fallback) and locks again when
    it comes back after the chosen timeout. Turning it on asks first; the
    recent-apps preview is blank while it's on.
  - `shortcuts/`: iOS's App Intents and App Shortcuts. Lock, Unlock, Start
    climate and Refresh are static shortcuts for the selected car; the app
    adds dynamic ones for that car (Start climate with its preset, Stop
    climate, charging, each other preset). Touch and hold the icon for Lock,
    Unlock, Start climate and Stop climate; the rest are in the launcher's
    shortcut list. Google Assistant, Routines and apps like Tasker send the
    intent `com.saivenkatkrishna.ghostblue.action.COMMAND` with a `command`
    extra (`lock`, `unlock`, `start_climate`, `stop_climate`, `start_charge`,
    `stop_charge`, `refresh` or `smart_charging`) and optionally `vehicle`
    (a name or VIN) and `preset`, once the owner allows them in Settings ›
    Shortcuts & automation. Commands run through `CommandWorker`, so they
    wait for the car and show progress like widget commands; Unlock waits
    for the phone to be unlocked first. `res/xml/shortcuts.xml` also declares
    an App Actions capability (`actions.intent.OPEN_APP_FEATURE`), which
    Assistant only picks up for an app published on Google Play.
  - Layouts use two columns from 600 dp wide, as iOS's `AdaptiveLayout`.
  - `widgets/`: Glance widgets for iOS's Command Center (small, medium and
    large, with lock and climate buttons and a refresh), Charge Dial and
    Woven Wordmark (the large card where the charge fills the model
    lettering, with charging progress and Lock and Unlock, in a mint, violet,
    amber or coral accent, or iOS's Rivian version under the Rivian theme),
    each optionally pinned to a car. A widget on screen follows the car's
    every status, which car is selected, the units and the theme. Quick
    Settings tiles for lock, climate and charging show the selected car's
    state (unlocking from a locked phone asks for the phone to be unlocked).
  - `background/`: WorkManager and alarms, since Android has no widget
    timelines or Shortcuts. `RefreshWorker` refreshes the cars every widget
    refresh interval (ten minutes while plugged in, five while a charging
    notification is up); `CommandWorker` runs widget and tile commands and
    shows their progress, one per control of a car (Unlock replaces a
    waiting Lock, but not a climate command), and drops one that waited more
    than two minutes to start; `SmartChargingScheduler` runs smart charging
    just after each SDG&E boundary (exact when the owner allows exact
    alarms), which iOS leaves to Shortcuts automations; `ReminderScheduler`
    posts precondition reminders daily and dated maintenance at 9 AM on its
    day. A boundary or reminder whose alarm was moved or held back still
    runs, late. Alarms are set again after a reboot, an update or a clock
    change.
  - `notifications/`: the data layer's alerts as notifications, on their own
    channels, with iOS's switch and quiet hours; a charging notification
    kept current while a car charges (iOS's Live Activity, behind the same
    beta switch).
  - `watch/`: sends the phone's accounts that have signed in to the watch
    over the Wearable Data Layer whenever they change.

  Where Android differs from iOS: smart charging posts what it changed
  (iOS shows it only in Shortcuts); widget, tile and shortcut commands wait
  for the car and show progress, where iOS's intents only send; there are
  no lock screen widgets or Dynamic Island. Each car's alert rules, its
  low-battery, unplugged and precondition automations, maintenance and
  remote activity are in Settings › Vehicles. The app lock can wait 1, 5 or
  15 minutes before locking again (iOS locks as soon as the app leaves the
  screen, Android's "Immediately"). The command log keeps a year and at most
  500 commands, and closes one left "sending" by a process that died; iOS
  keeps every row. Other apps can send commands only once the owner allows
  it, since any app could send the intent, and Unlock from a shortcut or
  automation needs the phone unlocked. On a watch with no screen lock, the
  watch tile's Unlock opens the watch app to confirm instead of unlocking
  the car in one tap.

  iOS features not ported yet:
  - Screens: rearranging Home's sections, the ABRP route planner, the
    ownership hub's multi-car overview and its "Find nearby charging
    stations" link, the test-car editor, troubleshooting, the debug export
    and the Setup Guide.
  - Widgets and live progress: the Hero Card widget, and climate's live
    progress (Android's ongoing notification covers charging only).
  - ABRP's battery-level telemetry.
  - Vehicle settings: the powertrain override, the charge-port type and the
    widget background controls.
  - Appearance: the dashboard preset, car size and accent colour.
  - Privacy: the ownership summary export and "Delete history and
    ownership data".

  The verification-code (MFA) prompt isn't needed: Hyundai USA's sign-in
  never asks for a code.

- **`wear/`**: the Wear OS app (Wear Compose Material 3, wallpaper colours or
  the Rivian look), a port of `GhostBlueWatch Watch App`. The watch has its own
  database and talks to the vehicle service itself, as iOS's watch app does.
  - `ui/`: a page per car, swiped sideways, with the charge, range and when it
    last updated, refresh, the car's render, and lock, climate and (when
    plugged in) charging buttons that send, wait for the car and stop waiting
    on a second tap; settings for kilometres, the Rivian look and each car's
    colour; sign-in on the watch (the test account in one tap, or a Hyundai
    account typed on the watch); help.
  - `tile/`: a tile for the car last shown, with its charge, range and state,
    lock and climate buttons that show their progress, and refresh.
  - `complication/`: iOS's battery-and-range complication as a ranged value
    (a ring on faces that draw one), short text or long text; tapping it
    opens that car.
  - `handoff/`: receives the phone's accounts, even with the watch app closed,
    and signs in the new ones. Known accounts take the phone's password and
    PIN; nothing on the watch is deleted because the phone dropped it. An
    account signed out on the watch stays out until it's signed in there
    again, and one that fails isn't tried again until its password or PIN
    changes, since every try is a real sign-in.

  Where the watch differs from iOS: iOS's watch reads the iPhone's accounts
  and gets its units, look and car colours over WatchConnectivity. Android's
  watch can also sign in by itself, and keeps its own units, look and colours.
  The Data Layer carries the accounts' passwords and PINs; it's encrypted
  between paired devices and only reaches an app with the same package name
  and signing key, and the watch stores them as the phone does. Neither app's
  data goes into Android's backups or device-to-device transfers; GhostBlue's
  own backup file is the way to move to another phone.

Not ported yet: Kia USA, Hyundai Canada and Europe, and with them MFA (Hyundai
USA never asks for a verification code). The factory reports these as not yet
supported; unlike iOS, which falls back to the test car when it can't make a
client, Android says so.

Where the data layer differs from iOS:

- A VIN is unique in the database. iOS needs `StoreReconciler` to merge the
  duplicates iCloud creates; Room enforces it instead, so that isn't ported.
- `AccountController` addresses cars by VIN and reads the current row at every
  step, rather than holding a model object, so a change made elsewhere (a
  screen, the test car's server) is never overwritten by a stale copy.
- Alerts go to `VehicleAlerts.sink`; the app installs its notifier there.

## Build and test

Use Android Studio's bundled JDK. Gradle downloads everything else, including
the Android 17 (API 37) platform that Material 3 1.5 needs to compile against.

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
```

```bash
./gradlew :betterbluekit:test :data:testDebugUnitTest :app:testDebugUnitTest :wear:testDebugUnitTest :app:assembleDebug :wear:assembleDebug
```

The `data` tests run Room under Robolectric, so they need no emulator. So do
the `app` tests: Compose UI tests of the main flows against the test car
(signing in, lock and climate, each tab, units, the Rivian theme, a manual
charging session, trips, privacy, backups, HTTP logs, the app lock, charging
prices and the budget, remote activity, maintenance), shortcuts and the
automation intent (who may send, the car and preset, Unlock waiting for the
phone), the two-column layouts
at a Pixel Fold's inner-screen size, the screens' ported rules, and phase
4's workers, schedules, tiles and widget content. They
include ports of the iOS suites (EnergySessionAccuracy, ChargingReliability,
SmartChargingAutomation, WakeUpReduction, BackupArchive, BackupTransfer,
VehicleArchive, OwnershipFeature) with the smart-charging waits on virtual time.
The `wear` tests run the watch app the same way on a round watch screen
(signing in with the test account, lock, settings) and check the tile and
complication content, including the tile's Unlock on a watch with no screen
lock.

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

`GhostBlue_Wear_Round` (Wear OS 6, round) runs the watch app. With a phone
also connected, give adb the emulator's serial (`-s emulator-5554`, or
`ANDROID_SERIAL` for Gradle). To add the tile and a complication without
tapping through the watch:

```bash
adb shell am broadcast -a com.google.android.wearable.app.DEBUG_SURFACE --es operation add-tile --ecn component com.saivenkatkrishna.ghostblue/com.saivenkatkrishna.ghostblue.wear.tile.VehicleTileService
```

```bash
adb shell am broadcast -a com.google.android.wearable.app.DEBUG_SURFACE --es operation set-complication --ecn component com.saivenkatkrishna.ghostblue/com.saivenkatkrishna.ghostblue.wear.complication.VehicleComplicationService --ecn watchface com.google.android.wearable.watchface.rwf/com.google.android.wearable.watchface.athletica.AthleticaWatchFaceService --ei slot 0 --ei type 5
```

(Type 5 is the ranged value, 3 short text, 4 long text. The default face has
no slots switched on; `--es operation set-watchface --ecn component …` picks
another.) The phone-to-watch handoff needs the two paired, which takes a
phone image with the Play Store and the Pixel Watch app; these emulators
don't have that, so it's covered by the `data` tests only.

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
