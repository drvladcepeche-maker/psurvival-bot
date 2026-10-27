/** Built-in P&S scenario templates. Points resolved on the server before enqueue. */
export type PointMap = Record<string, { x?: number; y?: number }>;

export function listScenarios(): string {
  return "Сценарии: launch, daily, alliance, tavern, mail. Запуск: /scenario launch. Калибровка: /point daily_open x y";
}

export function buildScenario(
  name: string,
  pkg: string,
  pts: PointMap,
): { missing: string[]; steps: unknown[] } | null {
  const need = (...ns: string[]) =>
    ns.filter((n) => !pts[n] || !Number.isInteger(pts[n].x) || !Number.isInteger(pts[n].y));
  const c = (n: string) => ({ type: "tap_coordinates", x: pts[n].x, y: pts[n].y });
  const launch = { type: "launch_app", package_name: pkg };
  const w = (ms: number) => ({ type: "wait", duration_ms: ms });
  switch (name) {
    case "launch":
      return { missing: [], steps: [launch, w(8000)] };
    case "daily":
      return {
        missing: need("daily_open", "daily_claim"),
        steps: [launch, w(8000), c("daily_open"), w(2000), c("daily_claim"), w(1500)],
      };
    case "alliance":
      return {
        missing: need("alliance_open", "alliance_help"),
        steps: [launch, w(8000), c("alliance_open"), w(2000), c("alliance_help"), w(1500)],
      };
    case "tavern":
      return {
        missing: need("tavern_open", "tavern_free_summon"),
        steps: [launch, w(8000), c("tavern_open"), w(2000), c("tavern_free_summon"), w(2000)],
      };
    case "mail":
      return {
        missing: need("mail_open", "mail_claim"),
        steps: [launch, w(8000), c("mail_open"), w(2000), c("mail_claim"), w(1500)],
      };
    default:
      return null;
  }
}
