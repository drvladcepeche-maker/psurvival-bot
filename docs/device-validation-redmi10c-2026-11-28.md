# Redmi 10C device validation — 2026-11-28

Status: **validated on device with Worker 0.1.3**.

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
