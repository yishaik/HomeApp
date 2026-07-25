import { createClient } from "npm:@supabase/supabase-js@2.110.8";

const corsHeaders = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Headers": "authorization, apikey, content-type, x-client-info",
  "Access-Control-Allow-Methods": "POST, OPTIONS",
  "Cache-Control": "no-store",
};

const json = (body: unknown, status = 200) => new Response(JSON.stringify(body), {
  status,
  headers: { ...corsHeaders, "Content-Type": "application/json; charset=utf-8" },
});

const sha256 = async (value: string) => Array.from(
  new Uint8Array(await crypto.subtle.digest("SHA-256", new TextEncoder().encode(value))),
).map((byte) => byte.toString(16).padStart(2, "0")).join("");

const parseKeySet = (raw: string | undefined): string[] => {
  if (!raw) return [];
  try {
    const parsed = JSON.parse(raw);
    return typeof parsed === "object" && parsed !== null ? Object.values(parsed).filter((v): v is string => typeof v === "string") : [];
  } catch {
    return [raw];
  }
};

const issueSessionToken = async (admin: ReturnType<typeof createClient>, email: string) => {
  const { data, error } = await admin.auth.admin.generateLink({ type: "magiclink", email });
  if (error) throw error;
  const tokenHash = data.properties?.hashed_token;
  if (!tokenHash) throw new Error("Supabase did not return a token hash");
  return tokenHash;
};

Deno.serve(async (request: Request) => {
  if (request.method === "OPTIONS") return new Response("ok", { headers: corsHeaders });
  if (request.method !== "POST") return json({ error: "method_not_allowed" }, 405);

  const url = Deno.env.get("SUPABASE_URL");
  const secretKeys = parseKeySet(Deno.env.get("SUPABASE_SECRET_KEYS"));
  const secretKey = secretKeys[0] ?? Deno.env.get("SUPABASE_SERVICE_ROLE_KEY");
  const publishableKeys = parseKeySet(Deno.env.get("SUPABASE_PUBLISHABLE_KEYS"));
  const legacyAnonKey = Deno.env.get("SUPABASE_ANON_KEY");
  if (legacyAnonKey) publishableKeys.push(legacyAnonKey);

  if (!url || !secretKey) return json({ error: "server_not_configured" }, 500);
  const suppliedApiKey = request.headers.get("apikey") ?? "";
  if (!publishableKeys.includes(suppliedApiKey)) return json({ error: "invalid_client" }, 401);

  const admin = createClient(url, secretKey, {
    auth: { autoRefreshToken: false, persistSession: false },
    global: { headers: { "X-Client-Info": "homeapp-activation/1.0" } },
  });

  try {
    const body = await request.json();
    const action = String(body.action ?? "activate");
    const deviceFingerprint = String(body.deviceFingerprint ?? "");
    const appVersion = String(body.appVersion ?? "unknown").slice(0, 40);

    if (action === "approve") {
      const authorization = request.headers.get("Authorization") ?? "";
      const accessToken = authorization.startsWith("Bearer ") ? authorization.slice(7) : "";
      if (!accessToken) return json({ error: "authentication_required" }, 401);
      const { data: userData, error: userError } = await admin.auth.getUser(accessToken);
      if (userError || !userData.user) return json({ error: "invalid_session" }, 401);

      const requestId = String(body.requestId ?? "");
      const { data: approver } = await admin.from("homeapp_profiles")
        .select("id, household_id").eq("id", userData.user.id).maybeSingle();
      const { data: pending } = await admin.from("homeapp_device_requests")
        .select("id, slot_id, status, expires_at, homeapp_activation_slots!inner(household_id, auth_user_id)")
        .eq("id", requestId).maybeSingle();
      const slot = pending?.homeapp_activation_slots as { household_id: string; auth_user_id: string | null } | undefined;
      if (!approver || !pending || !slot || slot.household_id !== approver.household_id || slot.auth_user_id === approver.id) {
        return json({ error: "request_not_found" }, 404);
      }
      if (pending.status !== "PENDING" || new Date(pending.expires_at).getTime() <= Date.now()) {
        return json({ error: "request_expired" }, 409);
      }
      const { error } = await admin.from("homeapp_device_requests").update({
        status: "APPROVED", approved_by: approver.id, resolved_at: new Date().toISOString(),
      }).eq("id", requestId).eq("status", "PENDING");
      if (error) throw error;
      return json({ approved: true });
    }

    const activationCode = String(body.activationCode ?? "");
    if (!activationCode || !deviceFingerprint) return json({ error: "missing_activation_data" }, 400);
    const fingerprintHash = await sha256(deviceFingerprint);

    const { count: recentFailures } = await admin.from("homeapp_activation_attempts")
      .select("id", { count: "exact", head: true })
      .eq("fingerprint_hash", fingerprintHash)
      .eq("succeeded", false)
      .gte("attempted_at", new Date(Date.now() - 15 * 60_000).toISOString());
    if ((recentFailures ?? 0) >= 10) return json({ error: "too_many_attempts" }, 429);

    const codeHash = await sha256(activationCode);
    const { data: slot, error: slotError } = await admin.from("homeapp_activation_slots")
      .select("id, household_id, email, display_name, avatar, accent_argb, recovery_code_hash, auth_user_id")
      .eq("code_hash", codeHash).eq("active", true).maybeSingle();

    if (slotError || !slot) {
      await admin.from("homeapp_activation_attempts").insert({ fingerprint_hash: fingerprintHash, succeeded: false });
      await new Promise((resolve) => setTimeout(resolve, 650));
      return json({ error: "invalid_activation_code" }, 401);
    }

    let authUserId = slot.auth_user_id as string | null;
    if (!authUserId) {
      const { data: created, error: createError } = await admin.auth.admin.createUser({
        email: slot.email,
        email_confirm: true,
        app_metadata: { household_id: slot.household_id, activation_slot_id: slot.id },
        user_metadata: { display_name: slot.display_name },
      });
      if (createError || !created.user) throw createError ?? new Error("Unable to create user");
      authUserId = created.user.id;
      const { error: profileError } = await admin.from("homeapp_profiles").insert({
        id: authUserId,
        household_id: slot.household_id,
        display_name: slot.display_name,
        avatar: slot.avatar,
        accent_argb: slot.accent_argb,
      });
      if (profileError) throw profileError;
      const { error: slotUpdateError } = await admin.from("homeapp_activation_slots")
        .update({ auth_user_id: authUserId, updated_at: new Date().toISOString() }).eq("id", slot.id);
      if (slotUpdateError) throw slotUpdateError;
    } else {
      await admin.auth.admin.updateUserById(authUserId, {
        app_metadata: { household_id: slot.household_id, activation_slot_id: slot.id },
      });
    }

    const { data: existingDevice } = await admin.from("homeapp_devices")
      .select("fingerprint_hash").eq("profile_id", authUserId).maybeSingle();

    const completeActivation = async () => {
      const now = new Date().toISOString();
      const { error: deviceError } = await admin.from("homeapp_devices").upsert({
        profile_id: authUserId,
        fingerprint_hash: fingerprintHash,
        approved_at: now,
        revoked_at: null,
        last_seen_at: now,
        app_version: appVersion,
      }, { onConflict: "profile_id" });
      if (deviceError) throw deviceError;
      await admin.from("homeapp_activation_attempts").insert({ fingerprint_hash: fingerprintHash, succeeded: true });
      const tokenHash = await issueSessionToken(admin, slot.email);
      return json({
        tokenHash,
        userId: authUserId,
        householdId: slot.household_id,
        displayName: slot.display_name,
        avatar: slot.avatar,
        accentArgb: slot.accent_argb,
      });
    };

    if (!existingDevice || existingDevice.fingerprint_hash === fingerprintHash) return await completeActivation();

    const recoveryCode = String(body.recoveryCode ?? "");
    if (recoveryCode && await sha256(recoveryCode) === slot.recovery_code_hash) return await completeActivation();

    if (action === "status") {
      const requestId = String(body.requestId ?? "");
      const { data: pending } = await admin.from("homeapp_device_requests")
        .select("status, requested_fingerprint_hash, expires_at").eq("id", requestId).eq("slot_id", slot.id).maybeSingle();
      if (!pending || pending.requested_fingerprint_hash !== fingerprintHash) return json({ error: "request_not_found" }, 404);
      if (pending.status === "APPROVED") return await completeActivation();
      if (pending.status === "REJECTED") return json({ error: "request_rejected" }, 403);
      if (new Date(pending.expires_at).getTime() <= Date.now()) return json({ error: "request_expired" }, 409);
      return json({ approvalRequired: true, requestId, status: pending.status }, 202);
    }

    const { data: pending, error: pendingError } = await admin.from("homeapp_device_requests").insert({
      slot_id: slot.id,
      requested_fingerprint_hash: fingerprintHash,
    }).select("id").single();
    if (pendingError) throw pendingError;
    return json({ approvalRequired: true, requestId: pending.id }, 409);
  } catch (error) {
    console.error(error);
    return json({ error: "activation_failed" }, 500);
  }
});
