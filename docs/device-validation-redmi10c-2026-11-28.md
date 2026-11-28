# Redmi 10C device validation — 2026-11-28

Status: **validated on device with Worker 0.1.3; dual-layout OCR follow-up passed on 2026-11-28**.

## Environment

- Device: Redmi 10C, Android 13 / SDK 33
- Game package: `com.global.ztmslg`
- Display: 720×1650
- Worker package: `com.psurvival.worker`, version 0.1.3
- Accessibility service: enabled and bound
- Transport: USB ADB to Debian Irbis
- Game UI language: Russian

## Confirmed read-only flow: Shooter Camp → Details

The old Scenario 2 coordinate `(360,430)` was incorrect for the current city layout: it opened the Watchtower, not the Shooter Camp. Do not reuse it.

The quest-card red arrow at `(50,1450)` was also tested. It activated a navigation marker over Headquarters and did not reliably select the Shooter Camp. It is not a target-building selector.

From a fresh screenshot of the current city at 720×1650, the Shooter Camp was visible in the central lower area and its level badge showed 13. The following Worker jobs were executed sequentially, each with `max_attempts=1` and no other queued/running job:

1. `7ebb2d04-8376-4e5d-abe7-20aff56303d9` — launch game, assert `city`, tap the visible Shooter Camp at `(350,830)`, wait, capture. Result: `succeeded`. The building menu identified the object as Shooter Camp.
2. `da9cdc6d-fc1f-43b3-8703-e38533bb31ac` — launch game, assert `city`, OCR tap `Детали`, wait, capture. Result: `succeeded`. OCR confidence for `Детали` was 79.68%, above the Worker safety threshold of 55%.

The final screen was classified as `building` by `pns_keyword_rules_v1`, confidence 0.87. OCR evidence included:

- `Данные здания`
- `Тут обучаются Стрелки. Улучшите здание, чтобы открыть более продвинутых юнитов.`
- `Эффект`
- `Уровень`
- `Разблокирует`
- troop names `Арбалетчик`, `Пистолетчик`, `Стрелец`

This confirms that the selected object is the Shooter Camp, not the Watchtower. The visible city-level badge showed level 13; the details panel also exposed troop unlock information.

## Safe execution constraints and outcome

- No tap was sent to `Улучшить`.
- No troop training, speedup, purchase, premium-currency action, march, battle, or other resource-consuming action was performed.
- No resources or diamonds were spent.
- Both jobs completed on the first attempt.
- Supabase device remained enabled and heartbeating; final queue check reported zero queued/running jobs.

## Calibrated sequence for future read-only inspection

For this exact Redmi 10C layout only:

```json
[
  {"type":"launch_app","package_name":"com.global.ztmslg"},
  {"type":"wait","duration_ms":2500},
  {"type":"assert_screen","category":"city"},
  {"type":"tap_coordinates","x":350,"y":830},
  {"type":"wait","duration_ms":1000},
  {"type":"tap_ocr_word","text":"Детали"},
  {"type":"wait","duration_ms":1000},
  {"type":"assert_screen","category":"building"},
  {"type":"capture_ui"}
]
```

The coordinate is valid only for the observed 720×1650 layout and current city composition. Always inspect a fresh screenshot before reusing it; the game map can shift. Keep the OCR confidence threshold unchanged. Never append an `Улучшить` tap to a read-only scenario.

## Next validation target

Parse the building details into structured fields (building name, current level, effect, unlock thresholds) and compare the OCR output across two clean captures. Do not trigger troop training or building upgrades during this read-only phase.


## OCR repeatability — four consecutive captures

A controlled job was executed while the Shooter Camp Details panel was open:

- Job: `34d7ac9d-8f3b-40e4-a972-8cde9870eaee`
- Steps: `assert_screen(building) → capture_ui → wait 1500 ms → assert_screen(building) → capture_ui`
- Attempts: 1; terminal status: `succeeded`; executed steps: 5; returned UI snapshots: 4 (two screen guards plus two explicit captures)
- All four snapshots reported OCR `status=ok`, screen category `building`, and classifier confidence `0.92`.
- All four OCR texts consistently contained `Данные здания`, the Shooter Camp description, `Уровень`, `Разблокирует`, and the three troop names.
- The unlock table repeatedly associated level 1 with `Арбалетчик`, level 4 with `Пистолетчик`, and level 7 with `Стрелец`.

The remaining table rows were OCR'd inconsistently (for example, isolated digits 2, 3, 5, 6, and 8); their meaning is not established. The `Эффект` value is also not reliably legible in these captures. Do not infer either field from this test.

No UI gestures beyond read-only capture/guards were performed. No resources or premium currency were spent. The final queue check returned zero queued/running jobs.


## Follow-up: dual-layout OCR acceptance — 2026-11-28

The earlier validation above records the initial OCR build and its original navigation experiments. The run #192 dual-layout OCR build was installed later and independently passed the following acceptance checks:

- APK identity: installed package SHA-256 `6796724a9698080bd88d0380414c3a7fe28a25c02befa05d6e235cf50f7b97ad`; package version 0.1.3 / versionCode 4.
- Russian model: on-device `rus.traineddata` SHA-256 `e16e5e036cce1d9ec2b00063cf8b54472625b9e14d893a169e2b0dedeb4df225`.
- Accessibility: `WorkerAccessibilityService` enabled and bound after the user re-confirmed MIUI's post-reinstall sensitive-permission warning.
- Worker transport: fresh Supabase heartbeat and successful queue claim/complete cycle.
- City screen: job `52b669ba-d69c-4b4d-aa33-c45170ee7d7d` completed successfully; `assert_screen(city)` passed at heuristic confidence 0.74 using merged OCR evidence from both layout passes.
- Building details: job `a959ef18-a2f7-42bd-894c-9a23465c88de` completed successfully with four executed steps. The unique OCR target `Детали` was recognized at 95.82% confidence and tapped; subsequent snapshots classified `building` at confidence 0.92. A physical screenshot confirmed the `Данные здания` panel for `Лагерь Стрелков`.
- Fail-closed behavior: the Scarlett purchase offer was classified `unknown` and dismissed using Android Back. An earlier low-confidence `Детали` OCR hit was rejected rather than tapped; no confidence threshold was lowered.
- Safety: no purchase, upgrade, troop training, troop movement, battle, speedup, or resource expenditure occurred.

CI run #192 (workflow run ID `46107260786`) passed both jobs: server validation and Android build/unit tests. This confirms the run #192 branch commit; the physical-device evidence above confirms the exact APK artifact installed from that run.

Still unverified: multiple distinct OCR matches are rejected in all edge cases; screen-lock recovery; process-death/accessibility-service restart recovery; prolonged network-loss recovery; unrelated-app capture isolation; and OCR behavior on Android API 26–29. Do not treat these as passed release gates.
