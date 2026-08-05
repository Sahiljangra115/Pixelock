# Pixelock

A strict, good looking focus app for Android 13 and up. Sideload only.

- Blocks Reels, Shorts, TikTok and Facebook Reels inside their apps, while the rest of each app keeps working.
- Focus timers with presets, a custom length up to 12 hours, and slide to start. Ending one early takes a password and a wait. A pause is one tap, 5 minutes, 3 per timer.
- 3 Bags, each an app set with a daily limit or a time window.
- 3 weekly Routines.
- Strict Mode, notification shade lock, and Day, Month, Year stats with towers of blocks.
- Friend groups: invite from your contacts, by email, QR, code or link, and compare daily totals on a leaderboard. Only totals are shared. Contacts stay on your phone.
- Pay by UPI to open one app for 15 minutes, or to end a Strict Mode timer.
- Laptop time from ActivityWatch, added to your group total by scanning a code.
- Calls, SMS, email, maps, travel and emergency apps are never blocked.

Forked from [curbox](https://github.com/curbox-app/curbox-android). Licensed under GPL 3.0, see `LICENSE`.

## Build

```bash
./gradlew testDebugUnitTest assembleRelease
```

`local.properties` needs `sdk.dir`. The release APK lands in `app/build/outputs/apk/release/`.

## Install on a phone

1. Copy the APK over and open it.
2. Android 13 and up treats sideloaded apps as restricted. If the Accessibility switch is grey, open App info, tap the three dots menu, and pick Allow restricted settings.
3. Follow the setup list inside the app.

## Online features

Debug builds with no server use a fake one, so everything can be tried without an account. For the real thing:

```bash
tools/setup_online.sh
```

It walks through the Supabase project, the database, the functions, Razorpay (you can skip this), the release build and the APK upload that invite links point to. Re-run it any time. It remembers what you typed in `.setup.env`.

## Laptop time

Needs [ActivityWatch](https://activitywatch.net) running. Open Friends, then Laptop time, on the phone for your key.

```bash
python tools/pixelock_laptop.py --pair KEY   # once
python tools/pixelock_laptop.py              # each day, then scan the code
```

`pip install qrcode` gets you a QR. Without it, type the printed code into the phone.

## Check a build is safe

```bash
tools/safety_check.sh
```

Needs an emulator or phone on adb. See `agents.md` for the rules it protects.
