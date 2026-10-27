import { buildScenario, listScenarios } from "./scenarios.ts";

type Ctx = {
  command: string;
  text: string;
  telegramUserId: number | undefined;
  chatId: number;
  updateId: number;
  serviceKey: string | null;
  readProfileSettings: (id: number) => Promise<Record<string, unknown>>;
  databaseRequest: <T>(path: string, method?: string, body?: unknown, prefer?: string) => Promise<T>;
  enqueueDailyJob: (uid: number, chatId: number, updateId: number, steps: unknown[]) => Promise<{ id: string; status: string }>;
};

export async function handleExtraCommands(ctx: Ctx): Promise<string | null> {
  const { command, text, telegramUserId, chatId, updateId } = ctx;
  if (command === "point") {
    if (telegramUserId === undefined || !ctx.serviceKey) return "Профиль недоступен.";
    const pa = text.replace(/^\/point(?:@[a-z0-9_]+)?\s*/i, "").trim().split(/\s+/);
    if (pa.length < 3) return "Формат: /point имя x y";
    const pn = pa[0], px = Number(pa[1]), py = Number(pa[2]);
    if (!/^[a-zA-Z0-9_]{1,40}$/.test(pn) || !Number.isInteger(px) || !Number.isInteger(py) || px < 0 || py < 0 || px > 10000 || py > 10000) {
      return "Имя [a-zA-Z0-9_]{1,40}; x,y целые 0–10000.";
    }
    try {
      const st = await ctx.readProfileSettings(telegramUserId);
      const points = (st.points && typeof st.points === "object" && !Array.isArray(st.points))
        ? { ...(st.points as Record<string, unknown>) }
        : {};
      points[pn] = { x: px, y: py };
      st.points = points;
      await ctx.databaseRequest(`psurvival_bot_profiles?telegram_user_id=eq.${telegramUserId}`, "PATCH", {
        settings: st,
        updated_at: new Date().toISOString(),
      });
      return `Точка «${pn}»=(${px},${py}). В сценарии: {"type":"tap_point","name":"${pn}"}`;
    } catch {
      return "Не удалось сохранить точку.";
    }
  }
  if (command === "scenarios") return listScenarios();
  if (command === "scenario") {
    if (telegramUserId === undefined || !ctx.serviceKey) return "Профиль недоступен.";
    const sn = text.replace(/^\/scenario(?:@[a-z0-9_]+)?\s*/i, "").trim().toLowerCase();
    if (!sn) return "Укажи: launch|daily|alliance|tavern|mail";
    try {
      const st = await ctx.readProfileSettings(telegramUserId);
      const pkg = typeof st.game_package === "string" ? st.game_package : "com.global.ztmslg";
      const pts = (st.points && typeof st.points === "object" && !Array.isArray(st.points))
        ? (st.points as Record<string, { x?: number; y?: number }>)
        : {};
      const built = buildScenario(sn, pkg, pts);
      if (!built) return "Неизвестный сценарий.";
      if (built.missing.length) {
        return "Нужна калибровка:\n" + built.missing.map((n) => "• /point " + n + " <x> <y>").join("\n");
      }
      const job = await ctx.enqueueDailyJob(telegramUserId, chatId, updateId, built.steps);
      return `Сценарий «${sn}» в очереди (${built.steps.length} шагов). ${job.id.slice(0, 8)}… ${job.status}. /tasks`;
    } catch {
      return "Не удалось поставить сценарий.";
    }
  }
  return null;
}
