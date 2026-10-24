import "jsr:@supabase/functions-js/edge-runtime.d.ts";
import { matchesSecret } from "./_shared/webhook_secret.ts";

const WEBHOOK_URL = "https://xevqbgjtxuveeqaiacvm.supabase.co/functions/v1/telegram-bot";

const json = (body: unknown, status = 200) =>
  new Response(JSON.stringify(body), {
    status,
    headers: { "content-type": "application/json; charset=utf-8" },
  });

type TelegramApiResult<T> = {
  ok: boolean;
  result?: T;
  error_code?: number;
  description?: string;
  transport_error?: boolean;
};

async function telegramApi<T>(
  token: string,
  method: string,
  payload?: Record<string, unknown>,
): Promise<TelegramApiResult<T>> {
  let response: Response;
  try {
    response = await fetch(`https://api.telegram.org/bot${token}/${method}`, {
      method: payload ? "POST" : "GET",
      headers: payload ? { "content-type": "application/json" } : undefined,
      body: payload ? JSON.stringify(payload) : undefined,
    });
  } catch {
    return { ok: false, transport_error: true };
  }
  let data: TelegramApiResult<T>;
  try {
    data = await response.json() as TelegramApiResult<T>;
  } catch {
    return { ok: false, error_code: response.status };
  }
  if (!response.ok) return { ok: false, error_code: data.error_code ?? response.status, description: data.description };
  return data;
}


const SUPABASE_URL = Deno.env.get("SUPABASE_URL") ?? "https://xevqbgjtxuveeqaiacvm.supabase.co";

function serviceKey(): string | null {
  return Deno.env.get("SUPABASE_SERVICE_ROLE_KEY") ?? null;
}

async function databaseRequest<T>(path: string, method = "GET", body?: unknown, preferOverride?: string): Promise<T> {
  const key = serviceKey();
  if (!key) throw new Error("database_service_key_missing");
  const response = await fetch(`${SUPABASE_URL}/rest/v1/${path}`, {
    method,
    headers: {
      apikey: key,
      authorization: `Bearer ${key}`,
      "content-type": "application/json",
      prefer: preferOverride ?? (method === "POST" ? "return=representation,resolution=merge-duplicates" : "return=representation"),
    },
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  const raw = await response.text();
  if (!response.ok) throw new Error(`database_http_${response.status}:${raw.slice(0, 200)}`);
  return (raw ? JSON.parse(raw) : null) as T;
}

async function saveProfile(telegramUserId: number, chatId: number): Promise<void> {
  await databaseRequest(
    "psurvival_bot_profiles?on_conflict=telegram_user_id",
    "POST",
    [{ telegram_user_id: telegramUserId, chat_id: chatId, last_seen_at: new Date().toISOString(), updated_at: new Date().toISOString() }],
  );
}

type StoredJob = { id: string; task_type: string; status: string; scheduled_at: string; created_at: string; last_error?: string | null; idempotency_key?: string | null };

async function listUserJobs(telegramUserId: number): Promise<StoredJob[]> {
  const query = new URLSearchParams({
    select: "id,task_type,status,scheduled_at,created_at,last_error",
    telegram_user_id: `eq.${telegramUserId}`,
    order: "created_at.desc",
    limit: "5",
  });
  return await databaseRequest<StoredJob[]>(`psurvival_bot_jobs?${query.toString()}`);
}

async function enqueueDailyJob(telegramUserId: number, chatId: number, updateId: number): Promise<StoredJob> {
  const idempotencyKey = `telegram_update_${updateId}`;
  const rows = await databaseRequest<StoredJob[]>(
    "psurvival_bot_jobs?on_conflict=telegram_user_id,idempotency_key",
    "POST",
    [{
      telegram_user_id: telegramUserId,
      chat_id: chatId,
      task_type: "daily_routine",
      payload: { requested_via: "telegram" },
      status: "queued",
      idempotency_key: idempotencyKey,
    }],
    "return=representation,resolution=ignore-duplicates",
  );
  if (rows?.[0]) return rows[0];
  const query = new URLSearchParams({
    select: "id,task_type,status,scheduled_at,created_at,last_error,idempotency_key",
    telegram_user_id: `eq.${telegramUserId}`,
    idempotency_key: `eq.${idempotencyKey}`,
    limit: "1",
  });
  const existing = await databaseRequest<StoredJob[]>(`psurvival_bot_jobs?${query.toString()}`);
  if (existing?.[0]) return existing[0];
  throw new Error("database_job_insert_returned_no_row");
}

Deno.serve(async (req: Request) => {
  if (req.method === "GET") {
    const botToken = Deno.env.get("TELEGRAM_BOT_TOKEN");
    const webhookSecret = Deno.env.get("TELEGRAM_WEBHOOK_SECRET");
    if (!botToken || !webhookSecret) {
      return json({ ok: true, service: "telegram-bot", configured: false, webhookRegistered: false, status: "missing_secrets" });
    }
    if (!/^[A-Za-z0-9_-]{1,256}$/.test(webhookSecret)) {
      return json({ ok: true, service: "telegram-bot", configured: true, webhookRegistered: false, status: "webhook_secret_format_invalid" });
    }

    const targetUrl = new URL(WEBHOOK_URL);
    const info = await telegramApi<{ url?: string }>(botToken, "getWebhookInfo");
    if (!info.ok || !info.result) {
      return json({
        ok: true, service: "telegram-bot", configured: true, webhookRegistered: false,
        status: info.transport_error ? "telegram_network_error" : `telegram_api_error_${info.error_code ?? "unknown"}`,
      });
    }

    const currentUrl = info.result.url ?? "";
    if (currentUrl === targetUrl.toString()) {
      return json({ ok: true, service: "telegram-bot", configured: true, webhookRegistered: true, status: "already_registered" });
    }
    if (currentUrl !== "") {
      return json({ ok: true, service: "telegram-bot", configured: true, webhookRegistered: false, status: "another_webhook_already_configured" });
    }

    const registration = await telegramApi<boolean>(botToken, "setWebhook", {
      url: targetUrl.toString(), secret_token: webhookSecret, allowed_updates: ["message"], drop_pending_updates: false,
    });
    if (!registration.ok || registration.result !== true) {
      const description = (registration.description ?? "").toLowerCase();
      const status = registration.transport_error
        ? "telegram_network_error"
        : description.includes("secret")
        ? "webhook_secret_rejected"
        : description.includes("certificate") || description.includes("tls")
        ? "webhook_tls_certificate_rejected"
        : description.includes("resolve") || description.includes("dns")
        ? "webhook_dns_resolution_failed"
        : description.includes("timeout") || description.includes("timed out")
        ? "webhook_connection_timeout"
        : description.includes("ip address")
        ? "webhook_ip_rejected"
        : description.includes("https")
        ? "webhook_https_requirement_failed"
        : description.includes("wrong response") || description.includes("response")
        ? "webhook_endpoint_response_rejected"
        : description.includes("webhook") || description.includes("url")
        ? "webhook_target_rejected"
        : description.includes("token")
        ? "telegram_bot_token_rejected"
        : `webhook_registration_error_${registration.error_code ?? "unknown"}`;
      return json({
        ok: true, service: "telegram-bot", configured: true, webhookRegistered: false, status,
        diagnostic: description.replace(/[\\r\n\\t]/g, " ").slice(0, 180),
      });
    }

    const verify = await telegramApi<{ url?: string }>(botToken, "getWebhookInfo");
    const verified = verify.ok && verify.result?.url === targetUrl.toString();
    return json({
      ok: true, service: "telegram-bot", configured: true, webhookRegistered: verified,
      status: verified ? "registered_and_verified" : verify.transport_error ? "telegram_network_error" : `verification_error_${verify.error_code ?? "url_mismatch"}`,
    });
  }

  if (req.method !== "POST") return json({ ok: false, error: "POST required" }, 405);
  const botToken = Deno.env.get("TELEGRAM_BOT_TOKEN");
  const webhookSecret = Deno.env.get("TELEGRAM_WEBHOOK_SECRET");
  if (!botToken || !webhookSecret) return json({ ok: false, error: "Bot configuration is missing" }, 503);
  const suppliedSecret = req.headers.get("X-Telegram-Bot-Api-Secret-Token") ?? "";
  if (!matchesSecret(webhookSecret, suppliedSecret)) return json({ ok: false, error: "Unauthorized" }, 401);

  let update: unknown;
  try { update = await req.json(); } catch { return json({ ok: false, error: "Invalid JSON" }, 400); }
  const updateObject = update as {
    update_id?: number;
    message?: { chat?: { id?: number }; from?: { id?: number }; text?: string };
  };
  const message = updateObject?.message;
  const chatId = message?.chat?.id;
  const telegramUserId = message?.from?.id;
  const updateId = updateObject?.update_id ?? Date.now();
  const text = typeof message?.text === "string" ? message.text.trim() : "";
  if (chatId === undefined || !text) return json({ ok: true, ignored: true });

  if (telegramUserId !== undefined && Number.isSafeInteger(telegramUserId)) {
    try {
      await saveProfile(telegramUserId, chatId);
    } catch (error) {
      console.error("profile persistence failed", error instanceof Error ? error.message : "unknown");
    }
  }

  const commandMatch = text.match(/^\/([a-z0-9_]+)(?:@[a-z0-9_]+)?(?:\s|$)/i);
  const command = commandMatch?.[1]?.toLowerCase() ?? "";
  let reply: string;
  switch (command) {
    case "start":
      reply = "Бот подключён. Это облачный прототип для Puzzles & Survival. Используй /help для списка команд.";
      break;
    case "help":
      reply = "Команды бота:\n/start — проверить подключение\n/help — список команд\n/status — проверить облачный вебхук\n/settings — проверить сохранение профиля\n/tasks — последние задания\n/daily — поставить ежедневный сценарий в очередь\n/about — возможности прототипа";
      break;
    case "settings": {
      if (telegramUserId === undefined || !serviceKey()) {
        reply = "Хранилище настроек недоступно: не найден Telegram user ID или серверный ключ базы.";
        break;
      }
      try {
        const query = new URLSearchParams({
          select: "telegram_user_id,settings,automation_enabled,updated_at,last_seen_at",
          telegram_user_id: `eq.${telegramUserId}`,
          limit: "1",
        });
        const rows = await databaseRequest<Array<{ settings: Record<string, unknown>; automation_enabled: boolean; updated_at: string }>>(
          `psurvival_bot_profiles?${query.toString()}`,
        );
        const profile = rows[0];
        reply = profile
          ? `Профиль сохранён в Supabase.\nАвтоматизация включена: ${profile.automation_enabled ? "да" : "нет"}.\nНастройки: ${JSON.stringify(profile.settings)}\nИзменено: ${profile.updated_at}`
          : "Профиль ещё не найден. Отправь /settings повторно.";
      } catch {
        reply = "Не удалось прочитать настройки из Supabase. Проверь состояние базы и повтори попытку.";
      }
      break;
    }
    case "tasks": {
      if (telegramUserId === undefined || !serviceKey()) {
        reply = "Очередь заданий недоступна: не найден Telegram user ID или серверный ключ базы.";
        break;
      }
      try {
        const jobs = await listUserJobs(telegramUserId);
        reply = jobs.length
          ? "Последние задания:\n" + jobs.map((job) => `• ${job.task_type}: ${job.status} (${job.id.slice(0, 8)})`).join("\n")
          : "Заданий пока нет. Команда /daily создаёт тестовую запись в постоянной очереди.";
      } catch {
        reply = "Не удалось прочитать очередь заданий из Supabase.";
      }
      break;
    }
    case "daily": {
      if (telegramUserId === undefined || !serviceKey()) {
        reply = "Не удалось поставить задание в очередь: профиль или серверный ключ базы недоступен.";
        break;
      }
      try {
        const job = await enqueueDailyJob(telegramUserId, chatId, updateId);
        reply = `Задание daily_routine сохранено в Supabase. ID: ${job.id}. Статус: ${job.status}.\nИсполнитель ещё не подключён, поэтому игровые действия пока не выполняются.`;
      } catch (error) {
        console.error("job enqueue failed", error instanceof Error ? error.message : "unknown");
        reply = "Не удалось сохранить задание. Проверь состояние базы и повтори попытку.";
      }
      break;
    }
    case "about":
      reply = "Сейчас доступны Telegram-команды, постоянные профили и очередь заданий в Supabase. Исполнитель Android и реальные действия в игре ещё не подключены.";
      break;
    case "status": {
      const info = await telegramApi<{ url?: string; pending_update_count?: number; last_error_message?: string }>(botToken, "getWebhookInfo");
      if (!info.ok || !info.result) {
        reply = "Не удалось проверить Telegram API. Повтори попытку позже.";
      } else if (info.result.url !== WEBHOOK_URL) {
        reply = "Вебхук Telegram не совпадает с ожидаемым адресом Supabase. Требуется восстановление подключения.";
      } else {
        const pending = info.result.pending_update_count ?? 0;
        reply = `Облачный вебхук подключён.\nОжидающих обновлений: ${pending}.\nЭто подтверждает регистрацию вебхука, но не проверяет управление игрой.`;
      }
      break;
    }
    default:
      reply = "Эта команда пока не реализована. Используй /help.";
  }

  const sent = await telegramApi<{ message_id?: number }>(botToken, "sendMessage", { chat_id: chatId, text: reply });
  if (!sent.ok || !sent.result) {
    // Non-2xx makes Telegram retry the update instead of silently losing the reply.
    return json({ ok: false, error: "Telegram sendMessage failed", code: sent.error_code ?? null }, 502);
  }
  return json({ ok: true });
});
