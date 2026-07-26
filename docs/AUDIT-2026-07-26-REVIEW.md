# HomeApp — Independent Verification Comments

**Reviewed:** 2026-07-26  
**Audit reviewed:** [`docs/AUDIT-2026-07-26.md`](./AUDIT-2026-07-26.md)  
**Current `main`:** `1421f6e61dabf133e8542d939a033b32634d62c9`  
**Application commit audited:** `2ea0b19f22ccc793a3caffe81102e5bcd96e9b7e`

## Verification scope

The only commit after the application commit is the audit-document commit itself, so the application code on current `main` still matches the code reviewed by the audit.

I independently checked the relevant repository, UI, persistence, notification, Realtime, CI, Gradle and migration paths. This review is also static: it did **not** exercise a physical Android device or the live Supabase project. A local Gradle run could not be performed from the review environment, so runtime-dependent claims remain runtime-dependent.

## Overall verdict

The audit is strong and its central conclusion is correct. The highest-risk production defect is the unsynchronised local write/sync path around `HomeRepository.save()`, `pending_push` and `LocalDatabase.replaceAll()`. The offline UI inconsistency and the inert notification/privacy controls are also confirmed.

The roadmap should be retained, but several recommendations need correction before implementation.

## Confirmed findings

The following findings are directly supported by current `main`:

- **N1:** Add, pin, archive/delete menu, reminders and completion are gated by `online`, while title/body edits, comments and checklist mutations are not.
- **N2:** `pending_push` is a comma-delimited metadata value updated with an unsynchronised read-modify-write.
- **N3:** `save()` does not share `syncMutex` with `syncNow()`, while `syncNow()` performs a destructive cache replacement.
- **N4:** `mutate()` and `_items.value` publishing can race on stale snapshots.
- **N5:** `notifyOtherUser` is persisted but not consumed by a notification-delivery path.
- **N6:** `lockScreenContentVisible` is persisted but not applied to notifications.
- **N7:** logout clears item rows and session state, but leaves metadata, PIN state and registered alarms behind.
- **N8:** exact-alarm capability is not checked; exact scheduling failures are silently downgraded.
- **N9 / #31 / #41:** the Realtime client lacks close/failure recovery, parses events by substring and triggers full syncs without debounce.
- **N10 / #18:** revisions are client-assigned, writes are blind upserts and the write response is ignored by `HomeRepository`.
- **N11:** a permanently pending item permanently shadows the remote version without a conflict-resolution state.
- **N13:** selecting English changes directionality without translating the application.
- **N14:** notification taps include `item_id`, but `MainActivity` does not consume it.
- **N15 / N16 / #40:** risky persistence and sync paths lack tests, and CI runs `test assembleDebug` without lint or release verification.
- **N18 / #21:** decode failures are silently omitted before the cache snapshot is replaced.
- **N19:** the SQLite upgrade hook is empty.
- **N20 / N21:** the Today-screen date semantics and Hebrew weekday parsing issues are present as described.
- **N23–N26:** revision retention, dead code, implicit demo builds and repeated alarm re-arming are supported by the code.

## Required corrections and implementation comments

### 1. N27 and #44 are not future-only: the repository is public

The audit repeatedly describes the repository as private and says the personal seed data only matters if the repository becomes public. The repository is currently **public**.

The migration currently commits household/member names, fixed UUIDs and local-invalid email identifiers. These are not authentication secrets, but they are personal identifiers and deployment-specific data. The earlier security commit also states that real activation-code hashes were scrubbed from the **public** migration and that the live codes were rotated.

**Correction:** raise N27 from deferred P2 to an active privacy/security cleanup. At minimum:

1. Replace personal seed identities and fixed production UUIDs with neutral placeholders or a separate untracked provisioning script.
2. Enable/run GitHub secret scanning plus a history scan such as Gitleaks or TruffleHog.
3. Verify that every credential or code ever committed is revoked/rotated.
4. Rewrite Git history only if a still-valid secret or materially sensitive value remains; rotation is normally more important than cosmetic history rewriting.

### 2. N10's proposed fix is incomplete

`SupabaseApi.upsertItem()` receives a row representation but decodes only `result[0].payload`. The database trigger changes the scalar `updated_at` column, not `payload.updatedAt`. Therefore simply returning and adopting `api.upsertItem(...)` would still return the stale timestamp embedded in the payload.

The same problem will apply to a server-owned revision if the scalar revision changes but the payload is not updated.

**Required implementation:** choose one canonical representation:

- Fetch/return scalar columns plus payload, decode the payload, then explicitly overlay server-owned fields such as `updated_at` and `revision`; **or**
- Make the database function/trigger update those fields inside the JSON payload as well.

The first option is clearer: scalar columns own concurrency and timestamps; payload owns domain content.

### 3. Do not hold the sync mutex across network I/O

The audit correctly recommends serialising local mutations and destructive sync operations, but a naive `save() { syncMutex.withLock { ... network request ... } }` would make user writes wait behind slow fetches and pushes.

**Recommended structure:**

1. Use a single repository actor or mutation mutex for the short local critical section.
2. In one SQLite transaction, upsert the item and insert/update its outbox row.
3. Publish the matching in-memory state before leaving the critical section.
4. Perform the network push outside the lock.
5. Re-enter the actor/transaction only to acknowledge success or record failure/conflict.

This gives atomic local durability without serialising the UI behind HTTP latency.

### 4. N2 should use a transactional outbox, not both a table and a separate read-modify-write mutex

A table keyed by item ID eliminates the comma-string race because `INSERT ... ON CONFLICT` and `DELETE` are atomic row operations. A separate mutex is not required merely to maintain the pending set.

What **is** required is atomicity between the item write and its outbox record. They must be committed in the same SQLite transaction. Otherwise a process death between the two statements can reproduce the same class of failure.

Suggested schema fields:

```sql
outbox(
  item_id TEXT PRIMARY KEY,
  operation TEXT NOT NULL,
  base_revision INTEGER,
  attempts INTEGER NOT NULL DEFAULT 0,
  queued_at TEXT NOT NULL,
  last_attempt_at TEXT,
  last_error TEXT,
  state TEXT NOT NULL DEFAULT 'pending'
)
```

### 5. N5's cheap fix does not fulfil the switch's promise

Wiring `notifyOtherUser` into `deriveNotifications()` only changes the partner's in-app derived list after their device receives/syncs the item. It does not proactively notify them in the background and therefore does not fully implement the Hebrew promise "הודע למשתמש השני".

**Recommendation:** remove or relabel the switch until a real cloud event and push/background delivery path exists. If retained as an in-app-only signal, label it explicitly as such.

### 6. N6 needs a data path into `ReminderReceiver`

Applying notification visibility in the receiver is correct, but the receiver currently has no access to the preference value.

Use one of these explicit designs:

- Include a redaction/visibility boolean in the scheduled alarm intent; reschedule alarms when the setting changes.
- Read a lightweight device-level preference directly in the receiver.

Avoid constructing the full repository in a `BroadcastReceiver` merely to read one setting.

### 7. N7 slightly overstates the read-state outcome

An inherited `notifications_seen_at` does not necessarily mark every notification as read. It marks notifications whose event timestamp is at or before the inherited timestamp as read. The isolation defect remains valid and important; the wording should be narrowed.

Also reset the in-memory `PreferencesStore` flow after clearing storage, otherwise the current process may retain the old preferences even after the metadata table is cleared.

### 8. N11 must not resolve failure by silently accepting the remote version

The suggested "after N failures, stop excluding it from remote merges" can overwrite an unsynced local change—the same failure class the outbox is meant to prevent.

After repeated failure, preserve **both** versions:

- Mark the outbox row `failed` or `conflict`.
- Store the latest remote snapshot separately.
- Surface a resolution UI: retry, keep local/force overwrite where authorised, accept remote, or manually merge.

Never discard the local mutation merely because its retry budget expired.

### 9. N19 cannot safely use unconditional drop-and-resync anymore

The database is not purely a cache once it stores durable offline writes and an outbox. Dropping the database during upgrade can delete changes that have never reached Supabase.

A destructive migration is safe only when the outbox is empty and the user has no unsynchronised data. Prefer explicit migrations for the item/outbox tables, or export pending mutations before rebuilding and restore them afterward.

### 10. Move the regression tests before the concurrency refactor

The roadmap admits that N3 needs the tests listed later in Phase 2. Back-filling them after changing every write path is unnecessarily risky.

Recommended Phase 0 order:

1. CI: wrapper-based `test lint assembleDebug assembleRelease`.
2. Characterisation tests for current merge/outbox behaviour and a deterministic N3 interleaving test.
3. SQLite migration/outbox transaction.
4. Repository actor/mutex refactor.
5. Enable offline UI paths.

### 11. Make demo mode explicit rather than only failing missing configuration

N25 is correct. A debug/demo build is useful, but it must not masquerade as a production APK.

Prefer explicit product flavours or build flags:

- `demoDebug`: sample data, visible permanent demo banner, clearly named artifact.
- `productionDebug` / `productionRelease`: fail configuration when Supabase values are missing.

CI should upload an artifact whose name and application label identify its mode.

### 12. N17's deadline wording is off by one day

The finding body correctly says data stops from **2027-01-01**. The heading says the calendar goes blank on **2026-12-31**. The implementation deadline is before 2027-01-01; 2026-12-31 is the final day covered by a 2026-only dataset.

### 13. Prefer the Gradle wrapper in CI

The audit's CI recommendation is directionally correct. Use the repository wrapper rather than a globally installed `gradle` command so local and CI builds use the same pinned distribution:

```bash
./gradlew --no-daemon test lint assembleDebug assembleRelease
```

Also add a separate Supabase/Deno validation job rather than making the Android job responsible for every backend check.

## Revised priority adjustments

| Priority | Change |
|---|---|
| **Immediate security/privacy** | Move N27/history scanning out of deferred #44 because the repository is public. |
| **Before write-path refactor** | Move N15 characterisation/concurrency tests ahead of N2–N4 implementation. |
| **Write-path design** | Implement item + outbox as one SQLite transaction; use an actor/short local lock; keep network outside the lock. |
| **Conflict safety** | Preserve local and remote versions on permanent push failure; never silently drop either. |
| **Cloud canonical fields** | Overlay server-owned revision/timestamps from scalar columns; adopting payload-only responses is insufficient. |
| **Product honesty** | Remove/relabel notify-partner until real delivery exists; make demo builds unmistakable. |

## Final assessment

The audit should be used as the implementation baseline. Its central prioritisation—data integrity first, then honest UI behaviour, then maintenance improvements—is correct.

Before coding, amend the implementation plan with the corrections above. In particular, treat the public-repository exposure as current, design the outbox transactionally, preserve both sides of conflicts, and define scalar server-owned fields as the concurrency source of truth.
