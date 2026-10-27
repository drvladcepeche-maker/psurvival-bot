import {
  json, WEBHOOK_URL, serviceKey, databaseRequest, saveProfile,
  registerWorker, readProfileSettings, setProfileSetting, listUserJobs,
  enqueueDailyJob, telegramApi,
} from "./lib.ts";
import { matchesSecret } from "./_shared/webhook_secret.ts";
import { handleExtraCommands } from "./handlers.ts";

Deno.serve(async (req: Request) => {
  if (req.method === "GET") {
    const botToken = Deno.env.get("TELEGRAM_BOT_TOKEN");
    const webhookSecret = Deno.env.get("TELEGRAM_WEBHOOK_SECRET");
    if (!botToken || !webhookSecret) return json({ ok: true, service: "telegram-bot", configured: false, webhookRegistered: false, status: "missing_secrets" });
    if (!/^[A-Za-z0-9_-]{1,256}$/.test(webhookSecret)) return json({ ok: true, service: "telegram-bot", configured: true, webhookRegistered: false, status: "webhook_secret_format_invalid" });
    const targetUrl = new URL(WEBHOOK_URL);
    const info = await telegramApi<{ url?: string }>(botToken, "getWebhookInfo");
    if (!info.ok || !info.result) return json({ ok: true, service: "telegram-bot", configured: true, webhookRegistered: false, status: info.transport_error ? "telegram_network_error" : `telegram_api_error_${info.error_code ?? "unknown"}` });
    const currentUrl = info.result.url ?? "";
    if (currentUrl === targetUrl.toString()) return json({ ok: true, service: "telegram-bot", configured: true, webhookRegistered: true, status: "already_registered" });
    if (currentUrl !== "") return json({ ok: true, service: "telegram-bot", configured: true, webhookRegistered: false, status: "another_webhook_already_configured" });
    const registration = await telegramApi<boolean>(botToken, "setWebhook", { url: targetUrl.toString(), secret_token: webhookSecret, allowed_updates: ["message"], drop_pending_updates: false });
    if (!registration.ok || registration.result !== true) {
      const description = (registration.description ?? "").toLowerCase();
      return json({ ok: true, service: "telegram-bot", configured: true, webhookRegistered: false, status: "webhook_registration_error", diagnostic: description.replace(/[\r\n\t]/g, " ").slice(0, 180) });
    }
    const verify = await telegramApi<{ url?: string }>(botToken, "getWebhookInfo");
    const verified = verify.ok && verify.result?.url === targetUrl.toString();
    return json({ ok: true, service: "telegram-bot", configured: true, webhookRegistered: verified, status: verified ? "registered_and_verified" : "verification_error" });
  }
  if (req.method !== "POST") return json({ ok: false, error: "POST required" }, 405);
  const botToken = Deno.env.get("TELEGRAM_BOT_TOKEN");
  const webhookSecret = Deno.env.get("TELEGRAM_WEBHOOK_SECRET");
  if (!botToken || !webhookSecret) return json({ ok: false, error: "Bot configuration is missing" }, 503);
  const suppliedSecret = req.headers.get("X-Telegram-Bot-Api-Secret-Token") ?? "";
  if (!matchesSecret(webhookSecret, suppliedSecret)) return json({ ok: false, error: "Unauthorized" }, 401);
  let update: unknown;
  try { update = await req.json(); } catch { return json({ ok: false, error: "Invalid JSON" }, 400); }
  const updateObject = update as { update_id?: number; message?: { chat?: { id?: number }; from?: { id?: number }; text?: string } };
  const message = updateObject?.message;
  const chatId = message?.chat?.id;
  const telegramUserId = message?.from?.id;
  const updateId = updateObject?.update_id ?? Date.now();
  const text = typeof message?.text === "string" ? message.text.trim() : "";
  if (chatId === undefined || !text) return json({ ok: true, ignored: true });
  if (telegramUserId !== undefined && Number.isSafeInteger(telegramUserId)) {
    try { await saveProfile(telegramUserId, chatId); } catch (error) { console.error("profile persistence failed", error instanceof Error ? error.message : "unknown"); }
  }
  const commandMatch = text.match(/^\/([a-z0-9_]+)(?:@[a-z0-9_]+)?(?:\s|$)/i);
  const command = commandMatch?.[1]?.toLowerCase() ?? "";
  let reply: string;
  switch (command) {
    case "start":
      reply = "Бот подключён. Используй /help.";
      break;
    case "help":
      reply = "Команды: /start /help /status /settings /set /tasks /daily /scenarios /scenario /point /register_worker /rotate_worker /about";
      break;
    case "settings": {
      if (telegramUserId === undefined || !serviceKey()) { reply = "Настройки недоступны."; break; }
      try {
        const settings = await readProfileSettings(telegramUserId);
        const steps = Array.isArray(settings.daily_steps) ? settings.daily_steps.length : 0;
        reply = `Пакет: ${String(settings.game_package ?? "не задан")}. Шагов: ${steps}. Точки: ${JSON.stringify(settings.points ?? {})}`;
      } catch { reply = "Не удалось прочитать настройки."; }
      break;
    }
    case "set": {
      if (telegramUserId === undefined || !serviceKey()) { reply = "Настройки недоступны."; break; }
      const args = text.replace(/^\/set(?:@[a-z0-9_]+)?\s*/i, "");
      const split = args.search(/\s/);
      if (split < 1) { reply = "Формат: /set game_package com.example.game или /set daily_steps [...]"; break; }
      try { reply = await setProfileSetting(telegramUserId, args.slice(0, split).trim(), args.slice(split).trim()); }
      catch { reply = "Не удалось сохранить настройку."; }
      break;
    }
    case "tasks": {
      if (telegramUserId === undefined || !serviceKey()) { reply = "Очередь недоступна."; break; }
      try {
        const jobs = await listUserJobs(telegramUserId);
        reply = jobs.length ? "Задания:\n" + jobs.map((j) => `• ${j.task_type}: ${j.status} (${j.id.slice(0, 8)})`).join("\n") : "Заданий нет. /daily или /scenario launch";
      } catch { reply = "Не удалось прочитать очередь."; }
      break;
    }
    case "daily": {
      if (telegramUserId === undefined || !serviceKey()) { reply = "Очередь недоступна."; break; }
      try {
        const settings = await readProfileSettings(telegramUserId);
        const configuredSteps = Array.isArray(settings.daily_steps) ? settings.daily_steps : [];
        const launchOnly = configuredSteps.length === 0 && typeof settings.game_package === "string";
        const steps = configuredSteps.length ? configuredSteps : launchOnly ? [{ type: "launch_app", package_name: settings.game_package as string }] : [];
        if (!steps.length) { reply = "Задай /set game_package или /set daily_steps, либо /scenario launch"; break; }
        const job = await enqueueDailyJob(telegramUserId, chatId, updateId, steps);
        reply = `Задание в очереди: ${job.id.slice(0, 8)}… ${job.status}`;
      } catch { reply = "Не удалось поставить задание."; }
      break;
    }
    case "register_worker":
    case "rotate_worker": {
      if (telegramUserId === undefined || !serviceKey()) { reply = "Регистрация недоступна."; break; }
      try {
        const registered = await registerWorker(telegramUserId, updateId, command === "rotate_worker");
        if (registered.alreadyRegistered) reply = "Исполнитель уже зарегистрирован. /rotate_worker для нового токена.";
        else reply = `Worker ID: android_${telegramUserId}\nToken: ${registered.token}\nAPI: https://xevqbgjtxuveeqaiacvm.supabase.co/functions/v1/psurvival-worker`;
      } catch { reply = "Не удалось зарегистрировать исполнителя."; }
      break;
    }
    case "about":
      reply = "P&S Worker: бот, очередь, Android AccessibilityService, /scenarios. Unity — координаты (tap_relative/tap_point). Пакет com.global.ztmslg — pm list packages | grep -i ztm";
      break;
    case "status": {
      const info = await telegramApi<{ url?: string; pending_update_count?: number }>(botToken, "getWebhookInfo");
      if (!info.ok || !info.result) reply = "Не удалось проверить Telegram API.";
      else if (info.result.url !== WEBHOOK_URL) reply = "Вебхук не совпадает с Supabase.";
      else reply = `Вебхук OK. Pending: ${info.result.pending_update_count ?? 0}`;
      break;
    }
    default: {
      const extra = await handleExtraCommands({ command, text, telegramUserId, chatId, updateId, serviceKey: serviceKey(), readProfileSettings, databaseRequest, enqueueDailyJob });
      reply = extra ?? "Команда не реализована. /help";
      break;
    }
  }
  const sent = await telegramApi<{ message_id?: number }>(botToken, "sendMessage", { chat_id: chatId, text: reply });
  if (!sent.ok || !sent.result) return json({ ok: false, error: "Telegram sendMessage failed", code: sent.error_code ?? null }, 502);
  return json({ ok: true });
});
