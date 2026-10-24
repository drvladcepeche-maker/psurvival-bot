export function matchesSecret(expected: string, supplied: string): boolean {
  const expectedBytes = new TextEncoder().encode(expected);
  const suppliedBytes = new TextEncoder().encode(supplied);
  let mismatch = expectedBytes.length ^ suppliedBytes.length;
  const maxLength = Math.max(expectedBytes.length, suppliedBytes.length);

  for (let i = 0; i < maxLength; i++) {
    mismatch |= (expectedBytes[i] ?? 0) ^ (suppliedBytes[i] ?? 0);
  }

  return mismatch === 0;
}
