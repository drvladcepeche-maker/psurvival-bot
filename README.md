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

## Required Supabase secrets

Configure these as Edge Function secrets in the Supabase dashboard (do not commit real values):

- `TELEGRAM_BOT_TOKEN` — token from @BotFather.
- `TELEGRAM_WEBHOOK_SECRET` — random secret consisting only of A-Z, a-z, 0-9, underscore, or hyphen. Use at least 32 random characters.\n- `TELEGRAM_OWNER_USER_ID` — numeric Telegram user ID allowed to start and inspect the server-side contacts resolver. This is mandatory; `/telegram_match` and `/telegram_status` are denied unless the caller matches this ID and uses a private chat.

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

- Implemented commands: `/start`, `/help`, `/status`, `/settings`, `/tasks`, `/daily`, `/register_worker`, `/rotate_worker`, `/telegram_match`, `/telegram_status`, `/myid`, and `/about`.\n- `/myid` returns the caller's numeric Telegram ID in a private chat so the owner can configure `TELEGRAM_OWNER_USER_ID`.\n- `/telegram_match` is owner-only (configure `TELEGRAM_OWNER_USER_ID`), reads phone numbers from `contact_bridge_snapshots`, skips phone numbers already checked for the same contact, and enqueues batches in `telegram_match_jobs`. The main Android phone may be offline after its contacts have synced.\n- `/telegram_status` is owner-only and reports the latest resolver job progress. The separate Hostless MTProto worker performs the lookups and updates contact snapshots; deploy it from the `contacts` repository. The worker never imports numbers into Telegram contacts.
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


## Sherlock username search integration

The bot includes a separate Sherlock queue and Python worker. It checks public profile URLs for submitted usernames; it does not identify account owners or access private profiles.

Telegram commands (private chat):
- `/sherlock <username>` — enqueue a username search. Up to five usernames may be supplied, separated by spaces.
- `/sherlock_status` — show the latest five search jobs.
- `/sherlock_history` — show the latest ten jobs.

The search queue is isolated in `public.sherlock_jobs`; it does not use the Android game-worker queue or the contacts resolver. Jobs are idempotent per Telegram update. The worker omits NSFW sites by default and preserves inconclusive/WAF outcomes separately from found profiles.

### Supabase migration

Apply `supabase/migrations/20261101010000_sherlock_search_queue.sql` to project `xevqbgjtxuveeqaiacvm` before deploying the updated Telegram Edge Function.

### Python worker deployment

The worker runs as a scheduled GitHub Actions job; a separate paid hosting service is not required.

- `.github/workflows/sherlock-worker.yml` starts the queue worker every five minutes and also supports manual dispatch.
- The worker installs the pinned `sherlock-project==0.16.2` package and processes queued jobs until the queue is empty.
- `sherlock-worker-gateway` is a Supabase Edge Function that accepts only GitHub Actions OIDC tokens from this repository and the approved branches.
- The gateway uses the Supabase service-role key and Telegram bot token stored as Supabase secrets. Neither secret is exposed to GitHub Actions or committed to the repository.
- The GitHub Actions workflow requires `id-token: write`; do not add a service-role key or bot token to GitHub repository variables or source files.

The gateway is deployed to project `xevqbgjtxuveeqaiacvm`. The scheduled worker becomes active on the default branch after this change is merged. A push to the integration branch also starts a worker run for validation. The workflow uses a public GitHub-hosted runner, so no payment card or phone number is needed for a separate worker host.

## Safety

Keep secrets out of source control. Do not use this prototype to bypass game protections or anti-cheat systems.


## Game scenario research and integration verification

- [Gameplay scenario catalogue](docs/gameplay-scenarios.md) — source-backed player workflows and conservative UI templates; exact labels must be checked on the current game build.
- [Integration test matrix](docs/integration-test-matrix.md) — end-to-end checks for Telegram, Supabase queue, Android worker and game launch.

These documents do not claim that a physical-device test has already passed. The current worker executes UI steps but cannot semantically verify game state or reliably infer screens from visual content.
