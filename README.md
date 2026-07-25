# HomeApp

Private, two-user Android family organizer with a shared calendar, tasks, simple lists, notes, comments, reminders, change history, archive, search, and cloud-ready synchronization.

## Current status

This repository contains a buildable native Android application, a Supabase schema, device-activation function, tests, CI, product documentation, and the approved design exports. The app starts in **demo/local mode** when Supabase variables are empty and remains fully navigable with seeded data. Cloud configuration activates the REST/realtime integration path.

## Stack

- Kotlin + Jetpack Compose + Material 3
- Android Gradle Plugin 9.3 / Gradle 9.5
- SQLite local cache through `SQLiteOpenHelper`
- WorkManager and AlarmManager for synchronization and offline reminders
- Android Keystore AES-GCM protection for the local PIN hash
- Supabase/Postgres backend with RLS, revisions, read receipts, comments, notifications, backups, and realtime-ready tables

## Build

```bash
gradle test assembleDebug
```

Use Gradle 9.5.0 (or open the project in current Android Studio). The APK is written to `app/build/outputs/apk/debug/app-debug.apk`.

### Cloud configuration

Create `~/.gradle/gradle.properties` or pass Gradle properties:

```properties
SUPABASE_URL=https://YOUR_PROJECT.supabase.co
SUPABASE_ANON_KEY=YOUR_ANON_KEY
```

Apply the backend:

```bash
supabase db push
supabase functions deploy activation --no-verify-jwt
```

Enable realtime for `items`, `comments`, `read_receipts`, and `notifications` as described in the migration.

## Product behavior implemented

- Daily combined timeline, all-day section, unscheduled/overdue section, new/read notes, completed items
- Day/week/month/year calendar modes
- Events, tasks, lists, notes, tags, assignees, participants, comments, links, archive and completion
- Note read receipts and automatic read after five seconds in the detail view
- Quick creation with Hebrew/English rule-based natural-language parsing and preview
- Read-only offline mode with complete local cache
- PIN-only local lock using Android Keystore
- Local reminders that continue without internet; ten-minute snooze action
- Custom recurrence engine, field-aware merge conflict resolver, and revision-ready backend
- Hebrew/English locale declaration with RTL support
- Search including archived content

## Important production setup

1. Replace demo activation behavior by configuring Supabase and deploying the activation function.
2. Create the two Auth users and profile rows, then store hashed activation/recovery codes.
3. Configure an FCM provider for server-originated push updates; local item reminders already work without it.
4. Add a release signing keystore only in local/CI secrets—never commit it.
5. Run device tests for exact alarms, notification permission, time-zone transitions, RTL, process death, and reinstall/device replacement.

See [`docs/architecture.md`](docs/architecture.md) and [`docs/implementation-status.md`](docs/implementation-status.md).
