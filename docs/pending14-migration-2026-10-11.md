# Remaining-14 source migration checkpoint (2026-10-11)

Historical source-only checkpoint. Subsequent implementation and verification
are recorded in `pending14-continuation-validation-2026-10-11.md` and
`pending14-unified-validation-2026-10-11.json`; the unbuilt counts below describe
this earlier checkpoint, not the latest worktree.

`allMigrationComplete=false`. Five previously pending rows now have source
implementations; nine remain pending. This is not completion of the requested
14-row batch, nor completion of the earlier partial implementations and audits.
No Gradle, Kotlin/Java compilation, tests, lint, R8, APK installation, host restart,
hot reload, scope changes or device feature actions were performed in this batch.
The newly defined tests have **not been run**. Unified validation remains deferred
until the remaining migration is implemented, as requested.

## WMShell: four source implementations

The actual code is `/system_ext/framework/Miui-WindowManager-Shell.jar`, SHA-256
`aae1827abff92240c405a77794506bebbb801b06a57c7f66e501ec181af42ded`.
The earlier no-DEX WMShell resource export is not the implementation carrier.
Current device `ro.build.version.sdk` is **37**, not 36. Installation checks this
SDK and the original JAR hash; an unsupported library fails closed. No old-class
fallback is substituted. Hashing adds one synchronous 8.7 MB read when a window
feature is enabled; runtime cost remains unmeasured.

The JAR was previously copied with root into
`/storage/emulated/0/MT2/mcp/pending14-20261011/` with matching source/copy hashes.
MT accepts APKs rather than raw JARs. An analysis-only APK contains the unchanged
`classes.dex` plus a synthetic manifest; it is **never an installable module or
replacement host**. DEX hash:
`c479b411136ceeb6055c5dd301ea008c79993600e7818b7e9dea64926b9a1515`.
`docs/window-decoration-evidence.json` records 20 complete live member reads,
including eight direct caller methods and both inherited fields.

- `window_hide_quick_button`: suppresses `MiuiDecorationDotView.onDraw(Canvas)`.
  It does not change visibility, touch dispatch, hit areas or accessibility.
- `window_disable_quick_menu`: suppresses the exact private
  `createHandleMenu(int, String)` and deoptimizes `handleCaptionClicked()`.
  It prevents new menus; it does not forcibly dismiss an already-open menu.
- `window_disable_infinity_gesture`: changes `isEnabled()` only within the native
  event-selection and freeform-hit-test calls. Nested, exception-safe,
  thread-local scope plus a conservative immediate-host-caller check avoids
  changing the three singleton `<clinit>` selections (including lazy initialization
  nested inside a gesture),
  task creation, orientation and window-size policies. This deliberately does
  not copy upstream's process-wide capability falsification. A gesture already
  in flight keeps its native continuation; new selection uses the opt-in rule.
- `window_hide_bottom_bar`: returns zero for native freeform bottom-caption
  height and suppresses drawing only for a captured decoration currently in
  windowing mode 5. Weak view/owner association follows existing native height
  queries; it does not post alpha changes or overwrite animation-owned alpha.
  Other windowing modes keep their native drawing. Missing ownership leaves
  drawing native. This library uses `getDecorationRootView()`, not the upstream
  nonexistent `mMiuiDecorationBottomView` field.

All four controls are default-off, in the SystemUI/Other/Windows panel, with a
single dispatcher installation chain. Runtime settings and safety checks retain
native behavior when disabled. Cached geometry, display-list rendering and
existing menus can require native relayout/recreation; a SystemUI restart is the
documented full recovery path. Small-window, split-screen, casting, orientation,
gesture cancellation and combination behavior are not device-accepted.

## Taplus: current native provider instead of the old settings key

`control_center_taplus_tile` adds an independent `hyperextend_taplus` QSTile and
classic/plugin editor candidate. It never instantiates NightModeTile, edits the
saved tile list or falls through to a native tile action. Factory, state, callback,
falsing, editor and launch contracts reuse the existing exact GMS/5G host evidence.
The feature is default-off and requires the user to add the tile.

Current ContentExtension `50015 / 5.1.5` no longer treats
`Settings.System.key_enable_taplus` as the live switch. `putTaplusEnable` writes
private preferences; `isTaplusEnable` can migrate the old System value into them.
Blindly copying the upstream settings write would fail to disable it reliably.

The adapter uses the native signature-protected `SwitchControlProvider`:

- `getControlStatus` returns the strictly typed `key_control_status` boolean.
- `setControlStatus` receives explicit `open` or `close`. It runs the native
  preference write, long-press setting update and ContentCatcher configuration.
- Package/version, exact provider identity, exported/enabled system-app state,
  declared signature permission and effective caller permission are checked.
  No permissions are granted or bypassed. Only the current owner user is supported.
- Writes require a fresh user click, unlocked device, listening/alive tile,
  falsing approval, current generation and <=1500 ms age. A fresh provider read
  must match the displayed value. A single native setter is followed by a read
  and final lifecycle check; failure is not retried or blindly rolled back.
- A bounded single worker performs provider calls. Five-second refresh runs only
  while listening; callbacks, listeners and refresh scheduling are cleaned up on
  destroy. Stale generations cannot publish or start another action. An in-flight
  Binder call cannot be cancelled; a click during a busy read is dropped, not queued.
- Long press is a separate action, through the verified implemented native
  ActivityStarter backend to the exact enabled/exported MainSettingsActivity.
  It cannot reach the provider setter. Missing launch capability disables only
  long press. Provider availability is still required for the tile itself.

Native reads can perform the host's one-time legacy preference migration. The
native setter is not a compare-and-set transaction and can partially update its
downstream catchers before failing. Re-reading confirms the primary preference,
not atomic completion of all native side effects. Disabling this module feature
does not undo a deliberate, completed Taplus setting change. Provider calls and
Activity launches have **not** been exercised on the device.

`docs/taplus-tile-evidence.json`: seven complete live MT members/manifest with the
APK hash, read/write/provider and catcher side-effect chain. Shared SystemUI,
plugin, framework and ActivityStarter manifests are linked rather than called
new live evidence from this batch. New tests define policy, action fencing,
external takeover and lifecycle-revocation cases, but have not run.

## Nine remaining pending rows

1. Snow leopard tile: upstream terminates `android.hardware.audio.service_64` and
   chmods `/dev/snd/pcm*`. No audio-service or permission action was executed. A
   bounded root transaction with durable original-mode restoration is still needed;
   a process-local boolean and hard-coded `660` restoration are not acceptable.
2. Sunshine normal mode.
3. Sunshine high mode.
4. Sunshine custom brightness. These three rows share one feature. Upstream's
   chmod-777 initialization, raw unlimited brightness write and partial restore
   need replacement with a verified device-specific range/ownership/restore contract.
5. Flashlight brightness: upstream redirects screen brightness and chmods torch
   nodes. No such device action was run; current controller and safe strength/range
   contract remain to be implemented.
6. Hide navigation bar: the **current launcher code carrier is found**, not merely
   missing. `Home.apk` has no DEX; `lib/arm64-v8a/libapp_launcher.so` contains Rust
   `hide_gesture_line_watcher.rs`, `is_hide_gesture_line` and system-navigation
   strings/imports. The shared rustruntime JAR is only 898 bytes and is not the old
   launcher implementation. A current native bridge/ABI must be traced; this batch
   does not pretend that old Java `NavStubView` hooks support it.
7. Manage all notifications: current Settings has all four `setupBlock()` methods.
   System-server channel enforcement and MIUI restrictions still need migration;
   enabling only the UI would not implement the feature.
8. Move statusbar gesture log: complete current SystemUI DEX-name/string searches
   found neither `GestureRecorder`/`mGestureRec`/`mLogfile` nor
   `statusbar_gestures.dat`. This scoped absence is not a global claim that the
   device never logs gestures. No unrelated files are moved and no empty switch
   is installed.
9. QS label: the exact preference occurs only in upstream XML/translations, not in
   the upstream Java/Kotlin consumer search. Current SystemUI search finds event
   analytics labels, not a proven feature target. The intended consumer still
   needs tracing; no guessed label mutation is installed.

The native searches above completed without pagination or skipped scopes for the
named library/query. They are not a complete native control-flow audit.
Raw reads/searches remain in `.workbuddy/tmp/mt-systemui/pending14-*`.
Original framework/services/Settings/SecurityCenter/ContentExtension extractions
remain available in the MCP directory for the continuing migration.

## Continuation contract

- Source ledger: `docs/pending14-source-progress-2026-10-11.json`; generator applies
  its five source-only mappings without promoting them to tested/device-verified.
- 337 rows: pending 9; existing-partial audit 27; other preexisting states unchanged
  except five additional `code_reviewed_partial_unbuilt` entries. SystemUI catalog
  now has 68 entries. Existing partial implementations are not silently completed.
- Finish the nine pending rows and retain/resolve these boundaries before running
  the requested unified tests/build/lint. Do not use the historical 772-test result
  or the installed 16:26:54 APK as evidence for this source.
- MT pagination now uses `textWindow.nextLine` when modern responses omit
  `pagination.returnedCount`; malformed/non-advancing pages still fail closed.
  Regression definitions were added, not executed.
- Nothing was committed, pushed, installed, restarted or enabled on the device.
