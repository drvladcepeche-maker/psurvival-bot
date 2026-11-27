# Puzzles & Survival screen classifier

Status: **first conservative on-device classifier; observation only**.

## OCR engine

The Android worker uses Tesseract4Android 4.9.0 (Tesseract 5.5.1) with the Russian `tessdata_fast` model. The model is pinned to upstream commit `87416418657359cb625c412a48b6e1d6d41c29bd` and verified by SHA-256 `e16e5e036cce1d9ec2b00063cf8b54472625b9e14d893a169e2b0dedeb4df225` in GitHub Actions before APK assembly. It is copied to the app-private `files/tesseract/tessdata` directory and processed locally; screenshots are not sent to a remote OCR service. The current model targets the Russian game UI. English-language game UI is not yet covered by the bundled model.

## Integration

`capture_ui` now adds `screen_classification` beside the existing OCR payload. The classifier runs locally from the OCR text and returns:

- `classifier`: ruleset version (`pns_keyword_rules_v1`);
- `category`: `city`, `building`, `quests`, `map`, `heroes`, `alliance`, `mail`, `events`, `battle`, or `unknown`;
- `confidence`: heuristic separation score, not a calibrated probability;
- `actionable`: always `false`;
- `reason`: empty OCR, no evidence, ambiguous/weak evidence, or matched keywords;
- `evidence`: matched keywords for the top candidate;
- `alternatives`: up to four ranked category candidates with scores and matched terms.

## Safety properties

- The classifier does not click, select, launch, dispatch, spend, collect, or mutate game state.
- A category is emitted only when the best score clears its category threshold and separates from the runner-up. Otherwise the result is `unknown`.
- OCR confidence is not interpreted as semantic certainty.
- A gameplay routine must not treat a category as authorization by itself. The Android worker now supports an explicit `assert_screen` step: it captures the current game UI and stops the job unless the classifier returns the requested category. A routine still needs a calibrated target selector and a post-action capture confirming the transition; the guard only verifies the screen category.

## Known limitations

This is a keyword baseline, not a trained visual model. OCR typos, language changes, overlays, mixed screens, and short labels can produce `unknown` or misclassification. It intentionally prefers `unknown` over a weak guess. Add new labels only from redacted, verified captures with game version and language recorded.

## Validation

Unit tests cover Russian city labels observed on the Redmi 10C, the observed Headquarters building-details screen, the observed Russian quest screen (including `Основной Квест`, `Суточный Квест`, and `Активность Альянса`), an in-game Scarlett purchase offer that must remain `unknown`, English map labels, unknown numeric/resource-only text, and empty OCR. Live-device validation should compare the classifier output with the actual screen before any action layer is considered.
