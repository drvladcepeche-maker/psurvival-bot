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
        diagnostic: description.replace(/[\\r\\n\\t]/g, " ").slice(0, 180),
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
  const message = (update as { message?: { chat?: { id?: number }; text?: string } })?.message;
  const chatId = message?.chat?.id;
  const text = typeof message?.text === "string" ? message.text.trim() : "";
  if (chatId === undefined || !text) return json({ ok: true, ignored: true });

  const commandMatch = text.match(/^\\/([a-z0-9_]+)(?:@[a-z0-9_]+)?(?:\\s|$)/i);
  const command = commandMatch?.[1]?.toLowerCase() ?? "";
  let reply: string;
  switch (command) {
    case "start":
      reply = "Бот подключён. Это облачный прототип для Puzzles & Survival. Используй /help для списка команд.";
      break;
    case "help":
      reply = "Команды бота:\\n/start — проверить подключение\\n/help — список команд\\n/status — проверить облачный вебхук\\n/about — возможности прототипа";
      break;
    case "about":
      reply = "Сейчас доступны Telegram-команды и облачный вебхук. Автоматизация игрового процесса, управление Android и выполнение игровых действий ещё не реализованы.";
      break;
    case "status": {
      const info = await telegramApi<{ url?: string; pending_update_count?: number; last_error_message?: string }>(botToken, "getWebhookInfo");
      if (!info.ok || !info.result) {
        reply = "Не удалось проверить Telegram API. Повтори попытку позже.";
      } else if (info.result.url !== WEBHOOK_URL) {
        reply = "Вебхук Telegram не совпадает с ожидаемым адресом Supabase. Требуется восстановление подключения.";
      } else {
        const pending = info.result.pending_update_count ?? 0;
        reply = `Облачный вебхук подключён.\\nОжидающих обновлений: ${pending}.\\nЭто подтверждает регистрацию вебхука, но не проверяет управление игрой.`;
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
