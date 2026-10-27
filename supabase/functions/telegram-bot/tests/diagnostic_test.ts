import { assertEquals } from "jsr:@std/assert";
import { sanitizeDiagnostic } from "../_shared/diagnostic.ts";

Deno.test("sanitizes only control characters, preserving ordinary r and t", () => {
  assertEquals(sanitizeDiagnostic("transport rejected"), "transport rejected");
  assertEquals(sanitizeDiagnostic("TLS\r\n\terror"), "TLS   error");
});

Deno.test("limits diagnostic length", () => {
  assertEquals(sanitizeDiagnostic("x".repeat(250)).length, 180);
});
