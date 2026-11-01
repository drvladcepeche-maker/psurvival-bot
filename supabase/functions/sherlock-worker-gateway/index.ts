import "jsr:@supabase/functions-js/edge-runtime.d.ts";
import { createRemoteJWKSet, jwtVerify } from "npm:jose@5.9.6";

const SUPABASE_URL = Deno.env.get("SUPABASE_URL") ?? "https://xevqbgjtxuveeqaiacvm.supabase.co";
const SERVICE_KEY = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY") ?? "";
const TELEGRAM_TOKEN = Deno.env.get("TELEGRAM_BOT_TOKEN") ?? "";
const AUDIENCE = "https://xevqbgjtxuveeqaiacvm.supabase.co/functions/v1/sherlock-worker-gateway";
const REPOSITORY = "drvladcepeche-maker/psurvival-bot";
const ALLOWED_REFS = new Set(["refs/heads/main", "refs/heads/feature/sherlock-telegram-integration"]);
const WORKER_ID = "github-actions-sherlock";
const JWKS = createRemoteJWKSet(new URL("https://token.actions.githubusercontent.com/.well-known/jwks"));

const json = (body: unknown, status = 200) => new Response(JSON.stringify(body), {
  status, headers: { "content-type": "application/json; charset=utf-8" },
});

async function authenticate(req: Request): Promise<boolean> {
  const authorization = req.headers.get("authorization") ?? "";
  const token = authorization.startsWith("Bearer ") ? authorization.slice(7) : "";
  if (!token) return false;
  try {
    const { payload } = await jwtVerify(token, JWKS, {
      issuer: "https://token.actions.githubusercontent.com", audience: AUDIENCE,
    });
    return payload.repository === REPOSITORY &&
      typeof payload.ref === "string" && ALLOWED_REFS.has(payload.ref) &&
      ["push", "schedule", "workflow_dispatch"].includes(String(payload.event_name));
  } catch {
    return false;
  }
}

async function database(path: string, method = "GET", body?: unknown) {
  if (!SERVICE_KEY) throw new Error("service_role_key_missing");
  const response = await fetch(`${SUPABASE_URL}/rest/v1/${path}`, {
    method,
    headers: {
      apikey: SERVICE_KEY, authorization: `Bearer ${SERVICE_KEY}`,
      "content-type": "application/json", prefer: "return=representation",
    },
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  const raw = await response.text();
  if (!response.ok) throw new Error(`database_http_${response.status}:${raw.slice(0, 250)}`);
  return raw ? JSON.parse(raw) : null;
}

async function sendTelegram(chatId: number, message: string): Promise<boolean> {
  if (!TELEGRAM_TOKEN) return false;
  const response = await fetch(`https://api.telegram.org/bot${TELEGRAM_TOKEN}/sendMessage`, {
    method: "POST", headers: { "content-type": "application/json" },
    body: JSON.stringify({ chat_id: chatId, text: message.slice(0, 3900), disable_web_page_preview: true }),
  });
  if (!response.ok) return false;
  return (await response.json()).ok === true;
}

Deno.serve(async (req: Request) => {
  if (req.method !== "POST") return json({ ok: false, error: "method_not_allowed" }, 405);
  if (!await authenticate(req)) return json({ ok: false, error: "unauthorized" }, 401);
  try {
    const body = await req.json();
    if (body.worker_id !== WORKER_ID) return json({ ok: false, error: "invalid_worker_id" }, 400);
    if (body.action === "claim") {
      const rows = await database("rpc/sherlock_claim_next_job", "POST", { p_worker_id: WORKER_ID });
      return json({ ok: true, data: rows ?? [] });
    }
    if (body.action === "patch") {
      if (typeof body.job_id !== "string" || !/^[0-9a-f-]{36}$/i.test(body.job_id))
        return json({ ok: false, error: "invalid_job_id" }, 400);
      if (!body.payload || typeof body.payload !== "object" || Array.isArray(body.payload))
        return json({ ok: false, error: "invalid_payload" }, 400);
      const allowed = new Set(["status", "total_sites", "checked_sites", "found_count",
        "results", "error_message", "finished_at", "updated_at"]);
      const payload: Record<string, unknown> = {};
      for (const [key, value] of Object.entries(body.payload)) {
        if (!allowed.has(key)) return json({ ok: false, error: "invalid_field" }, 400);
        payload[key] = value;
      }
      if (payload.status !== undefined && !["running", "succeeded", "failed"].includes(String(payload.status)))
        return json({ ok: false, error: "invalid_status" }, 400);
      const query = new URLSearchParams({
        id: `eq.${body.job_id}`, worker_id: `eq.${WORKER_ID}`, status: "eq.running",
      });
      const rows = await database(`sherlock_jobs?${query.toString()}`, "PATCH", payload);
      if (!Array.isArray(rows) || rows.length !== 1)
        return json({ ok: false, error: "job_not_owned_or_not_running" }, 409);
      let notificationSent: boolean | null = null;
      if (typeof body.notification === "string" && body.notification.length > 0)
        notificationSent = await sendTelegram(Number(rows[0].chat_id), body.notification);
      return json({ ok: true, data: rows[0], notification_sent: notificationSent });
    }
    return json({ ok: false, error: "unknown_action" }, 400);
  } catch (error) {
    console.error("sherlock-worker-gateway", error);
    return json({ ok: false, error: error instanceof Error ? error.message : "internal_error" }, 500);
  }
});
