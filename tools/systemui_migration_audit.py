#!/usr/bin/env python3
"""Enumerate every migration row and source reference without building the app.

References are search evidence, not a proof of an active hook. In particular an
upstream Java match can be a commented-out registration or a preference writer.
This report never promotes inventory status or treats an absent old name as a
feature that the current host cannot support.
"""
import argparse
import hashlib
import json
import re
import xml.etree.ElementTree as ET
from collections import Counter
from pathlib import Path

ANDROID = "{http://schemas.android.com/apk/res/android}"
STRUCTURAL = {"PreferenceCategory", "PreferenceScreen", "Preference"}


def digest(path):
    return hashlib.sha256(path.read_bytes().replace(b"\r\n", b"\n")).hexdigest()


def review_area(key):
    if "island_media" in key or "strong_toast" in key:
        return "dynamic_island"
    if "battery_detail" in key:
        return "status_bar_battery_detail"
    if "statusbar" in key or "status_bar" in key or "statubar" in key:
        return "status_bar"
    if "media_control" in key:
        return "media"
    if any(word in key for word in ("multi_win", "infinitymode", "bottombar")):
        return "window_shell"
    if "navigation" in key:
        return "navigation"
    if "lock_screen" in key or "charging" in key or "battery_temperature" in key:
        return "lock_screen"
    if any(word in key for word in ("tile", "qs_", "sunshine", "flash_light", "gms_open", "taplus")):
        return "tiles_and_layout"
    return "control_center_and_other"


def audit(reference, inventory):
    source = json.loads(inventory.read_text(encoding="utf-8"))
    items = source["items"]
    wanted = {}
    for item in items:
        key = item["key"]
        wanted.setdefault(key, set()).add(key)
        wanted.setdefault(key.removeprefix("prefs_key_"), set()).add(key)
    hits = {item["key"]: [] for item in items}
    # Match quoted keys only; substring matches would confuse switches with their
    # dependent parameters and inflate the apparent implementation coverage.
    quoted = re.compile(r"""["']([^"'\r\n]+)["']""")
    files = {}
    for directory in ("library/libhook/src", "library/core/src", "app/src"):
        root = reference / directory
        if not root.exists():
            continue
        for path in sorted(root.rglob("*")):
            if path.suffix not in {".kt", ".java"}:
                continue
            relative = path.relative_to(reference).as_posix()
            for line, text in enumerate(path.read_text(encoding="utf-8").splitlines(), 1):
                for match in quoted.finditer(text):
                    for key in wanted.get(match.group(1), ()):
                        hits[key].append({"file": relative, "line": line,
                                          "sourceSet": "hook" if "/libhook/" in relative else "ui_or_core",
                                          "lineText": text.strip()})
                        files[relative] = digest(path)
    xml = {}
    ancestors = {}
    for path in sorted((reference / "library/core/src/main/res/xml").glob("*.xml")):
        def visit(entry, inherited):
            key = entry.get(ANDROID + "key")
            if key in hits:
                xml[(path.stem, key)] = entry.attrib
                ancestors[(path.stem, key)] = inherited
            gating = {k: v for k, v in entry.attrib.items()
                      if k == ANDROID + "enabled" or k.endswith("isPreferenceVisible")}
            parents = inherited + ([{"tag": entry.tag, "key": key, "attributes": gating}] if gating else [])
            for child in entry:
                visit(child, parents)
        visit(ET.parse(path).getroot(), [])
    rows = []
    for item in items:
        key = item["key"]
        attributes = xml.get((item["page"], key), {})
        structural = item["kind"] in STRUCTURAL
        rows.append({
            "page": item["page"], "key": key, "title": item["title"],
            "area": review_area(key), "inventoryStatus": item["status"],
            "localTarget": item["target"], "kind": item["kind"],
            "structuralCandidate": structural,
            "upstreamEnabledAttribute": attributes.get(ANDROID + "enabled"),
            "upstreamVisibleAttribute": next((v for k, v in attributes.items() if k.endswith("isPreferenceVisible")), None),
            "upstreamFragment": attributes.get(ANDROID + "fragment"),
            "upstreamAncestorGates": ancestors.get((item["page"], key), []),
            "upstreamSourceReferences": hits[key],
            "reviewRequired": item["status"] in {"pending", "needs_audit_existing_partial"} or
                "partial" in item["status"] or "unbuilt" in item["status"],
            "boundary": item.get("boundary"),
            "evidenceManifest": item.get("evidenceManifest"),
            **{name: item[name] for name in ("counterEvidenceManifest", "settingsEvidenceManifest", "repairValidationManifest")
               if item.get(name)},
            "note": "Structural candidates and zero search hits require review, not an automatic completion waiver.",
        })
    return {
        "referenceRevision": source["revision"],
        "inventorySha256": digest(inventory),
        "validation": "Source enumeration only. No app build, test execution, lint, deployment or full logic acceptance.",
        "allEntriesEnumerated": len(rows) == len(items),
        "allFeaturesMigrated": False,
        "counts": dict(sorted(Counter(row["inventoryStatus"] for row in rows).items())),
        "reviewAreas": dict(sorted(Counter(row["area"] for row in rows if row["reviewRequired"]).items())),
        "upstreamSourceFiles": dict(sorted(files.items())),
        "items": rows,
    }


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("reference", type=Path)
    parser.add_argument("--inventory", type=Path, default=Path("docs/systemui-upstream-inventory.json"))
    parser.add_argument("--output", type=Path, default=Path("docs/systemui-source-audit-2026-10-11.json"))
    args = parser.parse_args()
    report = audit(args.reference, args.inventory)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({key: report[key] for key in ("allEntriesEnumerated", "allFeaturesMigrated", "counts", "reviewAreas")},
                     ensure_ascii=False))


if __name__ == "__main__":
    main()
