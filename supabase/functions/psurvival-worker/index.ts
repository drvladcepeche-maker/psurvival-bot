import "jsr:@supabase/functions-js/edge-runtime.d.ts";

const SUPABASE_URL = Deno.env.get("SUPABASE_URL") ?? "https://xevqbgjtxuveeqaiacvm.supabase.co";

const json = (body: unknown, status = 200) => new Response(JSON.stringify(body), {
  status,
  headers: { "content-type": "application/json; charset=utf-8" },
});

function serviceKey(): string | null {
  return Deno.env.get("SUPABASE_SERVICE_ROLE_KEY") ?? null;
}

async function db<T>(path: string, method = "GET", body?: unknown): Promise<T> {
  const key = serviceKey();
  if (!key) throw new Error("database_service_key_missing");
  const response = await fetch(`${SUPABASE_URL}/rest/v1/${path}`, {
    method,
    headers: {
      apikey: key,
      authorization: `Bearer ${key}`,
      "content-type": "application/json",
      prefer: method === "GET" ? "return=representation" : "return=representation",
    },
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  const raw = await response.text();
  if (!response.ok) throw new Error(`database_http_${response.status}:${raw.slice(0, 200)}`);
  return (raw ? JSON.parse(raw) : null) as T;
}

async function sha256Hex(value: string): Promise<string> {
  const digest = await crypto.subtle.digest("SHA-256", new TextEncoder().encode(value));
  return Array.from(new Uint8Array(digest), (b) => b.toString(16).padStart(2, "0")).join("");
}

type Device = { worker_id: string; owner_telegram_user_id: number; enabled: boolean };
type Job = { id: string; task_type: string; payload: Record<string, unknown>; attempts: number; max_attempts: number; status: string };

Deno.serve(async (req: Request) => {
  if (req.method === "GET") return json({ ok: true, service: "psurvival-worker", configured: Boolean(serviceKey()) });
  if (req.method !== "POST") return json({ ok: false, error: "POST required" }, 405);

  const key = serviceKey();
  if (!key) return json({ ok: false, error: "Worker service is not configured" }, 503);
  const authorization = req.headers.get("authorization") ?? "";
  const match = authorization.match(/^Bearer\s+(.+)$/i);
  if (!match) return json({ ok: false, error: "Worker token required" }, 401);

  let body: Record<string, unknown>;
  try {
    const parsed = await req.json();
    if (!parsed || typeof parsed !== "object" || Array.isArray(parsed)) return json({ ok: false, error: "JSON object required" }, 400);
    body = parsed as Record<string, unknown>;
  } catch {
    return json({ ok: false, error: "Invalid JSON" }, 400);
  }

  try {
    const tokenHash = await sha256Hex(match[1]);
    const devices = await db<Device[]>(
      `psurvival_bot_devices?select=worker_id,owner_telegram_user_id,enabled&token_hash=eq.${tokenHash}&enabled=eq.true&limit=1`,
    );
    const device = devices[0];
    if (!device || device.owner_telegram_user_id == null) return json({ ok: false, error: "Invalid worker token" }, 401);
    const requestedWorkerId = typeof body.worker_id === "string" ? body.worker_id.trim() : "";
    if (requestedWorkerId && requestedWorkerId !== device.worker_id) {
      return json({ ok: false, error: "Worker ID does not match the authenticated token" }, 403);
    }
    const workerId = device.worker_id;
    const action = body.action;

    if (action === "claim") {
      const jobs = await db<Job[]>("rpc/psurvival_bot_claim_next_job", "POST", {
        p_worker_id: workerId,
        p_lease_seconds: 90,
      });
      const job = jobs?.[0] ?? null;
      if (job) {
        await db("psurvival_bot_job_events", "POST", [{
          job_id: job.id, event_type: "claimed", worker_id: workerId,
          details: { attempt: job.attempts },
        }]);
      }
      await db(`psurvival_bot_devices?worker_id=eq.${encodeURIComponent(workerId)}`, "PATCH", { last_seen_at: new Date().toISOString() });
      return json({ ok: true, job });
    }

    if (action === "heartbeat" || action === "complete" || action === "fail") {
      const jobId = typeof body.job_id === "string" ? body.job_id : "";
      if (!/^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i.test(jobId)) {
        return json({ ok: false, error: "Valid job_id required" }, 400);
      }
      const current = await db<Job[]>(
        `psurvival_bot_jobs?select=id,task_type,payload,attempts,max_attempts,status&id=eq.${jobId}&worker_id=eq.${encodeURIComponent(workerId)}&status=eq.running&limit=1`,
      );
      if (!current[0]) return json({ ok: false, error: "Job is not leased to this worker" }, 409);
      const now = new Date();
      let patch: Record<string, unknown>;
      let eventType: string;
      let details: Record<string, unknown> = {};

      if (action === "heartbeat") {
        patch = { lease_expires_at: new Date(now.getTime() + 90_000).toISOString(), updated_at: now.toISOString() };
        eventType = "heartbeat";
      } else if (action === "complete") {
        const result = body.result && typeof body.result === "object" && !Array.isArray(body.result) ? body.result : {};
        patch = { status: "succeeded", result, finished_at: now.toISOString(), lease_expires_at: null, updated_at: now.toISOString(), last_error: null };
        eventType = "succeeded";
        details = { result_keys: Object.keys(result as Record<string, unknown>).slice(0, 20) };
      } else {
        const message = typeof body.error === "string" ? body.error.slice(0, 500) : "Worker reported failure";
        const retryable = body.retryable !== false;
        const willRetry = retryable && current[0].attempts < current[0].max_attempts;
        const delaySeconds = Math.min(300, 15 * (2 ** Math.max(0, current[0].attempts - 1)));
        patch = {
          status: willRetry ? "queued" : "failed",
          scheduled_at: willRetry ? new Date(now.getTime() + delaySeconds * 1000).toISOString() : now.toISOString(),
          finished_at: willRetry ? null : now.toISOString(),
          lease_expires_at: null,
          updated_at: now.toISOString(),
          last_error: message,
          worker_id: null,
        };
        eventType = willRetry ? "retry_scheduled" : "failed";
        details = { error: message, retry_delay_seconds: willRetry ? delaySeconds : 0 };
      }

      const changed = await db<Array<{ id: string }>>(
        `psurvival_bot_jobs?id=eq.${jobId}&worker_id=eq.${encodeURIComponent(workerId)}&status=eq.running`,
        "PATCH",
        patch,
      );
      if (!changed?.length) return json({ ok: false, error: "Job lease changed before update" }, 409);
      await db("psurvival_bot_job_events", "POST", [{ job_id: jobId, event_type: eventType, worker_id: workerId, details }]);
      return json({ ok: true, action, job_id: jobId, status: patch.status ?? "running" });
    }

    return json({ ok: false, error: "Unknown action. Use claim, heartbeat, complete, or fail." }, 400);
  } catch (error) {
    console.error("worker request failed", error instanceof Error ? error.message : "unknown");
    return json({ ok: false, error: "Worker request failed" }, 502);
  }
});
