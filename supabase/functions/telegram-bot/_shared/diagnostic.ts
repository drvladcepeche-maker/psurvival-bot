export function sanitizeDiagnostic(value: string): string {
  return value.replace(/[\r\n\t]/g, " ").slice(0, 180);
}
