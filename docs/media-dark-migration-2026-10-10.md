# Native media dark mode migration - 2026-10-10

## Scope and acceptance

New opt-in inline switch: SystemUI / control center / media card always dark
(`media_card_always_dark`). It maps only upstream
`prefs_key_system_ui_control_center_media_control_always_dark`, not the entire
media-background settings group. Existing settings and features remain intact.

No installation, restart/reload, feature toggle or SIM/network change occurred.
Device acceptance is deferred until migration completes, as requested. Exact
host contracts, unit tests and R8 success do not establish device behavior.

## Current host and reference

Reference: HyperCeiler `controlcenter/media3/AlwaysDark.kt`, `CustomBackground.kt`
and `system_ui_control_center_media_cards.xml`. Host: SystemUI
17.03.260226.r / 202602260, MT workspace `wot5ikcq`.

Upstream permanently replaces the media controller context and removes the
FullAOD listener. This implementation does neither. OS4 background updates use
`MediaViewBinder` and `NotificationViewEffectHelper.mediaViewEffectsMap`; the old
`updateMediaBackground` method is absent in the audited mediacontrol package.

Formal manifest: `docs/media-dark-systemui-evidence.json`. Read-only refresh:
`.workbuddy/tmp/mt-systemui/media-dark-verified.json`. It covers 21 code/layout/
field targets, six resource name mappings, one file mapping and eight concrete
day/night color values. Pagination/truncation was checked. All recorded text
hashes must match the refresh before this batch is recorded successful.

Native effects checked: normal, blur, glass, blur on keyguard, glass on keyguard,
glass on keyguard with light wallpaper, and full-AOD glass. Normal media shape
uses `media_notification_bg_color`: day `#ffffff`, night `#1a1a1a`.
Primary/secondary text and action-button resources also have distinct night values.
The read-only probe now supports explicit variant/value checks, rather than
treating a resource name match as proof that its day/night value differs.

## Implementation and safety boundaries

- The native effect receives a derived night Context only for its synchronous
  `apply` call. Original material selection, clear/apply transitions, glass
  parameters, rounding and native AOD-animation guard are not replaced.
- `updateForegroundColors` runs with a temporary derived context and restores
  the controller's original context in `finally`. A newer context published by
  another participant is not overwritten. Configuration cloning changes only
  night bits, preserving locale, density, font scale and other uiMode bits.
- Native text, action tints, seamless icon, enabled/disabled progress colors and
  progress visibility remain under the existing native methods. No global
  resource, configuration, effect map or seekbar paint is modified.
- The feature is restricted to the audited host version, main-thread calls and
  media-header instances. Flip tiny screen and dynamic island remain native.
  Delete-menu effects and ordinary notification-row effects are not hooked.
- All signatures resolve before hooks become active. If any installation fails,
  already-installed callbacks stay pass-through instead of producing a partially
  recolored card. Both the Object effect bridge and typed blur overload are
  covered; nested effect calls inherit the same context without double tracking.
- Direct foreground callers and the effect consumer are deoptimized. A review
  caught the first draft confusing listener `onFullAodChange(boolean, boolean)`
  with controller `onFullAodStateChanged(boolean)`; the former is the verified
  foreground caller. A checked-in MT method fixture now tests every target and
  deoptimized caller, including return types and parameter descriptors.

## Lifecycle review

Each media-header view has a weakly indexed binding. Attach registers a pre-draw
observer and refreshes colors for its still-current controller; detach removes
the observer and does not render into detached views. Native repeat-when-attached
material binding handles reattachment and reinflation. Controller references are
weak and restoration checks that the holder still belongs to this header.

Safe mode or a configuration change to tiny-screen restores the last owned
background through its original native effect and context, and refreshes native
foreground colors. A different native background object cancels old restoration;
an unmodified night update also discards old ownership. Pending AOD animation
defers restoration until the native transition releases ownership. Repeated
restoration failure is latched instead of logged on every frame.

This remains a bounded source audit. Runtime reflection into the instance-final
context field, native drawable reuse, material animation interactions, and all
cross-generation hot-reload cleanup are not proven by JVM tests. A native effect
may preserve the same background object while changing blend state; ownership is
not an exhaustive snapshot of private renderer state. Uniform device acceptance
must exercise these transitions before the feature is considered accepted.

## Verification and inventory

Final counts and APK identity: `docs/media-dark-build.json`.
Gradle log: `build/media-dark-build.log`. Sixteen new tests cover the feature
catalog, version/thread/tiny-screen/safe-mode gates, configuration bits,
context restoration including exceptions/nesting/new host writes, background
ownership and every installed method/caller against the MT signature fixture.

The inventory still has 337 preference/navigation/dependency rows, not functions:
97 pending, 132 existing partial implementations needing audit, 83 bounded static
implementations, 18 repairs awaiting device tests, four partial static rows and
three other boundary-specific statuses. Only the always-dark row changed status.

The custom background-mode row remains pending, with a note recording the real
OS4 effect chain. Cover art, blurred cover, radial/linear gradients, foreground
color animation, inverse colors and ambient-light options are not implemented by
this switch. Progressbar replacement/comet and dynamic island remain separate.
Full lint was not rerun; the previously reported 22 errors are not claimed fixed.

## Unified device acceptance additions

1. Day/night system themes with the switch off/on; normal, blur and glass materials;
   title/artist/time/action/seamless/seekbar contrast and disabled progress state.
2. Keyguard entry/exit with light/dark wallpaper, full AOD and pending AOD
   transitions; ensure seekbar/time hiding and wake-lock handling remain native.
3. Repeated app/media changes, rotation, density/font-scale changes, reinflation,
   detach/reattach and real flip tiny-screen transitions.
4. Safe mode before/during an effect update and AOD animation; native material
   and foreground restoration without stale owner/holder writes.
5. Combinations with migrated media layout, button sizing, text size and progress
   height. No acceptance claim based on a visible settings entry alone.
