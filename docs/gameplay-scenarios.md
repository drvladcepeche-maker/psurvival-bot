# Puzzles & Survival: bot scenario catalogue

Status: **draft for device validation**. These are UI automation templates, not a claim that every button label or screen layout is verified on the current game build.

## Evidence used

- [Puzzles and Survival Office Wiki — Beginners' Guide](https://puzzles-and-survival-office.fandom.com/wiki/Beginners%27_Guide_PnS%3A_How_TO_Start.): buildings, research, troop training, resource gathering, alliance/event tasks.
- [MrGuider — Puzzles & Survival Guide](https://www.mrguider.org/guides/puzzles-and-survival-guide/): heroes, troop types, gathering, daily/alliance quests and research.
- [Puzzles & Survival Tools & Guides — troop types and formations](https://puzzlesandsurvival.com/2025/04/07/the-difference-in-troop-types/): rally troop-composition guidance.
- [Puzzles & Survival community bot changelog](https://t.me/s/puzzle_survival?before=61): examples of existing automation categories such as routine loops, gathering, events and daily tasks. This is a third-party automation project, not an official game specification.

These sources describe strategy and player workflows; they do not provide a stable machine-readable game API or guarantee exact UI labels. Accordingly, the initial scenarios use the supported Android UI step format and should be tuned from a live screen recording/accessibility tree. No game-server API, anti-cheat bypass, credential harvesting, or hidden endpoint is assumed.

## Supported step schema

The current worker accepts:
- `launch_app`: `{"type":"launch_app","package_name":"com.global.ztmslg"}`
- `wait`: `{"type":"wait","duration_ms":3000}`
- `tap_text`: exact/partial visible accessibility text, e.g. `{"type":"tap_text","text":"Daily"}`
- `tap_description`: content description, e.g. `{"type":"tap_description","description":"Close"}`
- `tap_coordinates`: physical screen coordinates in pixels, e.g. `{"type":"tap_coordinates","x":540,"y":1800}` (must be calibrated for the device; do not copy coordinates between resolutions)
- `back`: Android Back action.

Each scenario must stop on an unexpected screen rather than blindly continue. The present executor cannot yet detect a specific screen or verify that a game action succeeded; use short scenarios and confirm outcomes manually.

## Scenario 0 — connectivity smoke test

Use first after installing the APK, saving the Worker ID/token, and enabling the Accessibility service.

```json
[
  {"type":"launch_app","package_name":"com.global.ztmslg"},
  {"type":"wait","duration_ms":5000}
]
```

Telegram setup:
1. In a private chat, run `/register_worker` and save the token in P&S Worker.
2. Set the routine with `/set daily_steps [{"type":"launch_app","package_name":"com.global.ztmslg"},{"type":"wait","duration_ms":5000}]`
3. Run `/daily`, then `/tasks`.
4. Confirm the game opens on the phone and the task becomes `succeeded`. If it remains queued, check worker registration/network/service status. If it fails, inspect the task's `last_error`.

## Scenario 1 — open game and wait for home screen

```json
[
  {"type":"launch_app","package_name":"com.global.ztmslg"},
  {"type":"wait","duration_ms":8000}
]
```

Use this as the prerequisite for every other routine. The delay is a starting value; adjust it after observing startup/login time. Do not automate login credentials. If the game shows a login, update, permission, or purchase screen, complete it manually and rerun the smoke test.

## Scenario 2 — daily-task screen inspection (semi-automatic)

Template only. Replace `Daily` with the exact accessible label shown on the current game build. Do not enable unattended repetition until each step is verified.

```json
[
  {"type":"launch_app","package_name":"com.global.ztmslg"},
  {"type":"wait","duration_ms":8000},
  {"type":"tap_text","text":"Daily"},
  {"type":"wait","duration_ms":1500}
]
```

Expected outcome: the daily-task screen is opened for inspection. This does **not** claim to collect rewards or complete tasks. If the tap fails, capture the visible screen and accessibility labels, then update the selector rather than guessing coordinates.

## Scenario 3 — resource gathering preparation (manual confirmation required)

Player guides recommend gathering with available marches and gathering-oriented heroes, and checking that marches return before sending them again. The current worker has no map recognition, march-state detection, or resource-tile classifier, so a reliable end-to-end gather loop is not yet implementable.

Safe initial routine: launch the game and wait. Perform map/tile selection manually, record the observed accessibility labels, and automate only a single confirmed tap at a time. Do not run a loop that may repeatedly send troops or consume speedups without state checks.

## Scenario 4 — alliance/rally preparation (no autonomous dispatch)

Troop composition depends on formation and rally lead; published community guides explicitly warn that the wrong troop type can increase losses. The bot must not infer rally formation from a generic screen. Keep troop selection and rally dispatch manual until the executor can read and validate the rally leader's formation and troop tier.

## Scenario 5 — research/building reminder workflow

Use the bot as a queue and reminder mechanism, not as an unverified click macro. The player checks building prerequisites, resource cost, research duration and event scoring in-game; only then can a separately verified UI sequence be authored. Avoid spending premium currency, using speedups, training troops, healing, or starting an attack automatically.

## Validation record template

For every added UI routine, record:
- game version, Android version, display resolution and UI language;
- start screen and expected end screen;
- exact step JSON;
- selector type and exact label/coordinates;
- observed result, task status and any `last_error`;
- whether the routine was tested twice from a clean starting state.

Do not commit raw logcat, screenshots containing account identifiers, APKs, tokens, cookies, or unredacted network captures.
