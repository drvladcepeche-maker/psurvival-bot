import { assertEquals } from "jsr:@std/assert";
import { matchesSecret } from "../../_shared/webhook_secret.ts";

Deno.test("accepts identical webhook secrets", () => {
  assertEquals(matchesSecret("long_random_secret_123", "long_random_secret_123"), true);
});

Deno.test("rejects a different secret", () => {
  assertEquals(matchesSecret("long_random_secret_123", "long_random_secret_124"), false);
});

Deno.test("rejects an empty supplied secret", () => {
  assertEquals(matchesSecret("long_random_secret_123", ""), false);
});

Deno.test("rejects a secret with a different length", () => {
  assertEquals(matchesSecret("abc", "abcd"), false);
});
