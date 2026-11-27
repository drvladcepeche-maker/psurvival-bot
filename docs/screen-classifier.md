# Puzzles & Survival screen classifier

Status: **first conservative on-device classifier; observation only**.

## Integration

`capture_ui` now adds `screen_classification` beside the existing OCR payload. The classifier runs locally from the OCR text and returns:

- `classifier`: ruleset version (`pns_keyword_rules_v1`);
- `category`: `city`, `quests`, `map`, `heroes`, `alliance`, `mail`, `events`, `battle`, or `unknown`;
- `confidence`: heuristic separation score, not a calibrated probability;
- `actionable`: always `false`;
- `reason`: empty OCR, no evidence, ambiguous/weak evidence, or matched keywords;
- `evidence`: matched keywords for the top candidate;
- `alternatives`: up to four ranked category candidates with scores and matched terms.

## Safety properties

- The classifier does not click, select, launch, dispatch, spend, collect, or mutate game state.
- A category is emitted only when the best score clears its category threshold and separates from the runner-up. Otherwise the result is `unknown`.
- OCR confidence is not interpreted as semantic certainty.
- No gameplay routine may use this field as authorization for an action. Any future action requires a separate expected-state guard, a calibrated target selector, and a post-action capture confirming the transition.

## Known limitations

This is a keyword baseline, not a trained visual model. OCR typos, language changes, overlays, mixed screens, and short labels can produce `unknown` or misclassification. It intentionally prefers `unknown` over a weak guess. Add new labels only from redacted, verified captures with game version and language recorded.

## Validation

Unit tests cover Russian city labels observed on the Redmi 10C, the observed Russian quest screen (including `Основной Квест`, `Суточный Квест`, and `Активность Альянса`), an in-game Scarlett purchase offer that must remain `unknown`, English map labels, unknown numeric/resource-only text, and empty OCR. Live-device validation should compare the classifier output with the actual screen before any action layer is considered.
