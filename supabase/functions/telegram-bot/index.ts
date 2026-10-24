import "jsr:@supabase/functions-js/edge-runtime.d.ts";
import { matchesSecret } from "./_shared/webhook_secret.ts";

const json = (body: unknown, status = 200) =>
  new Response(JSON.stringify(body), {
    status,
    headers: { "content-type": "application/json; charset=utf-8" },
  });

type TelegramApiResult<T> = { ok: boolean; result?: T };

async function telegramApi<T>(
  token: string,
  method: string,
  payload?: Record<string, unknown>,
): Promise<TelegramApiResult<T>> {
  const response = await fetch(`https://api.telegram.org/bot${token}/${method}`, {
    method: payload ? "POST" : "GET",
    headers: payload ? { "content-type": "application/json" } : undefined,
    body: payload ? JSON.stringify(payload) : undefined,
  });
  if (!response.ok) return { ok: false };
  try {
    return await response.json() as TelegramApiResult<T>;
  } catch {
    return { ok: false };
  }
}

Deno.serve(async (req: Request) => {
  if (req.method === "GET") {
    const botToken = Deno.env.get("TELEGRAM_BOT_TOKEN");
    const webhookSecret = Deno.env.get("TELEGRAM_WEBHOOK_SECRET");
    if (!botToken || !webhookSecret) {
      return json({
        ok: true,
        service: "telegram-bot",
        configured: false,
        webhookRegistered: false,
        status: "missing_secrets",
      });
    }

    if (!/^[A-Za-z0-9_-]{1,256}$/.test(webhookSecret)) {
      return json({
        ok: true,
        service: "telegram-bot",
        configured: true,
        webhookRegistered: false,
        status: "webhook_secret_format_invalid",
      });
    }

    const targetUrl = new URL(req.url);
    targetUrl.search = "";
    targetUrl.hash = "";
    const info = await telegramApi<{ url?: string }>(botToken, "getWebhookInfo");
    if (!info.ok || !info.result) {
      return json({
        ok: true,
        service: "telegram-bot",
        configured: true,
        webhookRegistered: false,
        status: "telegram_status_unavailable",
      });
    }

    const currentUrl = info.result.url ?? "";
    if (currentUrl === targetUrl.toString()) {
      return json({
        ok: true,
        service: "telegram-bot",
        configured: true,
        webhookRegistered: true,
        status: "already_registered",
      });
    }

    // Never replace another integration's webhook or discard pending updates.
    if (currentUrl !== "") {
      return json({
        ok: true,
        service: "telegram-bot",
        configured: true,
        webhookRegistered: false,
        status: "another_webhook_already_configured",
      });
    }

    const registration = await telegramApi<boolean>(botToken, "setWebhook", {
      url: targetUrl.toString(),
      secret_token: webhookSecret,
      allowed_updates: ["message"],
      drop_pending_updates: false,
    });

    if (!registration.ok || registration.result !== true) {
      return json({
        ok: true,
        service: "telegram-bot",
        configured: true,
        webhookRegistered: false,
        status: "registration_failed",
      });
    }

    const verify = await telegramApi<{ url?: string }>(botToken, "getWebhookInfo");
    const verified = verify.ok && verify.result?.url === targetUrl.toString();
    return json({
      ok: true,
      service: "telegram-bot",
      configured: true,
      webhookRegistered: verified,
      status: verified ? "registered_and_verified" : "registration_unverified",
    });
  }

  if (req.method !== "POST") {
    return json({ ok: false, error: "POST required" }, 405);
  }

  const botToken = Deno.env.get("TELEGRAM_BOT_TOKEN");
  const webhookSecret = Deno.env.get("TELEGRAM_WEBHOOK_SECRET");
  if (!botToken || !webhookSecret) {
    return json({ ok: false, error: "Bot configuration is missing" }, 503);
  }

  const suppliedSecret =
    req.headers.get("X-Telegram-Bot-Api-Secret-Token") ?? "";
  if (!matchesSecret(webhookSecret, suppliedSecret)) {
    return json({ ok: false, error: "Unauthorized" }, 401);
  }

  let update: unknown;
  try {
    update = await req.json();
  } catch {
    return json({ ok: false, error: "Invalid JSON" }, 400);
  }

  const message = (update as {
    message?: {
      chat?: { id?: number };
      text?: string;
    };
  })?.message;
  const chatId = message?.chat?.id;
  const text = typeof message?.text === "string" ? message.text.trim() : "";

  if (chatId === undefined || !text) {
    return json({ ok: true, ignored: true });
  }

  let reply: string;
  if (text === "/start" || text.startsWith("/start@")) {
    reply =
      "Бот подключён. Это начальный облачный прототип для Puzzles & Survival. Доступны команды /start и /help; управление игрой ещё не подключено.";
  } else if (text === "/help" || text.startsWith("/help@")) {
    reply =
      "Доступные команды:\n/start — проверить подключение\n/help — список команд\n\nУправление игрой пока не реализовано.";
  } else {
    reply = "Команда пока не реализована. Используй /help.";
  }

  const telegramResponse = await fetch(
    `https://api.telegram.org/bot${botToken}/sendMessage`,
    {
      method: "POST",
      headers: { "content-type": "application/json" },
      body: JSON.stringify({ chat_id: chatId, text: reply }),
    },
  );

  if (!telegramResponse.ok) {
    return json({ ok: false, error: "Telegram sendMessage failed" }, 502);
  }

  return json({ ok: true });
});
