# Migrated-feature review and GMS continuation - 2026-10-11

## Scope and order

This is a bounded source/control-flow review, not a claim that all 337 migration
rows are correct. Existing changes are preserved. No device package writes,
Activity launches, installation, restart, hot reload, settings/scope/network
changes, commit or push were performed.

1. Recheck the preceding network-speed/weather repair inputs and current
   GMS/card-list adapters against cached host contracts and policy tests.
2. Reproduce and repair GMS transaction completion defects; run the selected
   regression suites before adding a new migration path.
3. Implement the still-missing GMS long-click settings route with capability
   guards; run its tests and then the full local unit suite and lint.

## Findings and disposition

### Fixed: transaction success did not establish the final family state

`GmsPackageTransaction.apply` checked each package immediately after its write
but never rechecked the earlier packages or packages already at the desired
override. An external change during a later write could therefore produce
`complete=true` while the family no longer matched the requested state.

The per-write check also ignored effective enabled state. Matching UID/version
and raw override alone was not sufficient to claim the operation succeeded.

Three added tests fail on the old implementation, without touching a device:

- `finalFamilyReadDetectsTakeoverAfterAnEarlierWriteSucceeded`
- `matchingRawOverrideDoesNotProveTheEffectiveStateWasApplied`
- `finalReadAlsoChecksPackagesAlreadyAtTheRequestedOverride`

The repair compares complete expected name/UID/version/raw/effective-state
records after each write, rechecks the whole original snapshot before success,
and checks authorization again. Compensation requires the full still-owned
expected value; it never overwrites the external takeover used by the tests.
Two further tests cover a manifest-disabled default restored after failure and
authorization revocation during the last write. The test backend now models
raw default state through the application's actual manifest-enabled value.

This does not make a multi-package operation atomic. Concurrent changes after
the last read, or lost permission/lifecycle/time budget, remain explicit limits.
`restored` concerns owned writes, not reversal of another actor's changes.

### Reviewed without an additional defect found in the named source scope

| Adapter | Checked | Residual boundary |
| --- | --- | --- |
| Network speed | Previous repaired runtime/policy/fixture hashes; directional loopback, invalid sample and fallback tests | UI sampling is not the exact native background snapshot; geometry/animations remain unaccepted |
| Header weather | Previous repaired runtime/policy/fixture hashes; per-binding cooldown, invalidation and stale callback tests | Recreated headers have independent caches; provider permissions and real lifecycle require device acceptance |
| Card tile list | Install/discovery wiring, lazy immutable spec list before constructor exclusions, complete native detail creation body, nested/thread-local deduplication | Native null branches skip map insertion, so duplicate suppression does not overwrite a prior tile; arbitrary VoWiFi editing and drag UI remain open |

The 5G native-launch evidence was also revisited as the shared launch contract:
its recorded refactor implementation is an inert warning path. This is an
existing boundary, not something a class-name match or compile result removes.
The new GMS route explicitly rejects that backend. This review does not claim
a full fresh 5G telephony/control-flow audit.

## Continued migration: GMS long-click settings

- Add independent `TOGGLE` and `SETTINGS` actions to the bounded pending-intent
  path. A production policy gate prevents SETTINGS from invoking the package
  transaction. Native `isFalseLongTap` is distinct from ordinary tap filtering.
- Preserve the upstream explicit component:
  `com.miui.securitycenter/com.miui.googlebase.ui.GmsCoreSettings`.
  Require the exact enabled/exported system application and Activity plus any
  component permission, checked for the current main-user context.
- Only expose long-click when optional reflection succeeds and the native
  selector returns the recorded `LegacyActivityStarterInternalImpl`. Reject
  the known inert refactor and unknown implementations without a launch fallback.
- Resolve lazy starter services only at runtime on the main thread. Recheck
  current user, lock/restriction/permission, listening, feature/safety,
  generation, 1500ms intent age and target capability before launch. Native
  ActivityStarter owns dismissal and receives an explicit owner UserHandle.
- Missing target or optional route does not disable ordinary package toggling.
  No package state is written by a long-click, and no Activity was opened here.

Evidence: `docs/gms-settings-evidence.json` reuses eight complete cached native
members. The upstream route is pinned by a source hash. No current SecurityCenter
manifest/activity export is available: the configured MT endpoint refused a TCP
connection, and the cached export listing contains no SecurityCenter APK.
This is not evidence that the target is absent. Runtime guards are mandatory,
and actual target presence/launch acceptance remains unverified.

## Validation and continuation

The pre-fix run reproduced three failures in 12 tests. After repair, 52 selected
review tests passed; after continuation, 35 selected tests passed. Final local
validation: **128 suites / 767 tests / zero failures, errors or skips**;
lint **0 errors, 454 warnings, 2 hints**. Nine new tests ran in this batch.

Exact commands, test counts, reproduction names, source hashes and remaining
boundaries are in `docs/migrated-feature-review-validation-2026-10-11.json`.
No inventory row is promoted. 337 rows still include 14 pending and 27
existing-partial audits; SYSTEM_UI_FEATURES63 / STATUS_BAR_FEATURES17 are unchanged.

Continue bounded reviews of native-volume/percentage adapters and card/VoWiFi
lifecycle before tackling remaining implementations. WMShell/Launcher artifact
access, current SecurityCenter target evidence, all other partial residuals and
the complete 337-row functional review remain open.
