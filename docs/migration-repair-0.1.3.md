# Migration repair - 0.1.3

## Deployment and acceptance

- Follow-up installation requested on 2026-10-09: `adb install -r` at 13:22
  (Asia/Shanghai) failed with `no devices/emulators found`. Device listing,
  reconnect and mDNS discovery found no target. The APK remains **not installed**;
  this does not prevent committing/pushing the source repair at the user's request.
- Source version: `0.1.3` / versionCode `4`, repairing the `beb0fda` implementation
  audited in `676b3cc`. The original audit remains a historical record.
- **No APK installation, host restart, hot reload, feature enablement, LSPosed
  scope change or device failure injection was performed in this repair.**
- The previously installed 0.1.2 APK is not repaired by editing this checkout.
  Its non-default network-speed risk still applies. Do not use it to validate
  the fix. A controlled replacement/load is a separate operation.
- Source fixes and local tests are complete for the confirmed F01-F06 defects.
  Android lifecycle/rendering/audio acceptance is still pending. This is not a
  claim that all upstream features or all 336 preference rows are migrated.

## Confirmed defects repaired

| Finding | Repair | Regression evidence / device boundary |
| --- | --- | --- |
| F01: network array crash | `NetworkSpeedText.render` always returns two strings, or null to preserve the native two-slot value. Includes all custom, zero and hidden branches; exact `String[]` target matching | Exhaustive style/hide/traffic combinations use a host stub reading both indices; no live crash reproduction |
| F02: screenshot restore/authentication | Removed the public exported hide/show receiver, view mutation, sleep, and split before/after hooks entirely. `DisplayCapture` appends `StatusBar` to its own per-call exclusion array, which the audited native capture strategies consume | Array isolation, duplicate exclusion, retained filters and rear-display bypass tested. No sender, receiver, capture depth, timer or baseline visibility now exists to authenticate/restore. Actual ordinary/long screenshot output still needs acceptance |
| F03: wrong network work | Resolve the declared type of `mBgHandler`, not stripped `declaredClasses`; check receiver identity and message `200001`. Re-time only an already-pending native background sample while not hidden. Never replace UI `100004` or its `Long` payload | Independent-queue policy tests, no-resurrection cases and interval bounds. Host remains responsible for screen/visibility start-stop. Actual cadence still needs measurement |
| F04: battery swap | Exchange `mBatteryPercentContainer` and `mBatteryDigitalView` in their common outer parent, retaining both views, their layout parameters and the intervening charging indicator. Reapply on attach and native refresh | Idempotent order policy tested; actual RTL/charging/theme/Folme animation/accessibility need device regression |
| F05: clock-only margins | Include bold, size, both margins and vertical offset in the activation predicate. Formatting alone no longer resets status-bar style | Each field tested independently; visual baseline/margin acceptance pending |
| F06: clock seconds | One boundary-aligned main-thread task for attached clocks needing unquoted seconds; duplicate attach is map-idempotent. Screen off cancels the task; screen on/time/timezone refreshes. Detach removes the clock and unregisters the last receiver. Preserve host demo mode | Quoted/escaped format parsing and second-boundary calculations tested. Real multi-clock attach/detach, screen and timezone ordering pending |

Network sampling is now per-controller rather than process-global. Unsupported
counters, backwards time/counters and long idle periods reset the baseline;
the code no longer divides an entire sleep's traffic by a capped ten seconds.
The longest configured interval (10s) allows dispatch jitter without falsely
resetting every sample. Global `TrafficStats` totals remain a disclosed difference
from upstream's filtered interface accounting, especially with VPNs.

Screenshot exclusion preserves the host's secure-layer checks, default assistant
filters, work-profile filters, one-shot filter and existing argument array.
It does not crop/repaint the bitmap or request additional capture permissions.
The rear display intentionally keeps its native replacement filter. Hosts lacking
the vendor exclusion API keep native capture; no unsafe broadcast fallback exists.
Only the screenshot host executes this feature now; the SystemUI scope remains
first in the catalog to preserve its existing settings-page location.

## Per-app volume blur

The missing material has a concrete migration mismatch: the old implementation
required `setCornerRadius(float)` while the reference supports four radii as well
as one, and int/float numeric overloads. A reflection exception took the flat-card
fallback. This establishes a code failure path, not a captured device stack trace.

- `BlurDrawableApi` now resolves supported numeric overloads (four-corner first),
  sets blur radius 100, corner radius and tint, and preserves drawable alpha.
- Enable drawing with `setWillNotDraw(false)`; cache the drawable per attached
  ViewRoot instead of allocating a new blur region on each show/layout.
- Reapply theme/radius after layout, observe cross-window blur availability and
  unregister/clear the ViewRoot-bound drawable on detach. Reuse the fallback too.
- Keep the already-correct card geometry and native slider/audio logic. Do not
  blur/dim the full screen or force system material settings. The audited MiSound
  window already sets hardware acceleration (`flags = 0x1048106`).
- Unit tests exercise int-radius/single-float-corner and float-radius/four-int-
  corner implementations, tint and missing-API failure. These are API stubs, not
  a GPU rendering test.
- **System-disabled blur or a genuinely missing interface still uses a logged
  translucent fallback. That fallback is not counted as successful blur parity.**
  Compare card-local blur with the native volume strip on light/dark/busy backgrounds
  after a later controlled load; the current repair does not claim that comparison
  has already passed.

## Partial chains completed and additional review

| Item | Current source | Deliberate boundary / residual acceptance |
| --- | --- | --- |
| P01 media buttons | Add main/custom icon-size controls. Cover controller `bindButtonCommon` AND OS4 utility `bindButtonsCommon`, including deferred semantic bind callers and null-action cleanup. Scale the original drawable with a centered matrix rather than rasterizing it | Preserve animated icons, click listeners, tint and touch bounds. Semantic main buttons use native play/prev/next identity; non-semantic labels use localized host resources, not English substrings. Unrecognized labels are custom. Sizes are honestly labelled px (upstream XML says dp but its bitmap code uses px). Island/flip-tiny layout, backgrounds and progress remain outside scope; parent remains partial |
| P02 old control center | Add Settings `StatusBarUtils.isForceUseControlPanel(Context)` and deoptimize the selector consumers. Catalog and dispatcher include Settings as well as SystemUI. Native selector still writes `use_control_panel` | Does not force a style or bypass Lite/owner restrictions. Settings evidence is a cached APK v37/minSdk37, not proof of the presently running Settings version. Fresh-host match and selector-to-panel acceptance still required |
| P04 conversation redirect | Carry the menu's `mSbn` through a try/finally-scoped ThreadLocal to the existing launch hook; include `EXTRA_CONVERSATION_ID` only when package/UID/channel all match | Retain Mi Push route, launch-as-user, empty-channel/error fallback and original modal dismissal. No leaking a shortcut across channels/profiles. Generic non-menu calls remain channel-only |
| Mobile roaming/activity | Replace marker flows before `bind` calls `repeatWhenAttached`, rather than after a potentially synchronous collection | Native signal policy unchanged. Already-attached view, SIM switching and combinations need device checks |
| Status-bar double tap | Reuse the completed-tap tracker with monotonic event times, platform slops, cancel/drag/multi-touch invalidation, attach/detach reset and touch-exploration guard. Use a valid application tag key | No injected `performClick` on every down; preserve native touch dispatch. Existing tracker tests cover drags, cancellation, timeout and nonmonotonic input. Native shade interaction still needs acceptance |

P03 carrier replacement/separators/HD, P05 additional clock shapes and per-clock
styling, and P06 extended network-type/icon arrangements remain explicit cuts.
Unmigrated island/focus/strong-toast, battery information, media background/progress,
navigation/tile/weather/control-center layout work is not relabelled complete.
The prior audit's limited coverage for other original modules remains limited;
this repair is not an end-to-end re-audit of NFC, passkeys, wallpaper, sharing or
notification-icon rendering.

## Evidence and validation

- Reference revisions unchanged: HyperCeiler
  `5e4686069dd7ab1f3697e256d5fc7d68fb73e317`; HyperVolumeANC
  `636ce289457ed10f149c033e3b723cdfc62838aa`.
- Fresh read-only MT verification: 14 repair code/layout targets, four media-label
  resource mappings and one battery resource-file mapping;
  `migration-repair-host-evidence.json`. SystemUI version `202602260`.
- Fresh MiSound verification: 21 targets and four resource-file mappings;
  `app-volume-host-evidence.json`, version `260903`.
- Cached Settings and screenshot capture/strategy evidence is separately hashed
  in `migration-repair-local-evidence.json`; not misrepresented as a fresh MT read.
  No proprietary decompiled host files are committed.
- `:app:testDebugUnitTest :app:assembleRelease`: **153 tests, zero failures,
  errors or skipped tests; Release/R8 and vital Lint successful**.
- Full `:app:lintDebug` was also attempted: **not clean**. It reports 20 errors,
  140 warnings and two hints. Remaining errors are on unchanged code/config:
  MobileSignalVisibility permission/API annotations, NotificationHooks API guard,
  SettingsActivity superclass/API handling, wallpaper API levels and local.properties
  Windows escaping. No global suppression or baseline was added. The new blur API
  gate and battery/conversation SDK guards remove this repair's NewApi errors.
- `git diff --check` passes. Inventory generation retains 336 XML rows:
  41 scoped static implementations, 12 repaired/static/pending-device rows,
  two partial/static rows, 177 requiring audit and 104 pending. These are settings
  rows, not independent functions or accepted-on-device counts.

APK: `app/build/outputs/apk/release/app-release.apk`, version `0.1.3` / `4`.
Final build hash/size are recorded in `migration-repair-build.json`.
The subsequent user request authorizes committing/pushing this repair. Installation
is still pending reconnection; no host restart or functional acceptance is included.

```powershell
python tools/systemui_inventory.py .workbuddy/refsrc/HyperCeiler
python tools/mt_systemui_probe.py --manifest docs/migration-repair-host-evidence.json --output .workbuddy/tmp/mt-systemui/repair-evidence-refresh.json
python tools/mt_systemui_probe.py --workspace yglu33cw --manifest docs/app-volume-host-evidence.json --output .workbuddy/tmp/mt-systemui/repair-volume-refresh.json
.\gradlew.bat :app:testDebugUnitTest :app:assembleRelease --console=plain
```

## Later controlled device acceptance (not executed here)

Use only the replacement APK, confirm actual host versions and scopes, then test
each feature independently before combinations. Confirm disable/reload restores
native behavior. Specifically exercise all five network styles and 1/4/10-second
cadences; screen sleep/wake; repeated/long/failed/overlapping screenshots with no
live status-bar visibility change; battery styles/charging/RTL; clock seconds and
literal formats across screen/timezone/reinflate; Settings selector and old panel;
work-profile conversation/channel fallback; media animated/custom controls and
flip-screen transitions; and volume blur availability/theme/repeated paging/close.
