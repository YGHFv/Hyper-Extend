#!/usr/bin/env python3
"""Read-only, paginated MT evidence capture for the ongoing migration."""
import argparse
import hashlib
import json
from pathlib import Path

from mt_systemui_probe import MtSession


def call(session, name, arguments):
    return session.tool(name, arguments)


def read(session, workspace, locator, output):
    pages, chunks, line = [], [], 0
    while True:
        data = call(session, "mt_apk_read_text", {
            "workspaceId": workspace, "editSessionId": "", "locator": locator,
            "limit": 2000, "maxChars": 131072, "startLine": line, "startColumn": 0,
        })
        pages.append(data)
        more = data["pagination"]["hasMore"]
        if data["textWindow"].get("lineTruncated") or (
                data.get("truncated") and not (more and data.get("truncatedReason") == "window")):
            raise RuntimeError(f"Character-truncated evidence: {locator}")
        text = data["textWindow"]["text"]
        chunks.append(text)
        if not more:
            break
        next_line = data["textWindow"].get("nextLine")
        if next_line is None:
            next_line = line + data["pagination"].get("returnedCount", 0)
        if next_line <= line or data["textWindow"].get("nextColumn", 0):
            raise RuntimeError("Pagination did not advance")
        line = next_line
    output.parent.mkdir(parents=True, exist_ok=True)
    output.with_suffix(".json").write_text(json.dumps(pages, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    text = "\n".join(x.rstrip("\n") for x in chunks) + "\n"
    output.with_suffix(".txt").write_text(text, encoding="utf-8")
    return {"locator": locator, "sha256": hashlib.sha256(text.encode()).hexdigest(),
            "raw": output.with_suffix(".json").as_posix(), "pages": len(pages)}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("requests", type=Path)
    parser.add_argument("--url", default="http://<mcp-host>:8787/mcp")
    args = parser.parse_args()
    session = MtSession(args.url)
    for request in json.loads(args.requests.read_text(encoding="utf-8")):
        output = Path(request["output"])
        if request["tool"] == "read":
            result = read(session, request["workspace"], request["locator"], output)
            print(json.dumps(result))
        else:
            result = call(session, request["tool"], request["arguments"])
            output.parent.mkdir(parents=True, exist_ok=True)
            output.write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
            print(output, result.get("pagination", ""))


if __name__ == "__main__":
    main()
