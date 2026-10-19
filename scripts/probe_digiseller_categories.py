#!/usr/bin/env python3
"""Read-only public Digiseller category API probe. Never prints product IDs or seller data."""
import json
import urllib.parse
import urllib.request

BASE = "https://api.digiseller.com"
HEADERS = {"Accept": "application/json", "User-Agent": "Igrolov-API-Research/1.0"}
def get(path, params=None):
    url = BASE + path + ("?" + urllib.parse.urlencode(params) if params else "")
    req = urllib.request.Request(url, headers=HEADERS)
    with urllib.request.urlopen(req, timeout=25) as response:
        return json.load(response)

def rows(obj):
    if isinstance(obj, list):
        return obj
    if isinstance(obj, dict):
        for key in ("content", "items", "categories", "result"):
            if key in obj:
                nested = obj[key]
                if isinstance(nested, dict) and "item" in nested:
                    return rows(nested["item"])
                result = rows(nested)
                if result:
                    return result
    return []

def safe_category(entry):
    if not isinstance(entry, dict):
        return {}
    # Only documented category identifiers and display labels; no product data.
    return {k: entry[k] for k in ("id", "category_id", "name", "title", "parent_id")
            if k in entry and isinstance(entry[k], (str, int, float, list))}

report = {"marketplace": {}, "search": {}, "errors": []}
try:
    data = get("/api/dictionary/platforms/categories/plati")
    categories = rows(data)
    report["marketplace"]["root_retval"] = data.get("retval") if isinstance(data, dict) else None
    report["marketplace"]["root_categories"] = [safe_category(x) for x in categories][:100]
    report["marketplace"]["root_count"] = len(categories)
    subcategories = []
    for category in categories[:8]:
        cid = category.get("category_id", category.get("id")) if isinstance(category, dict) else None
        if not isinstance(cid, int):
            continue
        try:
            sub = get("/api/dictionary/platforms/subcategories/" + str(cid))
            items = rows(sub)
            subcategories.append({"parent_id": cid, "count": len(items),
                                  "items": [safe_category(x) for x in items][:100]})
        except Exception as exc:
            report["errors"].append("subcategory " + str(cid) + ": " + type(exc).__name__)
    report["marketplace"]["subcategories"] = subcategories
except Exception as exc:
    report["errors"].append("platform categories: " + type(exc).__name__ + ": " + str(exc)[:160])

for query in ("Hogwarts Legacy Steam", "Cyberpunk 2077 Steam"):
    try:
        data = get("/api/products/search2", {
            "query": query, "searchmode": 10, "sortmode": 2, "pagesize": 30,
            "pagenum": 1, "owner": 1, "details": 1,
            "checkhidesales": 1, "host": "plati.market"
        })
        items = rows(data)
        relevant = ("categor", "cataloguer", "seller", "owner", "platform")
        names = sorted({key for item in items if isinstance(item, dict)
                        for key in item if any(term in key.lower() for term in relevant)})
        report["search"][query] = {
            "retval": data.get("retval") if isinstance(data, dict) else None,
            "item_count": len(items), "metadata_field_names": names,
            "items_with_category_fields": sum(
                any("categor" in k.lower() for k in item)
                for item in items if isinstance(item, dict)),
        }
    except Exception as exc:
        report["errors"].append("search: " + type(exc).__name__ + ": " + str(exc)[:160])

with open("digiseller_category_probe.json", "w", encoding="utf-8") as file:
    json.dump(report, file, ensure_ascii=False, indent=2)
print(json.dumps(report, ensure_ascii=False, indent=2))
