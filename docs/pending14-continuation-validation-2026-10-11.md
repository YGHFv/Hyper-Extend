# Remaining-14 continuation and unified verification

This continues `pending14-migration-2026-10-11.md`, not a declaration of full
upstream parity. Eight of the original fourteen rows now have bounded source
implementations; six still have no implementation. The earlier 27
`needs_audit_existing_partial` rows remain separate unfinished work.
`allMigrationComplete` remains false.

## New implementation boundaries

### Notification management

- Default-off `notification_manage_system`, with Settings and system-server
  adapters restricted to the captured SDK37 fingerprint and owner user.
- Settings modifies the notification backend's UI row, not package identity:
  `systemApp=false`, `lockedChannelId=null`. Native notification permission,
  enterprise restrictions and enhanced confirmation remain in the original UI.
- Only the native Settings package toggle scopes the forced-notification query.
  Both `enableNotifications` overloads and exact callers are deoptimized. The
  native helper's XSpace-linked behavior is retained, not claimed to be isolated.
- System server scopes `NotificationChannel.isBlockable` only to the immediate
  `PreferencesHelper.updateNotificationChannel` caller, matching channel ID,
  `fromUser`, calling UID1000, system/SystemUI caller and owner target UID.
  Nested calls, exceptions and other threads cannot inherit an unrelated scope.
  No `setBlockable`, package/channel serialization or process-wide flag override.
- User changes still use native notification APIs and persistence. Disabling the
  module does not undo saved choices. Existing UI rows need recreation; full
  enable/disable recovery needs the documented host/device restart. Turning off
  important system notifications can hide important alerts.
- `notification-management-evidence.json`: 16 complete current method/field
  captures, including the native server call flags. Raw supporting page/listener
  reads are retained under `.workbuddy/tmp/mt-systemui/remaining9-*`.

### Native sunlight tile

- Default-off `control_center_sunlight_tile`, independent `hyperextend_sunlight`
  editor candidate. Does not change the saved tile list; user adds/clicks it.
- Uses current `Settings.System.sunlight_mode`, confirmed in Settings and the
  MIUI display controller. SDK/fingerprint, owner user, native FeatureParser
  capability, `canWrite` and brightness restriction gates are required.
- Displayed state is the saved preference, not current brightness. Does not
  change automatic brightness, force sensors, force high brightness, write sysfs
  or bypass native power/thermal policy. No unverified long-press destination.
- High/custom brightness modes remain separate pending rows. This is a bounded
  normal-mode replacement, not the complete upstream sunlight implementation.
- `sunlight-tile-evidence.json`: eight complete current members.

### Flashlight turn-on ceiling

- Default-off `flashlight_strength_limit`, configurable 1-100% ceiling, default
  100% (retain native value). Only the actual classId0 torch-on runnable with
  native maximum100 and owner user is eligible. Native force-off, low-battery,
  availability, camera lifecycle and errors remain untouched.
- After native `doSuperFlashlightStrategy`, cap only the exact subsequent
  `Settings.Global.getFloat` read in that runnable's thread/resolver scope.
  Native superflash explicitly forces 50%; a configured 100% does not raise it.
  Unexpected values retain native handling. Scope always unwinds on failure.
- No module settings write, sysfs write, camera invocation or automatic turn-on.
  Does not take over the screen-brightness slider or adjust an already-lit torch.
  Changes/disable apply on the next native enable. Live slider parity remains
  unfinished even though the inventory row now has a partial implementation.
- `flashlight-strength-evidence.json`: 13 complete current members/fields.

## Shared lifecycle and regression fixes

Taplus and sunlight now use `NativeToggleTileHooks`, `NativeToggleTileEditorHooks`
and the shared pure transaction policy, with separate feature/spec/readiness and
backend contracts. Taplus keeps its provider signature/identity checks and native
ActivityStarter route. Sunlight has no long-press route. Native interfaces and
editor contracts reuse the existing GMS/5G evidence; they are not counted as new
captures. No substitute native tile is constructed.

The shared lifecycle preserves falsing, unlock, owner-user, permission,
listening/destroy, generation and 1500ms click-age gates. A bounded worker does a
fresh comparison before one native write and a confirmation read afterward.
There is no atomic CAS guarantee, retry, or rollback over external edits. An
in-flight native call cannot be cancelled. Disabling a tile feature does not
undo deliberate saved native preferences. Refresh stops when not listening or
destroyed; stale generations cannot publish.

Unified compilation caught and fixed the wrong HookSettings numeric accessor
and missing `hasDetailPage` extension imports in four new/previously-uncompiled
test files. Catalog tests now cover 71 SystemUI entries and the notification
adapter's additional system-server scope.

## Still pending

The same six rows were re-audited against the current carriers and the device
read-only state; details and exact evidence are in
`pending14-remaining6-carrier-audit-2026-10-11.json`.

1. Snow leopard: `/dev/snd/pcmC0D*` exist as mode 0660 `system:audio`
   `u:object_r:audio_device:s0`, and upstream restores a hard-coded 0660 after
   `chmod 000` plus killing the audio HAL. A process-local boolean cannot survive
   restart while the nodes stay 000. Needs a durable per-node owner/mode/snapshot
   transaction with fail-closed restore. No chmod, kill or service action ran.
2. Sunlight high mode: both backlight node families exist but are
   permission-denied; upstream chmods 777 and raw-writes `Integer.MAX_VALUE` with
   no persisted last brightness or accepted-range validation. Needs a confirmed
   node contract. The native preference tile is not a substitute for this row.
3. Sunlight custom brightness: same node contract as the high mode, plus a real
   `max_brightness` clamp instead of a UI-only maximum. No placeholder seek bar.
4. Hide navigation bar: Home.apk DEX has zero `NavStubView`, `RecentsContainer`,
   `isMistakeTouch` or `showLandscapeOverviewGestureView` hits; launcher gesture
   code is Rust `libapp.so` with no traced bridge. Current SystemUI instead has a
   native `hide_gesture_line` global preference read by `SettingsManager` into
   `NavigationModeControllerInjector.mHideGestureLine`, which must be confirmed as
   a distinct control from the already-shipped `gesture_line` feature before use.
5. Move gesture log: current `CentralSurfacesImpl` has no `mGestureRec`; complete
   searches found no `GestureRecorder`, `GestureRecorderImpl` or
   `statusbar_gestures` string. No guessed file move and no inert toggle.
6. QS label: upstream has only a hidden preference and translations, with no hook
   consumer; current dex has no `qs_label`/`quick_settings_label` string. A
   user-facing behavior must be defined before any hook is written.

## Verification records

`pending14-evidence-recheck-2026-10-11.json` records fresh read-only rechecks of all
64 WMShell/Taplus/notification/sunlight/flashlight member captures; all match their
recorded text hashes. This verifies host-code evidence, not runtime behavior.
`remaining9-root-artifacts.json` records unchanged-DEX wrappers for the newly
root-extracted MIUI framework/services. Source/copy hashes matched. All wrappers
are analysis-only and must never be installed.

`pending14-unified-validation-2026-10-11.json` is the machine-readable result of
local build, unit tests and lint, including input hashes and output APK identity.
The inventory generator only promotes these eight bounded mappings while that
record's complete source-input hashes and success flags match. It does not
promote the six pending rows or the earlier unreviewed partials.

No APK installation, scope toggle, host restart, hot reload, setting change,
notification action, torch operation or feature-level device acceptance was
performed in this continuation. Historical installation records do not cover
the new artifact. No claim of full fourteen-row completion is made.
