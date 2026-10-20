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
    with urllib.request.urlopen(req, timeout=12) as response:
        return json.load(response)

def rows(obj):
    """Extract category arrays without silently assuming one API envelope."""
    if isinstance(obj, list):
        return obj
    if isinstance(obj, dict):
        for key in ("content", "items", "categories", "category", "result", "item", "data"):
            if key in obj:
                found = rows(obj[key])
                if found:
                    return found
    return []

def structure(obj, depth=0):
    """Record only envelope keys and JSON types, never seller or product values."""
    if depth >= 4:
        return {"type": type(obj).__name__}
    if isinstance(obj, list):
        return {"type": "list", "length": len(obj),
                "first": structure(obj[0], depth + 1) if obj else None}
    if isinstance(obj, dict):
        return {"type": "dict", "fields": {
            key: structure(value, depth + 1) for key, value in obj.items()
            if key not in ("id", "seller_id", "id_goods", "product_id", "name",
                           "title", "description", "text", "value")
        }}
    return {"type": type(obj).__name__}

def safe_category(entry):
    if not isinstance(entry, dict):
        return {}
    # Only documented category identifiers and display labels; no product data.
    return {k: entry[k] for k in ("id", "category_id", "name", "title", "parent_id")
            if k in entry and isinstance(entry[k], (str, int, float, list))}

report = {"marketplace": {}, "search": {}, "product_details": [], "errors": []}
sample_product_ids = []
seller_samples = []
CATEGORY_FIELDS = ("category_id", "category", "breadcrumbs", "subcategory_id", "owner")
def category_metadata(obj):
    if not isinstance(obj, dict):
        return {}
    return {key: obj[key] for key in CATEGORY_FIELDS
            if key in obj and isinstance(obj[key], (int, float, str, list, dict))}

try:
    data = get("/api/dictionary/platforms/categories/plati")
    categories = rows(data)
    report["marketplace"]["root_retval"] = data.get("retval") if isinstance(data, dict) else None
    report["marketplace"]["root_categories"] = [safe_category(x) for x in categories][:100]
    report["marketplace"]["root_count"] = len(categories)
    subcategories = []
    for category in categories[:40]:
        cid = category.get("category_id", category.get("id")) if isinstance(category, dict) else None
        if not isinstance(cid, int):
            continue
        try:
            sub = get("/api/dictionary/platforms/subcategories/" + str(cid))
            items = rows(sub)
            subcategories.append({"parent_id": cid, "count": len(items), "response_structure": structure(sub),
                                  "items": [safe_category(x) for x in items][:100]})
        except Exception as exc:
            report["errors"].append("subcategory " + str(cid) + ": " + type(exc).__name__)
    report["marketplace"]["subcategories"] = subcategories
except Exception as exc:
    report["errors"].append("platform categories: " + type(exc).__name__ + ": " + str(exc)[:160])

for query in ("Hogwarts Legacy Steam", "Cyberpunk 2077 Steam",
              "Steam Gift игра", "Steam Key игра", "Steam аккаунт игра"):
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
        # IDs are used in memory only to fetch public product details.
        for item in items:
            if not isinstance(item, dict):
                continue
            product_id = str(item.get("id", item.get("id_goods", "")))
            if product_id.isdecimal() and len(product_id) <= 12 and product_id not in sample_product_ids:
                sample_product_ids.append(product_id)
            seller_id = str(item.get("seller_id", ""))
            if seller_id.isdecimal() and product_id.isdecimal() and seller_id not in [x[1] for x in seller_samples]:
                seller_samples.append((product_id, seller_id))
            if len(sample_product_ids) >= 8 and len(seller_samples) >= 8:
                break
        report["search"][query] = {
            "retval": data.get("retval") if isinstance(data, dict) else None,
            "item_count": len(items), "metadata_field_names": names,
            "sample_category_metadata": [category_metadata(item) for item in items if category_metadata(item)][:10],
            "items_with_category_fields": sum(
                any("categor" in k.lower() for k in item)
                for item in items if isinstance(item, dict)),
        }
    except Exception as exc:
        report["errors"].append("search: " + type(exc).__name__ + ": " + str(exc)[:160])

# Correlate search results with their public product-detail category metadata.
for product_id in sample_product_ids[:8]:
    try:
        data = get("/api/products/" + product_id + "/data", {"currency": "RUB", "lang": "ru-RU"})
        product = data.get("product", {}) if isinstance(data, dict) else {}
        report["product_details"].append({
            "retval": data.get("retval") if isinstance(data, dict) else None,
            "category_metadata": category_metadata(product),
            "category_field_names": sorted(k for k in product if
                any(term in k.lower() for term in ("categor", "breadcrumb", "owner")))
        })
    except Exception as exc:
        report["errors"].append("product detail: " + type(exc).__name__)

# Seller-specific category trees and seller-scoped product cards.
# Seller/product IDs are only used in memory, never saved in the report.
report["seller_samples"] = []
for index, (product_id, seller_id) in enumerate(seller_samples[:8], 1):
    sample = {"sample": index}
    categories = []
    metadata = {}
    try:
        data = get("/api/categories", {"seller_id": seller_id})
        categories = rows(data)
        sample["seller_categories"] = {
            "retval": data.get("retval") if isinstance(data, dict) else None,
            "count": len(categories),
            "response_structure": structure(data),
            "labels": [
                {key: value for key, value in safe_category(x).items()
                 if key in ("name", "title")}
                for x in categories[:20]
            ]
        }
    except Exception as exc:
        sample["categories_error"] = type(exc).__name__
    try:
        data = get("/api/products/" + product_id + "/data", {
            "seller_id": seller_id, "currency": "RUB", "lang": "ru-RU"
        })
        product = data.get("product", {}) if isinstance(data, dict) else {}
        metadata = category_metadata(product)
        sample["product_with_seller"] = {
            "retval": data.get("retval") if isinstance(data, dict) else None,
            "category_id_present": "category_id" in metadata,
            "category_id_nonzero": str(metadata.get("category_id", "0")) not in ("0", "", "None"),
            "breadcrumb_count": len(metadata.get("breadcrumbs", [])) if isinstance(metadata.get("breadcrumbs"), list) else 0,
            "category_field_names": sorted(k for k in product if
                any(term in k.lower() for term in ("categor", "breadcrumb", "owner")))
        }
    except Exception as exc:
        sample["product_error"] = type(exc).__name__
    # Verify an exact seller-category ID match without publishing identifiers.
    seller_categories = categories if "seller_categories" in sample else []
    product_category = metadata.get("category_id") if "product_with_seller" in sample else None
    matches = [
        x.get("name", "") for x in seller_categories if isinstance(x, dict)
        and str(x.get("id", x.get("category_id", ""))) == str(product_category)
        and str(product_category) not in ("0", "None", "")
    ]
    sample["matched_category"] = {
        "found": len(matches) == 1,
        "name": matches[0][:100] if len(matches) == 1 else None,
        "matches": len(matches)
    }
    label = matches[0] if len(matches) == 1 else ""
    tokens = set(label.lower().replace("ё", "е").replace("-", " ").split())
    sample["matched_category"]["kind"] = (
        "Excluded" if tokens & {"аккаунт", "аккаунты", "accounts", "account", "профили", "профиль", "offline", "аренда"}
        else "Gift" if tokens & {"gift", "gifts", "гифт", "гифты"} and not tokens & {"key", "keys", "ключ", "ключи"}
        else "Key" if tokens & {"key", "keys", "ключ", "ключи"} and not tokens & {"gift", "gifts", "гифт", "гифты"}
        else "Unknown"
    )
    report["seller_samples"].append(sample)

with open("digiseller_category_probe.json", "w", encoding="utf-8") as file:
    json.dump(report, file, ensure_ascii=False, indent=2)
print(json.dumps(report, ensure_ascii=False, indent=2))
