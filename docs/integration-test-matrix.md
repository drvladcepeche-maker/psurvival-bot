# Integration test matrix: Telegram → Supabase → Android worker → game

This matrix distinguishes service health from a real end-to-end success. The public repository describes the Edge Functions and Android AccessibilityService, but a physical-device run must still be demonstrated.

## Components and contract

1. Telegram webhook: validates `X-Telegram-Bot-Api-Secret-Token`, parses message updates, and sends replies through the Telegram Bot API.
2. Supabase profile/settings: `psurvival_bot_profiles`.
3. Durable job queue: `psurvival_bot_jobs`, idempotency key scoped to Telegram user and update.
4. Worker registry: `psurvival_bot_devices`; token hashes only are stored.
5. Worker Edge Function: `claim`, `heartbeat`, `complete`, `fail`.
6. Android AccessibilityService: polls, claims one job, executes the supplied UI steps, and reports a terminal status.

## Test cases

| ID | Test | Expected result | Evidence |
|---|---|---|---|
| INT-01 | GET Telegram function endpoint | Reports configuration/webhook diagnostic; does not claim a chat roundtrip | HTTP response |
| INT-02 | Send `/start` in Telegram | Bot replies in the same chat | Actual Telegram reply |
| INT-03 | Send `/help` | Includes all implemented commands | Actual reply |
| INT-04 | Send malformed `/set daily_steps` JSON | Friendly validation error; settings unchanged | Reply + stored settings |
| INT-05 | Save a valid launch-only routine | Settings persisted and shown by `/settings` | Reply + profile row |
| INT-06 | Run `/daily` twice with distinct Telegram updates | Two intentional jobs, one per update | Queue rows |
| INT-07 | Telegram retries the same update ID | Idempotency prevents duplicate job | Single queue row for key |
| INT-08 | Worker POST without bearer token | HTTP 401 | HTTP response |
| INT-09 | Worker POST with invalid token | HTTP 401 | HTTP response |
| INT-09a | Valid token but mismatched Worker ID in request body | HTTP 403 | HTTP response |
| INT-10a | Send `/point daily_rewards 5000 5000` | Profile setting persists; invalid values rejected | Telegram reply + profile row |
| INT-10b | Send `/scenario daily_rewards` before calibration | Bot refuses to enqueue and names missing point | Telegram reply; no queue row |
| INT-10c | Calibrate point, then send `/scenario daily_rewards` | Job is queued with launch, wait, relative tap, wait steps | Telegram reply + queue payload |
| INT-10 | Registered worker claims a job | Job becomes leased/running for that worker | Queue row + event |
| INT-11 | Android executes launch + wait | Game opens; job reaches succeeded | Phone observation + `/tasks` |
| INT-12 | Invalid step fails | Job reaches failed or retry state with useful `last_error` | Queue row + event |
| INT-13 | Network lost during job | Lease/retry behavior does not silently mark success | Events + queue state |
| INT-14 | Accessibility service disabled | No claim/execution; user can diagnose locally | Device observation |
| INT-15 | Phone screen locked / app process restarted | Behavior documented; no claim of unattended reliability without testing | Device observation |

## Common fault isolation

- `/start` fails: check webhook secret, Telegram `getWebhookInfo`, function logs and sendMessage result.
- `/daily` says scenario is unconfigured: save `game_package` or `daily_steps` first.
- Job remains `queued`: verify Android app is running, token is saved, Accessibility service is enabled, and phone has network.
- Job fails at `launch_app`: verify package `com.global.ztmslg` is installed and has a launcher activity.
- Job fails at `tap_text`: the label may not be exposed through Android accessibility, may differ by language, or may not be on screen. Re-observe instead of guessing.
- Job stays `running`: inspect worker heartbeat, lease expiry, and job events. Do not submit repeated jobs until the previous job state is understood.
- HTTP 200 health response alone is not proof of Telegram delivery or in-game action.

## Current implementation caveats to verify

- Android routine execution currently reports the configured step count as `executed_steps`; this is not a semantic verification of the game outcome.
- `tap_coordinates` values are interpreted as screen pixels by the Android client. Calibrate per display resolution/orientation.
- The Android client now includes its configured Worker ID in API requests, and the server rejects a non-empty ID that does not match the authenticated token. Older clients that omit the field remain temporarily compatible; test INT-09a after deploying both updated components.
- Screen-relative actions `tap_relative` and `tap_point` use 0–10000 values scaled against current display metrics. `swipe` uses the same normalized range. `/point` stores a named point in profile settings; `/scenario` queues only a small template after required points exist.
- Built-in scenarios are entry-point taps, not verified game routines. The worker currently cannot confirm that a tap opened a screen or that a reward was claimed; confirm results on the phone and in `/tasks`.

- The Android service's current failure path can lose a failure-reporting error if the API is unavailable; inspect queue lease/retry state before assuming a job was handled.
- Device screen lock, process death, accessibility-service restarts, and intermittent network require physical-device tests.

## Release gate

Do not label a build “working end-to-end” until INT-02, INT-05, INT-08, INT-10 and INT-11 have been observed, with logs and screenshots redacted. Never place secrets in repository artifacts.
