# Lockscreen charging information - 2026-10-10

## Implemented scope

New opt-in `lockscreen_charging_info` entry in SystemUI / lock screen, based on
HyperCeiler `ChargingCVP`. Three options default off: detailed current in mA,
battery temperature, and custom interval. The default interval is 3s; custom
interval supports 1-5s in 0.5s steps. Five upstream preference rows map to this
bounded implementation; they are not five separate functions.

Only the audited OS4 202602260 native vertical indication area is supported.
A module-owned detail row appears below the original bottom indication only when
the host's currently rendered indication is battery type 3, the device is awake
and locked, and the original view is attached/shown. Its text must match the host's
current indication message. Native text, font, click handler, rotation queue and
priorities are not changed. The new row copies the current native text styling,
does not intercept clicks, and does not announce periodic accessibility live
region changes. No blanket single-line removal or 8.2sp override is applied.

Dozing/AOD, flip tiny screen, unplugged, reverse charging, battery-defender and
wireless-reposition states keep native presentation. Unknown/fault battery health
and non-charging broadcast status produce no detail. Other rotating indications,
including biometric errors and device-policy disclosure, never acquire the row.
Existing `lockscreen_hide_hint` excludes neither battery nor those error types;
a regression explicitly checks this composition boundary.

## Sampling and lifecycle

- Sampling uses Android `BatteryManager.BATTERY_PROPERTY_CURRENT_NOW` (microamps)
  plus sticky `ACTION_BATTERY_CHANGED` voltage (millivolts), temperature (tenths
  Celsius), plug, status and health. No sysfs/root reads or receiver registration.
- All battery sampling runs on one worker with a two-entry bounded queue and at
  most one in-flight request per binding. Main-thread callbacks check lifecycle
  generations; hide/detach/rebind cancels queued tasks and invalidates results.
  A running Binder call may finish but cannot publish into the old session.
- Only active visible charging indications schedule ticks. Pre-draw tracks native
  indication changes and resumes without forcing an indication update. Screen-off
  or unlock is detected by native visibility/doze state or the next tick; the tick
  checks PowerManager/KeyguardManager before starting a read. No screen-off loop
  or polling of all indication controllers is installed.
- Invalidated/unsupported/out-of-range readings are omitted, not rendered as zero.
  Samples expire after the configured interval plus 2s. Vendor current sign is
  treated as magnitude rather than assuming the sign convention from upstream.
- Watts are an approximate **battery-side** current-times-voltage calculation,
  prefixed with `~`. They are not charger input/rated power. Current is from one
  API query and voltage/temperature from the latest broadcast, not an atomic sensor
  snapshot; accuracy on dual-cell hardware and sample freshness need device tests.
- Area replacement disposes the previous row/listeners. Detach removes the row,
  pre-draw listener and tasks; reattach rechecks identity before resuming. Safe mode
  removes module-owned state without rewriting the native view. Reflection or UI
  failures trip host safety; expected unavailable battery data does not.

The upstream interval key does not match its XML, and upstream integer division
loses half seconds. The local setting uses the declared interval key and multiplies
by 500ms. Old user configurations are not modified or implicitly imported.

## Current-host evidence

The upstream `getChargingHintText` method is absent in the current SystemUI DEX.
The similarly named `computePowerChargingStringIndication` is used by lowlight/
CentralSurfaces, not the normal bottom indication. Current
`updateDeviceEntryIndication(boolean)` has inlined vendor charging-text creation;
hooking either old method would miss this surface.

The new hook uses exact `KeyguardIndicationController.setIndicationArea(ViewGroup)`
and deoptimizes all three audited callers:

1. `KeyguardViewConfigurator.start()`.
2. `KeyguardIndicationAreaBinder.bind(...)`.
3. `KeyguardIndicationAreaBinder$bind$1.dispose()` (restores the previous area).

Full-DEX smali search returned those three call sites with no further pages or
skipped scopes. `showIndication(int)` confirms native message/click/text wiring;
`indicationTypeToString(3)` confirms the battery type. Layout evidence confirms the
vertical container and original single-line text views. Twenty-one code/field/
layout targets were live refreshed; see `lockscreen-charging-systemui-evidence.json`
and `.workbuddy/tmp/mt-systemui/charging-*`. Earlier `lock-next-*` files are discovery
only: the broad charging search and shortcut-class read were incomplete and are
not cited as exhaustive/verified evidence.

Bottom shortcuts were also investigated. `KeyguardBottomAreaInjector.updateIcons`
delegates to `MiuiShortcutController`, which loads a shortcut plugin. Legacy left/
right fields no longer supply the old implementation. Bottom buttons remain
pending, not declared unsupported merely because those old fields are absent.

## Verification and deployment

Build/test record: `docs/lockscreen-charging-build.json`.
Gradle log: `build/lockscreen-charging-build.log`.
Live evidence refresh: `build/lockscreen-charging-evidence.log`.
Tests cover units, signed current, invalid/missing/fault/stale values, locale,
fractional intervals, gating, single-flight/stale completion, settings registration,
parent-option gating, hint-filter composition and exact hooked/caller signatures.
The initial build found two expected fixed-catalog assertions that needed updating
for the new entry; they were updated, not disabled.

JVM tests do not exercise Android Binder, View layout, the worker/Handler runtime,
reflection injection or device batteries. Full lint is not rerun; no claim that
the previous 22 lint findings are fixed.

No device install, restart, hot reload, function/scope/SIM change, commit or push
is performed. Device deployment remains the 15:43 installation. The local Release
contains the earlier media continuation plus this charging work and is not yet
deployed. Final acceptance remains one combined phase after migration.

## Remaining migration count

337 inventory rows include parameters/navigation/dependencies, not 337 functions.
After this change: 89 pending, 132 existing partial implementations awaiting audit,
7 partial-static implementations, 1 partial awaiting Settings host evidence,
18 static repairs awaiting device acceptance, 88 bounded static implementations,
and two special covered/native-supported rows. A feature-level deduplicated total
has not been established; a percentage would imply false precision.

The 89 pending rows split into control-center main/old-layout 17, card tiles 2,
media 8, other tiles 20, lockscreen 9, navigation 14, other SystemUI 19. Existing
partial statusbar/island work is tracked separately in the 132 audit rows. Media
palette/foreground animation/ambient and full progress styles, shortcut plugins,
AOD and WMShell still need further work.

Combined acceptance must cover wired/wireless/full/unplugged/fault/unsupported
readings, rapid indication rotation, errors and disclosure messages, large font/
small screen clipping, theme changes, lock/unlock/AOD/occlusion, repeated area
replacement and detach/reattach, parent/option defaults, custom 1.5s interval,
safe-mode cleanup, accessibility and battery/CPU impact. No per-batch test request.
