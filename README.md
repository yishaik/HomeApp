# HomeApp

Private, two-user Android family organizer with a shared calendar, tasks, simple lists, notes, comments, reminders, change history, archive, search, and cloud synchronization.

## Current status

The repository contains a buildable native Android app, a Supabase schema, a device-activation Edge Function, tests, CI, and design exports. The app starts in **demo/local mode** when the Supabase build properties are empty and stays fully navigable with seeded sample data. Supplying the Supabase properties activates the REST + realtime sync path.

## Stack

- Kotlin + Jetpack Compose + Material 3
- Android Gradle Plugin 9.x / Gradle 9.5, `compileSdk`/`targetSdk` 37, `minSdk` 26
- Local cache: SQLite via `SQLiteOpenHelper` (`LocalDatabase`), items stored as JSON payloads
- Networking: OkHttp (REST + WebSocket realtime) against Supabase
- Background work: WorkManager (periodic sync) + AlarmManager (exact offline reminders)
- Security: Android Keystore AES-GCM for the encrypted session and the local PIN hash
- Backend: Supabase / Postgres with Row Level Security, revision history, comments, read receipts, notifications, and realtime-enabled tables

## Build

```bash
gradle test assembleDebug
```

Use Gradle 9.5 (or open the project in current Android Studio). The debug APK is written to `app/build/outputs/apk/debug/app-debug.apk`.

### Cloud configuration

Pass the Supabase properties on the Gradle command line or add them to `~/.gradle/gradle.properties`. **The property names must match `app/build.gradle.kts` exactly:**

```properties
SUPABASE_URL=https://YOUR_PROJECT.supabase.co
SUPABASE_PUBLISHABLE_KEY=YOUR_PUBLISHABLE_KEY
```

Or on the command line:

```bash
gradle test assembleDebug \
  -PSUPABASE_URL=https://YOUR_PROJECT.supabase.co \
  -PSUPABASE_PUBLISHABLE_KEY=YOUR_PUBLISHABLE_KEY
```

These are compiled into `BuildConfig.SUPABASE_URL` / `BuildConfig.SUPABASE_PUBLISHABLE_KEY`. If either is empty, `SupabaseApi.configured` is `false` and the app runs entirely offline against the local cache with seeded demo data.

### Demo / local mode

When the Supabase properties are empty:

- No network calls are made; `SupabaseApi.configured` is `false`.
- On first launch the repository seeds sample items (`SampleData`) into the local SQLite cache.
- All navigation, creation, editing, checklists, comments, and reminders work locally and persist across restarts.
- The activation screen is skipped only once a real session exists; without Supabase the activation call cannot succeed, so demo mode is intended for local development and UI review.

### Apply the backend

```bash
supabase db push
supabase functions deploy activation --no-verify-jwt
```

See [`docs/SETUP.md`](docs/SETUP.md) for the full setup, and enable realtime for `homeapp_items`, `homeapp_comments`, `homeapp_read_receipts`, and `homeapp_notifications` (migration `0002` does this automatically).

## How activation works

The app is gated to two known users per household via **activation codes**, not open sign-up:

1. The user enters a numeric activation code (and, on a new device, an optional recovery code).
2. The app POSTs to the `activation` Edge Function (`/functions/v1/activation`) with the code and a device fingerprint (`Settings.Secure.ANDROID_ID`).
3. The function hashes the code (SHA-256), looks up the matching `homeapp_activation_slots` row, lazily creates the Auth user + `homeapp_profiles` row on first use, registers the device, and returns a **magic-link `tokenHash`** plus the profile details.
4. The app exchanges that `tokenHash` at `/auth/v1/verify` (`type=email`) for an access/refresh token pair — the real session.
5. The session is encrypted with an Android Keystore AES-GCM key and stored via `SessionStore`.

If a **different** device already holds the slot, the function returns *approval required* (HTTP 409). The app then polls `action=status`, and the already-activated device approves the new one (`action=approve`) before it can complete. The recovery code lets a user re-claim a slot on a new device without an approval round-trip. `household_id` is carried in the JWT `app_metadata` and is what RLS keys off.

## Product behavior implemented

- Bottom navigation: Today, Calendar, Tasks, Lists, Notes, Search
- Today timeline with sections (new notes, all-day, unscheduled/overdue, timeline, completed, read notes)
- Day/week/month/year calendar modes with Israeli/Hebrew calendar layers
- Item types: EVENT, TASK, LIST, NOTE — with tags, assignee/participants, comments, links, checklists, archive and completion
- Note read receipts (auto-read in the detail view)
- Quick-add sheet with rule-based Hebrew/English natural-language parsing (`NaturalLanguageParser`) that guesses type/date/time
- Local-first offline cache; edits persist and are pushed on the next sync
- PIN lock (4–8 digits) using an Android Keystore-protected hash, with configurable auto-lock timeout
- Local exact-time reminders via AlarmManager that fire without internet, with a 10-minute snooze action
- Derived notifications for changes by the other user (badge count, mark-all-read)
- Custom recurrence engine (`RecurrenceEngine`) and a field-aware conflict resolver (`ConflictResolver`)
- Forced RTL with Hebrew UI strings and a declared Hebrew/English locale config
- Search across active and archived content

## Production checklist

1. Configure Supabase and deploy the `activation` function; supply the two build properties.
2. Create the two `homeapp_activation_slots` rows with hashed activation/recovery codes (migration `0002` seeds two example slots — replace the hashes).
3. Optionally deploy the `fridge-display-api` function for a read-only kitchen/fridge agenda display.
4. FCM push is **not** wired; server-originated push updates would need an FCM provider. Local reminders already work without it.
5. Add a release signing keystore only via local/CI secrets — never commit it.
6. Test on-device: exact alarms, notification permission, time-zone transitions, RTL, process death, reinstall / device replacement.

See [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md), [`docs/DATABASE.md`](docs/DATABASE.md), and [`docs/SETUP.md`](docs/SETUP.md).
</content>
</invoke>
