#!/usr/bin/env python3
"""Check that current Steam top sellers have reachable vertical 600x900 artwork."""
import concurrent.futures
import html
import json
import re
import sys
import time
import urllib.error
import urllib.parse
import urllib.request

BASE = "https://store.steampowered.com"
BROWSE = "https://api.steampowered.com/IStoreBrowseService/GetItems/v1/"
HEADERS = {
    "Accept": "application/json,text/html,image/avif,image/webp,image/*,*/*;q=0.8",
    "User-Agent": "Mozilla/5.0 (compatible; Igrolov Artwork Smoke/1.0)",
}
ROW = re.compile(r"""(?is)<a\b[^>]*class=["'][^"']*search_result_row[^"']*["'][^>]*>.*?</a>""")
APP_ID = re.compile(r"""(?i)data-ds-appid=["']([0-9]{1,10})["']""")
TITLE = re.compile(r"""(?is)<span\b[^>]*class=["'][^"']*title[^"']*["'][^>]*>(.*?)</span>""")
CARD = re.compile(r"""(?is)<(?:div|section|article|li)\b(?=[^>]*\bclass\s*=\s*["'][^"']*\b(?:asset|grid-card|grid-item)\b)[^>]*>.*?(?=<(?:div|section|article|li)\b(?=[^>]*\bclass\s*=\s*["'][^"']*\b(?:asset|grid-card|grid-item)\b)|$)""")
POSTER_LINK = re.compile(r"""(?i)(?:href|src|data-src|data-original)\s*=\s*["'](https://cdn2\.steamgriddb\.com/grid/[a-f0-9]+\.(?:png|jpe?g|webp))["']""")
PORTRAIT = re.compile(r"""600\s*[×x]\s*900""", re.IGNORECASE)
ASSET_HOSTS = (
    "https://shared.steamstatic.com/store_item_assets/",
    "https://shared.akamai.steamstatic.com/store_item_assets/",
    "https://shared.fastly.steamstatic.com/store_item_assets/",
)


def get(url: str, byte_limit: int | None = None) -> bytes:
    error = None
    for attempt in range(3):
        request = urllib.request.Request(url, headers=HEADERS)
        try:
            with urllib.request.urlopen(request, timeout=18) as response:
                return response.read(byte_limit)
        except (urllib.error.URLError, TimeoutError, OSError) as exc:
            error = exc
            if attempt < 2:
                time.sleep(attempt + 1)
    raise RuntimeError(f"GET failed for {url}: {error}")


def parse_ranking_page(raw: str) -> tuple[list[tuple[int, str]], int]:
    root = json.loads(raw)
    rows = []
    for markup in ROW.finditer(root.get("results_html", "")):
        chunk = markup.group(0)
        id_match = APP_ID.search(chunk)
        name_match = TITLE.search(chunk)
        if not id_match:
            continue
        app_id = int(id_match.group(1))
        name = html.unescape(re.sub(r"<[^>]*>", " ", name_match.group(1))).strip() if name_match else str(app_id)
        rows.append((app_id, name))
    return rows, int(root.get("total_count", 0))


def current_top_sellers(limit: int = 100) -> list[tuple[int, str]]:
    items: dict[int, str] = {}
    start = 0
    total = 0
    while len(items) < limit and (start == 0 or start < total):
        params = urllib.parse.urlencode({
            "query": "", "start": start, "count": 50, "dynamic_data": "",
            "sort_by": "TopSellers", "category1": "998", "hidef2p": "1",
            "infinite": "1", "json": "1", "cc": "us", "l": "english",
        })
        page_rows, total = parse_ranking_page(get(f"{BASE}/search/results/?{params}").decode("utf-8", "replace"))
        if not page_rows:
            break
        for app_id, name in page_rows:
            items.setdefault(app_id, name)
        start += len(page_rows)
    return list(items.items())[:limit]



def current_popular_new_releases(limit: int = 100) -> list[tuple[int, str]]:
    """Mirror the app's pageable Steam Popular New Releases source."""
    items: dict[int, str] = {}
    start = 0
    total = 0
    while len(items) < limit and (start == 0 or start < total):
        params = urllib.parse.urlencode({
            "query": "", "start": start, "count": 50, "dynamic_data": "",
            "sort_by": "Released_DESC", "category1": "998", "category2": "popularnew",
            "infinite": "1", "json": "1", "cc": "ru", "l": "russian",
        })
        page_rows, total = parse_ranking_page(get(f"{BASE}/search/results/?{params}").decode("utf-8", "replace"))
        if not page_rows:
            break
        for app_id, name in page_rows:
            items.setdefault(app_id, name)
        start += len(page_rows)
    return list(items.items())[:limit]


def sale_page_game_ids(page: str) -> set[int]:
    """Read game IDs embedded in a public Steam themed-sale page."""
    decoded = html.unescape(page)
    match = re.search(r'"jsondata":"(.*?)","announcement_body"', decoded, re.DOTALL)
    if not match:
        return set()
    try:
        event_data = json.loads(json.loads('"' + match.group(1) + '"'))
    except (json.JSONDecodeError, TypeError):
        return set()
    ids: set[int] = set()

    def collect(value: object) -> None:
        if isinstance(value, dict):
            if value.get("type") == "game":
                try:
                    app_id = int(value.get("id", 0))
                    if app_id > 0:
                        ids.add(app_id)
                except (TypeError, ValueError):
                    pass
            for child in value.values():
                collect(child)
        elif isinstance(value, list):
            for child in value:
                collect(child)

    collect(event_data.get("sale_sections", []))
    return ids


def current_themed_sale_games(limit: int = 100) -> list[tuple[int, str]]:
    """Mirror the app's current Steam sale-page feed; empty is valid between events."""
    featured = json.loads(get(f"{BASE}/api/featuredcategories/?cc=ru&l=russian").decode("utf-8", "replace"))
    sale_urls: list[str] = []

    def collect_urls(value: object) -> None:
        if isinstance(value, dict):
            for child in value.values():
                if isinstance(child, str):
                    candidate = html.unescape(child).replace("\\\\/", "/")
                    if "store.steampowered.com" in candidate and "/sale/" in candidate.lower():
                        sale_urls.append(candidate)
                else:
                    collect_urls(child)
        elif isinstance(value, list):
            for child in value:
                collect_urls(child)

    collect_urls(featured)
    unique_urls = list(dict.fromkeys(sale_urls))[:5]
    games: dict[int, str] = {}
    for url in unique_urls:
        localized = url + ("&" if "?" in url else "?") + "cc=ru&l=russian"
        page = get(localized).decode("utf-8", "replace")
        ids = set(re.findall(r"""(?:data-ds-appid|data-appid)=["']([0-9]{1,10})["']""", page, re.IGNORECASE))
        ids.update(sale_page_game_ids(page))
        for app_id in ids:
            numeric_id = int(app_id)
            games.setdefault(numeric_id, str(numeric_id))
            if len(games) >= limit:
                return list(games.items())
    return list(games.items())


def actual_game_ids(app_ids: list[int]) -> set[int]:
    """Match the app's Steam enrichment filter: display games, never DLC or free apps."""
    result: set[int] = set()
    for offset in range(0, len(app_ids), 50):
        ids = app_ids[offset:offset + 50]
        joined_ids = ",".join(str(app_id) for app_id in ids)
        payload = json.loads(get(f"{BASE}/api/appdetails?appids={joined_ids}&cc=us&l=english").decode("utf-8", "replace"))
        for app_id in ids:
            entry = payload.get(str(app_id), {})
            data = entry.get("data") if entry.get("success") else None
            # Keep unknown apps conservatively: the Android app can display them with unknown RU status.
            if not isinstance(data, dict):
                result.add(app_id)
                continue
            if data.get("type") == "game" and not data.get("is_free", False):
                result.add(app_id)
    return result


def store_browse_covers(app_ids: list[int]) -> dict[int, list[str]]:
    result: dict[int, list[str]] = {}
    for offset in range(0, len(app_ids), 50):
        ids = app_ids[offset:offset + 50]
        payload = {
            "ids": [{"appid": app_id} for app_id in ids],
            "context": {"language": "english", "country_code": "US"},
            "data_request": {"include_assets": True},
        }
        url = BROWSE + "?input_json=" + urllib.parse.quote(json.dumps(payload, separators=(",", ":")))
        raw = json.loads(get(url).decode("utf-8", "replace"))
        items = raw.get("response", {}).get("store_items", [])
        for item in items:
            try:
                app_id = int(item.get("appid", item.get("id", 0)))
            except (ValueError, TypeError):
                continue
            assets = item.get("assets", {})
            template = str(assets.get("asset_url_format", "")).strip()
            urls = []
            for key in ("library_capsule_2x", "library_capsule"):
                filename = str(assets.get(key, "")).strip()
                if not filename or not template:
                    continue
                resolved = template.replace("${FILENAME}", filename)
                if resolved.startswith("https://"):
                    urls.append(resolved)
                else:
                    urls.extend(host + resolved.lstrip("/") for host in ASSET_HOSTS)
            result[app_id] = list(dict.fromkeys(urls))
    return result


def is_image(url: str) -> bool:
    referer = "https://www.steamgriddb.com/" if "steamgriddb.com" in url else "https://store.steampowered.com/"
    request = urllib.request.Request(url, headers={**HEADERS, "Range": "bytes=0-31", "Referer": referer})
    try:
        with urllib.request.urlopen(request, timeout=15) as response:
            data = response.read(32)
            mime = response.headers.get("Content-Type", "").lower()
            return response.status in (200, 206) and mime.startswith("image/") and (
                data.startswith(b"\xff\xd8\xff") or data.startswith(b"\x89PNG\r\n\x1a\n")
                or (data[:4] == b"RIFF" and data[8:12] == b"WEBP")
            )
    except (urllib.error.URLError, TimeoutError, OSError, ValueError):
        return False


def first_live(urls: list[str]) -> str | None:
    for url in urls:
        if is_image(url):
            return url
    return None


def steamgriddb_portraits(app_id: int) -> list[str]:
    page = get(f"https://www.steamgriddb.com/steam/{app_id}/grids").decode("utf-8", "replace")
    urls = []
    for match in CARD.finditer(page):
        card = match.group(0)
        if PORTRAIT.search(card):
            urls.extend(POSTER_LINK.findall(card))
    return list(dict.fromkeys(urls))


def main() -> int:
    sections = {
        "bestsellers": current_top_sellers(),
        "popular new releases": current_popular_new_releases(),
        "themed sales": current_themed_sale_games(),
    }
    if len(sections["bestsellers"]) < 50:
        raise RuntimeError(f"Steam returned only {len(sections['bestsellers'])} top sellers; refusing an incomplete artwork check")
    candidate_names: dict[int, str] = {}
    for games in sections.values():
        for app_id, name in games:
            candidate_names.setdefault(app_id, name)
    eligible = actual_game_ids(list(candidate_names))
    names = {app_id: name for app_id, name in candidate_names.items() if app_id in eligible}
    for section, games in sections.items():
        visible_ids = [app_id for app_id, _ in games if app_id in eligible]
        print(f"Artwork coverage source '{section}': {len(visible_ids)} eligible game cards (DLC/free excluded)")
    top = list(names.items())
    app_ids = list(names)
    covers = store_browse_covers(app_ids)
    checks = {}
    with concurrent.futures.ThreadPoolExecutor(max_workers=12) as pool:
        futures = {pool.submit(first_live, covers.get(app_id, [])): app_id for app_id in app_ids}
        for future in concurrent.futures.as_completed(futures):
            app_id = futures[future]
            checks[app_id] = ("Steam Store", future.result())

    missing = [app_id for app_id in app_ids if checks[app_id][1] is None]
    fallback_urls = {}
    with concurrent.futures.ThreadPoolExecutor(max_workers=6) as pool:
        futures = {pool.submit(steamgriddb_portraits, app_id): app_id for app_id in missing}
        for future in concurrent.futures.as_completed(futures):
            app_id = futures[future]
            try:
                fallback_urls[app_id] = future.result()
            except Exception as exc:
                fallback_urls[app_id] = []
                print(f"SteamGridDB page failed for {app_id} ({names[app_id]}): {exc}")

    with concurrent.futures.ThreadPoolExecutor(max_workers=12) as pool:
        futures = {pool.submit(first_live, fallback_urls.get(app_id, [])): app_id for app_id in missing}
        for future in concurrent.futures.as_completed(futures):
            app_id = futures[future]
            checks[app_id] = ("SteamGridDB", future.result())

    covered = [app_id for app_id in app_ids if checks[app_id][1] is not None]
    steam_count = sum(checks[app_id][0] == "Steam Store" for app_id in covered)
    sgdb_count = sum(checks[app_id][0] == "SteamGridDB" for app_id in covered)
    print(f"Portrait artwork smoke: {len(covered)}/{len(top)} games across all dynamic Leaders feeds have reachable vertical covers.")
    print(f"Verified sources: Steam Store {steam_count}, SteamGridDB public pages {sgdb_count}.")
    for app_id in app_ids:
        source, url = checks[app_id]
        if url is None:
            print(f"MISSING  {app_id}  {names[app_id]}")
    if len(covered) != len(top):
        raise RuntimeError(f"{len(top) - len(covered)} games across dynamic Leaders feeds have no reachable portrait cover")
    return 0


if __name__ == "__main__":
    try:
        sys.exit(main())
    except Exception as error:
        print(f"Portrait artwork smoke failed: {error}", file=sys.stderr)
        sys.exit(1)
