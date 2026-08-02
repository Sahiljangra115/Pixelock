# Project Progress: Pixelock

## Completed Milestones

┌──────────────────┬────────────────────────────────────────────────────────────┬─────────────┐
│ Milestone        │ Summary of Work                                            │ Status      │
├──────────────────┼────────────────────────────────────────────────────────────┼─────────────┤
│ Core Foundation  │ Gradle build setup, dependencies, base manifest, branding  │ Completed   │
│ Domain Engine    │ Pure decision logic, session policies, strict rules tests  │ Completed   │
│ Database & Room  │ AppUsageEntity, DayTotalEntity, DAO queries, v1->v2 schema │ Completed   │
│ Accessibility    │ AppBlockerService, ReelGate short-video intercept, Shade   │ Completed   │
│ Session Control  │ Timers, Bags, Routines, Pause flow, early exit passwords   │ Completed   │
│ UI & Dashboard   │ Board tabs, Timer Fragment, Stats with towers, Charts      │ Completed   │
│ Friend Groups    │ Supabase backend integration, group sync, invite flows     │ Completed   │
│ Emergency Unlock │ UPI Razorpay payment flow, Laptop token ActivityWatch CLI  │ Completed   │
│ System Stability │ ServiceWatchdogJob, OEM autostart intents, safety checks   │ Completed   │
│ Setup Wizard     │ SetupFragment checklist, permission grants, restricted fix │ Completed   │
└──────────────────┴────────────────────────────────────────────────────────────┴─────────────┘

## Recent Changes (November 2026)
- SetupFragment onboarding guidance updated for Android 13+ restricted settings.
- OEM autostart settings intent support added for MIUI, ColorOS, Oplus, Vivo, and iQOO.
- Safety check suite expanded with active background process checks (`group_background`).
- Removed redundant foreground service flags to prevent persistent notification bar clutter.
- ServiceHealth battery optimization deep links updated to handle already-whitelisted states.

## Upcoming Milestones
- Further battery optimization profiling on Android 15 developer preview.
- Localized language packs for regional focus prompts.
- Additional desktop client integrations beyond ActivityWatch.
