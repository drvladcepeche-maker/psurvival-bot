# Changes in fix/bot-scenarios-and-bridge

## Bot
- Fixed webhook diagnostic regex (no longer strips letters r/t)
- Updated /about
- New steps: capture_ui, tap_relative, swipe, tap_point
- Commands: /point, /scenarios, /scenario (launch, daily, alliance, tavern, mail)
- Modules: lib.ts, handlers.ts, scenarios.ts, runtime.ts

## Android worker
- tap_relative, swipe, tap_point, capture_ui support
- Better package-not-found error

## Package
Default game package remains com.global.ztmslg — verify with:
`pm list packages | grep -i ztm`
