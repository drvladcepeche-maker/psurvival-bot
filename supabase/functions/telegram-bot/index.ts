import "jsr:@supabase/functions-js/edge-runtime.d.ts";
import { matchesSecret } from "./_shared/webhook_secret.ts";

const WEBHOOK_URL = "https://xevqbgjtxuveeqaiacvm.supabase.co/functions/v1/telegram-bot";

const json = (body: unknown, status = 200) =>
  new Response(JSON.stringify(body), {
    status,
    headers: { "content-type": "application/json; charset=utf-8" },
  });

// FILE CONTINUED IN NEXT COMMIT - temporary stub to avoid empty file
Deno.serve(() => json({ ok: false, error: "deploy pending full source" }, 503));
