# Opposable-inspired UI control for Puzzles & Survival Worker

Source research: Opposable 0.5.4, package `com.getopposable.app`, analyzed statically on 2026-11-06. The research notes are kept separately in `drvladcepeche-maker/contacts`, branch `research/opposable-0.5.4`.

## Adapted mechanisms

- **Foreground application-window selection.** The worker enumerates Android application windows, prefers the focused window, then the highest layer. It does not accept a game window that is merely present in the background.
- **Launch confirmation.** `launch_app` uses `NEW_TASK | RESET_TASK_IF_NEEDED` and polls for the requested foreground application window for up to the configured timeout (default 5 seconds).
- **UI settling.** Accessibility events for the selected game are tracked even when action recording is disabled. When requested, the worker waits for the event stream to become quiet before capturing a post-action UI snapshot.
- **Semantic waits.** `wait_for_text` and `wait_for_description` wait for a matching visible game UI node, with a bounded timeout.
- **Foreground-scoped reads and taps.** `capture_ui` and semantic taps use the selected foreground application window, not an arbitrary active root.

## Scenario examples

Wait for a visible game label:

```json
{"type":"wait_for_text","text":"Daily","timeout_ms":12000}
```

Wait for an accessibility description:

```json
{"type":"wait_for_description","description":"Close","timeout_ms":5000}
```

Request a post-action check after a tap:

```json
{"type":"tap_relative","x":5000,"y":5000,"verify_after":true,"verify_timeout_ms":2500}
```

The post-action record contains the foreground package/window metadata, accessible labels, and whether game Accessibility events were observed and settled.

## Important limits

- `verify_after` is opt-in and defaults to false to preserve existing scenario timing.
- An Accessibility event becoming quiet confirms UI activity/settling, **not** that the intended game operation succeeded. The captured snapshot is evidence for the next decision.
- Puzzles & Survival may render much of its interface through Unity/canvas surfaces. Such screens can expose no useful Accessibility labels; the worker reports this rather than inventing a successful semantic verification.
- The worker remains scoped to the selected game package for semantic reads and taps. Opposable's unrestricted all-app control, local MCP server, remote relay, payment automation, and GUI-Owl model were not copied.
- No screenshot capture permission was added. The existing host-side ADB path remains the screenshot diagnostic route.
- No game purchase, premium-currency spending, or other irreversible action is automatically approved.

## Validation

The Android APK must build successfully in GitHub Actions before installation. A successful build does not prove that the new Accessibility window behavior works in the LDPlayer instance; runtime validation must confirm that the foreground package resolves to the selected game and that the event/snapshot data is useful.
