-- Production HomeApp schema. Tables are prefixed so this can coexist safely
-- with other applications in the same Supabase project.
create extension if not exists pgcrypto;
create schema if not exists homeapp_private;
revoke all on schema homeapp_private from public, anon, authenticated;

create type public.homeapp_item_type as enum ('EVENT','TASK','LIST','NOTE');
create type public.homeapp_item_status as enum ('ACTIVE','COMPLETED','CANCELLED','ARCHIVED');
create type public.homeapp_edit_policy as enum ('SHARED_EDIT','CREATOR_ONLY');
create type public.homeapp_device_request_status as enum ('PENDING','APPROVED','REJECTED','EXPIRED');

create table public.homeapp_households (
  id uuid primary key default gen_random_uuid(),
  name text not null,
  created_at timestamptz not null default now()
);

create table public.homeapp_profiles (
  id uuid primary key references auth.users(id) on delete cascade,
  household_id uuid not null references public.homeapp_households(id) on delete cascade,
  display_name text not null,
  avatar text not null default '',
  accent_argb bigint not null default 4284111831,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  unique (household_id, display_name)
);
create index homeapp_profiles_household_idx on public.homeapp_profiles(household_id);

-- This table is intentionally inaccessible through the Data API. Only the
-- activation Edge Function, using a secret key, may read it.
create table public.homeapp_activation_slots (
  id uuid primary key default gen_random_uuid(),
  household_id uuid not null references public.homeapp_households(id) on delete cascade,
  slot_name text not null,
  email text not null unique,
  display_name text not null,
  avatar text not null,
  accent_argb bigint not null,
  code_hash text not null unique,
  recovery_code_hash text not null,
  auth_user_id uuid unique references auth.users(id) on delete set null,
  active boolean not null default true,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now()
);
revoke all on public.homeapp_activation_slots from anon, authenticated;

create table public.homeapp_devices (
  id uuid primary key default gen_random_uuid(),
  profile_id uuid not null unique references public.homeapp_profiles(id) on delete cascade,
  fingerprint_hash text not null,
  push_token text,
  approved_at timestamptz not null default now(),
  revoked_at timestamptz,
  last_seen_at timestamptz not null default now(),
  app_version text
);

create table public.homeapp_device_requests (
  id uuid primary key default gen_random_uuid(),
  slot_id uuid not null references public.homeapp_activation_slots(id) on delete cascade,
  requested_fingerprint_hash text not null,
  status public.homeapp_device_request_status not null default 'PENDING',
  approved_by uuid references public.homeapp_profiles(id),
  created_at timestamptz not null default now(),
  resolved_at timestamptz,
  expires_at timestamptz not null default now() + interval '30 minutes'
);
create index homeapp_device_requests_slot_status_idx on public.homeapp_device_requests(slot_id, status, created_at desc);
revoke all on public.homeapp_device_requests from anon, authenticated;

create table public.homeapp_activation_attempts (
  id bigint generated always as identity primary key,
  fingerprint_hash text not null,
  succeeded boolean not null default false,
  attempted_at timestamptz not null default now()
);
create index homeapp_activation_attempts_fingerprint_idx
  on public.homeapp_activation_attempts(fingerprint_hash, attempted_at desc);
revoke all on public.homeapp_activation_attempts from anon, authenticated;

create table public.homeapp_items (
  id uuid primary key,
  household_id uuid not null references public.homeapp_households(id) on delete cascade,
  creator_id uuid not null references public.homeapp_profiles(id),
  type public.homeapp_item_type not null,
  status public.homeapp_item_status not null default 'ACTIVE',
  edit_policy public.homeapp_edit_policy not null default 'SHARED_EDIT',
  payload jsonb not null,
  revision bigint not null default 0 check (revision >= 0),
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now()
);
create index homeapp_items_household_updated_idx on public.homeapp_items(household_id, updated_at desc);
create index homeapp_items_payload_gin_idx on public.homeapp_items using gin(payload);

create table public.homeapp_item_revisions (
  id bigint generated always as identity primary key,
  item_id uuid not null references public.homeapp_items(id) on delete cascade,
  revision bigint not null,
  actor_id uuid references public.homeapp_profiles(id),
  payload jsonb not null,
  changed_fields jsonb not null default '{}'::jsonb,
  created_at timestamptz not null default now(),
  unique(item_id, revision)
);

create table public.homeapp_comments (
  id uuid primary key default gen_random_uuid(),
  item_id uuid not null references public.homeapp_items(id) on delete cascade,
  author_id uuid not null references public.homeapp_profiles(id),
  body text not null check (length(body) between 1 and 5000),
  link text,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now()
);

create table public.homeapp_read_receipts (
  item_id uuid not null references public.homeapp_items(id) on delete cascade,
  profile_id uuid not null references public.homeapp_profiles(id) on delete cascade,
  read_at timestamptz,
  primary key(item_id, profile_id)
);

create table public.homeapp_item_links (
  item_a uuid not null references public.homeapp_items(id) on delete cascade,
  item_b uuid not null references public.homeapp_items(id) on delete cascade,
  created_at timestamptz not null default now(),
  primary key(item_a, item_b),
  check(item_a < item_b)
);

create table public.homeapp_notifications (
  id uuid primary key default gen_random_uuid(),
  household_id uuid not null references public.homeapp_households(id) on delete cascade,
  recipient_id uuid not null references public.homeapp_profiles(id) on delete cascade,
  item_id uuid references public.homeapp_items(id) on delete cascade,
  title text not null,
  detail text not null,
  read_at timestamptz,
  collapse_key text,
  created_at timestamptz not null default now()
);
create index homeapp_notifications_recipient_idx on public.homeapp_notifications(recipient_id, created_at desc);

create table public.homeapp_user_preferences (
  profile_id uuid primary key references public.homeapp_profiles(id) on delete cascade,
  preferences jsonb not null default '{}'::jsonb,
  updated_at timestamptz not null default now()
);

create table public.homeapp_backup_snapshots (
  id uuid primary key default gen_random_uuid(),
  household_id uuid not null references public.homeapp_households(id) on delete cascade,
  payload jsonb not null,
  created_at timestamptz not null default now()
);
create index homeapp_backup_snapshots_retention_idx on public.homeapp_backup_snapshots(household_id, created_at desc);

-- RLS uses app_metadata, which only trusted server code can mutate.
alter table public.homeapp_households enable row level security;
alter table public.homeapp_profiles enable row level security;
alter table public.homeapp_devices enable row level security;
alter table public.homeapp_items enable row level security;
alter table public.homeapp_item_revisions enable row level security;
alter table public.homeapp_comments enable row level security;
alter table public.homeapp_read_receipts enable row level security;
alter table public.homeapp_item_links enable row level security;
alter table public.homeapp_notifications enable row level security;
alter table public.homeapp_user_preferences enable row level security;
alter table public.homeapp_backup_snapshots enable row level security;

create policy homeapp_household_select on public.homeapp_households
for select to authenticated
using (id = ((select auth.jwt())->'app_metadata'->>'household_id')::uuid);

create policy homeapp_profiles_select on public.homeapp_profiles
for select to authenticated
using (household_id = ((select auth.jwt())->'app_metadata'->>'household_id')::uuid);
create policy homeapp_profile_update_self on public.homeapp_profiles
for update to authenticated
using ((select auth.uid()) = id)
with check ((select auth.uid()) = id and household_id = ((select auth.jwt())->'app_metadata'->>'household_id')::uuid);

create policy homeapp_device_self_all on public.homeapp_devices
for all to authenticated
using ((select auth.uid()) = profile_id)
with check ((select auth.uid()) = profile_id);

create policy homeapp_items_select on public.homeapp_items
for select to authenticated
using (household_id = ((select auth.jwt())->'app_metadata'->>'household_id')::uuid);
create policy homeapp_items_insert on public.homeapp_items
for insert to authenticated
with check (
  household_id = ((select auth.jwt())->'app_metadata'->>'household_id')::uuid
  and creator_id = (select auth.uid())
);
create policy homeapp_items_update on public.homeapp_items
for update to authenticated
using (
  household_id = ((select auth.jwt())->'app_metadata'->>'household_id')::uuid
  and (edit_policy = 'SHARED_EDIT' or creator_id = (select auth.uid()))
)
with check (
  household_id = ((select auth.jwt())->'app_metadata'->>'household_id')::uuid
  and (edit_policy = 'SHARED_EDIT' or creator_id = (select auth.uid()))
);
-- Normal removal is archival; permanent client-side deletion is intentionally disabled.
create policy homeapp_items_no_delete on public.homeapp_items
for delete to authenticated using (false);

create policy homeapp_revisions_select on public.homeapp_item_revisions
for select to authenticated
using (exists (
  select 1 from public.homeapp_items i
  where i.id = item_id
    and i.household_id = ((select auth.jwt())->'app_metadata'->>'household_id')::uuid
));

-- The BEFORE UPDATE trigger capture_item_revision() runs as the caller (SECURITY INVOKER) and
-- inserts a history row here, so authenticated callers need an INSERT policy — without it every
-- payload-changing UPDATE fails with "new row violates row-level security policy" (403).
create policy homeapp_revisions_insert on public.homeapp_item_revisions
for insert to authenticated
with check (actor_id = (select auth.uid()));

create policy homeapp_comments_select on public.homeapp_comments
for select to authenticated
using (exists (
  select 1 from public.homeapp_items i
  where i.id = item_id
    and i.household_id = ((select auth.jwt())->'app_metadata'->>'household_id')::uuid
));
create policy homeapp_comments_insert on public.homeapp_comments
for insert to authenticated
with check (
  author_id = (select auth.uid()) and exists (
    select 1 from public.homeapp_items i
    where i.id = item_id
      and i.household_id = ((select auth.jwt())->'app_metadata'->>'household_id')::uuid
  )
);
create policy homeapp_comments_update on public.homeapp_comments
for update to authenticated
using (exists (
  select 1 from public.homeapp_items i
  where i.id = item_id
    and i.household_id = ((select auth.jwt())->'app_metadata'->>'household_id')::uuid
))
with check (exists (
  select 1 from public.homeapp_items i
  where i.id = item_id
    and i.household_id = ((select auth.jwt())->'app_metadata'->>'household_id')::uuid
));
create policy homeapp_comments_delete on public.homeapp_comments
for delete to authenticated
using (exists (
  select 1 from public.homeapp_items i
  where i.id = item_id
    and i.household_id = ((select auth.jwt())->'app_metadata'->>'household_id')::uuid
));

create policy homeapp_receipts_all on public.homeapp_read_receipts
for all to authenticated
using (exists (
  select 1 from public.homeapp_items i
  where i.id = item_id
    and i.household_id = ((select auth.jwt())->'app_metadata'->>'household_id')::uuid
))
with check (profile_id = (select auth.uid()) and exists (
  select 1 from public.homeapp_items i
  where i.id = item_id
    and i.household_id = ((select auth.jwt())->'app_metadata'->>'household_id')::uuid
));

create policy homeapp_links_all on public.homeapp_item_links
for all to authenticated
using (exists (
  select 1 from public.homeapp_items i
  where i.id = item_a
    and i.household_id = ((select auth.jwt())->'app_metadata'->>'household_id')::uuid
))
with check (exists (
  select 1 from public.homeapp_items i
  where i.id = item_a
    and i.household_id = ((select auth.jwt())->'app_metadata'->>'household_id')::uuid
));

create policy homeapp_notifications_self on public.homeapp_notifications
for all to authenticated
using (recipient_id = (select auth.uid()))
with check (recipient_id = (select auth.uid()));

create policy homeapp_preferences_self on public.homeapp_user_preferences
for all to authenticated
using (profile_id = (select auth.uid()))
with check (profile_id = (select auth.uid()));

create policy homeapp_backups_select on public.homeapp_backup_snapshots
for select to authenticated
using (household_id = ((select auth.jwt())->'app_metadata'->>'household_id')::uuid);

create or replace function homeapp_private.capture_item_revision()
returns trigger
language plpgsql
security invoker
set search_path = public, pg_temp
as $$
begin
  if old.payload is distinct from new.payload then
    insert into public.homeapp_item_revisions(item_id, revision, actor_id, payload, changed_fields)
    values(old.id, old.revision, auth.uid(), old.payload,
      jsonb_build_object('restored_from_revision', old.revision));
  end if;
  new.updated_at := now();
  return new;
end
$$;

create trigger homeapp_items_capture_revision
before update on public.homeapp_items
for each row execute function homeapp_private.capture_item_revision();

create or replace function public.homeapp_restore_item_revision(p_item_id uuid, p_revision bigint)
returns public.homeapp_items
language plpgsql
security invoker
set search_path = public, pg_temp
as $$
declare
  restored jsonb;
  result public.homeapp_items;
begin
  select r.payload into restored
  from public.homeapp_item_revisions r
  join public.homeapp_items i on i.id = r.item_id
  where r.item_id = p_item_id
    and r.revision = p_revision
    and i.household_id = ((select auth.jwt())->'app_metadata'->>'household_id')::uuid;

  if restored is null then
    raise exception 'Revision not found or not accessible';
  end if;

  update public.homeapp_items
  set payload = restored, revision = revision + 1
  where id = p_item_id
  returning * into result;
  return result;
end
$$;
revoke all on function public.homeapp_restore_item_revision(uuid, bigint) from public, anon;
grant execute on function public.homeapp_restore_item_revision(uuid, bigint) to authenticated;

create or replace function homeapp_private.cleanup_old_backups()
returns void
language sql
security invoker
set search_path = public, pg_temp
as $$
  delete from public.homeapp_backup_snapshots
  where created_at < now() - interval '30 days'
$$;

-- Explicit Data API grants are required for new projects created after May 2026.
grant usage on schema public to authenticated;
grant select on public.homeapp_households to authenticated;
grant select, update on public.homeapp_profiles to authenticated;
grant select, insert, update on public.homeapp_devices to authenticated;
grant select, insert, update on public.homeapp_items to authenticated;
grant select on public.homeapp_item_revisions to authenticated;
grant select, insert, update, delete on public.homeapp_comments to authenticated;
grant select, insert, update, delete on public.homeapp_read_receipts to authenticated;
grant select, insert, delete on public.homeapp_item_links to authenticated;
grant select, update, delete on public.homeapp_notifications to authenticated;
grant select, insert, update on public.homeapp_user_preferences to authenticated;
grant select on public.homeapp_backup_snapshots to authenticated;
grant usage, select on all sequences in schema public to authenticated;

-- Seed exactly one private household and two activation slots. Only hashes are stored.
insert into public.homeapp_households(id, name)
values ('a7b5b901-af73-492a-83e6-a8bebee532dc', 'הבית שלנו');

insert into public.homeapp_activation_slots(
  id, household_id, slot_name, email, display_name, avatar, accent_argb,
  code_hash, recovery_code_hash
) values
(
  'de5431e6-313a-4212-8efa-f79bbcfb9110',
  'a7b5b901-af73-492a-83e6-a8bebee532dc',
  'user_one', 'homeapp-yishai@local.invalid', 'ישי', 'י', 4284111831,
  '19e334d55da0153866b3f051f38c6f6de1ce25e4d74505e7b9b41ea58eb626c3',
  'd57a68f01e9cc90ed4390b68a81939756c3a35e8f01909c432501df4f34181ff'
),
(
  '1ade25cc-8d10-47c7-85ab-2dfdb413e411',
  'a7b5b901-af73-492a-83e6-a8bebee532dc',
  'user_two', 'homeapp-maayan@local.invalid', 'מעיין', 'מ', 4292897435,
  'e9d250257d106447f519977d81cdb0a205e8c76c51e93a645d1fba2b84a686bf',
  'f0bb47002ccd1464694b3858c353b53986900cc2c3ba9f9734e77a405affac07'
);

-- Add selected tables to Realtime only once.
do $$
begin
  if not exists (select 1 from pg_publication_tables where pubname='supabase_realtime' and schemaname='public' and tablename='homeapp_items') then
    alter publication supabase_realtime add table public.homeapp_items;
  end if;
  if not exists (select 1 from pg_publication_tables where pubname='supabase_realtime' and schemaname='public' and tablename='homeapp_comments') then
    alter publication supabase_realtime add table public.homeapp_comments;
  end if;
  if not exists (select 1 from pg_publication_tables where pubname='supabase_realtime' and schemaname='public' and tablename='homeapp_read_receipts') then
    alter publication supabase_realtime add table public.homeapp_read_receipts;
  end if;
  if not exists (select 1 from pg_publication_tables where pubname='supabase_realtime' and schemaname='public' and tablename='homeapp_notifications') then
    alter publication supabase_realtime add table public.homeapp_notifications;
  end if;
end $$;
