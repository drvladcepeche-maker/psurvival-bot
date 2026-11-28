const SCREEN_CATEGORIES = new Set([
  "city", "building", "quests", "map", "heroes", "alliance", "mail", "events", "battle",
]);

export function validateDailyStepsPayload(value: unknown): string | null {
  if (!Array.isArray(value) || value.length < 1 || value.length > 30) {
    return "daily_steps должен быть JSON-массивом из 1–30 шагов.";
  }

  for (const [index, step] of value.entries()) {
    if (!step || typeof step !== "object" || Array.isArray(step)) {
      return `Шаг ${index + 1}: ожидается JSON-объект.`;
    }
    const item = step as Record<string, unknown>;
    switch (item.type) {
      case "wait":
        if (typeof item.duration_ms !== "number" || !Number.isInteger(item.duration_ms) || item.duration_ms < 250 || item.duration_ms > 10000) {
          return `Шаг ${index + 1}: duration_ms должен быть целым числом 250–10000.`;
        }
        break;
      case "back":
      case "capture_ui":
        break;
      case "tap_text":
        if (typeof item.text !== "string" || !item.text.trim() || item.text.length > 120) return `Шаг ${index + 1}: нужен text длиной до 120 символов.`;
        break;
      case "tap_ocr_word":
        if (typeof item.text !== "string" || !item.text.trim() || item.text.length > 120 || /\s/.test(item.text.trim())) {
          return `Шаг ${index + 1}: tap_ocr_word требует одно слово длиной до 120 символов.`;
        }
        break;
      case "assert_screen":
        if (typeof item.category !== "string" || !SCREEN_CATEGORIES.has(item.category.trim().toLowerCase())) {
          return `Шаг ${index + 1}: assert_screen требует допустимую категорию экрана.`;
        }
        break;
      case "tap_description":
        if (typeof item.description !== "string" || !item.description.trim() || item.description.length > 120) return `Шаг ${index + 1}: нужен description длиной до 120 символов.`;
        break;
      case "tap_coordinates":
      case "tap_relative":
        if (!Number.isInteger(item.x) || !Number.isInteger(item.y) || (item.x as number) < 0 || (item.y as number) < 0 || (item.x as number) > 10000 || (item.y as number) > 10000) {
          return `Шаг ${index + 1}: x и y должны быть целыми координатами 0–10000.`;
        }
        break;
      case "tap_point":
        if (typeof item.name !== "string" || !/^[a-z0-9_]{1,40}$/.test(item.name)) return `Шаг ${index + 1}: tap_point требует name.`;
        if (!Number.isInteger(item.x) || !Number.isInteger(item.y) || (item.x as number) < 0 || (item.y as number) < 0 || (item.x as number) > 10000 || (item.y as number) > 10000) {
          return `Шаг ${index + 1}: tap_point требует x и y в диапазоне 0–10000.`;
        }
        break;
      case "swipe":
        for (const coordinate of ["start_x", "start_y", "end_x", "end_y"]) {
          if (!Number.isInteger(item[coordinate]) || (item[coordinate] as number) < 0 || (item[coordinate] as number) > 10000) return `Шаг ${index + 1}: swipe требует координаты 0–10000.`;
        }
        if (item.duration_ms !== undefined && (!Number.isInteger(item.duration_ms) || (item.duration_ms as number) < 100 || (item.duration_ms as number) > 10000)) return `Шаг ${index + 1}: duration_ms должен быть 100–10000.`;
        break;
      case "launch_app":
        if (typeof item.package_name !== "string" || !/^[A-Za-z0-9_]+(?:\.[A-Za-z0-9_]+)+$/.test(item.package_name)) {
          return `Шаг ${index + 1}: package_name должен быть идентификатором Android-пакета.`;
        }
        break;
      default:
        return `Шаг ${index + 1}: неизвестный тип. Допустимы wait, back, tap_text, tap_ocr_word, tap_description, tap_coordinates, tap_relative, tap_point, swipe, capture_ui, assert_screen, launch_app.`;
    }
  }
  return null;
}
