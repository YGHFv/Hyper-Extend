# Battery migration follow-up - 2026-10-09

## User-requested deployment

After the source-only repair, the user requested installation and GitHub push.
`adb install -r` succeeded at 19:55:51 (Asia/Shanghai); package manager confirms
`0.1.3` / `4`, and the installed APK SHA-256 matches the tested artifact:
`7d30225519767cd3438d519cc5c055814fb6052d93d340f51603fcef220b489e`.
SystemUI/MiSound/screenshot/system_server PIDs were 15807 / 1648 / 10463 / 3641
before and immediately after installation. This observation covers the immediate
check only, not later process lifetime. No restart/reload command, toggle or
scope change was issued. New-hook loading and feature acceptance remain pending.
The earlier source-only boundary below is retained as historical context.

## Scope and deployment boundary

Source baseline: `ff6e093`. This pass checks the migration ledger, the retained
F01-F06 repair paths and the battery visibility/style combination. It is not a
new full audit of every migrated feature. No new feature is added.

The source-repair phase was local only: no commit/push, APK installation, host restart,
hot reload, feature toggle or scope change. Version remains `0.1.3` / `4`;
identify this local artifact by its hash, not the version alone. Historical
installation evidence in `migration-repair-build.json` is unchanged.

## Findings and repairs

### F07 - P2: hiding the icon missed the OS4 hollow view

`StatusBarIcons.applyBatteryVisibility` hid `mBatteryIconView` and, for style 1,
`mBatteryDigitalView`, but never `mHollowBatteryIconView`. The cached OS4 layout
contains both icon views inside the digital container, while the percentage
container and charging image are siblings. The cached `onBatteryStyleChanged`
method explicitly switches visibility between solid and hollow icons.

`BatteryVisibilityPolicy.hiddenViewFields` now includes both icon fields for
every style and retains the existing style-1 container rule. Percentage and
charging visibility remain independent. Optional legacy/missing fields still
use null-safe reflection. The visibility hook also reapplies on attach, matching
the style hook's refresh/attach coverage; no broad `View.setVisibility` hook is
introduced.

### F08 - P2: custom font sizes could restore hidden text

The icon hook hid text by setting its size to zero; the separate battery style
hook wrote positive custom sizes without respecting those hide options.
Distinct hook IDs do not solve conflicting writes. Depending on callback order,
hidden percent/mark text could reappear. This is not an API-102 registration
collision claim.

Both paths now use `BatteryVisibilityPolicy`, captured from parent-gated
`HookSettings` at installation. Hidden numbers and marks always resolve to zero,
regardless of custom size; visible text preserves the existing half-unit scale
and greater-than-7.5dp threshold. Hiding percent also hides the mark and optional
legacy digit. Hiding only the mark still permits custom numeric text. Fully
hidden percent text skips reordering, bold and padding changes. A disabled
`status_bar_icons` parent cannot activate saved hide options through the custom
style feature. Settings changes still require the documented host reload.

## Evidence

Reference source remains HyperCeiler
`5e4686069dd7ab1f3697e256d5fc7d68fb73e317`, specifically
`library/libhook/src/main/java/com/sevtinge/hyperceiler/libhook/rules/systemui/statusbar/icon/all/HideBatteryIcon.kt`
and `BatteryStyle.kt` in that same directory. The reference's legacy field names
are not treated as an OS4 contract.

This pass uses **cached** SystemUI `17.03.260226.r` / `202602260` artifacts only.
It makes no fresh MT call or current-host-version claim. The local battery layout's
UTF-8/LF-normalized hash matches the earlier MT repair record
`dcb436f95989a4e078cb42a5a3460c327c0f9ba381fd2019c154242436af6865`.
The full cached battery class is inspected and hashed locally; its full-class
hash is not presented as a prior or fresh remote verification.

`migration-battery-followup-evidence.json` records raw and LF-normalized local
hashes, checked fragments, test/build results and deployment exclusions. Raw
proprietary decompilations remain in the ignored `.workbuddy` directory.

## Local validation

- Baseline unit test run passed before edits. Final suite: **199 tests, zero
  failures/errors/skips**, including eight new battery policy tests.
- New tests cover both icon fields across styles/missing style; independent
  charging visibility; percent/mark/legacy-digit semantics; the entire 0..200
  size range; both callback orders with repeated native font resets; and parent
  switch/default/missing-preference isolation. Composition uses a text-state
  stub, not Android rendering or actual Xposed dispatch.
- Release/R8, vital Lint and APK signature verification pass. APK is at
  `app/build/outputs/apk/release/app-release.apk`; hash/size are in the evidence.
- Full Debug Lint is **not clean**: 22 errors, 173 warnings, two hints. All errors
  are in files unchanged by this pass (mobile permissions/API levels, volume
  version APIs, notification API guard, SettingsActivity, wallpaper APIs and
  local.properties). Battery files have three pre-existing style suggestions
  on unchanged group-swap lines, not errors. No baseline/suppression is added.
- `git diff --check` passes. Regenerated inventory retains **336 rows**: 41 scoped
  static, 18 repaired/pending-device, two partial, 171 requiring audit and 104
  pending. Only the six F07/F08 settings rows are reclassified; these counts do
  not represent device-accepted functions or full upstream parity.

## Pending controlled acceptance

On a later authorized deployment, verify actual host versions, APK hash and
hook loading first. Exercise solid/hollow/digital styles, charge/unplug, theme,
reinflate/attach, RTL, lockscreen/AOD/island animation transitions and control
center. Test each hide option independently, then with custom sizes, bold,
margins and icon/percent swapping. Check that hiding only the mark keeps the
number visible, hiding the icon does not remove the percentage or charging
indicator, and disabling/reloading restores native behavior.

Animation completion, accessibility and actual layout/rendering remain unverified.
Local policy tests and cached host contracts are not a substitute for these checks.
