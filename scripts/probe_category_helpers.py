"""Pure helpers shared by the live category probe and offline regression tests."""

def select_seller_samples(items, existing, limit=2):
    """Reserve a per-query quota; early queries must not consume the whole sample."""
    seen = {seller for _, seller in existing}
    selected = []
    for item in items:
        if not isinstance(item, dict):
            continue
        product = str(item.get("id", item.get("id_goods", "")))
        seller = str(item.get("seller_id", ""))
        if not (product.isascii() and product.isdecimal() and 1 <= len(product) <= 12
                and seller.isascii() and seller.isdecimal() and 1 <= len(seller) <= 12):
            continue
        if seller in seen:
            continue
        selected.append((product, seller))
        seen.add(seller)
        if len(selected) >= limit:
            break
    return selected

def flatten_categories(categories):
    """Include real Digiseller 'sub' children without inventing inherited labels."""
    pending = list(reversed(categories))
    result = []
    while pending:
        category = pending.pop()
        if not isinstance(category, dict):
            continue
        result.append(category)
        children = category.get("sub")
        if isinstance(children, list):
            pending.extend(reversed(children))
    return result
