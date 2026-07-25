// Supabase Edge Function: fridge-display-api
// Read-only agenda for a kitchen/fridge display: today's events, new notes,
// open tasks and shopping lists for the household, as JSON.
// Auth: a display key configured in FRIDGE_DISPLAY_KEYS (same env-key-set
// pattern as the activation function's publishable keys), supplied by the
// display via ?token= or the X-Display-Key header. Data is read with the
// secret key; this function never writes.
import { createClient } from "npm:@supabase/supabase-js@2.110.8";

const corsHeaders = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Headers": "authorization, apikey, content-type, x-client-info, x-display-key",
  "Access-Control-Allow-Methods": "GET, OPTIONS",
  "Cache-Control": "no-store",
};

const json = (body: unknown, status = 200) => new Response(JSON.stringify(body), {
  status,
  headers: { ...corsHeaders, "Content-Type": "application/json; charset=utf-8" },
});

const parseKeySet = (raw: string | undefined): string[] => {
  if (!raw) return [];
  try {
    const parsed = JSON.parse(raw);
    return typeof parsed === "object" && parsed !== null ? Object.values(parsed).filter((v): v is string => typeof v === "string") : [];
  } catch {
    return [raw];
  }
};

type ItemPayload = {
  title?: string;
  startAt?: string | null;
  endAt?: string | null;
  allDay?: boolean;
  dueAt?: string | null;
  scheduledPublishAt?: string | null;
  pinned?: boolean;
  assignee?: string;
  creatorId?: string;
  checklist?: { title?: string; completed?: boolean }[];
};

type ItemRow = {
  id: string;
  type: "EVENT" | "TASK" | "LIST" | "NOTE";
  status: string;
  creator_id: string;
  payload: ItemPayload;
};

Deno.serve(async (request: Request) => {
  if (request.method === "OPTIONS") return new Response("ok", { headers: corsHeaders });
  if (request.method !== "GET") return json({ error: "method_not_allowed" }, 405);

  const url = Deno.env.get("SUPABASE_URL");
  const secretKeys = parseKeySet(Deno.env.get("SUPABASE_SECRET_KEYS"));
  const secretKey = secretKeys[0] ?? Deno.env.get("SUPABASE_SERVICE_ROLE_KEY");
  const displayKeys = parseKeySet(Deno.env.get("FRIDGE_DISPLAY_KEYS"));
  if (!url || !secretKey) return json({ error: "server_not_configured" }, 500);
  if (displayKeys.length === 0) return json({ error: "display_not_configured" }, 500);

  const requestUrl = new URL(request.url);
  const suppliedKey = requestUrl.searchParams.get("token") ?? request.headers.get("X-Display-Key") ?? "";
  if (!displayKeys.includes(suppliedKey)) return json({ error: "invalid_display_key" }, 401);

  const admin = createClient(url, secretKey, {
    auth: { autoRefreshToken: false, persistSession: false },
    global: { headers: { "X-Client-Info": "homeapp-fridge-display/1.0" } },
  });

  try {
    const timeZone = Deno.env.get("FRIDGE_TIMEZONE") ?? "Asia/Jerusalem";
    const dateInTz = (iso: string) => new Date(iso).toLocaleDateString("en-CA", { timeZone });
    const timeInTz = (iso: string) => new Date(iso).toLocaleTimeString("en-GB", { timeZone, hour: "2-digit", minute: "2-digit" });
    const now = new Date();
    const today = dateInTz(now.toISOString());

    // The schema seeds exactly one household; FRIDGE_HOUSEHOLD_ID overrides.
    const householdId = Deno.env.get("FRIDGE_HOUSEHOLD_ID");
    const householdQuery = admin.from("homeapp_households").select("id, name").order("created_at").limit(1);
    const { data: household, error: householdError } = householdId
      ? await householdQuery.eq("id", householdId).maybeSingle()
      : await householdQuery.maybeSingle();
    if (householdError) throw householdError;
    if (!household) return json({ error: "household_not_found" }, 404);

    const [{ data: profiles, error: profilesError }, { data: slots, error: slotsError }, { data: items, error: itemsError }] = await Promise.all([
      admin.from("homeapp_profiles").select("id, display_name").eq("household_id", household.id),
      admin.from("homeapp_activation_slots").select("slot_name, display_name").eq("household_id", household.id).eq("active", true),
      admin.from("homeapp_items").select("id, type, status, creator_id, payload").eq("household_id", household.id).eq("status", "ACTIVE").limit(500),
    ]);
    if (profilesError) throw profilesError;
    if (slotsError) throw slotsError;
    if (itemsError) throw itemsError;

    const profileNames = new Map<string, string>((profiles ?? []).map((p: { id: string; display_name: string }): [string, string] => [p.id, p.display_name]));
    const slotNames = new Map<string, string>((slots ?? []).map((s: { slot_name: string; display_name: string }): [string, string] => [s.slot_name, s.display_name]));
    const responsibleFor = (item: ItemRow): string => {
      switch (item.payload.assignee) {
        case "USER_ONE": return slotNames.get("user_one") ?? "";
        case "USER_TWO": return slotNames.get("user_two") ?? "";
        case "BOTH": return "שנינו";
        default: return profileNames.get(item.payload.creatorId ?? item.creator_id) ?? "";
      }
    };

    const rows = (items ?? []) as ItemRow[];
    const eventsToday = rows
      .filter((i) => i.type === "EVENT" && i.payload.startAt && dateInTz(i.payload.startAt) === today)
      .sort((a, b) => (a.payload.startAt ?? "").localeCompare(b.payload.startAt ?? ""))
      .map((i) => ({
        title: i.payload.title ?? "",
        allDay: i.payload.allDay === true,
        startTime: i.payload.allDay ? null : timeInTz(i.payload.startAt as string),
        endTime: i.payload.allDay || !i.payload.endAt ? null : timeInTz(i.payload.endAt),
        responsible: responsibleFor(i),
      }));

    const newNotes = rows
      .filter((i) => i.type === "NOTE" && (!i.payload.scheduledPublishAt || new Date(i.payload.scheduledPublishAt) <= now))
      .sort((a, b) => Number(b.payload.pinned === true) - Number(a.payload.pinned === true))
      .slice(0, 6)
      .map((i) => ({
        title: i.payload.title ?? "",
        creator: profileNames.get(i.payload.creatorId ?? i.creator_id) ?? "",
        isPinned: i.payload.pinned === true,
      }));

    const openTasks = rows
      .filter((i) => i.type === "TASK")
      .map((i) => ({
        title: i.payload.title ?? "",
        responsible: responsibleFor(i),
        isOverdue: !!i.payload.dueAt && new Date(i.payload.dueAt) < now,
      }))
      .sort((a, b) => Number(b.isOverdue) - Number(a.isOverdue))
      .slice(0, 10);

    const shoppingLists = rows
      .filter((i) => i.type === "LIST")
      .map((i) => ({
        title: i.payload.title ?? "",
        openItems: (i.payload.checklist ?? []).filter((e) => e.completed !== true).map((e) => e.title ?? ""),
      }));

    return json({
      workspace: household.name,
      date: today,
      eventsToday,
      newNotes,
      openTasks,
      shoppingLists,
    });
  } catch (error) {
    console.error(error);
    return json({ error: "fridge_display_failed" }, 500);
  }
});
