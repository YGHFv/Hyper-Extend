# Media artwork background: partial migration - 2026-10-10

Later source continuation: `media-continuation-2026-10-10.md` adds an opt-in
cover-only fade and lifecycle repairs, with 34 refreshed targets. That continuation
is not installed. This document's original 33-target/350-test scope is historical;
the 15:43 deployment record covers this original batch, not the later changes.

## Scope

New opt-in entry: SystemUI / control center / media card custom background
(`media_card_background`). Mode 0 retains native rendering. Modes 1-4 provide
deterministic mirrored cover mosaic, blurred cover, radial shade and linear shade.
Blur strength is 0-20%; zero really disables blur for the blurred-cover mode.

This is explicitly a **partial** migration of upstream `CustomBackground`, not
full visual parity. Both mapped upstream preference rows remain
`partially_implemented_static_verified`. Keyguard, AOD, flip tiny screen and
dynamic island do not receive custom artwork. Original Monet palettes, random
mosaic arrangement, color transitions, inverse colors and ambient light remain
pending. The ordinary unlocked notification-center card is the implemented scope.

No installation, restart/reload, feature toggle or network/SIM change occurred.
Device acceptance remains one later combined phase, as requested.

## Host evidence

SystemUI 17.03.260226.r / 202602260, MT workspace `wot5ikcq`.
Manifest: `docs/media-background-systemui-evidence.json`; refresh output:
`.workbuddy/tmp/mt-systemui/media-background-verified.json`.

33 code/layout/field targets, seven resource names, one resource-file mapping and
eight concrete day/night values were reread with pagination/truncation checks.
The final extra `clear` signature assertions were checked against exact-hash
cached text from these reads. A later repeat refresh was refused by the MT server
(WinError 10061); it did not invalidate or replace the successful earlier refresh.
This extends the native dark-mode evidence with:

- `bindMediaData` decodes the artwork before publishing `artWorkDrawable`.
  No module-side URI/package lookup or second `Icon.loadDrawable` is needed.
- `MediaData.artwork` distinguishes real cover art from the native app-icon
  fallback. Null artwork restores the native card instead of retaining the song.
- `detach`, `access$setTopMediaData` and `NotificationSectionsManager.reinflateViews`
  establish rebind/removal paths. Both actual callers are deoptimized.
- `isOnKeyguard`, `isShowingKeyguardNotification`, `enterFullAod` and pending AOD
  state gate custom backgrounds conservatively; unknown samples remain native.
- The seven native effect clear methods and `setMiViewMaterialTypeCompat(0, view)`
  remove blend/glass state before displaying custom artwork. Last native effect
  and context are retained for reapplication. Native outline radius and clipping
  remain, with matching clipping inside the custom drawable.

## Rendering and lifecycle

`MediaDarkHooks` is the sole appearance-hook owner. Both settings feed it, so
background and always-dark do not register competing replacements for the same
native methods. Always-dark alone retains its previous scoped resource behavior.

The module snapshots an already-loaded drawable on the main thread at 192x192.
It neither modifies host drawable bounds nor recycles host bitmaps. Software
bitmaps draw directly into the bounded target. Hardware bitmap copies are limited
to four megapixels; larger hardware sources or uncopyable drawables fall back to
native rendering. This bounded main-thread copy still needs device profiling.

Pixel processing runs on one worker, with at most four queued thumbnails. Cancel
removes queued jobs; each publish carries a generation checked on the main thread.
Rebind, detach and holder replacement invalidate previous generations. Publication
also checks attachment, safe mode and holder identity. Background bitmap references
are released normally rather than recycled while a RenderThread may still use them.

The blur is a deterministic, bounded separable box filter, not upstream hardware
blur. The mosaic uses mirrored tiles rather than upstream random rotations. Radial
and linear modes shade the cover without introducing an image-loading dependency.
Output channels are capped at 46/255. With the audited 50%-white secondary text,
the worst-case local contrast calculation exceeds 4.5:1. The deliberately dark
result differs from upstream palette extraction. This calculation is not a device
legibility/visual acceptance result.

Only a successfully installed custom background forces native night foreground
colors. Removing it reruns the native color writer. Native controls, action
drawables, seekbar behavior, layout, touch bounds and content descriptions are not
replaced. Size changes use drawable bounds; configuration changes rebuild the
thumbnail/radius. An unrecognized background writer wins until the next audited
bind/effect instead of being overwritten every frame.

Before each native material update the module relinquishes its background, lets
the host update, then reapplies only when eligible. Failure while clearing/installing
attempts native rollback. Pending AOD animations defer material reapplication;
the custom image itself is removed when entering the excluded state. Safe-mode
restoration retains newer host writes. Replaced holders cancel work and cannot
receive a previous holder's artwork.

## Verification

Final tests, artifact hash and build status are in `docs/media-background-build.json`;
Gradle log: `build/media-background-build.log`. New coverage checks entry/default
and persistence, excluded/unknown states, stale generations, blur invariants and
zero strength, contrast bounds, and exact lifecycle/clear method signatures.
Fifteen new JVM tests were added relative to the preceding 335-test batch.
The first compile exposed a nullable View access, and a later compile exposed
generic weak-reference inference; both were corrected, not bypassed.

The inventory contains 337 preference/navigation/dependency rows: 95 pending,
132 existing-partial requiring audit, 83 bounded static implementations, 18 repairs
awaiting device tests, six partial static rows, and three other boundary-specific
statuses. Only background mode and blur strength moved from pending to partial.
The prior 22 full-lint errors are not claimed fixed; full lint was not rerun.

## Remaining acceptance and implementation work

- Four modes and blur endpoints on actual covers, including transparent, hardware,
  missing, oversized and malformed artwork; rapid song/app changes and null data.
- Material/theme changes, notification shade reopen, reinflation, holder reuse,
  rotation/density/font scale and other background writers.
- Keyguard/AOD transitions before, during and after a processing result; safe mode
  during material changes; real tiny-screen transitions and restoration.
- Combination with always-dark, button/text/layout/progress customizations; test
  foreground contrast, clipping, control touch regions and RenderThread behavior.
- Profile thumbnail copy and processing latency/memory on device. JVM policies do
  not test Android reflection, native renderer effects or host animation ownership.
- Continue palette fidelity, transitions, inverse/ambient options and additional
  surfaces separately; do not mark the media page complete because modes exist.
