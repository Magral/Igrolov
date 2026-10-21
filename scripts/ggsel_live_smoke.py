#!/usr/bin/env python3
"""Read-only live smoke test for the public GGSEL buyer catalog endpoints."""
import json
import re
import sys
import time
import urllib.error
import urllib.request

CATEGORY_QUERY = "https://api.ggsel.com/elastic/goods/query-categories"
CATEGORY_GOODS = "https://api4.ggsel.com/elastic/goods/categories"
CATEGORY_PAGE = "https://ggsel.net/en/catalog/"
HEADERS = {
    "Accept": "application/json, text/plain, */*",
    "Content-Type": "application/json",
    "Origin": "https://ggsel.net",
    "Referer": "https://ggsel.net/",
    "Accept-Language": "en-US,en;q=0.9",
    "User-Agent": "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 Chrome/120.0.0.0 Mobile Safari/537.36 Igrolov/1.1.6",
}

def post_json(url, payload):
    body = json.dumps(payload, ensure_ascii=False).encode("utf-8")
    last = None
    for attempt in range(3):
        request = urllib.request.Request(url, data=body, headers=HEADERS, method="POST")
        try:
            with urllib.request.urlopen(request, timeout=20) as response:
                if not 200 <= response.status < 300:
                    raise RuntimeError(f"{url}: HTTP {response.status}")
                content_type = response.headers.get("Content-Type", "")
                if "json" not in content_type.lower():
                    raise RuntimeError(f"{url}: unexpected content type {content_type!r}")
                value = json.loads(response.read().decode("utf-8"))
                if not isinstance(value, dict):
                    raise RuntimeError(f"{url}: JSON root is not an object")
                return value
        except urllib.error.HTTPError as exc:
            if exc.code not in (429, 500, 502, 503, 504) or attempt == 2:
                raise RuntimeError(f"{url}: HTTP {exc.code}") from exc
            last = exc
        except (urllib.error.URLError, TimeoutError, json.JSONDecodeError) as exc:
            if attempt == 2:
                raise RuntimeError(f"{url}: {exc}") from exc
            last = exc
        time.sleep(1 + attempt)
    raise RuntimeError(str(last))

def get_text(url):
    headers = {
        "Accept": "text/html,application/xhtml+xml",
        "Origin": "https://ggsel.net",
        "Referer": "https://ggsel.net/",
        "Accept-Language": "en-US,en;q=0.9",
        "User-Agent": HEADERS["User-Agent"],
    }
    request = urllib.request.Request(url, headers=headers, method="GET")
    with urllib.request.urlopen(request, timeout=20) as response:
        if not 200 <= response.status < 300:
            raise RuntimeError(f"{url}: HTTP {response.status}")
        return response.read().decode("utf-8")

def category_id_from_html(html):
    match = re.search(
        r"""<script\b(?=[^>]*\bid=["']__NEXT_DATA__["'])[^>]*>(.*?)</script\s*>""",
        html, re.IGNORECASE | re.DOTALL
    )
    if not match:
        return 0
    try:
        category = json.loads(match.group(1)).get("props", {}).get("pageProps", {}).get("category", {})
        return int(category.get("digi_catalog") or 0)
    except (ValueError, TypeError, json.JSONDecodeError):
        return 0

def find_categories(root):
    pending = [root]
    visited = 0
    while pending and visited < 20000:
        value = pending.pop()
        visited += 1
        if isinstance(value, dict):
            try:
                catalog_id = int(value.get("digi_catalog", 0))
            except (TypeError, ValueError):
                catalog_id = 0
            name = str(value.get("name", value.get("title", ""))).strip()
            slug = str(value.get("url", "")).strip()
            if catalog_id >= 0 and name and slug and slug.replace("-", "").replace("_", "").isalnum():
                yield catalog_id, name, slug
            pending.extend(v for v in value.values() if isinstance(v, (dict, list)))
        elif isinstance(value, list):
            pending.extend(v for v in value if isinstance(v, (dict, list)))

def main():
    categories = list(find_categories(post_json(CATEGORY_QUERY, {"search_term": "Stellaris", "lang": "en", "limit": 10})))
    exact = next((c for c in categories if c[1].casefold() == "stellaris"), None)
    if exact is None:
        raise RuntimeError(f"GGSEL did not return exact Stellaris category; found {[c[1] for c in categories[:10]]}")
    _, name, slug = exact
    catalog_id = category_id_from_html(get_text(CATEGORY_PAGE + slug))
    if catalog_id <= 0:
        raise RuntimeError(f"GGSEL category page did not expose digi_catalog for {name} ({slug})")
    body = {
        "digi_catalog": catalog_id, "url": slug, "limit": 5,
        "content_type_ids": [], "search_after": [], "with_filters": False,
        "is_preorders": False, "ab_test_without_emoji": True,
        "sort": "sortByRec", "query_string": "", "with_forbidden": False,
        "min_price": "", "max_price": "", "currency": "wmr",
        "lang": "en", "platforms": ["Steam"], "page": 1,
    }
    products = post_json(CATEGORY_GOODS, body)
    if not isinstance(products, dict) or not isinstance(products.get("data"), dict):
        raise RuntimeError("GGSEL product catalog returned an invalid response")
    items = products["data"].get("items")
    if not isinstance(items, list):
        raise RuntimeError("GGSEL product catalog response has no items list")
    print(f"GGSEL live smoke passed: exact category + page ID + product catalog ({catalog_id}: {name}, {len(items)} rows)")

if __name__ == "__main__":
    try:
        main()
    except Exception as exc:
        print(f"GGSEL live smoke failed: {exc}", file=sys.stderr)
        sys.exit(1)
