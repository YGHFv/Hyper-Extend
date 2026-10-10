# Media continuation after the 15:43 installation - 2026-10-10

## Scope and deployment

This continuation adds an opt-in **cover-only** transition and audits the shared
`MediaDarkHooks` lifecycle. It does not finish media migration, or the SystemUI
inventory. No installation, host restart/reload, feature/scope change, SIM change
or runtime acceptance is performed. The device remains on the 15:43 installation
recorded in `migration-install-2026-10-10.json`; the newly built local Release APK
contains additional source changes and is not that installed artifact.

## New partial migration

- `media_card_background.transition`, a Boolean option under the existing parent,
  defaults off and requires the parent feature. Existing keys/defaults remain.
- Maps only the cover-crossfade part of upstream
  `prefs_key_system_ui_control_center_media_control_control_color_anim`.
  `MediaControlBgDrawable` / `TransitionDrawable` are the upstream references for
  the 333ms duration and hidden/detached snap behavior.
- Backgrounds keep the previously owned frame while processing a new valid cover.
  Publication crossfades only an owned, attached, shown ordinary card. Initial
  artwork, excluded surfaces, hidden/detached views and disabled system animations
  snap. No continuous ValueAnimator or detached-view frame callback is installed.
- Rapid changes flatten the current two-frame interpolation at 192x192 rather
  than nesting drawables or jumping to the previous target. Retained transition
  state has at most two pixel arrays and two bitmaps; transient flatten/allocation
  costs and Android renderer retention still require profiling.
- Host alpha is applied once to the composed background; clipping/gradient state
  remains native-sized. Module bitmaps are not recycled while RenderThread may
  still reference them. Both frames retain the existing 46/255 channel cap.

This is **not** Monet palette extraction, foreground color interpolation, inverse
colors, ambient light, resize animation, upstream random mosaic composition or
full gradient parity. All three background-related inventory rows stay partial.
Keyguard, AOD, flip tiny screen and dynamic island remain excluded from custom art.

## Audit fixes

1. The old implementation restored native material immediately on every cover
   request, producing a possible native-background flash before the worker result.
   Valid replacements now retain the owned frame; null/unusable/failed artwork
   still restores native instead of leaving the previous song indefinitely.
2. Previously even excluded-state binds copied thumbnails and queued processing.
   The request state now defers before snapshotting, cancels on exclusion and
   resumes on the next eligible pre-draw without requiring a new bind. The same
   failed source is not retried on every frame. Tiny-screen bind/detach bookkeeping
   still runs without enabling tiny-screen rendering.
3. Host `bindMediaData(null)` returns without clearing its previous mediaData.
   An explicit binding-validity flag prevents subsequent pre-draw/reattach from
   resurrecting that old cover. Holder ownership now requires exact controller,
   header and captured-holder identity, not merely the player's parent.
4. Holder replacement cancels work and restores the old holder before the setter
   overwrites its identity when this hook is active. Existing reinflate normally
   calls detach first; the setter hook closes the remaining ownership window.
   Unexpected/unhooked replacements retain only conservative background cleanup;
   safe-mode/tiny-screen/native transitions still need runtime acceptance.
5. Deferred AOD material restoration now records the exact background identity.
   A newer native writer invalidates that restoration instead of being clobbered.
   Successful reapplication refreshes native-background and always-dark ownership
   because the native effect may allocate a different Drawable.
6. An actual four-slot queue replaces the prior unbounded queue plus size check.
   Worker-side volatile generation checks skip already-invalidated work before
   processing and before posting results; main-thread generation checks remain.

No new global bitmap cache, URI/package read, shared Drawable mutation, AOD-listener
removal, permanent controller Context replacement or playback/seekbar replacement
is introduced. The existing main-thread hardware copy limit remains 4MP; this is
bounded allocation, not proof of acceptable frame latency.

## Evidence and verification

MT recovered. `media-background-systemui-evidence.json` now has **34** live-verified
code/layout/field targets, seven resource-name mappings, one file mapping and eight
day/night values. The former 33 targets' recorded hashes were unchanged. The added
`MiuiMediaHeaderView.setMediaViewHolder(MiuiMediaViewHolder)` signature is also in
the JVM fixture. A full-DEX smali call-site search returned only
`NotificationSectionsManager.reinflateViews`, with no more pages/skipped scopes;
that caller is already deoptimized. Reinflate assertions include the setter call.

Raw evidence: `.workbuddy/tmp/mt-systemui/media-continuation-*`.
Refresh log: `build/media-continuation-evidence.log`.
Build/test record: `docs/media-continuation-build.json`.
Build log: `build/media-continuation-build.log`.

Regression coverage includes request deferral/resume, null-bind invalidation,
identity rather than equality, forced refresh, no per-frame failed retries,
transition endpoints/interruptions/contrast/reference release, deferred material
ownership, reallocated native backgrounds, exact setter signature and option
parent/default gating. These JVM tests do not execute Android Canvas, reflection
hooks, the thread executor/Handler, device animations or RenderThread.

The inventory still has 337 configuration/navigation/dependency rows, not features:
94 pending, 132 existing-partial requiring audit, 83 bounded static implementations,
18 repairs awaiting device acceptance, seven partial-static rows and three other
boundary-specific rows. Only one pending preference moved to **partial**, not done.
Full lint is not rerun; the prior 22 known findings are not claimed fixed.

## Remaining combined acceptance

After migration, include all previous volume/signal/clock/media cases plus rapid
cover changes during fades, system animation scale zero, visibility changes,
null/failed/hardware/oversized art, shade reopen, density/theme reinflation, holder
replacement, lockscreen/AOD transitions while results are pending, newer native
background writers, safe mode and always-dark combinations. Profile the main
thread snapshot and transition flatten/allocation paths. No per-batch device test
is requested here.
