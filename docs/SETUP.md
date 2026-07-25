# Setup

End-to-end setup: Supabase backend, activation slots, and the Android build.

## Prerequisites

- JDK 17
- Gradle 9.5 (or Android Studio with a matching bundled Gradle)
- Android SDK (`compileSdk`/`targetSdk` 37, `minSdk` 26)
- [Supabase CLI](https://supabase.com/docs/guides/cli) and a Supabase project (for cloud mode)
- Deno / Supabase Functions runtime for the Edge Functions

## 1. Supabase project

1. Create a Supabase project and link the CLI:

   ```bash
   supabase link --project-ref YOUR_PROJECT_REF
   ```

2. Note two values from **Project Settings → API**:
   - Project URL → `SUPABASE_URL`
   - Publishable (anon) key → `SUPABASE_PUBLISHABLE_KEY`
   - Secret / service-role key → used only by the Edge Function, never shipped in the app.

## 2. Apply migrations

The production schema is `supabase/migrations/0002_production_homeapp.sql`
(prefixed `homeapp_*`). Apply migrations:

```bash
supabase db push
```

This creates the schema, RLS policies, the revision-capture trigger, realtime
publication entries, and seeds one household plus two example activation slots.
(`0001_homeapp.sql` is a legacy unprefixed schema the client does not use.)

## 3. Deploy Edge Functions

```bash
supabase functions deploy activation --no-verify-jwt
# optional read-only kitchen/fridge agenda display:
supabase functions deploy fridge-display-api --no-verify-jwt
```

Set the function secrets (server-side only):

```bash
supabase secrets set \
  SUPABASE_URL=https://YOUR_PROJECT.supabase.co \
  SUPABASE_SECRET_KEYS='["YOUR_SECRET_KEY"]' \
  SUPABASE_PUBLISHABLE_KEYS='["YOUR_PUBLISHABLE_KEY"]'
# for the fridge display, also:
# FRIDGE_DISPLAY_KEYS='["SOME_LONG_RANDOM_TOKEN"]'
```

The activation function accepts a request only if the caller's `apikey` header
is in `SUPABASE_PUBLISHABLE_KEYS` (a legacy `SUPABASE_ANON_KEY` is also
accepted). It reads the locked-down `homeapp_activation_slots` table with the
secret key and issues magic-link token hashes.

## 4. Create activation slots

Migration `0002` seeds two slots with **example** hashes — replace them. Each
slot stores only SHA-256 hashes of the activation code and recovery code.
Generate hashes (hex, lowercase) and update the rows:

```bash
printf '123456' | sha256sum   # -> code_hash
printf 'RECOVER01' | sha256sum # -> recovery_code_hash
```

```sql
update public.homeapp_activation_slots
set code_hash = 'HEX_HASH_OF_CODE',
    recovery_code_hash = 'HEX_HASH_OF_RECOVERY_CODE'
where slot_name = 'user_one';
```

Give each user their (unhashed) activation code out of band. The Auth user and
`homeapp_profiles` row are created lazily on first successful activation.

## 5. Enable / verify realtime

Migration `0002` adds `homeapp_items`, `homeapp_comments`,
`homeapp_read_receipts`, and `homeapp_notifications` to the
`supabase_realtime` publication automatically. Confirm under **Database →
Replication** if needed.

## 6. Build the APK

Local / demo mode (no cloud — seeded sample data, fully offline):

```bash
gradle test assembleDebug
```

Cloud mode — pass the Supabase properties (names must match
`app/build.gradle.kts` exactly):

```bash
gradle test assembleDebug \
  -PSUPABASE_URL=https://YOUR_PROJECT.supabase.co \
  -PSUPABASE_PUBLISHABLE_KEY=YOUR_PUBLISHABLE_KEY
```

Or persist them in `~/.gradle/gradle.properties`:

```properties
SUPABASE_URL=https://YOUR_PROJECT.supabase.co
SUPABASE_PUBLISHABLE_KEY=YOUR_PUBLISHABLE_KEY
```

Output: `app/build/outputs/apk/debug/app-debug.apk`. Install with
`adb install -r app/build/outputs/apk/debug/app-debug.apk`.

> Release builds enable minify + resource shrinking and require a signing
> keystore. Provide it via local/CI secrets only — never commit it.

## 7. First run

1. Launch the app → **Activation** screen.
2. Enter the activation code (new device → recovery code, or approve from the already-activated device).
3. Set a 4–8 digit PIN.
4. The app syncs and is ready.

## CI (`.github/workflows/android.yml`)

On push to `main` / `feature/**` and PRs to `main`, GitHub Actions:

- checks out, sets up Temurin JDK 17 and Gradle 9.5, installs the Android SDK;
- runs `gradle --no-daemon test assembleDebug`;
- uploads the debug APK (`HomeApp-debug-apk`) and test reports as artifacts.

CI builds **without** the Supabase properties, so it exercises the demo/local
path. To produce a cloud-configured APK from CI, add `SUPABASE_URL` /
`SUPABASE_PUBLISHABLE_KEY` as repository secrets and pass them as `-P` Gradle
properties in the build step.
</content>
