# Pixelock agent guide

Pixelock is a sideloaded Android focus app: short video blocking, focus timers, 3 Bags, Routines, Strict Mode, friend totals, paid unlocks. Forked from curbox (GPL 3.0, so this app is GPL 3.0). Spec: `~/Developer/.scratch/pixelock/PRD.md`.

## Start here

- Kotlin, classic Views, ViewBinding, Fragments. No Compose, no Navigation component.
- minSdk 33, targetSdk 35, JVM 17. One module `:app`, one flavor, debug and release.
- Package root: `app/src/main/java/dev/pixelock/app`.
- Build checks: `./gradlew testDebugUnitTest`, plus `assembleRelease lintDebug` when touching manifest, gradle or service wiring. `tools/safety_check.sh` runs the device safety gate on an emulator or phone.
- With the uninstall lock on, `adb uninstall` fails. `adb install -r` still works. Turn the lock off on the developer screen first, or use the kill switch.

## The rules that keep phones usable

Every one of these exists because breaking it can trap someone's phone. Do not weaken them.

1. `domain/decide()` is the single place blocking rules live. It has no Android imports. Everything that blocks calls it. Always allowed apps are checked first.
2. The block screen always has a way out. Nothing draws a window the user cannot leave.
3. Strict Mode is tied to one timer and one boot (`StrictRules.isEnforcing`), and timed on the boot clock so the date cannot move it. When the timer ends or the phone restarts, nothing enforces it. All strict state goes through `StrictModeController`. It cannot be turned off while any timer runs, and turning it on during a timer holds that timer.
   The uninstall lock is the one thing outside that rule: the user asked for it to stay on across timers and restarts. It stays safe only because its exits work: the row on the developer screen, the kill switch, the crash breaker, and safe mode. It leaves removal pages only (`isRemovalPage`), so setup, Wi Fi, SIM and emergency pages stay open. Never widen it to every page that shows the app's name.
4. Timers are capped by `MAX_SESSION_MIN`, and pauses add at most `PAUSE_MIN * MAX_PAUSES`. `decide()` reads the clock, so an end alarm that never fires cannot extend a block.
5. Crashes in the blocker process open no screen. `CrashGuard` trips after 3 crashes in 2 minutes, and the service then does nothing until the user clears it. Only the main process may open the crash screen.
6. Never block or bounce during a call (`isInCall`), and the shade lock leaves the lock screen alone.
7. No device owner, no Shizuku, no secure settings, no suspending packages. Device admin holds no policies.
8. Nothing unlocks except `PaymentFlow`, and only after the backend reports the order paid. The payment page lives in this app and back always leaves it. Keep UPI apps in `AlwaysAllow`, or a payment cannot finish during a timer.
9. A person ends a timer only through `SessionManager.endEarly`: end password, then a confirm that waits. A password never beats Strict Mode. The password can change only between timers, so a forgotten one costs at most one timer.
10. A pause lifts only the timer's app block, ends by itself, and is limited per timer. Short video and routines stay on. Strict stays on, so a pause never opens the uninstall path.
11. The one emergency exit is `SessionManager.killEverything`, on the developer screen. It must keep working during Strict Mode, or a stuck phone has no way out. It also ends the uninstall lock.
12. Banned apps go through `decide()`, after always allowed apps, so a ban can never close the dialer or a system page. `InstallGuard` leaves with Home, because back would land on the same search page and bounce in a loop.

## Service rules

- `AppBlockerService` must never crash because one feature fails. Feature calls stay inside `guarded`. In workers, rethrow `CancellationException`.
- Never walk node trees in `onAccessibilityEvent`. Node searches run on the worker, using the system search. The one exception is the capped walk in `visibleTexts`, used by `StrictGuard` and `InstallGuard`, because Compose pages answer no system search. Only package names cross to the worker, so no event is copied.
- Never trust `rootInActiveWindow` during a window change: it can still be the last app. Check its package before acting on it.
- Content and scroll events are switched on only while a watched app is in front (`setWideEvents`). Everywhere else the service hears window changes only.
- The one timer in the blocker is the usage tick, and it runs only while a distraction app is open.
- Since Android 14 the system hides sensitive views, and any view with `filterTouchesWhenObscured`, from this service. That covers the installer's Install button and the device admin rows. `uiautomator` still shows them, so a dump can mislead you: test with the running service. Never set `isAccessibilityTool` to get around this: this app is not one, and Play Protect blocks apps that claim it. Match the window class (`removalClasses`) or text that stays visible.
- No foreground service, not even during a timer. The system keeps an accessibility service bound without one. A foreground service adds a Stop button to Active apps (hidden only while device admin is active), and Stop leaves the service marked crashed: nothing blocks until Accessibility is toggled, the app updates, or the phone restarts. `safety_check.sh background` guards this.

## Processes

| Process | Runs |
| --- | --- |
| main | UI |
| `:blocker` | `AppBlockerService` |
| `:crash_handler` | `CrashLogActivity` |

The main process must be free to die when the app closes, so `:blocker` must not bind to anything in it. That is why Room has no multi instance invalidation: a screen reads fresh rows when it starts, not when `:blocker` writes.

Room only through `AppDatabase.getInstance()`. Settings only through `DataStoreManager`. Do not assume anything initialized in main also ran in `:blocker`. Settings changes reach the service through the settings flow in `PolicyStore`.

## Online

- All network goes through `data/remote/Backend`, in the main process only. No network in `:blocker`.
- `FakeBackend` answers debug builds with no `SUPABASE_URL`. Release builds compile it out. Check with `strings` on the dex after touching `Backends`.
- `SUPABASE_URL` and `SUPABASE_ANON_KEY` come from `local.properties` or env. Razorpay keys and the service key stay in Supabase secrets, never in the APK.
- The contract for what leaves the phone is `DayUpload`: date and two numbers. Nothing goes up outside a group, and leaving the last group deletes it from the server.
- Deep links only ask. Tapping a link never joins a group.
- Invites go out through the phone's own mail and message apps. The server sends no mail. `ContactPickerActivity` is the only reader of contacts, they stay in memory there, and nothing from it reaches `Backend`.
- Send `sb_` keys in the `apikey` header only, never as a bearer (`SupabaseBackend.send`, `_shared/keys.ts`).
- `tools/setup_online.sh` walks through the whole server setup.
- Laptop: `tools/pixelock_laptop.py` runs at login, counts per app minutes, and calls `push_laptop_day`. The phone links it with a one time code (`create_pair_code`, `redeem_pair_code`), so phone and laptop share one owner id. App names go to `laptop_days.apps`, readable only by the owner. `board()` returns phone, laptop and total, never app names. The old QR code path (`LaptopToken`) still works but is not the main route, and using both double counts.

## Data

- `data/models/Settings.kt` is JSON through Gson. Every field needs a default. Never rename a field. Do not read it per event.
- Room has no destructive fallback. Any entity change needs a version bump, a `Migration`, and a test in `androidTest`. Schemas are exported to `app/schemas`.
- Stats screens read `DayTotal` only.
- Per app IDs and package lists live only in `hardcoded/`, and `DataTablesTest` rejects bad entries.

## UI and copy

- Look is cloned from the Blockit screens: grey page, white cards, brown grey ink, orange accent, blocky digits. Colors are in `values/colors.xml` and `values-night/colors.xml`.
- Pixel art is drawn as rects. No allocation in `onDraw`.
- User text goes in `strings.xml`, at a sixth grade level, with no hyphens or dashes.
- The restart exit, the uninstall lock, the install block and the developer screen are never mentioned outside the developer screen.
- Rows on list screens sit in `addGroup` panels. Rows that open a page use `addPageRow`.
- Values that save to disk use `commitOnRelease`, and `onScrub` keeps their labels live. Never save per mark.
