# Pixelock

Pixelock is a focus app for Android 13 and newer. It blocks short video feeds and runs focus timers that you cannot end on impulse. Install it by sideloading the APK.

> **Status:** The app builds and passes its unit and lint checks. The online features (friend groups, invites, payments) have not run against a live Supabase or Razorpay account yet.

## Features

- **Blocks short video.** Reels, Shorts, TikTok and Facebook Reels stop inside their apps. The rest of each app keeps working.
- **Focus timers.** Pick a preset or set a length up to 12 hours. Slide to start.
- **Ending early takes a password and a wait.** A pause lasts 5 minutes. You get 3 pauses per timer.
- **Bags.** Group apps into up to 3 bags. Give each bag a daily limit or a time window.
- **Routines.** Set up to 3 weekly routines.
- **Strict Mode.** A strict timer holds even when you know the password. A phone restart ends Strict Mode.
- **Notification shade lock.** Locks the notification shade. Its switch is in Settings.
- **Stats.** See Day, Month and Year views, drawn as towers of blocks.
- **Friend groups.** Invite friends from your contacts, by email, QR code, code or link. Compare daily totals on a leaderboard. Only totals leave the phone. Contacts stay on the phone.
- **Paid unlocks.** Pay by UPI to open one app for 15 minutes, or to end a Strict Mode timer.
- **Laptop time.** Add focus time from [ActivityWatch](https://activitywatch.net) to your group total.
- **Always allowed.** Calls, SMS, email, maps, travel and emergency apps are never blocked.

## Requirements

- Android 13 (API 33) or newer.
- Permission to install apps from outside the Play Store.
- To build from source: JDK 17 and the Android SDK.

## Install

1. Build the release APK (see [Build](#build)) and copy it to the phone.
2. Open the APK and allow the install.
3. If the Accessibility switch is grey, open App info, tap the three dots menu, and choose **Allow restricted settings**. Android 13 and newer restrict sideloaded apps this way.
4. Follow the setup list inside the app.

## Build

```bash
./gradlew testDebugUnitTest assembleRelease
```

Add `sdk.dir` to `local.properties` first. The build writes the release APK to `app/build/outputs/apk/release/`.

## Online features

Debug builds without a server use a fake backend. Everything runs without an account. Payments in that mode succeed without money.

To use the real backend, run:

```bash
tools/setup_online.sh
```

The script walks you through each step:

- Create the Supabase project and database.
- Deploy the functions.
- Configure Razorpay (you can skip this).
- Build the release APK and upload it, so invite links can point to it.

Run the script again at any time. It saves your answers in `.setup.env`.

## Laptop time

Laptop time needs [ActivityWatch](https://activitywatch.net) running on your laptop. On the phone, open **Friends**, then **Laptop time**, to get your key.

```bash
python tools/pixelock_laptop.py --pair KEY   # once
python tools/pixelock_laptop.py              # each day, then scan the code
```

Install `qrcode` with `pip install qrcode` to show the code as a QR image. Without it, type the printed code into the phone.

## Device safety check

```bash
tools/safety_check.sh
```

Run this against an emulator or a spare test phone connected over adb.

> **Warning:** The script changes system settings, reboots the device and uninstalls the app. It refuses real phones unless you set `ALLOW_REAL_PHONE=yes`. Do not set it on a phone you use.

## Project docs

- [`agents.md`](agents.md): the rules that keep phones usable. Read these before you change blocking, timers or services.
- [`ARCHITECTURE.md`](ARCHITECTURE.md): how the parts fit together.
- [`PROJECT_STATUS.md`](PROJECT_STATUS.md): the current status and open work.

## License

Pixelock uses the GNU General Public License v3.0. See [`LICENSE`](LICENSE).

Pixelock forks [curbox](https://github.com/curbox-app/curbox-android), which uses the same license.
