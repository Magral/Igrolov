#!/usr/bin/env python3
"""Offline regression tests for sanitized probe validation."""
import unittest
from validate_digiseller_probe import validate


class ProbeValidationTests(unittest.TestCase):
    def test_exact_match_key(self):
        errors, counts = validate({"seller_samples": [{"matched_category": {
            "found": True, "matches": 1, "name": "Стим Ключи", "kind": "Key"}}]})
        self.assertEqual(errors, [])
        self.assertEqual(counts["Key"], 1)

    def test_zero_category_is_unknown(self):
        errors, counts = validate({"seller_samples": [{"matched_category": {
            "found": False, "matches": 0, "name": None, "kind": "Unknown"}}]})
        self.assertEqual(errors, [])
        self.assertEqual(counts["Unknown"], 1)

    def test_unverified_gift_rejected(self):
        errors, _ = validate({"seller_samples": [{"matched_category": {
            "found": False, "matches": 0, "kind": "Gift"}}]})
        self.assertTrue(errors)

    def test_ambiguous_duplicate_rejected(self):
        errors, _ = validate({"seller_samples": [{"matched_category": {
            "found": True, "matches": 2, "name": "Steam Gifts", "kind": "Gift"}}]})
        self.assertTrue(errors)

    def test_missing_samples_rejected(self):
        errors, _ = validate({})
        self.assertTrue(errors)


if __name__ == "__main__":
    unittest.main()
