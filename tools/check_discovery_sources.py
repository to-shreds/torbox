#!/usr/bin/env python3
"""Read-only public provider smoke check. No account, API key, or torrent add operation."""
import json
import re
import sys
import urllib.request
from pathlib import Path

MAX_BYTES = 4 * 1024 * 1024
results = []

class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, request, file_pointer, code, message, headers, new_url):
        return None

opener = urllib.request.build_opener(NoRedirect)

def get_json(url):
    request = urllib.request.Request(url, headers={"Accept": "application/json", "User-Agent": "TorBox-Discover-Contract-Check/1.0"})
    with opener.open(request, timeout=25) as response:
        raw = response.read(MAX_BYTES + 1)
        if len(raw) > MAX_BYTES:
            raise ValueError("Oversized provider response")
        value = json.loads(raw)
        if not isinstance(value, dict):
            raise ValueError("Expected a JSON object")
        return value

def check(label, action):
    try:
        detail = action()
        results.append({"check": label, "ok": True, "detail": detail})
    except Exception as failure:
        results.append({"check": label, "ok": False, "detail": f"{type(failure).__name__}: {failure}"})

def manifest():
    data = get_json("https://v3-cinemeta.strem.io/manifest.json")
    catalogs = data.get("catalogs", [])
    if not all(any(c.get("type") == kind and c.get("id") == "top" for c in catalogs) for kind in ("movie", "series")):
        raise ValueError("Expected movie and series Popular catalogs")
    return [{"id": c.get("id"), "type": c.get("type"), "name": c.get("name")} for c in catalogs]

def source(kind):
    data = get_json(f"https://v3-cinemeta.strem.io/catalog/{kind}/top.json")
    metas = data.get("metas")
    if not isinstance(metas, list):
        raise ValueError("Missing metas array")
    title = next((m for m in metas if re.fullmatch(r"tt\d{5,12}", str(m.get("id", "")))), None)
    if title is None:
        raise ValueError("No usable IMDb ID in Popular catalog")
    identifier = title["id"] + (":1:1" if kind == "series" else "")
    data = get_json(f"https://torrentio.strem.fun/stream/{kind}/{identifier}.json")
    streams = data.get("streams")
    if not isinstance(streams, list):
        raise ValueError("Missing streams array")
    hashes = {s["infoHash"].lower() for s in streams if re.fullmatch(r"[a-fA-F0-9]{40}", str(s.get("infoHash", "")))}
    if streams and not hashes:
        raise ValueError("Provider returned placeholders, not torrent hashes")
    return {"sample_id": identifier, "catalog_titles": len(metas), "valid_candidate_hashes": len(hashes), "scope": "public metadata and candidates only; TorBox cache was not queried"}

check("Cinemeta manifest", manifest)
for media in ("movie", "series"):
    check(f"{media} public catalog and torrent candidates", lambda kind=media: source(kind))
report = {"checks": results, "authenticated_torbox_tested": False}
output = Path("app/build/reports/discovery-sources.json")
output.parent.mkdir(parents=True, exist_ok=True)
output.write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
print(json.dumps(report, indent=2))
sys.exit(0 if all(item["ok"] for item in results) else 1)
