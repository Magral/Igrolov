#!/usr/bin/env python3
"""Validate sanitized Digiseller probe output; missing category evidence is not an API failure."""
import collections
import json
import os
import sys
from pathlib import Path


def validate(report):
    errors = []
    samples = report.get("seller_samples")
    if not isinstance(samples, list):
        return ["seller_samples must be an array"], {}
    counts = collections.Counter()
    for i, sample in enumerate(samples, 1):
        match = sample.get("matched_category", {})
        found = match.get("found")
        number = match.get("matches")
        kind = match.get("kind", "Unknown")
        if not isinstance(found, bool) or not isinstance(number, int) or number < 0:
            errors.append(f"sample {i}: invalid match fields")
            continue
        if found != (number == 1):
            errors.append(f"sample {i}: inconsistent exact match count")
        if kind not in ("Gift", "Key", "Excluded", "Unknown"):
            errors.append(f"sample {i}: unsupported category kind")
        if not found and kind != "Unknown":
            errors.append(f"sample {i}: unverified category must remain Unknown")
        if found and not match.get("name"):
            errors.append(f"sample {i}: matched category has no name")
        counts[kind] += 1
    return errors, counts


def main():
    path = Path(sys.argv[1] if len(sys.argv) > 1 else "digiseller_category_probe.json")
    report = json.loads(path.read_text(encoding="utf-8"))
    errors, counts = validate(report)
    api_errors = report.get("errors", [])
    if not isinstance(api_errors, list):
        errors.append("errors must be an array")
        api_errors = []
    summary = [
        "## Digiseller category probe",
        f"- Seller samples: {len(report.get('seller_samples', []))}",
        f"- Verified Gift: {counts['Gift']}",
        f"- Verified Key: {counts['Key']}",
        f"- Verified account/rental: {counts['Excluded']}",
        f"- Unknown / unverified: {counts['Unknown']}",
        f"- API probe errors: {len(api_errors)}",
        f"- Validation: {'FAILED' if errors else 'PASSED'}",
        "",
        "Unknown categories are expected and must not be guessed as Gift or Key.",
    ]
    if api_errors:
        summary.append("API error types: " + ", ".join(str(e).split(":")[0][:60] for e in api_errors[:10]))
    if errors:
        summary.extend("- ERROR: " + e for e in errors)
    message = "\n".join(summary) + "\n"
    print(message)
    if os.environ.get("GITHUB_STEP_SUMMARY"):
        with open(os.environ["GITHUB_STEP_SUMMARY"], "a", encoding="utf-8") as out:
            out.write(message)
    return 1 if errors or api_errors else 0


if __name__ == "__main__":
    sys.exit(main())
