# MT reconnection and migrated-slider review - 2026-10-11

`done=false`. This is a bounded source/host-evidence review and repair, not a
337-feature functional acceptance pass. Machine-readable results and scoped
source hashes are in `docs/mt-reconnect-validation-2026-10-11.json`.

## Connection and evidence

- `http://<mcp-host>:8787/mcp` initializes and executes read-only calls.
  It advertises 57 tools. The old `mt_apk_list_workspaces` is absent and returns
  Unknown tool; the replacement is `mt_apk_workspace_list` followed by
  `mt_apk_workspace_open`. Initial saved workspaces were empty, not a network
  failure. Repository probes no longer assume the historical workspace ID.
- Local WorkBuddy configuration already uses `.85`; the inspected Codex MT
  entry still uses `.58`, which timed out. No global client configuration was
  changed. This round uses direct MCP protocol requests from repository tools.
- Reopened exported SystemUI 202602260 (`lh32vy5t`) and plugin 183022200
  (`3dwrf9o4`) using paths returned by the server. Workspace IDs are recovery
  hints only; subsequent runs must discover/reopen and check package/version.
- Refreshed 13 manifests: native SystemUI, slider values, three percentage
  adapters, card list/color, GMS SystemUI/plugin/settings, weather, network
  loopback and native media height. All **268 evidence entries** match hashes
  (**262 distinct package/locator pairs**). 166 exact text hashes and 102 hashes
  with only a terminal-newline normalization match the historical conventions.
  Method-body changes are not hidden by fragment-only signature checks.
- This refresh covers the manifests' `evidence` arrays, not a new global xref
  sweep, all resources, framework bytecode, installed-device package identity,
  Activity launch or visual/lifecycle acceptance. Existing full cached text is
  reusable because the fresh complete text hashes match it.
- The Home APK list is complete (20 APKs); the recursive Home listing is also
  complete (21 files). No SecurityCenter APK appears in either. The policy denies
  outside Home `/storage/emulated/0/MT2/mcp`; it was not changed or bypassed.
  This is not evidence that GmsCoreSettings is absent on the device.
- Reopened exported `com.android.wm.shell` version 37 (`419o7wli`): zero DEX
  entries/classes. Its complete manifest was captured. This export does not
  supply the implementation needed to unblock the WMShell controls. Do not
  infer that the real device implementation or the features do not exist.

## Findings and repairs

1. MT helper compatibility and verification: obsolete workspace names and the
   hard-coded old ID blocked valid connections. The helper now discovers the
   advertised API, reopens saved URI paths (not possibly stale source paths),
   checks the opened package/version and fails on ambiguous/empty selections.
   Legacy API is used only if advertised. Old manifests without packageName
   require explicit `--package`, never an inferred identity from a stale ID.
   Text hashes now gate success in addition to fragments; missing criteria,
   truncation, changed bodies and non-advancing discovery are rejected.
   Both helpers share a read-only allowlist, reject edit sessions and require
   reusable read-only workspace opens. Ten offline tests exercise these gates.
2. Inline brightness/volume percentage labels could remain visible after safety
   activation: HookRuntime bypasses the very update/cleanup hooks they depended
   on. An observer exists only while the module owns a displayed label; before
   the next draw it releases the label on disable, safety, hide or detach state.
   Restore/dispose removes that observer; actual detach also restores directly.
   This does not force a frame, poll services, replay native updates or change
   volume/brightness. Percentage mode 1 shares this fix; mode 2 overlay is unchanged.
3. A native rich-text takeover with the same visible characters passed the old
   `toString()` ownership check and could be replaced by the old plain baseline.
   `SliderLabelState.release` now requires still-visible matching plain text,
   clears ownership once, and never replaces a native Spanned value. Different
   text or visibility remains untouched. No original spans are retained.

## Validation and remaining boundaries

- Selected Kotlin regression: 7 suites / 30 tests, including five new pure-state
  tests for ownership, rich-text takeover, new baselines and lifecycle release.
  Full Kotlin suite: 129 suites / 772 tests, zero failures/errors/skips.
  Tool regression: 10 tests passed. Debug lint: 0 errors, 453 warnings, 2 hints.
  Command logs are in the validation manifest. No assertions or host-signature
  requirements removed.
- Source review of native extension selection, percentage overlays and card
  list ownership did not identify another repair in this bounded pass. Matched
  host contracts and passing policies do not prove those features fully work.
- Inline cleanup is next-pre-draw/detach, not synchronous. Equal plain-text
  native writes remain indistinguishable by value ownership. Main-panel,
  mirror/edit/accessibility capability changes still require native refresh.
  Actual TextView observer/detach behavior and visual acceptance need device QA.
- GMS target presence, package toggles and settings launch remain unverified.
  WMShell/Launcher implementation artifacts and the existing 14 pending,
  27 existing-partial audit rows and other partial boundaries remain open.
- No Release packaging/R8, install, restart, hot reload, package toggle, Activity
  launch, device setting/scope/network change, commit or push was performed.
  The previously installed 16:26:54 APK does not include this or the preceding
  two source-repair batches. No ledger row was promoted to complete.

## Recheck commands

```powershell
python -m unittest discover -s tools -p test_mt_systemui_probe.py -v
python tools/mt_systemui_probe.py --manifest docs/slider-value-plugin-evidence.json
python tools/mt_systemui_probe.py --manifest docs/gms-tile-systemui-evidence.json --package com.android.systemui
```

If no matching saved workspace exists, first use the available-APK listing and
open its returned path with `temporary=false`. If multiple matches exist,
select an actual listed `--workspace`. Neither procedure weakens file policy or
the host version gate. Do not copy the old workspace IDs blindly.
