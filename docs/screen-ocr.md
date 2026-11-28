# On-device game screen OCR

## Scope

The Android Worker extends `capture_ui` with screenshot-based OCR and a conservative text classifier. It preserves the Accessibility-tree snapshot and adds OCR text, normalized line/word bounds, pass diagnostics, and a non-actionable screen category.

## Runtime requirements

- Android 11 / API 30 or newer for `AccessibilityService.takeScreenshot()`.
- Accessibility service metadata declares `android:canTakeScreenshot="true"`.
- The APK bundles Tesseract4Android 4.9.0 (Tesseract 5.5.1) and the Russian `rus.traineddata` model.
- The model is pinned to upstream `tessdata_fast` commit `87416418657359cb625c412a48b6e1d6d41c29bd` and its SHA-256 is checked in CI.
- No camera permission or MediaProjection prompt is required for the AccessibilityService screenshot API.

On Android 10 / API 29 and lower, Accessibility labels remain available; screenshot OCR is marked `unsupported`.

## Data flow

1. Confirm that the active Accessibility window belongs to the selected Puzzles & Survival package.
2. Collect a bounded set of visible, non-editable Accessibility labels.
3. On API 30+, capture the default display using `takeScreenshot`.
4. Convert the HardwareBuffer into a software Bitmap in memory.
5. Run two independent Tesseract layout passes: `PSM_SPARSE_TEXT` and `PSM_AUTO`.
6. Keep the higher-scoring pass as the primary OCR transcript; classify against the bounded union of both pass transcripts so labels found by either layout remain available. Preserve word boxes from both passes for guarded target matching.
7. Normalize line and word bounds to the 0–10000 coordinate space.
8. Recycle the Bitmap. No screenshot image is written to local storage.

The screenshot bitmap is not sent to Supabase. OCR text, bounds, and bounded metadata are included in the Worker job result. This may include visible in-game names or other screen text; test only with screens appropriate for transmission to the configured Worker API.

## Result shape

The `screenshot_ocr` object includes:

- `status`, `engine`, `language`, `processed_on_device`;
- `image_width`, `image_height`;
- `page_segmentation` and per-pass quality diagnostics in `passes`;
- primary `text` from the higher-scoring pass and bounded `classification_text` merged from both passes;
- `lines` and `words`, each with text, pass name, normalized bounds, and OCR confidence where available.

The sibling `screen_classification` object reports a conservative keyword category, heuristic confidence, evidence, alternatives, and `actionable: false`. The classifier is observation-only and does not authorize an action.

Possible OCR statuses:

- `unsupported`: Android API below 30.
- `failed`: screenshot capture, conversion, or OCR failed. The `error` field contains a short diagnostic.
- `ok`: OCR completed; this does not guarantee that every visible label was recognized correctly.

## Guarded OCR interaction

The Worker supports `tap_ocr_word` for a single-word selector. A tap is rejected unless the target is unique, has a valid bounding box inside the game window, and meets the strict confidence threshold (55). Lower confidence is accepted only when both independent layout passes identify the same word at overlapping coordinates and satisfy the additional confidence floors. Multiple spatially distinct matches fail closed.

The Worker supports `assert_screen` as a precondition. It retries up to three times only when the classifier returns `unknown`; a confidently different category fails immediately. The guard checks category only; it does not prove the identity or state of a particular building or control.

## Failure diagnostics and privacy

Failed jobs may include a bounded text-only diagnostic trail containing the last few OCR/classifier snapshots, selected word samples, confidence values, and normalized bounds. Screenshot bitmaps are not included. Accessibility capture excludes editable fields and is limited to the selected game package. Nevertheless, OCR text can contain player names or other visible game text, so avoid capturing private chats or unrelated sensitive screens.

## Safety boundaries

- The classifier always returns `actionable: false`.
- OCR confidence is a recognition score, not semantic certainty.
- A recognized word alone is not sufficient authorization to tap: uniqueness, confidence, bounds, and active game-window checks are also required.
- No purchase, upgrade, training, march, battle, or other resource-consuming action should be part of a read-only validation scenario.
- The classifier intentionally prefers `unknown` over weak or ambiguous guesses.

## Device validation checklist

- [x] Install the exact CI APK on Android 11+ and verify its package version (Redmi 10C, Worker 0.1.3 / versionCode 4; APK SHA-256 recorded in the device checkpoint).
- [x] Confirm the Worker Accessibility service is enabled and bound.
- [x] Capture a city screen and confirm `screenshot_ocr.status = ok` and category `city`.
- [x] Capture a Shooter Camp building-details screen and confirm category `building`.
- [ ] Confirm line and word bounds are within 0–10000.
- [x] Test a unique, clearly visible OCR word target `Детали`; verify the resulting screen with fresh captures.
- [ ] Confirm ambiguous/multiple OCR matches fail without tapping.
- [x] Confirm a confidently different screen category stops the routine.
- [ ] Confirm an `unknown` classification is retried at most three times.
- [ ] Open another app and confirm the game-window guard prevents capture.
- [ ] Verify failure diagnostics contain text only, not screenshots.
- [ ] Test API 26–29 if supported; confirm OCR reports `unsupported` while Accessibility labels remain available.

## Known limitations

- The bundled model targets Russian game UI; English OCR is not covered by this model.
- OCR typos, overlays, mixed screens, small labels, and Unity-rendered text can still cause missed or incorrect recognition. The classifier sees evidence from both layout passes, but weak or conflicting evidence remains `unknown`.
- Keyword classification is a baseline, not a trained visual model; its confidence is heuristic, not a calibrated probability.
- Live validation of the latest dual-pass OCR build passed on the Redmi 10C on 2026-11-28. City and Shooter Camp building-details guards passed; a unique high-confidence `Детали` OCR target was tapped and the resulting building screen was independently confirmed. Remaining unchecked checklist items are still unverified.
