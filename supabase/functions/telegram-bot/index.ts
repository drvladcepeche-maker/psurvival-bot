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

async function sha256Hex(value: string): Promise<string> {
  const digest = await crypto.subtle.digest("SHA-256", new TextEncoder().encode(value));
  return Array.from(new Uint8Array(digest), (b) => b.toString(16).padStart(2, "0")).join("");
}

async function deriveWorkerToken(telegramUserId: number, updateId: number): Promise<string> {
  const key = serviceKey();
  if (!key) throw new Error("database_service_key_missing");
  const cryptoKey = await crypto.subtle.importKey(
    "raw", new TextEncoder().encode(key), { name: "HMAC", hash: "SHA-256" }, false, ["sign"],
  );
  const signature = await crypto.subtle.sign(
    "HMAC", cryptoKey, new TextEncoder().encode(`psurvival-worker:${telegramUserId}:${updateId}`),
  );
  return "psw_" + Array.from(new Uint8Array(signature), (b) => b.toString(16).padStart(2, "0")).join("");
}

async function registerWorker(telegramUserId: number, updateId: number, rotate: boolean): Promise<{ token: string; alreadyRegistered: boolean }> {
  const workerId = `android_${telegramUserId}`;
  const token = await deriveWorkerToken(telegramUserId, updateId);
  const tokenHash = await sha256Hex(token);
  const query = new URLSearchParams({
    select: "worker_id,token_hash",
    worker_id: `eq.${workerId}`,
    limit: "1",
  });
  const existing = await databaseRequest<Array<{ worker_id: string; token_hash: string }>>(
    `psurvival_bot_devices?${query.toString()}`,
  );
  if (existing[0] && !rotate) {
    if (existing[0].token_hash === tokenHash) return { token, alreadyRegistered: false };
    return { token: "", alreadyRegistered: true };
  }
  await databaseRequest(
    "psurvival_bot_devices?on_conflict=worker_id",
    "POST",
    [{
      worker_id: workerId,
      owner_telegram_user_id: telegramUserId,
      token_hash: tokenHash,
      label: `Android worker ${telegramUserId}`,
      enabled: true,
      updated_at: new Date().toISOString(),
    }],
  );
  return { token, alreadyRegistered: false };
}



type ProfileSettings = Record<string, unknown>;

async function readProfileSettings(telegramUserId: number): Promise<ProfileSettings> {
  const query = new URLSearchParams({
    select: "settings",
    telegram_user_id: `eq.${telegramUserId}`,
    limit: "1",
  });
  const rows = await databaseRequest<Array<{ settings: ProfileSettings }>>(
    `psurvival_bot_profiles?${query.toString()}`,
  );
  return rows[0]?.settings ?? {};
}

function validateDailySteps(value: unknown): string | null {
  if (!Array.isArray(value) || value.length < 1 || value.length > 30) {
    return "daily_steps должен быть JSON-массивом из 1–30 шагов.";
  }
  for (const [index, step] of value.entries()) {
    if (!step || typeof step !== "object" || Array.isArray(step)) return `Шаг ${index + 1}: ожидается JSON-объект.`;
    const item = step as Record<string, unknown>;
    switch (item.type) {
      case "wait":
        if (typeof item.duration_ms !== "number" || !Number.isInteger(item.duration_ms) || item.duration_ms < 250 || item.duration_ms > 10000) {
          return `Шаг ${index + 1}: duration_ms должен быть целым числом 250–10000.`;
        }
        break;
      case "back":
        break;
      case "tap_text":
        if (typeof item.text !== "string" || !item.text.trim() || item.text.length > 120) return `Шаг ${index + 1}: нужен text длиной до 120 символов.`;
        break;
      case "tap_description":
        if (typeof item.description !== "string" || !item.description.trim() || item.description.length > 120) return `Шаг ${index + 1}: нужен description длиной до 120 символов.`;
        break;
      case "tap_coordinates":
        if (!Number.isInteger(item.x) || !Number.isInteger(item.y) || (item.x as number) < 0 || (item.y as number) < 0 || (item.x as number) > 10000 || (item.y as number) > 10000) {
          return `Шаг ${index + 1}: x и y должны быть целыми координатами 0–10000.`;
        }
        break;
      case "launch_app":
        if (typeof item.package_name !== "string" || !/^[A-Za-z0-9_]+(?:\.[A-Za-z0-9_]+)+$/.test(item.package_name)) {
          return `Шаг ${index + 1}: package_name должен быть идентификатором Android-пакета.`;
        }
        break;
      default:
        return `Шаг ${index + 1}: неизвестный тип. Допустимы wait, back, tap_text, tap_description, tap_coordinates, launch_app.`;
    }
  }
  return null;
}

async function setProfileSetting(telegramUserId: number, key: string, rawValue: string): Promise<string> {
  let value: unknown;
  if (key === "game_package") {
    if (!/^[A-Za-z0-9_]+(?:\.[A-Za-z0-9_]+)+$/.test(rawValue)) return "game_package должен быть идентификатором Android-пакета.";
    value = rawValue;
  } else if (key === "daily_steps") {
    try { value = JSON.parse(rawValue); } catch { return "daily_steps должен содержать корректный JSON-массив."; }
    const validation = validateDailySteps(value);
    if (validation) return validation;
  } else {
    return "Допустимые ключи: game_package, daily_steps.";
  }

  const settings = await readProfileSettings(telegramUserId);
  settings[key] = value;
  const query = new URLSearchParams({ telegram_user_id: `eq.${telegramUserId}` });
  const updated = await databaseRequest<Array<{ telegram_user_id: number }>>(
    `psurvival_bot_profiles?${query.toString()}`,
    "PATCH",
    { settings, updated_at: new Date().toISOString() },
  );
  if (!updated?.length) throw new Error("profile_setting_update_failed");
  return key === "daily_steps"
    ? `Сохранён сценарий из ${(value as unknown[]).length} шагов.`
    : `Сохранён пакет игры: ${String(value)}.`;
}

async function listUserJobs(telegramUserId: number): Promise<StoredJob[]> {
  const query = new URLSearchParams({
    select: "id,task_type,status,scheduled_at,created_at,last_error",
    telegram_user_id: `eq.${telegramUserId}`,
    order: "created_at.desc",
    limit: "5",
  });
  return await databaseRequest<StoredJob[]>(`psurvival_bot_jobs?${query.toString()}`);
}

async function enqueueDailyJob(telegramUserId: number, chatId: number, updateId: number, steps: unknown[]): Promise<StoredJob> {
  const idempotencyKey = `telegram_update_${updateId}`;
  const rows = await databaseRequest<StoredJob[]>(
    "psurvival_bot_jobs?on_conflict=telegram_user_id,idempotency_key",
    "POST",
    [{
      telegram_user_id: telegramUserId,
      chat_id: chatId,
      task_type: "daily_routine",
      payload: { requested_via: "telegram", steps },
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
      reply = "Команды бота:\n/start — проверить подключение\n/help — список команд\n/status — проверить облачный вебхук\n/settings — показать сохранённые настройки\n/set — изменить настройку\n/tasks — последние задания\n/daily — поставить сценарий в очередь\n/register_worker — зарегистрировать Android-исполнитель\n/rotate_worker — перевыпустить токен исполнителя\n/about — возможности прототипа";
      break;
    case "settings": {
      if (telegramUserId === undefined || !serviceKey()) {
        reply = "Хранилище настроек недоступно: не найден Telegram user ID или серверный ключ базы.";
        break;
      }
      try {
        const settings = await readProfileSettings(telegramUserId);
        const steps = Array.isArray(settings.daily_steps) ? settings.daily_steps.length : 0;
        reply = `Профиль сохранён в Supabase.\nПакет игры: ${String(settings.game_package ?? "не задан")}.\nШагов сценария: ${steps}.\nНастройки: ${JSON.stringify(settings)}\nЧтобы задать сценарий: /set daily_steps [{"type":"launch_app","package_name":"пакет.приложения"}]`;
      } catch {
        reply = "Не удалось прочитать настройки из Supabase. Проверь состояние базы и повтори попытку.";
      }
      break;
    }
    case "set": {
      if (telegramUserId === undefined || !serviceKey()) {
        reply = "Хранилище настроек недоступно.";
        break;
      }
      const args = text.replace(/^\/set(?:@[a-z0-9_]+)?\s*/i, "");
      const split = args.search(/\s/);
      if (split < 1) {
        reply = 'Формат: /set game_package com.example.game или /set daily_steps [{"type":"wait","duration_ms":1000}]';
        break;
      }
      const key = args.slice(0, split).trim();
      const value = args.slice(split).trim();
      if (!value) {
        reply = "После ключа настройки нужно указать значение.";
        break;
      }
      try {
        reply = await setProfileSetting(telegramUserId, key, value);
      } catch (error) {
        console.error("profile setting update failed", error instanceof Error ? error.message : "unknown");
        reply = "Не удалось сохранить настройку в Supabase.";
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
        const settings = await readProfileSettings(telegramUserId);
        const steps = Array.isArray(settings.daily_steps) ? settings.daily_steps : [];
        const job = await enqueueDailyJob(telegramUserId, chatId, updateId, steps);
        reply = steps.length
          ? `Задание daily_routine сохранено в Supabase. ID: ${job.id}. Статус: ${job.status}. Шагов: ${steps.length}.`
          : `Задание сохранено в очереди (ID: ${job.id}), но сценарий не настроен. Сначала используй /set daily_steps с JSON-массивом шагов.`;
      } catch (error) {
        console.error("job enqueue failed", error instanceof Error ? error.message : "unknown");
        reply = "Не удалось сохранить задание. Проверь состояние базы и повтори попытку.";
      }
      break;
    }
    case "register_worker":
    case "rotate_worker": {
      if (telegramUserId === undefined || chatId !== telegramUserId || !serviceKey()) {
        reply = "Регистрация исполнителя доступна только в личном чате и при подключённом хранилище.";
        break;
      }
      try {
        const registered = await registerWorker(telegramUserId, updateId, command === "rotate_worker");
        if (registered.alreadyRegistered) {
          reply = "Android-исполнитель уже зарегистрирован. Если потерян токен, используй /rotate_worker; старый токен перестанет работать.";
        } else {
          reply = `Исполнитель зарегистрирован. Сохрани токен в настройках Android-приложения и не пересылай его другим.\nWorker ID: android_${telegramUserId}\nAPI: https://xevqbgjtxuveeqaiacvm.supabase.co/functions/v1/psurvival-worker\nToken: ${registered.token}\n\nAPK Android-исполнителя собирается в CI. После установки и включения службы доступности он сможет выполнять сохранённые шаги интерфейса.`;
        }
      } catch (error) {
        console.error("worker registration failed", error instanceof Error ? error.message : "unknown");
        reply = "Не удалось зарегистрировать исполнителя. Проверь состояние базы и повтори попытку.";
      }
      break;
    }
    case "about":
      reply = "Работают Telegram-команды, постоянные профили, очередь заданий и серверный протокол исполнителя. Android-клиент, который взаимодействует с интерфейсом игры, ещё не реализован.";
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
