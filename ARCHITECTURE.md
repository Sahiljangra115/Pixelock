# Architecture: Pixelock

Pixelock is a focus and digital wellbeing application for Android 13 and higher (sideloaded). It enforces strict distraction limits, intercepts short-form video reels inside major social apps, provides emergency unlock mechanisms, and synchronizes aggregate accountability totals with friends.

## System Topology

```
┌────────────────────────────────────────────────────────┐
│                      Android OS                        │
│   ┌──────────────────────┐    ┌────────────────────┐   │
│   │ Accessibility Service│    │ Device Admin API   │   │
│   │ (AppBlockerService)  │    │ (AdminReceiver)    │   │
│   └──────────┬───────────┘    └────────┬───────────┘   │
└──────────────┼─────────────────────────┼───────────────┘
               │                         │
┌──────────────▼─────────────────────────▼───────────────┐
│                   Pixelock Runtime                     │
│                                                        │
│  ┌──────────────────────────────────────────────────┐  │
│  │ Blockers & Enforcement                           │  │
│  │  • AppGate (app-level package blocking)          │  │
│  │  • ReelGate (short video feed interception)      │  │
│  │  • ShadeLock (notification shade lock)           │  │
│  │  • StrictGuard (blocks settings tampering)       │  │
│  └──────────────────────────┬───────────────────────┘  │
│                             │                          │
│  ┌──────────────────────────▼───────────────────────┐  │
│  │ State Machine & Domain Logic                     │  │
│  │  • SessionManager (active timer state)           │  │
│  │  • StrictModeController (strict rules)           │  │
│  │  • Decide (pure evaluation engine)               │  │
│  │  • PolicyStore (bags, routines, time windows)    │  │
│  └──────────────────────────┬───────────────────────┘  │
│                             │                          │
│  ┌──────────────────────────▼───────────────────────┐  │
│  │ Persistence Layer                                │  │
│  │  • Room AppDatabase (AppUsage, DayTotal)         │  │
│  │  • DataStoreManager (preferences)                │  │
│  └──────────────────────────┬───────────────────────┘  │
│                             │                          │
│  ┌──────────────────────────▼───────────────────────┐  │
│  │ Remote Sync & Payments                           │  │
│  │  • SupabaseBackend / FakeBackend                 │  │
│  │  • GroupSync (friend totals sync)                │  │
│  │  • PaymentFlow (UPI unlock via Razorpay)         │  │
│  └──────────────────────────────────────────────────┘  │
└────────────────────────────────────────────────────────┘
```

## Key Subsystems

### 1. Enforcement Subsystem
- **Accessibility Service (`AppBlockerService`)**: Intercepts foreground window state and scroll events. Runs in isolated process `:blocker`.
- **ReelGate**: Detects active short-form video UI nodes (Reels, Shorts, TikTok) and redirects back to neutral views while keeping primary app functions intact.
- **StrictGuard & ShadeLock**: Prevents pulling down the quick settings shade or uninstalling/stopping the service while Strict Mode is active.
- **AdminReceiver**: Device administrator hook preventing uninstalls during active lock sessions.

### 2. State & Policy Management
- **Decide (`Decide.kt`)**: Pure functional evaluation engine that takes current timestamp, active session, bags, and app package to determine allow or block actions.
- **PolicyStore**: Persists bags (app groups with time allowances) and routines (scheduled recurring blocks).
- **SessionManager**: Coordinates timer countdowns, pause budgets (up to 3 pauses of 5 minutes), and early termination passwords.

### 3. Data & Storage
- **Room (`AppDatabase`)**: Stores local daily usage counts and aggregate totals. Migrations 1->2 tested and verified.
- **DataStoreManager**: Fast non-blocking key-value store for app settings, onboard flags, and strict mode toggles.

### 4. Group Sync & Backend
- **Supabase Edge Functions**: Serves friend group invites, cryptographic challenge verification, and Razorpay webhook processing.
- **Privacy Design**: Only aggregate daily focus minutes are shared across friend groups. Raw app lists and usage records never leave the local device.
- **Laptop Integration (`pixelock_laptop.py`)**: ActivityWatch integration that pairs via QR code and cryptographically passes desktop focus time to the phone ledger.
