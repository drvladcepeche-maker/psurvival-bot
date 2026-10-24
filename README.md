# Puzzles & Survival Telegram Bot — cloud prototype

A minimal cloud webhook for a Telegram bot related to Puzzles & Survival. The first iteration supports `/start` and `/help`; it does **not** automate gameplay or run an emulator.

## Architecture

- Telegram sends updates to a Supabase Edge Function webhook.
- The function validates Telegram's `X-Telegram-Bot-Api-Secret-Token` header.
- The function replies through Telegram Bot API.
- No game account credentials are stored by this prototype.

## Supabase project

Project URL: `https://xevqbgjtxuveeqaiacvm.supabase.co`

Telegram endpoint:
`https://xevqbgjtxuveeqaiacvm.supabase.co/functions/v1/telegram-bot`

Worker API endpoint:
`https://xevqbgjtxuveeqaiacvm.supabase.co/functions/v1/psurvival-worker`

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

Install the Supabase CLI and sign in, then link this repository to project ref `xevqbgjtxuveeqaiacvm`. Set the secrets through the dashboard or CLI. Deploy both functions:

```sh
supabase functions deploy telegram-bot --project-ref xevqbgjtxuveeqaiacvm --no-verify-jwt
supabase functions deploy psurvival-worker --project-ref xevqbgjtxuveeqaiacvm --no-verify-jwt
```

The Telegram function intentionally uses `--no-verify-jwt` because Telegram does not send a Supabase user JWT; it validates Telegram's webhook secret header. The worker API uses per-device bearer tokens hashed in the database and checks device ownership before claiming jobs.

## Automated validation

GitHub Actions type-checks both Edge Functions, runs unit tests for webhook-secret comparison, verifies the Telegram webhook, and checks that the worker API health endpoint is configured and rejects unauthenticated POST requests.

## Current scope and limitations

- Implemented commands: `/start`, `/help`, `/status`, `/settings`, `/tasks`, `/daily`, `/register_worker`, `/rotate_worker`, and `/about`.
- Telegram user profiles are persisted in `psurvival_bot_profiles`; settings storage is ready for additional user-configurable fields.
- `/daily` enqueues a durable `daily_routine` job with an idempotency key, so Telegram retries do not create duplicate tasks.
- `/tasks` reads the most recent tasks from Supabase.
- `/register_worker` issues a private-chat worker token; `/rotate_worker` invalidates the previous token by replacing its hash.
- Queue schema: `psurvival_bot_jobs`; executor registry: `psurvival_bot_devices`; event log: `psurvival_bot_job_events`.
- The worker API supports authenticated `claim`, `heartbeat`, `complete`, and `fail` operations with leases and retry scheduling.
- Job claims are scoped to the Telegram user who owns the worker.
- Telegram send failures are checked against both HTTP status and the Bot API JSON `ok` field; failures return HTTP 502 so Telegram can retry delivery.
- A GET request to the function endpoint returns a basic health/configuration response.
- The health response only confirms that secrets exist, not that the Telegram webhook or Bot API has been tested.
- The persistent database and server-side worker protocol are deployed, but no Android client is present yet. Therefore, queued tasks remain pending until a worker client connects.
- The current `daily_routine` task is only a queue record; it does not yet launch the game or perform in-game actions.
- A real Android executor must be developed and tested on a physical device, with explicit permissions and recovery for screen locks, app restarts, and network loss.
- Supabase Edge Functions are short-lived request handlers, not a persistent Android emulator or 24/7 gameplay host.

## Safety

Keep secrets out of source control. Do not use this prototype to bypass game protections or anti-cheat systems.
