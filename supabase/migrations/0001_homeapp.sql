-- HomeApp schema for a private two-user household.
-- Apply with `supabase db push` or paste into the Supabase SQL editor.
create extension if not exists pgcrypto;

create type public.item_type as enum ('EVENT','TASK','LIST','NOTE');
create type public.item_status as enum ('ACTIVE','COMPLETED','CANCELLED','ARCHIVED');
create type public.edit_policy as enum ('SHARED_EDIT','CREATOR_ONLY');

create table public.households (
  id uuid primary key default gen_random_uuid(),
  name text not null default 'Home',
  created_at timestamptz not null default now()
);

create table public.profiles (
  id uuid primary key references auth.users(id) on delete cascade,
  household_id uuid not null references public.households(id) on delete cascade,
  display_name text not null,
  avatar text not null default '',
  accent_argb bigint not null default 4284111831,
  created_at timestamptz not null default now(),
  unique (household_id, display_name)
);

create table public.activation_codes (
  id uuid primary key default gen_random_uuid(),
  household_id uuid not null references public.households(id) on delete cascade,
  profile_id uuid not null references public.profiles(id) on delete cascade,
  code_hash text not null,
  recovery_code_hash text not null,
  active boolean not null default true,
  created_at timestamptz not null default now()
);

create table public.devices (
  id uuid primary key default gen_random_uuid(),
  profile_id uuid not null references public.profiles(id) on delete cascade,
  fingerprint_hash text not null,
  push_token text,
  approved_at timestamptz,
  revoked_at timestamptz,
  last_seen_at timestamptz not null default now(),
  unique(profile_id)
);

create table public.items (
  id uuid primary key,
  household_id uuid not null references public.households(id) on delete cascade,
  creator_id uuid not null references public.profiles(id),
  type public.item_type not null,
  status public.item_status not null default 'ACTIVE',
  edit_policy public.edit_policy not null default 'SHARED_EDIT',
  payload jsonb not null,
  revision bigint not null default 0,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now()
);
create index items_household_updated_idx on public.items(household_id, updated_at desc);
create index items_payload_gin_idx on public.items using gin(payload);

create table public.item_revisions (
  id bigserial primary key,
  item_id uuid not null references public.items(id) on delete cascade,
  revision bigint not null,
  actor_id uuid not null references public.profiles(id),
  payload jsonb not null,
  changed_fields jsonb not null default '{}'::jsonb,
  created_at timestamptz not null default now(),
  unique(item_id, revision)
);

create table public.comments (
  id uuid primary key default gen_random_uuid(),
  item_id uuid not null references public.items(id) on delete cascade,
  author_id uuid not null references public.profiles(id),
  body text not null,
  link text,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now()
);

create table public.read_receipts (
  item_id uuid not null references public.items(id) on delete cascade,
  profile_id uuid not null references public.profiles(id) on delete cascade,
  read_at timestamptz,
  primary key(item_id, profile_id)
);

create table public.item_links (
  item_a uuid not null references public.items(id) on delete cascade,
  item_b uuid not null references public.items(id) on delete cascade,
  created_at timestamptz not null default now(),
  primary key(item_a, item_b),
  check(item_a < item_b)
);

create table public.notifications (
  id uuid primary key default gen_random_uuid(),
  household_id uuid not null references public.households(id) on delete cascade,
  recipient_id uuid not null references public.profiles(id) on delete cascade,
  item_id uuid references public.items(id) on delete cascade,
  title text not null,
  detail text not null,
  read_at timestamptz,
  collapse_key text,
  created_at timestamptz not null default now()
);

create table public.user_preferences (
  profile_id uuid primary key references public.profiles(id) on delete cascade,
  preferences jsonb not null default '{}'::jsonb,
  updated_at timestamptz not null default now()
);

create table public.backup_snapshots (
  id uuid primary key default gen_random_uuid(),
  household_id uuid not null references public.households(id) on delete cascade,
  payload jsonb not null,
  created_at timestamptz not null default now()
);
create index backup_snapshots_retention_idx on public.backup_snapshots(household_id, created_at desc);

create or replace function public.current_household_id()
returns uuid language sql stable security definer set search_path = public as $$
  select household_id from public.profiles where id = auth.uid()
$$;

alter table public.households enable row level security;
alter table public.profiles enable row level security;
alter table public.devices enable row level security;
alter table public.items enable row level security;
alter table public.item_revisions enable row level security;
alter table public.comments enable row level security;
alter table public.read_receipts enable row level security;
alter table public.item_links enable row level security;
alter table public.notifications enable row level security;
alter table public.user_preferences enable row level security;
alter table public.backup_snapshots enable row level security;

create policy household_members_read_household on public.households for select using (id = public.current_household_id());
create policy household_members_read_profiles on public.profiles for select using (household_id = public.current_household_id());
create policy household_members_update_profiles on public.profiles for update using (household_id = public.current_household_id());
create policy own_device_access on public.devices for all using (profile_id = auth.uid()) with check (profile_id = auth.uid());
create policy household_items_read on public.items for select using (household_id = public.current_household_id());
create policy household_items_insert on public.items for insert with check (household_id = public.current_household_id() and creator_id = auth.uid());
create policy household_items_update on public.items for update using (
  household_id = public.current_household_id() and
  ((edit_policy = 'SHARED_EDIT') or creator_id = auth.uid())
);
create policy household_items_delete_disabled on public.items for delete using (false);
create policy household_revisions_read on public.item_revisions for select using (
  exists(select 1 from public.items i where i.id = item_id and i.household_id = public.current_household_id())
);
create policy household_comments_all on public.comments for all using (
  exists(select 1 from public.items i where i.id = item_id and i.household_id = public.current_household_id())
) with check (
  exists(select 1 from public.items i where i.id = item_id and i.household_id = public.current_household_id())
);
create policy household_receipts_all on public.read_receipts for all using (
  exists(select 1 from public.items i where i.id = item_id and i.household_id = public.current_household_id())
) with check (
  exists(select 1 from public.items i where i.id = item_id and i.household_id = public.current_household_id())
);
create policy household_links_all on public.item_links for all using (
  exists(select 1 from public.items i where i.id = item_a and i.household_id = public.current_household_id())
) with check (
  exists(select 1 from public.items i where i.id = item_a and i.household_id = public.current_household_id())
);
create policy own_notifications on public.notifications for all using (recipient_id = auth.uid()) with check (recipient_id = auth.uid());
create policy own_preferences on public.user_preferences for all using (profile_id = auth.uid()) with check (profile_id = auth.uid());
create policy household_backups_read on public.backup_snapshots for select using (household_id = public.current_household_id());

create or replace function public.capture_item_revision()
returns trigger language plpgsql security definer set search_path = public as $$
begin
  if tg_op = 'UPDATE' and old.payload is distinct from new.payload then
    insert into public.item_revisions(item_id, revision, actor_id, payload, changed_fields)
    values(new.id, new.revision, auth.uid(), new.payload, jsonb_build_object('previous_revision', old.revision));
  end if;
  new.updated_at := now();
  return new;
end $$;
create trigger items_capture_revision before update on public.items for each row execute function public.capture_item_revision();

create or replace function public.restore_item_revision(p_item_id uuid, p_revision bigint)
returns public.items language plpgsql security definer set search_path = public as $$
declare restored jsonb; result public.items;
begin
  select payload into restored from public.item_revisions where item_id = p_item_id and revision = p_revision;
  if restored is null then raise exception 'Revision not found'; end if;
  update public.items set payload = restored, revision = revision + 1 where id = p_item_id returning * into result;
  return result;
end $$;

create or replace function public.cleanup_old_backups()
returns void language sql security definer set search_path = public as $$
  delete from public.backup_snapshots where created_at < now() - interval '30 days'
$$;

-- Realtime must be enabled after migration:
-- alter publication supabase_realtime add table public.items, public.comments, public.read_receipts, public.notifications;
