import "jsr:@supabase/functions-js/edge-runtime.d.ts";
import { matchesSecret } from "./_shared/webhook_secret.ts";

const json = (body: unknown, status = 200) =>
  new Response(JSON.stringify(body), {
    status,
    headers: { "content-type": "application/json; charset=utf-8" },
  });


Deno.serve(async (req: Request) => {
  const url = new URL(req.url);

  if (req.method === "GET") {
    return json({
      ok: true,
      service: "telegram-bot",
      configured: Boolean(
        Deno.env.get("TELEGRAM_BOT_TOKEN") &&
        Deno.env.get("TELEGRAM_WEBHOOK_SECRET"),
      ),
      note: "Health check only; Telegram webhook delivery is not confirmed by this response.",
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
    // Do not return or log the Bot API URL, because it contains the token.
    return json({ ok: false, error: "Telegram sendMessage failed" }, 502);
  }

  return json({ ok: true });
});
