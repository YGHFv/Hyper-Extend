# Continuous migration worker - 2026-10-11

## Current source checkpoint: five of the remaining fourteen rows

`done=false`. WMShell four controls and the current-provider Taplus tile now have
source implementations, opt-in UI and installation chains. There are 27 new
complete live MT member/manifest records. Tests are defined, not executed.
337 rows: nine pending, 27 existing-partial audits; five rows move only to
`code_reviewed_partial_unbuilt`. SystemUI catalog: 68. No compile, tests, lint,
R8, installation, restart, hot reload or device-feature action in this batch.
Unified validation is deferred until the remaining migration is implemented.

Read `docs/pending14-migration-2026-10-11.md` and its source-progress JSON before
continuing. WMShell's implementation JAR is available via root extraction; the
device SDK is 37. Launcher gesture code is Rust in `libapp_launcher.so`.
ContentExtension's current switch is private-preference/provider-backed, not the
old Settings.System key. The following missing-artifact statements and test
counts are historical and do not describe these new sources.

## Current checkpoint: MT restored and inline percentage lifecycle repair

`done=false`. MT is reachable at `.85:8787/mcp`; the old workspace API was
removed and saved workspaces were initially empty. The local Codex MT entry
still points to the timed-out `.58`; global configuration was not changed.
Repository helpers now discover/reopen current saved workspaces, enforce the
package/version identity and compare complete text hashes, with read-only guards.

Fresh SystemUI202602260/plugin183022200 reads match all **268 entries across
13 manifests** (262 distinct package/locator pairs; only historical terminal-LF
normalization differs for 102 entries). This covers existing native volume,
percentage, card, GMS, weather, network and media contracts, not runtime behavior
or a new global xref sweep. SecurityCenter is not in the complete permitted Home
listing; target Activity presence is still unverified. WMShell export has zero
DEX/classes and does not unblock the actual implementation. No access-policy
change or traversal of denied paths was attempted.

Source review found and repaired two inline slider-label gaps: safety mode can
bypass native hooks and leave owned text behind, so an owned-only pre-draw
observer now restores on disable/safety/hide; an equal native rich-text takeover
is no longer overwritten by plain-text restoration. Mode1 percentage shares
these repairs, mode2 overlay stays unchanged. Next-pre-draw/detach cleanup,
equal plain-text takeover and native capability-refresh boundaries remain.

Selected regression: **30 Kotlin tests**; full suite: **129 suites / 772 tests /
0 failures / 0 errors / 0 skipped**. Ten offline MCP-helper tests also passed.
Debug lint: **0 errors, 453 warnings, 2 hints**. Executed commands, refresh outputs and scoped hashes:
`docs/mt-reconnect-validation-2026-10-11.json`.
Findings and remaining scope: `docs/mt-reconnect-review-2026-10-11.md`.

337 rows unchanged: **14 pending, 27 existing-partial audits, 12 structural,
93 static, 10 repaired-static, 4 partial-static, 132 partial-source,
43 source-repair, 2 other**. SYSTEM_UI_FEATURES63 / STATUS_BAR_FEATURES17.
No runtime acceptance, package/Activity/device-setting action, Release packaging,
R8, installation, restart, hot reload, commit or push. The installed 16:26:54
APK does not include this or the two previous source-repair batches. Legacy
`_unbuilt` statuses retain their old cumulative hash gate; scoped validation
links do not silently promote them. Continue partial reviews and migration;
the remaining 14-key queue and 27 audits below are still open.

## Previous checkpoint: migrated-feature review and GMS settings continuation

`done=false`. First reviewed the previous network/weather repair and the current
GMS/card-list adapters. Three regression tests reproduced false GMS transaction
success: takeover of an earlier package, mismatch of effective enabled state,
and changes to an already-target package. Repaired full post-write checks,
whole-family final verification and still-owned compensation; selected regression
suites passed before continuing migration. Additional tests cover restoring a
manifest-disabled default and revocation during the last write.

Then implemented the missing GMS long-click route as a separate SETTINGS action
that cannot enter the package-write branch. Exact system/enabled/exported/
permission checks guard SecurityCenter GmsCoreSettings. Optional reflection and
the actual native starter selector gate availability; the known inert refactor
and unknown backends are rejected. Main-thread native ActivityStarter receives
explicit owner UserHandle after final lifecycle/user/age/capability rechecks.
Missing settings capability leaves ordinary toggle available.

MT currently refuses connection. Eight complete cached native launch-contract
members and the upstream route support this source implementation, but current
SecurityCenter target presence and actual launch are NOT verified. No package
toggle, Activity launch, install/restart/hot reload or device-setting change ran.
No Release packaging/R8, commit or push ran in this batch.

Review scope, findings, fixes and remaining boundaries:
`docs/migrated-feature-review-2026-10-11.md`.
Executed tests/lint, reproduction results and repaired-source hashes:
`docs/migrated-feature-review-validation-2026-10-11.json`.
Native route evidence: `docs/gms-settings-evidence.json`.

Validation: the original transaction failed three of the 12 reproduction tests;
after repair, 52 selected review tests passed. The continuation then passed 35
selected tests, followed by **128 suites / 767 tests / 0 failures / 0 errors /
0 skipped**. Lint passed with **0 errors, 454 warnings, 2 hints**. Nine new test
definitions ran. These results are local validation, not Activity/package-action
or per-feature runtime acceptance.

337 ledger rows remain **14 pending, 27 existing-partial audits, 12 structural,
93 static, 10 repaired-static, 4 partial-static, 132 partial-source,
43 source-repair, 2 other**. SYSTEM_UI_FEATURES63 / STATUS_BAR_FEATURES17.
No status was promoted and no full337/runtime acceptance is claimed. The prior
installed 16:26:54 APK contains neither this batch nor the preceding repair.

## Previous checkpoint: partial-feature repair batch (network speed and weather)

This checkpoint supersedes the older build/queue statements below. `done=false`.
The earlier unified pass ran 747 tests, built Release and installed it at
16:26:54; that installed APK does NOT contain the source repairs in this batch.
This batch does not install, restart, hot-reload, change settings/scopes/network,
commit or push. Validation results and exact repaired-source hashes are recorded
in `docs/partial-feature-repair-validation-2026-10-11.json`.

Validation completed: 45 targeted tests, then **127 suites / 758 tests /
0 failures / 0 errors / 0 skipped**. `:app:lint` passed with **0 errors,
454 warnings, 2 hints**. Eleven new tests ran; no Release packaging, R8 or
installation was requested or executed for this batch.

### Actual behavior repaired

- Network speed: `StatusBarNetworkSpeed` now resolves the exact static
  `TrafficStats.getTxBytes(String):long` and `getRxBytes(String):long` queries
  used with `lo` by the complete cached current native SAMPLE handler. Resolution
  is inert and required only when custom sampling is selected. Each direction
  subtracts its loopback delta before formatting; initial, unavailable, reset,
  incoherent and sleep samples keep native text instead of displaying fabricated
  zeroes or spikes. A loopback-only reset also invalidates the sample. Read
  failures discard the previous sampler; aggregation remains overflow-safe.
  No new hook entry point, sampling worker, timer or device write is added.
- Evidence is `docs/network-speed-loopback-evidence.json`, reusing the complete
  cached SystemUI handler and a two-signature call-site fixture. This is NOT a
  fresh device capture or a claim of complete TrafficStats method-body evidence:
  that class is not declared in the cached framework.jar. Exact static/type
  matching remains mandatory at runtime.
- Header weather: new pure `HeaderWeatherRefresh` state separates temporary
  hide/detach from disable/safety/user invalidation. The same header instance
  retains only its current-user weather snapshot and 15-minute attempt cooldown
  across hide/detach; it cancels the pending native request and removes its label.
  Reopening no longer issues an immediate duplicate query. Failed/cancelled
  attempts are rate-limited too; a never-delivered callback cannot block retries
  forever. Disable, safety, user change and tracker loss discard the cache.
  Obsolete/duplicate callbacks are rejected before parsing and cannot cancel or
  overwrite a newer user's request. Existing 6-hour data age checks remain.
- Catalog descriptions, inventory boundaries and source-audit links now describe
  these actual behaviors. Five new network tests and six weather lifecycle tests
  exercise the production policies; no assertions or host-signature requirements
  are removed. See the validation manifest for executed results, not older
  "test definitions only" statements.

### Remaining boundaries and queue

- Network sampling still happens at the native UI text callback rather than
  using the exact background sample. The four reads are not atomic; inconsistent
  samples fail native. Fixed-width/container measurements, native animations and
  font/layout/safety restoration still require runtime review or restart.
- Weather cooldown is per header instance, not shared across independent header
  recreations. Provider permissions, real layout/animations and runtime lifecycle
  acceptance remain unverified. There is no new provider query while hidden.
- 337 ledger rows remain: **14 pending, 27 existing-partial audits, 12 structural,
  93 static, 10 repaired-static, 4 partial-static, 132 partial-source,
  43 source-repair, 2 other**. SYSTEM_UI_FEATURES63 / STATUS_BAR_FEATURES17.
  No row is promoted to complete or device-accepted. The legacy `_unbuilt`
  labels still follow the old cumulative hash gate; the new scoped validation
  is linked separately through `repairValidationManifest`.
- The pending list is the 14-key list in the GMS checkpoint, NOT the older
  15-key snapshot containing GMS. The 27 audits and all other named partial
  residuals (media, VoWiFi editing, volume, 5G, material, etc.) stay open.
  This batch is not the required full 337-row functional rereview.

## Earlier recovery point: GMS tile and native low-end media progress repair

- `done=false`. Current337: **14 pending, 27 existing-partial audits, 12 structural,
  93 static, 10 repaired-static, 4 partial-static, 132 source-only partial,
  43 source-only repairs, 2 other**. SYSTEM_UI_FEATURES is now **63** (actual GMS
  feature added, corresponding test expectation updated); STATUS_BAR_FEATURES17.
  Previous62 assertion was preserved until this real new feature was added.
- GmsTileHooks/Targets/Policy/EditorHooks: independent default-off `control_center_gms_tile`,
  `hyperextend_gms` spec. Current complete34-method QSTile proxy, native BooleanState
  copies, full factory/dispatch callers, additive classic/plugin editor candidates.
  No ScreenLock/NFC/native tile repurposing and no writing saved tile selection.
  New module vector, catalog/panel, ControlCenterHooks and shared plugin discovery
  chain are connected. Core GMS must exist; no installing missing family packages.
- GMS explicit click handles ten existing allowlisted packages. Recheck main-user,
  unlocked, permission, apps-control restriction, falsing, generation and1500ms
  intent age, compare UID/version/raw/effective state snapshots before and during
  serial writes. Once accepted, the transaction/compensation gets a separate5s
  budget with authorization/lifecycle checks before every write; an IPC already
  in progress cannot be cancelled. Default raw state0 follows actual ApplicationInfo.enabled rather
  than assuming enabled. Disable core last/enable first. Setter throwing after a
  write is handled by read-back and best-effort reverse compensation of only matching
  owned values, no retries. Revocation/user change/time expiry stops further writes;
  partial outcome is logged and re-read. Disabling module does NOT undo completed
  deliberate package-state actions. Long-click SecurityCenter route remains open.
- Actual package state calls were **not executed**. Evidence is current MT21 complete
  SystemUI entries (163 method/field fixture members), plugin8 entries, cached
  framework6 exact members/full instruction bytes, and current manifest declaring
  CHANGE_COMPONENT_ENABLED_STATE. Complete factory/editor xrefs have hasMore=false.
  Manifests: gms-tile-systemui/plugin/framework-evidence.json. No installation,
  reboot, hot reload, scope/network/SIM/settings/package toggle, commit or push.
- MediaNativeHeightScope/Hooks fixes an actual existing gap for BOTH ordinary and
  island progress: current drawPrimaryMode uses inherited ProgressBar.getMaxHeight,
  and level1 invokes BOTH primary and high-end drawing. Previous mProgressHeight
  override alone did not cover low-end/primary geometry. Exact identity-bound
  synchronous getter scope plus existing temporary mProgressHeight now agree,
  with finally cleanup and content/max-height bounds. No setMaxHeight/requestLayout,
  changed touch range, SeekBar replacement or replay. Both adapters require all
  hooks ready. Ordinary media additionally keeps dragging/pressed drawing native
  and requires attached/shown/awake/window-visible like the island path.
  Evidence: media-native-height-evidence.json, current complete draw/xref and
  cached getter bytecode. New tests: GmsTileMigrationTest8 and MediaNativeHeightTest3,
  plus IslandBackgroundMigrationTest6 from this continuation; **17 definitions,
  zero executions**. No Gradle/compiler/unit tests/lint/R8/build run.
- Navigation investigation corrected another inherited assumption: upstream
  SystemUIV imports `rules.home.navigation.HideNavigationBar`, whose four hooks
  ONLY target Launcher RecentsContainer/NavStubView; it is not a SystemUI navbar
  window-removal implementation. The requested feature is NOT deleted/reclassified
  complete. Read-only MT opened exported current Home801027726 as `q4gtdzv4`:
  **zero DEX, android:hasCode=false, required uses-library hyperos.rustruntime.v5**.
  This export does not provide the actual Launcher implementation. Raw
  continue-home-open/manifest; full manifest SHA256
  a893eff40c8b2d6a30ff7f23b7d9ddc5f3160236b8e6b8d8bd5843ae8cd9e911.
  No scope/device change. Continue other work; do not pretend resource APK proves
  old methods present or that hiding the SystemUI line implements Launcher hooks.
- Still pending14 keys: snow_leopard_mode; Taplus; sunshine_new_mode,
  sunshine_new_mode_high, sunshine_mode_brightness; security_flash_light_switch;
  hide_navigation_bar; allow_manage_all_notifications; disable_miui_multi_win_switch,
  remove_miui_multi_win_switch, disable_infinitymode_gesture, disable_bottombar;
  move_log_to_miui; qs_label. WMShell com.miui.wm.shell access evidence is below.
- Still audit27: right battery slot1; short/long strong-toast test actions2; generic
  Wi-Fi/hotspot/airplane/data-saver/volume/speakerphone/call-record producers7;
  mobile no-card/newHD/smallHD/bigHD/hidden-big-mobile-type5; lyrics bridge12.
  All partial residuals remain: VoWiFi arbitrary card edits; media exact AGSL/
  transitions/AOD, native shader power/visual parity; volume replacement/scale/
  buttons/hold;5G mode3/icon/Phone route; material first-frame/safety and others
  already listed in earlier checkpoints. Unified build/lint/full337 review not begun.

### Immediate continuation and files saved

- Next concrete flashlight evidence is now cached, not blocked on all device access:
  `continue-flash-strength-controller` is the full current
  MiuiFlashlightControllerImpl; `continue-flash-strength-write` is its actual
  `$$ExternalSyntheticLambda3.run` dispatch (not a guessed `$7` class).
  setFlashlight preserves force-off/battery-off, worker checks torch availability;
  for max strength exactly100 it reads Global `mi_flashlight_strength` and calls
  CameraManager.turnOnTorchWithStrengthLevel, otherwise native setTorchMode.
  Full `$8` node fallback and `$9` callback classes also cached. All haveMore=false.
  Continue trace of the native strength editor/setting writer and precise safety
  restoration before adapting the requested brightness-slider behavior. Nothing
  invoked or written. Whole-SystemUI sunlight_mode literal search returned0;
  this does not establish absence in Settings/framework or justify an empty tile.
  Raw searches: `continue-next-tile-0/1.json`.
- Saved new production files under `hook/feature/systemui/`:
  IslandBackgroundHooks/Targets/Policy.kt; GmsTileHooks/Targets/Policy/EditorHooks.kt;
  MediaNativeHeightHooks/Scope.kt. New drawable `res/drawable/ic_qs_gms.xml`.
  Modified IslandMediaSettings, StatusBarFeatures, SystemUiFeatures, FeaturePanels,
  MediaCardHooks, ControlCenterHooks, SystemUiPluginHooks, MediaProgressHooks and
  IslandProgressHooks. Three new test classes and SystemUiFeaturesTest count63.
  Five new evidence manifests and five full-signature fixtures; README, three
  entry/handoff headers, this progress file, generator and both inventories updated.
- Hand-read new callback ownership, shader restoration, tile worker exception/
  pending-click/generation paths and main installer/discovery connectivity.
  This is NOT compilation, unit-test execution, lint, runtime acceptance or the
  still-required full337 fresh functional review. `git diff --check` only checked
  whitespace. Do not run Gradle until all substantive migration/residual repair is
  complete. No agents, background migration commands or commits were created.

## Latest recovery point: independent island background and palette (source only)

- `done=false`. Current337: **15 pending, 27 existing-partial audits, 12 structural,
  93 static, 10 repaired-static, 4 partial-static, 131 source-only partial,
  43 source-only repairs, 2 other**. SYSTEM_UI_FEATURES62 unchanged;
  STATUS_BAR_FEATURES17. No Gradle/compiler/tests/lint/R8 or device changes.
- Added IslandBackgroundHooks/Targets/Policy, default-off independent background
  catalog/config/panel and MediaCardHooks installation. Six upstream island
  background rows now have actual source adapters, not ordinary-holder aliases.
  Full current MusicBgView and both MusicBgShader strategies were read. The real
  background has one shader-owned child; retain it, its parent, native animation,
  additional-view synchronization and transforms. Only its owned alpha and the
  parent background change; native takeover, detach, hide, disable and safety
  restore owned values. No removing/reparenting views or replacing callbacks.
- Bounded four cover modes/Monet, linear inversion, paired color/cover transitions,
  native-mode ambient 0/native, 1/hide, 2/custom. Shared job/generation and paused
  ambient clock for both holders. Use already resolved native artwork/app icon;
  no new URI/PM image loading. Text/actions/time/seamless and actual seekbar fields
  have ownership restoration; high-end seekbar Paint tint is scoped to draw and
  finally restored without changing shader, texture or alpha. Current UI-mode
  caller discovered by complete xref and included in deoptimization.
- Evidence: `docs/island-background-current-evidence.json`, 53 full members plus
  four fully paginated shader/view classes, matching fixture. Six policy/signature
  tests defined in IslandBackgroundMigrationTest, **not executed**. No current
  compile/test/runtime result; historical606 remains inapplicable.
- Boundaries: native shader work still runs while its visual output is hidden;
  no power-saving claim. Sampled artwork/dominant bins differ from upstream
  processors/MiPalette. Exact AGSL, all resize/pull-down/color-transition/contrast
  parity and device rendering acceptance remain open. Native first attach/restart
  is required to enable. Existing layout/progress and all other partial residuals
  are not reclassified as complete.
- WMShell new concrete evidence: current SystemUI manifest requires uses-library
  **`com.miui.wm.shell`**, not merely resource package `com.android.wm.shell`.
  `current-systemui-manifest.txt` SHA256
  `b2f1805b6df881a899b806ef1f53928b08ccc4984fa6959f53b40c02eb6b45d9`.
  Latest APK listing has20 entries, hasMore=false, only resource shell APK.
  Allowed MT export directory has21 files, no implementation JAR. Reads of
  `/system_ext/etc/permissions` and `/system/etc/permissions` are FILE_ACCESS_DENIED
  by MT file policy; prior framework directory also denied. This is an external
  shared-library implementation access dependency, NOT proof the library is absent.
  Raw: next-available-apks and shell-library-discovery-0/1/2.
- Continue actual GMS/other pending tiles next; Taplus needs current contentextension
  provider/setting contract, not just a legacy key. Remaining27 audits: right
  battery slot1, strong-toast test actions2, generic icon producers7, mobile
  variants5, lyrics12. Pending15, all earlier bounded partial residuals, unified
  build/lint and full337 rereview remain unfinished.

## Latest recovery point: real island layout/text/progress and parsed strong-toast delivery

- `done=false`. Current337: **15 pending, 33 existing-partial audits, 12 structural,
  93 static, 10 repaired-static, 4 partial-static, 125 source-only partial,
  43 source-only repairs, 2 other**. SYSTEM_UI_FEATURES62 remains unchanged;
  STATUS_BAR_FEATURES16 now includes independent island layout/text/progress and
  hide-strong-toast. No structural row or subordinate status was mass-promoted.
- No Gradle/compiler/tests/lint/R8, packaging/install/restart/hot reload/device
  writes/settings/scope/network/SIM changes, commit/push or agents/background
  migration workers. Eight new test definitions are NOT run. Historical606 is
  not current validation. Metadata generation only; build remains deferred.
- StrongToastHooks/Targets/Policy: traced actual current `strong_toast_action`
  command to DeviceNotificationListenerImpl.setStatus, native Gson validation,
  then handleDeviceNotification. Suppress only the synchronous parsed external
  delivery with owner/bundle identity and nested ThreadLocal cleanup; preserve
  command distribution, parser/logs, internal battery animation and all removals.
  No old class-name matching or globally cancelled pre-draw. Four full current
  members and sole full CommandQueueDelegate dispatch caller; source-only.
  Existing prompts expire natively; next command restores when disabled. Short/
  long test actions remain separately unresolved, not reclassified as switches.
- IslandMediaSettings + IslandMediaHooks/Targets/Policy: actual independent
  MiuiIslandMediaViewBinderImpl and primary/dummy MiuiIslandMediaViewHolder.
 13 layout/text rows now wired: album/app marker, seamless, order/left alignment,
  title/artist margins, icon sizes and title/artist/time sizes. Native reset loads
  one ConstraintSet and applies it twice; a scoped copy changes the verified XML
  constraints BEFORE those native applies and restores the original template
  field in finally. No extra apply, reparent or listener/session replacement.
  Visibility is owned by bindings rather than polluted template defaults. Owned
  SP sizes and icon matrices restore before native callbacks and on detach/
  disabled/safety pre-draw; native takeover becomes the new baseline. Animated
  drawables/touch targets retained. Semantic middle3 slots treated as primary,
  including native prevOrCustom/nextOrCustom; ordinary actions use custom size.
  Layout constraints themselves still require reset/restart for full recovery.
  Corrected two upstream discrepancies: size XML labels dp but hook uses SP;
  artist XML default2dp but hook default4dp. Local defaults preserve native.
 41 complete members plus current primary/dummy XML and ConstraintSet XML;
  full attach/detach/info/seamless/actions/reset and template field xrefs.
- IslandProgressHooks/Targets:7 independent progress rows, real/dummy pair,
  exact attach/detach/observer/onDraw; preserve SeekBar, range, RenderState,
  session, touch listeners and accessibility. The CURRENT ordinary and island
  controllers both use HyperProgressSeekBar (the initial assumption that their
  current SeekBar types differ was incorrect); they have different holders and
  must not share parent settings. Native size is temporary during drawing;
  custom wave/line/comet/thumb use current theme color, not level2 no-op setters.
  Awake/unlocked/display0 only; pressed/dragging stays native. Native animation
  flags and system animator setting retained; weak maps, no new poller or job.
 31 complete entries. Four complete LiveData dispatch callers plus register
  also added to ordinary MediaProgressTargets/manifest/fixture (43 entries).
- Important correction: upstream network_speed_all_status_enable is ONLY the
  installation parent for NewNetworkSpeed/Style/Spacing/Sec in BOTH SystemUIV
  and SystemUIB, not an all-status placement feature. Existing local parent and
  runtime gates actually implement it. The row is source-repair-unbuilt; older
  assertions about a separate missing placement were unsupported and superseded.
- Files: new IslandMediaSettings; StrongToastHooks/Targets/Policy;
  IslandMediaHooks/Targets/Policy; IslandProgressHooks/Targets;
  IslandMediaMigrationTest (8 definitions);3 new host fixtures/manifests.
  Changed StatusBarFeatures, FeaturePanels, HookDispatcher, MediaCardHooks,
  MediaProgressTargets, ordinary progress fixture/manifest and both generators'
  inventories. No current-source test/compile acceptance is claimed.

### Remaining queue now

- All15 pending actual keys remain.33 audit rows: right-side battery slot;2
  strong-toast test actions;7 generic icon policies/producers;6 island background/
  color/ambient controls;5 mobile marker/style variants;12 lyric entries.
  All earlier bounded partial/static/unbuilt residuals remain independently open.
- Latest pending investigations: cached Settings17/37 has AppNotificationSettings
  systemApp + permission + ApplicationHelper + ECM gates, Base channel blockable/
  lockedChannel gates, MiuiNotificationBackend and backend save paths. Merely
  enabling preference would not establish manage-all-notifications backend parity;
  no fake UI unlock saved. Snow-leopard upstream kills audio HAL then chmods PCM
  device nodes; no such commands executed/copied without reversible current
  device contract. GMS/Taplus and sunlight/flash intensity still unimplemented.
- Island six background settings require full current MusicBgView/MusicBgShader
  lifecycle/copy synchronization, not ordinary holder reuse. Full paginated
  classes and current native setGradientColor, setMusicBgShader, foreground
  writers are cached as next-*; continue there. Lyrics integration12, VoWiFi
  arbitrary card edits, volume/native-panel residuals and full337 review remain.
- WMShell implementation shared-library access, hidden QS-label semantics and log
  successor writer still unresolved; don't turn missing old names into absence.
  Continue all remaining substantive migration before unified Gradle and lint.

## Latest recovery point: battery telemetry, geometry, signal order, alarm window and screenshot

- `done=false`. Current337: **15 pending, 55 existing-partial audits, 12 structural,
  93 static, 10 repaired-static, 4 partial-static, 104 source-only partial,
  42 source-only repairs, 2 other**. Catalog counts are scoped: SYSTEM_UI_FEATURES62,
  STATUS_BAR_FEATURES12 (two new entries);62 is NOT the total feature catalog.
- No Gradle/compiler/tests/lint/R8/build/install/restart/hot reload/device changes,
  commit/push, sub-agent or background migration worker. Historical606 tests do
  not cover this source. New test definitions and evidence are not runtime proof.
- BatteryDetailSettings + BatteryDetailHooks/Policy/Targets: default-off left-clock
  sibling telemetry label,17 formatting/sampling settings. Current exact inflate,
  typed field and XML parent identities; native clock/hierarchy retained. Lazy
  bounded battery reads; generation/staleness rejection; hide/detach/lock/sleep/
  disable/safety removes owned label and stops polling. BatteryManager microamps,
  read-only sticky voltage/temperature; no sysfs/device writes. Right independent
  slot remains unimplemented: current battery containers measure named children,
  icon container requires StatusIconDisplayable. No fake type91 or empty switch.
- BatteryOrderHooks/Targets replaces ineffective child reorder: native current
  onLayout lays out named fields. Swap horizontal rectangles after native layout
  for ordinary HOME/style3 only, preserving sizes/gap/vertical geometry/identity.
  Charging/transformed/island/AOD/expanding states stay native. Next native layout
  restores.15 complete members and fully paginated layout/measure evidence.
- SignalOrderHooks/Targets/Policy: new default-off feature edits the fresh native
  factory slot backing list BEFORE publication, preserving slot identity and all
  unrelated positions. Reject holders/subslots/duplicates/non-native sequence.
  Exact factory and full Dagger caller,7 members + complete46-slot resource array.
  Shared list affects native panels/displays; reconstruction needed on disable.
  Resource array DOES include no_sim/hd; previous zero DEX strings did not prove
  absence. Their real producers are still unresolved.
- AlarmWindowHooks/Targets/Policy: existing alarm choice mode3 plus0..48h setting.
  Filter original isIconVisible only; native false/block lists/minimalism win.
  Capture existing UserTracker; current-user locked_screen_alarm_status epoch
  matches actual native observer, AlarmManager fallback.30s asynchronous reads,
  35s freshness,59s threshold tolerance, user/generation invalidation and weak
  view lifecycle. No scheduling/provider writes/locale weekday fabrication.
 16 complete current members; keyguard alarm text remains native.
- ScreenshotStatusBar/ScreenshotCaptureTargets/StatusBarPolicies repair: exact
  screenshot10603038 d1.d strategy and ScreenCaptureInternal builder signatures,
  not availability of either unrelated capture API. Main display, skip rear
  capture, append StatusBar only to per-call exclusions preserving identity if
  present. No View/Surface mutation or captures initiated.10 complete declarations
  with code hashes from CACHED screenshot APK/framework, not new installed-host
  confirmation; complete62080-method scan found sole DisplayCapture caller.
- Source clarity repairs in IconBlockListHooks (Elvis covers both branches) and
  WifiIndicatorHooks (weak-view property name). Known battery-order fixture call
  extra argument corrected on resume. Manual source read of new lifecycle and
  failure handling performed; full337 audit and all runtime acceptance remain.
- New tests: BatteryDetailMigrationTest, SignalOrderAndAlarmTest; changed
  StatusBarPoliciesTest. Five fixtures51 entries (41 current MT,10 cached).
  Matching manifests: battery-detail-current, battery-order-current,
  signal-order-current, alarm-window-current, screenshot-layer-cached. Defined,
  NOT executed. Generator and both inventories synchronized.

### Exact remaining work after this checkpoint

- All15 pending keys remain (snapshot later in this document). All55 untouched
  audits, partial/static/unbuilt residuals, unified build/lint and full337 review
  remain. Do not convert structural navigation or source coverage to completion.
- Battery right slot; island/strong-toast29 and lyrics12; actual no-SIM/HD/record/
  speakerphone producers; network speed all-status placement; VoWiFi arbitrary
  card order/edit/suppression; full media palettes/AGSL/resize/island/AOD; native
  volume replacement percentage and scale/buttons/hold;5G mode3/icons/Phone
  Activity; material/blur first-frame and safety recovery.
- Pending actual features: snow leopard, GMS/Taplus, three sunshine controls,
  flashlight intensity, whole navigation bar, manage all notifications, four
  WMShell controls, log destination, hidden QS-label requirement. Hidden QS-label
  has real hidden-parent preference but no established consumer/semantics; not
  deleted. Log exact old names missing is NOT proof no successor writer exists.
- WMShell implementation DEX unavailable in prior reads; denied shared framework
  directories and resource-only APK are real evidence-access limits, not proof
  absence. Continue read-only shared-library discovery; other work continues.

## Latest recovery point: status-bar lists, indicators and native Wi-Fi standard filter

- `done=false`. Catalog62. Current337: **15 pending, 75 existing-partial audits,
  12 structural navigation, 93 static, 10 repaired-static, 4 partial-static,
  85 source-only partial, 41 source-only repairs, 2 other**. Thirty prior audit
  records now have bounded source implementations/repairs; three old repaired
  battery visibility records are downgraded to unbuilt. No compiled/device
  promotion, no structural-child status changes.
- No Gradle/compiler/tests/lint/R8/build/install/restart/hot reload/device write/
  feature/scope/network/SIM changes, commit/push, sub-agent or background worker.
  Historical606 tests do NOT cover this source. Twelve new test definitions
  are saved, NONE run. Seven new fixtures contain141 entries (140 complete
  current-MT manifest member entries, plus one separately identified cached
  ActivityThread declaration); these are NOT all337 functional coverage.

### Actual saved implementations

1. `IconBlockListHooks/Targets`, `StatusIconPolicy/Host`: replace installation-time
   static RIGHT/CONTROL_CENTER list mutation with exact native instance setter
   inputs and owned per-container ignoredSlots deltas. Current ModernStatusBarView
   setBlocked is a no-op and IconManager's Wi-Fi creation discards the blocked
   parameter; therefore actual parent ignoredSlots filtering is also updated for
   keyguard/QS_FAKE, not only the manager list. Restore only owned previous list
   before incremental native adds, preserve native takeover, then apply fresh
   per-role policy. Native minimalism, user hide lists, icon availability, island
   space, animation and other producer gates remain active. Disable returns to
   native at the next list callback; safety/full reconstruction needs restart.
   25 full current members and complete relevant setter/default-list xrefs.
   Twenty-one upstream rows with actual native-default-list slots are source
   partial, not full always-show parity. Other candidate aliases are NOT promoted.
2. `BatteryVisibilityHooks/Targets`: four exact native refresh methods, twelve
   complete callers and eight typed fields, common readiness plus runtime main
   thread/display/version gates. Preserve solid/hollow/digital/charging hiding
   and number/mark zero-size precedence. Remove nonexistent legacy digit field
   from policy and its legacy-only test expectation, not a user functionality.
   Fixed load-time settings; full restoration needs restart as with battery style.
   24 full members; previous three repaired records now explicitly unbuilt.
3. `WifiIndicatorHooks/Targets`: current WifiRepositoryImpl has NO declared
   constructor, so old activity code returned without installing. Replace that
   and Wi-Fi-standard Flow field replacement with native bind/render hooks.
   Original resource IDs/tags/cache, theme/tint, model, subscriptions/cancellation
   stay intact. Weak owned visibility snapshots restore on detach or disabled/
   safety pre-draw, never over native takeover; repeated bind restores before
   native cached initialization. No strong map-to-view chain or custom jobs.
   Main display;16 complete members. Mode2 hides, mode0 native.
4. `WifiStandardHooks/Targets/Policy`: additionally wire mode1 under the EXISTING
   standard key. Exact current merged mapper classId2 filters <=5 after the raw
   native calculator. A temporary assignable FlowCollector interface bridge
   recovers only native4/5 outgoing zero, not a fabricated standard or another
   merged mapping. Original mapper, one downstream emit, continuation and
   suspension result remain intact; finally identity-restore and deactivate bridge
   even on failure/takeover. Runtime-only currentApplication/version lookup,
   no early singleton read. Native availability/forced-hide/metered/carrier-merged/
   slave/chipset decisions remain native. Shared Wi-Fi VM means mode1 is process-
   wide and can also affect native activity placement; mode2 is main-display-only.
   Disable waits next emission, mode changes/legacy cache/safety need restart.
   15 current members + cached framework declaration (not fresh device evidence).
5. `NotificationIconLimitHooks/Targets`: replace post-setter mMaxIcons overwrite
   with exact NotificationIconsViewData copy BEFORE native collector difference,
   binding and MaximumIndex failure accounting. Retain list/order/filtering,
   native limit type and zero suppression; both collector and view role must be
   statusbar[0], actual version/display/thread must match. AOD/shelf, geometry,
   dots, flip-screen and island space stay native. Disable waits next emission/
   rebind, no stale replay or new coroutine.18 full current members.
6. `MobileMarkerHooks/Targets/Policy`: exact current merged emitters and class IDs
   distinguish VoWiFi, roaming, small roaming and activity from speech-HD,
   satellite, network-type and no-service entries. Replace only native Boolean/
   Pair presentation input; preserve roaming's first-or-second writer condition
   and activity's valid-update gate, original animator-container writer and
   per-SIM lifecycle. No concrete Flow/VM/OperatorConfig writes. Main display;
   native emit/rebind restores, safety/legacy replaced-flow cleanup needs restart.
   29 full members. Three upstream rows source-repair-unbuilt.
7. `OperatorIconHooks/Targets`: current OperatorConfig also has NO declared
   constructor, so old first-constructor adapter was inert. Hook complete
   getMiuiOperatorConfig(int) after its sole new-object return; set only hideVolte
   before publication, preserving all other native operator fields/calculations.
   Four exact callers plus actual flag readers, runtime version/context/option,
   no cache iteration or service initialization/telephony/IMS/network write.
   Native display config is process-wide; cache/HD-map/Flow refresh and disable
   follow native lifecycle, full recovery may need restart.13 full members.

### Source and evidence files added/changed in this segment

- New statusbar sources: StatusIconPolicy/Host.kt; IconBlockListHooks/Targets.kt;
  BatteryVisibilityHooks/Targets.kt; WifiIndicatorHooks/Targets.kt;
  WifiStandardHooks/Policy/Targets.kt; NotificationIconLimitHooks/Targets.kt;
  MobileMarkerHooks/Policy/Targets.kt; OperatorIconHooks/Targets.kt.
- Changed StatusBarIcons.kt / StatusBarMobile.kt install chains, isolated failures
  by subfeature; StatusBarFields.kt removes Unsafe fallback now that its concrete
  Flow replacements are gone; BatteryVisibilityPolicy.kt; StatusBarFeatures.kt
  retains all feature keys and adds the real standard mode1 rather than an empty
  choice. Removed claims that absent old names prove island/detail/alarm/swap
  incompatibility; unsupported candidates remain explicitly open.
- New StatusIconMigrationTest.kt (12 definitions); updated
  BatteryVisibilityPolicyTest.kt. Full fixtures and matching docs manifests use
  prefixes battery-visibility-current, icon-block-list-current,
  wifi-indicator-current, wifi-standard-current, notification-icon-limit-current,
  mobile-markers-current, operator-icons-current.
- tools/systemui_inventory.py and both generated inventories; recovery/entry
  documents and README synchronized. Only metadata generation and whitespace
  checking allowed so far. Latest git diff --check passes, not build validation.

### Exact current open queue and findings

- The pending-key snapshot below remains exactly15; all75 unreviewed rows plus
  explicit partial/unbuilt residuals still require substantive work. Unified
  build/lint and post-build review of ALL337 remain deferred and unperformed.
- Current exact-string slot searches (all hasMore=false) show no `no_sim` or
  `record`; `nosim` occurs only in network/demo data, `hd` hits only unrelated
  Lottie parser keys, `speakerphone` only demo paths. NOT proof that the requested
  feature is impossible: trace actual modern producers and framework slot
  resources. Wi-Fi/hotspot/airplane/data_saver have Dagger/provider/resource
  routes still to finish reading; do not promote via candidate aliases. Raw
  icons-slot-*.json retains complete search scope/skipped information.
- Wi-Fi/mobile reordering and alarm-before-ring remain actual residuals. Battery
  detail18, island/strong-toast29 and lyrics still need implementations. Next
  substantive source to read: upstream statusbar/battery/BatteryDetailIndicator.kt,
  reuse only independently verified current telemetry/host placement, not stale
  ChargeUtils absence claims. Current LockscreenChargingHooks already has native
  ChargeUtils access; do not infer battery detail missing from the older comment.
- VoWiFi arbitrary card editing/suppression, media full palettes/AGSL/resize/
  island/AOD, replacement native-volume percentage, volume hold/scale/buttons,
  5G mode3/icon variants/Phone Activity evidence, carrier variants and earlier
  material/blur safety/first-frame boundaries stay open. Existing summaries below
  are historical where they say standard mode1/VoLTE constructor is unimplemented.
- WMShell evidence-access dependency unchanged: no implementation DEX, policy-
  denied system framework directories; resource-only APK is not implementation.
  Launcher whole-nav counterpart, manage-all-notification backend parity,
  GMS/Taplus/sunlight/flashlight/snow-leopard/log writer/hidden-parent QS label
  still need actual implementations or precise remaining dependency evidence.


## Latest recovery point: material, window blur, battery, gestures and network speed

- `done=false`. Catalog 62. Current 337 rows: 15 pending, 105 existing-partial
  audits, 12 structural navigation, 93 static, 13 repaired-static, 4 partial-static,
  62 source-only partial, 31 source-only repairs, 2 other. Five battery style
  audit rows now have actual control-flow fixes, plus one gesture and fifteen
  network-speed audit rows. Four old repaired battery style rows and two old
  repaired network-speed rows are downgraded to unbuilt because source changed. Nothing is promoted
  to a compiled or device-accepted state.
- All work and all earlier changes remain saved. No Gradle/compiler/unit test/
  lint/R8/build/install/restart/hot reload/device write/scope/network/SIM change,
  commit/push, sub-agent or background migration task ran. Historical606 tests
  do not cover this source. Only read-only evidence, metadata generation and
  whitespace checking ran. Unified build and post-build full337 review remain
  deferred until substantive migration and residual fixes are finished.

### New source implementations and actual limits

- `TileMaterialSettings`, `TileMaterialHooks/Policy/Targets`: default-off catalog
  and shared plugin-loader chain for current normal small/card tiles. The actual
  renderer uses ColorBlendToken; old holder updateBlendBlur methods are no-ops.
  Exact endpoint identity, copied six-array tokens, validated plugin array IDs,
  resource cache by Configuration; upstream collapsed-seekbar active material
  replaces only an actually different native palette (LAB color e6ff00 instead
  of native ebff00). Off palette already agrees and is left unchanged. Native
  interpolation, blend modes/order, material capability/default-theme/super-power
  gates, geometry and glass rendering remain native. Detail/edit/restricted/
  transient/traffic-warning/special-background states are excluded. No service
  or singleton value reads during installation. Six hooks, 43 full dispatch
  callers, weak bindings and observed native write/clear/material takeover.
  Disable/hide/detach/30s replay the latest native tokens only while root/state/
  Configuration ownership remains valid. Safety bypass makes writer observation
  unavailable, so it forfeits replay rather than overwriting newer native state;
  full safety recovery requires restart/native refresh. Volume/ringer material,
  glass parity and first-frame runtime coverage are still open.
  Evidence: `docs/tile-material-evidence.json`, 76 full members (four shared State
  fields from SystemUI, others from plugin); class/resource/xref captures named
  `material-*` in the MT cache. Six unexecuted test definitions and full fixture.
- `ForceBlurSettings`, `ForceBlurHooks/Policy/Targets`: default-off host catalog
  entry and SystemUiCustomHooks install chain. Native true support unchanged;
  only high-end gfx classification is relaxed after separately rechecking real
  hardware, persist.sysui.disableBlur and live cross-window enable state. No
  ActivityManager override and no settings/window creation. Application getter
  is invoked only at runtime, not while installing hooks. Positive added blur
  is limited to main-display applyBlur. Weak owner/root/radius tracking handles
  capability withdrawal/disable and old-root cleanup before retargeting. Only
  an owned zero-radius replay may bypass native support, retaining native valid
  Surface checks, frame transaction scheduling, last-radius/target bookkeeping
  and early-wakeup end. Detach/hide/30s bounded cleanup; safety still requires
  restart because both hooks are bypassed. Generic pre-application capability
  consumers cache a global answer, including possible secondary-display UI
  assumptions; this remains a named partial scope, not full global parity.
  Evidence: `docs/force-blur-evidence.json` 18 complete MT members and
  `docs/force-blur-framework-evidence.json` five cached framework declarations
  (explicitly not new device evidence); fixture23, four unexecuted tests.
- `StatusBarBatteryStyle`: replace first-name match with four exact nonstatic
  refresh methods, four typed fields, twelve full dispatch callers and common
  ready gate; runtime version/main-display/main-thread gate. Numeric limits now
  match catalog sliders. Repair hidden/absent percent-mark trailing padding,
  preserve default27 native mark baseline, and keep native font family when
  applying bold. Hide-percent precedence and native sibling-group ordering
  remain. Configuration is still fixed at load; changing/disabling and safety
  restoration require user restart, not unsafe live reparenting. AOD/island
  and native LayoutTransition rendering remain unaccepted.
  New `BatteryStylePolicy/Targets`, `BatteryStylePolicyTest`, 20-member current
  evidence/fixture. Three unexecuted tests. This is source review and repair of
  nine rows, not current compiled regression coverage.

### Precise remaining queue

1. Remaining15 pending: snow-leopard audio-node behavior; GMS tile; Taplus tile;
   sunlight new_mode/new_mode_high/brightness; flashlight brightness switch;
   whole navigation-bar hiding; manage-all-notifications across SystemUI,
   Settings and system_server; four WMShell behaviors; gesture-log destination;
   hidden-parent QS label. The bottom snapshot lists exact keys. No empty switches
   were added for these, no old-name absence was called functional completion.
2. 105 actual existing-partial audits: status icons/mobile/notification maximum,
   18 battery-detail entries, network-speed modes/layout, lyrics, gestures and
   29 island/strong-toast entries, plus all retained partial feature gaps.
   StatusBarNetworkSpeed exact-signature/initial-sample repair is now saved.
   Its all_status_enable remains separate, not an alias of the parent switch.
   Next follow real icon/mobile and island/battery-detail paths, along with all
   listed pending features, rather than reporting these repairs as completion.
3. Next concrete source review locations: StatusBarIcons.installIconSlots still
   writes shared static lists during installation; battery/icon-limit subhooks
   still use partial reflection; StatusBarFields permits Unsafe concrete-flow
   field replacement. These are real code paths to rework after tracing current
   producers/consumers, not evidence of completed icon migration. No changes to
   those files were made in this segment. Retained partial gaps include VoWiFi
   arbitrary order/editor, media AGSL/resize/
   full glass and island/AOD, Android replacement-volume percentage, native volume
   hold/scale/buttons, 5G cellular-detail mode3/icons/settings Activity evidence,
   carrier variants, native material first-frame/teardown/safety behavior.
4. WMShell implementation artifact remains unavailable under current read-only
   MT file policy (framework directories FILE_ACCESS_DENIED, allowed shell APK
   resource-only). This is an external evidence dependency only for those four
   features, not an explanation for the other work. No device alteration used.
5. Finish those implementations/repairs, synchronize inventory, then unified
   `:app:testDebugUnitTest :app:assembleRelease` and full lint, then review every
   function and all337 source/evidence decisions from the start. Do not run
   the deferred build prematurely or substitute the historical606-test result.

### Current segment modification list

- New core `TileMaterialSettings.kt`, `ForceBlurSettings.kt`; catalog/panel/install
  wiring in `SystemUiFeatures.kt`, `FeaturePanels.kt`, `SystemUiPluginHooks.kt`,
  `SystemUiCustomHooks.kt`; catalog count test now62.
- New `TileMaterialHooks/Policy/Targets.kt`, `ForceBlurHooks/Policy/Targets.kt`.
- Updated `StatusBarBatteryStyle.kt`, `StatusBarFeatures.kt`; new
  `BatteryStylePolicy/Targets.kt` in hook/feature/statusbar.
- New `TileMaterialTest.kt`, `ForceBlurTest.kt`, `BatteryStylePolicyTest.kt`;
  three fixture files and four evidence manifests named above.
- Inventory generator, both generated inventories, README and continuation docs.
- 19 new test definitions this segment, none executed. Last `git diff --check`
  passes; this is whitespace only, not tests, compiler or lint.

### Continued source repairs after that checkpoint

- `StatusBarGestures` no longer calls setOnTouchListener or tags the View. It
  observes the exact PhoneStatusBarView.onTouchEvent after native processing,
  restricted to actual MiuiPhoneStatusBarView background touches with a native
  handler and true native result. Preserve original event/return, child controls,
  dispatch interactive gate, touchable region and native long-press/shade logic.
  Capture history before dispatch; finger-only, single pointer, no buttons,
  unobscured, finite/in-bounds, main-display/main-thread attached visible gates;
  accessibility, cancel/drag, detach/configuration and disable invalidate the
  weak tracker. PowerManager.goToSleep(long):void is exact, resolved at install
  without obtaining/invoking a service, and invoked only after a completed pair
  with final settings/safety/interactive/accessibility rechecks. No power action
  was actually executed. Native accepted-background-only coverage remains a
  boundary, not expanded hit regions. Legacy installed listener requires user
  restart to replace; no hot reload performed.
  New StatusBarGestureTargets/Policy and three unexecuted tests; seven current
  MT members plus one cached framework declaration, fixture8. Evidence files
  `statusbar-double-tap-current-evidence.json` and
  `statusbar-double-tap-framework-evidence.json`.
- `StatusBarNetworkSpeed` now resolves twelve complete methods/callers and six
  typed fields with common selected-hook readiness. UI and background queue
  thread gates, actual SystemUI version and dynamic parent setting are checked.
  No unnecessary sampler for native default text. Sampler validity distinguishes
  initial/unavailable/reset/sleep counters from genuine zero traffic; retain
  native text until a valid interval, prevent aggregate Long overflow, reset
  controller sampler on native hidden/disabled callbacks. Actual native fallback
  units are not swallowed: owned unit visibility is hidden only for custom empty
  second slots and restored for real native unit data. Default alignment leaves
  native gravity; numeric inputs bounded to catalog. Native pending bg SAMPLE
  only is rescheduled on its own Looper; no queue revival/new worker/service.
  Disabled setting returns interval to native at the next callback; full layout
  and safety restoration still require restart. Three new tests are defined,
  not run; `network-speed-current-evidence.json` and fixture18.
  Read upstream NewNetworkSpeedStyle fully: lineSpacing is stored*0.05f passed
  as a multiplier, not dp. Catalog label now states multiplier with unchanged
  storage/divisor. Remaining: all_status_enable, fixed-width/container geometry,
  stock loopback subtraction versus aggregate sampling, mode-dependent font
  display units and native animations/lifecycle acceptance. Fifteen existing
  audit rows are now explicit source repair; two prior repaired rows become
  unbuilt because source changed. This is not full network-speed completion.
- Also changed `StatusBarFeatures.kt`, `NetworkSpeedPolicy.kt`, added
  `NetworkSpeedTargets.kt`, expanded `NetworkSpeedPolicyTest.kt`; inventory
  generator and outputs synchronized. No compiler/test/lint/device execution.

## Earlier recovery checkpoint (historical)

- `done=false`. Baseline: 337 records, 44 pending, 126 existing-partial audits,
  12 structural navigation records, 53 catalog features.
- All pre-existing Git changes are authorized and preserved. No build, compiler,
  tests, lint, R8, installation, restart, hot reload, device/settings writes,
  commit/push, background task or sub-agent is permitted in this phase.
- Historical 606 tests do not cover current sources. Unified build and complete
  post-build feature review have NOT started.
- Read continuation/handoff, inventory generator, inventory and MT probe helper.
  MT confirms SystemUI 202602260 and plugin 183022200 (workspace `gqbnd0ih`).

## In progress

1. Source implementations saved for card list, focus colors, weather/city,
   navigation geometry, three gesture-line sizes, media palettes/inversion/
   ambient effects, percentage labels/overlays and lockscreen shortcut material.
   Also saved classic/hardware-volume percentage, user-bound media app-icon
   fallback, owned Toast blur, and independent 5G long-press settings routing.
   All new work remains partial/unbuilt. Current catalog: 60.
   Current inventory: 17 pending, 126 existing-partial audits, 12 navigation,
   93 static, 19 repaired, 4 partial static, 60 source-only partial, 4 source-only
   repair, 2 other (337 total). No earlier completed status was promoted.
   Three existing media background records are now explicitly unbuilt because
   their implementations changed; historical static checks do not cover them.
2. Next substantive queue: remaining tiles and their teardown/device capability
   paths; notification management across SystemUI/Settings/system_server; force
   background blur, navigation hiding, Toast, QS-label disposition, gesture-log
   destination; Android replacement-volume percentage, media resize/AGSL parity,
   VoWiFi editor coverage; status bar/battery/island
   and all 126 existing-partial control-flow reviews. WMShell remains a separately
   evidenced artifact-access dependency, not an explanation for other gaps.

## Saved source and evidence

- `CardTileList*`: ten ordinary upstream specs, explicit none and bounded ordered
  parser; native lazy caches one immutable list before constructor exclusions.
  Detail scope rejects only a duplicate successful creation, restores nested
  scopes on exceptions; native VoWiFi lifecycle untouched. Three hooks and shared
  plugin discovery gated together. Disable/edit need controller recreation.
  27 full members in `docs/card-tile-list-evidence.json`; 6 unexecuted test
  definitions. Drag editor and arbitrary VoWiFi reorder/suppression remain open.
- `NotificationBlend*`: 27 exact effect/bridge scopes and seven dispatch callers.
  Native array is copied, only color slots replaced; outer glass fallback scene
  retained, full-AOD outer scopes excluded. Native blur capability, mode, clip,
  animation and clear unchanged. Keyguard opt-in defaults off. 45 full members in
  `docs/notification-blend-evidence.json`; 4 unexecuted tests. Actual glass tokens,
  full AOD and tiny-screen resources are not implemented by this adapter.
- `HeaderWeather*`: owned accessible non-clickable label beside untouched native
  date/land clock; geometry avoids other visible leaf views and handles RTL.
  Native WeatherFetcherController queries actual/hourly provider data, has a
  10-second timeout and closes cursors. Uses generation/weak callback checks,
  removes label/cancels on hide/disable/safety/detach/user switch. Read checks
  valid temperature/unit and <=6h age. Current-user UserTracker is weakly
  captured from NavigationBar; synchronized getUserId/createCurrentUserContext
  are checked before/after query setup, callback and pre-draw. No owner-user
  fallback, receiver or per-frame Binder call. 24 full members in
  `docs/header-weather-evidence.json`; 5 unexecuted tests. The 15-minute refresh
  applies within a visible cycle: hide/detach clears data and nextFetch, so
  reopening currently queries immediately. Provider permissions/data/layout
  still need runtime acceptance.
- `NavigationGeometry*`: four bar/frame height parameters; native Window/
  providedInsets/measurement/resource sampling scopes stay consistent without
  global resource changes. Default main-screen gesture mode only; 0 native,
  nonzero >=4dp, frame >= bar. 12 exact members, 4 unexecuted tests. No independent
  system_server policy change. Configuration/disable needs user restart.
- `NavigationHandle*`: the three upstream disabled preferences have genuine
  current-host implementations, not empty controls. XML "height" explicitly
  meant navigation_handle_bottom. Optional bottom (-1 native) and portrait/
  landscape widths (0 native) temporarily override mBottom/mHandleWidth along
  with the existing radius in all draw/export entries, then restore in finally.
  Native press/pulse factors bound the requested dimensions to actual view
  geometry. Touch/nav-frame/mode remain unchanged. Main display only.
  10 full-member extension evidence records; fixture expands to 32 signatures.
  Four added, unexecuted policy/settings tests; no upstream working UI claimed.
- `MediaPalette*`, `MediaArtwork*`, `MediaDarkHooks`: current Monet constructor
  `(WallpaperColors, boolean, int)` uses CONTENT switch 6, not the obsolete Style
  enum. Native 13-shade indices, linear reverse threshold 192; owned title,
  artist, times, actions, seamless and seekbar colors. Cover and foreground
  palette share the 333ms interrupted-fade timeline. Level0/1 primary setters
  remain native; level1/2 shader passes now receive a scoped MODULATE Paint
  filter, restoring by identity and retaining existing non-null filters.
  Original shaders/textures/alpha/geometry are not replaced; no new AGSL program
  is compiled by the adapter. All hooks share the final ready gate. Full shader
  source reads: continuous-music_player_tracker{,_middle}.txt/.json.
  Mode0 ambient owns a removable overlay on mediaBg, preserves native material,
  uses bounded dominant sampling or optional Monet, moves only while playing,
  visible and system animations enabled. Missing art now consumes the native
  bindMediaData appIconDrawable, which already loads for MediaData.userId. It
  validates userId/appUid/package identity, never uses stale artwork as an icon,
  and clears prior pixels/palette on package/user change rather than cross-fading.
  No additional package-manager or URI loading is introduced. Failed/unknown-user
  fallback restores native. Six full members in docs/media-fallback-evidence.json
  and two new unexecuted test definitions cover selection and signatures.
  Earlier palette evidence: 29 full members plus two shader files
  in `docs/media-palette-evidence.json`; 8 new policy/fixture tests and one
  catalog-options test, all unexecuted. Not upstream AGSL/resize/island parity.
- `NotificationBlendHooks`: fixes interaction with cloned always-dark Context.
  Bound actual view ancestry and same package/UID replace strict Context object
  identity; colors follow the actual nested Context while retaining the outer
  effect scene and full-AOD exclusion. No global resource mutation.
- `Percentage*`: default-off entry, separate brightness/volume, inline native
  top-label mode and real per-window drag overlay mode. Four exact listeners
  cover main brightness/volume, secondary brightness and actual brightness-window
  SeekBar; 22 dispatch callers deoptimized, no singleton window lookup. Native
  callbacks execute unchanged once. Weak identity bindings, stop/hide/detach/
  disable/safety/30s timeout cleanup, inset/rotation/font bounds. Slider min/max
  and native valueToVolume/max/muted are used; no volume/brightness writes.
  Advanced blur uses only the owned label and native capability gates, with a
  plain background fallback. Classic MiuiBrightnessSlider's actual listener now
  has three hooks and complete dispatch callers; native mirror stays unchanged.
  Hardware-key VolumeColumn uses its active stream and confirmed StreamState
  level when not tracking, native progressToLevel when dragging, and mShowing
  plus exact owner/column/bar identities. showH's weak deferred callback waits
  for actual attach and rechecks state; dismissH cancels pending/owned overlay.
  Reusing the same SeekBar for a different owner/column/stream now disposes the
  old value closure before binding the replacement, including same-frame reuse.
  No automatic brightness/volume writes. Extended super-volume range stays
  native. Android replacement volume dialog is still not included; inline
  mode intentionally only uses control-center labels. Two added unexecuted
  tests; 16 SystemUI + 31 plugin complete members (47 unique signatures) in
  docs/percentage-legacy-{systemui,plugin}-evidence.json.
  Earlier five unexecuted tests and 35 full plugin members remain in
  `docs/percentage-plugin-evidence.json`.
- `LockscreenMaterial*`, `OwnedBackdrop`: exact AOD22446301 shortcut layout chain,
  owned circle behind each existing image, no reparenting or native background/
  blend/outline changes. Standard BackgroundBlurDrawable and opt-in owned-view
  MiBlur retain native configuration/hardware/cross-window gates. Mirror actual
  icon size/alpha/scale; sufficiently opaque tint gets a reversible contrast
  color. Interactive/showing/not-occluded/not-going-away plus main display;
  hide/detach/release/disable/safety clear/remove. Release dispatch includes both
  inflateShortcutViews and onDestroy; rebind checks controller/parent identity.
  14 complete live plugin/SystemUI members and 14 cached framework signatures
  with complete instruction arrays. Framework cache is not newly captured
  current-device evidence. Five unexecuted policy/fixture tests.
- Existing flashlight bridge and shortcut installation no longer read MiuiStub
  or MiuiShortcutManager INSTANCE during installation. Field resolution stays
  inert; instance reads occur only at runtime controller/scene queries. No
  functionality/status promotion is inferred from this source-only repair.
- `ToastMaterial*`: new default-off SystemUI entry. Exact default text_toast /
  ToastLinearLayout constructor plus ToastFactory and all three factory dispatch
  callers. Adds an owned BackgroundBlurDrawable only on attached, ordinary
  unlocked main-display text Toasts with native capability/configuration/cross-
  window permission. Existing native blur and custom/plugin views stay native.
  Text, icon, padding, measurement, animation, presenter, accessibility, window
  and duration remain native; no repeated ToastStub initialization/listeners.
  Detach/hide/lock/screen-off/disable/safety/native takeover/30-second cleanup
  releases blur and restores only the owned background. Framework cache remains
  separately identified, not fresh device evidence. Seven complete current
  members plus complete layout/resource reads in docs/toast-material-evidence.json;
  four unexecuted tests. Not app-process Toast or upstream shadow pixel parity.
- Independent 5G tile now handles longClick in a separate non-network branch.
  The exact Phone 5G settings Activity must be system, enabled, exported and
  permission-accessible. Missing target or optional long-click reflection leaves
  only this action unavailable, not the 5G toggle. Same bounded/generation-pinned
  intent queue, native isFalseLongTap, final owner/user/slot/subscription/lock and
  capability rechecks; native ActivityStarter receives explicit owner UserHandle.
  No long-click network write or automatic setting change. Metrics spec now uses
  the spec rather than display label. Eight complete current-host members in
  docs/five-g-settings-evidence.json; two added unexecuted tests. Phone Activity
  presence is NOT captured/current-device acceptance. Native refactor implementation
  is an inert flag-warning route; legacy implementation retains the explicit user.
  Mode3 cellular detail and two upstream icon styles remain incomplete.
- TileCornerHooks and CardTileColorHooks now resolve singleton fields at install
  but read their values only inside runtime eligibility queries. This removes
  eager ThemeUtils/QSItemView companion initialization; no completion promotion.
- New read-only helper `tools/mt_continuous_read.py` records all pages. Intermediate
  `truncatedReason=window` is followed only with `hasMore` and no truncated line;
  character truncation remains an error. No build/test command is hidden in it.
- WMShell: read-only file listing of `/system_ext/framework`, `/system/framework`
  and `/product/framework` returns MCP `FILE_ACCESS_DENIED`; did not bypass the
  service policy. Allowed MT2/mcp listing still has the known resource
  `com.android.wm.shell_17.apk`, no demonstrated implementation JAR. Five active
  workspaces contain no WMShell library. Raw `continuous-library-list-{0..3}.json`
  records the exact limitation. This is unavailable evidence, NOT proof that the
  device has no implementation shared library. Four features remain pending.

## Remaining scope (not waived)

- The 17 current pending rows and 126 audit rows remain open; partial/unbuilt
  rows also retain their explicit remaining behavior. Indexing is not review.
- Partial migrations retain their named outstanding behavior: 5G detail/icon
  styles and real settings-page acceptance, volume hold/scale/buttons, carrier variants, media animation and
  palettes, lockscreen material and tile material.
- Three disabled gesture-line sizes are now adapted as described above. The
  hidden-parent QS label SwitchPreference still has no exact libhook consumer
  (fresh case-insensitive qs.?label search is empty). It remains pending rather
  than being deleted, classified as navigation, or equated with another label.
- WMShell implementation DEX has not yet been found. Read-only shared-library
  discovery is required; lack of ADB alone is not sufficient evidence.
- All 337 records require final coverage review after substantive work and
  deferred unified `:app:testDebugUnitTest :app:assembleRelease` and full lint.

## Exact next recovery tasks

1. Finish current-source ownership/control-flow review of the new adapters,
   especially scope interactions and failed reflection/restore paths. No build
   has been run to hide an unresolved source issue.
2. Follow the generated inventory's 17 pending keys (snapshot appended below).
   System-notification management is a cross-scope feature: upstream SystemUI
   imports the Settings AllowManageAllNotifications; Settings hooks setupBlock
   plus NotificationFilterHelper, framework hooks NotificationChannel
   isBlockable/setBlockable. Current SystemUI uses NotificationSettingsHelper
   isNonBlockable in its menu and NotificationEntry.mBlockable in adapters.
   Do not implement only a UI enable while system_server still rejects writes.
3. Force-background-blur is not header gradient: current supportsBlursOnWindows
   checks CROSS_WINDOW_BLUR_SUPPORTED, high-end gfx, persist.sysui.disableBlur
   and the existing CrossWindowBlurListeners enable state. Its full class is
   continuous-blur-utils.txt/.json. Retain real safety/capability controls.
4. Toast's new owned-drawable implementation is saved, not compiled/accepted.
   Do not regress it to forcing useBlurRes: that method only reads a tag.
   Cached ToastStubImpl has addBlur(View,WeakReference,boolean); it is not invoked.
5. Update generators, exact fixtures/evidence and this checkpoint as work lands.
   Then complete all partial scope gaps and 126 real control-flow audits before
   the single unified build/lint/test phase and full post-build review.

## Resumed segment modification list

- Core: NavigationHandleSettings.kt, MediaBackgroundSettings.kt,
  PercentageSettings.kt (new), LockscreenMaterialSettings.kt (new),
  SystemUiFeatures.kt, FeaturePanels.kt.
- SystemUI hooks: NavigationHandleHooks/Policy/Targets.kt;
  MediaPalettePolicy/Targets/Resolver/Binding.kt (new);
  MediaAmbientDrawable.kt (new); MediaArtworkJob/Drawable.kt, MediaDarkHooks.kt,
  MediaCardHooks.kt; PercentageHooks/Policy/Targets.kt (new);
  OwnedBackdrop.kt (new); LockscreenMaterialHooks/Policy/Targets.kt (new);
  SliderValueHooks.kt, SystemUiPluginHooks.kt, NotificationBlendHooks.kt,
  LockscreenFlashlightBridge.kt, LockscreenShortcutHooks.kt.
- Tests: NavigationHandleSettingsTest.kt, NavigationHandlePolicyTest.kt,
  NavigationHandleTargetsTest.kt, MediaBackgroundSettingsTest.kt,
  SystemUiFeaturesTest.kt; new MediaPaletteTest.kt, PercentageTest.kt,
  LockscreenMaterialTest.kt. New definitions only; none executed.
- Fixtures: expanded navigation-handle-host-members.txt; new
  media-palette-host-members.txt, percentage-host-members.txt,
  lockscreen-material-host-members.txt, owned-backdrop-framework-members.txt.
- Evidence: navigation-handle-dimensions-evidence.json, media-palette-evidence.json,
  percentage-plugin-evidence.json, lockscreen-material-evidence.json,
  owned-backdrop-framework-evidence.json. All source-only; MT reads are complete,
  cached framework provenance is explicitly separate.
- Metadata/docs: tools/systemui_inventory.py and both generated JSON inventories;
  this progress log and the three entry/handoff documents. All earlier work,
  including the previous segment's four feature families, remains preserved.
- Last lightweight check: git diff --check succeeds (existing CRLF notices).
  This is whitespace checking, NOT compilation, lint, tests or functional QA.

## Additional findings and next implementation locations

- Tile material: current QSCardViewHolder.updateBlendBlur(boolean) and
  QSItemViewHolder.updateBlendBlur(boolean) are complete no-op methods; compact
  holder only forwards to them. Upstream QsTileSuperBlur's entire hook body is
  commented out. Real current rendering is QSTileItemIconView
  applyTileBackgroundStyle(float,float) -> MiBackgroundStyle.setMiBackgroundStyle
  with ColorBlendToken/BionicsToken/LightParams; detail uses a different token
  overload. Do NOT hook the no-op old holder slots or force capability flags.
  Current resource search finds cc_tile_default_blend_colors and
  miui_seekbar_fg_blend_colors_collapsed; next inspect the actual token constructors,
  resolved dark/light variants, interpolation and native clear/recycle before
  implementing reversible material substitution. Complete new holder reads:
  continuation-tile-material-{card,item,compact}.txt/.json.
- Force blur: complete supports/apply xrefs are continuation-blur-{supports,apply}-
  xref.json (hasMore=false). supportsBlursOnWindows is already exactly hardware,
  high-end gfx, persist.sysui.disableBlur and cross-window checks. A switch that
  retains every gate unchanged would do nothing. Relaxing high-end classification
  alone also affects native applyBlur's zero-radius cleanup when cross-window
  blur turns off; solve that restoration path rather than bypassing real hardware
  or configuration controls. Scope includes shade, modal, dream/keyguard and
  fingerprint quick-open callers, not just header gradient.
- Gesture log: new complete dex-name search for GestureRecorder/mGestureRec/
  mLogfile is empty in main SystemUI; earlier statusbar_gestures search is empty.
  No empty switch added. The requested destination policy still needs an actual
  current writer; absence of the old names is not itself completion.
- Hide navigation bar: upstream SystemUIV imports the Home navigation hook; its
  methods all target com.miui.home.recents, not SystemUI. Need actual current
  launcher/window counterpart and restoration, not another handle-only alias.
  The allowed MT directory contains the current launcher APK; no new workspace
  has been opened for it in this segment.
- GMS/Taplus/Sunlight/flashlight/snow-leopard remain actual pending implementations.
  Taplus string search in SystemUI is empty; its consumer lives in another app.
  SnowLeopard upstream directly kills the audio service and chmods PCM nodes;
  neither command was executed or copied into an unreviewed lifecycle adapter.
- QS-label still belongs to a hidden category and has no libhook consumer.
  Broad current label search was paginated (hasMore=true), so it is NOT evidence
  of comprehensive current absence or equivalence to tile labels.

## Latest resumed modification list

- PercentageHooks/Policy.kt, new PercentageLegacyTargets.kt, ControlCenterHooks.kt,
  PercentageTest.kt; two legacy evidence manifests and one fixture.
- MediaDarkHooks.kt, MediaBackgroundPolicy.kt, MediaPaletteTargets.kt,
  MediaPaletteTest.kt; media-fallback evidence and fixture.
- New ToastMaterialSettings.kt and ToastMaterialHooks/Policy/Targets.kt,
  ToastMaterialTest.kt; OwnedBackdrop.kt, SystemUiCustomHooks.kt, catalog/panel
  wiring and catalog count test; toast evidence and fixture.
- FiveGTileHooks/Policy.kt, new FiveGSettingsTargets.kt, FiveGTilePolicyTest.kt;
  five-g-settings evidence and fixture; existing stock feature description.
- TileCornerHooks.kt and CardTileColorHooks.kt deferred singleton reads;
  SystemUiPluginHooks.kt accurate source-review wording.
- Inventory generator, both generated inventories and recovery/entry docs.
- Ten added test definitions this segment, NONE executed. No Gradle/compiler/
  unit tests/lint/R8/build/install/device write/restart/hot reload/commit/push.
- Metadata regenerated after the latest source edits. `git diff --check` is
  whitespace-only and passes; no inference of compilation or test success.

## Pending key snapshot

- `prefs_key_system_ui_control_center_snow_leopard_mode`
- `prefs_key_security_center_gms_open`
- `prefs_key_security_center_taplus`
- `prefs_key_system_control_center_sunshine_new_mode`
- `prefs_key_system_control_center_sunshine_new_mode_high`
- `prefs_key_system_control_center_sunshine_mode_brightness`
- `prefs_key_security_flash_light_switch`
- `prefs_key_system_ui_hide_navigation_bar`
- `prefs_key_system_framework_allow_manage_all_notifications`
- `prefs_key_system_ui_disable_miui_multi_win_switch`
- `prefs_key_system_ui_remove_miui_multi_win_switch`
- `prefs_key_system_ui_disable_infinitymode_gesture`
- `prefs_key_system_ui_disable_bottombar`
- `prefs_key_system_ui_move_log_to_miui`
- `prefs_key_system_ui_qs_label`

This list does not waive the 105 existing-partial audits or the named remaining
behavior on partial/unbuilt records. All 337 final coverage decisions remain open
until the required full review; structural rows remain navigation only.

## Unified build verification and install pass (2026-10-11, this segment)

Command that was actually run on the whole working tree, in the order the user
required (everything else already migrated/written first, then one build):

- `.\\gradlew.bat --console=plain :app:testDebugUnitTest :app:assembleRelease`
  -> BUILD SUCCESSFUL. 126 suites / 747 tests / 0 failures / 0 errors / 0 skipped.
- `.\\gradlew.bat --console=plain :app:lint :app:assembleRelease`
  -> BUILD SUCCESSFUL. lint 0 errors, 454 warnings, 2 hints (debug report).
  lintVitalRelease also passed inside the unified run.
- Artifact `app/build/outputs/apk/release/app-release.apk`, 2,862,666 bytes,
  sha256 d349d962751a9a5ff665179c22647d4f6ce12d394b87cb25cc47c4392db9fde1,
  versionCode 4 / versionName 0.1.3, debug signing config (debug cert sha256
  344981218ad8fc329b462725d64493ad01dabedaf84f2a4a75a37d814053fd94).
- `adb -s <serial> install -r <apk>` -> Success (device lastUpdateTime
  2026-10-11 16:26:54). No host process restarted: system_server/com.android.systemui/
  com.android.settings/com.miui.misound pids were 3641/3672/3950/14828 before and
  after. No new crash-buffer entry at 16:2x (the newest is the 00:05:24
  SubscriptionManagerEx Holder NPE from the earlier failed attempt).
- Evidence: `docs/unified-build-verify-2026-10-11.json`,
  `docs/unified-build-install-2026-10-11.json`, logs build/build-verify.log /
  build/test-verify.log / build/assemble-verify.log / build/lint-verify.log.

This is compile + package + install evidence only. It is NOT device acceptance:
no restart, no hot reload, no scope/enable toggle, no functional host check was
performed, so no migrated feature became "verified on device" because of it. The
historical 605-test / 411-warning run in docs/migration-validation-build-2026-10-11.json
does not describe this tree.

### Defects fixed to reach this state (not test relaxation)

1. `hook/feature/statusbar/StatusBarPolicies.kt` - `original.orEmpty() + "StatusBar"`
   is `Array<out String>.plus(element)`, which has no candidate. Now
   `(original ?: emptyArray()) + "StatusBar"`.
2. `hook/feature/systemui/TileMaterialHooks.kt` - a statement starting with
   `!field(...)` immediately after `owner.parent as? View` did not parse
   (unexpected token at the `!`). Split into named booleans
   (`plainItem && !field(...)`); semantics identical.
3. Four new test files (CardTileListTest, GmsTileMigrationTest,
   IslandBackgroundMigrationTest, NavigationGeometryTest) used `HyperFeature.panel`
   without importing the `core.panel` extension property.
4. `core/SystemUiFeatures.kt` - `control_center_gms_tile` was added without
   `group`, so the row dropped out of every systemui submenu and surfaced on the
   scope main page. Added `group = ScopeFeatureGroup.CONTROL_CENTER`. This was a
   real navigation defect caught by ScopeFeatureGroupTest and
   SystemUiFeaturesTest.newFeaturesAreOptInAndRemainInSystemUi.
5. `core/SystemUiFeatures.kt` - six new requirement texts said "重启宿主"/
   "重建生效" instead of the repository-mandated "重启系统界面" sentence.
   Reworded; the invariant (every systemui requirement states the restart) was kept.
6. `core/SystemUiFeatures.kt` - `systemui_toast_blur` summary was 30 chars, over
   the 28-char list budget; shortened.
7. Test expectation sync for features that are genuinely new in this migration
   (source behaviour untouched): FeaturePresentationTest now derives inline
   switches from `STATUS_BAR_FEATURES` as well and expects
   `status_bar_signal_order` inside the ICONS panel; SystemUiFeaturesTest expects
   `lockscreen_shortcut_blur` in LOCK_SCREEN.
8. `TileMaterialTest` asserted `t.methods` holds no `updateBlendBlur`, but
   `methods` deliberately includes the dispatch `callers` and
   `docs/tile-material-evidence.json` proves
   `BrightnessWindowSliderView.updateBlendBlur()V` is a real host call site. The
   assertion now checks `renders`/`writers`, so it still forbids treating that
   call site as a hook entry point.
9. Lint fatal/errors, all in files added by this migration, suppressed narrowly
   in the style already used in this repo (`ClipboardOverlayHooks`,
   `NotificationImportanceSettingsHooks`); no lint baseline was created:
   - `OwnedBackdrop.kt`: `@SuppressLint("BlockedPrivateApi")` for
     `CrossWindowBlurListeners.CROSS_WINDOW_BLUR_SUPPORTED`.
   - `@SuppressLint("ResourceType")`: `NavigationGeometryHooks` (host dimen
     0x10502a8 / 0x10502a4) and `WifiIndicatorHooks.install` (host ids
     0x7f0b0dac / 0x7f0b0dc2).
   - `@SuppressLint("NewApi")`: `FiveGTileHooks.openSettings`,
     `GmsTileHooks.supported`, `IslandBackgroundHooks`, `IslandMediaHooks.install`,
     `MediaPaletteResolver`, `StrongToastHooks.install`
     (`UserManager#isManagedProfile`, `SubscriptionManager#getSlotIndex`,
     `BlendModeColorFilter`/`BlendMode.MODULATE`, `Context#getDisplay`,
     `WallpaperColors`). These only go through the HyperOS 4 (API 36) host
     process; the module never runs them in its own minSdk 26 process.

### What is still NOT done (done = false)

- The 15 pending keys below are still pending. This pass did not implement any of
  them; it only made the existing tree compile, lint and install. WMShell still
  has no implementation dex (shared library `com.miui.wm.shell` is referenced by
  the SystemUI manifest; the system permission XMLs are refused by the MT file
  policy, so "not obtainable" is NOT proven).
- Hide-navigation-bar still needs the real Launcher counterpart (upstream Home
  variant targets com.miui.home.recents RecentsContainer/NavStubView, and the
  launcher APK here is hasCode=false). QS label still has no current consumer and
  its search was paginated, so absence is not proven. Move-log,
  allow-manage-all-notifications, flashlight and sunshine/snow-leopard/taplus
  remain actual pending implementations.
- The 12 structural/navigation-only rows stay navigation-only; they were not
  promoted by this pass. The needs-audit and partial rows keep their status.
- No feature was re-labelled as device-verified. Everything that was
  "static verified" before this pass is still only static verified; the new
  compile/install evidence covers the tree as a whole, not per-feature behaviour.

Next step for a continuing worker: resume the pending implementations in the
order already recorded above, keeping the same per-item requirements (directory +
config + install chain, lifecycle/close/restore, pure policy, full-signature
fixture tests, current host evidence), and only re-run Gradle/lint when a further
unified verification is wanted.
