import { assertEquals } from "jsr:@std/assert";
import { validateDailyStepsPayload } from "../_shared/daily_steps.ts";

Deno.test("accepts classifier guard and OCR word steps", () => {
  assertEquals(validateDailyStepsPayload([
    { type: "assert_screen", category: "city" },
    { type: "tap_ocr_word", text: "Детали" },
    { type: "wait", duration_ms: 1000 },
    { type: "assert_screen", category: "building" },
    { type: "capture_ui" },
  ]), null);
});

Deno.test("rejects a multi-word OCR selector", () => {
  const error = validateDailyStepsPayload([{ type: "tap_ocr_word", text: "Данные здания" }]);
  assertEquals(typeof error, "string");
});

Deno.test("rejects unknown screen categories", () => {
  const error = validateDailyStepsPayload([{ type: "assert_screen", category: "purchase" }]);
  assertEquals(typeof error, "string");
});

Deno.test("rejects unsupported step types", () => {
  const error = validateDailyStepsPayload([{ type: "upgrade_building" }]);
  assertEquals(typeof error, "string");
});
