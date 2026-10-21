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
CATEGORY_GOODS_FALLBACK = "https://api.ggsel.com/elastic/goods/categories"
CATEGORY_SEARCH_FALLBACK = "https://api.ggsel.com/elastic/goods/query"
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
    for attempt in range(5):
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
            if exc.code not in (429, 500, 502, 503, 504) or attempt == 4:
                raise RuntimeError(f"{url}: HTTP {exc.code}") from exc
            last = exc
        except (urllib.error.URLError, TimeoutError, json.JSONDecodeError) as exc:
            if attempt == 4:
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
        html = response.read().decode("utf-8")
        print(f"GGSEL category page: HTTP {response.status}, final_url={response.geturl()}, content_type={response.headers.get('Content-Type', '')}, bytes={len(html)}")
        return html

def category_id_from_html(html, expected_name, expected_slug):
    next_data = re.search(
        r"""<script\b(?=[^>]*\bid=["']__NEXT_DATA__["'])[^>]*>(.*?)</script\s*>""",
        html, re.IGNORECASE | re.DOTALL
    )
    if next_data:
        try:
            category = json.loads(next_data.group(1)).get("props", {}).get("pageProps", {}).get("category", {})
            if category.get("name") == expected_name and category.get("url") == expected_slug:
                catalog_id = int(category.get("digi_catalog") or 0)
                if catalog_id > 0:
                    return catalog_id
        except (ValueError, TypeError, json.JSONDecodeError):
            pass

    # Current GGSEL App Router pages serialize category metadata in hydration data.
    pattern = re.compile(
        r"""(?is)\{[^{}]{0,5000}?"digi_catalog"\s*:\s*"?(\d+)"?[^{}]{0,2000}?"name"\s*:\s*"([^"\\]+)"[^{}]{0,1000}?"url"\s*:\s*"([^"\\]+)"""
    )
    for candidate in pattern.finditer(html):
        if candidate.group(2).casefold() == expected_name.casefold() and candidate.group(3) == expected_slug:
            return int(candidate.group(1))
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
    page_html = get_text(CATEGORY_PAGE + slug)
    catalog_id = category_id_from_html(page_html, name, slug)
    if catalog_id <= 0:
        preview = re.sub(r"\s+", " ", page_html[:700])
        print(f"GGSEL category page HTML preview: {preview}", file=sys.stderr)
        for marker in ("digi_catalog", name, "pageProps", "__next_f"):
            positions = [m.start() for m in re.finditer(re.escape(marker), page_html, re.IGNORECASE)][:3]
            for position in positions:
                context = re.sub(r"\s+", " ", page_html[max(0, position - 160):position + 360])
                print(f"GGSEL page marker {marker} at {position}: {context}", file=sys.stderr)
        raise RuntimeError(f"GGSEL category page did not expose digi_catalog for {name} ({slug})")
    body = {
        "digi_catalog": catalog_id, "url": slug, "limit": 5,
        "content_type_ids": [], "search_after": [], "with_filters": False,
        "is_preorders": False, "ab_test_without_emoji": True,
        "sort": "sortByRec", "query_string": "", "with_forbidden": False,
        "min_price": "", "max_price": "", "currency": "wmr",
        "lang": "en", "platforms": ["Steam"], "page": 1,
    }
    api4_dns_unavailable = False
    variants = [
        ("wide-category", {**body, "limit": 60, "with_filters": True, "sort": "sortByPriceUp", "platforms": []}),
        ("steam-filter", {**body, "limit": 60, "with_filters": True, "sort": "sortByPriceUp", "platforms": ["Steam"]}),
        ("current", body),
    ]
    for label, request_body in variants:
        if api4_dns_unavailable:
            products = post_json(CATEGORY_GOODS_FALLBACK, request_body)
        else:
            try:
                products = post_json(CATEGORY_GOODS, request_body)
            except RuntimeError as exc:
                message = str(exc).lower()
                if not any(term in message for term in ("name or service not known", "temporary failure in name resolution", "getaddrinfo failed", "nodename nor servname")):
                    raise
                api4_dns_unavailable = True
                print("GGSEL api4 DNS unavailable; retrying product catalog through api.ggsel.com", file=sys.stderr)
                products = post_json(CATEGORY_GOODS_FALLBACK, request_body)
        if not isinstance(products, dict) or not isinstance(products.get("data"), dict):
            raise RuntimeError("GGSEL product catalog returned an invalid response")
        items = products["data"].get("items")
        if not isinstance(items, list):
            raise RuntimeError("GGSEL product catalog response has no items list")
        print(f"GGSEL live variant {label}: {len(items)} product rows")
        if items:
            print(f"GGSEL live smoke passed: exact category + page ID + product catalog ({catalog_id}: {name}, variant={label})")
            return
    search_body = {
        "search_term": "Stellaris", "digi_catalog": catalog_id, "url": slug,
        "limit": 60, "search_after": [], "is_preorders": False,
        "with_filters": True, "with_categories": False, "sort": "sortByPriceUp",
        "content_type_ids": [48, 2], "with_forbidden": False,
        "min_price": "", "max_price": "", "currency": "wmr",
        "lang": "en", "platforms": ["Steam"],
    }
    search_results = post_json(CATEGORY_SEARCH_FALLBACK, search_body)
    search_data = search_results.get("data") if isinstance(search_results, dict) else None
    search_items = search_data.get("items") if isinstance(search_data, dict) else None
    if not isinstance(search_items, list):
        raise RuntimeError("GGSEL search endpoint returned an invalid product list")
    print(f"GGSEL live search fallback: {len(search_items)} product rows")
    matching = [item for item in search_items if isinstance(item, dict) and "stellaris" in str(item.get("name", "")).casefold()]
    if matching:
        print(f"GGSEL live smoke passed: exact category + page ID + query endpoint ({catalog_id}: {name}, {len(matching)} Stellaris rows)")
        return
    raise RuntimeError("GGSEL product/category APIs returned no Stellaris rows")

if __name__ == "__main__":
    try:
        main()
    except Exception as exc:
        print(f"GGSEL live smoke failed: {exc}", file=sys.stderr)
        sys.exit(1)
