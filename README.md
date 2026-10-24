# Puzzles & Survival Telegram Bot — cloud prototype

A minimal cloud webhook for a Telegram bot related to Puzzles & Survival. The first iteration supports `/start` and `/help`; it does **not** automate gameplay or run an emulator.

## Architecture

- Telegram sends updates to a Supabase Edge Function webhook.
- The function validates Telegram's `X-Telegram-Bot-Api-Secret-Token` header.
- The function replies through Telegram Bot API.
- No game account credentials are stored by this prototype.

## Supabase project

Project URL: `https://xevqbgjtxuveeqaiacvm.supabase.co`

Function endpoint:
`https://xevqbgjtxuveeqaiacvm.supabase.co/functions/v1/telegram-bot`

The function is deployed separately from this repository; deploy this source when making code changes.

## Required Supabase secrets

Configure these as Edge Function secrets in the Supabase dashboard (do not commit real values):

- `TELEGRAM_BOT_TOKEN` — token from @BotFather.
- `TELEGRAM_WEBHOOK_SECRET` — random secret consisting only of A-Z, a-z, 0-9, underscore, or hyphen. Use at least 32 random characters.

Never place real bot tokens or secrets in Git, issue reports, or chat messages.

## Configure Telegram webhook

After deploying the function and saving both secrets, register the webhook using Telegram Bot API `setWebhook`:

- URL: the function endpoint above.
- `secret_token`: the exact value stored as `TELEGRAM_WEBHOOK_SECRET`.
- `allowed_updates`: `["message"]`.

Perform this request privately from a trusted client; do not paste the bot token into public URLs, repository files, or logs. Verify the result with Telegram Bot API `getWebhookInfo`, then send `/start` to the bot in Telegram.

## Local development / deployment

Install the Supabase CLI and sign in, then link this repository to project ref `xevqbgjtxuveeqaiacvm`. Set the secrets through the dashboard or CLI. Deploy only this function:

```sh
supabase functions deploy telegram-bot --project-ref xevqbgjtxuveeqaiacvm --no-verify-jwt
```

The function intentionally uses `--no-verify-jwt` because Telegram does not send a Supabase user JWT. The handler instead validates Telegram's webhook secret header.

## Automated validation

GitHub Actions runs `deno check`, unit tests for webhook-secret comparison, and a live GET smoke test against the deployed Supabase function. The health check reports whether required secrets exist; it does not prove that Telegram webhook delivery or `/start` replies work.

## Current scope and limitations

- Implemented commands: `/start`, `/help`, `/status`, and `/about`; unknown commands receive a short fallback reply.
- `/status` checks the current Telegram webhook URL and pending-update count through Telegram Bot API.
- Telegram send failures are checked against both HTTP status and the Bot API JSON `ok` field; failures return HTTP 502 so Telegram can retry delivery.
- A GET request to the function endpoint returns a basic health/configuration response.
- The health response only confirms that secrets exist, not that the Telegram webhook or Bot API has been tested.
- No game automation, Android control, persistent command queue, game-account integration, admin allowlist, or 24/7 game session is implemented yet. The current deployment is a command/webhook foundation only.
- Phone-only gameplay automation is a separate component and must be tested on the device.

## Safety

Keep secrets out of source control. Do not use this prototype to bypass game protections or anti-cheat systems.
