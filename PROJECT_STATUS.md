# Project Status: Pixelock

## Current Status Overview

┌─────────────────────────┬────────────────────────────────────────────────────────┐
│ Metric / Property       │ Current State                                          │
├─────────────────────────┼────────────────────────────────────────────────────────┤
│ Project Name            │ Pixelock                                               │
│ Application Type        │ Android Digital Wellbeing & Strict Blocker             │
│ Target Platforms        │ Android 13 (API 33) to Android 15                      │
│ Distribution Mode       │ Sideload / Direct APK install                          │
│ License                 │ GPL 3.0                                                │
│ Database Schema Version │ v2 (Room migration tested)                             │
│ Cloud Infrastructure    │ Supabase (PostgreSQL, Edge Functions, RLS)             │
│ Payment Integration     │ Razorpay UPI webhooks (optional self-hosted)           │
│ Test Coverage           │ Unit tests pass, migration tests pass, safety check ok │
│ Active Development      │ Continuous maintenance and stability hardening         │
└─────────────────────────┴────────────────────────────────────────────────────────┘

## Known Nuances & Workarounds
- **Android Restricted Settings**: Sideloaded apps on Android 13+ require manual user grant for Accessibility Service ("Allow restricted settings" in App Info).
- **Aggressive OEM Battery Killers**: Xiaomi (MIUI/HyperOS), BBK (Oppo/Vivo/Realme) kill accessibility services in background if autostart permission and battery freedom are not granted. Handled via explicit OEM intents in `SetupFragment` and `OemAutostartIntents`.
- **Active Apps Tray**: Foreground services with ongoing notifications appear in Android 13+ active apps tray where users can tap "Stop". Pixelock avoids holding a constant foreground service when idle to prevent easy bypasses.
