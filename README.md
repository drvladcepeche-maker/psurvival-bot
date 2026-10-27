# Puzzles & Survival Telegram Bot — cloud prototype

A Telegram bot backend with persistent Supabase profiles and task queue, a device-authenticated worker API, and an Android AccessibilityService worker prototype. The Android client can execute explicit UI steps supplied in a queued job; a ready-made game routine and physical-device test are not yet complete.

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

## Android worker quick start

1. Download the debug APK from the latest successful [GitHub Actions build](https://github.com/drvladcepeche-maker/psurvival-bot/actions/workflows/validate.yml).
2. Install it on the Android phone that has Puzzles & Survival. Open **P&S Worker**.
3. In a private Telegram chat with the bot, send `/register_worker`; enter the returned worker ID and token in the app.
4. Enable **P&S Worker** under Android Settings → Accessibility → Installed apps/services. Keep the service enabled and the phone online.
5. Send `/scenarios` to list the small built-in scenarios.

The available scenario templates are deliberately minimal:
- `/scenario launch` opens `com.global.ztmslg` and waits.
- `/scenario daily_rewards` opens the game, taps one calibrated screen point, and waits. It does **not** verify that a reward was claimed or navigate through additional screens.
- `/scenario alliance`, `/scenario tavern`, and `/scenario mail` similarly require a calibrated entry point; they do not automate the full feature.

Calibrate each required point while the game is open, using screen-relative values from 0 to 10000:
`/point daily_rewards 8500 2400`

Replace the example values with the actual location on the device. Then issue `/scenario daily_rewards`. To run a custom sequence, use `/set daily_steps <JSON array>` followed by `/daily`. Do not use guessed coordinates or repeat jobs if the previous job is still running.

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

- Implemented commands: `/start`, `/help`, `/status`, `/settings`, `/set`, `/tasks`, `/daily`, `/scenarios`, `/scenario`, `/point`, `/register_worker`, `/rotate_worker`, and `/about`.
- Telegram user profiles are persisted in `psurvival_bot_profiles`; settings storage is ready for additional user-configurable fields.
- `/daily` enqueues a durable `daily_routine` job with an idempotency key, so Telegram retries do not create duplicate tasks. Configure `daily_steps` first; otherwise the executor marks the job as unconfigured rather than pretending it ran.
- `/tasks` reads the most recent tasks from Supabase.
- `/register_worker` issues a private-chat worker token; `/rotate_worker` invalidates the previous token by replacing its hash.
- Queue schema: `psurvival_bot_jobs`; executor registry: `psurvival_bot_devices`; event log: `psurvival_bot_job_events`.
- The worker API supports authenticated `claim`, `heartbeat`, `complete`, and `fail` operations with leases and retry scheduling.
- Job claims are scoped to the Telegram user who owns the worker.
- Telegram send failures are checked against both HTTP status and the Bot API JSON `ok` field; failures return HTTP 502 so Telegram can retry delivery.
- A GET request to the function endpoint returns a basic health/configuration response.
- The health endpoint does not prove that a real Telegram chat received a reply; that requires a live message roundtrip.
- An Android AccessibilityService client is now in the repository. It polls the queue, sends heartbeats, executes explicit steps, and reports completion or failure. APK generation is being validated in GitHub Actions; no physical-device run has been confirmed.
- The current `daily_routine` task is only a queue record; it does not yet launch the game or perform in-game actions.
- The Android client requires manual installation, token entry, and enabling the Accessibility service in Android Settings. It has not been tested on the user's physical device, and behavior under screen locks, app restarts, and network loss remains unverified.
- Configure a routine in a private Telegram chat with `/set daily_steps [{"type":"launch_app","package_name":"com.global.ztmslg"},{"type":"wait","duration_ms":3000}]`. Supported step types are `launch_app`, `wait`, `back`, `tap_text`, `tap_description`, and `tap_coordinates`. Screen-specific actions must be tuned to the actual game UI.
- Supabase Edge Functions are short-lived request handlers, not a persistent Android emulator or 24/7 gameplay host.

## Safety

Keep secrets out of source control. Do not use this prototype to bypass game protections or anti-cheat systems.


## Game scenario research and integration verification

- [Gameplay scenario catalogue](docs/gameplay-scenarios.md) — source-backed player workflows and conservative UI templates; exact labels must be checked on the current game build.
- [Integration test matrix](docs/integration-test-matrix.md) — end-to-end checks for Telegram, Supabase queue, Android worker and game launch.

These documents do not claim that a physical-device test has already passed. The current worker executes UI steps but cannot semantically verify game state or reliably infer screens from visual content.
