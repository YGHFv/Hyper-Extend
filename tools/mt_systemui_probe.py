#!/usr/bin/env python3
"""Read-only MCP refresh of the exact host-code evidence used by SystemUI hooks."""
import argparse
import hashlib
import json
import urllib.request
from pathlib import Path


class MtSession:
    def __init__(self, url):
        self.url = url
        self.session = None
        self.counter = 0
        self.request("initialize", {
            "protocolVersion": "2025-06-18", "capabilities": {},
            "clientInfo": {"name": "hyperextend-systemui-probe", "version": "1.0"},
        })
        self.request("notifications/initialized", {}, notification=True)

    def request(self, method, params, notification=False):
        self.counter += 1
        payload = {"jsonrpc": "2.0", "method": method, "params": params}
        if not notification:
            payload["id"] = self.counter
        headers = {"Content-Type": "application/json", "Accept": "application/json, text/event-stream"}
        if self.session:
            headers["Mcp-Session-Id"] = self.session
            headers["MCP-Protocol-Version"] = "2025-06-18"
        request = urllib.request.Request(self.url, json.dumps(payload).encode(), headers, method="POST")
        with urllib.request.urlopen(request, timeout=180) as response:
            self.session = response.headers.get("Mcp-Session-Id", self.session)
            text = response.read().decode("utf-8")
            if notification:
                return None
            if "text/event-stream" in response.headers.get("Content-Type", ""):
                messages = [json.loads(line[5:].strip()) for line in text.splitlines() if line.startswith("data:")]
            else:
                messages = [json.loads(text)]
        reply = next(message for message in messages if message.get("id") == self.counter)
        if "error" in reply:
            raise RuntimeError(reply["error"])
        return reply["result"]

    def tool(self, name, arguments):
        if name not in {"mt_apk_read_text", "mt_apk_list_workspaces", "mt_apk_search", "mt_apk_resource_read"}:
            raise ValueError("This probe only permits read-only evidence calls")
        result = self.request("tools/call", {"name": name, "arguments": arguments})
        if result.get("isError"):
            raise RuntimeError(result)
        data = json.loads(next(item["text"] for item in result["content"] if item["type"] == "text"))
        if not data["ok"]:
            raise RuntimeError(data["error"])
        return data["data"]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--url", default="http://192.168.10.85:8787/mcp")
    parser.add_argument("--workspace", default="wot5ikcq")
    parser.add_argument("--manifest", type=Path, default=Path("docs/systemui-host-evidence.json"))
    parser.add_argument("--output", type=Path, default=Path(".workbuddy/tmp/mt-systemui/evidence-refresh.json"))
    args = parser.parse_args()
    manifest = json.loads(args.manifest.read_text(encoding="utf-8"))
    session = MtSession(args.url)
    hosts = session.tool("mt_apk_list_workspaces", {})["items"]
    host = next(item for item in hosts if item["workspaceId"] == args.workspace)
    if host["packageName"] != manifest["host"]["packageName"]:
        raise RuntimeError("Workspace is not the expected host package")
    if host["versionCode"] != manifest["host"]["versionCode"]:
        raise RuntimeError("Host version differs from the evidence manifest; audit it before updating the manifest")
    verified = []
    for evidence in manifest["evidence"]:
        data = session.tool("mt_apk_read_text", {
            "workspaceId": args.workspace, "editSessionId": "", "locator": evidence["locator"],
            "limit": 2000, "maxChars": 131072, "startLine": 0, "startColumn": 0,
        })
        if data.get("pagination", {}).get("hasMore") or data.get("truncated"):
            raise RuntimeError(f"Incomplete text: {evidence['locator']}")
        text = data["textWindow"]["text"]
        missing = [fragment for fragment in evidence["contains"] if fragment not in text]
        if missing:
            raise RuntimeError(f"Host code changed: {evidence['locator']}: {missing}")
        verified.append({"locator": evidence["locator"], "sha256": hashlib.sha256(text.encode()).hexdigest()})
        print("verified", evidence["locator"])
    resources = []
    for resource in manifest.get("resourceNames", []):
        data = session.tool("mt_apk_search", {
            "workspaceId": args.workspace, "editSessionId": "", "target": "resource_table_names",
            "query": resource["name"], "queryType": "literal", "caseSensitive": True,
            "matchMode": "exact", "prefix": "", "includeMatchOffsets": False,
            "limit": 50, "snippetMaxChars": 0,
        })
        if data["pagination"]["hasMore"] or data.get("skippedScopes"):
            raise RuntimeError(f"Incomplete resource lookup: {resource['name']}")
        matches = [hit for hit in data["hits"] if hit["hit"].get("type") == resource["type"]]
        if len(matches) != 1 or matches[0]["locator"] != resource["locator"]:
            raise RuntimeError(f"Resource identity changed: {resource['name']}: {matches}")
        resources.append(resource)
        print("verified resource", resource["type"] + "/" + resource["name"])
    resource_files = []
    for resource in manifest.get("resourceFiles", []):
        data = session.tool("mt_apk_resource_read", {
            "workspaceId": args.workspace, "editSessionId": "",
            "reads": [{"locator": resource["locator"], "variant": "default"}],
            "maxValueChars": 4096, "maxValueXmlChars": 32768, "maxItemsPerValue": 50, "resolveDepth": 0,
        })
        values = data["results"]
        if (len(values) != 1 or values[0]["valueKind"] != "file_path"
                or values[0].get("valueTruncated") or values[0]["value"] != resource["path"]):
            raise RuntimeError(f"Resource file mapping changed: {resource}: {data}")
        resource_files.append(resource)
        print("verified resource file", resource["path"])
    resource_values = []
    for resource in manifest.get("resourceValues", []):
        data = session.tool("mt_apk_resource_read", {
            "workspaceId": args.workspace, "editSessionId": "",
            "reads": [{"locator": resource["locator"], "variant": resource["variant"]}],
            "maxValueChars": 4096, "maxValueXmlChars": 32768, "maxItemsPerValue": 50, "resolveDepth": 0,
        })
        values = data["results"]
        if (len(values) != 1 or values[0].get("errorCode") or values[0].get("valueTruncated")
                or values[0].get("valueKind") != resource["valueKind"] or values[0].get("value") != resource["value"]):
            raise RuntimeError(f"Resource value changed: {resource}: {data}")
        resource_values.append(resource)
        print("verified resource value", resource["locator"], resource["variant"])
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps({"host": host, "verified": verified, "resources": resources, "resourceFiles": resource_files,
                                       "resourceValues": resource_values}, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    print(f"Verified {len(verified)} host code targets. This is not a device runtime test.")
    if resources:
        print(f"Verified {len(resources)} resource name/id mappings.")


if __name__ == "__main__":
    main()
