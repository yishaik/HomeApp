# Database

The production backend is a Supabase/Postgres schema in migration
`supabase/migrations/0002_production_homeapp.sql`. All tables are prefixed
`homeapp_*` so the app can share a Supabase project with other apps. This is the
schema the Android client actually uses (`SupabaseApi` reads
`/rest/v1/homeapp_items` and `/rest/v1/homeapp_profiles`).

> Note: `0001_homeapp.sql` is an earlier, **unprefixed** schema (`items`,
> `profiles`, …) kept for history. The client does not use it — treat `0002` as
> authoritative.

## Tables

| Table | Purpose |
|---|---|
| `homeapp_households` | One row per household. |
| `homeapp_profiles` | One row per user (PK = `auth.users.id`), with `display_name`, `avatar`, `accent_argb`, `household_id`. |
| `homeapp_activation_slots` | Pre-provisioned user slots: `email`, display info, and **hashed** `code_hash` / `recovery_code_hash`. Locked down — only the activation Edge Function (secret key) can read it; `anon`/`authenticated` are revoked. |
| `homeapp_device_requests` | Pending new-device approvals for a slot (`PENDING/APPROVED/REJECTED/EXPIRED`, 30-min expiry). Revoked from clients. |
| `homeapp_activation_attempts` | Rate-limiting log of activation attempts by fingerprint hash. Revoked from clients. |
| `homeapp_devices` | One device per profile (`fingerprint_hash`, optional `push_token`, `app_version`, approve/revoke timestamps). |
| `homeapp_items` | The core data. Columns: `id`, `household_id`, `creator_id`, `type`, `status`, `edit_policy`, `payload jsonb`, `revision`, `created_at`, `updated_at`. Indexed on `(household_id, updated_at desc)` and a GIN index on `payload`. |
| `homeapp_item_revisions` | Append-only history of prior item payloads (see trigger below). |
| `homeapp_comments` | Comments per item (`body` length-checked 1–5000). |
| `homeapp_read_receipts` | Per `(item_id, profile_id)` read state. |
| `homeapp_item_links` | Undirected links between two items (`check(item_a < item_b)`). |
| `homeapp_notifications` | Server-side notifications per recipient (`title`, `detail`, `read_at`, `collapse_key`). |
| `homeapp_user_preferences` | Per-profile preferences JSON. |
| `homeapp_backup_snapshots` | Household backup payloads (30-day retention via `cleanup_old_backups()`). |

Enums: `homeapp_item_type` (`EVENT/TASK/LIST/NOTE`), `homeapp_item_status`
(`ACTIVE/COMPLETED/CANCELLED/ARCHIVED`), `homeapp_edit_policy`
(`SHARED_EDIT/CREATOR_ONLY`), `homeapp_device_request_status`.

## Row Level Security

RLS is enabled on every table. Household scoping keys off the JWT
`app_metadata.household_id` claim (set by the activation function, mutable only
by trusted server code):

```sql
household_id = ((select auth.jwt())->'app_metadata'->>'household_id')::uuid
```

Key policies:

- **Households / profiles / items / revisions / backups** — `SELECT` restricted to the caller's household. Profiles: a user may `UPDATE` only their own row.
- **Items INSERT** — allowed only when `household_id` matches and `creator_id = auth.uid()`.
- **Items UPDATE** — allowed when in-household **and** (`edit_policy = 'SHARED_EDIT'` **or** `creator_id = auth.uid()`). This enforces the `CREATOR_ONLY` edit policy at the database.
- **Items DELETE disabled** — policy `homeapp_items_no_delete` is `USING (false)`. **Hard delete is impossible from the client.** Deletion is done as a soft-delete: the client sets `status = CANCELLED` via `UPDATE`. The app hides `CANCELLED` items.
- **Revisions INSERT** — `homeapp_revisions_insert` allows `INSERT` when `actor_id = auth.uid()`. This is required because the revision-capture trigger runs as the caller (see below); without it every item edit would fail RLS.
- **Comments / read receipts / links** — full access scoped to items in the caller's household (receipts additionally require `profile_id = auth.uid()` on write).
- **Notifications / preferences** — each user sees and writes only their own rows (`recipient_id` / `profile_id = auth.uid()`).

Explicit `GRANT`s to `authenticated` are also issued (required for Supabase
projects created after May 2026), matching each table's intended access.

## Revision-history trigger

```sql
create trigger homeapp_items_capture_revision
before update on public.homeapp_items
for each row execute function homeapp_private.capture_item_revision();
```

`homeapp_private.capture_item_revision()` runs `SECURITY INVOKER` (as the
calling user). On any `UPDATE` where `payload` changed, it inserts the **prior**
payload into `homeapp_item_revisions` and refreshes `updated_at`. Because it
runs as the caller, the `homeapp_revisions_insert` RLS policy is what lets that
insert succeed — this pairing is the reason both the trigger and the INSERT
policy must exist together.

`homeapp_restore_item_revision(p_item_id, p_revision)` (SECURITY INVOKER,
granted to `authenticated`) restores a stored payload and bumps `revision`.

## Realtime

Migration `0002` adds `homeapp_items`, `homeapp_comments`,
`homeapp_read_receipts`, and `homeapp_notifications` to the
`supabase_realtime` publication (idempotently). The client subscribes to
`homeapp_items` changes filtered by `household_id`.

## Seed data

The migration seeds one household (`הבית שלנו`) and **two** activation slots
(`ישי`, `מעיין`) storing only SHA-256 hashes of the activation and recovery
codes. Replace these hashes with your own before production — see
[`SETUP.md`](SETUP.md).
</content>
