# Migration completeness audit - 2026-10-09

## Repair follow-up (0.1.3 installed, hosts not reloaded)

The F01-F06 defects below describe `beb0fda` / installed 0.1.2. They are preserved
as historical evidence, **not the status of current source**. Repairs, regression
coverage, explicit implementation differences and remaining device checks are in
[migration-repair-0.1.3.md](migration-repair-0.1.3.md). The replacement APK has not
been loaded into existing hosts by a requested restart/reload. Installation of
0.1.3 succeeded at 13:25:22 on the same day, with matching APK hash and unchanged
host PIDs; no feature toggle or functional acceptance was performed. The old-code
risk warning still applies to existing hosts until controlled load/acceptance.

## Result and deployment state (historical)

**The volume entry was not an isolated incomplete migration. Do not treat the
current migration list, a successful build, or a nonzero hook count as acceptance.**

- Reviewed application source: `beb0fda` on `main`.
- Installed with `adb install -r`: Release 0.1.2, versionCode 3, success on the
  connected device. Package update time: 2026-10-09 06:04:16, Asia/Shanghai.
- Installed APK SHA-256:
  `f5027f670b31dbe22489dd9a45ff4ce55e39a6df2b9677149d527d536a8b690e`.
- The installed build previously passed 136 unit tests and Release assembly.
  This audit did not reinstall it, restart any host, enable a feature, or change
  LSPosed scope. No new functional device acceptance was performed.
- Source and earlier migration work were committed and pushed to
  `https://github.com/YGHFv/Hyper-Extend`, `main`, as `beb0fda`.
- **The runtime defects below remain in that APK. In particular, do not enable
  non-default network-speed styles or restart SystemUI to load them.** This
  follow-up records findings and corrects inventory classification, not runtime fixes.

## Scope and evidence

Deep review covers the 28 entries in `core/SystemUiFeatures.kt` and the seven
groups in `core/StatusBarFeatures.kt`, comparing local call paths with HyperCeiler
revision `5e4686069dd7ab1f3697e256d5fc7d68fb73e317`. The volume feature has its own
inventory and acceptance checklist in `app-volume-migration.md`.

The other original module entries are inventoried separately below. Their
entry-point review is **not** a full cross-host parity or security audit.

Host evidence: SystemUI `17.03.260226.r` / `202602260`, plugin `183022200`, and
MiSound `260903`. Existing manifests retain their own precise scope. New
read-only evidence for this audit is in `migration-audit-host-evidence.json`.
Five code/layout targets and the battery resource-file mapping were freshly
verified. Raw host decompilations remain local, not committed to the repository.

Unless prefixed otherwise, source paths below are relative to
`app/src/main/java/io/github/YGHFv/HyperExtend/`.

## Confirmed defects in exposed settings

### F01 - P1: custom network-speed text can crash SystemUI

- Local: `hook/feature/statusbar/StatusBarNetworkSpeed.kt:169`.
- Trigger: enable the network-speed feature and select any style 1 through 4;
  the hook passes a one-element `String[]` to the original `updateText`.
- Host: `NetworkSpeedController.updateText(String[])` unconditionally accesses
  indices 0 and 1. The second access throws `ArrayIndexOutOfBoundsException`.
  This is a host-call exception, not a harmless missing hook; HookRuntime does
  not replay the original invocation to undo it.
- Reference: `statusbar/network/NewNetworkSpeed.kt` maintains two output slots.
- Required fix: preserve the two-slot number/unit contract for every style and
  hidden/zero-speed branch; the unused unit slot can be empty.
- Missing tests: all five styles, all hidden/slow combinations, and a host stub
  that reads both slots. Do not reproduce the failure in live SystemUI first.

### F02 - P1: screenshot hiding has an unsafe/incomplete restore protocol

- Local: `hook/feature/statusbar/ScreenshotStatusBar.kt:82`, `:99`, `:140`.
- `registerReceiver` returns a sticky Intent or null, not a success flag. A
  normal successful nonsticky registration returns null, so the marker is not
  set and reattachment registers more receivers. There is no detach cleanup.
- Repeated hide events overwrite `visibilityBefore` with `INVISIBLE`; an
  overlapping capture can then restore to the wrong state. Recreated views
  retain receivers until process teardown.
- The exported receiver accepts the public action `miui.intent.TAKE_SCREENSHOT`
  without a receiver-side sender permission or identity check. The module does
  not authenticate hide/show events. Third-party reachability also depends on
  the ROM's protected-broadcast policy, which was not verified; no exploit was
  run on device. Do not treat this receiver as authenticated by its action name.
- Capture starts hiding in a before hook; the after hook runs only when the
  original returns normally. A capture failure has no `finally` restoration,
  and a lost finish event has no timeout recovery. The 80 ms sleep is not an
  acknowledgement that SystemUI has drawn the hidden frame.
- Required fix: authenticate the sender, register/unregister with the view
  lifecycle, track capture ownership/depth without overwriting the baseline,
  and use a single around-hook with `finally` plus bounded recovery.
- Missing tests: duplicate attach, overlapping captures, unauthorized sender,
  capture exception, lost finish, originally invisible view, and actual frame
  ordering. This is distinct from `screenshot_clipboard`.

### F03 - P2: network update interval schedules the wrong work

- Local: `hook/feature/statusbar/StatusBarNetworkSpeed.kt:366`.
- The migration uses `updateTextSeen` to identify the message to reschedule and
  sends an empty message with the same ID to the same Handler.
- Host background message `200001` samples traffic and posts UI message
  `100004` with a `Long` payload. Only the UI message calls `updateText`; an empty
  replacement UI message fails its payload check. Background sampling continues
  to schedule itself at 4000 ms. Thus display work is confused with sampling.
- Handler discovery also relies solely on `declaredClasses`; the retrieved
  controller has no MemberClasses annotation. That is an additional discovery
  risk, not a runtime observation. The wrong-work diagnosis holds even if
  discovery succeeds. The shared flag also spans both Handler threads.
- Reference: `statusbar/network/NetworkSpeedSpacing.java` reschedules the
  controller's `mBgHandler` sampling message and supplies class-name fallbacks.
- Required tests: independent UI/background queues, nonempty payloads, actual
  sample cadence, screen off/on, no duplicate work, and missing-target fallback.

### F04 - P2: battery icon/percentage swap is a no-op on the audited layout

- Local: `hook/feature/statusbar/StatusBarBatteryStyle.kt:179`.
- `changeLocation` reorders the numeric TextView and mark inside `percent.parent`.
  On this host, that parent is `mBatteryPercentContainer` (`0x7f0b0185`), and the
  number (`0x7f0b0187`) is already its first child. The function returns immediately.
- The battery icon lives in a separate `mBatteryDigitalView` FrameLayout
  (`0x7f0b0180`). Moving children inside the percentage container cannot swap
  that container with the icon. Verified in `res/layout/battery_digital_view.xml`.
- Reference: `statusbar/icon/all/BatteryStyle.kt` itself gates its old swap
  implementation off on newer HyperOS layouts. Replacing field names alone did
  not adapt the hierarchy.
- Required fix/tests: move the outer percentage group relative to the icon
  group while preserving charging indicators, layout parameters, animation and
  accessibility; repeat across battery styles, RTL, charging and theme changes.

### F05 - P2: changing only clock margins/offset never installs the hook

- Local: `hook/feature/statusbar/StatusBarClock.kt:89`.
- With default size, bold off, and no custom format, installation returns 0
  without testing left/right margins or vertical offset. These sliders are
  exposed independently, so using only them silently has no effect.
- Required fix/tests: include every style field in the no-change predicate;
  test each setting independently, rather than only combined custom styles.

### F06 - P2: clock formats support seconds text but omit seconds scheduling

- Local: `hook/feature/statusbar/StatusBarClock.kt:100`, `:149`.
- The code formats current time only after host `MiuiClock.updateTime`. It has
  no per-second scheduling, attach/detach registration, or screen-state timer.
- Reference: `statusbar/clock/StatusBarClockNew.kt:489` implements
  `SecondsFrameCallback`; format parsing registers clocks needing seconds.
- Audited host controller registers `KeyguardUpdateMonitorCallback` and updates
  on time/configuration events; the reviewed clock/controller code does not
  supply the missing per-second loop. A format containing seconds therefore
  does not establish a ticking seconds clock. Exact observed cadence remains
  a device acceptance item, not something measured in this audit.
- Required tests: seconds/no-seconds/escaped literals, second-boundary alignment,
  screen off/on, detach cleanup, timezone changes and multiple clock instances.

## Partial migrations, not equivalent upstream replacements

These are distinct from F01-F06: some boundaries were already disclosed, but
their parent feature must not be counted as full upstream parity.

| ID | Feature | Present locally | Missing or deliberately narrowed |
| --- | --- | --- | --- |
| P01 | Media card layout | Ordinary notification-center cover/overlay hiding, order, alignment, output entry and two margins | Reference `MediaViewLayout.initButtonSize` main/custom button bitmap scaling; island layout; background and progress effects remain separate pending work |
| P02 | Old control center | SystemUI force flag and its Flow consumer | Upstream also hooks Settings `StatusBarUtils.isForceUseControlPanel(Context)`. No local Settings half; users without a stock selector can enable this and still have no way to choose the old style |
| P03 | Control-center carrier | Hide carrier-name area, retain privacy indicators/space | Separator removal, device-name replacement and HD behavior; already explicitly partial in the catalog |
| P04 | Notification channel redirect | Package/channel/UID-based user-aware launch and fallback | Upstream includes `Settings.EXTRA_CONVERSATION_ID` from notification `shortcutId`; local `ControlCenterHooks.kt:61` does not carry it, so conversation-specific destination parity is missing |
| P05 | Clock family | Four text formats; status-bar clock size/bold/margins | Two-row date shapes, other clocks' individual styling, Pad-date hide and sync control; F05/F06 additionally break exposed behavior |
| P06 | Mobile/icon family | Signal modes, SIM hiding, several markers and icon slots | Separate/big/custom network-type text, dual-row signal, Wi-Fi/mobile swap, force-show Wi-Fi standard and alarm-before-ringing mode |

The inventory now marks P01/P02 parent keys and P04 as partial instead of implemented.
It retains the correct XML mapping for media text size: the reference XML uses
`...media_button_size_switch`, while `MediaViewSize.kt` reads
`...media_control_text_size`. This is an **upstream XML/hook discrepancy**, not
proof that HyperExtend's own text-size switch is disconnected.

## Coverage ledger: the 28 SystemUI entries

"Scoped review" means no additional missing branch was identified within the
explicitly advertised scope. It does not mean device-verified or full parity.
All rows still require enable/use/disable and host lifecycle acceptance.

| Local feature ID | Review result / boundary | Required acceptance focus |
| --- | --- | --- |
| `control_center_hide_edit` | Scoped review; plugin appearance hook | Entry hidden without disabling editing capability |
| `volume_hide_collapsed_footer` | Scoped review; collapsed-only change | Expand restores native footer conditions |
| `notification_auto_expand` | Scoped review; selected packages only | Manual collapse, privacy, keyguard, empty list |
| `notification_expanded_timeout` | Scoped review; guarded heads-up removal | Reply/menu/call exceptions and cleanup |
| `control_center_hide_carrier` | Partial P03 | Carrier disappears; privacy indicators survive |
| `media_card_layout` | Partial P01 | Native controls, constraints, long titles, RTL |
| `media_card_text_size` | Scoped ordinary-card review; no island/flip styling | Rebind/config changes and font scaling |
| `media_unlock_custom_actions` | Scoped review; build and click gates both covered | Native custom action really executes |
| `lockscreen_third_party_biometrics` | Scoped capability override, not authentication bypass | Trusted third-party lockscreen; unenrolled fallback |
| `systemui_monet_custom` | Scoped seed-color override | Invalid color, dark/light, contrast, wallpaper-repair combination |
| `notification_unlock_focus` | Scoped package whitelist, no invented focus content | Selected vs unselected apps and unsupported payloads |
| `notification_disable_auto_fold` | Scoped review | New notifications vs existing folded/manual entries |
| `lockscreen_hide_hint` | Scoped unlock hint removal | Charge/admin/biometric error hints retained |
| `control_center_auto_collapse` | Scoped review | Available tile click; edit/admin/long-press exceptions |
| `control_center_unlock_old` | Partial cross-host chain P02 | Settings selector plus actual old-style panel |
| `notification_channel_settings` | Partial conversation destination P04 | Work profile, conversation, empty channel, Mi Push fallback |
| `lockscreen_scramble_pin` | Scoped review; both animation matrices handled | Labels/input/animations agree; delete/emergency unchanged |
| `lockscreen_double_tap` | Scoped review; gesture tracker guards | Blank area, drag, multi-touch and touch exploration |
| `lockscreen_hide_zen` | Scoped OS4 presentation-flow adaptation | Count retained, DND remains enabled |
| `notification_zen_fix` | Scoped upstream semantics, includes priority exceptions | Sound/vibration/light suppression without hiding notification |
| `clipboard_native_overlay` | Scoped allowlist adjustment with restoration | Sensitive/locked/suppressed cases and native editing |
| `lockscreen_show_notifications` | Scoped upstream visibility predicate | Sensitive content and managed profile privacy |
| `lockscreen_keep_notifications` | Scoped shown-after-unlock state change | Unlock/relock without resurrecting removed notifications |
| `lockscreen_hide_status_bar` | Scoped OS4 visibility flow | Unlock restores normal status bar |
| `lockscreen_hide_ble_toast` | Scoped success-toast removal | Authentication failures and other toasts unaffected |
| `notification_mute_when_interactive` | Scoped upstream alert semantics | Interactive vs screen-off sounds/vibration/light |
| `notification_disable_transparent` | Scoped review | Landscape/rotation/rebind backgrounds |
| `notification_freeform` | Scoped Activity-intent eligibility | Supported app opens small window; non-Activity/Mi Push fallback |

## Coverage ledger: seven status-bar groups

| Local feature ID | Review result | Remaining work |
| --- | --- | --- |
| `status_bar_icons` | Scoped slot/visibility implementation, narrower than upstream P06 | All slot modes, notification count, battery-style combinations; no blanket parity claim |
| `status_bar_battery_style` | F04 confirmed | Outer hierarchy fix and charge/style/font regression |
| `status_bar_mobile` | Signal modes/SIM hiding adapted; P06 partial | Roaming/activity flow replacement runs after bind, unlike the new signal path; verify already-attached collection and move before collection if needed |
| `status_bar_network_speed` | F01 and F03 confirmed | Host array contract and sampling scheduler first; then long-sleep/traffic-counter semantics and visuals |
| `status_bar_clock` | F05/F06 confirmed; P05 partial | Activation predicate, ticking seconds, lifecycle, then additional shapes |
| `status_bar_double_tap` | Main upstream gesture path present | Only ACTION_DOWN is tracked; canceled drags/multi-touch/accessibility need separate acceptance, not assumed safe |
| `status_bar_screenshot_hide` | F02 confirmed | Authenticated two-host lifecycle and restoration before enabling |

Not migrated: island/focus lyrics/strong-toast appearance, battery information
card, and other pending catalog items. Missing old class names are not evidence
that these capabilities are impossible on OS4; new owners and call paths must
be located before classifying them as inapplicable.

## Other module entries: limited review, not full acceptance

| Entry | What was checked / coverage limit |
| --- | --- |
| `volume_app_entry` | Dedicated prior fix: entry background, original ball suppression, MiSound card/animation/paging code present. See `app-volume-migration.md`; visual and audio acceptance still pending |
| `screenshot_clipboard` | Unlock hook plus publication guard present, unlike F02. Saved-image pending/trash checks and timeout exist; re-test ordinary/long screenshots and downstream sharing |
| `gesture_line` | Drawing and hidden-state paths both present; WMShell shared-library ownership documented, not incorrectly limited to APK classes. Re-test recognition/gesture/highlight behavior |
| `wallpaper_monet` | Startup/retry/events and palette regeneration paths present. Full source parity and live wallpaper/multiuser acceptance not repeated in this audit |
| `native_notify_icon` | No-substitution, smallIcon, ANIP library and update lifecycle paths present. Not a claim to port all upstream rendering/tint/corner/application-rule UI; visual combinations need their own audit |
| `nfc_card_face` | Card-address mapping/factory and island paths present; Glide is capture-only locally whereas upstream can replace Glide arguments. Alternate loading routes need per-card acceptance; non-Xiaomi wallets are outside local scope |
| `passkey_fix` | Four host dispatches present (Settings, security center, scanner, system_server), not just a Settings entry. End-to-end enrollment/sign-in and global-build-flag interactions still require dedicated audit |
| `rotation_suggestion` | Both eligibility and visible-state paths present; original behavior-level feature, not a HyperCeiler page migration |
| `milink_clipboard_guard` | Original repair, not upstream migration; retain its earlier crash-reproduction regression |
| `mishare_receive_guard` | Original repair, not upstream migration; retain its two-device transfer regression |
| `rotation_lock_fix` | Original system_server repair, not upstream migration; not re-exercised here |

## Excluded false lead: multiple hooks on one method

Battery visibility/style, mobile signal/markers, and screenshot before/after
register distinct hook IDs on the same executable. This was investigated, but
is **not reported as a proven API-102 collision**. Published API 102.0.0
`XposedInterface.HookBuilder.setId` explicitly identifies hooks by module,
executable and ID; the same ID replaces the previous hook. Distinct IDs are not
by themselves evidence that the second hook is rejected.

Source: https://repo.maven.apache.org/maven2/io/github/libxposed/api/102.0.0/api-102.0.0-sources.jar

The old absolute claim in HookRuntime's comment was inaccurate. Interaction
ordering still deserves combination tests; F02's missing `finally` remains a
real defect regardless of how many hook registrations succeed.

## Inventory and repair order

`systemui-upstream-inventory.json` contains **336 XML rows, not 336 independent
features**. After this audit: 39 scoped/static implemented, 4 partial/static,
10 rows with confirmed defects (F01/F03/F04/F05/F06), 177 still requiring audit,
and 106 pending. F02 belongs to a local exposed entry and is tracked in this
report rather than inventing an upstream XML key. One finding may cover several
settings; neither row counts nor parent-switch counts measure completeness.

1. Fix F01's crash contract before using custom network styles. Add regression
   tests before building/installing a replacement APK.
2. Fix F02's authorization and restoration lifecycle; validate across both
   hosts. Do not test arbitrary capture failures against the current live build.
3. Fix F03-F06, with isolated settings, actual host layouts/messages, and
   off/on/reattach/rotation/charging tests. Review mobile marker collection order.
4. Restore end-to-end parity for P01/P02/P04 or explicitly narrow the exposed
   promises; schedule the remaining upstream capabilities separately.
5. Perform controlled reference-vs-migration device acceptance. A feature is
   complete only when its entry, action, resulting UI/state, cleanup and disable
   path all agree. Tests that only check class names or catalog keys do not count.

Reproduction of the read-only checks:

```powershell
python tools/mt_systemui_probe.py --manifest docs/migration-audit-host-evidence.json --output .workbuddy/tmp/mt-systemui/migration-audit-refresh.json
python tools/systemui_inventory.py .workbuddy/refsrc/HyperCeiler
```

The first command needs the matching MT workspace on the connected audit host.
It validates positive evidence fragments; it does not run the module or prove
absence of other host paths. The current 136 tests do not cover the network
array contract, clock activation/timing, battery view hierarchy, or screenshot
restore state machine identified here.
