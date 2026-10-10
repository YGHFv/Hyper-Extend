# SystemUI next migration and handoff audit - 2026-10-10

## Acceptance boundary

This records the uninstalled volume-animation/dual-row migration batch and the
follow-up source audit. It is not full upstream parity or device acceptance.
The earlier settings/UI cleanup installation is reported by the handoff; this
follow-up did not install an APK, restart/reload a host, change SIMs, or enable
features. Existing worktree changes remain in place; no commit or push was made.

Audited hosts: SystemUI `17.03.260226.r / 202602260` (MT `wot5ikcq`) and volume
plugin `183022200` (MT `gqbnd0ih`). Raw reads and verification outputs are in
`.workbuddy/tmp/mt-systemui/`. All MT calls were read-only; evidence reads checked
pagination and resource lookup completeness. The recorded hashes match the
refreshed text, not a running hook's behavior.

## Batch implementation carried forward

- Volume entry: all six positions synchronize with the native volume content's
  live transform. Subtract inherited parent transforms to avoid double motion.
  Footer additions expand the fixed frame; replacement positions do not. Restore
  owned changes when hidden, expanded, dismissed, or blocked by safe mode.
- Dual-row mobile signal: per-binding VM interface facade, never replace a
  shared VM's visibility globally or remove native views/IDs/constraints. Two
  known ordinary cellular signals only; default-data SIM anchors the upper row.
  Single-SIM, airplane, satellite, unknown, and unsupported icons fall back.
  Four upstream asset styles, signed offsets, and 70-140% scale are available.
  Non-default signal visibility and hidden-SIM configurations are incompatible.
- Notification count: suppress only the audited overflow-dismiss callback;
  retain coordinator attachment, manual/app removal, and server posting limits.
- Mobile-type text: replace non-empty native names at `getMobileTypeName(int)`;
  deoptimize the audited caller. Native visibility and measurement remain intact.
- Prior source reviews: safe-mode checks in seconds clock, signal relay,
  delayed notification operations and status-bar double tap; preserve pressed
  media progress height during layout updates. This is not an audit of every hook.

## Follow-up findings and repairs

1. **Dual-row padding could overwrite newer host configuration.** The old array
   snapshot restored every edge unconditionally. Each horizontal edge now tracks
   its own baseline and owned value; a host update becomes the new baseline.
   Top/bottom padding is never restored or overwritten by this feature. Repeated
   fallback no longer issues redundant padding writes. Five pure JVM tests cover
   accumulation, new baselines, fallback, density offsets, and reattachment.
2. **Vertical offset could retain old density.** Density is now part of the
   composite drawable cache key, so an unchanged signal/tint still rebuilds its
   dp-derived offset after density changes.
3. **Detached collectors could deliver stale callbacks.** Binding callbacks now
   carry an attachment generation and ignore updates after detach/rebind. Normal
   detach disposes all local collection handles and restores the native view;
   final detach stops subscription/broadcast listeners. This race hardening and
   actual callback ordering still require host runtime testing.
4. **Null accessibility descriptions could retain old text.** A binding without
   an owned description now samples native null as well as non-null updates.
5. **Footer measurement assumed every layout included the addition.** The
   measured increment is now recorded only when actual laid-out height matches
   our owned height. Native relayout/clamping no longer subtracts an old added
   row from available space. Two regression tests cover these cases.
6. **Single-line input validation had gaps.** Validation now occurs before trim,
   uses Unicode code points, and rejects boundary controls, Unicode line and
   paragraph separators, supplementary format controls, and unpaired surrogates.
   Ordinary surrounding spaces are still trimmed, with an eight-code-point limit.
   Three new tests cover these cases and supplementary character length.

The inherited facade identity `equals/hashCode` and status-bar gesture safe-mode
checks were included in the first follow-up build, before these additional edits.
The signal resource pattern is now cached rather than compiled on every emission.

## Lifecycle evidence and remaining risk

- `JavaAdapterKt.collectFlow(View, Flow, Consumer)` uses `repeatWhenAttached`.
  Its callback collects at `Lifecycle.State.CREATED`, not STARTED/RESUMED.
- `ViewLifecycleOwner` remains at least CREATED while attached, including when
  its window is not visible. A hidden second icon therefore does not, by itself,
  cancel this local subscription. The native visibility collector changes
  visibility/removal animation state, not the local subscription lifecycle.
- Each parent container is paired independently; subscription IDs must match
  exactly, so a new SIM in the same slot cannot reuse an old subscription's level.
- Detach clears signal/satellite/provider samples; reattach must collect them
  again before a valid pair is eligible. Rebinding disposes the previous local
  binding before creating another facade.
- Drawing still cooperates with native binder writes through pre-draw sampling.
  Real ordering, removal animations, theme/tint updates, density/RTL combinations,
  TalkBack descriptions and repeated host rebinds have not been accepted on-device.
- Hot reload across module generations and comprehensive failure-injection cleanup
  are not covered by this source review. Do not treat the generation guard as a
  complete hot-reload lifecycle solution.

## Evidence and inventory

- `docs/mobile-type-text-systemui-evidence.json`: 6 code targets for name lookup,
  caller, VM getter, collector, renderer and native measurement.
- `docs/dual-row-systemui-evidence.json`: 14 code/layout targets (including the
  count-limit chain and four new lifecycle targets), plus 5 signal resource IDs.
- `docs/app-volume-position-evidence.json`: 6 code/layout targets, 3 IDs and the
  footer layout file mapping.
- Refreshed outputs: `mobile-type-text-verified.json`, `dual-handoff-verified.json`,
  and `volume-handoff-verified.json` in the raw evidence directory.
- `tools/systemui_inventory.py` now maps custom text to its exact evidence and
  boundary. Only that row changed from unaudited to static implementation; the
  whole mobile-type page is not marked migrated.

Inventory after regeneration: **337 configuration rows**, including navigation
and dependency rows, not 337 independent features:

| Status | Rows |
| --- | ---: |
| needs_audit_existing_partial | 164 |
| pending | 98 |
| implemented_static_verified | 50 |
| repaired_static_pending_device | 18 |
| partially_implemented_static_verified | 4 |
| covered_by_existing_feature_per_user | 1 |
| already_supported_by_current_host | 1 |
| partially_implemented_pending_settings_host | 1 |

Upstream cross-check: `MobileNetworkTypeSettings` and `SystemUIB/SystemUIV`
actually wire independent type display/display logic through `MobileTypeSingle2Hook`.
These remain pending locally. The separate big-network-type preference is hidden
in upstream XML, with no matching current libhook consumer found; no empty local
switch was added. Clock forms, media background/full progress styles, control
center tiles/layout, charging and lockscreen entry details, island, AOD and WMShell
work also remain incomplete.

## Build verification

- First follow-up: 273 tests, zero failures/errors/skips; Release success in
  `build/systemui-handoff-build.log`. Covers the two previously unbuilt changes.
- Final follow-up: **283 tests**, zero failures/errors/skips; R8 Release success
  in 2m 1s. Result: `docs/systemui-next-audit-build.json`;
  log: `build/systemui-next-audit-build.log`. `git diff --check` passed and a second
  inventory generation reproduced the tracked snapshot byte for byte.
- Release path: `app/build/outputs/apk/release/app-release.apk`; R8 remains enabled.
- Full lint is not claimed clean: the previous full run had 22 known errors.
  Release vital lint is not a substitute for that full run.

## Device acceptance still required

Use a tested R8 Release only. Installation/restart/feature changes require the
user's deployment direction; do not restart SystemUI automatically.

1. All six volume positions: show/dismiss/expand, animation alignment, complete
   clickable capsule, continuous playback starts/stops, two playing apps,
   portrait/landscape, insufficient space, lock/unlock and native button restore.
   Test footer additions separately from both replacement modes.
2. Dual-row: both default-data choices, independent reception changes (especially
   the hidden lower SIM), data handover, one/two SIMs, removal/reinsertion, airplane,
   unsupported/satellite/no-service states, repeated detach/rebind and restoration.
3. Dual-row appearance: all four styles, both tint modes, density/font changes,
   light/dark backgrounds, offset/scale extremes and TalkBack. Check each status
   bar/control-center/display container rather than assuming a shared VM is safe.
4. Composition: custom type text plus dual-row, roaming/activity marker options,
   and incompatible visibility settings. Confirm no fake labels when disconnected,
   and native measurement of the maximum accepted text.
5. Count-limit: controlled notifications beyond the host threshold, then manual
   clear/app withdrawal/group update; monitor memory/drawing and restore the limit.
6. Safe mode: after user-directed activation, check clock scheduling, relay
   restoration, delayed notification work and double-tap behavior. Stored feature
   preferences must remain unchanged.
