# 0.9.7 Stable — region and delivery title parsing

- Retains 2 x 100 catalog paging and search / watch progress from 0.9.6.
- Title tokenization handles typographic apostrophes and punctuation, including Dragon´s / Dragon's / Dragons.
- Russian region labels are recognized as individual tokens, including RU, RUS, РУ, РФ, Russia, Россия, and mixed lists with separators.
- Explicit exclusion labels override positive labels. Russian-language-only labels do not confirm the region.
- Unspecified delivery method is kept as "Не указан" in all-type searches; never misrepresented as Gift or Key. Gift/Key-specific filters require explicit title evidence.
- Manual search shows unconfirmed-region results with a warning; monitoring uses only titles that explicitly confirm Russia.
- DLC matching uses explicit DLC markers and known Steam DLC names when available.
- Basic counts of region status shown on search results.

Limitations: catalog listings do not guarantee stock or price for the Russian purchase variant. DLC listings that omit the base game name may not be returned by a base-title catalog query. APK build not verified in this environment.
