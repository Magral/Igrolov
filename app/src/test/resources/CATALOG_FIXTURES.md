# Recorded catalog pages for 1.0.5

Each `catalog-<SteamAppID>-2026-10-20.json.gz.b64` contains a complete first search page (100 rows), public Digiseller product/data responses, Steam root app details/DLC IDs and, for two unambiguous region/edition examples, real price/calc responses. Missing cards remain null. No purchases or account credentials are involved.

JSON is gzip-compressed and Base64-encoded to keep large repeated HTML descriptions compact while retaining all content used by production classification. Decode using Python:

```python
import base64, gzip, json, pathlib
p = pathlib.Path('catalog-289070-2026-10-20.json.gz.b64')
recording = json.loads(gzip.decompress(base64.b64decode(p.read_text())))
```

`base_game` is a manually reviewed golden annotation of first-game offers; it does not imply verified delivery, stock, region or variant price. The test also requires known valid keys to remain for Civilization VI and Cities: Skylines. The Sims 4 page has no verified base-game Steam Gift/Key: its Steam rows are services/accounts or unavailable cards, and most other rows concern EA App/DLC.

The test substitutes recorded responses at the HTTP boundary, then exercises the production classifier, enrichment, stock and price validation, and visible-offer filter.
