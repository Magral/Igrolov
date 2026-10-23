import json
import urllib.request


APP_ID = 1771300
headers = {"Accept": "application/json", "User-Agent": "Igrolov CI smoke"}


def get_json(url):
    request = urllib.request.Request(url, headers=headers)
    with urllib.request.urlopen(request, timeout=20) as response:
        return json.loads(response.read().decode("utf-8"))


app = get_json(
    f"https://store.steampowered.com/api/appdetails?appids={APP_ID}&l=english&cc=us"
)[str(APP_ID)]
assert app["success"] and app["data"]["type"] == "game"
royal_packages = [
    option["packageid"]
    for group in app["data"].get("package_groups", [])
    for option in group.get("subs", [])
    if "royal edition" in option.get("option_text", "").lower()
]
assert royal_packages, "Steam appdetails no longer lists the KCD II Royal Edition package"

verified = False
for package_id in royal_packages:
    package = get_json(
        f"https://store.steampowered.com/api/packagedetails?packageids={package_id}&cc=us&l=english"
    ).get(str(package_id), {})
    if package.get("success") and any(
        entry.get("id") == APP_ID for entry in package.get("data", {}).get("apps", [])
    ):
        verified = True
        break
assert verified, "The KCD II Royal Edition package does not contain the selected base game"
print(f"Steam edition smoke passed: app {APP_ID}, packages {royal_packages}")
