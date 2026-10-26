#!/usr/bin/env python3
"""Smoke check Steam's public feed for currently featured themed sale pages."""
import json
import re
import time
import urllib.error
import urllib.request

BASE = "https://store.steampowered.com"
HEADERS = {
    "Accept": "application/json,text/html,application/xhtml+xml",
    "User-Agent": "Igrolov Android themed-sales-smoke",
}


def get(url: str) -> str:
    last_error = None
    for attempt in range(3):
        request = urllib.request.Request(url, headers=HEADERS)
        try:
            with urllib.request.urlopen(request, timeout=20) as response:
                return response.read().decode("utf-8", errors="replace")
        except (urllib.error.URLError, TimeoutError) as error:
            last_error = error
            if attempt < 2:
                time.sleep(attempt + 1)
    raise RuntimeError(f"Steam endpoint unavailable: {last_error}")


def themed_sale_links(payload: dict) -> list[tuple[str, str]]:
    candidates = []
    groups = [payload.get(str(index), {}) for index in range(8)]
    if isinstance(payload.get("items"), list):
        groups.append({"name": "", "items": payload["items"]})
    groups.extend([payload.get("spotlights", {}), payload.get("events", {})])
    for group in groups:
        items = group.get("items", []) if isinstance(group, dict) else group
        if not isinstance(items, list):
            continue
        fallback_name = group.get("name", "") if isinstance(group, dict) else ""
        for item in items:
            if not isinstance(item, dict):
                continue
            url = next((str(item.get(key, "")).strip() for key in ("url", "sale_url", "target_url")
                        if "store.steampowered.com" in str(item.get(key, ""))
                        and "/sale/" in str(item.get(key, "")).lower()), "")
            if not url:
                continue
            name = next((str(item.get(key, "")).strip() for key in ("title", "name", "alt")
                         if item.get(key) and re.search(
                             r"sale|fest|festival|распрод|фест|скидк|акци",
                             str(item.get(key)), re.IGNORECASE)), "")
            name = name or (fallback_name if re.search(
                r"sale|fest|festival|распрод|фест|скидк|акци", fallback_name, re.IGNORECASE) else "")
            name = name or re.sub(r"[_/-]+", " ", url.split("/sale/", 1)[1].split("?", 1)[0]).strip()
            if re.search(r"sale|fest|festival|распрод|фест|скидк|акци", name, re.IGNORECASE):
                candidates.append((name, url))
    return list(dict.fromkeys(candidates))


featured_url = f"{BASE}/api/featuredcategories/?cc=ru&l=russian"
featured = json.loads(get(featured_url))
if not isinstance(featured, dict):
    raise RuntimeError("Steam featured categories response is not a JSON object")

events = themed_sale_links(featured)
source = "featuredcategories"
if not events:
    fallback = json.loads(get(f"{BASE}/api/discounts_and_events_data/?cc=ru&l=russian"))
    events = themed_sale_links(fallback)
    source = "discounts_and_events_data"

print(f"Steam themed sale source: {source}; active sale pages: {len(events)}")
for name, url in events[:5]:
    localized = url + ("&" if "?" in url else "?") + "cc=ru&l=russian"
    page = get(localized)
    app_ids = set(re.findall(r"""(?:data-ds-appid|data-appid)=["']([0-9]{1,10})["']""", page, re.IGNORECASE))
    print(f"- {name}: Steam page reachable; game ids in initial HTML: {len(app_ids)}")
