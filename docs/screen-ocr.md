# On-device game screen OCR

## Scope

This change extends the existing `capture_ui` Worker step. It preserves the existing Accessibility-tree snapshot and adds OCR metadata from a live screenshot.

## Runtime requirements

- Android 11 / API 30 or newer for `AccessibilityService.takeScreenshot()`.
- Accessibility service metadata must declare `android:canTakeScreenshot="true"`.
- Google ML Kit Text Recognition v2 Latin model is bundled in the APK (`com.google.mlkit:text-recognition:16.0.1`).
- No camera permission or MediaProjection prompt is required for this AccessibilityService screenshot API.

On Android 10 / API 29 and lower, `capture_ui` continues to return Accessibility labels and marks screenshot OCR as `unsupported`.

## Data flow

1. Confirm that the active Accessibility window belongs to the selected Puzzles & Survival package.
2. Collect visible, non-editable Accessibility labels.
3. On API 30+, capture the default display using `takeScreenshot`.
4. Convert the HardwareBuffer into a software Bitmap in memory.
5. Run ML Kit Text Recognition locally.
6. Return recognized text and line bounding boxes normalized to the 0–10000 screen coordinate space.
7. Recycle the Bitmap. No screenshot image is written to local storage.

The screenshot bitmap is not sent to Supabase. The OCR text and line coordinates are included in the existing `capture_ui` result payload and therefore are sent to the configured Worker API when the job is completed. Keep this in mind when testing screens containing player names or chat text.

## Result shape

Existing fields remain:

- `package_name`
- `captured_at_ms`
- `node_count`
- `labels`
- `note`

New field:

```json
{
  "screenshot_ocr": {
    "status": "ok",
    "engine": "google_ml_kit_text_recognition_v2",
    "processed_on_device": true,
    "image_width": 1080,
    "image_height": 2400,
    "text": "recognized screen text",
    "lines": [
      {
        "text": "Claim",
        "bounds": {
          "left": 4200,
          "top": 8500,
          "right": 6100,
          "bottom": 8900
        },
        "confidence": 0.98
      }
    ]
  }
}
```

Possible non-success statuses:

- `unsupported`: Android API below 30.
- `failed`: screenshot capture, conversion, or OCR failed. The `error` field contains a short diagnostic.

## Safety boundaries

This change does not add screen classification, automatic tapping by OCR, or gameplay scenario execution based on OCR output. It only adds observation data to `capture_ui`. Existing job steps and server validation are unchanged.

No screenshot is persisted as a file. OCR output is returned through the existing job completion response.

## Device validation checklist

- [ ] Install the new APK on Android 11+.
- [ ] Enable the Paurvivals Accessibility service.
- [ ] Launch Puzzles & Survival and keep it in the foreground.
- [ ] Run one `capture_ui` job.
- [ ] Confirm `screenshot_ocr.status = ok`.
- [ ] Confirm OCR text contains visible screen labels.
- [ ] Confirm line bounds are within 0–10000.
- [ ] Open another app and confirm game-window guard prevents capture.
- [ ] Test a Unity screen with no Accessibility labels and verify OCR still returns text.
- [ ] Test API 26–29 if supported; confirm OCR reports `unsupported` while Accessibility labels remain available.

## Known limitations

- OCR confidence is a recognition score, not proof that a label identifies a specific game control.
- Text-only OCR is not enough to identify icons, colors, ownership markers, or game state.
- No action may be taken based solely on a recognized string. A separate classifier and post-action verification layer are required.
- Device build and live gameplay validation have not yet been run in this repository workflow.
