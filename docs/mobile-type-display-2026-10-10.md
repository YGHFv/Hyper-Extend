# Mobile type display migration - 2026-10-10

## Scope and acceptance

User direction: continue migration first, then perform device acceptance as one
combined phase. This batch does not install, restart/reload hosts, toggle options,
or change network/SIM state. Tests and exact MT contracts are not device acceptance.

New entry: SystemUI / status bar / mobile network type display
(`status_bar_mobile_type_display`, off by default).

- Five display modes: native, show with valid service, non-WiFi, hidden, and
  connected mobile data only.
- Separate native text view, physical left/right placement, bold, 9-20 dp font
  size in 0.5 dp steps, 0-8 dp horizontal offsets, and -4 to 4 dp vertical offset.
- Existing custom text and dual-row signal remain separate features. No setting
  keys are renamed or reset; no native network/service state is fabricated.

## Host and upstream evidence

Upstream `MobileNetworkTypeSettings`, `SystemUIB/SystemUIV` and
`MobileTypeSingle2Hook` provide the actual preference/installation/rendering chain.
The hidden big-network-type switch remains unaudited; it is not this feature.

Current host: `com.android.systemui`, `17.03.260226.r / 202602260`, MT workspace
`wot5ikcq`. Formal manifest: `docs/mobile-type-display-systemui-evidence.json`.
The read-only refresh verifies 18 code/layout targets and four resource IDs,
including complete pagination checks; recorded hashes match refreshed text.
Raw reads: `.workbuddy/tmp/mt-systemui/type-*.smali`; refresh output:
`.workbuddy/tmp/mt-systemui/mobile-type-display-verified.json`.

The chain includes:

1. `MiuiMobileIconBinder.bind` consumes the VM interface, not a concrete VM cast.
2. Small type: `getMobileTypeVisible` feeds collector `$15` / `$13$1`.
3. Separate text: `getMobileTypeSingleVisible` combines with satellite visibility
   and root visibility in `$18` / `$18$1`; `$3$2` invokes native `setChildVisible`.
4. Special 5G artwork: `getShowSpecial5GIcon` feeds `$14` and the drawable state.
5. Text names still pass through `$22$1`, native `measure()` and layout updates.
6. The host `MobileUtils.isInService(ServiceState)` checks voice and data
   registration, including its WLAN/WWAN registration distinction. It is reused
   instead of inventing a simpler voice-only service test.
7. Native `onViewRemoved` clears the animation-state tag; therefore ordering uses
   `bringChildToFront`, not remove/add, and retains native IDs/constraints.

## Implementation and intentional differences

`MobileTypeDisplayHooks` creates a per-binding facade with three visibility
relays. All other interface methods delegate to the previous VM/facade. Original
flows remain subscribed at CREATED level through the host attachment helper;
hidden lower-SIM roots continue updating. Detach disposes collections and restores
owned styling; callbacks carry a generation to reject stale updates.

No shared VM field is replaced by this new feature. `MobileViewModelFacade` now
also serves dual-row signal, so either wrapper order preserves both sets of
overrides. Original VM access for mobile markers and visibility unwraps all local
layers. Identity equality/hash code do not invoke host equality. There is no
global proxy-to-original weak map that must be manually synchronized.

The native large/special artwork is preserved when separate display is off.
Separate display hides small/special variants through their relays, but does not
remove those views, replace their IDs, or disable drawable measurement. The
native text receives names, tint, content updates and visibility animation. Only
font, physical side, horizontal padding and vertical offset are customized.
Restoration tracks owned values and retains newer host writes.

Behavioral boundaries:

- Native mode with separate display off installs no unnecessary hook.
- Native mode with separate display on transfers the union of native type
  visibility to the text view. Unlike upstream's dual-row fallback, it does not
  force a label when every native type variant is hidden.
- Always-show still requires known cellular service, non-empty current name and
  native root visibility. It never revives a hidden SIM or a disconnected label.
- Non-WiFi uses the host's `wifiAvailable` flow, not a separate connectivity query.
- Connected-only requires this SIM's `isDataConnected` and known non-WiFi state.
  This intentionally uses an explicit per-SIM data guard for either visual style.
- Unknown provider/service/WiFi/data values fall back as documented by the pure
  policy. Empty names and known no-service states never reuse previous labels.
- Satellite providers retain native behavior except explicit hide mode, which
  suppresses type variants without hiding the satellite icon itself.
- Safe mode restores original relay samples and styling on callback/pre-draw.
  Complete cross-generation hot-reload cleanup is still outside this batch.

## Tests and build

New JVM coverage includes five modes and unknown state combinations, service loss,
reconnect, native special variants, physical ordering in RTL/LTR, preserving new
host children, non-accumulating style ownership, nullable typeface restoration,
both facade nesting orders, identity/weak-map behavior, original exceptions, and
catalog/default/range/persistence registration.

The first test run failed only the old fixed settings-page count after adding the
new entry. That expectation was updated from eight to nine; no assertion was
removed. Final results and artifact identity are in
`docs/mobile-type-display-build.json`; build log: `build/mobile-type-display-build.log`.
Final run: **302 tests**, zero failures/errors/skips, including 19 added tests;
R8 Release succeeded in 1m 45s. Tracked diff and untracked text whitespace checks
passed, and a second inventory generation reproduced the saved snapshot exactly.
Full lint was not rerun; the prior 22 errors are not claimed resolved.

## Inventory update

Eight exact preference rows now map to this implementation and its evidence.
Navigation/dependency rows and the separate activity-indicator row are not marked
complete merely because this page has an implementation.

The regenerated inventory still contains 337 rows, including navigation and
dependencies: 156 existing-partial rows need audit, 98 remain pending, 58 have a
bounded static implementation, 18 have static repairs awaiting device tests,
four are partial static implementations, and three have other bounded statuses.
This is not full SystemUI migration completion.

## Unified acceptance additions

After migration completion, include these with the existing volume/dual-row matrix:

- All five modes with separate display on/off, both SIMs, default-data handover,
  WiFi attach/loss, per-SIM data loss, no service and empty-name transitions.
- Dual-row plus type display plus custom text, with both default-data choices;
  verify hidden lower-SIM updates and no appearance of a hidden root.
- Theme/tint/density/font changes, RTL/LTR, all offset/font extremes, repeated
  rebinds and detach/reattach; verify native text/special icon restoration.
- Visibility animations, TalkBack, control center and status bar containers;
  check animation tags, text measurements and crowded status-bar layout.
- Safe mode and user-directed disable/reload: restore native appearance while
  retaining configured values. No runtime completion is recorded here.
