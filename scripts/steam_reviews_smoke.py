#!/usr/bin/env python3
"""Read public Steam score totals using the current API, with the store fallback."""
import json
import time
import urllib.parse
import urllib.request

for appid in (281990, 381210):
    payload = {"appid": appid, "languages": ["all"], "filter": 1,
               "review_type": 0, "purchase_type": 0, "num_per_page": 1}
    urls = [
        "https://api.steampowered.com/IUserReviewsService/GetAppReviews/v1/?input_json="
        + urllib.parse.quote(json.dumps(payload)),
        f"https://store.steampowered.com/appreviews/{appid}?json=1&filter=recent&language=all&review_type=all&purchase_type=steam&num_per_page=1",
    ]
    errors = []
    summary = None
    for attempt in range(3):
        for url in urls:
            try:
                request = urllib.request.Request(url, headers={"User-Agent": "Igrolov smoke", "Accept": "application/json"})
                with urllib.request.urlopen(request, timeout=20) as response:
                    data = json.load(response)
                if "success" in data and data["success"] != 1:
                    raise ValueError("unsuccessful store response")
                summary = data.get("response", data)["query_summary"]
                positive, total = int(summary["total_positive"]), int(summary["total_reviews"])
                assert 0 <= positive <= total and total > 0
                break
            except Exception as error:
                errors.append(str(error))
                summary = None
        if summary is not None:
            break
        time.sleep(attempt + 1)
    if summary is None:
        raise RuntimeError(f"Steam reviews {appid}: {errors}")
    print(f"Steam {appid}: {positive * 100 // total}% ({positive}/{total}), score totals verified")
