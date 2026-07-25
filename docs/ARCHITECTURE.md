# Architecture

HomeApp is a local-first Android app that syncs a small shared dataset for a two-user household to Supabase/Postgres. It works fully offline and treats the cloud as a replica to merge with, not a source of truth that overwrites local edits.

## Layers

```
UI (Jetpack Compose, Material 3)
        │  observes StateFlow
HomeRepository  ── single source of truth for the UI
   │        │            │
LocalDatabase   SupabaseApi   SessionStore / PinVault
 (SQLite)       (OkHttp REST   (Android Keystore
                + WebSocket)    AES-GCM)
```

- **UI / Compose** — `ui/HomeAppRoot.kt` is the root. It chooses between `ActivationScreen`, `PinSetupScreen`, `PinUnlockScreen`, and the main app (bottom nav: Today, Calendar, Tasks, Lists, Notes, Search) plus overlay screens (item detail, settings, notifications). Screens observe `HomeRepository` flows with `collectAsStateWithLifecycle`.
- **Repository** — `data/HomeRepository.kt` owns all app state as `StateFlow`s (`items`, `online`, `currentUser`, `users`, `syncing`, `lastSyncError`, `notifications`, `unreadCount`). Every mutation goes through it.
- **Local DB** — `data/LocalDatabase.kt` (`SQLiteOpenHelper`, db `homeapp.db`, v1). Two tables: `items(id, payload, updated_at, archived)` where `payload` is the JSON-encoded `HomeItem`, and `metadata(key, value)` used for the pending-push set, preferences, and the notifications-seen timestamp. `data/JsonCodec.kt` encodes/decodes a `HomeItem` to/from JSON.
- **Sync** — `sync/SupabaseApi.kt` talks to Supabase over OkHttp: `/functions/v1/activation`, `/auth/v1/verify`, `/auth/v1/token` (refresh), REST `/rest/v1/homeapp_items` and `/rest/v1/homeapp_profiles`, and a `/realtime/v1/websocket` subscription. `sync/ConflictResolver.kt` provides a field-aware three-way merge helper.
- **Security** — `security/SessionStore.kt` stores the auth session encrypted with an Android Keystore AES-GCM key; `security/PinVault.kt` stores a Keystore-encrypted SHA-256 PIN hash and enforces the auto-lock timeout; `security/ActivationManager.kt` drives the activation/approval flow.
- **Notifications / background** — `notifications/` holds the WorkManager `SyncWorker` (periodic sync), the AlarmManager `ReminderScheduler`/`ReminderReceiver` (exact offline reminders + snooze), `NotificationChannels`, and `BootReceiver` (reschedule after boot / app update).

`HomeApplication` wires everything and enqueues a 15-minute periodic `SyncWorker` constrained to a connected network.

## Local-first sync model

Writes never wait on the network:

1. **`save(item)`** stamps `updatedAt = now`, bumps `revision`, fills in `householdId`/`creatorId` from the session, upserts to SQLite, marks the item **pending** (in `metadata["pending_push"]`, a comma-joined id set persisted across restarts), and updates the in-memory `items` flow immediately. It then *attempts* a background push; on success it clears the pending flag, on failure it leaves the item pending and records `lastSyncError`.
2. **`pushPending()`** retries every id still in the pending set on the next opportunity, clearing each on success.
3. **`syncNow()`** (triggered on connectivity regain, on activation, by realtime messages, and by the periodic worker) fetches remote profiles and items, then **merges** rather than clobbers:

   ```
   merged = remoteItems.filter { it.id !in pending } + pending.mapNotNull { localById[it] }
   ```

   Remote wins for everything not locally pending; locally pending items keep their unsynced version so a later push can still deliver them. This is what stops sync from resurrecting a locally-deleted item or overwriting an in-flight local edit. After merging, `syncNow()` calls `pushPending()`.

A `Mutex` serializes syncs. Realtime is a WebSocket subscription to `homeapp_items` changes filtered by `household_id`; any change message just triggers another `syncNow()`.

### Delete = soft-delete

Hard `DELETE` is disabled server-side by RLS (`homeapp_items_no_delete`). `delete(id)` instead saves the item with `status = CANCELLED` through the normal upsert path and hides `CANCELLED` items everywhere. This survives a sync across devices, unlike a hard delete the server would reject.

### Sessions and refresh

Before any authenticated call, `validSession()` returns the current session, or refreshes it via `/auth/v1/token?grant_type=refresh_token` when it expires within 120 seconds, persisting the refreshed session.

## Data model

Defined in `domain/Models.kt`. The central entity is `HomeItem`:

- `type: ItemType` — `EVENT | TASK | LIST | NOTE`
- `status: ItemStatus` — `ACTIVE | COMPLETED | CANCELLED | ARCHIVED`
- `editPolicy: EditPolicy` — `SHARED_EDIT | CREATOR_ONLY`
- `assignee: Assignee` — `NONE | USER_ONE | USER_TWO | BOTH`; plus `participantIds`, `tags`
- Scheduling: `startAt`/`endAt`/`allDay` (events), `dueAt` (tasks/lists), `scheduledPublishAt` (notes)
- Nested collections: `reminders: List<Reminder>`, `checklist: List<ChecklistEntry>`, `comments: List<Comment>`, `readReceipts: List<ReadReceipt>`, `linkedItems: List<LinkedItem>`
- `location`/`mapsUrl`, `recurrence: RecurrenceRule?`, `pinned`, `notifyOtherUser`
- `revision`, `createdAt`, `updatedAt`

Helpers on `HomeItem`: `dateIn(zone)` (which timestamp places it on a calendar day, by type), `isOverdue(now)` (active task/list past its `dueAt`), `isVisibleTo(userId, now)` (scheduled notes hidden from the other user until publish time).

Other domain types: `UserPreferences` (locale, accent, start destination, PIN timeout, today sections, default reminders, important tags, calendar layers — persisted as JSON by `PreferencesStore`), `RecurrenceRule` (daily/weekly/monthly/yearly with interval, days-of-week, ordinal, until), `AppNotification` (derived, not stored), and `ParsedQuickAdd` (natural-language parse result).

### Edit policy

`SHARED_EDIT` items can be edited by either household member; `CREATOR_ONLY` items only by their creator. This is enforced both in the UI and by the RLS `UPDATE` policy on `homeapp_items`.

### Comments and read receipts

Comments are carried inside the item payload and merged by id (union, dedup, sorted by time) during conflict resolution. Read receipts track per-user read state; opening a note in the detail view marks it read for the current user.

### Revision history (server-side)

Every payload-changing `UPDATE` on `homeapp_items` fires a `BEFORE UPDATE` trigger that inserts the prior payload into `homeapp_item_revisions` and refreshes `updated_at`. Because the trigger runs as the caller (`SECURITY INVOKER`), `homeapp_item_revisions` needs its own `INSERT` policy (`homeapp_revisions_insert`, `with check actor_id = auth.uid()`) — without it every edit would fail RLS with a 403. `homeapp_restore_item_revision(item_id, revision)` restores a past payload. See [`DATABASE.md`](DATABASE.md).

## Notifications (in-app)

`HomeRepository.notifications` is **derived** from the item list, not fetched: it surfaces items updated by the *other* user, completions by the other user, and new comments from the other user. An item counts as unread when its relevant timestamp is after the current user's `notifications_seen_at` marker (in `metadata`). `markAllNotificationsRead()` advances that marker.

## Reminders

`ReminderScheduler.schedule(item)` sets an exact `AlarmManager` alarm (`setExactAndAllowWhileIdle`, falling back to `setAndAllowWhileIdle`) for each enabled reminder at `target − minutesBefore`, where target is the item's `startAt` or `dueAt`. `ReminderReceiver` posts a high-importance notification (if `POST_NOTIFICATIONS` is granted) with **Open** and **Snooze 10 min** actions. `BootReceiver` re-enqueues a sync/reschedule on boot and package replacement. These work with no network.
</content>
