import "jsr:@supabase/functions-js/edge-runtime.d.ts";

export const WEBHOOK_URL = "https://xevqbgjtxuveeqaiacvm.supabase.co/functions/v1/telegram-bot";

export const json = (body: unknown, status = 200) =>
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

export async function telegramApi<T>(
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

export const SUPABASE_URL = Deno.env.get("SUPABASE_URL") ?? "https://xevqbgjtxuveeqaiacvm.supabase.co";

export function serviceKey(): string | null {
  return Deno.env.get("SUPABASE_SERVICE_ROLE_KEY") ?? null;
}

export async function databaseRequest<T>(path: string, method = "GET", body?: unknown, preferOverride?: string): Promise<T> {
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

export async function saveProfile(telegramUserId: number, chatId: number): Promise<void> {
  await databaseRequest(
    "psurvival_bot_profiles?on_conflict=telegram_user_id",
    "POST",
    [{ telegram_user_id: telegramUserId, chat_id: chatId, last_seen_at: new Date().toISOString(), updated_at: new Date().toISOString() }],
  );
}

type StoredJob = { id: string; task_type: string; status: string; scheduled_at: string; created_at: string; last_error?: string | null; idempotency_key?: string | null };

export async function sha256Hex(value: string): Promise<string> {
  const digest = await crypto.subtle.digest("SHA-256", new TextEncoder().encode(value));
  return Array.from(new Uint8Array(digest), (b) => b.toString(16).padStart(2, "0")).join("");
}

export async function deriveWorkerToken(telegramUserId: number, updateId: number): Promise<string> {
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

export async function registerWorker(telegramUserId: number, updateId: number, rotate: boolean): Promise<{ token: string; alreadyRegistered: boolean }> {
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

export async function readProfileSettings(telegramUserId: number): Promise<ProfileSettings> {
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

export function validateDailySteps(value: unknown): string | null {
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
      case "capture_ui":
        break;
      case "tap_relative":
        if (typeof item.x !== "number" || typeof item.y !== "number" || (item.x as number) < 0 || (item.x as number) > 1 || (item.y as number) < 0 || (item.y as number) > 1)
          return `Шаг ${index + 1}: tap_relative: x,y в 0.0–1.0`;
        break;
      case "swipe":
        break;
      case "tap_point":
        if (typeof item.name !== "string" || !(item.name as string).trim()) return `Шаг ${index + 1}: tap_point требует name`;
        break;
      default:
        return `Шаг ${index + 1}: неизвестный тип. Допустимы wait, back, capture_ui, tap_text, tap_description, tap_coordinates, tap_relative, swipe, tap_point, launch_app.`;
    }
  }
  return null;
}

export async function setProfileSetting(telegramUserId: number, key: string, rawValue: string): Promise<string> {
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

export async function listUserJobs(telegramUserId: number): Promise<StoredJob[]> {
  const query = new URLSearchParams({
    select: "id,task_type,status,scheduled_at,created_at,last_error",
    telegram_user_id: `eq.${telegramUserId}`,
    order: "created_at.desc",
    limit: "5",
  });
  return await databaseRequest<StoredJob[]>(`psurvival_bot_jobs?${query.toString()}`);
}

export async function enqueueDailyJob(telegramUserId: number, chatId: number, updateId: number, steps: unknown[]): Promise<StoredJob> {
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
