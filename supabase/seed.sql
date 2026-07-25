-- Run after creating two Auth users in the Supabase dashboard.
-- Replace the UUID placeholders before execution.
insert into public.households(id, name) values ('00000000-0000-0000-0000-000000000001', 'Kaminsky Home') on conflict do nothing;
-- insert into public.profiles(id, household_id, display_name, avatar, accent_argb) values
-- ('USER_ONE_AUTH_UUID', '00000000-0000-0000-0000-000000000001', 'ישי', 'י', 4284111831),
-- ('USER_TWO_AUTH_UUID', '00000000-0000-0000-0000-000000000001', 'מעיין', 'מ', 4292897435);
-- Store only SHA-256 hashes of activation and recovery codes.
