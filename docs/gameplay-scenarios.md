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
- `tap_coordinates`: physical screen coordinates in pixels, e.g. `{"type":"tap_coordinates","x":360,"y":750}` (must be calibrated for the device; do not copy coordinates between resolutions)
- `back`: Android Back action.
- `assert_screen`: capture the current game UI, run on-device OCR/classification, and stop the job unless the detected category exactly matches the expected value, e.g. `{"type":"assert_screen","category":"city"}`. Allowed categories: `city`, `quests`, `map`, `heroes`, `alliance`, `mail`, `events`, `battle`. The screen snapshot and classifier evidence are returned with the job result. This guard does not prove an individual button/action succeeded; it prevents the next step from running when the expected screen is not detected.
- `tap_ocr_word`: find one unique exact OCR word with confidence ≥55 and tap its bounding-box center; optional `offset_x_px` / `offset_y_px` calibrate the tap relative to the word. The final target must remain inside the active game window bounds captured with the screenshot; taps into Android system bars are rejected. Multiword selectors, ambiguous matches, missing boxes, low-confidence words, and out-of-window targets fail closed.

Each scenario must stop on an unexpected screen rather than blindly continue. The executor can assert a coarse screen category and capture OCR evidence, but it cannot yet prove that a specific game action succeeded; use short scenarios and confirm outcomes manually.

## Scenario 0 — connectivity smoke test

Use first after installing the APK, saving the Worker ID/token, and enabling the Accessibility service.

```json
[
  {"type":"launch_app","package_name":"com.global.ztmslg"},
  {"type":"wait","duration_ms":8000},
  {"type":"assert_screen","category":"city"}
]
```

Telegram setup:
1. In a private chat, run `/register_worker` and save the token in P&S Worker.
2. Set the routine with `/set daily_steps [{"type":"launch_app","package_name":"com.global.ztmslg"},{"type":"wait","duration_ms":8000},{"type":"assert_screen","category":"city"},{"type":"capture_ui"}]`
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


Current device note: on the Redmi 10C, the visible bottom `Квесты` label is outside the Unity accessibility window bounds (`[0,0][720,1507]` on a 720×1650 display). Do not automate that tab until the system-bar/window-inset behavior is resolved. The smoke scenario therefore stops after a verified city capture; the quest-screen classifier is validated from a manually opened screen, not yet from an automated navigation sequence.

## Scenario 2 — city screen capture and classification (read-only)

This read-only smoke scenario verifies the city screen and returns the OCR snapshot. It does not navigate through the bottom game tabs; on the current Redmi 10C build those tabs appear below the active Unity window boundary.

```json
[
  {"type":"launch_app","package_name":"com.global.ztmslg"},
  {"type":"wait","duration_ms":8000},
  {"type":"assert_screen","category":"city"},
  {"type":"capture_ui"}
]
```

Expected outcome: `assert_screen` returns `city` and `capture_ui` returns the OCR snapshot. No task is opened, no reward is claimed, and no game resources are consumed.

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

## Review and community feedback considered

- [Apple App Store reviews](https://apps.apple.com/us/app/puzzles-survival/id1517980891?platform=iphone&see-all=reviews) contain both positive comments about social/alliance play and varied events and negative reports about monetization, repetitive progression, bugs and customer support. These are individual opinions, not verified defect reports.
- [Appshunter review aggregation](https://appshunter.io/ios/app/puzzles-and-survival/id1517980891) summarizes the same mixed pattern across user-written reviews; its sentiment labels are third-party aggregation, not an official measurement.
- [Reddit beginner guide](https://www.reddit.com/r/PuzzlesandSurvival/comments/mqhtc7) emphasizes active alliances and their research/event rewards. It is an older player guide and should be treated as strategic context, not a current UI specification.

Design implications: keep routines short and auditable; prioritize repeatable low-risk checks (launch, screen navigation, reminders); do not automate purchases, premium-currency spending, battle dispatches or irreversible choices; require explicit device observation before using selectors or coordinates. Reviews cannot establish API behavior or current screen layout.
