# Clock migration - 2026-10-10

## Scope and acceptance

User direction: continue migration, then perform device acceptance as one combined
phase. No APK installation, host restart/reload, feature toggles, or network/SIM
changes occurred in this batch. Build success and MT evidence are not acceptance.

Existing entry: SystemUI / status bar / clock (`status_bar_clock`). Existing
format, font, padding and status vertical-offset keys are retained.

- Status clock: single/existing format, time above date, or date above time;
  dual-line alignment, line-spacing multiplier and fixed width.
- Status, notification-center large, notification-center mini/horizontal and
  tablet date clocks: separate bold, size, horizontal padding and vertical offset.
- Optional large-clock format synchronization and tablet-date hiding.
- No changes to unrecognized clock IDs or horizontal-clock native formatting.

All added choices/sliders use the existing Miuix configuration rows. The feature
stays disabled by default. Stored status offset 12 still means zero movement;
new role offsets use signed half-dp values. Width 30 means native layout width,
not a forced 30 dp. Default role sizes mean retain the native size.

## Host contracts and upstream comparison

Reference: HyperCeiler `StatusBarClockNew.kt` and
`system_ui_status_bar_new_clock_indicator*.xml`. Formal host manifest:
`docs/clock-systemui-evidence.json` (SystemUI 17.03.260226.r / 202602260,
MT workspace `wot5ikcq`). Nine code/layout targets and five resource mappings
were read completely and verified; all nine recorded text hashes match the
refresh in `.workbuddy/tmp/mt-systemui/clock-next-verified.json`.

Important differences from copying the upstream hook:

1. `big_time` is a `MiuiNotificationHeaderClock` subclass of `MiuiClock`, not a
   separate time writer. Native header/theme code reapplies size and appearance;
   pre-draw reapplies owned styling to cover these writes.
2. Current Calendar exposes `format(Context, CharSequence)` and
   `setTimeInMillis(long): void`. Exact methods are used. Formatting is synchronous
   on the main thread and restores the shared calendar timestamp in `finally`.
   There is no background executor racing native calendar updates or posting
   stale formatted text after detach.
3. Native `updateTime` is retained, including content descriptions and header
   whitespace handling. Before each native update, the previous native text is
   restored; custom text is applied afterwards. Demo mode restores native state.
4. MT found three direct `updateTime` callers: `onAttachedToWindow`,
   `setClockMode(int)` and controller `updateTime$1`. These callers are deoptimized
   before installing the hook. Caller search is saved in `clock-next-clock-callers.json`.
5. Existing local large-clock formats remain independent by default. Explicit
   synchronization selects only the first status time-format line. Disabling
   synchronization preserves the previously stored large-clock pattern.
   This intentionally differs from upstream's default-sync Boolean encoding.
6. A two-line status format uses the first status and mini/date pattern lines;
   blank patterns fall back to native hour-cycle-aware time plus `M/d E`.
   Blank single-role formats retain native output. Invalid patterns fall back and
   log once instead of repeatedly tripping host-wide safe mode.

## Lifecycle and restoration review

Each recognized TextView owns its attach/pre-draw binding. Detach removes the
observer and seconds scheduling and restores only values still owned by this
hook. New native writes become the restoration baseline. Safe mode and demo mode
restore text, typeface, size, padding, translation, line configuration, width,
alignment and visibility. Native-time suppression is released in `finally`, even
if a native update throws; original exceptions are not swallowed or replayed.

Text restoration compares content rather than CharSequence identity because
TextView may wrap strings; the native CharSequence is retained. Line restoration
records line-count and pixel-height modes, avoiding invalid `minLines = -1`
restoration. Changes are applied only when values differ. Pre-draw does not
reformat text each frame. The existing shared, boundary-aligned seconds ticker
retains screen-off and safe-mode guards.

This is a bounded review, not a complete hot-reload/lifecycle audit. In particular,
host animation interaction, demo transitions and Android TextView setter behavior
still need runtime checks; pure policy tests cannot establish these behaviors.

## Tests, build and inventory

Final artifact identity, checks and exact test totals are recorded in
`docs/clock-build.json`; Gradle log: `build/clock-next-build.log`.
Seventeen new JVM tests cover role registration, legacy persistence/defaults,
range/offset units, format fallback/order/synchronization, seconds detection,
shared-calendar restoration on success/failure and wrapped-text restoration.
Full lint was not rerun; the previous 22 known errors are not claimed resolved.

The regenerated inventory has 337 upstream preference/navigation/dependency rows,
not 337 independent functions. Twenty-four additional exact clock rows now have
bounded implementation mappings. Seven older clock repair rows retain their
repair status instead of being relabeled as fully accepted. Current counts:
98 pending, 132 existing-partial needing audit, 82 bounded static implementations,
18 repairs awaiting device tests, four partial static implementations and three
other boundary-specific statuses. Navigation rows are not promoted with a page.

## Unified acceptance additions

- All five clock IDs: normal/default, separate sizes/bold/padding and offsets;
  theme/density/font-scale changes, rotation and repeated shade expansion.
- Dual-line order, alignment, spacing, fixed-width clipping and status icon space;
  12/24-hour cycles, time zone/user changes, unusual custom formats and seconds.
- Large-clock independent/sync transitions; mini/horizontal behavior; actual
  tablet-date hiding and native visibility restoration on a tablet.
- Demo mode entry/exit, screen-off/on, detach/reinflate, safe-mode restoration and
  combinations with clock-position and status-bar visibility hooks.

## Next media-background investigation

No media-background entry was added in this batch. Upstream's
`updateMediaBackground` target is absent in the current mediacontrol package;
this is not evidence that the feature itself is inapplicable.

Read-only investigation found controller `attach` emitting
`NotificationRowViewModel._onBackgroundUpdateEvents` through the media-header
injector; `MediaViewBinder$bind$1$1` is an actual consumer. Background rendering
combines notification style, keyguard state, wallpaper brightness and background
events. Its inlined combine coroutine selects `NotifEffectType`, clears the old
effect when material changes and calls the new effect from `mediaViewEffectsMap`.
`MediaViewNormalEffect`, `MediaViewBlurEffect`, `MediaViewGlassEffect` and
`MediaViewGlassFullAodEffect` write the holder's `mediaBg` background/blend/material
state. The binder preserves the pending AOD animation guard. These eight candidate
contracts are recorded in `docs/media-background-candidate-evidence.json`; they
must not promote any upstream inventory row to implemented.

Next implementation must cover the remaining keyguard variants and drawable
ownership/clear/reapply contracts, then artwork loading/cancellation and foreground
contrast. Simply writing a background after bind would leave native blend/glass
state active and allow later material updates to overwrite it.
Native foreground updates also depend on FullAOD, glass material, uiMode and tiny
screen state. Upstream AlwaysDark removes an AOD listener and permanently changes
the controller context; this behavior has not been copied.

Candidate reads/searches remain under `.workbuddy/tmp/mt-systemui/media-next-*`.
They are investigation evidence only, not a migrated or device-verified feature.
